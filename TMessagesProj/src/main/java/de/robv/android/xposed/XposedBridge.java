package de.robv.android.xposed;

import android.util.Log;

import org.telegram.messenger.SgramHookNative;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * sGram: Xposed-compatible bridge on top of LSPlant (libsgramhook). Implements the part of the
 * de.robv.android.xposed API used by exteraGram plugins: hookMethod / hookAll* / unhook and
 * invokeOriginalMethod.
 */
public final class XposedBridge {

    public static final String TAG = "sGramXposed";

    private static final HashMap<Member, HookInfo> hooked = new HashMap<>();

    private XposedBridge() {
    }

    public static boolean isAvailable() {
        return SgramHookNative.available();
    }

    public static void log(String text) {
        Log.i(TAG, text);
    }

    public static void log(Throwable t) {
        Log.e(TAG, Log.getStackTraceString(t));
    }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        if (!(hookMethod instanceof Method) && !(hookMethod instanceof Constructor)) {
            throw new IllegalArgumentException("Only methods and constructors can be hooked: " + hookMethod);
        }
        if (Modifier.isAbstract(hookMethod.getModifiers())) {
            throw new IllegalArgumentException("Cannot hook abstract methods: " + hookMethod);
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        if (!SgramHookNative.available()) {
            throw new IllegalStateException("Method hooking is unavailable on this device", SgramHookNative.getLoadError());
        }
        synchronized (hooked) {
            HookInfo info = hooked.get(hookMethod);
            if (info == null) {
                info = new HookInfo(hookMethod);
                Method backup;
                try {
                    Method callbackMethod = HookInfo.class.getDeclaredMethod("callback", Object[].class);
                    backup = SgramHookNative.hook(hookMethod, info, callbackMethod);
                } catch (NoSuchMethodException e) {
                    throw new IllegalStateException(e);
                }
                if (backup == null) {
                    throw new IllegalStateException("Failed to hook " + hookMethod);
                }
                backup.setAccessible(true);
                info.backup = backup;
                hooked.put(hookMethod, info);
            }
            info.add(callback);
        }
        return callback.new Unhook(hookMethod);
    }

    public static void unhookMethod(Member hookMethod, XC_MethodHook callback) {
        synchronized (hooked) {
            HookInfo info = hooked.get(hookMethod);
            if (info != null) {
                info.remove(callback);
                // The ART hook stays installed: unhooking while another thread is inside the
                // callback is unsafe, and an empty HookInfo just calls the original.
            }
        }
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) {
        HashSet<XC_MethodHook.Unhook> unhooks = new HashSet<>();
        for (Method method : hookClass.getDeclaredMethods()) {
            if (method.getName().equals(methodName) && !Modifier.isAbstract(method.getModifiers())) {
                unhooks.add(hookMethod(method, callback));
            }
        }
        return unhooks;
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        HashSet<XC_MethodHook.Unhook> unhooks = new HashSet<>();
        for (Constructor<?> constructor : hookClass.getDeclaredConstructors()) {
            unhooks.add(hookMethod(constructor, callback));
        }
        return unhooks;
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args) throws Throwable {
        if (args == null) {
            args = new Object[0];
        }
        HookInfo info;
        synchronized (hooked) {
            info = hooked.get(method);
        }
        try {
            if (info != null) {
                return info.backup.invoke(thisObject, args);
            }
            if (method instanceof Method) {
                ((Method) method).setAccessible(true);
                return ((Method) method).invoke(thisObject, args);
            }
            throw new IllegalArgumentException("Constructor is not hooked: " + method);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    public static boolean deoptimizeMethod(Member method) {
        return SgramHookNative.available() && SgramHookNative.deoptimize(method);
    }

    // public: LSPlant calls callback() from a generated class in another package
    public static final class HookInfo {
        final Member target;
        final boolean isStatic;
        final Class<?> returnType;
        final Class<?>[] paramTypes;
        volatile Method backup;
        private volatile XC_MethodHook[] callbacks = new XC_MethodHook[0];

        HookInfo(Member target) {
            this.target = target;
            this.isStatic = Modifier.isStatic(target.getModifiers());
            this.returnType = target instanceof Method ? ((Method) target).getReturnType() : void.class;
            this.paramTypes = target instanceof Method ? ((Method) target).getParameterTypes() : ((Constructor<?>) target).getParameterTypes();
        }

        synchronized void add(XC_MethodHook callback) {
            ArrayList<XC_MethodHook> list = new ArrayList<>();
            for (XC_MethodHook c : callbacks) {
                if (c == callback) {
                    return;
                }
                list.add(c);
            }
            int i = 0;
            while (i < list.size() && list.get(i).priority >= callback.priority) {
                i++;
            }
            list.add(i, callback);
            callbacks = list.toArray(new XC_MethodHook[0]);
        }

        synchronized void remove(XC_MethodHook callback) {
            ArrayList<XC_MethodHook> list = new ArrayList<>();
            for (XC_MethodHook c : callbacks) {
                if (c != callback) {
                    list.add(c);
                }
            }
            callbacks = list.toArray(new XC_MethodHook[0]);
        }

        // Called by LSPlant instead of the original. args[0] is "this" for instance methods.
        public Object callback(Object[] rawArgs) throws Throwable {
            Object thisObject;
            Object[] args;
            if (isStatic) {
                thisObject = null;
                args = rawArgs;
            } else {
                thisObject = rawArgs[0];
                args = new Object[rawArgs.length - 1];
                System.arraycopy(rawArgs, 1, args, 0, args.length);
            }
            XC_MethodHook[] snapshot = callbacks;
            if (snapshot.length == 0) {
                try {
                    return backup.invoke(thisObject, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause() != null ? e.getCause() : e;
                }
            }

            XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
            param.method = target;
            param.thisObject = thisObject;
            param.args = args;

            int beforeIdx = 0;
            do {
                try {
                    snapshot[beforeIdx].beforeHookedMethod(param);
                } catch (Throwable t) {
                    log(t);
                    param.setResultSilently(null);
                    param.returnEarly = false;
                    continue;
                }
                if (param.returnEarly) {
                    beforeIdx++;
                    break;
                }
            } while (++beforeIdx < snapshot.length);

            if (!param.returnEarly) {
                try {
                    coerceArgs(param.args);
                    param.setResultSilently(backup.invoke(param.thisObject, param.args));
                } catch (InvocationTargetException e) {
                    param.setThrowableSilently(e.getCause() != null ? e.getCause() : e);
                }
            }

            int afterIdx = beforeIdx - 1;
            do {
                Object lastResult = param.getResult();
                Throwable lastThrowable = param.getThrowable();
                try {
                    snapshot[afterIdx].afterHookedMethod(param);
                } catch (Throwable t) {
                    log(t);
                    if (lastThrowable == null) {
                        param.setResultSilently(lastResult);
                    } else {
                        param.setThrowableSilently(lastThrowable);
                    }
                }
            } while (--afterIdx >= 0);

            if (param.hasThrowable()) {
                throw param.getThrowable();
            }
            return coerce(param.getResult());
        }

        private void coerceArgs(Object[] args) {
            if (args == null) {
                return;
            }
            for (int i = 0; i < args.length && i < paramTypes.length; i++) {
                if (paramTypes[i].isPrimitive()) {
                    args[i] = coerceTo(paramTypes[i], args[i]);
                }
            }
        }

        // Python plugins return Long/Double for every number; ART unboxes by the declared type.
        private Object coerce(Object result) {
            return coerceTo(returnType, result);
        }

        static Object coerceTo(Class<?> returnType, Object result) {
            if (result == null || !returnType.isPrimitive()) {
                if (result == null && returnType.isPrimitive() && returnType != void.class) {
                    return defaultValue(returnType);
                }
                return result;
            }
            if (result instanceof Number) {
                Number n = (Number) result;
                if (returnType == int.class) return n.intValue();
                if (returnType == long.class) return n.longValue();
                if (returnType == float.class) return n.floatValue();
                if (returnType == double.class) return n.doubleValue();
                if (returnType == short.class) return n.shortValue();
                if (returnType == byte.class) return n.byteValue();
                if (returnType == boolean.class) return n.intValue() != 0;
                if (returnType == char.class) return (char) n.intValue();
            }
            return result;
        }

        private static Object defaultValue(Class<?> type) {
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0f;
            if (type == double.class) return 0d;
            if (type == short.class) return (short) 0;
            if (type == byte.class) return (byte) 0;
            if (type == char.class) return (char) 0;
            return null;
        }
    }
}
