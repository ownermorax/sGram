package org.telegram.messenger;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/**
 * История сообщений (как в AyuGram):
 * - удалённые остаются в локальной базе Telegram и в чате с пометкой "deleted", копия — в таблице deleted;
 * - прошлые версии изменённых сообщений — в таблице edits.
 * Записи привязаны к id пользователя (owner_id), а не к номеру слота аккаунта.
 */
public class SgramDeletedController {

    public static final String PREF_SAVE = "deleted_save";
    public static final String PREF_SAVE_EDITS = "edits_save";
    public static final String PREF_SAVE_MEDIA = "deleted_save_media";
    public static final String PREF_LIMIT_MB = "deleted_limit_mb";
    public static final String PREF_EXCLUDED = "deleted_excluded";
    public static final int DEFAULT_LIMIT_MB = 200;
    public static final String DB_NAME = "sgram_deleted.db";
    public static final String MEDIA_DIR = "sgram_deleted";

    private static final SgramDeletedController[] instances = new SgramDeletedController[UserConfig.MAX_ACCOUNT_COUNT];
    private static DbHelper dbHelper;

    private final int currentAccount;
    private long indexOwner;
    // dialogId -> mid
    private final HashMap<Long, HashSet<Integer>> index = new HashMap<>();
    private final HashMap<Long, HashSet<Integer>> editsIndex = new HashMap<>();

    public static class Entry {
        public long dialogId;
        public int mid;
        public int date;
        public int deletedAt;
        public long fromId;
        public boolean out;
        public String chatTitle;
        public String fromName;
        public String text;
        public String mediaType;
        public String mediaPath;
        public long size;
    }

    public static class EditVersion {
        public int date;
        public String text;
        public String mediaType;
    }

    // Сообщение с прошлыми версиями — для вкладки Edited
    public static class EditedEntry {
        public long dialogId;
        public int mid;
        public int versions;
        public int lastSavedAt;
        public String lastText;
        public String chatTitle;
    }

    public static SgramDeletedController getInstance(int account) {
        SgramDeletedController local = instances[account];
        if (local == null) {
            synchronized (SgramDeletedController.class) {
                local = instances[account];
                if (local == null) {
                    instances[account] = local = new SgramDeletedController(account);
                }
            }
        }
        return local;
    }

    private SgramDeletedController(int account) {
        currentAccount = account;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        return prefs().getBoolean(PREF_SAVE, false);
    }

    public static boolean isEditHistoryEnabled() {
        return prefs().getBoolean(PREF_SAVE_EDITS, true);
    }

    public static boolean isSaveMedia() {
        return prefs().getBoolean(PREF_SAVE_MEDIA, true);
    }

    // --- исключённые чаты: удалённые и правки там не сохраняются ---

    private static volatile java.util.Set<String> excludedCache;

    public static java.util.Set<String> getExcluded() {
        java.util.Set<String> set = excludedCache;
        if (set == null) {
            excludedCache = set = new HashSet<>(prefs().getStringSet(PREF_EXCLUDED, new HashSet<>()));
        }
        return set;
    }

    public static boolean isExcluded(long dialogId) {
        return getExcluded().contains(String.valueOf(dialogId));
    }

    public static void setExcluded(long dialogId, boolean excluded) {
        HashSet<String> set = new HashSet<>(getExcluded());
        if (excluded) set.add(String.valueOf(dialogId));
        else set.remove(String.valueOf(dialogId));
        prefs().edit().putStringSet(PREF_EXCLUDED, set).apply();
        excludedCache = set;
    }

    /** Сколько сохранено удалённых в этом чате (все аккаунты). */
    public static int countForDialog(long dialogId) {
        try (Cursor c = db().rawQuery("SELECT (SELECT COUNT(*) FROM deleted WHERE dialog_id = ?) + (SELECT COUNT(*) FROM edits WHERE dialog_id = ?)",
                new String[]{String.valueOf(dialogId), String.valueOf(dialogId)})) {
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    /** Убирает всё сохранённое по чату: удалённые исчезают и из самого чата. */
    public static void clearDialog(long dialogId) {
        ArrayList<long[]> victims = new ArrayList<>();
        try (Cursor c = db().rawQuery("SELECT owner_id, mid, upd_key FROM deleted WHERE dialog_id = ?", new String[]{String.valueOf(dialogId)})) {
            while (c.moveToNext()) {
                victims.add(new long[]{c.getLong(0), dialogId, c.getInt(1), c.getLong(2)});
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        removeEntries(victims);
        try {
            db().execSQL("DELETE FROM edits WHERE dialog_id = " + dialogId);
        } catch (Exception e) {
            FileLog.e(e);
        }
        reloadIndexes();
    }

    public static int getLimitMb() {
        return prefs().getInt(PREF_LIMIT_MB, DEFAULT_LIMIT_MB);
    }

    public static void setLimitMb(int mb) {
        prefs().edit().putInt(PREF_LIMIT_MB, mb).apply();
        Utilities.globalQueue.postRunnable(SgramDeletedController::trimToLimit);
    }

    static synchronized SQLiteDatabase db() {
        if (dbHelper == null) {
            dbHelper = new DbHelper(ApplicationLoader.applicationContext);
        }
        return dbHelper.getWritableDatabase();
    }

    static File mediaDir() {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), MEDIA_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private long owner() {
        return UserConfig.getInstance(currentAccount).getClientUserId();
    }

    private static int accountForOwner(long ownerId) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig uc = UserConfig.getInstance(a);
            if (uc.isClientActivated() && uc.getClientUserId() == ownerId) return a;
        }
        return -1;
    }

    // Вызывается из UI-потока при отрисовке, поэтому индекс в памяти
    public boolean isDeleted(long dialogId, int mid) {
        ensureIndex();
        synchronized (index) {
            HashSet<Integer> set = index.get(dialogId);
            return set != null && set.contains(mid);
        }
    }

    public boolean hasEditHistory(long dialogId, int mid) {
        ensureIndex();
        synchronized (index) {
            HashSet<Integer> set = editsIndex.get(dialogId);
            return set != null && set.contains(mid);
        }
    }

    private void ensureIndex() {
        long ownerId = owner();
        if (ownerId == 0) return;
        synchronized (index) {
            if (indexOwner == ownerId) return;
            index.clear();
            editsIndex.clear();
            String[] args = {String.valueOf(ownerId)};
            try (Cursor c = db().rawQuery("SELECT dialog_id, mid FROM deleted WHERE owner_id = ?", args)) {
                while (c.moveToNext()) addTo(index, c.getLong(0), c.getInt(1));
            } catch (Exception e) {
                FileLog.e(e);
            }
            try (Cursor c = db().rawQuery("SELECT DISTINCT dialog_id, mid FROM edits WHERE owner_id = ?", args)) {
                while (c.moveToNext()) addTo(editsIndex, c.getLong(0), c.getInt(1));
            } catch (Exception e) {
                FileLog.e(e);
            }
            indexOwner = ownerId;
        }
    }

    private static void addTo(HashMap<Long, HashSet<Integer>> map, long dialogId, int mid) {
        HashSet<Integer> set = map.get(dialogId);
        if (set == null) {
            map.put(dialogId, set = new HashSet<>());
        }
        set.add(mid);
    }

    private static void removeFrom(HashMap<Long, HashSet<Integer>> map, long dialogId, int mid) {
        HashSet<Integer> set = map.get(dialogId);
        if (set != null) {
            set.remove(mid);
            if (set.isEmpty()) map.remove(dialogId);
        }
    }

    private static TLRPC.Message readMessage(SQLiteCursor cursor, int column, long selfId) throws Exception {
        NativeByteBuffer data = cursor.byteBufferValue(column);
        if (data == null) return null;
        TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
        if (message != null) {
            message.readAttachPath(data, selfId);
        }
        data.reuse();
        return message;
    }

    private static byte[] serialize(TLRPC.Message message) {
        SerializedData sd = new SerializedData(message.getObjectSize());
        message.serializeToStream(sd);
        byte[] bytes = sd.toByteArray();
        sd.cleanup();
        return bytes;
    }

    /**
     * Вызывается в очереди MessagesStorage вместо markMessagesAsDeleted.
     * key: 0 для личек и обычных групп, -channelId для каналов.
     * Возвращает id, которых нет в локальной базе (их удаляем как обычно).
     */
    public ArrayList<Integer> onMessagesDeleted(long key, ArrayList<Integer> ids) {
        ensureIndex();
        ArrayList<Integer> unknown = new ArrayList<>(ids);
        HashMap<Long, ArrayList<Integer>> saved = new HashMap<>();
        long selfId = owner();
        MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
        SQLiteCursor cursor = null;
        try {
            String idsStr = TextUtils.join(",", ids);
            if (key != 0) {
                cursor = storage.getDatabase().queryFinalized(String.format(Locale.US, "SELECT uid, data, mid FROM messages_v2 WHERE mid IN(%s) AND uid = %d", idsStr, key));
            } else {
                cursor = storage.getDatabase().queryFinalized(String.format(Locale.US, "SELECT uid, data, mid FROM messages_v2 WHERE mid IN(%s) AND is_channel = 0", idsStr));
            }
            int now = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
            SQLiteDatabase db = db();
            db.beginTransaction();
            try {
                while (cursor.next()) {
                    long did = cursor.longValue(0);
                    int mid = cursor.intValue(2);
                    // Избранное чистит сам пользователь — не храним; исключённые чаты тоже
                    if (did == selfId || isExcluded(did)) continue;
                    TLRPC.Message message = readMessage(cursor, 1, selfId);
                    if (message == null || message instanceof TLRPC.TL_messageService) continue;
                    unknown.remove((Integer) mid);
                    if (isDeleted(did, mid)) continue;

                    byte[] bytes = serialize(message);
                    String mediaPath = null;
                    long size = bytes.length;
                    if (isSaveMedia() && message.media != null) {
                        File copy = copyMedia(message, did, mid);
                        if (copy != null) {
                            mediaPath = copy.getAbsolutePath();
                            size += copy.length();
                        }
                    }

                    long fromId = MessageObject.getFromChatId(message);
                    ContentValues v = new ContentValues();
                    v.put("owner_id", selfId);
                    v.put("account", currentAccount);
                    v.put("dialog_id", did);
                    v.put("mid", mid);
                    v.put("upd_key", key);
                    v.put("date", message.date);
                    v.put("deleted_at", now);
                    v.put("from_id", fromId);
                    v.put("out", message.out ? 1 : 0);
                    v.put("chat_title", peerName(did));
                    v.put("from_name", peerName(fromId));
                    v.put("text", message.message);
                    v.put("media_type", mediaType(message));
                    v.put("media_path", mediaPath);
                    v.put("size", size);
                    v.put("data", bytes);
                    db.insertWithOnConflict("deleted", null, v, SQLiteDatabase.CONFLICT_REPLACE);

                    synchronized (index) {
                        addTo(index, did, mid);
                    }
                    ArrayList<Integer> list = saved.get(did);
                    if (list == null) saved.put(did, list = new ArrayList<>());
                    list.add(mid);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) cursor.dispose();
        }
        if (!saved.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> {
                for (HashMap.Entry<Long, ArrayList<Integer>> e : saved.entrySet()) {
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.sgramMessagesMarkedDeleted, e.getKey(), e.getValue());
                }
            });
            trimToLimit();
        }
        return unknown;
    }

    /**
     * Вызывается в очереди MessagesStorage до того, как новая версия перезапишет старую.
     * Сохраняет прошлую версию, если поменялся текст или медиа (а не только реакции/просмотры).
     */
    public void onMessagesEdited(long dialogId, ArrayList<TLRPC.Message> edited) {
        ensureIndex();
        long selfId = owner();
        if (dialogId == selfId || isExcluded(dialogId)) return;
        MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
        int now = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
        boolean added = false;
        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            for (TLRPC.Message msg : edited) {
                if (msg == null || msg.id <= 0) continue;
                TLRPC.Message old = null;
                SQLiteCursor cursor = null;
                try {
                    cursor = storage.getDatabase().queryFinalized(String.format(Locale.US, "SELECT data FROM messages_v2 WHERE uid = %d AND mid = %d", dialogId, msg.id));
                    if (cursor.next()) old = readMessage(cursor, 0, selfId);
                } finally {
                    if (cursor != null) cursor.dispose();
                }
                if (old == null || old instanceof TLRPC.TL_messageService) continue;
                if (TextUtils.equals(old.message, msg.message) && mediaId(old) == mediaId(msg)) continue;
                byte[] bytes = serialize(old);
                ContentValues v = new ContentValues();
                v.put("owner_id", selfId);
                v.put("dialog_id", dialogId);
                v.put("mid", msg.id);
                v.put("version_date", old.edit_date != 0 ? old.edit_date : old.date);
                v.put("saved_at", now);
                v.put("text", old.message);
                v.put("media_type", mediaType(old));
                v.put("size", bytes.length);
                v.put("data", bytes);
                db.insert("edits", null, v);
                synchronized (index) {
                    addTo(editsIndex, dialogId, msg.id);
                }
                added = true;
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            db.endTransaction();
        }
        if (added) trimToLimit();
    }

    public ArrayList<EditVersion> loadEdits(long dialogId, int mid) {
        ArrayList<EditVersion> result = new ArrayList<>();
        try (Cursor c = db().rawQuery("SELECT version_date, text, media_type FROM edits WHERE owner_id = ? AND dialog_id = ? AND mid = ? ORDER BY version_date ASC, rowid ASC",
                new String[]{String.valueOf(owner()), String.valueOf(dialogId), String.valueOf(mid)})) {
            while (c.moveToNext()) {
                EditVersion v = new EditVersion();
                v.date = c.getInt(0);
                v.text = c.getString(1);
                v.mediaType = c.getString(2);
                result.add(v);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    // Последние правки сверху; текст — самая свежая из прошлых версий
    public ArrayList<EditedEntry> loadEditedEntries() {
        ArrayList<EditedEntry> result = new ArrayList<>();
        try (Cursor c = db().rawQuery("SELECT dialog_id, mid, COUNT(*), MAX(saved_at), " +
                        "(SELECT text FROM edits e2 WHERE e2.owner_id = e.owner_id AND e2.dialog_id = e.dialog_id AND e2.mid = e.mid ORDER BY version_date DESC, rowid DESC LIMIT 1) " +
                        "FROM edits e WHERE owner_id = ? GROUP BY dialog_id, mid ORDER BY MAX(saved_at) DESC",
                new String[]{String.valueOf(owner())})) {
            while (c.moveToNext()) {
                EditedEntry e = new EditedEntry();
                e.dialogId = c.getLong(0);
                e.mid = c.getInt(1);
                e.versions = c.getInt(2);
                e.lastSavedAt = c.getInt(3);
                e.lastText = c.getString(4);
                e.chatTitle = peerName(e.dialogId);
                result.add(e);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    // Текущая версия из базы Telegram; вызывать в очереди MessagesStorage
    public TLRPC.Message loadCurrentMessage(long dialogId, int mid) {
        SQLiteCursor cursor = null;
        try {
            cursor = MessagesStorage.getInstance(currentAccount).getDatabase().queryFinalized(String.format(Locale.US, "SELECT data FROM messages_v2 WHERE uid = %d AND mid = %d", dialogId, mid));
            if (cursor.next()) return readMessage(cursor, 0, owner());
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) cursor.dispose();
        }
        return null;
    }

    public void removeEdits(long dialogId, int mid) {
        try {
            db().delete("edits", "owner_id = ? AND dialog_id = ? AND mid = ?", new String[]{String.valueOf(owner()), String.valueOf(dialogId), String.valueOf(mid)});
        } catch (Exception e) {
            FileLog.e(e);
        }
        synchronized (index) {
            removeFrom(editsIndex, dialogId, mid);
        }
    }

    private static long mediaId(TLRPC.Message m) {
        if (m.media == null) return 0;
        if (m.media.photo != null) return m.media.photo.id;
        if (m.media.document != null) return m.media.document.id;
        return m.media.getClass().hashCode();
    }

    private String peerName(long peerId) {
        MessagesController mc = MessagesController.getInstance(currentAccount);
        if (peerId > 0) {
            TLRPC.User user = mc.getUser(peerId);
            return user != null ? UserObject.getUserName(user) : null;
        } else if (peerId < 0) {
            TLRPC.Chat chat = mc.getChat(-peerId);
            return chat != null ? chat.title : null;
        }
        return null;
    }

    private static String mediaType(TLRPC.Message message) {
        if (message.media == null || message.media instanceof TLRPC.TL_messageMediaEmpty) return null;
        if (message.media instanceof TLRPC.TL_messageMediaWebPage) return null;
        if (message.media instanceof TLRPC.TL_messageMediaPhoto) return "Photo";
        if (MessageObject.isRoundVideoMessage(message)) return "Video message";
        if (MessageObject.isVoiceMessage(message)) return "Voice message";
        if (MessageObject.isStickerMessage(message) || MessageObject.isAnimatedStickerMessage(message)) return "Sticker";
        if (MessageObject.isGifMessage(message)) return "GIF";
        if (MessageObject.isVideoMessage(message)) return "Video";
        if (MessageObject.isMusicMessage(message)) return "Music";
        if (message.media instanceof TLRPC.TL_messageMediaDocument) return "File";
        if (message.media instanceof TLRPC.TL_messageMediaGeo || message.media instanceof TLRPC.TL_messageMediaVenue || message.media instanceof TLRPC.TL_messageMediaGeoLive) return "Location";
        if (message.media instanceof TLRPC.TL_messageMediaContact) return "Contact";
        if (message.media instanceof TLRPC.TL_messageMediaPoll) return "Poll";
        return "Media";
    }

    private File copyMedia(TLRPC.Message message, long did, int mid) {
        try {
            File src = FileLoader.getInstance(currentAccount).getPathToMessage(message, false);
            if (src == null || !src.exists() || src.length() == 0) {
                if (!TextUtils.isEmpty(message.attachPath)) {
                    src = new File(message.attachPath);
                }
            }
            if (src == null || !src.exists() || src.length() == 0) return null;
            String name = src.getName();
            int dot = name.lastIndexOf('.');
            String ext = dot >= 0 ? name.substring(dot) : "";
            File dst = new File(mediaDir(), owner() + "_" + did + "_" + mid + ext);
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return dst;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    // Список для экрана просмотра, новые сверху
    public ArrayList<Entry> loadEntries() {
        ArrayList<Entry> result = new ArrayList<>();
        try (Cursor c = db().rawQuery("SELECT dialog_id, mid, date, deleted_at, from_id, out, chat_title, from_name, text, media_type, media_path, size FROM deleted WHERE owner_id = ? ORDER BY deleted_at DESC, mid DESC",
                new String[]{String.valueOf(owner())})) {
            while (c.moveToNext()) {
                Entry e = new Entry();
                e.dialogId = c.getLong(0);
                e.mid = c.getInt(1);
                e.date = c.getInt(2);
                e.deletedAt = c.getInt(3);
                e.fromId = c.getLong(4);
                e.out = c.getInt(5) != 0;
                e.chatTitle = c.getString(6);
                e.fromName = c.getString(7);
                e.text = c.getString(8);
                e.mediaType = c.getString(9);
                e.mediaPath = c.getString(10);
                e.size = c.getLong(11);
                result.add(e);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    // [удалённых, изменённых сообщений] текущего аккаунта (как на экране истории) + байты всего кэша
    public static long[] getStatsFor(long ownerId) {
        long[] r = getStats();
        String[] args = {String.valueOf(ownerId), String.valueOf(ownerId)};
        try (Cursor c = db().rawQuery("SELECT (SELECT COUNT(*) FROM deleted WHERE owner_id = ?), " +
                "(SELECT COUNT(*) FROM (SELECT DISTINCT dialog_id, mid FROM edits WHERE owner_id = ?))", args)) {
            if (c.moveToFirst()) {
                r[0] = c.getLong(0);
                r[1] = c.getLong(1);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return r;
    }

    // Записи, которые не видны ни в одном аккаунте (owner_id = 0 или вышедший аккаунт), только занимают место
    public static void purgeOrphans() {
        ArrayList<String> owners = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig uc = UserConfig.getInstance(a);
            if (uc.isClientActivated() && uc.getClientUserId() != 0) owners.add(String.valueOf(uc.getClientUserId()));
        }
        if (owners.isEmpty()) return;
        String in = TextUtils.join(",", owners);
        ArrayList<long[]> victims = new ArrayList<>();
        try (Cursor c = db().rawQuery("SELECT owner_id, dialog_id, mid, upd_key FROM deleted WHERE owner_id NOT IN (" + in + ")", null)) {
            while (c.moveToNext()) victims.add(new long[]{c.getLong(0), c.getLong(1), c.getInt(2), c.getLong(3)});
        } catch (Exception e) {
            FileLog.e(e);
        }
        removeEntries(victims);
        try {
            db().execSQL("DELETE FROM edits WHERE owner_id NOT IN (" + in + ")");
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // [удалённых, изменений, байты] по всем аккаунтам
    public static long[] getStats() {
        long[] r = {0, 0, 0};
        try (Cursor c = db().rawQuery("SELECT (SELECT COUNT(*) FROM deleted), (SELECT COUNT(*) FROM edits), " +
                "(SELECT COALESCE(SUM(size), 0) FROM deleted) + (SELECT COALESCE(SUM(size), 0) FROM edits)", null)) {
            if (c.moveToFirst()) {
                r[0] = c.getLong(0);
                r[1] = c.getLong(1);
                r[2] = c.getLong(2);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return r;
    }

    // Самые старые записи (удалённые и версии) вылетают первыми, пока общий размер больше лимита
    private static synchronized void trimToLimit() {
        int limitMb = getLimitMb();
        if (limitMb <= 0) return;
        long limit = limitMb * 1024L * 1024L;
        long total = getStats()[2];
        if (total <= limit) return;
        ArrayList<long[]> deletedVictims = new ArrayList<>();
        ArrayList<Long> editVictims = new ArrayList<>();
        try (Cursor c = db().rawQuery("SELECT 0, owner_id, dialog_id, mid, upd_key, size, deleted_at AS t FROM deleted " +
                "UNION ALL SELECT 1, owner_id, dialog_id, mid, rowid, size, saved_at AS t FROM edits ORDER BY t ASC", null)) {
            while (c.moveToNext() && total > limit) {
                if (c.getInt(0) == 0) {
                    deletedVictims.add(new long[]{c.getLong(1), c.getLong(2), c.getInt(3), c.getLong(4)});
                } else {
                    editVictims.add(c.getLong(4));
                }
                total -= c.getLong(5);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        removeEdits(editVictims);
        removeEntries(deletedVictims);
    }

    private static void removeEdits(ArrayList<Long> rowIds) {
        if (rowIds.isEmpty()) return;
        try {
            db().execSQL("DELETE FROM edits WHERE rowid IN (" + TextUtils.join(",", rowIds) + ")");
        } catch (Exception e) {
            FileLog.e(e);
        }
        reloadIndexes();
    }

    private static void reloadIndexes() {
        for (SgramDeletedController c : instances) {
            if (c == null) continue;
            synchronized (c.index) {
                c.indexOwner = 0;
            }
        }
    }

    // Очистка кэша: копии удаляются, а сами удалённые сообщения пропадают из чатов
    public static void clearAll() {
        ArrayList<long[]> victims = new ArrayList<>();
        try (Cursor c = db().rawQuery("SELECT owner_id, dialog_id, mid, upd_key FROM deleted", null)) {
            while (c.moveToNext()) {
                victims.add(new long[]{c.getLong(0), c.getLong(1), c.getInt(2), c.getLong(3)});
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        removeEntries(victims);
        try {
            db().execSQL("DELETE FROM edits");
        } catch (Exception e) {
            FileLog.e(e);
        }
        reloadIndexes();
        File[] files = mediaDir().listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
    }

    public void removeEntry(long dialogId, int mid) {
        long updKey = 0;
        String[] args = {String.valueOf(owner()), String.valueOf(dialogId), String.valueOf(mid)};
        try (Cursor c = db().rawQuery("SELECT upd_key FROM deleted WHERE owner_id = ? AND dialog_id = ? AND mid = ?", args)) {
            if (c.moveToFirst()) updKey = c.getLong(0);
        } catch (Exception e) {
            FileLog.e(e);
        }
        ArrayList<long[]> victims = new ArrayList<>();
        victims.add(new long[]{owner(), dialogId, mid, updKey});
        removeEntries(victims);
    }

    private static void removeEntries(ArrayList<long[]> victims) {
        if (victims.isEmpty()) return;
        // account -> updKey -> mids
        HashMap<Integer, HashMap<Long, ArrayList<Integer>>> byAccount = new HashMap<>();
        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            for (long[] v : victims) {
                long ownerId = v[0];
                long dialogId = v[1];
                int mid = (int) v[2];
                long updKey = v[3];
                String[] args = {String.valueOf(ownerId), String.valueOf(dialogId), String.valueOf(mid)};
                try (Cursor c = db.rawQuery("SELECT media_path FROM deleted WHERE owner_id = ? AND dialog_id = ? AND mid = ?", args)) {
                    if (c.moveToFirst() && !c.isNull(0)) new File(c.getString(0)).delete();
                }
                db.delete("deleted", "owner_id = ? AND dialog_id = ? AND mid = ?", args);
                int account = accountForOwner(ownerId);
                if (account < 0) continue;
                SgramDeletedController controller = getInstance(account);
                synchronized (controller.index) {
                    removeFrom(controller.index, dialogId, mid);
                }
                HashMap<Long, ArrayList<Integer>> byKey = byAccount.get(account);
                if (byKey == null) byAccount.put(account, byKey = new HashMap<>());
                ArrayList<Integer> mids = byKey.get(updKey);
                if (mids == null) byKey.put(updKey, mids = new ArrayList<>());
                mids.add(mid);
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            db.endTransaction();
        }
        // Теперь удаляем по-настоящему, как сделал бы Telegram
        for (HashMap.Entry<Integer, HashMap<Long, ArrayList<Integer>>> a : byAccount.entrySet()) {
            int account = a.getKey();
            MessagesStorage storage = MessagesStorage.getInstance(account);
            for (HashMap.Entry<Long, ArrayList<Integer>> k : a.getValue().entrySet()) {
                long key = k.getKey();
                ArrayList<Integer> mids = k.getValue();
                storage.getStorageQueue().postRunnable(() -> {
                    ArrayList<Long> dialogIds = storage.markMessagesAsDeleted(key, mids, false, true, 0, 0);
                    storage.updateDialogsWithDeletedMessages(key, -key, mids, dialogIds);
                    AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messagesDeleted, mids, -key, false));
                });
            }
        }
    }

    private static class DbHelper extends SQLiteOpenHelper {
        DbHelper(Context context) {
            super(context, DB_NAME, null, 2);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE deleted (" +
                    "account INTEGER, dialog_id INTEGER, mid INTEGER, upd_key INTEGER, date INTEGER, deleted_at INTEGER, " +
                    "from_id INTEGER, out INTEGER, chat_title TEXT, from_name TEXT, text TEXT, media_type TEXT, media_path TEXT, " +
                    "size INTEGER, data BLOB, owner_id INTEGER DEFAULT 0, PRIMARY KEY (account, dialog_id, mid))");
            db.execSQL("CREATE INDEX deleted_at_idx ON deleted (deleted_at)");
            createV2(db);
        }

        private void createV2(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS edits (" +
                    "owner_id INTEGER, dialog_id INTEGER, mid INTEGER, version_date INTEGER, saved_at INTEGER, " +
                    "text TEXT, media_type TEXT, size INTEGER, data BLOB)");
            db.execSQL("CREATE INDEX IF NOT EXISTS edits_msg_idx ON edits (owner_id, dialog_id, mid)");
            db.execSQL("CREATE INDEX IF NOT EXISTS deleted_owner_idx ON deleted (owner_id, dialog_id, mid)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE deleted ADD COLUMN owner_id INTEGER DEFAULT 0");
                // В v1 записи были привязаны к номеру слота — переводим на id пользователя
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    UserConfig uc = UserConfig.getInstance(a);
                    if (!uc.isClientActivated()) continue;
                    db.execSQL("UPDATE deleted SET owner_id = " + uc.getClientUserId() + " WHERE account = " + a);
                }
                createV2(db);
            }
        }
    }
}
