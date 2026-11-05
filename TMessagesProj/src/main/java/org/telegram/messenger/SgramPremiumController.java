package org.telegram.messenger;

import android.content.Context;

public class SgramPremiumController {

    // isPremium() дёргается из отрисовки, поэтому значение кэшируется
    private static volatile Boolean localPremium;

    public static boolean isLocalPremium() {
        Boolean value = localPremium;
        if (value == null) {
            value = ApplicationLoader.applicationContext
                    .getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE)
                    .getBoolean("local_premium", false);
            localPremium = value;
        }
        return value;
    }

    public static void setLocalPremium(boolean enabled) {
        localPremium = enabled;
        ApplicationLoader.applicationContext
                .getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("local_premium", enabled).apply();
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                UserConfig userConfig = UserConfig.getInstance(a);
                if (!userConfig.isClientActivated()) continue;
                MessagesController.getInstance(a).updatePremium(userConfig.isPremium());
                NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.currentUserPremiumStatusChanged);
                MediaDataController.getInstance(a).loadReactions(false, null);
                MessagesController.getInstance(a).getStoriesController().invalidateStoryLimit();
            }
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.premiumStatusChangedGlobal);
        });
    }
}
