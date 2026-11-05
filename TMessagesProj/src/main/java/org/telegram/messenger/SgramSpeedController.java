package org.telegram.messenger;

import android.content.Context;

public class SgramSpeedController {

    public static boolean isSpeedBoost() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE)
                .getBoolean("speed_boost", false);
    }
}