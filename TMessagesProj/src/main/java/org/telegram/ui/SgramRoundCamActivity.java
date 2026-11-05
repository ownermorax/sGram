package org.telegram.ui;

import android.view.View;

import org.telegram.messenger.SgramRoundCam;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * sGram: настройки Round Video Enhancer.
 */
public class SgramRoundCamActivity extends UniversalFragment {

    private static final int ID_ENABLED = 1, ID_RES = 2, ID_VBR = 3, ID_ABR = 4, ID_HEVC = 5, ID_FPS = 6, ID_WIDE = 7, ID_TAP = 8;

    @Override
    protected CharSequence getTitle() {
        return "Round Video Enhancer";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean on = SgramRoundCam.isEnabled();
        items.add(UItem.asHeader("Качество кружков"));
        items.add(UItem.asCheck(ID_ENABLED, "Своё разрешение и битрейт").setChecked(on));
        if (on) {
            items.add(UItem.asButton(ID_RES, "Разрешение", res(SgramRoundCam.RES[SgramRoundCam.getIdx(SgramRoundCam.RES_IDX, SgramRoundCam.RES)])));
            items.add(UItem.asButton(ID_VBR, "Битрейт видео", SgramRoundCam.VBR[SgramRoundCam.getIdx(SgramRoundCam.VBR_IDX, SgramRoundCam.VBR)] + " кбит/с"));
            items.add(UItem.asButton(ID_ABR, "Битрейт аудио", SgramRoundCam.ABR[SgramRoundCam.getIdx(SgramRoundCam.ABR_IDX, SgramRoundCam.ABR)] + " кбит/с"));
        }
        items.add(UItem.asCheck(ID_HEVC, "Кодек HEVC (H.265)").setChecked(SgramRoundCam.getBool(SgramRoundCam.HEVC)));
        items.add(UItem.asCheck(ID_FPS, "Лок 30 FPS").setChecked(SgramRoundCam.getBool(SgramRoundCam.LOCK_FPS)));
        items.add(UItem.asShadow(SgramRoundCam.isHevcSupported()
                ? "HEVC — лучше качество при том же размере. Если кружок не записывается или не играет — выключи."
                : "HEVC: на этом телефоне нет кодировщика H.265, будет обычный H.264."));
        items.add(UItem.asHeader("Камера"));
        items.add(UItem.asCheck(ID_WIDE, "Широкий угол на фронталке").setChecked(SgramRoundCam.getBool(SgramRoundCam.WIDE)));
        items.add(UItem.asCheck(ID_TAP, "Смена камеры двойным тапом").setChecked(SgramRoundCam.getBool(SgramRoundCam.DOUBLE_TAP)));
        items.add(UItem.asShadow("Широкий угол работает, если фронтальная камера его поддерживает (Android 11+). Двойной тап — по кружку во время записи."));
    }

    private static String res(int r) {
        return r + "×" + r;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_ENABLED: toggle(SgramRoundCam.ENABLED); break;
            case ID_HEVC: toggle(SgramRoundCam.HEVC); break;
            case ID_FPS: toggle(SgramRoundCam.LOCK_FPS); break;
            case ID_WIDE: toggle(SgramRoundCam.WIDE); break;
            case ID_TAP: toggle(SgramRoundCam.DOUBLE_TAP); break;
            case ID_RES: choose("Разрешение", SgramRoundCam.RES_IDX, SgramRoundCam.RES, "", true); return;
            case ID_VBR: choose("Битрейт видео", SgramRoundCam.VBR_IDX, SgramRoundCam.VBR, " кбит/с", false); return;
            case ID_ABR: choose("Битрейт аудио", SgramRoundCam.ABR_IDX, SgramRoundCam.ABR, " кбит/с", false); return;
            default: return;
        }
        listView.adapter.update(true);
    }

    private void toggle(String key) {
        SgramRoundCam.setBool(key, !SgramRoundCam.getBool(key));
    }

    private void choose(String title, String key, int[] values, String suffix, boolean square) {
        if (getParentActivity() == null) return;
        String[] options = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            options[i] = (square ? res(values[i]) : String.valueOf(values[i])) + suffix;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(title);
        b.setItems(options, (d, which) -> {
            SgramRoundCam.setIdx(key, which);
            listView.adapter.update(true);
        });
        showDialog(b.create());
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
