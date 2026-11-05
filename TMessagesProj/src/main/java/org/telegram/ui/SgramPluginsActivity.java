package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SgramPlugins;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * sGram: list of installed exteraGram plugins, engine switch and install from file.
 */
public class SgramPluginsActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int REQUEST_PLUGIN_FILE = 997;

    private static final int ID_ENGINE = 1;
    private static final int ID_INSTALL = 2;
    private static final int ID_PLUGIN = 1000;
    private static final int ID_ACTION = 5000;

    private final ArrayList<JSONObject> plugins = new ArrayList<>();
    private final ArrayList<SgramPlugins.MenuItem> actions = new ArrayList<>();
    private boolean loading;

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.sgramPluginsChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.onActivityResultReceived);
        reload();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.sgramPluginsChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.onActivityResultReceived);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.sgramPluginsChanged) {
            reload();
        } else if (id == NotificationCenter.onActivityResultReceived) {
            int requestCode = (Integer) args[0];
            int resultCode = (Integer) args[1];
            Intent data = (Intent) args[2];
            if (requestCode == REQUEST_PLUGIN_FILE && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                importUri(data.getData());
            }
        }
    }

    private void reload() {
        if (loading) {
            return;
        }
        loading = true;
        Utilities.globalQueue.postRunnable(() -> {
            if (SgramPlugins.isEngineEnabled() && !SgramPlugins.isRunning() && SgramPlugins.hasPluginFiles()) {
                SgramPlugins.start();
            }
            ArrayList<JSONObject> list = new ArrayList<>();
            try {
                com.chaquo.python.PyObject r = SgramPlugins.call("list_plugins");
                if (r != null) {
                    JSONArray array = new JSONArray(r.toString());
                    for (int i = 0; i < array.length(); i++) {
                        list.add(array.getJSONObject(i));
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            ArrayList<SgramPlugins.MenuItem> acts = new ArrayList<>(SgramPlugins.getMenuItems(SgramPlugins.MENU_DRAWER));
            acts.addAll(SgramPlugins.getMenuItems(SgramPlugins.MENU_MAIN));
            AndroidUtilities.runOnUIThread(() -> {
                loading = false;
                plugins.clear();
                plugins.addAll(list);
                actions.clear();
                actions.addAll(acts);
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
        });
    }

    @Override
    protected CharSequence getTitle() {
        return "Plugins";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCheck(ID_ENGINE, "Plugin Engine").setChecked(SgramPlugins.isEngineEnabled()));
        String status;
        if (!SgramPlugins.isEngineEnabled()) {
            status = "All plugins are off.";
        } else if (SgramPlugins.isRunning()) {
            status = "Python 3.11 is running. Compatible with exteraGram plugins (SDK 1.4.6).";
        } else if (SgramPlugins.getStartError() != null) {
            status = "Engine failed to start: " + SgramPlugins.getStartError();
        } else {
            status = "Python starts when the first plugin is installed.";
        }
        items.add(UItem.asShadow(status));

        items.add(UItem.asHeader("Installed"));
        if (plugins.isEmpty()) {
            items.add(UItem.asShadow("No plugins yet. Tap a .plugin file in any chat or install one from a file."));
        } else {
            for (int i = 0; i < plugins.size(); i++) {
                JSONObject p = plugins.get(i);
                String error = p.isNull("error") ? null : p.optString("error");
                String sub = error != null ? "⚠ " + error : joinNonEmpty(p.optString("author"), firstLine(p.optString("description")));
                items.add(UItem.asButtonCheck(ID_PLUGIN + i, p.optString("name") + "  " + p.optString("version"), sub).setChecked(p.optBoolean("enabled")));
            }
        }
        items.add(UItem.asButton(ID_INSTALL, R.drawable.msg_add, "Install from File"));
        items.add(UItem.asShadow("Plugins run Python code inside sGram with full access to your account. Install only plugins you trust."));

        if (!actions.isEmpty()) {
            items.add(UItem.asHeader("Plugin Actions"));
            for (int i = 0; i < actions.size(); i++) {
                SgramPlugins.MenuItem a = actions.get(i);
                UItem item = a.getIconResId() != 0 ? UItem.asButton(ID_ACTION + i, a.getIconResId(), a.text) : UItem.asButton(ID_ACTION + i, a.text);
                if (!TextUtils.isEmpty(a.subtext)) {
                    item.textValue = a.subtext;
                }
                items.add(item);
            }
            items.add(UItem.asShadow(null));
        }
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return (nl >= 0 ? s.substring(0, nl) : s).trim();
    }

    private static String joinNonEmpty(String a, String b) {
        if (TextUtils.isEmpty(a)) {
            return b;
        }
        if (TextUtils.isEmpty(b)) {
            return a;
        }
        return a + " · " + b;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ENGINE) {
            boolean enabled = !SgramPlugins.isEngineEnabled();
            SgramPlugins.setEngineEnabled(enabled);
            if (listView != null) {
                listView.adapter.update(true);
            }
        } else if (item.id == ID_INSTALL) {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                getParentActivity().startActivityForResult(intent, REQUEST_PLUGIN_FILE);
            } catch (Exception e) {
                FileLog.e(e);
            }
        } else if (item.id >= ID_PLUGIN && item.id < ID_PLUGIN + plugins.size()) {
            JSONObject p = plugins.get(item.id - ID_PLUGIN);
            boolean checkClick = LocaleController.isRTL ? x <= dp(76) : x >= view.getMeasuredWidth() - dp(76);
            if (checkClick) {
                boolean enabled = !p.optBoolean("enabled");
                setPluginEnabled(this, p.optString("id"), enabled);
            } else {
                presentFragment(new SgramPluginSettingsActivity(p.optString("id")));
            }
        } else if (item.id >= ID_ACTION && item.id < ID_ACTION + actions.size()) {
            HashMap<String, Object> context = new HashMap<>();
            context.put("account", currentAccount);
            context.put("context", getParentActivity());
            context.put("fragment", this);
            SgramPlugins.onMenuItemClick(actions.get(item.id - ID_ACTION), context);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    static void setPluginEnabled(BaseFragment fragment, String pluginId, boolean enabled) {
        Utilities.globalQueue.postRunnable(() -> {
            com.chaquo.python.PyObject r = SgramPlugins.call("set_enabled", pluginId, enabled);
            String error = r != null ? r.toString() : null;
            SgramPlugins.notifyChanged();
            if (error != null) {
                AndroidUtilities.runOnUIThread(() -> showError(fragment, "Plugin failed to load", error));
            }
        });
    }

    static void showError(BaseFragment fragment, String title, String text) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity());
        b.setTitle(title);
        b.setMessage(text);
        b.setPositiveButton("OK", null);
        fragment.showDialog(b.create());
    }

    private void importUri(Uri uri) {
        Utilities.globalQueue.postRunnable(() -> {
            String name = "plugin.py";
            try (Cursor cursor = ApplicationLoader.applicationContext.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && cursor.getString(idx) != null) {
                        name = cursor.getString(idx);
                    }
                }
            } catch (Exception ignore) {
            }
            File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "sgram_plugin_import");
            dir.mkdirs();
            File out = new File(dir, name.replaceAll("[^A-Za-z0-9._-]", "_"));
            try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
                 FileOutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 8 * 1024 * 1024) {
                        throw new IllegalStateException("File is too large for a plugin");
                    }
                    os.write(buf, 0, n);
                }
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> showError(this, "Install Plugin", "Can't read the file: " + e.getMessage()));
                return;
            }
            AndroidUtilities.runOnUIThread(() -> installPlugin(this, out));
        });
    }

    public static boolean isPluginFile(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase();
        return n.endsWith(".plugin") || n.endsWith(".py");
    }

    /** .plugin files always; .py only when it looks like an exteraGram plugin (otherwise Doc Viewer opens it). */
    public static boolean looksLikePlugin(File f, String fileName) {
        if (f == null || !f.exists() || f.length() > 8 * 1024 * 1024) {
            return false;
        }
        String name = (fileName != null ? fileName : f.getName()).toLowerCase();
        if (name.endsWith(".plugin")) {
            return true;
        }
        if (!name.endsWith(".py")) {
            return false;
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(f))) {
            boolean id = false, base = false;
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null && lines++ < 400) {
                id |= line.startsWith("__id__");
                base |= line.contains("BasePlugin");
            }
            return id && base;
        } catch (Exception e) {
            return false;
        }
    }

    /** Shows plugin info and installs it after confirmation. */
    public static void installPlugin(BaseFragment fragment, File file) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        AlertDialog progress = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.show();
        Utilities.globalQueue.postRunnable(() -> {
            String info = null;
            if (SgramPlugins.start()) {
                com.chaquo.python.PyObject r = SgramPlugins.call("inspect_file", file.getAbsolutePath());
                info = r != null ? r.toString() : null;
            }
            final String json = info;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    progress.dismiss();
                } catch (Exception ignore) {
                }
                if (fragment.getParentActivity() == null) {
                    return;
                }
                if (json == null) {
                    String err = SgramPlugins.getStartError();
                    showError(fragment, "Install Plugin", "Plugin engine is not available" + (err != null ? ": " + err : "."));
                    return;
                }
                try {
                    JSONObject meta = new JSONObject(json);
                    if (meta.has("error")) {
                        showError(fragment, "Not a plugin", meta.optString("error"));
                        return;
                    }
                    SgramGuardSheet.show(fragment, file, meta, () -> doInstall(fragment, file));
                } catch (Exception e) {
                    FileLog.e(e);
                }
            });
        });
    }

    private static void doInstall(BaseFragment fragment, File file) {
        Utilities.globalQueue.postRunnable(() -> {
            com.chaquo.python.PyObject r = SgramPlugins.call("install", file.getAbsolutePath());
            SgramPlugins.notifyChanged();
            String json = r != null ? r.toString() : "{\"error\":\"engine is not running\"}";
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    JSONObject res = new JSONObject(json);
                    if (!res.isNull("error") && res.has("error")) {
                        showError(fragment, "Plugin installed with errors", res.optString("error"));
                    } else if (fragment.getParentActivity() != null) {
                        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, "Plugin installed").show();
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            });
        });
    }
}
