package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * sGram: the commonly used part of de.robv.android.xposed.XposedHelpers.
 */
public final class XposedHelpers {

    private static final HashMap<String, Field> fieldCache = new HashMap<>();
    private static final WeakHashMap<Object, HashMap<String, Object>> additionalFields = new WeakHashMap<>();

    private XposedHelpers() {
    }

    public static final class ClassNotFoundError extends Error {
        public ClassNotFoundError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        if (classLoader == null) {
            classLoader = XposedHelpers.class.getClassLoader();
        }
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new ClassNotFoundError(className, e);
        }
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        try {
            return findClass(className, classLoader);
        } catch (ClassNotFoundError e) {
            return null;
        }
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        String key = clazz.getName() + '#' + fieldName;
        synchronized (fieldCache) {
            Field cached = fieldCache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        Class<?> c = clazz;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                synchronized (fieldCache) {
                    fieldCache.put(key, f);
                }
                return f;
            } catch (NoSuchFieldException ignore) {
            }
            c = c.getSuperclass();
        }
        throw new NoSuchFieldError(key);
    }

    public static Field findFieldIfExists(Class<?> clazz, String fieldName) {
        try {
            return findField(clazz, fieldName);
        } catch (NoSuchFieldError e) {
            return null;
        }
    }

    public static Object getObjectField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).get(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        try {
            findField(obj.getClass(), fieldName).set(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        return (Boolean) getObjectField(obj, fieldName);
    }

    public static void setBooleanField(Object obj, String fieldName, boolean value) {
        setObjectField(obj, fieldName, value);
    }

    public static int getIntField(Object obj, String fieldName) {
        return ((Number) getObjectField(obj, fieldName)).intValue();
    }

    public static void setIntField(Object obj, String fieldName, int value) {
        setObjectField(obj, fieldName, value);
    }

    public static long getLongField(Object obj, String fieldName) {
        return ((Number) getObjectField(obj, fieldName)).longValue();
    }

    public static void setLongField(Object obj, String fieldName, long value) {
        setObjectField(obj, fieldName, value);
    }

    public static float getFloatField(Object obj, String fieldName) {
        return ((Number) getObjectField(obj, fieldName)).floatValue();
    }

    public static void setFloatField(Object obj, String fieldName, float value) {
        setObjectField(obj, fieldName, value);
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        try {
            return findField(clazz, fieldName).get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
        try {
            findField(clazz, fieldName).set(null, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static boolean getStaticBooleanField(Class<?> clazz, String fieldName) {
        return (Boolean) getStaticObjectField(clazz, fieldName);
    }

    public static void setStaticBooleanField(Class<?> clazz, String fieldName, boolean value) {
        setStaticObjectField(clazz, fieldName, value);
    }

    public static int getStaticIntField(Class<?> clazz, String fieldName) {
        return ((Number) getStaticObjectField(clazz, fieldName)).intValue();
    }

    public static void setStaticIntField(Class<?> clazz, String fieldName, int value) {
        setStaticObjectField(clazz, fieldName, value);
    }

    public static long getStaticLongField(Class<?> clazz, String fieldName) {
        return ((Number) getStaticObjectField(clazz, fieldName)).longValue();
    }

    private static Class<?>[] resolveTypes(Class<?> clazz, Object[] parameterTypes) {
        Class<?>[] types = new Class<?>[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Object t = parameterTypes[i];
            if (t instanceof Class) {
                types[i] = (Class<?>) t;
            } else if (t instanceof String) {
                types[i] = findClass((String) t, clazz.getClassLoader());
            } else {
                throw new IllegalArgumentException("Parameter type must be Class or String: " + t);
            }
        }
        return types;
    }

    public static Method findMethodExact(Class<?> clazz, String methodName, Object... parameterTypes) {
        Class<?>[] types = resolveTypes(clazz, parameterTypes);
        Class<?> c = clazz;
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(methodName, types);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignore) {
            }
            c = c.getSuperclass();
        }
        throw new NoSuchMethodError(clazz.getName() + '#' + methodName);
    }

    public static Method findMethodExact(String className, ClassLoader classLoader, String methodName, Object... parameterTypes) {
        return findMethodExact(findClass(className, classLoader), methodName, parameterTypes);
    }

    public static Method findMethodExactIfExists(Class<?> clazz, String methodName, Object... parameterTypes) {
        try {
            return findMethodExact(clazz, methodName, parameterTypes);
        } catch (NoSuchMethodError | ClassNotFoundError e) {
            return null;
        }
    }

    public static Constructor<?> findConstructorExact(Class<?> clazz, Object... parameterTypes) {
        try {
            Constructor<?> c = clazz.getDeclaredConstructor(resolveTypes(clazz, parameterTypes));
            c.setAccessible(true);
            return c;
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(clazz.getName() + "#<init>");
        }
    }

    private static boolean isAssignable(Class<?> type, Object arg) {
        if (arg == null) {
            return !type.isPrimitive();
        }
        Class<?> a = arg.getClass();
        if (type.isAssignableFrom(a)) {
            return true;
        }
        if (type.isPrimitive()) {
            if (type == boolean.class) return a == Boolean.class;
            if (type == char.class) return a == Character.class;
            return arg instanceof Number;
        }
        return false;
    }

    private static Method findMethodForArgs(Class<?> clazz, String methodName, boolean wantStatic, Object[] args) {
        Class<?> c = clazz;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName) || Modifier.isStatic(m.getModifiers()) != wantStatic) {
                    continue;
                }
                Class<?>[] types = m.getParameterTypes();
                if (types.length != args.length) {
                    continue;
                }
                boolean ok = true;
                for (int i = 0; i < types.length && ok; i++) {
                    ok = isAssignable(types[i], args[i]);
                }
                if (ok) {
                    m.setAccessible(true);
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        throw new NoSuchMethodError(clazz.getName() + '#' + methodName);
    }

    private static Object[] coerceArgs(Class<?>[] types, Object[] args) {
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = types[i].isPrimitive() ? XposedBridge.HookInfo.coerceTo(types[i], args[i]) : args[i];
        }
        return out;
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        Method m = findMethodForArgs(obj.getClass(), methodName, false, args);
        try {
            return m.invoke(obj, coerceArgs(m.getParameterTypes(), args));
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        } catch (InvocationTargetException e) {
            throw new InvocationTargetError(e.getCause());
        }
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        Method m = findMethodForArgs(clazz, methodName, true, args);
        try {
            return m.invoke(null, coerceArgs(m.getParameterTypes(), args));
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        } catch (InvocationTargetException e) {
            throw new InvocationTargetError(e.getCause());
        }
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            Class<?>[] types = c.getParameterTypes();
            if (types.length != args.length) {
                continue;
            }
            boolean ok = true;
            for (int i = 0; i < types.length && ok; i++) {
                ok = isAssignable(types[i], args[i]);
            }
            if (ok) {
                try {
                    c.setAccessible(true);
                    return c.newInstance(coerceArgs(types, args));
                } catch (InvocationTargetException e) {
                    throw new InvocationTargetError(e.getCause());
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        throw new NoSuchMethodError(clazz.getName() + "#<init>");
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0 || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("no callback defined");
        }
        XC_MethodHook callback = (XC_MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        Object[] types = new Object[parameterTypesAndCallback.length - 1];
        System.arraycopy(parameterTypesAndCallback, 0, types, 0, types.length);
        return XposedBridge.hookMethod(findMethodExact(clazz, methodName, types), callback);
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader, String methodName, Object... parameterTypesAndCallback) {
        return findAndHookMethod(findClass(className, classLoader), methodName, parameterTypesAndCallback);
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(Class<?> clazz, Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0 || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("no callback defined");
        }
        XC_MethodHook callback = (XC_MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        Object[] types = new Object[parameterTypesAndCallback.length - 1];
        System.arraycopy(parameterTypesAndCallback, 0, types, 0, types.length);
        return XposedBridge.hookMethod(findConstructorExact(clazz, types), callback);
    }

    public static Object setAdditionalInstanceField(Object obj, String key, Object value) {
        synchronized (additionalFields) {
            HashMap<String, Object> fields = additionalFields.get(obj);
            if (fields == null) {
                fields = new HashMap<>();
                additionalFields.put(obj, fields);
            }
            return fields.put(key, value);
        }
    }

    public static Object getAdditionalInstanceField(Object obj, String key) {
        synchronized (additionalFields) {
            Map<String, Object> fields = additionalFields.get(obj);
            return fields == null ? null : fields.get(key);
        }
    }

    public static Object removeAdditionalInstanceField(Object obj, String key) {
        synchronized (additionalFields) {
            Map<String, Object> fields = additionalFields.get(obj);
            return fields == null ? null : fields.remove(key);
        }
    }

    public static final class InvocationTargetError extends Error {
        public InvocationTargetError(Throwable cause) {
            super(cause);
        }
    }
}
