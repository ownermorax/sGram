package org.telegram.messenger;

import android.content.Context;

public class SgramGhostController {

    public static boolean isGhostNoOnline() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE)
                .getBoolean("ghost_no_online", false);
    }

    public static boolean isGhostNoRead() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE)
                .getBoolean("ghost_no_read", false);
    }

    public static boolean isGhostNoStories() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE)
                .getBoolean("ghost_no_stories", false);
    }

    public static boolean isGhostNoTyping() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE)
                .getBoolean("ghost_no_typing", false);
    }
}