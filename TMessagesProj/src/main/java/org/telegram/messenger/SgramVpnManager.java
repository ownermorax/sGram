package org.telegram.messenger;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import com.wireguard.android.backend.Backend;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Statistics;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;
import com.wireguard.config.Interface;
import com.wireguard.config.Peer;
import org.telegram.ui.LaunchActivity;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.locks.ReentrantLock;

public class SgramVpnManager implements Tunnel {

    private static volatile SgramVpnManager instance;
    private final Backend backend;
    private long lastRx = 0;
    private long lastTx = 0;

    // GoBackend не потокобезопасен: getStatistics() из таймера уведомления читал нативный хэндл туннеля,
    // пока setState() в другом потоке его закрывал — процесс падал в Go без Java-исключения.
    // Все обращения к backend идут под этим локом.
    private final ReentrantLock lock = new ReentrantLock();
    // Пока туннель перезапускается, промежуточный DOWN не должен гасить сервис уведомления
    private volatile boolean changingState;

    public static SgramVpnManager getInstance() {
        SgramVpnManager local = instance;
        if (local == null) {
            synchronized (SgramVpnManager.class) {
                local = instance;
                if (local == null) instance = local = new SgramVpnManager();
            }
        }
        return local;
    }

    private SgramVpnManager() {
        backend = new GoBackend(ApplicationLoader.applicationContext);
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE);
    }

    @Override
    public String getName() { return "sGramVPN"; }

    @Override
    public void onStateChange(State newState) {
        Context context = ApplicationLoader.applicationContext;
        if (newState == State.UP) {
            prefs().edit().putBoolean("vpn_paused", false).apply();
            startForegroundService(context);
        } else if (newState == State.DOWN) {
            saveStatsToHistory();
            // Полностью тушим сервис, только если это не пауза и не перезапуск туннеля
            if (!changingState && !prefs().getBoolean("vpn_paused", false)) {
                context.stopService(new Intent(context, SgramVpnService.class));
            }
        }
    }

    private void startForegroundService(Context context) {
        Intent intent = new Intent(context, SgramVpnService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            FileLog.e("sGram VPN: startForegroundService failed", e);
        }
    }

    public boolean isUp() {
        lock.lock();
        try {
            return backend.getState(this) == State.UP;
        } catch (Exception e) {
            return false;
        } finally {
            lock.unlock();
        }
    }

    // null, если туннель сейчас меняет состояние — лучше пропустить тик, чем лезть в закрывающийся туннель
    public Statistics getStatistics() {
        if (!lock.tryLock()) return null;
        try {
            if (backend.getState(this) != State.UP) return null;
            return backend.getStatistics(this);
        } catch (Exception e) {
            return null;
        } finally {
            lock.unlock();
        }
    }

    public void saveStatsToHistory() {
        Statistics stats = getStatistics();
        if (stats == null) return;
        long currentRx = stats.totalRx();
        long currentTx = stats.totalTx();
        long deltaRx = currentRx - lastRx;
        long deltaTx = currentTx - lastTx;
        if (deltaRx > 0 || deltaTx > 0) {
            SharedPreferences prefs = prefs();
            prefs.edit()
                    .putLong("all_time_rx", prefs.getLong("all_time_rx", 0) + deltaRx)
                    .putLong("all_time_tx", prefs.getLong("all_time_tx", 0) + deltaTx)
                    .apply();
            lastRx = currentRx;
            lastTx = currentTx;
        }
    }

    private void setState(State state, Config config) {
        lock.lock();
        // Подъём при уже поднятом туннеле = DOWN + UP внутри GoBackend; явная остановка гасит сервис как обычно
        changingState = state == State.UP;
        try {
            backend.setState(this, state, config);
        } catch (Exception e) {
            FileLog.e("sGram VPN: setState " + state + " failed", e);
        } finally {
            changingState = false;
            lock.unlock();
        }
    }

    // Автозапуск при открытии приложения: если туннель уже поднят, не трогаем его
    public void ensureStarted() {
        Utilities.stageQueue.postRunnable(() -> {
            if (!isUp()) startVpnInternal();
        });
    }

    // Явный (пере)запуск — после смены настроек
    public void startVpn() {
        Utilities.stageQueue.postRunnable(this::startVpnInternal);
    }

    private void startVpnInternal() {
        try {
            SharedPreferences prefs = prefs();
            Intent intent = VpnService.prepare(ApplicationLoader.applicationContext);
            if (intent != null) {
                // Нет разрешения на VPN (или его забрал другой VPN) — просим на главном потоке
                AndroidUtilities.runOnUIThread(() -> {
                    if (LaunchActivity.instance != null) {
                        try {
                            LaunchActivity.instance.startActivityForResult(intent, 51820);
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    }
                });
                return;
            }

            Interface.Builder interfaceBuilder = new Interface.Builder()
                    .parsePrivateKey(prefs.getString("wg_priv_key", "").trim())
                    .parseAddresses(prefs.getString("wg_address", "").trim());

            interfaceBuilder.parseDnsServers(prefs.getString("wg_dns", "8.8.8.8"));

            // Режим: или "Только ТГ", или "Весь телефон с исключениями"
            if (prefs.getBoolean("vpn_only_tg", false)) {
                interfaceBuilder.includeApplication(ApplicationLoader.applicationContext.getPackageName());
                FileLog.d("sGram WG: Mode - Only Telegram");
            } else {
                Set<String> excluded = prefs.getStringSet("vpn_excluded_apps", new HashSet<>());
                for (String pkg : excluded) {
                    try {
                        interfaceBuilder.excludeApplication(pkg);
                    } catch (Exception e) {
                        FileLog.e("sGram WG: Failed to exclude " + pkg, e);
                    }
                }
                FileLog.d("sGram WG: Mode - Full Phone with Exclusions (" + excluded.size() + ")");
            }

            Config config = new Config.Builder()
                    .setInterface(interfaceBuilder.build())
                    .addPeer(new Peer.Builder()
                            .parsePublicKey(prefs.getString("wg_pub_key", "").trim())
                            .parseEndpoint(prefs.getString("wg_endpoint", "").trim())
                            .parseAllowedIPs("0.0.0.0/0")
                            .build())
                    .build();

            lastRx = 0;
            lastTx = 0;
            setState(State.UP, config);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public void stopVpn() {
        saveStatsToHistory();
        prefs().edit().putBoolean("vpn_paused", false).apply();
        Utilities.stageQueue.postRunnable(() -> setState(State.DOWN, null));
    }

    public void pauseVpn() {
        saveStatsToHistory();
        prefs().edit().putBoolean("vpn_paused", true).apply();
        Utilities.stageQueue.postRunnable(() -> setState(State.DOWN, null));
    }
}
