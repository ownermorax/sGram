package org.telegram.messenger;

import android.app.*;
import android.content.*;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import org.telegram.ui.LaunchActivity;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

public class SgramVpnService extends Service {
    private static final String CHANNEL_ID = "sgram_vpn_live";
    public static final String ACTION_PAUSE = "org.telegram.messenger.SgramVpnService.PAUSE";
    public static final String ACTION_RESUME = "org.telegram.messenger.SgramVpnService.RESUME";

    private Timer statsTimer;
    private long startTime = 0;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_PAUSE.equals(action)) {
                SgramVpnManager.getInstance().pauseVpn();
                updateNotification();
            } else if (ACTION_RESUME.equals(action)) {
                SgramVpnManager.getInstance().startVpn();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        // 1. МГНОВЕННО: startForeground до createNotificationChannel() и любого I/O.
        // На API 35 таймаут очень короткий, createNotificationChannel() — binder call
        // к system_server, который может блокироваться.
        try {
            Notification miniNotification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.menu_2sv)
                    .setContentTitle("sGram VPN")
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                    .build();
            startForeground(1001, miniNotification);
        } catch (Exception e) {
            FileLog.e("sGram VPN: startForeground in onCreate failed", e);
        }

        // 2. Только после startForeground — создаём канал (может делать I/O)
        createNotificationChannel();

        // 3. После канала обновляем уведомление с полным содержимым
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(1001, getNotification(null));
            }
        } catch (Exception e) {
            FileLog.e("sGram VPN: notification update in onCreate failed", e);
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PAUSE);
        filter.addAction(ACTION_RESUME);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 1. МГНОВЕННО: минимальное уведомление, никакого I/O
        Notification minNotification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.menu_2sv)
                .setContentTitle("sGram VPN")
                .setContentText("Starting...")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
        try {
            startForeground(1001, minNotification);
        } catch (Exception e) {
            FileLog.e("sGram VPN: startForeground in onStartCommand failed", e);
        }

        // START_STICKY: после убийства процесса система поднимает только этот сервис, без туннеля.
        // Поднимаем туннель сами (если VPN включён), иначе висело бы уведомление «Connected» без VPN
        if (intent == null) {
            SharedPreferences prefs = getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE);
            if (prefs.getBoolean("vpn_enabled", false) && !prefs.getBoolean("vpn_paused", false)) {
                SgramVpnManager.getInstance().ensureStarted();
            } else if (!prefs.getBoolean("vpn_paused", false)) {
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        // 2. После того как система уведомлена — фоном загружаем данные и обновляем уведомление
        if (startTime == 0) startTime = System.currentTimeMillis();
        new Thread(() -> {
            // Важно: getNotification() читает SharedPreferences — синхронный I/O на диске.
            // Выполняем в фоне, чтобы не блокировать главный поток.
            Notification fullNotification = getNotification(null);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(1001, fullNotification);
            }
        }).start();

        startStatsUpdater();
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "VPN Status", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void startStatsUpdater() {
        if (statsTimer != null) statsTimer.cancel();
        statsTimer = new Timer();
        statsTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                SgramVpnManager.getInstance().saveStatsToHistory();
                updateNotification();
            }
        }, 1000, 1000);
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(1001, getNotification(null));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private Notification getNotification(String manualText) {
        SharedPreferences prefs = getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE);
        boolean isPaused = prefs.getBoolean("vpn_paused", false);

        String text;
        if (isPaused) {
            text = "VPN is Paused";
        } else if (manualText != null) {
            text = manualText;
        } else {
            // null — туннель сейчас перезапускается (или ещё не поднят): статистику не трогаем
            com.wireguard.android.backend.Statistics stats = SgramVpnManager.getInstance().getStatistics();
            text = stats != null ? String.format(Locale.ENGLISH, "↓ %s | ↑ %s", formatBytes(stats.totalRx()), formatBytes(stats.totalTx())) : "Connecting...";
        }

        // Клик по самому уведомлению - открывает sGram
        Intent piIntent = new Intent(this, LaunchActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, piIntent, PendingIntent.FLAG_IMMUTABLE);

        // Настройка интента для кнопок Pause/Resume
        // Явно указываем пакет (getPackageName()), чтобы Android 14+ пропустил Broadcast
        Intent actionIntent = new Intent(isPaused ? ACTION_RESUME : ACTION_PAUSE);
        actionIntent.setPackage(getPackageName());

        PendingIntent actionPI = PendingIntent.getBroadcast(
                this,
                isPaused ? 2 : 1, // Разные ID для разных состояний
                actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.menu_2sv) // Твоя иконка
                .setContentTitle(isPaused ? "sGram VPN: Paused" : "sGram VPN: Connected")
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setUsesChronometer(!isPaused) // Таймер идет только когда VPN активен
                .setWhen(startTime)
                .setContentIntent(pi)
                .setColor(0xFF0088CC) // Синий цвет Telegram
                .setOnlyAlertOnce(true) // Чтобы шторка не "прыгала" каждую секунду
                // Добавляем кнопку действия
                .addAction(0, isPaused ? "RESUME" : "PAUSE", actionPI)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);

        return builder.build();
    }


    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.ENGLISH, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignore) {}
        if (statsTimer != null) statsTimer.cancel();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
