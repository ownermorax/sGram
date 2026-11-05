package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.LaunchActivity;

/** Music Editor: пункт «Редактор трека» в меню «⋮» стокового плеера и Material Player; сам редактор — python/sgram_music_editor.py. */
public class SgramMusicEditor {

    public static final String PREF = "music_editor";
    public static final int PICK_REQ = 0x7E0E;

    public static boolean isEnabled() {
        return ApplicationLoader.applicationContext.getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE).getBoolean(PREF, true);
    }

    /** Вызывается из ItemOptions.show(): добавляем пункт, если меню строит плеер. */
    public static void onItemOptionsShow(ItemOptions o) {
        if (!isEnabled() || MediaController.getInstance().getPlayingMessageObject() == null) {
            return;
        }
        boolean player = false;
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName(), m = e.getMethodName();
            if ("org.telegram.ui.Components.AudioPlayerAlert".equals(c) && "showMenuOptions".equals(m)
                    || "dev.rooni.player.PlayerSheet".equals(c) && "showMenu".equals(m)) {
                player = true;
                break;
            }
        }
        if (!player) {
            return;
        }
        o.add(R.drawable.msg_edit, "Редактор трека", () -> {
            o.dismiss();
            // плеер закрываем: иначе он сворачивается при открытии вложенных диалогов и уносит редактор
            SgramMaterialPlayer.dismissSheet();
            org.telegram.ui.ActionBar.BaseFragment f = LaunchActivity.getLastFragment();
            if (f != null && f.getVisibleDialog() instanceof org.telegram.ui.Components.AudioPlayerAlert) {
                f.getVisibleDialog().dismiss();
            }
            AndroidUtilities.runOnUIThread(SgramMusicEditor::open, 350);
        });
    }

    private static Object module() {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(ApplicationLoader.applicationContext));
        }
        return Python.getInstance().getModule("sgram_music_editor");
    }

    public static void open() {
        Activity act = LaunchActivity.instance;
        if (act == null) {
            return;
        }
        try {
            ((com.chaquo.python.PyObject) module()).callAttr("open_editor", act);
        } catch (Throwable t) {
            FileLog.e(t);
            Toast.makeText(act, "Music Editor: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public static void onActivityResult(int resultCode, Intent data) {
        try {
            ((com.chaquo.python.PyObject) module()).callAttr("on_cover_picked", resultCode, data);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
