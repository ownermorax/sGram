package org.telegram.messenger;

import de.robv.android.xposed.XC_MethodHook;

/**
 * sGram: XC_MethodHook whose callbacks are implemented in Python (base_plugin._HookCallbacks):
 * Chaquopy can only proxy interfaces, so the abstract class is bridged through {@link Callbacks}.
 */
public final class SgramPyMethodHook extends XC_MethodHook {

    public interface Callbacks {
        void before(MethodHookParam param);

        void after(MethodHookParam param);

        boolean isReplacement();
    }

    private final Callbacks callbacks;

    public SgramPyMethodHook(Callbacks callbacks, int priority) {
        super(priority);
        this.callbacks = callbacks;
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        callbacks.before(param);
    }

    @Override
    protected void afterHookedMethod(MethodHookParam param) {
        callbacks.after(param);
    }
}
