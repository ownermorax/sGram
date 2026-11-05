package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.telegram.ui.SgramMenuActivity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Обновления sGram с сайта: sgram.xpowl.xyz/version.json → плашка над списком чатов (DialogsActivity),
 * APK качается в files/sgram_update и ставится системным установщиком.
 */
public class SgramUpdater {

    private static final String VERSION_URL = "https://sgram.xpowl.xyz/version.json";
    private static final long CHECK_INTERVAL = 30 * 60 * 1000L;
    private static final String APK_MIME = "application/vnd.android.package-archive";

    public static final int STATE_NONE = 0, STATE_AVAILABLE = 1, STATE_DOWNLOADING = 2, STATE_READY = 3, STATE_ERROR = 4;

    private static volatile int state = STATE_NONE;
    private static volatile String version, apkUrl;
    private static volatile int progress;
    private static volatile boolean checking;
    private static long lastCheck;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE);
    }

    private static File dir() {
        File d = new File(ApplicationLoader.applicationContext.getFilesDir(), "sgram_update");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File apk(String v) {
        return new File(dir(), "sgram-" + v + ".apk");
    }

    public static int getState() {
        return state;
    }

    public static String getVersion() {
        return version;
    }

    public static int getProgress() {
        return progress;
    }

    /** Показывать ли плашку (крестик скрывает только эту версию). */
    public static boolean isBannerVisible() {
        return state != STATE_NONE && version != null && !version.equals(prefs().getString("update_dismissed", null));
    }

    public static void dismiss() {
        if (version != null) prefs().edit().putString("update_dismissed", version).apply();
        notifyChanged();
    }

    /** Вызывается из DialogsActivity.onResume; не чаще раза в полчаса. */
    public static void check() {
        long now = System.currentTimeMillis();
        if (checking || state == STATE_DOWNLOADING || now - lastCheck < CHECK_INTERVAL) return;
        lastCheck = now;
        checking = true;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                JSONObject json = new JSONObject(new String(fetch(VERSION_URL), "UTF-8"));
                String v = json.getString("version").trim();
                String url = json.getString("url").trim();
                cleanup(v);
                if (compare(v, SgramMenuActivity.SGRAM_VERSION) > 0 && url.startsWith("https://")) {
                    if (!v.equals(version) || state == STATE_NONE || state == STATE_ERROR) {
                        version = v;
                        apkUrl = url;
                        state = apk(v).exists() ? STATE_READY : STATE_AVAILABLE;
                        notifyChanged();
                    }
                } else if (state != STATE_NONE) {
                    state = STATE_NONE;
                    version = null;
                    notifyChanged();
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                checking = false;
            }
        });
    }

    /** Нажатие на плашку. */
    public static void onClick(Activity activity) {
        if (state == STATE_AVAILABLE || state == STATE_ERROR) {
            download();
        } else if (state == STATE_READY) {
            install(activity);
        }
    }

    private static void download() {
        final String v = version, url = apkUrl;
        if (v == null || url == null) return;
        state = STATE_DOWNLOADING;
        progress = 0;
        notifyChanged();
        Utilities.globalQueue.postRunnable(() -> {
            File part = new File(dir(), "sgram-" + v + ".apk.part");
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                long total = c.getContentLengthLong();
                long done = 0;
                int lastShown = 0;
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            progress = (int) (done * 100 / total);
                            if (progress - lastShown >= 5) {
                                lastShown = progress;
                                notifyChanged();
                            }
                        }
                    }
                }
                if (total > 0 && done != total) throw new Exception("incomplete " + done + "/" + total);
                if (!part.renameTo(apk(v))) throw new Exception("rename failed");
                state = STATE_READY;
            } catch (Throwable e) {
                FileLog.e(e);
                part.delete();
                state = STATE_ERROR;
            } finally {
                if (c != null) c.disconnect();
            }
            notifyChanged();
            if (state == STATE_READY) {
                AndroidUtilities.runOnUIThread(() -> {
                    Activity a = org.telegram.ui.LaunchActivity.instance;
                    if (a != null) install(a);
                });
            }
        });
    }

    private static void install(Activity activity) {
        File f = version != null ? apk(version) : null;
        if (f == null || !f.exists()) {
            state = STATE_AVAILABLE;
            notifyChanged();
            return;
        }
        AndroidUtilities.openForView(f, f.getName(), APK_MIME, activity, null, false);
    }

    /** Удаляет скачанные APK версий, которые уже не нужны. */
    private static void cleanup(String latest) {
        File[] files = dir().listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.getName().equals("sgram-" + latest + ".apk")) f.delete();
        }
    }

    private static byte[] fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setUseCaches(false);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 65536) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    static int compare(String a, String b) {
        String[] x = a.replaceFirst("^v", "").split("\\."), y = b.replaceFirst("^v", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parse(x[i]) : 0, q = i < y.length ? parse(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private static void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.sgramUpdateState));
    }
}
