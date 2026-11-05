package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * sGram: scam marks from the GiD registry (port of the exteraGram plugin "Скам метка из реестра GiD"
 * by 0patient v Hoffman). Posts of the public channel @GID_ScamBase look like
 * "ID 8877063132 | @username ⚠ Доказательства(link)"; every listed user gets a red line in the profile.
 *
 * Unlike the plugin, the base updates itself: on start and every 6 hours only new posts are
 * fetched (min_id), the whole channel is re-read once a week to drop removed entries.
 */
public final class SgramScamBase {

    public static final String CHANNEL = "GID_ScamBase";
    public static final String PREF_ENABLED = "scam_marks";

    private static final long UPDATE_INTERVAL = 6 * 60 * 60 * 1000L;
    private static final long FULL_INTERVAL = 7 * 24 * 60 * 60 * 1000L;
    private static final Pattern ID_PATTERN = Pattern.compile("ID\\s*:?\\s*(\\d{5,})(?:\\s*\\|\\s*@([A-Za-z0-9_]{3,32}))?");

    public static final class Entry {
        public final long id;
        public final String username;
        public final String proof;

        Entry(long id, String username, String proof) {
            this.id = id;
            this.username = username;
            this.proof = proof;
        }
    }

    public interface Callback {
        void onDone(String error);
    }

    private static final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();
    private static volatile boolean loaded;
    private static volatile boolean updating;
    private static final Runnable periodic = () -> update(false, null);

    private SgramScamBase() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("sgram_scambase", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        return ApplicationLoader.applicationContext.getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE).getBoolean(PREF_ENABLED, true);
    }

    public static void setEnabled(boolean enabled) {
        ApplicationLoader.applicationContext.getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, enabled).apply();
        if (enabled) {
            init();
        }
        notifyUpdated();
    }

    private static File file() {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), "sgram_scambase.tsv");
    }

    /** Called from ApplicationLoader. */
    public static void init() {
        if (!isEnabled()) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            load();
            schedule(15_000);
        });
    }

    private static void schedule(long delay) {
        Utilities.globalQueue.cancelRunnable(periodic);
        long since = System.currentTimeMillis() - prefs().getLong("updated", 0);
        Utilities.globalQueue.postRunnable(periodic, Math.max(delay, UPDATE_INTERVAL - since));
    }

    private static void load() {
        if (loaded) {
            return;
        }
        File f = file();
        if (f.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\t", -1);
                    if (parts.length < 3) {
                        continue;
                    }
                    try {
                        long id = Long.parseLong(parts[0]);
                        entries.put(id, new Entry(id, emptyToNull(parts[1]), emptyToNull(parts[2])));
                    } catch (NumberFormatException ignore) {
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        loaded = true;
        notifyUpdated();
    }

    private static String emptyToNull(String s) {
        return TextUtils.isEmpty(s) ? null : s;
    }

    private static void save(HashMap<Long, Entry> map) {
        File tmp = new File(file().getPath() + ".tmp");
        try (BufferedWriter w = new BufferedWriter(new FileWriter(tmp))) {
            for (Entry e : map.values()) {
                w.write(e.id + "\t" + (e.username != null ? e.username : "") + "\t" + (e.proof != null ? e.proof : "") + "\n");
            }
        } catch (Exception e) {
            FileLog.e(e);
            return;
        }
        tmp.renameTo(file());
    }

    /** @return the registry entry for this user, or null (also when marks are off). */
    public static Entry get(long userId) {
        if (userId <= 0 || !loaded || !isEnabled()) {
            return null;
        }
        return entries.get(userId);
    }

    public static int size() {
        return entries.size();
    }

    public static long lastUpdate() {
        return prefs().getLong("updated", 0);
    }

    public static boolean isUpdating() {
        return updating;
    }

    private static void notifyUpdated() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.sgramScamBaseUpdated));
    }

    private static int pickAccount() {
        if (UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated()) {
            return UserConfig.selectedAccount;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                return a;
            }
        }
        return -1;
    }

    /** Fetches new posts (or the whole channel when full / weekly). */
    public static void update(boolean full, Callback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            load();
            if (updating || !isEnabled()) {
                if (callback != null) AndroidUtilities.runOnUIThread(() -> callback.onDone(updating ? "Already updating" : "Scam marks are off"));
                return;
            }
            int account = pickAccount();
            if (account < 0) {
                schedule(60_000);
                if (callback != null) AndroidUtilities.runOnUIThread(() -> callback.onDone("Not logged in"));
                return;
            }
            boolean fullRun = full || entries.isEmpty() || System.currentTimeMillis() - prefs().getLong("full_updated", 0) > FULL_INTERVAL;
            updating = true;
            notifyUpdated();
            resolve(account, false, (peer, error) -> {
                if (peer == null) {
                    finish(error, callback);
                    return;
                }
                Run run = new Run(account, peer, fullRun ? 0 : prefs().getInt("last_id", 0), fullRun, callback);
                run.next(0);
            });
        });
    }

    private interface PeerCallback {
        void run(TLRPC.InputPeer peer, String error);
    }

    private static void resolve(int account, boolean force, PeerCallback callback) {
        SharedPreferences p = prefs();
        long channelId = p.getLong("channel_id", 0);
        if (!force && channelId != 0) {
            TLRPC.TL_inputPeerChannel peer = new TLRPC.TL_inputPeerChannel();
            peer.channel_id = channelId;
            peer.access_hash = p.getLong("access_hash_" + account, 0);
            if (peer.access_hash != 0) {
                callback.run(peer, null);
                return;
            }
        }
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = CHANNEL;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> Utilities.globalQueue.postRunnable(() -> {
            if (response instanceof TLRPC.TL_contacts_resolvedPeer) {
                TLRPC.TL_contacts_resolvedPeer res = (TLRPC.TL_contacts_resolvedPeer) response;
                for (TLRPC.Chat chat : res.chats) {
                    if (chat.id == res.peer.channel_id) {
                        p.edit().putLong("channel_id", chat.id).putLong("access_hash_" + account, chat.access_hash).apply();
                        TLRPC.TL_inputPeerChannel peer = new TLRPC.TL_inputPeerChannel();
                        peer.channel_id = chat.id;
                        peer.access_hash = chat.access_hash;
                        callback.run(peer, null);
                        return;
                    }
                }
            }
            callback.run(null, error != null ? error.text : "Channel not found");
        }));
    }

    private static final class Run {
        final int account;
        final TLRPC.InputPeer peer;
        final int minId;
        final boolean full;
        final Callback callback;
        final HashMap<Long, Entry> found = new HashMap<>();
        int maxId;
        boolean retried;

        Run(int account, TLRPC.InputPeer peer, int minId, boolean full, Callback callback) {
            this.account = account;
            this.peer = peer;
            this.minId = minId;
            this.full = full;
            this.callback = callback;
        }

        void next(int offsetId) {
            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = peer;
            req.offset_id = offsetId;
            req.limit = 100;
            req.min_id = minId;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> Utilities.globalQueue.postRunnable(() -> {
                if (error != null) {
                    if (!retried && (error.text.startsWith("CHANNEL_INVALID") || error.text.startsWith("PEER_ID_INVALID"))) {
                        retried = true;
                        resolve(account, true, (p, e) -> {
                            if (p == null) {
                                finish(e, callback);
                            } else {
                                ((TLRPC.TL_inputPeerChannel) peer).access_hash = ((TLRPC.TL_inputPeerChannel) p).access_hash;
                                next(offsetId);
                            }
                        });
                        return;
                    }
                    finish(error.text, callback);
                    return;
                }
                if (!(response instanceof TLRPC.messages_Messages)) {
                    finish("Unexpected response", callback);
                    return;
                }
                ArrayList<TLRPC.Message> messages = ((TLRPC.messages_Messages) response).messages;
                int smallest = Integer.MAX_VALUE;
                for (TLRPC.Message m : messages) {
                    smallest = Math.min(smallest, m.id);
                    maxId = Math.max(maxId, m.id);
                    parse(m, found);
                }
                if (messages.isEmpty() || smallest <= minId + 1) {
                    complete();
                } else {
                    final int nextOffset = smallest;
                    Utilities.globalQueue.postRunnable(() -> next(nextOffset), 600);
                }
            }));
        }

        void complete() {
            HashMap<Long, Entry> map = new HashMap<>();
            if (!full) {
                map.putAll(entries);
            }
            map.putAll(found);
            save(map);
            if (full) {
                entries.clear();
            }
            entries.putAll(map);
            SharedPreferences.Editor e = prefs().edit()
                    .putInt("last_id", Math.max(maxId, full ? 0 : prefs().getInt("last_id", 0)))
                    .putLong("updated", System.currentTimeMillis());
            if (full) {
                e.putLong("full_updated", System.currentTimeMillis());
            }
            e.apply();
            finish(null, callback);
        }
    }

    private static void finish(String error, Callback callback) {
        updating = false;
        if (error != null) {
            FileLog.d("sGram scam base: " + error);
        }
        schedule(error != null && error.startsWith("FLOOD_WAIT") ? 60 * 60 * 1000L : 60_000);
        notifyUpdated();
        if (callback != null) {
            AndroidUtilities.runOnUIThread(() -> callback.onDone(error));
        }
    }

    static void parse(TLRPC.Message message, HashMap<Long, Entry> out) {
        String text = message.message;
        if (TextUtils.isEmpty(text) || !text.contains("ID")) {
            return;
        }
        Matcher m = ID_PATTERN.matcher(text);
        ArrayList<long[]> ids = new ArrayList<>();   // {id, start}
        ArrayList<String> usernames = new ArrayList<>();
        while (m.find()) {
            try {
                ids.add(new long[]{Long.parseLong(m.group(1)), m.start()});
                usernames.add(m.group(2));
            } catch (NumberFormatException ignore) {
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        String[] proofs = new String[ids.size()];
        if (message.entities != null) {
            for (TLRPC.MessageEntity entity : message.entities) {
                if (!(entity instanceof TLRPC.TL_messageEntityTextUrl) || entity.url == null || !entity.url.contains("GID_Proof")) {
                    continue;
                }
                // the proof belongs to the closest ID before it (Java strings are UTF-16, like entity offsets)
                for (int i = ids.size() - 1; i >= 0; i--) {
                    if (ids.get(i)[1] < entity.offset) {
                        if (proofs[i] == null) {
                            proofs[i] = entity.url;
                        }
                        break;
                    }
                }
            }
        }
        for (int i = 0; i < ids.size(); i++) {
            long id = ids.get(i)[0];
            out.put(id, new Entry(id, usernames.get(i), proofs[i]));
        }
    }
}
