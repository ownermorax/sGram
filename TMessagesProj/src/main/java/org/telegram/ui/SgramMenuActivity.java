package org.telegram.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SgramDeletedController;
import org.telegram.messenger.SgramPremiumController;
import org.telegram.messenger.SgramVpnManager;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.boosts.cells.selector.SelectorUserCell;
import org.telegram.ui.Components.RecyclerListView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Locale;

public class SgramMenuActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, MainTabsActivity.TabFragmentDelegate {

    // Обновлять при каждой версии sGram
    public static final String SGRAM_VERSION = "2.3.0";

    private static final int REQUEST_WG_CONFIG = 999;
    private static final int REQUEST_CARD_IMAGE = 998;

    // Виды ячеек
    private static final int TYPE_HEADER = 0, TYPE_TEXT = 1, TYPE_SHADOW = 2, TYPE_CHECK_LARGE = 3, TYPE_CHECK_SMALL = 4, TYPE_BIG_HEADER = 5;

    // Строки меню
    private static final int ROW_TRAFFIC = 1, ROW_VPN = 2, ROW_CONFIG = 3, ROW_ONLY_TG = 4, ROW_EXCLUDED = 5,
            ROW_BYPASS = 6, ROW_GHOST = 7, ROW_GHOST_ONLINE = 8, ROW_GHOST_READ = 9, ROW_GHOST_STORIES = 10, ROW_GHOST_TYPING = 11,
            ROW_SPEED = 12, ROW_PREMIUM = 13,
            ROW_HISTORY = 14, ROW_SAVE_DELETED = 15, ROW_SAVE_EDITS = 16, ROW_SAVE_MEDIA = 17, ROW_LIMIT = 18, ROW_VIEW_DELETED = 19, ROW_CLEAR = 20,
            ROW_TOOLS = 21, ROW_MINIAPPS = 22, ROW_BOOSTS = 23, ROW_CRASHES = 24,
            ROW_XRAY = 25, ROW_XRAY_SERVERS = 26, ROW_ROUND_BACK = 27, ROW_CARD_IMAGE = 28, ROW_CARD_SHOW = 29,
            ROW_FAST_CROP = 30, ROW_DOC_VIEWER = 31, ROW_SPOOF = 32,
            ROW_PLUGINS_HEADER = 33, ROW_PLUGINS = 34, ROW_SCAM = 35, ROW_SCAM_INFO = 36, ROW_LOCAL_NAMES = 37, ROW_EXCLUDED_CHATS = 38, ROW_MD3_PLAYER = 40, ROW_INLINE_CALC = 41, ROW_MUSIC_EDITOR = 42, ROW_ROUND_CAM = 43, ROW_HIDDEN_FOLDER = 44;

    private static final int[] ROWS = {
            ROW_TRAFFIC, ROW_VPN, ROW_CONFIG, ROW_ONLY_TG, ROW_EXCLUDED,
            ROW_XRAY, ROW_XRAY_SERVERS,
            ROW_BYPASS,
            ROW_GHOST, ROW_GHOST_ONLINE, ROW_GHOST_READ, ROW_GHOST_STORIES, ROW_GHOST_TYPING,
            ROW_SPEED, ROW_PREMIUM,
            ROW_HISTORY, ROW_SAVE_DELETED, ROW_SAVE_EDITS, ROW_SAVE_MEDIA, ROW_LIMIT, ROW_EXCLUDED_CHATS, ROW_VIEW_DELETED, ROW_CLEAR,
            ROW_PLUGINS_HEADER, ROW_PLUGINS,
            ROW_TOOLS, ROW_ROUND_BACK, ROW_ROUND_CAM, ROW_FAST_CROP, ROW_INLINE_CALC, ROW_DOC_VIEWER, ROW_CARD_SHOW, ROW_CARD_IMAGE, ROW_SPOOF, ROW_SCAM, ROW_SCAM_INFO, ROW_LOCAL_NAMES, ROW_HIDDEN_FOLDER, ROW_MD3_PLAYER, ROW_MUSIC_EDITOR, ROW_MINIAPPS, ROW_BOOSTS, ROW_CRASHES,
    };

    private RecyclerListView listView;
    private ListAdapter listAdapter;
    private TextView easterEgg;
    private boolean isVpnEnabled;
    private String configName = "Not Loaded";
    private SharedPreferences preferences;
    private long[] historyStats = {0, 0, 0};

    public SgramMenuActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        preferences = ApplicationLoader.applicationContext.getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE);
        isVpnEnabled = preferences.getBoolean("vpn_enabled", false);
        configName = preferences.getString("conf_name", "Not Loaded");
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.onActivityResultReceived);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.sgramXrayStateChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.sgramPluginsChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.sgramScamBaseUpdated);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.onActivityResultReceived);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.sgramXrayStateChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.sgramPluginsChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.sgramScamBaseUpdated);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.sgramXrayStateChanged || id == NotificationCenter.sgramPluginsChanged || id == NotificationCenter.sgramScamBaseUpdated) {
            if (listAdapter != null) listAdapter.notifyDataSetChanged();
            return;
        }
        if (id == NotificationCenter.onActivityResultReceived) {
            int requestCode = (Integer) args[0];
            int resultCode = (Integer) args[1];
            Intent data = (Intent) args[2];
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
            Uri uri = data.getData();
            if (requestCode == REQUEST_WG_CONFIG) {
                processConfigFile(uri);
            } else if (requestCode == REQUEST_CARD_IMAGE) {
                uploadCardImage(uri);
            }
        }
    }

    private void processConfigFile(Uri uri) {
        try {
            String fileName = "config.conf";
            android.database.Cursor cursor = ApplicationLoader.applicationContext.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1) fileName = cursor.getString(nameIndex);
                cursor.close();
            }

            InputStream inputStream = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            String line;
            SharedPreferences.Editor editor = preferences.edit();
            boolean found = false;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("[")) continue;
                String[] parts = line.split("=", 2);
                if (parts.length < 2) continue;
                String key = parts[0].trim();
                String value = parts[1].trim();
                if (key.equalsIgnoreCase("PrivateKey")) { editor.putString("wg_priv_key", value); found = true; }
                else if (key.equalsIgnoreCase("Address")) editor.putString("wg_address", value);
                else if (key.equalsIgnoreCase("DNS")) editor.putString("wg_dns", value);
                else if (key.equalsIgnoreCase("PublicKey")) editor.putString("wg_pub_key", value);
                else if (key.equalsIgnoreCase("Endpoint")) editor.putString("wg_endpoint", value);
                else if (key.equalsIgnoreCase("AllowedIPs")) editor.putString("wg_allowed_ips", value);
            }
            if (found) {
                configName = fileName;
                editor.putString("conf_name", configName);
                editor.apply();
            }
            final boolean finalFound = found;
            final String finalName = configName;
            AndroidUtilities.runOnUIThread(() -> {
                if (listAdapter != null) listAdapter.notifyDataSetChanged();
                BulletinFactory.of(SgramMenuActivity.this).createSimpleBulletin(finalFound ? R.raw.contact_check : R.raw.error, finalFound ? "Config " + finalName + " loaded" : "File error").show(true);
            });
            inputStream.close();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.ENGLISH, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    @Override
    public View createView(Context context) {
        // sGram: уведомления сверху, под шапкой — снизу их закрывает панель вкладок
        org.telegram.ui.Components.Bulletin.addDelegate(this, new org.telegram.ui.Components.Bulletin.Delegate() {
            @Override
            public int getTopOffset(int tag) {
                return AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight();
            }
        });
        actionBar.setBackButtonImage(0);
        actionBar.setBackButtonDrawable(null);
        actionBar.setTitle("sGram Settings");

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        FrameLayout container = (FrameLayout) fragmentView;

        listView = new RecyclerListView(context) {
            private float startX;
            private float startY;
            private boolean isHorizontalScroll;
            // Пасхалка: "by Morax" видна только если тянуть список дальше конца
            private float pullLastY;
            private float pull;
            private ValueAnimator pullAnimator;

            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    pullLastY = ev.getRawY();
                    if (pullAnimator != null) {
                        pullAnimator.cancel();
                        pullAnimator = null;
                    }
                } else if (action == MotionEvent.ACTION_MOVE && !isHorizontalScroll) {
                    float y = ev.getRawY();
                    float dy = pullLastY - y;
                    pullLastY = y;
                    if (pull > 0 || (dy > 0 && !canScrollVertically(1))) {
                        float old = pull;
                        pull = Math.max(0, Math.min(pull + (dy > 0 ? dy * 0.45f : dy), AndroidUtilities.dp(90)));
                        applyPull(pull);
                        if (pull > 0 || old > 0) return true;
                    }
                } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && pull > 0) {
                    releasePull();
                    MotionEvent cancel = MotionEvent.obtain(ev);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    return true;
                }
                return super.dispatchTouchEvent(ev);
            }

            private void releasePull() {
                pullAnimator = ValueAnimator.ofFloat(pull, 0);
                pullAnimator.setDuration(250);
                pullAnimator.addUpdateListener(a -> {
                    pull = (float) a.getAnimatedValue();
                    applyPull(pull);
                });
                pullAnimator.start();
            }

            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                switch (ev.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = ev.getX();
                        startY = ev.getY();
                        isHorizontalScroll = false;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float diffX = Math.abs(ev.getX() - startX);
                        float diffY = Math.abs(ev.getY() - startY);
                        if (diffX > diffY && diffX > AndroidUtilities.getPixelsInCM(0.3f, true)) {
                            isHorizontalScroll = true;
                            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                            return false;
                        }
                        break;
                }
                return super.onInterceptTouchEvent(ev);
            }

            @Override
            public boolean onTouchEvent(MotionEvent ev) {
                if (isHorizontalScroll) return false;
                return super.onTouchEvent(ev);
            }
        };

        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        int paddingTop = ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight;
        listView.setPadding(0, paddingTop, 0, AndroidUtilities.dp(120));
        listView.setClipToPadding(false);

        container.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);

        easterEgg = new TextView(context);
        easterEgg.setText("by Morax\nv" + SGRAM_VERSION);
        easterEgg.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 5.5f);
        easterEgg.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        easterEgg.setGravity(Gravity.CENTER);
        easterEgg.setAlpha(0f);
        container.addView(easterEgg, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        listView.setOnItemClickListener((view, position) -> onRowClick(context, view, ROWS[position]));
        return fragmentView;
    }

    // Список уезжает вверх на pull, подпись появляется в открывшейся щели под последней строкой
    private void applyPull(float pull) {
        if (listView == null || easterEgg == null) return;
        listView.setTranslationY(-pull);
        View last = listView.getLayoutManager() != null ? listView.getLayoutManager().findViewByPosition(ROWS.length - 1) : null;
        if (last == null || pull <= 0) {
            easterEgg.setAlpha(0f);
            return;
        }
        float gapTop = listView.getTop() + last.getBottom() - pull;
        easterEgg.setTranslationY(gapTop + (pull - easterEgg.getHeight()) / 2f);
        easterEgg.setAlpha(Math.min(1f, pull / AndroidUtilities.dp(45)));
    }

    private void toggle(View view, String key, boolean def) {
        boolean value = !preferences.getBoolean(key, def);
        preferences.edit().putBoolean(key, value).commit();
        if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(value);
    }

    private void onRowClick(Context context, View view, int row) {
        switch (row) {
            case ROW_VPN: {
                if (preferences.getString("wg_priv_key", "").isEmpty()) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Please import config first").show(true);
                    return;
                }
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    if (getParentActivity().checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        getParentActivity().requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 101);
                    }
                }
                isVpnEnabled = !isVpnEnabled;
                preferences.edit().putBoolean("vpn_enabled", isVpnEnabled).apply();
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(isVpnEnabled);
                if (isVpnEnabled && org.telegram.messenger.SgramXray.isEnabled() && !org.telegram.messenger.SgramXray.isOnlyTg()) {
                    // в Android один VPN: Xray «весь телефон» выключаем
                    org.telegram.messenger.SgramXray.getInstance().stop();
                }
                if (isVpnEnabled) SgramVpnManager.getInstance().startVpn();
                else SgramVpnManager.getInstance().stopVpn();
                break;
            }
            case ROW_CONFIG: {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                getParentActivity().startActivityForResult(intent, REQUEST_WG_CONFIG);
                break;
            }
            case ROW_ONLY_TG:
                toggle(view, "vpn_only_tg", false);
                if (isVpnEnabled) {
                    SgramVpnManager.getInstance().stopVpn();
                    AndroidUtilities.runOnUIThread(() -> SgramVpnManager.getInstance().startVpn(), 500);
                }
                break;
            case ROW_EXCLUDED:
                presentFragment(new SgramAppSelectActivity());
                break;
            case ROW_XRAY: {
                org.telegram.messenger.SgramXray xray = org.telegram.messenger.SgramXray.getInstance();
                if (org.telegram.messenger.SgramXray.isEnabled()) {
                    xray.stop();
                } else if (xray.getSelected() == null) {
                    presentFragment(new SgramXrayActivity());
                    return;
                } else {
                    if (!org.telegram.messenger.SgramXray.isOnlyTg() && isVpnEnabled) {
                        isVpnEnabled = false;
                        preferences.edit().putBoolean("vpn_enabled", false).apply();
                        SgramVpnManager.getInstance().stopVpn();
                    }
                    xray.start();
                }
                if (listAdapter != null) listAdapter.notifyDataSetChanged();
                break;
            }
            case ROW_XRAY_SERVERS: presentFragment(new SgramXrayActivity()); break;
            case ROW_ROUND_BACK: toggle(view, "round_back_camera", false); break;
            case ROW_FAST_CROP: toggle(view, "fast_crop", true); break;
            case ROW_INLINE_CALC: toggle(view, org.telegram.messenger.SgramInlineCalc.PREF, true); break;
            case ROW_MUSIC_EDITOR: toggle(view, org.telegram.messenger.SgramMusicEditor.PREF, true); break;
            case ROW_DOC_VIEWER: toggle(view, SgramDocViewer.PREF_ENABLED, true); break;
            case ROW_SPOOF: showSpoofDialog(); break;
            case ROW_CARD_SHOW: toggle(view, org.telegram.messenger.SgramCardSkin.PREF_SHOW, true); break;
            case ROW_CARD_IMAGE: showCardImageDialog(); break;
            case ROW_BYPASS: toggle(view, "bypass_all", false); break;
            case ROW_GHOST_ONLINE: toggle(view, "ghost_no_online", false); break;
            case ROW_GHOST_READ: toggle(view, "ghost_no_read", false); break;
            case ROW_GHOST_STORIES: toggle(view, "ghost_no_stories", false); break;
            case ROW_GHOST_TYPING: toggle(view, "ghost_no_typing", false); break;
            case ROW_SPEED: toggle(view, "speed_boost", false); break;
            case ROW_PREMIUM: {
                boolean premium = !SgramPremiumController.isLocalPremium();
                SgramPremiumController.setLocalPremium(premium);
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(premium);
                break;
            }
            case ROW_SAVE_DELETED: toggle(view, SgramDeletedController.PREF_SAVE, false); break;
            case ROW_SAVE_EDITS: toggle(view, SgramDeletedController.PREF_SAVE_EDITS, true); break;
            case ROW_SAVE_MEDIA: toggle(view, SgramDeletedController.PREF_SAVE_MEDIA, true); break;
            case ROW_LIMIT: showLimitDialog(); break;
            case ROW_VIEW_DELETED: presentFragment(new SgramDeletedMessagesActivity()); break;
            case ROW_CLEAR: showClearDialog(); break;
            case ROW_MINIAPPS: presentFragment(new SgramMiniAppsActivity()); break;
            case ROW_BOOSTS: showMyBoosts(); break;
            case ROW_CRASHES: showCrashReports(); break;
            case ROW_PLUGINS: presentFragment(new SgramPluginsActivity()); break;
            case ROW_SCAM: {
                boolean enabled = !org.telegram.messenger.SgramScamBase.isEnabled();
                org.telegram.messenger.SgramScamBase.setEnabled(enabled);
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(enabled);
                break;
            }
            case ROW_SCAM_INFO: updateScamBase(); break;
            case ROW_MD3_PLAYER: presentFragment(new SgramMaterialPlayerActivity()); break;
            case ROW_HIDDEN_FOLDER: presentFragment(new SgramHiddenFolderActivity()); break;
            case ROW_ROUND_CAM: presentFragment(new SgramRoundCamActivity()); break;
            case ROW_LOCAL_NAMES: presentFragment(new SgramLocalNamesActivity()); break;
            case ROW_EXCLUDED_CHATS: presentFragment(new SgramDeletedExcludedActivity()); break;
        }
    }

    // --- реестр мошенников GiD ---

    private String scamBaseValue() {
        if (!org.telegram.messenger.SgramScamBase.isEnabled()) return "Off";
        if (org.telegram.messenger.SgramScamBase.isUpdating()) return "Updating…";
        long updated = org.telegram.messenger.SgramScamBase.lastUpdate();
        int size = org.telegram.messenger.SgramScamBase.size();
        if (updated == 0) return size > 0 ? String.valueOf(size) : "Not loaded";
        return size + " · " + org.telegram.messenger.LocaleController.formatDateAudio(updated / 1000, false);
    }

    private void updateScamBase() {
        if (getParentActivity() == null) return;
        if (!org.telegram.messenger.SgramScamBase.isEnabled()) {
            showResult("GiD Scam Base", "Turn on GiD Scam Marks first.");
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("GiD Scam Base");
        builder.setMessage("Users listed in @" + org.telegram.messenger.SgramScamBase.CHANNEL + " get a red line in their profile. "
                + "The base updates by itself every 6 hours.\n\nEntries: " + org.telegram.messenger.SgramScamBase.size());
        builder.setPositiveButton("Update Now", (d, w) -> {
            org.telegram.messenger.SgramScamBase.update(false, error -> showResult("GiD Scam Base", error != null ? "Update failed: " + error : "Updated. Entries: " + org.telegram.messenger.SgramScamBase.size()));
            if (listAdapter != null) listAdapter.notifyDataSetChanged();
        });
        builder.setNeutralButton("Reload All", (d, w) -> {
            org.telegram.messenger.SgramScamBase.update(true, error -> showResult("GiD Scam Base", error != null ? "Update failed: " + error : "Reloaded. Entries: " + org.telegram.messenger.SgramScamBase.size()));
            if (listAdapter != null) listAdapter.notifyDataSetChanged();
        });
        builder.setNegativeButton("Close", null);
        showDialog(builder.create());
    }

    // --- подмена устройства (Device Fingerprint Spoofer) ---

    private void showSpoofDialog() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        boolean enabled = org.telegram.messenger.SgramDeviceSpoof.isEnabled();
        String[] s = org.telegram.messenger.SgramDeviceSpoof.spoofed();
        ArrayList<CharSequence> items = new ArrayList<>();
        items.add(enabled ? "☑  Enabled" : "☐  Enabled");
        items.add("Profile: " + org.telegram.messenger.SgramDeviceSpoof.profileName());
        items.add("Custom device_model: " + preferences.getString(org.telegram.messenger.SgramDeviceSpoof.PREF_MODEL, org.telegram.messenger.SgramDeviceSpoof.PRESETS[0][1]));
        items.add("Custom system_version: " + preferences.getString(org.telegram.messenger.SgramDeviceSpoof.PREF_SYSVER, "SDK 34"));
        boolean spoofApp = preferences.getBoolean(org.telegram.messenger.SgramDeviceSpoof.PREF_SPOOF_APP, true);
        items.add((spoofApp ? "☑  " : "☐  ") + "Spoof app_version");
        items.add("app_version: " + preferences.getString(org.telegram.messenger.SgramDeviceSpoof.PREF_APPVER, org.telegram.messenger.SgramDeviceSpoof.DEFAULT_APPVER));
        items.add("Apply Now");
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Device Spoof");
        builder.setMessage("Changes how this device looks in Settings → Devices on Telegram servers.\nNow sent: " + s[0] + " · " + s[1] + (s[2] != null ? " · " + s[2] : ""));
        builder.setItems(items.toArray(new CharSequence[0]), (d, which) -> {
            switch (which) {
                case 0:
                    preferences.edit().putBoolean(org.telegram.messenger.SgramDeviceSpoof.PREF_ENABLED, !enabled).commit();
                    applySpoof();
                    break;
                case 1: {
                    String[][] presets = org.telegram.messenger.SgramDeviceSpoof.PRESETS;
                    CharSequence[] names = new CharSequence[presets.length + 1];
                    for (int i = 0; i < presets.length; i++) names[i] = presets[i][0];
                    names[presets.length] = "Custom";
                    AlertDialog.Builder b = new AlertDialog.Builder(activity);
                    b.setTitle("Profile");
                    b.setItems(names, (d2, w) -> {
                        preferences.edit().putInt(org.telegram.messenger.SgramDeviceSpoof.PREF_PROFILE, w).commit();
                        applySpoof();
                    });
                    showDialog(b.create());
                    return;
                }
                case 2: spoofInput("device_model", org.telegram.messenger.SgramDeviceSpoof.PREF_MODEL, org.telegram.messenger.SgramDeviceSpoof.PRESETS[0][1]); return;
                case 3: spoofInput("system_version", org.telegram.messenger.SgramDeviceSpoof.PREF_SYSVER, "SDK 34"); return;
                case 4:
                    preferences.edit().putBoolean(org.telegram.messenger.SgramDeviceSpoof.PREF_SPOOF_APP, !spoofApp).commit();
                    applySpoof();
                    break;
                case 5: spoofInput("app_version", org.telegram.messenger.SgramDeviceSpoof.PREF_APPVER, org.telegram.messenger.SgramDeviceSpoof.DEFAULT_APPVER); return;
                case 6: applySpoof(); break;
            }
            if (listAdapter != null) listAdapter.notifyDataSetChanged();
        });
        builder.setNegativeButton("Close", null);
        showDialog(builder.create());
    }

    private void spoofInput(String title, String key, String def) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        org.telegram.ui.Components.EditTextBoldCursor input = new org.telegram.ui.Components.EditTextBoldCursor(activity);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        input.setSingleLine();
        input.setText(preferences.getString(key, def));
        FrameLayout frame = new FrameLayout(activity);
        frame.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40, Gravity.TOP, 24, 8, 24, 0));
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(title);
        builder.setView(frame);
        builder.setPositiveButton("Save", (d, w) -> {
            String v = input.getText().toString().trim();
            preferences.edit().putString(key, v.isEmpty() ? def : v).commit();
            if (key.equals(org.telegram.messenger.SgramDeviceSpoof.PREF_APPVER) || org.telegram.messenger.SgramDeviceSpoof.isCustom()) applySpoof();
            showSpoofDialog();
        });
        builder.setNegativeButton("Cancel", (d, w) -> showSpoofDialog());
        showDialog(builder.create());
    }

    private void applySpoof() {
        if (listAdapter != null) listAdapter.notifyDataSetChanged();
        org.telegram.messenger.SgramDeviceSpoof.applyNow((ok, failed, error) -> {
            showResult("Device Spoof", failed == 0 ? "Device updated on " + ok + (ok == 1 ? " account." : " accounts.") : "Failed on " + failed + (error != null ? ": " + error : ""));
        });
    }

    // --- своя картинка на карте кошелька ---

    private void showCardImageDialog() {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Wallet Card Image");
        builder.setMessage("Your image replaces the blue background of your wallet card. Everyone with sGram sees it on your profile card.");
        builder.setPositiveButton("Choose Image", (d, w) -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            getParentActivity().startActivityForResult(intent, REQUEST_CARD_IMAGE);
        });
        builder.setNeutralButton("Remove", (d, w) -> {
            AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
            progress.setCanCancel(false);
            progress.show();
            org.telegram.messenger.SgramCardSkin.remove(currentAccount, error -> {
                try { progress.dismiss(); } catch (Exception ignore) {}
                showResult("Wallet Card Image", error != null ? "Failed: " + error : "Card image removed.");
            });
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    // Плашки снизу закрывает панель вкладок — результат показываем диалогом
    private void showResult(String title, String text) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(title);
        b.setMessage(text);
        b.setPositiveButton("OK", null);
        showDialog(b.create());
    }

    private void uploadCardImage(Uri uri) {
        AndroidUtilities.runOnUIThread(() -> {
            if (getParentActivity() == null) return;
            AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
            progress.setCanCancel(false);
            progress.show();
            org.telegram.messenger.SgramCardSkin.upload(currentAccount, uri, error -> {
                try { progress.dismiss(); } catch (Exception ignore) {}
                showResult("Wallet Card Image", error != null ? "Upload failed: " + error : "Card image set. Open your profile card to see it.");
            });
        });
    }

    private static final int[] LIMITS_MB = {50, 100, 200, 500, 1024, 2048, 5120, 0};

    private static String formatLimit(int mb) {
        if (mb <= 0) return "Unlimited";
        if (mb >= 1024 && mb % 1024 == 0) return (mb / 1024) + " GB";
        if (mb >= 1024) return String.format(Locale.US, "%.1f GB", mb / 1024f);
        return mb + " MB";
    }

    private void refreshStats() {
        Utilities.globalQueue.postRunnable(() -> {
            SgramDeletedController.purgeOrphans();
            long[] stats = SgramDeletedController.getStatsFor(org.telegram.messenger.UserConfig.getInstance(currentAccount).getClientUserId());
            AndroidUtilities.runOnUIThread(() -> {
                historyStats = stats;
                if (listAdapter != null) listAdapter.notifyDataSetChanged();
            });
        });
    }

    private void showLimitDialog() {
        if (getParentActivity() == null) return;
        int current = SgramDeletedController.getLimitMb();
        CharSequence[] items = new CharSequence[LIMITS_MB.length + 1];
        boolean preset = false;
        for (int i = 0; i < LIMITS_MB.length; i++) {
            items[i] = (LIMITS_MB[i] == current ? "✓ " : "") + formatLimit(LIMITS_MB[i]);
            if (LIMITS_MB[i] == current) preset = true;
        }
        items[LIMITS_MB.length] = (preset ? "" : "✓ ") + "Custom…" + (preset ? "" : " (" + formatLimit(current) + ")");
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Cache Limit");
        builder.setItems(items, (dialog, which) -> {
            if (which == LIMITS_MB.length) {
                showCustomLimitDialog();
                return;
            }
            applyLimit(LIMITS_MB[which]);
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void applyLimit(int mb) {
        SgramDeletedController.setLimitMb(mb);
        if (listAdapter != null) listAdapter.notifyDataSetChanged();
        AndroidUtilities.runOnUIThread(this::refreshStats, 500);
    }

    // Свой лимит в мегабайтах, 0 — без лимита
    private void showCustomLimitDialog() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        org.telegram.ui.Components.EditTextBoldCursor input = new org.telegram.ui.Components.EditTextBoldCursor(activity);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        input.setHint("Size in MB (0 = unlimited)");
        input.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        input.setText(String.valueOf(SgramDeletedController.getLimitMb()));
        input.setSelection(input.length());
        FrameLayout frame = new FrameLayout(activity);
        frame.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40, Gravity.TOP, 24, 8, 24, 0));
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Custom Limit");
        builder.setView(frame);
        builder.setPositiveButton("Save", (d, w) -> {
            int mb;
            try {
                mb = Integer.parseInt(input.getText().toString().trim());
            } catch (Exception e) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Enter a number").show(true);
                return;
            }
            applyLimit(Math.max(0, Math.min(mb, 1024 * 1024)));
        });
        builder.setNegativeButton("Cancel", null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        AndroidUtilities.runOnUIThread(() -> {
            input.requestFocus();
            AndroidUtilities.showKeyboard(input);
        }, 200);
    }

    private void showClearDialog() {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Clear Cache");
        builder.setMessage("All saved deleted messages, edit history and media will be removed. Deleted messages will also disappear from chats.");
        builder.setPositiveButton("Clear", (dialog, which) -> Utilities.globalQueue.postRunnable(() -> {
            SgramDeletedController.clearAll();
            AndroidUtilities.runOnUIThread(this::refreshStats, 300);
        }));
        builder.setNegativeButton("Cancel", null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        TextView button = (TextView) dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (button != null) button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
    }

    // Журнал падений: причины завершения процесса от Android + стек Java-исключений
    private void showCrashReports() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        Utilities.globalQueue.postRunnable(() -> {
            String report = org.telegram.messenger.SgramCrashLog.buildReport();
            AndroidUtilities.runOnUIThread(() -> {
                if (getParentActivity() == null) return;
                TextView textView = new TextView(getParentActivity());
                textView.setText(report);
                textView.setTextIsSelectable(true);
                textView.setTypeface(android.graphics.Typeface.MONOSPACE);
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
                textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
                textView.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
                ScrollView scrollView = new ScrollView(getParentActivity());
                scrollView.addView(textView);
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setTitle("Crash Reports");
                builder.setView(scrollView);
                builder.setPositiveButton("Copy", (d, w) -> {
                    AndroidUtilities.addToClipboard(report);
                    BulletinFactory.of(this).createCopyBulletin("Report copied").show(true);
                });
                builder.setNeutralButton("Clear", (d, w) -> Utilities.globalQueue.postRunnable(org.telegram.messenger.SgramCrashLog::clear));
                builder.setNegativeButton("Close", null);
                showDialog(builder.create());
            });
        });
    }

    // --- Мои бусты (по мотивам плагина Boost Checker by @yzewe) ---

    private void showMyBoosts() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.show();
        TL_stories.TL_premium_getMyBoosts req = new TL_stories.TL_premium_getMyBoosts();
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            try {
                progress.dismiss();
            } catch (Exception ignore) {}
            if (getParentActivity() == null) return;
            if (error != null || !(response instanceof TL_stories.TL_premium_myBoosts)) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Failed to load boosts" + (error != null ? ": " + error.text : "")).show(true);
                return;
            }
            TL_stories.TL_premium_myBoosts res = (TL_stories.TL_premium_myBoosts) response;
            getMessagesController().putUsers(res.users, false);
            getMessagesController().putChats(res.chats, false);
            if (res.my_boosts.isEmpty()) {
                AlertDialog.Builder empty = new AlertDialog.Builder(getParentActivity());
                empty.setTitle("My Boosts");
                empty.setMessage("You have no active boosts.");
                empty.setPositiveButton("Close", null);
                showDialog(empty.create());
                return;
            }
            LinearLayout layout = new LinearLayout(getParentActivity());
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
            AlertDialog[] dialogRef = new AlertDialog[1];
            for (int i = 0; i < res.my_boosts.size(); i++) {
                TL_stories.TL_myBoost boost = res.my_boosts.get(i);
                SelectorUserCell cell = new SelectorUserCell(getParentActivity(), false, getResourceProvider(), i != res.my_boosts.size() - 1);
                cell.setBoost(boost);
                if (boost.peer != null) {
                    final TLRPC.Peer peer = boost.peer;
                    cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
                    cell.setOnClickListener(v -> {
                        if (dialogRef[0] != null) dialogRef[0].dismiss();
                        Bundle args = new Bundle();
                        if (peer.channel_id != 0) args.putLong("chat_id", peer.channel_id);
                        else if (peer.chat_id != 0) args.putLong("chat_id", peer.chat_id);
                        else args.putLong("user_id", peer.user_id);
                        presentFragment(new ChatActivity(args));
                    });
                }
                layout.addView(cell);
            }
            ScrollView scrollView = new ScrollView(getParentActivity());
            scrollView.addView(layout);
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle("My Boosts (" + res.my_boosts.size() + ")");
            builder.setView(scrollView);
            builder.setPositiveButton("Close", null);
            dialogRef[0] = builder.create();
            showDialog(dialogRef[0]);
        }));
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listAdapter != null) listAdapter.notifyDataSetChanged();
        refreshStats();
    }

    public void onTabReselected() { if (listView != null) listView.smoothScrollToPosition(0); }
    public boolean canAutoUpdate() { return false; }
    public boolean canScrollUp() { return listView != null && listView.canScrollVertically(-1); }
    public org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode getGlassSource() { return null; }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private Context mContext;
        public ListAdapter(Context context) { mContext = context; }

        @Override
        public int getItemCount() { return ROWS.length; }

        @Override
        public int getItemViewType(int position) {
            switch (ROWS[position]) {
                case ROW_TRAFFIC: return TYPE_HEADER;
                case ROW_GHOST: case ROW_HISTORY: case ROW_TOOLS: case ROW_PLUGINS_HEADER: return TYPE_BIG_HEADER;
                case ROW_SCAM: return TYPE_CHECK_SMALL;
                case ROW_VPN: case ROW_XRAY: case ROW_BYPASS: case ROW_SPEED: case ROW_PREMIUM: return TYPE_CHECK_LARGE;
                case ROW_ROUND_BACK: case ROW_CARD_SHOW: case ROW_FAST_CROP: case ROW_INLINE_CALC: case ROW_MUSIC_EDITOR: case ROW_DOC_VIEWER: return TYPE_CHECK_SMALL;
                case ROW_ONLY_TG: case ROW_GHOST_ONLINE: case ROW_GHOST_READ: case ROW_GHOST_STORIES: case ROW_GHOST_TYPING:
                case ROW_SAVE_DELETED: case ROW_SAVE_EDITS: case ROW_SAVE_MEDIA:
                    return TYPE_CHECK_SMALL;
                default: return TYPE_TEXT;
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == TYPE_TEXT || type == TYPE_CHECK_LARGE || type == TYPE_CHECK_SMALL;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == TYPE_HEADER || viewType == TYPE_BIG_HEADER) view = new HeaderCell(mContext);
            else if (viewType == TYPE_CHECK_LARGE) view = new TextCheckCell(mContext, 0);
            else if (viewType == TYPE_CHECK_SMALL) view = new TextCheckCell(mContext, 1);
            else if (viewType == TYPE_SHADOW) view = new ShadowSectionCell(mContext);
            else view = new TextSettingsCell(mContext);
            view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int row = ROWS[position];
            View v = holder.itemView;
            switch (row) {
                case ROW_TRAFFIC: {
                    String rx = formatBytes(preferences.getLong("all_time_rx", 0));
                    String tx = formatBytes(preferences.getLong("all_time_tx", 0));
                    ((HeaderCell) v).setText("Total VPN Traffic: ↓ " + rx + " / ↑ " + tx);
                    break;
                }
                case ROW_GHOST: bindBigHeader((HeaderCell) v, "Ghost Mode"); break;
                case ROW_HISTORY: bindBigHeader((HeaderCell) v, "Message History"); break;
                case ROW_TOOLS: bindBigHeader((HeaderCell) v, "Tools"); break;
                case ROW_PLUGINS_HEADER: bindBigHeader((HeaderCell) v, "Plugins"); break;
                case ROW_PLUGINS: bindText((TextSettingsCell) v, "exteraGram Plugins", org.telegram.messenger.SgramPlugins.isEngineEnabled() ? null : "Off", 18, false, false); break;
                case ROW_SCAM: bindSmallCheck((TextCheckCell) v, "GiD Scam Marks", org.telegram.messenger.SgramScamBase.isEnabled(), true, TOP_CHECK); break;
                case ROW_ROUND_CAM: bindText((TextSettingsCell) v, "Round Video Enhancer", org.telegram.messenger.SgramRoundCam.isEnabled() ? null : "Off", 18, true, false); break;
                case ROW_HIDDEN_FOLDER: bindText((TextSettingsCell) v, "Hidden Folder", org.telegram.messenger.SgramHiddenFolder.isEnabled() ? null : "Off", 18, true, false); break;
                case ROW_MD3_PLAYER: bindText((TextSettingsCell) v, "Material Player", org.telegram.messenger.SgramMaterialPlayer.isEnabled() ? null : "Off", 18, true, false); break;
                case ROW_SCAM_INFO: bindText((TextSettingsCell) v, "Scam Base", scamBaseValue(), 18, true, false); break;
                case ROW_LOCAL_NAMES: {
                    int count = org.telegram.messenger.SgramLocalNames.getAll().size();
                    bindText((TextSettingsCell) v, "Local Names", !org.telegram.messenger.SgramLocalNames.isEnabled() ? "Off" : count > 0 ? String.valueOf(count) : null, 18, true, false);
                    break;
                }

                case ROW_VPN: bindLargeCheck((TextCheckCell) v, "WireGuard", isVpnEnabled, true); break;
                case ROW_XRAY: bindLargeCheck((TextCheckCell) v, "Xray VPN", org.telegram.messenger.SgramXray.isEnabled(), true); break;
                case ROW_XRAY_SERVERS: {
                    org.telegram.messenger.SgramXray.Server server = org.telegram.messenger.SgramXray.getInstance().getSelected();
                    bindText((TextSettingsCell) v, "Subscriptions & Servers", server != null ? server.name : null, 21, false, false);
                    break;
                }
                case ROW_ROUND_BACK: bindSmallCheck((TextCheckCell) v, "Round Videos: Back Camera", preferences.getBoolean("round_back_camera", false), true, TOP_CHECK); break;
                case ROW_CARD_SHOW: bindSmallCheck((TextCheckCell) v, "Custom Wallet Cards", org.telegram.messenger.SgramCardSkin.isShowEnabled(), true, TOP_CHECK); break;
                case ROW_FAST_CROP: bindSmallCheck((TextCheckCell) v, "Fast Crop on Zoom", preferences.getBoolean("fast_crop", true), true, TOP_CHECK); break;
                case ROW_MUSIC_EDITOR: bindSmallCheck((TextCheckCell) v, "Music Editor", preferences.getBoolean(org.telegram.messenger.SgramMusicEditor.PREF, true), true, TOP_CHECK); break;
                case ROW_INLINE_CALC: bindSmallCheck((TextCheckCell) v, "Inline Calculator", preferences.getBoolean(org.telegram.messenger.SgramInlineCalc.PREF, true), true, TOP_CHECK); break;
                case ROW_DOC_VIEWER: bindSmallCheck((TextCheckCell) v, "Built-in Document Viewer", SgramDocViewer.isEnabled(), true, TOP_CHECK); break;
                case ROW_SPOOF: bindText((TextSettingsCell) v, "Device Spoof", org.telegram.messenger.SgramDeviceSpoof.isEnabled() ? org.telegram.messenger.SgramDeviceSpoof.profileName() : "Off", 18, true, false); break;
                case ROW_CARD_IMAGE: bindText((TextSettingsCell) v, "Wallet Card Image", null, 41, true, false); break;
                case ROW_BYPASS: bindLargeCheck((TextCheckCell) v, "Restrictions Bypass", preferences.getBoolean("bypass_all", false), true); break;
                case ROW_SPEED: bindLargeCheck((TextCheckCell) v, "Speed Boost", preferences.getBoolean("speed_boost", false), true); break;
                case ROW_PREMIUM: bindLargeCheck((TextCheckCell) v, "Local Premium", SgramPremiumController.isLocalPremium(), false); break;

                case ROW_ONLY_TG: bindSmallCheck((TextCheckCell) v, "Only for sGram", preferences.getBoolean("vpn_only_tg", false), false); break;
                case ROW_GHOST_ONLINE: bindSmallCheck((TextCheckCell) v, "Block Online", preferences.getBoolean("ghost_no_online", false), true); break;
                case ROW_GHOST_READ: bindSmallCheck((TextCheckCell) v, "Block Read Receipts", preferences.getBoolean("ghost_no_read", false), true); break;
                case ROW_GHOST_STORIES: bindSmallCheck((TextCheckCell) v, "Block Story Views", preferences.getBoolean("ghost_no_stories", false), true); break;
                case ROW_GHOST_TYPING: bindSmallCheck((TextCheckCell) v, "Block Typing", preferences.getBoolean("ghost_no_typing", false), false); break;
                case ROW_SAVE_DELETED: bindSmallCheck((TextCheckCell) v, "Save Deleted Messages", SgramDeletedController.isEnabled(), true); break;
                case ROW_SAVE_EDITS: bindSmallCheck((TextCheckCell) v, "Save Edit History", SgramDeletedController.isEditHistoryEnabled(), true); break;
                case ROW_SAVE_MEDIA: bindSmallCheck((TextCheckCell) v, "Save Media", SgramDeletedController.isSaveMedia(), true); break;

                case ROW_CONFIG: bindText((TextSettingsCell) v, "Config: " + configName, null, 21, true, false); break;
                case ROW_EXCLUDED: bindText((TextSettingsCell) v, "Excluded Applications", null, 21, false, false); break;
                case ROW_LIMIT: bindText((TextSettingsCell) v, "Cache Limit", formatLimit(SgramDeletedController.getLimitMb()), 41, true, false); break;
                case ROW_EXCLUDED_CHATS: {
                    int count = SgramDeletedController.getExcluded().size();
                    bindText((TextSettingsCell) v, "Excluded Chats", count > 0 ? String.valueOf(count) : null, 41, true, false);
                    break;
                }
                case ROW_VIEW_DELETED: bindText((TextSettingsCell) v, "View History", historyStats[0] + " del · " + historyStats[1] + " edited · " + formatBytes(historyStats[2]), 41, true, false); break;
                case ROW_CLEAR: bindText((TextSettingsCell) v, "Clear Cache", null, 41, false, true); break;
                case ROW_MINIAPPS: bindText((TextSettingsCell) v, "Mini Apps DevTools", null, 18, true, false); break;
                case ROW_BOOSTS: bindText((TextSettingsCell) v, "My Boosts", null, 18, true, false); break;
                case ROW_CRASHES: bindText((TextSettingsCell) v, "Crash Reports", null, 18, false, false); break;
            }
        }

        private TextView field(Object cell, Class<?> cls, String name) {
            try {
                java.lang.reflect.Field field = cls.getDeclaredField(name);
                field.setAccessible(true);
                return (TextView) field.get(cell);
            } catch (Exception ignore) {
                return null;
            }
        }

        private void bindBigHeader(HeaderCell cell, String text) {
            TextView tv = field(cell, HeaderCell.class, "textView");
            if (tv != null) {
                tv.setPadding(0, 0, 0, 0);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
                tv.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
                tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
            }
            cell.setText(text);
        }

        private void bindLargeCheck(TextCheckCell cell, String text, boolean checked, boolean divider) {
            TextView tv = field(cell, TextCheckCell.class, "textView");
            if (tv != null) {
                // 18dp — на одной линии с заголовками (Ghost Mode, Total VPN Traffic)
                tv.setPadding(AndroidUtilities.dp(18), 0, 0, 0);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
                tv.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
                tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
            }
            cell.setTextAndCheck(text, checked, divider);
        }

        // У TextCheckCell текст от самого края, у TextSettingsCell — с отступом 21dp: 39 здесь = 18 там
        private static final int TOP_CHECK = 39;

        private void bindSmallCheck(TextCheckCell cell, String text, boolean checked, boolean divider) {
            bindSmallCheck(cell, text, checked, divider, 41);
        }

        private void bindSmallCheck(TextCheckCell cell, String text, boolean checked, boolean divider, int indent) {
            TextView tv = field(cell, TextCheckCell.class, "textView");
            if (tv != null) {
                tv.setPadding(AndroidUtilities.dp(indent), 0, 0, 0);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
                tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            }
            cell.setTextAndCheck(text, checked, divider);
        }

        private void bindText(TextSettingsCell cell, String text, String value, int indent, boolean divider, boolean red) {
            if (value != null) cell.setTextAndValue(text, value, divider);
            else cell.setText(text, divider);
            cell.setTextColor(Theme.getColor(red ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteBlackText));
            TextView tv = field(cell, TextSettingsCell.class, "textView");
            if (tv != null) tv.setPadding(AndroidUtilities.dp(indent), 0, 0, 0);
        }
    }
}
