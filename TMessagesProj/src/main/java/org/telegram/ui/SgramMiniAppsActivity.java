package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.SgramMiniAppTools;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

// Настройки инструментов мини-аппов (по мотивам плагина MiniApp Injector by @huecoder)
public class SgramMiniAppsActivity extends BaseFragment {

    private static final int TYPE_HEADER = 0, TYPE_CHECK = 1, TYPE_ACTION = 2, TYPE_SHADOW = 3, TYPE_INFO = 4;

    private static final int ROW_DEVTOOLS = 1, ROW_ERUDA = 2, ROW_UNLOCK = 3, ROW_ZOOM = 4, ROW_DEBUG = 5,
            ROW_SCRIPTS = 6, ROW_ADD_SCRIPT = 7, ROW_SCRIPTS_INFO = 8, ROW_TOOLS = 9, ROW_INSPECTOR = 10,
            ROW_BROWSER = 11, ROW_CLEAR = 12, ROW_CLEAR_SCRIPTS = 13, ROW_SHADOW = 14, ROW_SOURCE = 1000;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private final ArrayList<Integer> rows = new ArrayList<>();
    private ArrayList<SgramMiniAppTools.Source> sources = new ArrayList<>();

    private void buildRows() {
        sources = SgramMiniAppTools.getSources();
        rows.clear();
        rows.add(ROW_DEVTOOLS);
        rows.add(ROW_ERUDA);
        rows.add(ROW_UNLOCK);
        rows.add(ROW_ZOOM);
        rows.add(ROW_DEBUG);
        rows.add(ROW_SHADOW);
        rows.add(ROW_SCRIPTS);
        for (int i = 0; i < sources.size(); i++) rows.add(ROW_SOURCE + i);
        rows.add(ROW_ADD_SCRIPT);
        rows.add(ROW_SCRIPTS_INFO);
        rows.add(ROW_TOOLS);
        rows.add(ROW_INSPECTOR);
        rows.add(ROW_BROWSER);
        rows.add(ROW_CLEAR);
        rows.add(ROW_CLEAR_SCRIPTS);
        rows.add(ROW_SHADOW);
    }

    @Override
    public View createView(Context context) {
        // sGram: уведомления сверху, под шапкой — снизу их закрывает панель вкладок
        org.telegram.ui.Components.Bulletin.addDelegate(this, new org.telegram.ui.Components.Bulletin.Delegate() {
            @Override
            public int getTopOffset(int tag) {
                return AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight();
            }
        });
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Mini Apps");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        buildRows();
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter = new ListAdapter(context));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            int row = rows.get(position);
            if (row == ROW_ERUDA || row == ROW_UNLOCK || row == ROW_ZOOM || row == ROW_DEBUG) {
                String key = prefKey(row);
                boolean value = !SgramMiniAppTools.get(key);
                SgramMiniAppTools.set(key, value);
                ((TextCheckCell) view).setChecked(value);
            } else if (row >= ROW_SOURCE) {
                SgramMiniAppTools.Source s = sources.get(row - ROW_SOURCE);
                s.on = !s.on;
                SgramMiniAppTools.saveSources(sources);
                ((TextCheckCell) view).setChecked(s.on);
            } else if (row == ROW_ADD_SCRIPT) {
                showAddScript();
            } else if (row == ROW_INSPECTOR) {
                showInspector();
            } else if (row == ROW_BROWSER) {
                String url = SgramMiniAppTools.getLastUrl();
                if (TextUtils.isEmpty(url)) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "Open a mini app first").show(true);
                    return;
                }
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    getParentActivity().startActivity(intent);
                } catch (Exception ignore) {}
            } else if (row == ROW_CLEAR) {
                SgramMiniAppTools.clearWebData();
                BulletinFactory.of(this).createSimpleBulletin(R.raw.done, "Mini app cookies and cache cleared").show(true);
            } else if (row == ROW_CLEAR_SCRIPTS) {
                SgramMiniAppTools.clearScriptCache();
                BulletinFactory.of(this).createSimpleBulletin(R.raw.done, "Scripts will be downloaded again").show(true);
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            int row = rows.get(position);
            if (row < ROW_SOURCE) return false;
            SgramMiniAppTools.Source s = sources.get(row - ROW_SOURCE);
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle("Remove Script");
            builder.setMessage(s.url);
            builder.setPositiveButton("Remove", (d, w) -> {
                sources.remove(s);
                SgramMiniAppTools.saveSources(sources);
                buildRows();
                adapter.notifyDataSetChanged();
            });
            builder.setNegativeButton("Cancel", null);
            showDialog(builder.create());
            return true;
        });
        return fragmentView;
    }

    private static String prefKey(int row) {
        switch (row) {
            case ROW_ERUDA: return SgramMiniAppTools.PREF_ERUDA;
            case ROW_UNLOCK: return SgramMiniAppTools.PREF_UNLOCK;
            case ROW_ZOOM: return SgramMiniAppTools.PREF_ZOOM;
            default: return SgramMiniAppTools.PREF_REMOTE_DEBUG;
        }
    }

    private void showAddScript() {
        Context context = getParentActivity();
        if (context == null) return;
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        TextView warning = new TextView(context);
        warning.setText("The script runs inside the mini app with access to its initData and session. Add only scripts you trust.");
        warning.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        warning.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        layout.addView(warning, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 12));
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setHint("https://cdn.jsdelivr.net/npm/vconsole");
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        editText.setSingleLine(true);
        editText.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        layout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 24, 0, 24, 8));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Add Script");
        builder.setView(layout);
        builder.setPositiveButton("Add", (d, w) -> {
            String url = editText.getText().toString().trim();
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Enter a link to a .js file").show(true);
                return;
            }
            SgramMiniAppTools.addSource(url);
            buildRows();
            adapter.notifyDataSetChanged();
        });
        builder.setNegativeButton("Cancel", null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        // Пауза перед подтверждением, как в оригинальном плагине: успеть прочитать предупреждение
        View button = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            TextView btn = (TextView) button;
            btn.setEnabled(false);
            for (int i = 3; i >= 0; i--) {
                final int left = i;
                AndroidUtilities.runOnUIThread(() -> {
                    btn.setText(left == 0 ? "ADD" : "ADD (" + left + ")");
                    btn.setEnabled(left == 0);
                }, (3 - i) * 1000L);
            }
        }
    }

    private void showInspector() {
        String info = SgramMiniAppTools.getLastInfo();
        if (TextUtils.isEmpty(info)) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "Open a mini app first").show(true);
            return;
        }
        TextView textView = new TextView(getParentActivity());
        textView.setText(info);
        textView.setTextIsSelectable(true);
        textView.setTypeface(android.graphics.Typeface.MONOSPACE);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        textView.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(8));
        android.widget.ScrollView scrollView = new android.widget.ScrollView(getParentActivity());
        scrollView.addView(textView);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("WebApp Inspector");
        builder.setView(scrollView);
        builder.setPositiveButton("Copy", (d, w) -> {
            AndroidUtilities.addToClipboard(info);
            BulletinFactory.of(this).createCopyBulletin("Copied to clipboard").show(true);
        });
        builder.setNegativeButton("Close", null);
        showDialog(builder.create());
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            int row = rows.get(position);
            if (row == ROW_DEVTOOLS || row == ROW_SCRIPTS || row == ROW_TOOLS) return TYPE_HEADER;
            if (row == ROW_SHADOW) return TYPE_SHADOW;
            if (row == ROW_SCRIPTS_INFO) return TYPE_INFO;
            if (row == ROW_ERUDA || row == ROW_UNLOCK || row == ROW_ZOOM || row == ROW_DEBUG || row >= ROW_SOURCE) return TYPE_CHECK;
            return TYPE_ACTION;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == TYPE_CHECK || type == TYPE_ACTION;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case TYPE_HEADER: view = new HeaderCell(context); break;
                case TYPE_CHECK: view = new TextCheckCell(context); break;
                case TYPE_SHADOW: view = new ShadowSectionCell(context); break;
                case TYPE_INFO: view = new TextInfoPrivacyCell(context); break;
                default: view = new TextSettingsCell(context); break;
            }
            if (viewType != TYPE_SHADOW && viewType != TYPE_INFO) {
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int row = rows.get(position);
            View v = holder.itemView;
            switch (row) {
                case ROW_DEVTOOLS: ((HeaderCell) v).setText("DevTools"); break;
                case ROW_SCRIPTS: ((HeaderCell) v).setText("Custom Scripts"); break;
                case ROW_TOOLS: ((HeaderCell) v).setText("Tools"); break;
                case ROW_ERUDA:
                    ((TextCheckCell) v).setTextAndValueAndCheck("Eruda Console", "Console, network and storage. Page CSP is disabled while DevTools or scripts are on", SgramMiniAppTools.get(SgramMiniAppTools.PREF_ERUDA), true, true);
                    break;
                case ROW_UNLOCK:
                    ((TextCheckCell) v).setTextAndCheck("Unlock Text Selection", SgramMiniAppTools.get(SgramMiniAppTools.PREF_UNLOCK), true);
                    break;
                case ROW_ZOOM:
                    ((TextCheckCell) v).setTextAndCheck("Force Zoom", SgramMiniAppTools.get(SgramMiniAppTools.PREF_ZOOM), true);
                    break;
                case ROW_DEBUG:
                    ((TextCheckCell) v).setTextAndValueAndCheck("Remote Debugging", "Inspect from PC via chrome://inspect", SgramMiniAppTools.get(SgramMiniAppTools.PREF_REMOTE_DEBUG), false, false);
                    break;
                case ROW_ADD_SCRIPT: ((TextSettingsCell) v).setTextAndIcon("Add Script", R.drawable.msg_add, false); break;
                case ROW_SCRIPTS_INFO:
                    ((TextInfoPrivacyCell) v).setText("Scripts are downloaded once, cached and injected into every mini app. Long press a script to remove it.");
                    break;
                case ROW_INSPECTOR: ((TextSettingsCell) v).setTextAndIcon("WebApp Inspector", R.drawable.msg_info, true); break;
                case ROW_BROWSER: ((TextSettingsCell) v).setTextAndIcon("Open Last App in Browser", R.drawable.msg_language, true); break;
                case ROW_CLEAR: ((TextSettingsCell) v).setTextAndIcon("Clear Cookies and Cache", R.drawable.msg_clear, true); break;
                case ROW_CLEAR_SCRIPTS: ((TextSettingsCell) v).setTextAndIcon("Re-download Scripts", R.drawable.msg_retry, false); break;
                default:
                    if (row >= ROW_SOURCE) {
                        SgramMiniAppTools.Source s = sources.get(row - ROW_SOURCE);
                        String name = s.url.substring(s.url.lastIndexOf('/') + 1);
                        ((TextCheckCell) v).setTextAndValueAndCheck(TextUtils.isEmpty(name) ? s.url : name, s.url, s.on, false, true);
                    }
                    break;
            }
        }
    }
}
