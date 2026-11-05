package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SgramVpnManager;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SgramAppSelectActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter listAdapter;
    private SharedPreferences preferences;
    private ArrayList<AppInfo> apps = new ArrayList<>();
    private HashSet<String> selectedPackages;

    private static class AppInfo {
        String name;
        String packageName;
        Drawable icon;
    }

    @Override
    public boolean onFragmentCreate() {
        preferences = ApplicationLoader.applicationContext.getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE);
        Set<String> set = preferences.getStringSet("vpn_excluded_apps", new HashSet<>());
        selectedPackages = new HashSet<>(set);
        loadApps();
        return super.onFragmentCreate();
    }

    private void loadApps() {
        Utilities.globalQueue.postRunnable(() -> {
            PackageManager pm = ApplicationLoader.applicationContext.getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(0);
            ArrayList<AppInfo> tempApps = new ArrayList<>();

            for (ApplicationInfo info : packages) {
                String pkg = info.packageName;

                // Показываем только приложения с иконкой в лаунчере (служебные пакеты прошивки скрываем),
                // но уже выбранные оставляем всегда, чтобы их можно было снять
                if (!selectedPackages.contains(pkg) && pm.getLaunchIntentForPackage(pkg) == null) {
                    continue;
                }

                // Не добавляем сам Telegram
                if (pkg.equals(ApplicationLoader.applicationContext.getPackageName())) continue;

                String label = info.loadLabel(pm).toString();
                if (label.isEmpty() || pkg.contains(".providers")) continue;

                AppInfo app = new AppInfo();
                app.name = label;
                app.packageName = pkg;
                tempApps.add(app);
            }

            // Сортировка: сначала выбранные, потом по имени
            Collections.sort(tempApps, (a, b) -> {
                boolean aSelected = selectedPackages.contains(a.packageName);
                boolean bSelected = selectedPackages.contains(b.packageName);
                if (aSelected != bSelected) return aSelected ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            });

            AndroidUtilities.runOnUIThread(() -> {
                apps = tempApps;
                if (listAdapter != null) listAdapter.notifyDataSetChanged();
            });
        });
    }


    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Excluded Apps");
        actionBar.setAllowOverlayTitle(true);

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    // Сохраняем при выходе
                    preferences.edit().putStringSet("vpn_excluded_apps", selectedPackages).apply();
                    // Перезапускаем VPN для применения настроек
                    if (preferences.getBoolean("vpn_enabled", false)) {
                        SgramVpnManager.getInstance().stopVpn();
                        AndroidUtilities.runOnUIThread(() -> SgramVpnManager.getInstance().startVpn(), 500);
                    }
                    finishFragment();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);

        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= apps.size()) return;

            AppInfo app = apps.get(position);
            if (selectedPackages.contains(app.packageName)) {
                selectedPackages.remove(app.packageName);
            } else {
                selectedPackages.add(app.packageName);
            }

            if (view instanceof org.telegram.ui.Cells.UserCell) {
                boolean checked = selectedPackages.contains(app.packageName);
                ((org.telegram.ui.Cells.UserCell) view).setChecked(checked, true); // Плавное переключение тумблера
            }
        });


        ((FrameLayout) fragmentView).addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private Context mContext;
        public ListAdapter(Context context) { mContext = context; }

        @Override
        public int getItemCount() { return apps.size(); }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) { return true; }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            // Конструктор: context, padding (6), type (2 = Switch), isAdmin (false)
            // Уменьшил отступ с 58 до 6, чтобы иконка была ближе к краю
            org.telegram.ui.Cells.UserCell cell = new org.telegram.ui.Cells.UserCell(mContext, 6, 2, false);
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            return new RecyclerListView.Holder(cell);
        }



        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            AppInfo app = apps.get(position);
            org.telegram.ui.Cells.UserCell cell = (org.telegram.ui.Cells.UserCell) holder.itemView;

            boolean isChecked = selectedPackages.contains(app.packageName);

            // Устанавливаем данные
            cell.setData(null, app.name, app.packageName, 0, position != apps.size() - 1);

            // Теперь это тумблер (Switch)
            cell.setChecked(isChecked, false);

            // Грузим иконку через рефлексию
            try {
                PackageManager pm = ApplicationLoader.applicationContext.getPackageManager();
                Drawable icon = pm.getApplicationIcon(app.packageName);

                java.lang.reflect.Field field = cell.getClass().getDeclaredField("avatarImageView");
                field.setAccessible(true);
                org.telegram.ui.Components.BackupImageView imageView = (org.telegram.ui.Components.BackupImageView) field.get(cell);

                if (imageView != null && icon != null) {
                    imageView.setImageDrawable(icon);
                }
            } catch (Exception ignore) {}
        }


    }
}
