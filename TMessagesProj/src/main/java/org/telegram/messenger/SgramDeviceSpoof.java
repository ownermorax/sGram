package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.InputSerializedData;
import org.telegram.tgnet.OutputSerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.zip.CRC32;

/**
 * Подмена device_model / system_version / app_version на серверах Telegram
 * (по мотивам плагина Device Fingerprint Spoofer by Daxo-Developer @Daxo_OS).
 * На старте — подмена аргументов ConnectionsManager.init; без перезапуска — сырой
 * invokeWithLayer(initConnection(help.getNearestDc)) от каждого аккаунта.
 */
public class SgramDeviceSpoof {

    public static final String PREF_ENABLED = "spoof_enabled";
    public static final String PREF_PROFILE = "spoof_profile";
    public static final String PREF_MODEL = "spoof_model";
    public static final String PREF_SYSVER = "spoof_sysver";
    public static final String PREF_SPOOF_APP = "spoof_app";
    public static final String PREF_APPVER = "spoof_appver";
    public static final String DEFAULT_APPVER = "12.1.1";

    // Название, device_model, SDK
    public static final String[][] PRESETS = {
            {"Samsung Galaxy S24 Ultra", "samsungSM-S928B", "34"},
            {"Samsung Galaxy S23", "samsungSM-S911B", "34"},
            {"Samsung Galaxy A54", "samsungSM-A546B", "34"},
            {"Google Pixel 8 Pro", "GooglePixel 8 Pro", "35"},
            {"Google Pixel 7", "GooglePixel 7", "34"},
            {"Xiaomi 14", "Xiaomi23127PN0CG", "34"},
            {"POCO F5", "Xiaomi23049PCD8G", "34"},
            {"OnePlus 12", "OnePlusCPH2573", "34"},
            {"Nothing Phone (2)", "NothingA065", "34"},
            {"Sony Xperia 1 V", "SonyXQ-DQ72", "34"},
            {"Motorola Edge 40", "motorolamotorola edge 40", "34"},
    };
    // Индекс профиля PRESETS.length — свой

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        return prefs().getBoolean(PREF_ENABLED, false);
    }

    public static int getProfile() {
        return prefs().getInt(PREF_PROFILE, 0);
    }

    public static boolean isCustom() {
        return getProfile() >= PRESETS.length;
    }

    public static String profileName() {
        int p = getProfile();
        return p < PRESETS.length ? PRESETS[p][0] : "Custom";
    }

    /** {model, sysver, appver или null — не менять} */
    public static String[] spoofed() {
        SharedPreferences p = prefs();
        int idx = getProfile();
        String model, sysver;
        if (idx < PRESETS.length) {
            model = PRESETS[idx][1];
            sysver = "SDK " + PRESETS[idx][2];
        } else {
            model = p.getString(PREF_MODEL, PRESETS[0][1]);
            sysver = p.getString(PREF_SYSVER, "SDK 34");
        }
        String app = p.getBoolean(PREF_SPOOF_APP, true) ? p.getString(PREF_APPVER, DEFAULT_APPVER) : null;
        return new String[]{model, sysver, app};
    }

    // Настоящие значения — как их считает ConnectionsManager
    private static String[] real;

    public static String[] realValues() {
        if (real != null) return real;
        String model = Build.MANUFACTURER + Build.MODEL;
        String sysver = "SDK " + Build.VERSION.SDK_INT;
        String app;
        try {
            Context ctx = ApplicationLoader.applicationContext;
            app = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            app = DEFAULT_APPVER;
        }
        return new String[]{model, sysver, app};
    }

    /** Вызывается из ConnectionsManager.init перед native_init. args = {model, sysver, appver}. */
    public static int patchInit(int version, String[] args) {
        if (real == null) real = new String[]{args[0], args[1], args[2]};
        if (!isEnabled()) return version;
        String[] s = spoofed();
        args[0] = s[0];
        args[1] = s[1];
        if (s[2] != null) args[2] = s[2];
        // Другая «version» заставляет tgnet заново отправить initConnection при старте
        CRC32 crc = new CRC32();
        crc.update((args[0] + args[1] + args[2]).getBytes());
        return (int) ((version + 1 + crc.getValue() % 900) & 0x7FFFFFFF);
    }

    public interface Callback {
        void done(int ok, int failed, String error);
    }

    /** Применить сейчас, без перезапуска: подмена или (если выключено) настоящие значения. */
    public static void applyNow(Callback callback) {
        String[] values;
        if (isEnabled()) {
            values = spoofed();
            if (values[2] == null) values[2] = realValues()[2];
        } else {
            values = realValues();
        }
        int[] counters = {0, 0, 0};
        String[] lastError = {null};
        int total = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) total++;
        }
        if (total == 0) {
            callback.done(0, 0, "No accounts");
            return;
        }
        final int expected = total;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) continue;
            InitConnection req = new InitConnection(values[0], values[1], values[2]);
            ConnectionsManager.getInstance(a).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (error != null) {
                    counters[1]++;
                    lastError[0] = error.text;
                } else {
                    counters[0]++;
                }
                if (counters[0] + counters[1] == expected) callback.done(counters[0], counters[1], lastError[0]);
            }));
        }
    }

    // invokeWithLayer(initConnection(...)) поверх help.getNearestDc
    private static class InitConnection extends TLObject {
        private final TLRPC.TL_help_getNearestDc inner = new TLRPC.TL_help_getNearestDc();
        private final String model, sysver, appver;

        InitConnection(String model, String sysver, String appver) {
            this.model = model;
            this.sysver = sysver;
            this.appver = appver;
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            String sysLang, lang;
            try {
                sysLang = LocaleController.getSystemLocaleStringIso639().toLowerCase();
                lang = LocaleController.getLocaleStringIso639().toLowerCase();
            } catch (Exception e) {
                sysLang = lang = "en";
            }
            stream.writeInt32(0xda9b0d0d); // invokeWithLayer
            stream.writeInt32(TLRPC.LAYER);
            stream.writeInt32(0xc1cd5ea9); // initConnection
            stream.writeInt32(0);
            stream.writeInt32(BuildVars.APP_ID);
            stream.writeString(model);
            stream.writeString(sysver);
            stream.writeString(appver);
            stream.writeString(sysLang);
            stream.writeString("android");
            stream.writeString(lang);
            inner.serializeToStream(stream);
        }

        @Override
        public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
            return inner.deserializeResponse(stream, constructor, exception);
        }
    }
}
