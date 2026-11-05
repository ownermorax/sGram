package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import dalvik.system.InMemoryDexClassLoader;

/**
 * sGram: Hidden Folder.
 * Локальная папка для чатов под 4-значным код-паролем: чаты пропадают из списка, поиска, счётчиков и уведомлений.
 * DEX лежит в assets/sgram/hidden_folder.dex (хуки через наш XposedBridge), данные — в prefs aniv_local_hidden_folder.
 * Вход: 0 — зажать вкладку «Чаты», 2 — зажать «Настройки чатов» (SettingsActivity.onLongClick).
 */
public class SgramHiddenFolder {

    private static final String KEY = "hidden_folder", KEY_ENTRY = "hidden_folder_entry", KEY_BIOMETRIC = "hidden_folder_biometric";
    private static Class<?> core;
    private static boolean started;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("sgram", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        return prefs().getBoolean(KEY, false);
    }

    public static void setEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY, enabled).apply();
        if (enabled) start(); else stop();
    }

    public static int getEntry() {
        int entry = prefs().getInt(KEY_ENTRY, 0);
        return entry == 2 ? 2 : 0;
    }

    public static void setEntry(int entry) {
        prefs().edit().putInt(KEY_ENTRY, entry).apply();
        if (started) call("setEntryPoint", String.valueOf(entry));
    }

    public static boolean isBiometricAvailable() {
        try {
            return BiometricManager.from(ApplicationLoader.applicationContext).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean isBiometric() {
        return prefs().getBoolean(KEY_BIOMETRIC, true) && isBiometricAvailable();
    }

    public static void setBiometric(boolean enabled) {
        prefs().edit().putBoolean(KEY_BIOMETRIC, enabled).apply();
    }

    private static Object promptedSession;
    private static int promptedTicket = -1;

    /**
     * Экран DEX (exteraGram UniversalFragment) стал видимым. Если это папка, закрытая код-паролем,
     * спрашиваем отпечаток: успех открывает ту же сессию, что и верный код (FolderSession.unlock + revealSession).
     */
    public static void onScreenResumed(org.telegram.ui.ActionBar.BaseFragment fragment) {
        if (!started || core == null || !isBiometric()) return;
        AndroidUtilities.runOnUIThread(() -> {
            try {
                Object instance = field(core, null, "instance");
                Object screen = null;
                for (Object ref : (java.util.List<?>) field(core, instance, "screens")) {
                    Object s = ((java.lang.ref.WeakReference<?>) ref).get();
                    if (s != null && field(s.getClass(), s, "fragment") == fragment) {
                        screen = s;
                        break;
                    }
                }
                if (screen == null || field(screen.getClass(), screen, "pinGate") == null
                        || Boolean.TRUE.equals(field(screen.getClass(), screen, "changingPin"))
                        || Boolean.TRUE.equals(field(screen.getClass(), screen, "closed"))) return;
                Object session = field(screen.getClass(), screen, "session");
                int ticket = (Integer) method(session, "ticket").invoke(session);
                if (session == promptedSession && ticket == promptedTicket) return;
                promptedSession = session;
                promptedTicket = ticket;
                if (!(fragment.getParentActivity() instanceof androidx.fragment.app.FragmentActivity)) return;
                androidx.fragment.app.FragmentActivity activity = (androidx.fragment.app.FragmentActivity) fragment.getParentActivity();
                final Object coreInstance = instance;
                BiometricPrompt prompt = new BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        try {
                            if (!(Boolean) method(session, "unlock", int.class).invoke(session, ticket)) return;
                            method(coreInstance, "revealSession", session.getClass()).invoke(coreInstance, session);
                        } catch (Throwable e) {
                            FileLog.e(e);
                        }
                    }
                });
                prompt.authenticate(new BiometricPrompt.PromptInfo.Builder()
                        .setTitle("Скрытая папка")
                        .setNegativeButtonText("Ввести код")
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                        .build());
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }, 200);
    }

    private static Object field(Class<?> cls, Object target, String name) throws Exception {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignore) {
            }
        }
        throw new NoSuchFieldException(cls.getName() + "." + name);
    }

    private static Method method(Object target, String name, Class<?>... params) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, params);
        m.setAccessible(true);
        return m;
    }

    public static void init() {
        if (isEnabled()) AndroidUtilities.runOnUIThread(SgramHiddenFolder::start);
    }

    private static synchronized void start() {
        if (started) return;
        try {
            ClassLoader app = SgramHiddenFolder.class.getClassLoader();
            for (String c : new String[]{"org.telegram.ui.ActionBar.BaseFragment", "org.telegram.ui.DialogsActivity",
                    "org.telegram.ui.MainTabsActivity", "org.telegram.ui.Cells.DialogCell", "org.telegram.ui.Cells.ProfileSearchCell",
                    "org.telegram.ui.Cells.UserCell", "org.telegram.messenger.MessagesStorage", "org.telegram.messenger.NotificationsController"}) {
                Class.forName(c, true, app);
            }
            if (core == null) {
                byte[] dex;
                try (InputStream in = ApplicationLoader.applicationContext.getAssets().open("sgram/hidden_folder.dex")) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    dex = out.toByteArray();
                }
                core = new InMemoryDexClassLoader(ByteBuffer.wrap(dex), app).loadClass("com.aniv.hiddenfolder.HiddenFolderCore");
            }
            invoke("configure", "{}");
            invoke("setEntryPoint", String.valueOf(getEntry()));
            invoke("load");
            started = true;
        } catch (Throwable e) {
            showError(e);
        }
    }

    private static synchronized void stop() {
        if (!started) return;
        call("unload");
        started = false;
    }

    /** Открыть папку (спросит код-пароль, при первом запуске — приветствие). */
    public static void open(org.telegram.ui.ActionBar.BaseFragment fragment) {
        if (fragment == null || !isEnabled()) return;
        start();
        if (started) call("openEntry", fragment);
    }

    /** Заново показать приветствие (сменить чаты/пароль сохраняются). */
    public static void showWelcome(org.telegram.ui.ActionBar.BaseFragment fragment) {
        if (fragment == null || !isEnabled()) return;
        start();
        if (started) call("showWelcome", fragment);
    }

    /** Долгое нажатие на «Настройки чатов». */
    public static boolean onSettingsLongPress(org.telegram.ui.ActionBar.BaseFragment fragment) {
        if (!isEnabled() || getEntry() != 2) return false;
        open(fragment);
        return true;
    }

    private static void call(String name, Object... args) {
        try {
            invoke(name, args);
        } catch (Throwable e) {
            showError(e);
        }
    }

    private static void invoke(String name, Object... args) throws Exception {
        for (Method m : core.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == args.length) {
                m.invoke(null, args);
                return;
            }
        }
        throw new NoSuchMethodException("HiddenFolderCore." + name);
    }

    private static void showError(Throwable e) {
        FileLog.e(e);
        Throwable c = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
        android.widget.Toast.makeText(ApplicationLoader.applicationContext, "Hidden Folder: " + c, android.widget.Toast.LENGTH_LONG).show();
    }
}
