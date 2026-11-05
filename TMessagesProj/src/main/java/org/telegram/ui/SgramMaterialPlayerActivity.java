package org.telegram.ui;

import android.view.View;

import org.telegram.messenger.SgramMaterialPlayer;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * sGram: настройки Material Player (как в плагине md3_player).
 */
public class SgramMaterialPlayerActivity extends UniversalFragment {

    private static final int ID_ENABLED = 1, ID_MINI = 2, ID_BAR = 3, ID_COLORS = 4, ID_WAVY = 5, ID_DOT = 6, ID_ANIM = 7, ID_SHUFFLE = 8;

    @Override
    protected CharSequence getTitle() {
        return "Material Player";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCheck(ID_ENABLED, "Material Player + Lyrics").setChecked(SgramMaterialPlayer.isEnabled()));
        items.add(UItem.asShadow("Плеер в стиле Material Expressive с синхронизированным текстом песен. Порт плагина md3_player (@RnPlugins)."));
        items.add(UItem.asHeader("Отображение"));
        items.add(UItem.asCheck(ID_MINI, "На главном экране").setChecked(SgramMaterialPlayer.getBool(SgramMaterialPlayer.MINI_DIALOGS)));
        items.add(UItem.asCheck(ID_BAR, "Панель в чате").setChecked(SgramMaterialPlayer.getBool(SgramMaterialPlayer.CONTEXT_BAR)));
        items.add(UItem.asShadow("Кастомный мини-плеер вместо стокового. Применяется после перезапуска."));
        items.add(UItem.asHeader("Стиль"));
        items.add(UItem.asButton(ID_COLORS, "Цвета", SgramMaterialPlayer.isThemeColors() ? "Тема Telegram" : "Обложка трека"));
        items.add(UItem.asCheck(ID_WAVY, "Волнистый прогресс").setChecked(SgramMaterialPlayer.getBool(SgramMaterialPlayer.WAVY)));
        items.add(UItem.asCheck(ID_DOT, "Точка на конце").setChecked(SgramMaterialPlayer.getBool(SgramMaterialPlayer.DOT)));
        items.add(UItem.asButton(ID_ANIM, "Анимация", SgramMaterialPlayer.getAnimStyle() == 1 ? "Скольжение" : "Морфинг"));
        items.add(UItem.asCheck(ID_SHUFFLE, "Скрыть «Перемешать»").setChecked(SgramMaterialPlayer.getBool(SgramMaterialPlayer.HIDE_SHUFFLE)));
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_ENABLED: SgramMaterialPlayer.setEnabled(!SgramMaterialPlayer.isEnabled()); break;
            case ID_MINI: toggle(SgramMaterialPlayer.MINI_DIALOGS); break;
            case ID_BAR: toggle(SgramMaterialPlayer.CONTEXT_BAR); break;
            case ID_WAVY: toggle(SgramMaterialPlayer.WAVY); break;
            case ID_DOT: toggle(SgramMaterialPlayer.DOT); break;
            case ID_SHUFFLE: toggle(SgramMaterialPlayer.HIDE_SHUFFLE); break;
            case ID_COLORS:
                choose("Цвета", new String[]{"Обложка трека", "Тема Telegram"}, i -> SgramMaterialPlayer.setThemeColors(i == 1));
                return;
            case ID_ANIM:
                choose("Анимация", new String[]{"Морфинг", "Скольжение"}, SgramMaterialPlayer::setAnimStyle);
                return;
            default: return;
        }
        listView.adapter.update(true);
    }

    private void toggle(String key) {
        SgramMaterialPlayer.setBool(key, !SgramMaterialPlayer.getBool(key));
    }

    private void choose(String title, String[] options, org.telegram.messenger.Utilities.Callback<Integer> done) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(title);
        b.setItems(options, (d, which) -> {
            done.run(which);
            listView.adapter.update(true);
        });
        showDialog(b.create());
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
