package org.telegram.ui;

import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SgramLocalNames;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.Collections;

/**
 * sGram: settings of Local Names — global switch, all overrides (local → original),
 * export/import via clipboard and delete all.
 */
public class SgramLocalNamesActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_ENABLED = 1;
    private static final int ID_EXPORT = 2;
    private static final int ID_IMPORT = 3;
    private static final int ID_DELETE_ALL = 4;
    private static final int ID_ITEM = 1000;

    private ArrayList<SgramLocalNames.Override> list = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        reload();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.updateInterfaces) {
            reload();
            if (listView != null) {
                listView.adapter.update(true);
            }
        }
    }

    private void reload() {
        list = SgramLocalNames.getAll();
        Collections.sort(list, (a, b) -> String.valueOf(a.localName()).compareToIgnoreCase(String.valueOf(b.localName())));
    }

    @Override
    protected CharSequence getTitle() {
        return "Local Names";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCheck(ID_ENABLED, "Show Local Names").setChecked(SgramLocalNames.isEnabled()));
        items.add(UItem.asShadow("Open a profile, tap ⋮ → Local Name to rename a user, bot, group or channel. Only you see it: the chat list, chats and profiles show the local name, Telegram never gets it."));
        if (!list.isEmpty()) {
            items.add(UItem.asHeader("Overrides"));
            for (int i = 0; i < list.size(); i++) {
                SgramLocalNames.Override o = list.get(i);
                String local = o.localName();
                if (o.username != null) {
                    local += "  @" + o.username;
                }
                String original = "was: " + (o.originalName() != null ? o.originalName() : "?") + (o.origUsername != null ? "  @" + o.origUsername : "");
                items.add(UItem.asButton(ID_ITEM + i, local, original));
            }
            items.add(UItem.asShadow(null));
        }
        items.add(UItem.asButton(ID_EXPORT, R.drawable.msg_copy, "Export to Clipboard"));
        items.add(UItem.asButton(ID_IMPORT, R.drawable.msg_download, "Import from Clipboard"));
        if (!list.isEmpty()) {
            items.add(UItem.asButton(ID_DELETE_ALL, R.drawable.msg_delete, "Delete All").red());
        }
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_ENABLED:
                SgramLocalNames.setEnabled(!SgramLocalNames.isEnabled());
                listView.adapter.update(true);
                return;
            case ID_EXPORT:
                AndroidUtilities.addToClipboard(SgramLocalNames.exportJson());
                BulletinFactory.of(this).createCopyBulletin("Local names copied").show();
                return;
            case ID_IMPORT: {
                ClipboardManager cm = (ClipboardManager) ApplicationLoader.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE);
                CharSequence text = cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0 ? cm.getPrimaryClip().getItemAt(0).coerceToText(getParentActivity()) : null;
                int count = text != null ? SgramLocalNames.importJson(currentAccount, text.toString()) : -1;
                if (count < 0) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Clipboard has no exported local names").show();
                } else {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, "Imported: " + count).show();
                }
                reload();
                listView.adapter.update(true);
                return;
            }
            case ID_DELETE_ALL: {
                AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
                b.setTitle("Delete All");
                b.setMessage("Remove all " + list.size() + " local names and restore the original names?");
                b.setPositiveButton("Delete", (d, w) -> {
                    SgramLocalNames.resetAll();
                    reload();
                    listView.adapter.update(true);
                });
                b.setNegativeButton("Cancel", null);
                showDialog(b.create());
                return;
            }
        }
        int index = item.id - ID_ITEM;
        if (index >= 0 && index < list.size()) {
            long dialogId = list.get(index).dialogId;
            if (getMessagesController().getUser(dialogId) != null || getMessagesController().getChat(-dialogId) != null) {
                SgramLocalNames.showEditDialog(this, dialogId);
            } else {
                Bundle args = new Bundle();
                if (dialogId > 0) {
                    args.putLong("user_id", dialogId);
                } else {
                    args.putLong("chat_id", -dialogId);
                }
                presentFragment(new ProfileActivity(args));
            }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
