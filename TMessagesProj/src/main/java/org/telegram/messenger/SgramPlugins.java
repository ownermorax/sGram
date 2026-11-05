package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * sGram: engine for exteraGram plugins (.plugin / .py files, Python 3.11 via Chaquopy).
 * The Python side lives in src/main/python: sgram_engine.py plus our own implementation of the
 * exteraGram SDK modules (base_plugin, client_utils, android_utils, hook_utils, ui.*, ...).
 * Java calls into Python only for names a plugin registered, so idle plugins cost nothing.
 */
public final class SgramPlugins {

    public static final String PREFS = "sgram_plugins";
    public static final String PREF_ENGINE = "engine_enabled";
    private static final String PREF_CRASH_DISABLED = "crash_disabled";

    public static final int STRATEGY_DEFAULT = 0;
    public static final int STRATEGY_CANCEL = 1;
    public static final int STRATEGY_MODIFY = 2;
    public static final int STRATEGY_MODIFY_FINAL = 3;

    public static final String MENU_MESSAGE = "MESSAGE_CONTEXT_MENU";
    public static final String MENU_DRAWER = "DRAWER_MENU";
    public static final String MENU_MAIN = "MAIN_MENU";
    public static final String MENU_CHAT = "CHAT_ACTION_MENU";
    public static final String MENU_PROFILE = "PROFILE_ACTION_MENU";

    private static final Object lock = new Object();
    private static volatile PyObject engine;
    private static volatile boolean starting;
    private static volatile String startError;

    private static volatile Set<String> hookNames = Collections.emptySet();
    private static volatile String[] hookSubstrings = new String[0];
    private static volatile boolean sendMessageHook;

    private static final ArrayList<MenuItem> menuItems = new ArrayList<>();
    private static volatile DispatchQueue pluginsQueue;

    private SgramPlugins() {
    }

    public static final class MenuItem {
        public final String pluginId;
        public final String itemId;
        public final String type;
        public final String text;
        public final String icon;
        public final String subtext;
        public final int priority;

        MenuItem(String pluginId, String itemId, String type, String text, String icon, String subtext, int priority) {
            this.pluginId = pluginId;
            this.itemId = itemId;
            this.type = type;
            this.text = text;
            this.icon = icon;
            this.subtext = subtext;
            this.priority = priority;
        }

        public int getIconResId() {
            if (TextUtils.isEmpty(icon)) {
                return 0;
            }
            return ApplicationLoader.applicationContext.getResources().getIdentifier(icon, "drawable", ApplicationLoader.applicationContext.getPackageName());
        }
    }

    public static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEngineEnabled() {
        return prefs().getBoolean(PREF_ENGINE, true);
    }

    public static File getPluginsDir() {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "plugins");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static boolean hasPluginFiles() {
        File[] files = getPluginsDir().listFiles();
        if (files == null) {
            return false;
        }
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".py")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isRunning() {
        return engine != null;
    }

    public static String getStartError() {
        return startError;
    }

    /** Called once from ApplicationLoader: starts Python only if there are plugins to run. */
    public static void init() {
        String disabled = prefs().getString(PREF_CRASH_DISABLED, null);
        if (disabled != null) {
            prefs().edit().remove(PREF_CRASH_DISABLED).apply();
            AndroidUtilities.runOnUIThread(() -> android.widget.Toast.makeText(ApplicationLoader.applicationContext,
                    "sGram crashed, plugins disabled: " + disabled, android.widget.Toast.LENGTH_LONG).show(), 2000);
        }
        if (!isEngineEnabled() || !hasPluginFiles() || !hasEnabledPlugins()) {
            return;
        }
        Utilities.globalQueue.postRunnable(SgramPlugins::start);
    }

    /** Safe mode: called from the crash handler; a crash that went through Python or hooks disables all plugins. */
    public static void onCrash(Throwable crash) {
        if (engine == null || !involvesPlugins(crash)) {
            return;
        }
        try {
            SharedPreferences p = prefs();
            SharedPreferences.Editor e = p.edit();
            StringBuilder ids = new StringBuilder();
            for (Map.Entry<String, ?> entry : p.getAll().entrySet()) {
                if (entry.getKey().startsWith("enabled_") && Boolean.TRUE.equals(entry.getValue())) {
                    e.putBoolean(entry.getKey(), false);
                    if (ids.length() > 0) ids.append(", ");
                    ids.append(entry.getKey().substring(8));
                }
            }
            if (ids.length() > 0) {
                e.putString(PREF_CRASH_DISABLED, ids.toString());
            }
            e.commit();
        } catch (Throwable ignore) {
        }
    }

    // Падения вне плагинов (например, OOM в MessagesStorage) не должны выключать плагины
    private static boolean involvesPlugins(Throwable e) {
        for (int depth = 0; e != null && depth < 10; e = e.getCause(), depth++) {
            if (e.getClass().getName().startsWith("com.chaquo.python")) return true;
            for (StackTraceElement el : e.getStackTrace()) {
                String c = el.getClassName();
                if (c.startsWith("com.chaquo.python") || c.startsWith("de.robv.android.xposed") || c.contains("LSPHooker")) return true;
            }
        }
        return false;
    }

    /** Есть ли включённые плагины — без них Python при старте не поднимаем. */
    private static boolean hasEnabledPlugins() {
        for (Map.Entry<String, ?> entry : prefs().getAll().entrySet()) {
            if (entry.getKey().startsWith("enabled_") && Boolean.TRUE.equals(entry.getValue())) return true;
        }
        return false;
    }

    /** Starts the engine (blocking). Safe to call from any background thread. */
    public static boolean start() {
        if (engine != null) {
            return true;
        }
        synchronized (lock) {
            if (engine != null) {
                return true;
            }
            if (starting) {
                return false;
            }
            starting = true;
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(ApplicationLoader.applicationContext));
                }
                PyObject module = Python.getInstance().getModule("sgram_engine");
                module.callAttr("start", getPluginsDir().getAbsolutePath(), BuildVars.BUILD_VERSION_STRING);
                engine = module;
                startError = null;
            } catch (Throwable e) {
                FileLog.e(e);
                startError = e.toString();
            } finally {
                starting = false;
            }
        }
        if (engine != null) {
            AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.sgramPluginsChanged));
            onAppEvent("START");
        }
        return engine != null;
    }

    /** Disables every plugin until the engine is turned on again; Python itself can't be stopped. */
    public static void setEngineEnabled(boolean enabled) {
        prefs().edit().putBoolean(PREF_ENGINE, enabled).apply();
        if (enabled) {
            Utilities.globalQueue.postRunnable(() -> {
                if (engine == null) {
                    start();
                } else {
                    call("resume_all");
                }
                notifyChanged();
            });
        } else if (engine != null) {
            Utilities.globalQueue.postRunnable(() -> {
                call("suspend_all");
                notifyChanged();
            });
        }
    }

    /** client_utils.PLUGINS_QUEUE */
    public static DispatchQueue getPluginsQueue() {
        if (pluginsQueue == null) {
            synchronized (lock) {
                if (pluginsQueue == null) {
                    pluginsQueue = new DispatchQueue("pluginsQueue");
                }
            }
        }
        return pluginsQueue;
    }

    public static void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.sgramPluginsChanged));
    }

    /** Calls a function of sgram_engine; returns null if the engine is down or the call failed. */
    public static PyObject call(String name, Object... args) {
        PyObject e = engine;
        if (e == null) {
            return null;
        }
        try {
            return e.callAttr(name, args);
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    // ---- called from Python ----

    public static void setHooks(String[] exact, String[] substrings, boolean sendHook) {
        HashSet<String> set = new HashSet<>();
        if (exact != null) {
            Collections.addAll(set, exact);
        }
        hookNames = set;
        hookSubstrings = substrings != null ? substrings : new String[0];
        sendMessageHook = sendHook;
    }

    public static void addMenuItem(String pluginId, String itemId, String type, String text, String icon, String subtext, int priority) {
        synchronized (menuItems) {
            for (int i = 0; i < menuItems.size(); i++) {
                if (menuItems.get(i).itemId.equals(itemId)) {
                    menuItems.remove(i);
                    break;
                }
            }
            menuItems.add(new MenuItem(pluginId, itemId, type, text, icon, subtext, priority));
        }
    }

    public static boolean removeMenuItem(String itemId) {
        synchronized (menuItems) {
            for (int i = 0; i < menuItems.size(); i++) {
                if (menuItems.get(i).itemId.equals(itemId)) {
                    menuItems.remove(i);
                    return true;
                }
            }
        }
        return false;
    }

    public static void removePluginMenuItems(String pluginId) {
        synchronized (menuItems) {
            for (int i = menuItems.size() - 1; i >= 0; i--) {
                if (menuItems.get(i).pluginId.equals(pluginId)) {
                    menuItems.remove(i);
                }
            }
        }
    }

    // ---- menus ----

    public static ArrayList<MenuItem> getMenuItems(String type) {
        ArrayList<MenuItem> result = new ArrayList<>();
        if (engine == null) {
            return result;
        }
        synchronized (menuItems) {
            for (MenuItem item : menuItems) {
                if (item.type.equals(type)) {
                    result.add(item);
                }
            }
        }
        Collections.sort(result, (a, b) -> Integer.compare(b.priority, a.priority));
        return result;
    }

    public static void onMenuItemClick(MenuItem item, Map<String, Object> context) {
        call("on_menu_item_click", item.itemId, context instanceof HashMap ? context : new HashMap<>(context));
    }

    // ---- hooks ----

    private static boolean matches(String name) {
        if (hookNames.contains(name)) {
            return true;
        }
        for (String s : hookSubstrings) {
            if (name.contains(s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean wants(Object obj) {
        return engine != null && obj != null && (!hookNames.isEmpty() || hookSubstrings.length != 0) && matches(obj.getClass().getSimpleName());
    }

    /** @return {strategy, value} or null when nothing changed */
    private static Object[] result(PyObject r) {
        if (r == null) {
            return null;
        }
        List<PyObject> list = r.asList();
        int strategy = list.get(0).toInt();
        if (strategy == STRATEGY_DEFAULT) {
            return null;
        }
        Object value = list.size() > 1 && list.get(1) != null ? list.get(1).toJava(Object.class) : null;
        Object extra = list.size() > 2 && list.get(2) != null ? list.get(2).toJava(Object.class) : null;
        return new Object[]{strategy, value, extra};
    }

    /** @return the request to send (possibly replaced) or null to cancel it */
    public static TLObject preRequest(TLObject request, int account) {
        if (!wants(request)) {
            return request;
        }
        Object[] r = result(call("pre_request", request.getClass().getSimpleName(), account, request));
        if (r == null) {
            return request;
        }
        if ((int) r[0] == STRATEGY_CANCEL) {
            return null;
        }
        return r[1] instanceof TLObject ? (TLObject) r[1] : request;
    }

    /** @return {response, error} to deliver */
    public static Object[] postRequest(TLObject request, int account, TLObject response, TLRPC.TL_error error) {
        Object[] out = {response, error};
        if (!wants(request)) {
            return out;
        }
        Object[] r = result(call("post_request", request.getClass().getSimpleName(), account, response, error));
        if (r != null && (int) r[0] != STRATEGY_CANCEL) {
            if (r[1] instanceof TLObject) {
                out[0] = r[1];
            }
            if (r[2] instanceof TLRPC.TL_error) {
                out[1] = r[2];
            }
        }
        return out;
    }

    /** Runs update hooks over the list in place: cancelled updates are removed, replaced ones swapped. */
    public static void onUpdates(ArrayList<TLRPC.Update> updates, int account) {
        if (engine == null || updates == null || (hookNames.isEmpty() && hookSubstrings.length == 0)) {
            return;
        }
        for (int i = 0; i < updates.size(); i++) {
            TLRPC.Update update = updates.get(i);
            if (!wants(update)) {
                continue;
            }
            Object[] r = result(call("on_update", update.getClass().getSimpleName(), account, update));
            if (r == null) {
                continue;
            }
            if ((int) r[0] == STRATEGY_CANCEL) {
                updates.remove(i--);
            } else if (r[1] instanceof TLRPC.Update) {
                updates.set(i, (TLRPC.Update) r[1]);
            }
        }
    }

    /** @return the container to process (possibly replaced) or null to drop it */
    public static TLRPC.Updates onUpdatesContainer(TLRPC.Updates updates, int account) {
        if (!wants(updates)) {
            return updates;
        }
        Object[] r = result(call("on_updates", updates.getClass().getSimpleName(), account, updates));
        if (r == null) {
            return updates;
        }
        if ((int) r[0] == STRATEGY_CANCEL) {
            return null;
        }
        return r[1] instanceof TLRPC.Updates ? (TLRPC.Updates) r[1] : updates;
    }

    /** @return params to send (possibly replaced) or null to cancel */
    public static SendMessagesHelper.SendMessageParams onSendMessage(SendMessagesHelper.SendMessageParams params, int account) {
        if (engine == null || !sendMessageHook || params == null) {
            return params;
        }
        Object[] r = result(call("on_send_message", account, params));
        if (r == null) {
            return params;
        }
        if ((int) r[0] == STRATEGY_CANCEL) {
            return null;
        }
        return r[1] instanceof SendMessagesHelper.SendMessageParams ? (SendMessagesHelper.SendMessageParams) r[1] : params;
    }

    public static void onAppEvent(String event) {
        if (engine == null) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> call("on_app_event", event));
    }
}
