package org.telegram.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import com.chaquo.python.PyObject;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SgramPlugins;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * sGram: one plugin — enable switch, info, the plugin's own settings (create_settings()),
 * logs, share and delete. Also used for settings sub-pages (Text.create_sub_fragment).
 */
public class SgramPluginSettingsActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_ENABLED = 1;
    private static final int ID_LOGS = 2;
    private static final int ID_SHARE = 3;
    private static final int ID_DELETE = 4;
    private static final int ID_SETTING = 1000;

    private final String pluginId;
    private final PyObject subFragment; // callable returning settings for a sub-page, or null
    private final String subTitle;

    private JSONObject info;
    private List<PyObject> settings = new ArrayList<>();

    public SgramPluginSettingsActivity(String pluginId) {
        this(pluginId, null, null);
    }

    private SgramPluginSettingsActivity(String pluginId, PyObject subFragment, String subTitle) {
        this.pluginId = pluginId;
        this.subFragment = subFragment;
        this.subTitle = subTitle;
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.sgramPluginsChanged);
        load();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.sgramPluginsChanged);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.sgramPluginsChanged) {
            load();
            if (listView != null) {
                listView.adapter.update(true);
            }
        }
    }

    private void load() {
        info = null;
        try {
            PyObject r = SgramPlugins.call("list_plugins");
            if (r != null) {
                JSONArray array = new JSONArray(r.toString());
                for (int i = 0; i < array.length(); i++) {
                    if (pluginId.equals(array.getJSONObject(i).optString("id"))) {
                        info = array.getJSONObject(i);
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        settings = new ArrayList<>();
        try {
            PyObject list = subFragment != null ? SgramPlugins.call("call_safely", pluginId, subFragment) : SgramPlugins.call("get_settings", pluginId);
            if (list != null) {
                settings = list.asList();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    protected CharSequence getTitle() {
        if (subTitle != null) {
            return subTitle;
        }
        return info != null ? info.optString("name") : pluginId;
    }

    private static String str(PyObject item, String field) {
        PyObject v = item.get(field);
        return v == null ? null : v.toString();
    }

    private static PyObject attr(PyObject item, String field) {
        try {
            return item.get(field);
        } catch (Exception e) {
            return null;
        }
    }

    private Object value(PyObject item) {
        PyObject key = attr(item, "key");
        PyObject def = attr(item, "default");
        if (key == null) {
            return def != null ? def.toJava(Object.class) : null;
        }
        PyObject v = SgramPlugins.call("get_setting_value", pluginId, key.toString(), def);
        return v != null ? v.toJava(Object.class) : null;
    }

    private static int icon(PyObject item) {
        String name = str(item, "icon");
        if (TextUtils.isEmpty(name)) {
            return 0;
        }
        return ApplicationLoader.applicationContext.getResources().getIdentifier(name, "drawable", ApplicationLoader.applicationContext.getPackageName());
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (subFragment == null) {
            if (info == null) {
                items.add(UItem.asShadow("Plugin not found."));
                return;
            }
            items.add(UItem.asCheck(ID_ENABLED, "Enabled").setChecked(info.optBoolean("enabled")));
            StringBuilder about = new StringBuilder();
            about.append("Version ").append(info.optString("version"));
            if (!TextUtils.isEmpty(info.optString("author"))) {
                about.append(" · by ").append(info.optString("author"));
            }
            if (!TextUtils.isEmpty(info.optString("description"))) {
                about.append("\n\n").append(info.optString("description"));
            }
            if (!info.isNull("error") && info.has("error")) {
                about.append("\n\n⚠ ").append(info.optString("error"));
            }
            items.add(UItem.asShadow(about.toString()));
        }

        for (int i = 0; i < settings.size(); i++) {
            PyObject item = settings.get(i);
            if (item == null) {
                continue;
            }
            String type = str(item, "type");
            if (type == null) {
                continue;
            }
            int id = ID_SETTING + i;
            String text = str(item, "text");
            switch (type) {
                case "header":
                    items.add(UItem.asHeader(text));
                    break;
                case "divider":
                    items.add(UItem.asShadow(text));
                    break;
                case "switch": {
                    Object v = value(item);
                    String sub = str(item, "subtext");
                    UItem u = TextUtils.isEmpty(sub) ? UItem.asCheck(id, text) : UItem.asButtonCheck(id, text, sub);
                    items.add(u.setChecked(Boolean.TRUE.equals(v)));
                    break;
                }
                case "selector": {
                    Object v = value(item);
                    List<PyObject> choices = item.get("items").asList();
                    int index = v instanceof Number ? ((Number) v).intValue() : 0;
                    String current = index >= 0 && index < choices.size() ? choices.get(index).toString() : "";
                    items.add(button(id, icon(item), text, current));
                    break;
                }
                case "input":
                case "edit_text": {
                    Object v = value(item);
                    String label = "edit_text".equals(type) ? str(item, "hint") : text;
                    String shown = v != null && !TextUtils.isEmpty(v.toString()) ? v.toString() : str(item, "subtext");
                    if (shown != null && shown.length() > 40) {
                        shown = shown.substring(0, 40) + "…";
                    }
                    items.add(button(id, icon(item), label, shown));
                    break;
                }
                case "text": {
                    UItem u = button(id, icon(item), text, str(item, "subtext"));
                    PyObject red = attr(item, "red");
                    PyObject accent = attr(item, "accent");
                    if (red != null && red.toBoolean()) {
                        u.red();
                    } else if (accent != null && accent.toBoolean()) {
                        u.accent();
                    }
                    items.add(u);
                    break;
                }
                case "custom": {
                    PyObject view = attr(item, "view");
                    if (view != null) {
                        Object v = view.toJava(Object.class);
                        if (v instanceof View) {
                            View cv = (View) v;
                            if (cv.getParent() instanceof ViewGroup) {
                                ((ViewGroup) cv.getParent()).removeView(cv);
                            }
                            items.add(UItem.asCustom(id, cv));
                        }
                    }
                    break;
                }
            }
        }

        if (subFragment == null) {
            if (!settings.isEmpty()) {
                items.add(UItem.asShadow(null));
            }
            items.add(UItem.asButton(ID_LOGS, R.drawable.msg_log, "Logs"));
            items.add(UItem.asButton(ID_SHARE, R.drawable.msg_share, "Share Plugin File"));
            items.add(UItem.asButton(ID_DELETE, R.drawable.msg_delete, "Delete Plugin").red());
            items.add(UItem.asShadow(null));
        }
    }

    private static UItem button(int id, int icon, CharSequence text, CharSequence value) {
        if (icon != 0) {
            return TextUtils.isEmpty(value) ? UItem.asButton(id, icon, text) : UItem.asButton(id, icon, text, value);
        }
        return TextUtils.isEmpty(value) ? UItem.asButton(id, text) : UItem.asButton(id, text, value);
    }

    @Override
    protected void onClick(UItem uItem, View view, int position, float x, float y) {
        switch (uItem.id) {
            case ID_ENABLED:
                if (info != null) {
                    SgramPluginsActivity.setPluginEnabled(this, pluginId, !info.optBoolean("enabled"));
                }
                return;
            case ID_LOGS:
                showLogs();
                return;
            case ID_SHARE:
                shareFile();
                return;
            case ID_DELETE:
                confirmDelete();
                return;
        }
        int index = uItem.id - ID_SETTING;
        if (index < 0 || index >= settings.size()) {
            return;
        }
        PyObject item = settings.get(index);
        String type = str(item, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "switch": {
                boolean checked = !Boolean.TRUE.equals(value(item));
                SgramPlugins.call("setting_changed", pluginId, item, checked);
                refresh();
                break;
            }
            case "selector": {
                List<PyObject> choices = item.get("items").asList();
                CharSequence[] names = new CharSequence[choices.size()];
                Object v = value(item);
                int current = v instanceof Number ? ((Number) v).intValue() : 0;
                for (int i = 0; i < names.length; i++) {
                    names[i] = (i == current ? "✓ " : "") + choices.get(i).toString();
                }
                AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
                b.setTitle(str(item, "text"));
                b.setItems(names, (d, which) -> {
                    SgramPlugins.call("setting_changed", pluginId, item, which);
                    refresh();
                });
                b.setNegativeButton("Cancel", null);
                showDialog(b.create());
                break;
            }
            case "input":
            case "edit_text":
                showInput(item, "edit_text".equals(type));
                break;
            case "text":
            case "custom": {
                PyObject sub = attr(item, "create_sub_fragment");
                PyObject onClick = attr(item, "on_click");
                if (sub != null) {
                    presentFragment(new SgramPluginSettingsActivity(pluginId, sub, str(item, "text")));
                } else if (onClick != null) {
                    SgramPlugins.call("call_safely", pluginId, onClick, view);
                    AndroidUtilities.runOnUIThread(this::refresh, 300);
                }
                break;
            }
        }
    }

    @Override
    protected boolean onLongClick(UItem uItem, View view, int position, float x, float y) {
        int index = uItem.id - ID_SETTING;
        if (index < 0 || index >= settings.size()) {
            return false;
        }
        PyObject cb = attr(settings.get(index), "on_long_click");
        if (cb == null) {
            return false;
        }
        SgramPlugins.call("call_safely", pluginId, cb, view);
        return true;
    }

    private void refresh() {
        load();
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void showInput(PyObject item, boolean editText) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        Object v = value(item);
        EditTextBoldCursor input = new EditTextBoldCursor(activity);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        input.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        boolean multiline = editText && attr(item, "multiline") != null && item.get("multiline").toBoolean();
        if (multiline) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            input.setMaxLines(8);
        } else {
            input.setSingleLine();
        }
        PyObject maxLength = attr(item, "max_length");
        if (maxLength != null && maxLength.toInt() > 0) {
            input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(maxLength.toInt())});
        }
        String hint = str(item, editText ? "hint" : "subtext");
        if (hint != null) {
            input.setHint(hint);
        }
        input.setText(v != null ? v.toString() : "");
        input.setSelection(input.length());
        FrameLayout frame = new FrameLayout(activity);
        frame.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 24, 8, 24, 0));
        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle(editText ? str(item, "hint") : str(item, "text"));
        b.setView(frame);
        b.setPositiveButton("Save", (d, w) -> {
            SgramPlugins.call("setting_changed", pluginId, item, input.getText().toString());
            refresh();
        });
        b.setNegativeButton("Cancel", null);
        showDialog(b.create());
        AndroidUtilities.runOnUIThread(() -> {
            input.requestFocus();
            AndroidUtilities.showKeyboard(input);
        }, 200);
    }

    private void showLogs() {
        PyObject r = SgramPlugins.call("get_logs", pluginId);
        String logs = r != null ? r.toString() : "";
        if (logs.isEmpty()) {
            logs = "No log messages yet.";
        }
        final String report = logs;
        TextView textView = new TextView(getParentActivity());
        textView.setText(report);
        textView.setTextIsSelectable(true);
        textView.setTypeface(android.graphics.Typeface.MONOSPACE);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
        textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        textView.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        ScrollView scrollView = new ScrollView(getParentActivity());
        scrollView.addView(textView);
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle("Logs");
        b.setView(scrollView);
        b.setPositiveButton("Copy", (d, w) -> {
            AndroidUtilities.addToClipboard(report);
            BulletinFactory.of(this).createCopyBulletin("Logs copied").show();
        });
        b.setNegativeButton("Close", null);
        showDialog(b.create());
    }

    private void shareFile() {
        if (info == null) {
            return;
        }
        try {
            File src = new File(info.optString("path"));
            File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "sgram_plugin_share");
            dir.mkdirs();
            File out = new File(dir, pluginId + ".plugin");
            AndroidUtilities.copyFile(src, out);
            Uri uri = FileProvider.getUriForFile(getParentActivity(), ApplicationLoader.getApplicationId() + ".provider", out);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getParentActivity().startActivity(Intent.createChooser(intent, "Share Plugin"));
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Can't share: " + e.getMessage()).show();
        }
    }

    private void confirmDelete() {
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle("Delete Plugin");
        b.setMessage("Delete " + (info != null ? info.optString("name") : pluginId) + " and its settings?");
        b.setPositiveButton("Delete", (d, w) -> Utilities.globalQueue.postRunnable(() -> {
            SgramPlugins.call("delete", pluginId);
            SgramPlugins.notifyChanged();
            AndroidUtilities.runOnUIThread(this::finishFragment);
        }));
        b.setNegativeButton("Cancel", null);
        AlertDialog dialog = b.create();
        showDialog(dialog);
        View button = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }
}
