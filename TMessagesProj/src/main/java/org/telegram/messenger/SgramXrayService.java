package org.telegram.messenger;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

/**
 * Ядро Xray в отдельном процессе :xray (в основном процессе уже есть Go-рантайм WireGuard,
 * два Go-рантайма в одном процессе падают с SIGSEGV). Основной процесс шлёт сюда готовый конфиг,
 * состояние и пинг возвращаются широковещательными интентами внутри пакета.
 * В режиме «весь телефон» поднимает VPN и отдаёт tun ядру; иначе — только локальный SOCKS.
 *
 * Здесь нельзя трогать ApplicationLoader и прочее из основного процесса — только Context сервиса.
 */
public class SgramXrayService extends VpnService {

    public static final String ACTION_START = "org.telegram.sgram.XRAY_START";
    public static final String ACTION_STOP = "org.telegram.sgram.XRAY_STOP";
    public static final String ACTION_PING = "org.telegram.sgram.XRAY_PING";
    public static final String BROADCAST_STATE = "org.telegram.sgram.XRAY_STATE";
    public static final String BROADCAST_PING = "org.telegram.sgram.XRAY_PING_RESULT";
    private static final String TAG = "sGramXray";
    private static final String CHANNEL_ID = "sgram_xray";
    private static final int NOTIFICATION_ID = 51821;

    private final Object lock = new Object();
    private CoreController controller;
    private ParcelFileDescriptor tun;
    private boolean running;
    private boolean foreground;
    private int pinging;
    private static boolean envReady;

    private final CoreCallbackHandler callback = new CoreCallbackHandler() {
        @Override
        public long onEmitStatus(long code, String status) {
            Log.d(TAG, "status: " + status);
            return 0;
        }

        @Override
        public long shutdown() {
            return 0;
        }

        @Override
        public long startup() {
            return 0;
        }
    };

    private void ensureEnv() {
        synchronized (SgramXrayService.class) {
            if (envReady) return;
            File dir = new File(getFilesDir(), "xray");
            dir.mkdirs();
            Libv2ray.initCoreEnv(dir.getAbsolutePath(), "");
            envReady = true;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(action)) {
            startForegroundCompat(intent.getStringExtra("name"));
            String config = intent.getStringExtra("config");
            boolean withTun = intent.getBooleanExtra("tun", false);
            ArrayList<String> excluded = intent.getStringArrayListExtra("excluded");
            new Thread(() -> start(config, withTun, excluded), "xray-start").start();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            new Thread(() -> {
                stopCore();
                broadcastState(null);
                stopForegroundCompat();
                stopIfIdle();
            }, "xray-stop").start();
            return START_NOT_STICKY;
        }
        if (ACTION_PING.equals(action)) {
            ArrayList<String> ids = intent.getStringArrayListExtra("ids");
            ArrayList<String> configs = intent.getStringArrayListExtra("configs");
            if (ids != null && configs != null) ping(ids, configs);
            return START_NOT_STICKY;
        }
        // Перезапуск системой без интента — ядро уже потеряно, выходим
        stopIfIdle();
        return START_NOT_STICKY;
    }

    private void start(String config, boolean withTun, ArrayList<String> excluded) {
        String error = null;
        synchronized (lock) {
            stopCoreLocked();
            try {
                ensureEnv();
                int fd = 0;
                if (withTun) {
                    Builder builder = new Builder();
                    builder.setSession("sGram Xray");
                    builder.setMtu(1500);
                    builder.addAddress("10.10.14.1", 30);
                    builder.addRoute("0.0.0.0", 0);
                    builder.addDnsServer("1.1.1.1");
                    // Сам sGram (все его процессы) в туннель не идёт — он ходит через локальный SOCKS ядра
                    builder.addDisallowedApplication(getPackageName());
                    if (excluded != null) {
                        for (String pkg : excluded) {
                            try {
                                builder.addDisallowedApplication(pkg);
                            } catch (Exception ignore) {
                            }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false);
                    tun = builder.establish();
                    if (tun == null) throw new IllegalStateException("VPN permission revoked");
                    fd = tun.getFd();
                }
                if (controller == null) controller = Libv2ray.newCoreController(callback);
                controller.startLoop(config, fd);
                running = controller.getIsRunning();
                if (!running) error = "core failed to start";
            } catch (Throwable e) {
                Log.e(TAG, "start", e);
                error = e.getMessage() != null ? e.getMessage() : e.toString();
                stopCoreLocked();
            }
        }
        broadcastState(error);
        if (!running) {
            stopForegroundCompat();
            stopIfIdle();
        }
    }

    private void stopCore() {
        synchronized (lock) {
            stopCoreLocked();
        }
    }

    // Ядро останавливается раньше, чем закрывается tun — иначе оно держит дескриптор
    private void stopCoreLocked() {
        try {
            if (controller != null && controller.getIsRunning()) controller.stopLoop();
        } catch (Throwable e) {
            Log.e(TAG, "stop", e);
        }
        running = false;
        try {
            if (tun != null) tun.close();
        } catch (Exception ignore) {
        }
        tun = null;
    }

    private void ping(ArrayList<String> ids, ArrayList<String> configs) {
        synchronized (lock) {
            pinging++;
        }
        new Thread(() -> {
            ExecutorService pool = Executors.newFixedThreadPool(6);
            CountDownLatch latch = new CountDownLatch(ids.size());
            try {
                ensureEnv();
            } catch (Throwable e) {
                Log.e(TAG, "env", e);
            }
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i), cfg = configs.get(i);
                pool.execute(() -> {
                    int ms = -1;
                    try {
                        long d = Libv2ray.measureOutboundDelay(cfg, SgramXray.TEST_URL);
                        ms = d > 0 ? (int) d : -1;
                    } catch (Throwable e) {
                        Log.d(TAG, "ping " + id + ": " + e);
                    }
                    Intent r = new Intent(BROADCAST_PING).setPackage(getPackageName());
                    r.putExtra("id", id).putExtra("ms", ms);
                    sendBroadcast(r);
                    latch.countDown();
                });
            }
            pool.shutdown();
            try {
                latch.await(90, TimeUnit.SECONDS);
            } catch (InterruptedException ignore) {
            }
            sendBroadcast(new Intent(BROADCAST_PING).setPackage(getPackageName()).putExtra("done", true));
            synchronized (lock) {
                pinging--;
            }
            stopIfIdle();
        }, "xray-ping").start();
    }

    private void stopIfIdle() {
        synchronized (lock) {
            if (!running && pinging == 0) stopSelf();
        }
    }

    private void broadcastState(String error) {
        Intent i = new Intent(BROADCAST_STATE).setPackage(getPackageName());
        i.putExtra("running", running);
        if (error != null) i.putExtra("error", error);
        sendBroadcast(i);
    }

    private void startForegroundCompat(String serverName) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "sGram Xray", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, LaunchActivity.class), PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.menu_2sv)
                .setContentTitle("sGram Xray")
                .setContentText(serverName != null ? serverName : "")
                .setOngoing(true)
                .setUsesChronometer(true)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(pi)
                .setColor(0xFF0088CC)
                .setOnlyAlertOnce(true);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, b.build());
        }
        foreground = true;
    }

    private void stopForegroundCompat() {
        if (!foreground) return;
        foreground = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    @Override
    public void onRevoke() {
        stopCore();
        broadcastState("VPN was turned off by another app");
        stopForegroundCompat();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopCore();
        broadcastState(null);
        super.onDestroy();
        // Процесс :xray больше не нужен — освобождаем память
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
