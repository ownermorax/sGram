package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;

// Свой порядок аккаунтов: ранг по user id (переживает перестановку слотов), по умолчанию — время входа
public class SgramAccountOrder {

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("sgram_account_order", Context.MODE_PRIVATE);
    }

    public static long rank(int account) {
        UserConfig cfg = UserConfig.getInstance(account);
        return prefs().getLong("r_" + cfg.getClientUserId(), cfg.loginTime);
    }

    public static int compare(Integer a, Integer b) {
        return Long.compare(rank(a), rank(b));
    }

    public static void sort(ArrayList<Integer> accounts) {
        java.util.Collections.sort(accounts, SgramAccountOrder::compare);
    }

    // Все активные аккаунты в текущем порядке
    public static ArrayList<Integer> all() {
        ArrayList<Integer> list = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) list.add(a);
        }
        sort(list);
        return list;
    }

    // Сдвиг на одну позицию в общем списке (dir = -1 вверх, +1 вниз); ранги нормализуются 0..n-1
    public static boolean move(int account, int dir) {
        ArrayList<Integer> list = all();
        int i = list.indexOf(account), j = i + dir;
        if (i < 0 || j < 0 || j >= list.size()) return false;
        list.set(i, list.get(j));
        list.set(j, account);
        SharedPreferences.Editor e = prefs().edit();
        for (int k = 0; k < list.size(); k++) {
            e.putLong("r_" + UserConfig.getInstance(list.get(k)).getClientUserId(), k);
        }
        e.apply();
        return true;
    }
}
