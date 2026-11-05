package org.telegram.messenger;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Картинка в буфер обмена (как «Копировать фото» в AyuGram): файл в files/cache + FileProvider. */
public class SgramClipboard {

    private static File target(String ext) {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache");
        dir.mkdirs();
        File[] old = dir.listFiles((d, name) -> name.startsWith("sgram_clip_"));
        if (old != null) for (File f : old) f.delete();
        return new File(dir, "sgram_clip_" + System.currentTimeMillis() + "." + ext);
    }

    public static boolean copyBitmap(Bitmap bitmap) {
        try {
            File file = target("png");
            try (OutputStream out = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            return copyUri(file);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    public static boolean copyFile(File src) {
        if (src == null || !src.exists()) return false;
        try {
            String name = src.getName().toLowerCase();
            String ext = name.endsWith(".png") ? "png" : name.endsWith(".webp") ? "webp" : name.endsWith(".gif") ? "gif" : "jpg";
            File file = target(ext);
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return copyUri(file);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    private static boolean copyUri(File file) {
        Context ctx = ApplicationLoader.applicationContext;
        Uri uri = FileProvider.getUriForFile(ctx, ApplicationLoader.getApplicationId() + ".provider", file);
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newUri(ctx.getContentResolver(), "image", uri));
        return true;
    }
}
