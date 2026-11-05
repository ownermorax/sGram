package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Своя картинка на карте кошелька. Картинки лежат на xpowl.xyz по id пользователя,
 * поэтому их видят все, у кого sGram. Загрузка подписана initData веб-аппа @gxwai:
 * сервер проверяет подпись Telegram и знает, что картинку ставит сам владелец.
 */
public class SgramCardSkin {

    public static final String PREF_SHOW = "card_skins_show";
    private static final String BASE = "https://xpowl.xyz/sgram-api/card";
    private static final String BOT = "gxwai";
    // Пропорции лицевой стороны карты (WalletCard2DView: 336×205)
    public static final int WIDTH = 1008, HEIGHT = 615;
    private static final long RECHECK_MS = 10 * 60 * 1000L;

    private static final DispatchQueue queue = new DispatchQueue("sgramCardSkin");
    private static final HashMap<Long, Bitmap> bitmaps = new HashMap<>();
    private static final HashMap<Long, Long> checkedAt = new HashMap<>();
    private static final HashMap<Long, Boolean> loading = new HashMap<>();
    // Карты, которые надо перерисовать, когда картинка пользователя изменится (вьюшки держат Runnable сами)
    private static final HashMap<Long, ArrayList<WeakReference<Runnable>>> subscribers = new HashMap<>();

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE);
    }

    public static boolean isShowEnabled() {
        return prefs().getBoolean(PREF_SHOW, true);
    }

    private static File dir() {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "sgram_cards");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static File file(long userId) {
        return new File(dir(), userId + ".jpg");
    }

    /**
     * Картинка из памяти (или null) — вызывать из UI-потока. Если её ещё нет или она устарела,
     * фоном берётся с диска/сервера, и после загрузки вызывается onUpdate.
     */
    /** Нехватка памяти: картинки снова возьмутся с диска при следующем get(). UI-поток. */
    public static void trim() {
        if (loading.isEmpty()) checkedAt.clear();
        bitmaps.clear();
    }

    public static Bitmap get(long userId, Runnable onUpdate) {
        if (userId == 0 || !isShowEnabled()) return null;
        if (onUpdate != null) subscribe(userId, onUpdate);
        Long checked = checkedAt.get(userId);
        if ((checked == null || System.currentTimeMillis() - checked > RECHECK_MS) && loading.get(userId) == null) {
            loading.put(userId, true);
            final boolean fromDisk = checked == null && !bitmaps.containsKey(userId);
            queue.postRunnable(() -> load(userId, fromDisk));
        }
        return bitmaps.get(userId);
    }

    private static void subscribe(long userId, Runnable r) {
        ArrayList<WeakReference<Runnable>> list = subscribers.get(userId);
        if (list == null) subscribers.put(userId, list = new ArrayList<>());
        for (int i = list.size() - 1; i >= 0; i--) {
            Runnable x = list.get(i).get();
            if (x == null) list.remove(i);
            else if (x == r) return;
        }
        list.add(new WeakReference<>(r));
    }

    private static void load(long userId, boolean fromDisk) {
        File f = file(userId);
        Bitmap diskBitmap = null;
        if (fromDisk && f.exists()) {
            diskBitmap = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (diskBitmap != null) {
                Bitmap b = diskBitmap;
                AndroidUtilities.runOnUIThread(() -> publish(userId, b));
            }
        }
        Bitmap result = null;
        boolean changed = false;
        boolean gone = false;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE + "/" + userId + ".jpg").openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            if (f.exists()) conn.setIfModifiedSince(f.lastModified());
            int code = conn.getResponseCode();
            if (code == 200) {
                long modified = conn.getLastModified();
                File tmp = new File(dir(), userId + ".tmp");
                try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                result = BitmapFactory.decodeFile(tmp.getAbsolutePath());
                if (result != null) {
                    tmp.renameTo(f);
                    if (modified > 0) f.setLastModified(modified);
                    changed = true;
                } else {
                    tmp.delete();
                }
            } else if (code == 404) {
                gone = f.exists() || bitmaps.containsKey(userId);
                f.delete();
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        final Bitmap r = result;
        final boolean ch = changed, g = gone;
        AndroidUtilities.runOnUIThread(() -> {
            checkedAt.put(userId, System.currentTimeMillis());
            loading.remove(userId);
            if (ch) publish(userId, r);
            else if (g) publish(userId, null);
        });
    }

    private static void publish(long userId, Bitmap bitmap) {
        if (bitmap == null) bitmaps.remove(userId);
        else bitmaps.put(userId, bitmap);
        ArrayList<WeakReference<Runnable>> list = subscribers.get(userId);
        if (list == null) return;
        for (WeakReference<Runnable> ref : new ArrayList<>(list)) {
            Runnable r = ref.get();
            if (r != null) r.run();
        }
    }

    /** Рисует картинку с обрезкой по центру в прямоугольник w×h. */
    public static void draw(Canvas canvas, Bitmap bitmap, float w, float h, Paint paint) {
        float scale = Math.max(w / bitmap.getWidth(), h / bitmap.getHeight());
        float dw = bitmap.getWidth() * scale, dh = bitmap.getHeight() * scale;
        RectF dst = new RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
        canvas.drawBitmap(bitmap, null, dst, paint);
    }

    public interface Callback {
        void done(String error);
    }

    /** Обрезает картинку под карту, подписывает запрос и загружает на сервер. */
    public static void upload(int account, Uri uri, Callback callback) {
        queue.postRunnable(() -> {
            byte[] jpeg;
            Bitmap preview;
            try {
                Bitmap src;
                try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(in, null, opts);
                    int sample = 1;
                    while (opts.outWidth / (sample * 2) >= WIDTH && opts.outHeight / (sample * 2) >= HEIGHT) sample *= 2;
                    opts = new BitmapFactory.Options();
                    opts.inSampleSize = sample;
                    try (InputStream in2 = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
                        src = BitmapFactory.decodeStream(in2, null, opts);
                    }
                }
                if (src == null) throw new Exception("Can't read image");
                preview = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(preview);
                draw(c, src, WIDTH, HEIGHT, new Paint(Paint.FILTER_BITMAP_FLAG));
                src.recycle();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int quality = 88;
                do {
                    bos.reset();
                    preview.compress(Bitmap.CompressFormat.JPEG, quality, bos);
                    quality -= 10;
                } while (bos.size() > 1400 * 1024 && quality > 30);
                jpeg = bos.toByteArray();
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> callback.done(e.getMessage()));
                return;
            }
            final Bitmap finalPreview = preview;
            withInitData(account, (initData, error) -> queue.postRunnable(() -> {
                if (error != null) {
                    AndroidUtilities.runOnUIThread(() -> callback.done(error));
                    return;
                }
                String err = request("POST", initData, jpeg);
                long selfId = UserConfig.getInstance(account).getClientUserId();
                if (err == null) {
                    try (OutputStream out = new FileOutputStream(file(selfId))) {
                        out.write(jpeg);
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (err == null) {
                        checkedAt.put(selfId, System.currentTimeMillis());
                        publish(selfId, finalPreview);
                    }
                    callback.done(err);
                });
            }));
        });
    }

    public static void remove(int account, Callback callback) {
        withInitData(account, (initData, error) -> queue.postRunnable(() -> {
            String err = error != null ? error : request("DELETE", initData, null);
            long selfId = UserConfig.getInstance(account).getClientUserId();
            if (err == null) file(selfId).delete();
            AndroidUtilities.runOnUIThread(() -> {
                if (err == null) {
                    checkedAt.put(selfId, System.currentTimeMillis());
                    publish(selfId, null);
                }
                callback.done(err);
            });
        }));
    }

    private static String request(String method, String initData, byte[] body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("X-Init-Data", initData);
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "image/jpeg");
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body);
                }
            }
            int code = conn.getResponseCode();
            return code == 200 ? null : "Server error " + code;
        } catch (Exception e) {
            FileLog.e(e);
            return e.getMessage() != null ? e.getMessage() : e.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private interface InitDataCallback {
        void done(String initData, String error);
    }

    // initData веб-аппа бота — подписан Telegram, по нему сервер узнаёт id пользователя
    private static void withInitData(int account, InitDataCallback callback) {
        AndroidUtilities.runOnUIThread(() -> {
            MessagesController mc = MessagesController.getInstance(account);
            TLObject cached = mc.getUserOrChat(BOT);
            if (cached instanceof TLRPC.User) {
                requestWebView(account, (TLRPC.User) cached, callback);
                return;
            }
            TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
            req.username = BOT;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (!(response instanceof TLRPC.TL_contacts_resolvedPeer) || ((TLRPC.TL_contacts_resolvedPeer) response).users.isEmpty()) {
                    callback.done(null, "Can't find @" + BOT + (error != null ? ": " + error.text : ""));
                    return;
                }
                TLRPC.TL_contacts_resolvedPeer res = (TLRPC.TL_contacts_resolvedPeer) response;
                mc.putUsers(res.users, false);
                requestWebView(account, res.users.get(0), callback);
            }));
        });
    }

    private static void requestWebView(int account, TLRPC.User bot, InitDataCallback callback) {
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.TL_messages_requestSimpleWebView req = new TLRPC.TL_messages_requestSimpleWebView();
        req.bot = mc.getInputUser(bot);
        req.url = "https://xpowl.xyz/sgram";
        req.flags |= TLObject.FLAG_3;
        req.platform = "android";
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            String initData = response instanceof TLRPC.TL_webViewResultUrl ? extractInitData(((TLRPC.TL_webViewResultUrl) response).url) : null;
            if (!TextUtils.isEmpty(initData)) {
                callback.done(initData, null);
                return;
            }
            // Запасной путь — обычный веб-апп бота (как кнопка web_app в клавиатуре)
            String first = error != null ? error.text : "no init data";
            TLRPC.TL_messages_requestWebView req2 = new TLRPC.TL_messages_requestWebView();
            req2.peer = mc.getInputPeer(bot.id);
            req2.bot = mc.getInputUser(bot);
            req2.url = "https://xpowl.xyz/sgram";
            req2.flags |= TLObject.FLAG_1;
            req2.platform = "android";
            ConnectionsManager.getInstance(account).sendRequest(req2, (response2, error2) -> {
                String init2 = response2 instanceof TLRPC.TL_webViewResultUrl ? extractInitData(((TLRPC.TL_webViewResultUrl) response2).url) : null;
                if (!TextUtils.isEmpty(init2)) callback.done(init2, null);
                else callback.done(null, "Telegram refused: " + first + " / " + (error2 != null ? error2.text : "no init data"));
            });
        });
    }

    private static String extractInitData(String url) {
        if (url == null) return null;
        int hash = url.indexOf('#');
        if (hash < 0) return null;
        for (String part : url.substring(hash + 1).split("&")) {
            if (part.startsWith("tgWebAppData=")) {
                return Uri.decode(part.substring("tgWebAppData=".length()));
            }
        }
        return null;
    }
}
