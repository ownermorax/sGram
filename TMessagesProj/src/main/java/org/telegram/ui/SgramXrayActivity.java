package org.telegram.ui;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SgramXray;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** Xray как в Happ: подписки и серверы, пинг, выбор сервера. */
public class SgramXrayActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int TYPE_HEADER = 0, TYPE_CHECK = 1, TYPE_TEXT = 2, TYPE_DETAIL = 3, TYPE_SHADOW = 4;
    private static final int MENU_ADD = 1, MENU_TEST = 2, MENU_UPDATE = 3;

    private static class Row {
        int type;
        String key;
        SgramXray.Subscription sub;
        SgramXray.Server server;

        Row(int type, String key) {
            this.type = type;
            this.key = key;
        }
    }

    private final ArrayList<Row> rows = new ArrayList<>();
    private RecyclerListView listView;
    private ListAdapter adapter;
    private boolean testing;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yy HH:mm", Locale.US);

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.sgramXrayStateChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.sgramXrayStateChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.sgramXrayStateChanged) {
            if (args.length > 0 && args[0] instanceof String) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, (String) args[0]).show(true);
            }
            update();
        }
    }

    private SgramXray xray() {
        return SgramXray.getInstance();
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
        actionBar.setTitle("Xray VPN");
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_ADD, R.drawable.msg_add);
        menu.addItem(MENU_TEST, R.drawable.msg_speed);
        menu.addItem(MENU_UPDATE, R.drawable.msg_retry);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
                else if (id == MENU_ADD) showAddDialog();
                else if (id == MENU_TEST) testAll();
                else if (id == MENU_UPDATE) updateAll();
            }
        });

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frame;
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter = new ListAdapter(context));
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> onClick(view, rows.get(position)));
        listView.setOnItemLongClickListener((view, position) -> {
            Row row = rows.get(position);
            if (row.server != null) {
                showServerOptions(row.server);
                return true;
            }
            if (row.sub != null) {
                showSubOptions(row.sub);
                return true;
            }
            return false;
        });
        update();
        return fragmentView;
    }

    private void update() {
        rows.clear();
        rows.add(new Row(TYPE_CHECK, "connect"));
        rows.add(new Row(TYPE_CHECK, "only_tg"));
        rows.add(new Row(TYPE_TEXT, "add"));
        rows.add(new Row(TYPE_TEXT, "ua"));
        rows.add(new Row(TYPE_SHADOW, "s0"));
        SgramXray x = xray();
        synchronized (x) {
            // серверы без подписки (добавлены ссылкой)
            ArrayList<SgramXray.Server> manual = new ArrayList<>();
            for (SgramXray.Server s : x.servers) if (s.subId == null || x.getSubscription(s.subId) == null) manual.add(s);
            if (!manual.isEmpty()) {
                rows.add(new Row(TYPE_HEADER, "manual"));
                for (SgramXray.Server s : manual) {
                    Row r = new Row(TYPE_DETAIL, "server");
                    r.server = s;
                    rows.add(r);
                }
            }
            for (SgramXray.Subscription sub : x.subscriptions) {
                Row h = new Row(TYPE_DETAIL, "sub");
                h.sub = sub;
                rows.add(h);
                for (SgramXray.Server s : x.servers) {
                    if (!sub.id.equals(s.subId)) continue;
                    Row r = new Row(TYPE_DETAIL, "server");
                    r.server = s;
                    rows.add(r);
                }
                rows.add(new Row(TYPE_SHADOW, "s"));
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void onClick(View view, Row row) {
        switch (row.key) {
            case "connect": {
                SgramXray x = xray();
                if (SgramXray.isEnabled()) {
                    x.stop();
                } else {
                    if (x.getSelected() == null) {
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Add a subscription or a link first").show(true);
                        return;
                    }
                    // WireGuard и Xray одновременно не работают: в Android один VPN
                    if (!SgramXray.isOnlyTg() && org.telegram.messenger.SgramVpnManager.getInstance().isUp()) {
                        SgramXray.prefs_putBoolean("vpn_enabled", false);
                        org.telegram.messenger.SgramVpnManager.getInstance().stopVpn();
                    }
                    x.start();
                }
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(SgramXray.isEnabled());
                break;
            }
            case "only_tg": {
                boolean value = !SgramXray.isOnlyTg();
                SgramXray.prefs_putBoolean(SgramXray.PREF_ONLY_TG, value);
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(value);
                xray().restart();
                break;
            }
            case "add":
                showAddDialog();
                break;
            case "ua":
                showUaDialog();
                break;
            case "sub":
                showSubOptions(row.sub);
                break;
            case "server": {
                xray().selectedId = row.server.id;
                xray().save();
                update();
                if (SgramXray.isEnabled()) xray().restart();
                break;
            }
        }
    }

    private EditTextBoldCursor input(Activity activity, String hint, String text) {
        EditTextBoldCursor input = new EditTextBoldCursor(activity);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        input.setHint(hint);
        input.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMaxLines(4);
        if (text != null) input.setText(text);
        return input;
    }

    private void showInputDialog(String title, String hint, String text, String button, Utilities_Callback onSave) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        EditTextBoldCursor input = input(activity, hint, text);
        FrameLayout frame = new FrameLayout(activity);
        frame.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 24, 8, 24, 0));
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(title);
        builder.setView(frame);
        builder.setPositiveButton(button, (d, w) -> onSave.run(input.getText().toString().trim()));
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
        AndroidUtilities.runOnUIThread(() -> {
            input.requestFocus();
            AndroidUtilities.showKeyboard(input);
        }, 200);
    }

    private interface Utilities_Callback {
        void run(String value);
    }

    // Подписка (https://…) или ссылки vless://, vmess://, trojan://, ss://, hysteria2:// — из буфера подставляется сама
    private void showAddDialog() {
        String clip = null;
        try {
            ClipboardManager cm = (ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
                if (t != null && t.toString().contains("://")) clip = t.toString().trim();
            }
        } catch (Exception ignore) {
        }
        showInputDialog("Add Subscription or Link", "https://… or vless://…", clip, "Add", text -> {
            if (TextUtils.isEmpty(text)) return;
            AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
            progress.setCanCancel(false);
            progress.show();
            xray().addFromText(text, error -> {
                try {
                    progress.dismiss();
                } catch (Exception ignore) {
                }
                update();
                if (error != null) BulletinFactory.of(this).createSimpleBulletin(R.raw.error, error).show(true);
                else BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, "Added").show(true);
            });
        });
    }

    private void showUaDialog() {
        showInputDialog("User-Agent", SgramXray.DEFAULT_UA,
                SgramXray.prefs_getString(SgramXray.PREF_UA, SgramXray.DEFAULT_UA), "Save", text -> {
                    SgramXray.prefs_putString(SgramXray.PREF_UA, TextUtils.isEmpty(text) ? SgramXray.DEFAULT_UA : text);
                    update();
                });
    }

    private void updateAll() {
        ArrayList<SgramXray.Subscription> subs;
        synchronized (xray()) {
            subs = new ArrayList<>(xray().subscriptions);
        }
        if (subs.isEmpty()) return;
        int[] left = {subs.size()};
        for (SgramXray.Subscription s : subs) {
            xray().updateSubscription(s, error -> {
                if (error != null) BulletinFactory.of(this).createSimpleBulletin(R.raw.error, s.name + ": " + error).show(true);
                if (--left[0] == 0) {
                    update();
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, "Subscriptions updated").show(true);
                }
            });
        }
    }

    private void testAll() {
        if (testing) return;
        ArrayList<SgramXray.Server> list;
        synchronized (xray()) {
            list = new ArrayList<>(xray().servers);
        }
        if (list.isEmpty()) return;
        testing = true;
        xray().measureAll(list, () -> {
            if (adapter != null) adapter.notifyDataSetChanged();
        }, () -> {
            testing = false;
            if (adapter != null) adapter.notifyDataSetChanged();
        });
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void showSubOptions(SgramXray.Subscription sub) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(sub.name);
        builder.setItems(new CharSequence[]{"Update", "Rename", "Copy URL", "Delete"}, (d, which) -> {
            if (which == 0) {
                xray().updateSubscription(sub, error -> {
                    update();
                    if (error != null) BulletinFactory.of(this).createSimpleBulletin(R.raw.error, error).show(true);
                });
            } else if (which == 1) {
                showInputDialog("Rename", "Name", sub.name, "Save", text -> {
                    if (!TextUtils.isEmpty(text)) {
                        sub.name = text;
                        xray().save();
                        update();
                    }
                });
            } else if (which == 2) {
                AndroidUtilities.addToClipboard(sub.url);
                BulletinFactory.of(this).createCopyBulletin("URL copied").show(true);
            } else {
                xray().removeSubscription(sub);
                update();
            }
        });
        showDialog(builder.create());
    }

    private void showServerOptions(SgramXray.Server server) {
        if (getParentActivity() == null) return;
        ArrayList<CharSequence> items = new ArrayList<>();
        items.add("Test");
        if (server.link != null) items.add("Copy Link");
        items.add("Delete");
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(server.name);
        builder.setItems(items.toArray(new CharSequence[0]), (d, which) -> {
            CharSequence item = items.get(which);
            if ("Test".contentEquals(item)) {
                ArrayList<SgramXray.Server> one = new ArrayList<>();
                one.add(server);
                xray().measureAll(one, () -> adapter.notifyDataSetChanged(), () -> adapter.notifyDataSetChanged());
                adapter.notifyDataSetChanged();
            } else if ("Copy Link".contentEquals(item)) {
                AndroidUtilities.addToClipboard(server.link);
                BulletinFactory.of(this).createCopyBulletin("Link copied").show(true);
            } else {
                xray().removeServer(server);
                update();
            }
        });
        showDialog(builder.create());
    }

    private static String protocol(SgramXray.Server s) {
        if (s.json != null) return "JSON";
        int i = s.link.indexOf("://");
        return i > 0 ? s.link.substring(0, i).toUpperCase(Locale.ROOT) : "?";
    }

    private static String pingText(SgramXray.Server s) {
        if (s.ping == -3) return "testing…";
        if (s.ping == -1) return "timeout";
        if (s.ping < 0) return null;
        return s.ping + " ms";
    }

    private String subDetail(SgramXray.Subscription sub) {
        StringBuilder sb = new StringBuilder();
        if (sub.total > 0) {
            sb.append(SgramXray.formatBytes(sub.upload + sub.download)).append(" / ").append(SgramXray.formatBytes(sub.total));
        } else if (sub.upload + sub.download > 0) {
            sb.append(SgramXray.formatBytes(sub.upload + sub.download)).append(" used");
        }
        if (sub.expire > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("until ").append(dateFormat.format(new Date(sub.expire * 1000L)));
        }
        if (sb.length() > 0) sb.append('\n');
        sb.append("updated ").append(sub.updated > 0 ? dateFormat.format(new Date(sub.updated)) : "never");
        return sb.toString();
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
            return rows.get(position).type;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int t = holder.getItemViewType();
            return t == TYPE_CHECK || t == TYPE_TEXT || t == TYPE_DETAIL;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case TYPE_HEADER: view = new HeaderCell(context); break;
                case TYPE_CHECK: view = new TextCheckCell(context); break;
                case TYPE_DETAIL: {
                    TextDetailSettingsCell cell = new TextDetailSettingsCell(context);
                    cell.setMultilineDetail(true);
                    view = cell;
                    break;
                }
                case TYPE_SHADOW: view = new ShadowSectionCell(context); break;
                default: view = new TextSettingsCell(context); break;
            }
            if (viewType != TYPE_SHADOW) view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            View v = holder.itemView;
            boolean divider = position + 1 < rows.size() && rows.get(position + 1).type != TYPE_SHADOW;
            switch (row.key) {
                case "connect": {
                    SgramXray.Server s = xray().getSelected();
                    String state = xray().isRunning() ? "Connected" : SgramXray.isEnabled() ? "Connecting…" : "Disconnected";
                    ((TextCheckCell) v).setTextAndValueAndCheck("Connect", state + (s != null ? " · " + s.name : ""), SgramXray.isEnabled(), true, divider);
                    break;
                }
                case "only_tg":
                    ((TextCheckCell) v).setTextAndValueAndCheck("Only for sGram", "Off — whole phone through VPN (except excluded apps)", SgramXray.isOnlyTg(), true, divider);
                    break;
                case "add":
                    ((TextSettingsCell) v).setText("Add Subscription or Link", divider);
                    ((TextSettingsCell) v).setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
                    break;
                case "ua":
                    ((TextSettingsCell) v).setTextAndValue("User-Agent", SgramXray.prefs_getString(SgramXray.PREF_UA, SgramXray.DEFAULT_UA), divider);
                    ((TextSettingsCell) v).setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    break;
                case "manual":
                    ((HeaderCell) v).setText("Servers");
                    break;
                case "sub":
                    ((TextDetailSettingsCell) v).setTextAndValue("📂 " + (TextUtils.isEmpty(row.sub.name) ? row.sub.url : row.sub.name), subDetail(row.sub), divider);
                    break;
                case "server": {
                    SgramXray.Server s = row.server;
                    boolean selected = s.id.equals(xray().selectedId);
                    String ping = pingText(s);
                    ((TextDetailSettingsCell) v).setTextAndValue((selected ? "✅ " : "") + s.name, protocol(s) + (ping != null ? " · " + ping : ""), divider);
                    break;
                }
            }
        }
    }
}
