package org.telegram.ui;

import android.view.View;

import org.telegram.messenger.SgramHiddenFolder;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * sGram: настройки Hidden Folder.
 */
public class SgramHiddenFolderActivity extends UniversalFragment {

    private static final int ID_ENABLED = 1, ID_OPEN = 2, ID_ENTRY = 3, ID_WELCOME = 4, ID_BIOMETRIC = 5;
    private static final String[] ENTRIES = {"Зажать вкладку «Чаты»", "Зажать «Настройки чатов»"};

    @Override
    protected CharSequence getTitle() {
        return "Hidden Folder";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean on = SgramHiddenFolder.isEnabled();
        items.add(UItem.asCheck(ID_ENABLED, "Hidden Folder").setChecked(on));
        items.add(UItem.asShadow("Локальная папка под код-паролем: её чаты не видны в списке, поиске, счётчиках и уведомлениях. Хранится только на этом устройстве."));
        if (!on) return;
        items.add(UItem.asButton(ID_OPEN, "Открыть папку"));
        items.add(UItem.asButton(ID_ENTRY, "Открывать через", ENTRIES[SgramHiddenFolder.getEntry() == 2 ? 1 : 0]));
        if (SgramHiddenFolder.isBiometricAvailable()) {
            items.add(UItem.asCheck(ID_BIOMETRIC, "Вход по отпечатку").setChecked(SgramHiddenFolder.isBiometric()));
        }
        items.add(UItem.asButton(ID_WELCOME, "Показать приветствие снова"));
        items.add(UItem.asShadow("Код-пароль и чаты сохранятся."));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_ENABLED:
                SgramHiddenFolder.setEnabled(!SgramHiddenFolder.isEnabled());
                listView.adapter.update(true);
                break;
            case ID_OPEN: SgramHiddenFolder.open(this); break;
            case ID_BIOMETRIC:
                SgramHiddenFolder.setBiometric(!SgramHiddenFolder.isBiometric());
                listView.adapter.update(true);
                break;
            case ID_WELCOME: SgramHiddenFolder.showWelcome(this); break;
            case ID_ENTRY:
                if (getParentActivity() == null) return;
                AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
                b.setTitle("Открывать через");
                b.setItems(ENTRIES, (d, which) -> {
                    SgramHiddenFolder.setEntry(which == 1 ? 2 : 0);
                    listView.adapter.update(true);
                });
                showDialog(b.create());
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
