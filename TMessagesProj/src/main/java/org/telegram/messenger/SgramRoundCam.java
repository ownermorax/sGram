package org.telegram.messenger;

import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

import org.telegram.messenger.camera.Camera2Session;

/**
 * sGram: Round Video Enhancer — качество и управление камерой для кружков (порт идеи плагина RVCE, без DEX).
 */
public class SgramRoundCam {

    public static final int[] RES = {384, 480, 512, 640};
    public static final int[] VBR = {1000, 1400, 1600, 1800, 2000, 2300};
    public static final int[] ABR = {64, 96, 128, 160, 192, 256, 320};

    public static final String ENABLED = "rc_enabled", RES_IDX = "rc_res", VBR_IDX = "rc_vbr", ABR_IDX = "rc_abr",
            HEVC = "rc_hevc", LOCK_FPS = "rc_lock_fps", WIDE = "rc_wide", DOUBLE_TAP = "rc_double_tap";

    private static Boolean hevcSupported;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("sgram_prefs", android.content.Context.MODE_PRIVATE);
    }

    public static boolean getBool(String key) {
        return prefs().getBoolean(key, !HEVC.equals(key));
    }

    public static void setBool(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
    }

    public static int getIdx(String key, int[] values) {
        int def = key.equals(RES_IDX) ? 3 : key.equals(VBR_IDX) ? 3 : 4;
        int i = prefs().getInt(key, def);
        return i < 0 || i >= values.length ? def : i;
    }

    public static void setIdx(String key, int i) {
        prefs().edit().putInt(key, i).apply();
    }

    public static boolean isEnabled() {
        return getBool(ENABLED);
    }

    public static int size(int def) {
        return isEnabled() ? RES[getIdx(RES_IDX, RES)] : def;
    }

    public static int videoBitrate(int def) {
        return isEnabled() ? VBR[getIdx(VBR_IDX, VBR)] * 1024 : def;
    }

    public static int audioBitrate(int def) {
        return isEnabled() ? ABR[getIdx(ABR_IDX, ABR)] * 1000 : def;
    }

    public static String videoMime() {
        return getBool(HEVC) && isHevcSupported() ? "video/hevc" : "video/avc";
    }

    public static boolean isHevcSupported() {
        if (hevcSupported == null) {
            boolean found = false;
            try {
                for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                    if (!info.isEncoder()) continue;
                    for (String type : info.getSupportedTypes()) {
                        if ("video/hevc".equalsIgnoreCase(type)) {
                            found = true;
                            break;
                        }
                    }
                    if (found) break;
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            hevcSupported = found;
        }
        return hevcSupported;
    }

    public static Camera2Session round(Camera2Session session) {
        if (session != null) {
            session.sgramLockFps = getBool(LOCK_FPS);
            session.sgramWide = getBool(WIDE);
        }
        return session;
    }
}
