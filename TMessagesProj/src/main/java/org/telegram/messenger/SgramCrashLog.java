package org.telegram.messenger;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Журнал падений sGram без включения логов Telegram:
 * - стек Java-исключений пишется в files/sgram_crashes;
 * - причины завершения процесса от Android 11+ (нативные краши, ANR, убийство системой) с трейсами.
 */
public class SgramCrashLog {

    private static final int KEEP = 10;
    private static final SimpleDateFormat FORMAT = new SimpleDateFormat("dd.MM.yy HH:mm:ss", Locale.US);

    private static File dir() {
        File d = new File(ApplicationLoader.applicationContext.getFilesDir(), "sgram_crashes");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static void install() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, exception) -> {
            SgramPlugins.onCrash(exception);
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                pw.println(FORMAT.format(new Date()) + "  thread: " + thread.getName());
                pw.println("sGram " + BuildVars.BUILD_VERSION_STRING + ", Android " + Build.VERSION.RELEASE + ", " + Build.MANUFACTURER + " " + Build.MODEL);
                exception.printStackTrace(pw);
                if (isOom(exception)) memoryReport(pw);
                pw.flush();
                File f = new File(dir(), "java-" + System.currentTimeMillis() + ".txt");
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(sw.toString().getBytes(StandardCharsets.UTF_8));
                }
                File[] files = dir().listFiles();
                if (files != null && files.length > KEEP) {
                    Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                    for (int i = 0; i < files.length - KEEP; i++) files[i].delete();
                }
            } catch (Throwable ignore) {
            }
            if (previous != null) previous.uncaughtException(thread, exception);
        });
    }

    private static boolean isOom(Throwable e) {
        for (int i = 0; e != null && i < 10; e = e.getCause(), i++) {
            if (e instanceof OutOfMemoryError) return true;
        }
        return false;
    }

    // Что занимало память в момент OOM — чтобы найти утечку по следующему отчёту
    private static void memoryReport(PrintWriter pw) {
        try {
            Runtime rt = Runtime.getRuntime();
            pw.println();
            pw.println("Memory: heap " + (rt.totalMemory() - rt.freeMemory()) / 1048576 + "/" + rt.maxMemory() / 1048576
                    + " MB, native " + android.os.Debug.getNativeHeapAllocatedSize() / 1048576 + " MB, threads " + Thread.activeCount());
            pw.println("Hidden Folder " + SgramHiddenFolder.isEnabled() + ", Material Player " + SgramMaterialPlayer.isEnabled()
                    + ", Speed Boost " + SgramSpeedController.isSpeedBoost());
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (!UserConfig.isValidAccount(a) || !UserConfig.getInstance(a).isClientActivated()) continue;
                MessagesController mc = MessagesController.getInstance(a);
                int msgs = 0;
                for (int i = 0; i < mc.dialogMessage.size(); i++) {
                    java.util.ArrayList<MessageObject> l = mc.dialogMessage.valueAt(i);
                    if (l != null) msgs += l.size();
                }
                pw.println("acc " + a + ": dialogs " + mc.getAllDialogs().size() + ", dialogMessage " + msgs
                        + ", users " + mc.getUsers().size() + ", chats " + mc.getChats().size());
            }
        } catch (Throwable t) {
            pw.println("memoryReport: " + t);
        }
    }

    public static String buildReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("sGram ").append(BuildVars.BUILD_VERSION_STRING)
                .append(", Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append("), ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n\n");

        sb.append("=== Process exits (Android) ===\n");
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                ActivityManager am = (ActivityManager) ApplicationLoader.applicationContext.getSystemService(Context.ACTIVITY_SERVICE);
                List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(null, 0, 15);
                if (exits.isEmpty()) sb.append("(none)\n");
                for (ApplicationExitInfo e : exits) {
                    sb.append('\n').append(FORMAT.format(new Date(e.getTimestamp()))).append("  ").append(reasonName(e.getReason()));
                    sb.append("  process: ").append(e.getProcessName());
                    sb.append("\n  importance: ").append(importanceName(e.getImportance()));
                    sb.append(", status: ").append(e.getStatus());
                    sb.append(", pss: ").append(e.getPss() / 1024).append(" MB, rss: ").append(e.getRss() / 1024).append(" MB");
                    if (e.getDescription() != null) sb.append("\n  ").append(e.getDescription());
                    sb.append('\n');
                    int reason = e.getReason();
                    if (reason == ApplicationExitInfo.REASON_CRASH_NATIVE || reason == ApplicationExitInfo.REASON_ANR) {
                        String trace = readTrace(e);
                        if (trace != null) sb.append(trace).append('\n');
                    }
                }
            } catch (Throwable t) {
                sb.append("error: ").append(t).append('\n');
            }
        } else {
            sb.append("needs Android 11+\n");
        }

        sb.append("\n=== Java crashes ===\n");
        File[] files = dir().listFiles();
        if (files == null || files.length == 0) {
            sb.append("(none)\n");
        } else {
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File f : files) {
                sb.append('\n').append(readFile(f, 12000)).append('\n');
            }
        }
        return sb.toString();
    }

    public static void clear() {
        File[] files = dir().listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    private static String readTrace(ApplicationExitInfo e) {
        if (Build.VERSION.SDK_INT < 30) return null;
        try (InputStream in = e.getTraceInputStream()) {
            if (in == null) return null;
            // Нативный трейс — бинарный tombstone (protobuf), читаем только печатные строки
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null && lines < 120 && sb.length() < 16000) {
                String clean = line.replaceAll("[^\\x20-\\x7E\\t]", " ").trim();
                if (clean.length() < 4) continue;
                sb.append("  ").append(clean).append('\n');
                lines++;
            }
            return sb.toString();
        } catch (Throwable t) {
            return "  trace error: " + t;
        }
    }

    private static String readFile(File f, int max) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null && sb.length() < max) sb.append(line).append('\n');
        } catch (Throwable t) {
            sb.append("read error: ").append(t);
        }
        return sb.toString();
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case 1: return "EXIT_SELF";
            case 2: return "SIGNALED";
            case 3: return "LOW_MEMORY";
            case 4: return "CRASH (Java)";
            case 5: return "CRASH_NATIVE";
            case 6: return "ANR";
            case 7: return "INITIALIZATION_FAILURE";
            case 8: return "PERMISSION_CHANGE";
            case 9: return "EXCESSIVE_RESOURCE_USAGE";
            case 10: return "USER_REQUESTED";
            case 11: return "USER_STOPPED";
            case 12: return "DEPENDENCY_DIED";
            case 13: return "OTHER";
            case 14: return "FREEZER";
            case 15: return "PACKAGE_STATE_CHANGE";
            case 16: return "PACKAGE_UPDATED";
            default: return "UNKNOWN(" + reason + ")";
        }
    }

    private static String importanceName(int importance) {
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return "foreground";
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) return "foreground service";
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) return "visible";
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) return "service";
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED) return "cached";
        return String.valueOf(importance);
    }
}
