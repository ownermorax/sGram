package org.telegram.ui;

import android.os.Bundle;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.R;
import org.telegram.messenger.SgramDeletedController;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * sGram: chats where deleted messages and edits are not saved.
 */
public class SgramDeletedExcludedActivity extends UniversalFragment {

    private static final int ID_ADD = 1;
    private static final int ID_CHAT = 1000;

    private final ArrayList<Long> dialogs = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        reload();
        return super.onFragmentCreate();
    }

    private void reload() {
        dialogs.clear();
        for (String s : SgramDeletedController.getExcluded()) {
            try {
                dialogs.add(Long.parseLong(s));
            } catch (NumberFormatException ignore) {
            }
        }
    }

    @Override
    protected CharSequence getTitle() {
        return "Excluded Chats";
    }

    private String name(long dialogId) {
        if (DialogObject.isUserDialog(dialogId)) {
            TLRPC.User user = getMessagesController().getUser(dialogId);
            return user != null ? UserObject.getUserName(user) : "User " + dialogId;
        }
        TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
        return chat != null ? chat.title : "Chat " + (-dialogId);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asButton(ID_ADD, R.drawable.msg_add, "Add Chats").accent());
        items.add(UItem.asShadow("Deleted messages and edit history are not saved in these chats. Tap a chat to remove it from the list."));
        if (!dialogs.isEmpty()) {
            items.add(UItem.asHeader("Excluded"));
            for (int i = 0; i < dialogs.size(); i++) {
                items.add(UItem.asButton(ID_CHAT + i, name(dialogs.get(i))));
            }
            items.add(UItem.asShadow(null));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ADD) {
            Bundle args = new Bundle();
            args.putBoolean("onlySelect", true);
            args.putBoolean("checkCanWrite", false);
            args.putBoolean("allowGlobalSearch", false);
            DialogsActivity picker = new DialogsActivity(args);
            picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
                if (fragment != picker) picker.removeSelfFromStack();
                fragment.finishFragment();
                for (int i = 0; i < dids.size(); i++) {
                    exclude(this, dids.get(i).dialogId, () -> {
                        reload();
                        if (listView != null) listView.adapter.update(true);
                    });
                }
                return true;
            });
            presentFragment(picker);
        } else if (item.id >= ID_CHAT && item.id < ID_CHAT + dialogs.size()) {
            SgramDeletedController.setExcluded(dialogs.get(item.id - ID_CHAT), false);
            reload();
            listView.adapter.update(true);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    /** Excludes the chat; if something is already saved there, asks whether to clear it. */
    public static void exclude(BaseFragment fragment, long dialogId, Runnable done) {
        SgramDeletedController.setExcluded(dialogId, true);
        Utilities.globalQueue.postRunnable(() -> {
            int saved = SgramDeletedController.countForDialog(dialogId);
            AndroidUtilities.runOnUIThread(() -> {
                if (done != null) done.run();
                if (saved <= 0 || fragment.getParentActivity() == null) return;
                AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity());
                b.setTitle("Saved History");
                b.setMessage(saved + " saved deleted messages and edits are already kept for this chat. Remove them too?");
                b.setPositiveButton("Remove", (d, w) -> Utilities.globalQueue.postRunnable(() -> SgramDeletedController.clearDialog(dialogId)));
                b.setNegativeButton("Keep", null);
                fragment.showDialog(b.create());
            });
        });
    }
}
