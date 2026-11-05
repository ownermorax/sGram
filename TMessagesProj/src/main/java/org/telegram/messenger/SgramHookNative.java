package org.telegram.messenger;

import java.lang.reflect.Member;
import java.lang.reflect.Method;

/**
 * sGram: JNI bridge to libsgramhook (LSPlant + Dobby). Used only by the Xposed-style layer
 * in de.robv.android.xposed.XposedBridge.
 */
public final class SgramHookNative {

    private static int loaded; // 0 = not tried, 1 = ok, -1 = failed
    private static Throwable loadError;

    private SgramHookNative() {
    }

    public static synchronized boolean available() {
        if (loaded == 0) {
            try {
                System.loadLibrary("lsplant");
                System.loadLibrary("sgramhook");
                loaded = init() ? 1 : -1;
                if (loaded < 0) {
                    loadError = new IllegalStateException("LSPlant init failed");
                }
            } catch (Throwable e) {
                loadError = e;
                loaded = -1;
            }
        }
        return loaded > 0;
    }

    public static Throwable getLoadError() {
        return loadError;
    }

    private static native boolean init();

    /** @return backup method that calls the original implementation, or null on failure */
    public static native Method hook(Member target, Object hooker, Method callback);

    public static native boolean unhook(Member target);

    public static native boolean isHooked(Member target);

    public static native boolean deoptimize(Member target);

    public static native boolean makeClassInheritable(Class<?> target);
}
