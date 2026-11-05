package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SgramDeletedController;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class SgramDeletedMessagesActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter adapter;
    private TextView emptyView;
    private ArrayList<SgramDeletedController.Entry> entries = new ArrayList<>();
    private ArrayList<SgramDeletedController.EditedEntry> edited = new ArrayList<>();
    // 0 — удалённые, 1 — изменённые
    private int tab;
    private TextView[] tabViews = new TextView[2];
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yy HH:mm:ss", Locale.US);

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
        actionBar.setTitle("Message History");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        LinearLayout tabs = new LinearLayout(context);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        String[] titles = {"Deleted", "Edited"};
        for (int i = 0; i < 2; i++) {
            final int t = i;
            TextView tv = new TextView(context);
            tv.setText(titles[i]);
            tv.setGravity(Gravity.CENTER);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            tv.setTypeface(AndroidUtilities.bold());
            tv.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
            tv.setOnClickListener(v -> selectTab(t));
            tabViews[i] = tv;
            tabs.addView(tv, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        }
        frameLayout.addView(tabs, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44, Gravity.TOP));

        emptyView = new TextView(context);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 44, 0, 0));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter = new ListAdapter(context));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 44, 0, 0));

        listView.setOnItemClickListener((view, position) -> {
            if (tab == 1) {
                if (position >= 0 && position < edited.size()) showEditHistory(edited.get(position));
                return;
            }
            if (position < 0 || position >= entries.size()) return;
            SgramDeletedController.Entry e = entries.get(position);
            if (!TextUtils.isEmpty(e.mediaPath) && new File(e.mediaPath).exists()) {
                File f = new File(e.mediaPath);
                AndroidUtilities.openForView(f, f.getName(), null, getParentActivity(), null, false);
            } else {
                openChat(e.dialogId, e.mid);
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (tab == 1) {
                if (position < 0 || position >= edited.size()) return false;
                showEditedOptions(edited.get(position));
                return true;
            }
            if (position < 0 || position >= entries.size()) return false;
            showOptions(entries.get(position));
            return true;
        });

        updateTabs();
        load();
        return fragmentView;
    }

    private void selectTab(int t) {
        if (tab == t) return;
        tab = t;
        updateTabs();
        if (adapter != null) adapter.notifyDataSetChanged();
        updateEmpty();
    }

    private void updateTabs() {
        for (int i = 0; i < 2; i++) {
            tabViews[i].setTextColor(Theme.getColor(i == tab ? Theme.key_windowBackgroundWhiteBlueHeader : Theme.key_windowBackgroundWhiteGrayText));
        }
    }

    private void updateEmpty() {
        int count = tab == 0 ? entries.size() : edited.size();
        if (emptyView != null) {
            emptyView.setText(tab == 0 ? "No deleted messages yet" : (SgramDeletedController.isEditHistoryEnabled() ? "No edited messages yet" : "Edit history is off.\nTurn on Save Edit History in sGram settings."));
            emptyView.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        }
        actionBar.setSubtitle(entries.size() + " deleted · " + edited.size() + " edited");
    }

    private void load() {
        Utilities.globalQueue.postRunnable(() -> {
            SgramDeletedController controller = SgramDeletedController.getInstance(currentAccount);
            ArrayList<SgramDeletedController.Entry> list = controller.loadEntries();
            ArrayList<SgramDeletedController.EditedEntry> editedList = controller.loadEditedEntries();
            AndroidUtilities.runOnUIThread(() -> {
                entries = list;
                edited = editedList;
                if (adapter != null) adapter.notifyDataSetChanged();
                updateEmpty();
            });
        });
    }

    private void openChat(long dialogId, int mid) {
        Bundle args = new Bundle();
        if (dialogId > 0) {
            args.putLong("user_id", dialogId);
        } else {
            args.putLong("chat_id", -dialogId);
        }
        args.putInt("message_id", mid);
        presentFragment(new ChatActivity(args));
    }

    // Все версии сверху вниз: от самой старой к текущей
    private void showEditHistory(SgramDeletedController.EditedEntry entry) {
        SgramDeletedController controller = SgramDeletedController.getInstance(currentAccount);
        getMessagesStorage().getStorageQueue().postRunnable(() -> {
            TLRPC.Message current = controller.loadCurrentMessage(entry.dialogId, entry.mid);
            ArrayList<SgramDeletedController.EditVersion> versions = controller.loadEdits(entry.dialogId, entry.mid);
            AndroidUtilities.runOnUIThread(() -> {
                if (getParentActivity() == null) return;
                SpannableStringBuilder sb = new SpannableStringBuilder();
                int total = versions.size() + (current != null ? 1 : 0);
                for (int i = 0; i < total; i++) {
                    boolean isCurrent = i == versions.size();
                    int date = isCurrent ? (current.edit_date != 0 ? current.edit_date : current.date) : versions.get(i).date;
                    String text = isCurrent ? current.message : versions.get(i).text;
                    String media = isCurrent ? null : versions.get(i).mediaType;
                    if (sb.length() > 0) sb.append("\n\n");
                    int start = sb.length();
                    sb.append(dateFormat.format(new Date(date * 1000L))).append(isCurrent ? " · current" : "");
                    sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), 0);
                    sb.append('\n');
                    if (!TextUtils.isEmpty(media)) sb.append('[').append(media).append("] ");
                    sb.append(TextUtils.isEmpty(text) ? "(empty)" : text);
                }
                TextView textView = new TextView(getParentActivity());
                textView.setText(sb);
                textView.setTextIsSelectable(true);
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
                textView.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(8));
                ScrollView scrollView = new ScrollView(getParentActivity());
                scrollView.addView(textView);
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setTitle(peerName(entry.dialogId, entry.chatTitle));
                builder.setView(scrollView);
                builder.setPositiveButton("Open in Chat", (d, w) -> openChat(entry.dialogId, entry.mid));
                builder.setNegativeButton("Close", null);
                showDialog(builder.create());
            });
        });
    }

    private void showEditedOptions(SgramDeletedController.EditedEntry entry) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(peerName(entry.dialogId, entry.chatTitle));
        builder.setItems(new CharSequence[]{"Show History", "Open in Chat", "Delete History"}, (dialog, which) -> {
            if (which == 0) {
                showEditHistory(entry);
            } else if (which == 1) {
                openChat(entry.dialogId, entry.mid);
            } else {
                Utilities.globalQueue.postRunnable(() -> {
                    SgramDeletedController.getInstance(currentAccount).removeEdits(entry.dialogId, entry.mid);
                    AndroidUtilities.runOnUIThread(this::load);
                });
            }
        });
        showDialog(builder.create());
    }

    private void showOptions(SgramDeletedController.Entry e) {
        if (getParentActivity() == null) return;
        ArrayList<CharSequence> items = new ArrayList<>();
        ArrayList<Integer> actions = new ArrayList<>();
        if (!TextUtils.isEmpty(e.text)) {
            items.add("Copy Text");
            actions.add(0);
        }
        items.add("Open in Chat");
        actions.add(1);
        items.add("Delete");
        actions.add(2);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(chatTitle(e));
        builder.setItems(items.toArray(new CharSequence[0]), (dialog, which) -> {
            int action = actions.get(which);
            if (action == 0) {
                AndroidUtilities.addToClipboard(e.text);
                BulletinFactory.of(this).createCopyBulletin("Text copied").show(true);
            } else if (action == 1) {
                openChat(e.dialogId, e.mid);
            } else {
                Utilities.globalQueue.postRunnable(() -> {
                    SgramDeletedController.getInstance(currentAccount).removeEntry(e.dialogId, e.mid);
                    AndroidUtilities.runOnUIThread(this::load);
                });
            }
        });
        showDialog(builder.create());
    }

    private String peerName(long peerId, String fallback) {
        MessagesController mc = getMessagesController();
        if (peerId > 0) {
            TLRPC.User user = mc.getUser(peerId);
            if (user != null) return UserObject.getUserName(user);
        } else if (peerId < 0) {
            TLRPC.Chat chat = mc.getChat(-peerId);
            if (chat != null) return chat.title;
        }
        return !TextUtils.isEmpty(fallback) ? fallback : String.valueOf(peerId);
    }

    private String chatTitle(SgramDeletedController.Entry e) {
        return peerName(e.dialogId, e.chatTitle);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return tab == 0 ? entries.size() : edited.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            TextDetailSettingsCell cell = new TextDetailSettingsCell(context);
            cell.setMultilineDetail(true);
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (tab == 1) {
                SgramDeletedController.EditedEntry e = edited.get(position);
                String body = (TextUtils.isEmpty(e.lastText) ? "(media)" : e.lastText) +
                        "\n" + e.versions + (e.versions == 1 ? " old version" : " old versions") + " · edited " + dateFormat.format(new Date(e.lastSavedAt * 1000L));
                ((TextDetailSettingsCell) holder.itemView).setTextAndValue(peerName(e.dialogId, e.chatTitle), body, position != edited.size() - 1);
                return;
            }
            SgramDeletedController.Entry e = entries.get(position);
            String title = chatTitle(e);
            if (e.dialogId < 0 && e.fromId != 0 && e.fromId != e.dialogId) {
                title += " · " + (e.out ? "You" : peerName(e.fromId, e.fromName));
            } else if (e.out) {
                title += " · You";
            }
            StringBuilder body = new StringBuilder();
            if (!TextUtils.isEmpty(e.mediaType)) {
                body.append('[').append(e.mediaType);
                if (!TextUtils.isEmpty(e.mediaPath)) body.append(", saved");
                body.append("] ");
            }
            if (!TextUtils.isEmpty(e.text)) body.append(e.text);
            body.append("\nsent ").append(dateFormat.format(new Date(e.date * 1000L)))
                    .append(" · deleted ").append(dateFormat.format(new Date(e.deletedAt * 1000L)));
            ((TextDetailSettingsCell) holder.itemView).setTextAndValue(title, body.toString(), position != entries.size() - 1);
        }
    }
}
