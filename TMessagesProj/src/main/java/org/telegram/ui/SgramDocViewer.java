package org.telegram.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SgramDocExtract;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Просмотр файлов внутри приложения (по мотивам плагина Doc Viewer by Daxo-Developer @Daxo_OS):
 * PDF, DOCX, XLSX, PPTX, ODF, RTF, EPUB, FB2, EML, Markdown, HTML, SVG, CSV, JSON, код, архивы, картинки.
 */
public class SgramDocViewer extends Dialog {

    public static final String PREF_ENABLED = "doc_viewer";
    // «Открыть в другом приложении» — openForView пропускает просмотрщик
    public static boolean bypass;

    private static final int BG = 0xff09090d, BAR = 0xd916161e, TEXT = 0xfff1f1f6, SUB = 0xff9698aa;
    private static final int CHUNK = 6000;
    private static final DispatchQueue queue = new DispatchQueue("sgramDocViewer");

    private final Activity activity;
    private final File file;
    private final String name, mime, fmt;
    private TextView subtitle;
    private FrameLayout content;
    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private LruCache<Integer, Bitmap> pageCache;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        return prefs().getBoolean(PREF_ENABLED, true);
    }

    /** Из AndroidUtilities.openForView: true — открыли сами. */
    public static boolean tryOpen(Activity activity, File f, String name, String mime) {
        if (bypass || activity == null || f == null || !f.exists() || f.length() == 0 || !isEnabled()) return false;
        if (TextUtils.isEmpty(name)) name = f.getName();
        String fmt = SgramDocExtract.detect(name, mime);
        if (fmt == null) {
            if (f.length() > SgramDocExtract.MAX_TEXT || !SgramDocExtract.looksLikeText(f)) return false;
            fmt = "text";
        }
        if (("text".equals(fmt) || "csv".equals(fmt) || "json".equals(fmt)) && !SgramDocExtract.looksLikeText(f)) return false;
        // apk — ставится системой, не смотрим
        if (name.toLowerCase(Locale.ROOT).endsWith(".apk")) return false;
        try {
            new SgramDocViewer(activity, f, name, mime, fmt).show();
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private SgramDocViewer(Activity activity, File file, String name, String mime, String fmt) {
        super(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        this.activity = activity;
        this.file = file;
        this.name = name;
        this.mime = mime;
        this.fmt = fmt;
        Window w = getWindow();
        if (w != null) {
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            w.setStatusBarColor(BG);
            w.setNavigationBarColor(BG);
        }
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BAR);
        bar.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
        bar.addView(button("✕", v -> dismiss()), LayoutHelper.createLinear(48, 48));
        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText(name);
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titles.addView(title);
        subtitle = new TextView(activity);
        subtitle.setTextColor(SUB);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        subtitle.setSingleLine();
        subtitle.setText(SgramDocExtract.size(file.length()));
        titles.addView(subtitle);
        bar.addView(titles, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 4, 0, 4, 0));
        bar.addView(button("↗", v -> openExternally()), LayoutHelper.createLinear(48, 48));
        root.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52));

        content = new FrameLayout(activity);
        root.addView(content, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        setContentView(root);
        setOnDismissListener(d -> release());
        load();
    }

    private TextView button(String text, View.OnClickListener l) {
        TextView b = new TextView(activity);
        b.setText(text);
        b.setTextColor(TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Theme.createSelectorDrawable(0x33ffffff, 1));
        b.setOnClickListener(l);
        return b;
    }

    private void openExternally() {
        dismiss();
        bypass = true;
        try {
            AndroidUtilities.openForView(file, name, mime, activity, null, false);
        } finally {
            bypass = false;
        }
    }

    private TextView message(String text) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextColor(SUB);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        t.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);
        return t;
    }

    private void show(View v) {
        content.removeAllViews();
        content.addView(v, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    private void load() {
        show(message("Loading…"));
        switch (fmt) {
            case "pdf": loadPdf(); break;
            case "image": loadImage(); break;
            case "web": loadWeb(); break;
            default: loadText(); break;
        }
    }

    private void fail(Throwable e) {
        FileLog.e(e);
        AndroidUtilities.runOnUIThread(() -> show(message("Can't open this file\n" + (e.getMessage() != null ? e.getMessage() : e.toString()) + "\n\nTap ↗ to open in another app")));
    }

    // --- текст ---

    private void loadText() {
        queue.postRunnable(() -> {
            try {
                boolean[] mono = {false};
                String text = SgramDocExtract.extract(file, fmt, mono);
                ArrayList<String> chunks = new ArrayList<>();
                int i = 0;
                while (i < text.length()) {
                    int end = Math.min(text.length(), i + CHUNK);
                    if (end < text.length()) {
                        int nl = text.lastIndexOf('\n', end);
                        if (nl > i + CHUNK / 2) end = nl + 1;
                    }
                    chunks.add(text.substring(i, end));
                    i = end;
                }
                int lines = 1;
                for (int k = 0; k < text.length(); k++) if (text.charAt(k) == '\n') lines++;
                final int lineCount = lines;
                AndroidUtilities.runOnUIThread(() -> showText(chunks, mono[0], lineCount));
            } catch (Throwable e) {
                fail(e);
            }
        });
    }

    private void showText(ArrayList<String> chunks, boolean mono, int lines) {
        subtitle.setText(label() + " · " + lines + " lines · " + SgramDocExtract.size(file.length()));
        RecyclerView list = new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                TextView t = new TextView(activity);
                t.setTextColor(0xffe8e8ee);
                t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, mono ? 12 : 15);
                if (mono) t.setTypeface(Typeface.MONOSPACE);
                t.setLineSpacing(0, 1.15f);
                t.setTextIsSelectable(true);
                t.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
                if (mono) {
                    t.setHorizontallyScrolling(false);
                }
                t.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(t) {};
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                ((TextView) holder.itemView).setText(chunks.get(position));
            }

            @Override
            public int getItemCount() {
                return chunks.size();
            }
        });
        list.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(24));
        list.setClipToPadding(false);
        // Таблицы и код — без переноса, с прокруткой вбок
        if (mono && ("xlsx".equals(fmt) || "csv".equals(fmt) || "archive".equals(fmt))) {
            HorizontalScrollView h = new HorizontalScrollView(activity);
            h.setFillViewport(true);
            h.addView(list, new FrameLayout.LayoutParams(AndroidUtilities.dp(1400), ViewGroup.LayoutParams.MATCH_PARENT));
            show(h);
        } else {
            show(list);
        }
    }

    private String label() {
        switch (fmt) {
            case "docx": return "DOCX";
            case "xlsx": return "Excel";
            case "pptx": return "PowerPoint";
            case "odf": return "OpenDocument";
            case "rtf": return "RTF";
            case "eml": return "Email";
            case "epub": return "EPUB";
            case "fb2": return "FB2";
            case "archive": return "Archive";
            case "csv": return "CSV";
            case "json": return "JSON";
            case "ipynb": return "Jupyter";
            default: return SgramDocExtract.ext(name).toUpperCase(Locale.ROOT);
        }
    }

    // --- веб ---

    private void loadWeb() {
        queue.postRunnable(() -> {
            try {
                String text = SgramDocExtract.readText(file);
                String ext = SgramDocExtract.ext(name);
                String html;
                if (ext.equals("md") || ext.equals("markdown") || ext.equals("mdown")) html = SgramDocExtract.markdownToHtml(text, true);
                else if (ext.equals("svg")) html = SgramDocExtract.page("<div style='text-align:center'>" + text + "</div>", true).replace("<svg", "<svg style='max-width:100%;height:auto'");
                else html = text;
                AndroidUtilities.runOnUIThread(() -> {
                    WebView web = new WebView(activity);
                    WebSettings s = web.getSettings();
                    s.setJavaScriptEnabled(false);
                    s.setAllowFileAccess(false);
                    s.setBuiltInZoomControls(true);
                    s.setDisplayZoomControls(false);
                    s.setLoadWithOverviewMode(true);
                    s.setUseWideViewPort(!ext.equals("md") && !ext.equals("markdown") && !ext.equals("mdown"));
                    web.setBackgroundColor(BG);
                    web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
                    subtitle.setText((ext.equals("svg") ? "SVG" : ext.startsWith("m") ? "Markdown" : "HTML") + " · " + SgramDocExtract.size(file.length()));
                    show(web);
                });
            } catch (Throwable e) {
                fail(e);
            }
        });
    }

    // --- картинки ---

    private void loadImage() {
        queue.postRunnable(() -> {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), o);
                int max = Math.max(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y) * 2;
                int sample = 1;
                while (o.outWidth / sample > max || o.outHeight / sample > max) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                Bitmap b = BitmapFactory.decodeFile(file.getAbsolutePath(), o2);
                if (b == null) throw new Exception("Unsupported image");
                int w = o.outWidth, h = o.outHeight;
                AndroidUtilities.runOnUIThread(() -> {
                    ImageView iv = new ImageView(activity);
                    iv.setImageBitmap(b);
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    ZoomLayout zoom = new ZoomLayout(activity);
                    zoom.addView(iv, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
                    subtitle.setText(w + "×" + h + " · " + SgramDocExtract.size(file.length()));
                    show(zoom);
                });
            } catch (Throwable e) {
                fail(e);
            }
        });
    }

    // --- PDF ---

    private void loadPdf() {
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
        } catch (Throwable e) {
            fail(e);
            return;
        }
        int count = renderer.getPageCount();
        pageCache = new LruCache<Integer, Bitmap>(64 * 1024 * 1024) {
            @Override
            protected int sizeOf(Integer key, Bitmap value) {
                return value.getByteCount();
            }
        };
        int width = AndroidUtilities.displaySize.x;
        // размеры страниц заранее — чтобы список не прыгал
        float[] ratios = new float[count];
        synchronized (this) {
            for (int i = 0; i < count; i++) {
                try (PdfRenderer.Page p = renderer.openPage(i)) {
                    ratios[i] = p.getHeight() / (float) Math.max(1, p.getWidth());
                } catch (Throwable e) {
                    ratios[i] = 1.414f;
                }
            }
        }
        RecyclerView list = new RecyclerView(activity);
        LinearLayoutManager lm = new LinearLayoutManager(activity);
        list.setLayoutManager(lm);
        list.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                ImageView iv = new ImageView(activity);
                iv.setBackgroundColor(Color.WHITE);
                iv.setScaleType(ImageView.ScaleType.FIT_XY);
                return new RecyclerView.ViewHolder(iv) {};
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                ImageView iv = (ImageView) holder.itemView;
                int w = width - AndroidUtilities.dp(8);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(w, (int) (w * ratios[position]));
                lp.setMargins(AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4));
                iv.setLayoutParams(lp);
                iv.setTag(position);
                Bitmap cached = pageCache.get(position);
                iv.setImageBitmap(cached);
                if (cached == null) {
                    queue.postRunnable(() -> {
                        Bitmap b = renderPage(position, w);
                        AndroidUtilities.runOnUIThread(() -> {
                            if (b != null) pageCache.put(position, b);
                            if (Integer.valueOf(position).equals(iv.getTag())) iv.setImageBitmap(b);
                        });
                    });
                }
            }

            @Override
            public int getItemCount() {
                return count;
            }
        });
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                int first = lm.findFirstVisibleItemPosition();
                subtitle.setText("PDF · page " + (first + 1) + " / " + count);
            }
        });
        subtitle.setText("PDF · page 1 / " + count);
        ZoomLayout zoom = new ZoomLayout(activity);
        zoom.addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        show(zoom);
    }

    // Рендер в 2× ширины экрана, чтобы при зуме текст оставался чётким
    private synchronized Bitmap renderPage(int index, int width) {
        if (renderer == null) return null;
        try (PdfRenderer.Page page = renderer.openPage(index)) {
            int w = Math.min(width * 2, 2400);
            int h = (int) (w * page.getHeight() / (float) Math.max(1, page.getWidth()));
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            b.eraseColor(Color.WHITE);
            page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return b;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private synchronized void release() {
        try {
            if (renderer != null) renderer.close();
            if (pfd != null) pfd.close();
        } catch (Throwable ignore) {
        }
        renderer = null;
        pfd = null;
        if (pageCache != null) pageCache.evictAll();
    }

    /** Зум щипком и двойным тапом для всего содержимого, при зуме — перетаскивание во все стороны. */
    private static class ZoomLayout extends FrameLayout {
        private float scale = 1f, tx, ty, lastX, lastY, scrollRest;
        private boolean dragging;
        private VelocityTracker velocity;
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector gestureDetector;

        ZoomLayout(Context context) {
            super(context);
            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                    return true;
                }
            });
            gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (scale > 1.1f) {
                        scale = 1f;
                        tx = ty = 0;
                        apply();
                    } else {
                        zoomBy(2.5f, e.getX(), e.getY());
                    }
                    return true;
                }
            });
        }

        private void zoomBy(float f, float fx, float fy) {
            float ns = Math.max(1f, Math.min(5f, scale * f));
            f = ns / scale;
            // точка под пальцами остаётся на месте
            tx = fx - (fx - tx) * f;
            ty = fy - (fy - ty) * f;
            scale = ns;
            apply();
        }

        private void apply() {
            float minX = getWidth() - getWidth() * scale, minY = getHeight() - getHeight() * scale;
            tx = Math.min(0, Math.max(minX, tx));
            ty = Math.min(0, Math.max(minY, ty));
            if (getChildCount() == 0) return;
            View c = getChildAt(0);
            c.setPivotX(0);
            c.setPivotY(0);
            c.setScaleX(scale);
            c.setScaleY(scale);
            c.setTranslationX(tx);
            c.setTranslationY(ty);
        }

        // Пока зум > 1 (или два пальца), все жесты наши: двигаем вправо-влево сами,
        // вверх-вниз — прокручиваем список, а у края списка двигаем содержимое.
        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            scaleDetector.onTouchEvent(ev);
            gestureDetector.onTouchEvent(ev);
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                dragging = false;
                if (velocity == null) velocity = VelocityTracker.obtain();
                velocity.clear();
            }
            if (velocity != null) velocity.addMovement(ev);
            if (!dragging && (ev.getPointerCount() > 1 || scale > 1.01f)) {
                dragging = true;
                if (a != MotionEvent.ACTION_DOWN) {
                    MotionEvent c = MotionEvent.obtain(ev);
                    c.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(c);
                    c.recycle();
                }
                resetFocus(ev);
            }
            if (!dragging) {
                super.dispatchTouchEvent(ev);
                return true;
            }
            if (a == MotionEvent.ACTION_POINTER_DOWN || a == MotionEvent.ACTION_POINTER_UP) {
                resetFocus(ev);
            } else if (a == MotionEvent.ACTION_MOVE) {
                float x = focusX(ev), y = focusY(ev);
                pan(x - lastX, y - lastY);
                lastX = x;
                lastY = y;
            } else if (a == MotionEvent.ACTION_UP && getChildCount() > 0 && getChildAt(0) instanceof RecyclerView) {
                velocity.computeCurrentVelocity(1000);
                ((RecyclerView) getChildAt(0)).fling(0, (int) (-velocity.getYVelocity() / scale));
            }
            return true;
        }

        private void pan(float dx, float dy) {
            tx += dx;
            View c = getChildCount() > 0 ? getChildAt(0) : null;
            if (c instanceof RecyclerView && dy != 0 && c.canScrollVertically(dy > 0 ? -1 : 1)) {
                scrollRest -= dy / scale;
                int s = (int) scrollRest;
                scrollRest -= s;
                c.scrollBy(0, s);
            } else {
                ty += dy;
            }
            apply();
        }

        private void resetFocus(MotionEvent ev) {
            lastX = focusX(ev);
            lastY = focusY(ev);
        }

        private float focusX(MotionEvent ev) {
            return focus(ev, true);
        }

        private float focusY(MotionEvent ev) {
            return focus(ev, false);
        }

        // Центр пальцев без отпускаемого — без скачка при отпускании одного из двух
        private float focus(MotionEvent ev, boolean x) {
            int skip = ev.getActionMasked() == MotionEvent.ACTION_POINTER_UP ? ev.getActionIndex() : -1;
            float sum = 0;
            int n = 0;
            for (int i = 0; i < ev.getPointerCount(); i++) {
                if (i == skip) continue;
                sum += x ? ev.getX(i) : ev.getY(i);
                n++;
            }
            return n == 0 ? 0 : sum / n;
        }
    }
}
