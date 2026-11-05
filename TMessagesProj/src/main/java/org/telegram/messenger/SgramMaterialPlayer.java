package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;

import dalvik.system.InMemoryDexClassLoader;

/**
 * sGram: Material Player (порт плагина md3_player от @RnPlugins, github.com/YouRooni/plugins).
 * Полноэкранный плеер в стиле Material Expressive + синхронизированный текст песен (lrcmux/KuGou).
 * DEX плагина лежит в assets/sgram/md3_player.dex, открывается напрямую из FragmentContextView.
 * Настройки — в prefs md3_player_prefs (их читает PlayerConfig плагина).
 */
public class SgramMaterialPlayer {

    private static final String KEY = "material_player";
    public static final String MINI_DIALOGS = "mini_player_dialogs", CONTEXT_BAR = "context_bar_enabled",
            COLOR_SOURCE = "color_source", WAVY = "wavy_seekbar", DOT = "seekbar_dot", ANIM = "anim_style",
            HIDE_SHUFFLE = "sgram_hide_shuffle";
    private static Class<?> hookClass;

    /** Закрыть открытый Material Player (PlayerSheet.instance). */
    public static void dismissSheet() {
        if (hookClass == null) return;
        try {
            Class<?> c = hookClass.getClassLoader().loadClass("dev.rooni.player.PlayerSheet");
            java.lang.reflect.Field f = c.getDeclaredField("instance");
            f.setAccessible(true);
            Object sheet = f.get(null);
            if (sheet instanceof android.app.Dialog) ((android.app.Dialog) sheet).dismiss();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
    private static boolean started;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("sgram", Context.MODE_PRIVATE);
    }

    public static SharedPreferences playerPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("md3_player_prefs", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        return prefs().getBoolean(KEY, true);
    }

    public static void setEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY, enabled).apply();
        if (enabled) start(); else stop();
    }

    public static boolean getBool(String key) {
        boolean def = !MINI_DIALOGS.equals(key) && !CONTEXT_BAR.equals(key);
        return playerPrefs().getBoolean(key, def);
    }

    public static boolean isThemeColors() {
        return "theme".equals(playerPrefs().getString(COLOR_SOURCE, "cover"));
    }

    public static int getAnimStyle() {
        return playerPrefs().getInt(ANIM, 0);
    }

    public static void setBool(String key, boolean value) {
        playerPrefs().edit().putBoolean(key, value).apply();
        applySettings();
    }

    public static void setThemeColors(boolean theme) {
        playerPrefs().edit().putString(COLOR_SOURCE, theme ? "theme" : "cover").apply();
        applySettings();
    }

    public static void setAnimStyle(int style) {
        playerPrefs().edit().putInt(ANIM, style).apply();
        applySettings();
    }

    public static void init() {
        // хуки ставим после старта UI: на уже инициализированные классы (как это делал движок плагинов)
        if (isEnabled()) AndroidUtilities.runOnUIThread(SgramMaterialPlayer::start, 2500);
    }

    private static void writePlayerPrefs() {
        SharedPreferences p = playerPrefs();
        p.edit()
                .putBoolean(MINI_DIALOGS, getBool(MINI_DIALOGS))
                .putBoolean(CONTEXT_BAR, getBool(CONTEXT_BAR))
                .putString(COLOR_SOURCE, isThemeColors() ? "theme" : "cover")
                .putBoolean("online_lyrics", true)
                .putBoolean(WAVY, getBool(WAVY))
                .putBoolean(DOT, getBool(DOT))
                .putInt(ANIM, getAnimStyle())
                .apply();
    }

    /** Передать настройки в уже загруженный плеер (как apply_settings в плагине). */
    private static void applySettings() {
        writePlayerPrefs();
        if (hookClass == null) return;
        boolean mini = getBool(MINI_DIALOGS), bar = getBool(CONTEXT_BAR), wavy = getBool(WAVY), dot = getBool(DOT);
        String color = isThemeColors() ? "theme" : "cover";
        int anim = getAnimStyle();
        for (Method m : hookClass.getMethods()) {
            try {
                String name = m.getName();
                int n = m.getParameterTypes().length;
                if (n == 1) {
                    switch (name) {
                        case "setAnimStyle": m.invoke(null, anim); break;
                        case "setSeekBarDot": m.invoke(null, dot); break;
                        case "setWavySeekBar": m.invoke(null, wavy); break;
                        case "setColorSource": m.invoke(null, color); break;
                        case "setMiniPlayerDialogs": m.invoke(null, mini); break;
                        case "setContextBar": m.invoke(null, bar); break;
                    }
                } else if (n == 7 && "updateSettings".equals(name)) {
                    m.invoke(null, mini, bar, color, true, wavy, false, anim);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    private static synchronized void start() {
        if (started) return;
        try {
            writePlayerPrefs();
            ClassLoader app = SgramMaterialPlayer.class.getClassLoader();
            for (String c : new String[]{"org.telegram.ui.ActionBar.BaseFragment", "org.telegram.ui.Components.AudioPlayerAlert",
                    "org.telegram.ui.Components.FragmentContextView", "org.telegram.ui.LaunchActivity"}) {
                Class.forName(c, true, app);
            }
            if (hookClass == null) {
                byte[] dex;
                try (InputStream in = ApplicationLoader.applicationContext.getAssets().open("sgram/md3_player.dex")) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    dex = out.toByteArray();
                }
                ClassLoader loader = new InMemoryDexClassLoader(ByteBuffer.wrap(dex), app);
                hookClass = loader.loadClass("dev.rooni.player.PlayerHook");
            }
            hookClass.getMethod("start").invoke(null);
            started = true;
            applySettings();
        } catch (Throwable e) {
            showError(e);
        }
    }

    /** Открыть плеер Material напрямую (без хука). false — показать стоковый. */
    public static boolean open(org.telegram.ui.ActionBar.BaseFragment fragment) {
        if (fragment == null || !isEnabled()) return false;
        try {
            start();
            if (hookClass == null) return false;
            Class<?> player = hookClass.getClassLoader().loadClass("dev.rooni.player.Md3Player");
            for (Method m : player.getMethods()) {
                if ("open".equals(m.getName()) && m.getParameterTypes().length == 2) {
                    m.invoke(null, fragment, null);
                    if (getBool(HIDE_SHUFFLE)) {
                        for (int delay : new int[]{0, 150, 500}) AndroidUtilities.runOnUIThread(SgramMaterialPlayer::hideShuffle, delay);
                    }
                    return true;
                }
            }
            showError(new NoSuchMethodException("Md3Player.open"));
        } catch (Throwable e) {
            showError(e);
        }
        return false;
    }

    /** Перемешивание в плеере не работает — прячем кнопку (поле ControlsRow.shuffle). */
    private static void hideShuffle() {
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object global = wmg.getMethod("getInstance").invoke(null);
            Field rootsField = wmg.getDeclaredField("mRoots");
            rootsField.setAccessible(true);
            List<?> roots = (List<?>) rootsField.get(global);
            Class<?> controls = hookClass.getClassLoader().loadClass("dev.rooni.player.ControlsRow");
            Field shuffle = controls.getDeclaredField("shuffle");
            shuffle.setAccessible(true);
            for (Object root : roots.toArray()) {
                View view = (View) root.getClass().getMethod("getView").invoke(root);
                hideIn(view, controls, shuffle);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void hideIn(View view, Class<?> controls, Field shuffle) throws IllegalAccessException {
        if (view == null) return;
        if (controls.isInstance(view)) {
            View button = (View) shuffle.get(view);
            if (button != null && button.getVisibility() != View.GONE) {
                button.setVisibility(View.GONE);
                view.requestLayout();
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) hideIn(group.getChildAt(i), controls, shuffle);
        }
    }

    private static void showError(Throwable e) {
        FileLog.e(e);
        Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
        android.widget.Toast.makeText(ApplicationLoader.applicationContext, "Material Player: " + c, android.widget.Toast.LENGTH_LONG).show();
    }

    private static synchronized void stop() {
        if (!started || hookClass == null) return;
        try {
            hookClass.getMethod("stop").invoke(null);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        started = false;
    }
}
