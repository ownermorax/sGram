package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebStorage;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Инструменты для мини-аппов (по мотивам плагина MiniApp Injector by @huecoder):
 * eruda-консоль, свои JS-скрипты, разлок выделения, зум, remote debug, инспектор WebApp.
 * Код внедряется через evaluateJavascript, поэтому работает и при строгом CSP. Всё выключено по умолчанию.
 */
public class SgramMiniAppTools {

    public static final String ERUDA_URL = "https://cdn.jsdelivr.net/npm/eruda@3.4.1/eruda.js";

    public static final String PREF_ERUDA = "miniapp_eruda";
    public static final String PREF_UNLOCK = "miniapp_unlock";
    public static final String PREF_ZOOM = "miniapp_zoom";
    public static final String PREF_REMOTE_DEBUG = "miniapp_remote_debug";
    private static final String PREF_SOURCES = "miniapp_sources";

    private static final String JS_ZOOM = "(function(){try{var m=document.querySelector('meta[name=viewport]');"
            + "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}"
            + "m.setAttribute('content','width=device-width, initial-scale=1, maximum-scale=6, user-scalable=yes');"
            + "}catch(e){}})();";
    private static final String JS_UNLOCK = "(function(){try{var s=document.createElement('style');"
            + "s.innerHTML='*{-webkit-user-select:text!important;user-select:text!important;-webkit-touch-callout:default!important;}';"
            + "(document.head||document.documentElement).appendChild(s);}catch(e){}})();";
    private static final String JS_INFO = "(function(){try{var w=window.Telegram&&window.Telegram.WebApp||{};"
            + "return JSON.stringify({version:w.version,platform:w.platform,colorScheme:w.colorScheme,"
            + "initData:w.initData,user:(w.initDataUnsafe&&w.initDataUnsafe.user)||null,url:location.href},null,2);"
            + "}catch(e){return 'err: '+e;}})();";

    public static class Source {
        public String url;
        public boolean on;
    }

    // Только в памяти: в адресе и initData лежит авторизация аппки, на диск не пишем
    private static volatile String lastUrl;
    private static volatile String lastInfo;
    private static WeakReference<WebView> lastWebView;
    private static final HashSet<String> loading = new HashSet<>();

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE);
    }

    public static boolean get(String key) {
        return prefs().getBoolean(key, false);
    }

    public static void set(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
        if (PREF_REMOTE_DEBUG.equals(key)) {
            try {
                WebView.setWebContentsDebuggingEnabled(value || SharedConfig.debugWebView);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    public static boolean isRemoteDebug() {
        return get(PREF_REMOTE_DEBUG);
    }

    public static ArrayList<Source> getSources() {
        ArrayList<Source> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs().getString(PREF_SOURCES, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Source s = new Source();
                s.url = o.getString("url");
                s.on = o.optBoolean("on", true);
                list.add(s);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return list;
    }

    public static void saveSources(ArrayList<Source> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Source s : list) {
                JSONObject o = new JSONObject();
                o.put("url", s.url);
                o.put("on", s.on);
                arr.put(o);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        prefs().edit().putString(PREF_SOURCES, arr.toString()).apply();
    }

    public static void addSource(String url) {
        ArrayList<Source> list = getSources();
        Source s = new Source();
        s.url = url;
        s.on = true;
        list.add(s);
        saveSources(list);
        fetch(url, null);
    }

    public static String getLastUrl() {
        return lastUrl;
    }

    public static String getLastInfo() {
        return lastInfo;
    }

    private static File cacheFile(String url) {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "sgram_miniapp");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, Utilities.MD5(url) + ".js");
    }

    private static String readCached(String url) {
        File f = cacheFile(url);
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    // Качаем скрипт в фоне и кешируем; когда готов — внедряем в открытую аппку
    private static void fetch(String url, Runnable onReady) {
        synchronized (loading) {
            if (!loading.add(url)) return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(cacheFile(url))) {
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (onReady != null) AndroidUtilities.runOnUIThread(onReady);
            } catch (Exception e) {
                FileLog.e(e);
                cacheFile(url).delete();
            } finally {
                synchronized (loading) {
                    loading.remove(url);
                }
            }
        });
    }

    public static void clearScriptCache() {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "sgram_miniapp");
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    // Вызывается из BotWebViewContainer.MyWebView после загрузки страницы мини-аппа
    public static void onPageFinished(WebView webView, String url) {
        lastUrl = url;
        lastWebView = new WeakReference<>(webView);
        try {
            webView.evaluateJavascript(JS_INFO, value -> {
                try {
                    lastInfo = value != null && value.startsWith("\"") ? new JSONArray("[" + value + "]").getString(0) : value;
                } catch (Exception e) {
                    lastInfo = value;
                }
            });
            if (get(PREF_UNLOCK)) webView.evaluateJavascript(JS_UNLOCK, null);
            if (get(PREF_ZOOM)) webView.evaluateJavascript(JS_ZOOM, null);
            if (get(PREF_ERUDA)) inject(webView, ERUDA_URL, "eruda.init();");
            for (Source s : getSources()) {
                if (s.on) inject(webView, s.url, "");
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void inject(WebView webView, String url, String init) {
        String js = readCached(url);
        if (js == null) {
            fetch(url, () -> {
                WebView wv = lastWebView != null ? lastWebView.get() : null;
                if (wv == webView) inject(webView, url, init);
            });
            return;
        }
        // Один и тот же скрипт не внедряем дважды на одной странице
        String guard = "__sgram_" + Utilities.MD5(url).substring(0, 8);
        String code = "(function(){if(window." + guard + ")return;window." + guard + "=1;try{" + js + "\n;" + init + "}catch(e){}})();";
        try {
            webView.evaluateJavascript(code, null);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // CSP страницы (font-src/connect-src и т.п.) ломает eruda и свои скрипты: иконки-квадраты, пустые вкладки
    public static boolean shouldBypassCsp() {
        if (get(PREF_ERUDA)) return true;
        for (Source s : getSources()) {
            if (s.on) return true;
        }
        return false;
    }

    /**
     * Сами грузим главную страницу мини-аппа и отдаём её WebView без заголовков Content-Security-Policy.
     * null — пусть WebView грузит сам (редиректы, ошибки сети).
     */
    public static WebResourceResponse fetchWithoutCsp(WebResourceRequest request) {
        HttpURLConnection conn = null;
        try {
            String url = request.getUrl().toString();
            if (!url.startsWith("https://") && !url.startsWith("http://")) return null;
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("GET");
            boolean hasCookie = false;
            if (request.getRequestHeaders() != null) {
                for (Map.Entry<String, String> e : request.getRequestHeaders().entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                    if ("cookie".equalsIgnoreCase(e.getKey())) hasCookie = true;
                }
            }
            String cookie = CookieManager.getInstance().getCookie(url);
            if (!hasCookie && !TextUtils.isEmpty(cookie)) conn.setRequestProperty("Cookie", cookie);
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                conn.disconnect();
                return null;
            }
            HashMap<String, String> headers = new HashMap<>();
            for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
                String key = e.getKey();
                if (key == null) continue;
                String lower = key.toLowerCase();
                if (lower.equals("set-cookie")) {
                    for (String v : e.getValue()) CookieManager.getInstance().setCookie(url, v);
                    continue;
                }
                if (lower.startsWith("content-security-policy")) continue;
                headers.put(key, TextUtils.join(", ", e.getValue()));
            }
            String contentType = conn.getContentType();
            String encoding = null;
            if (contentType == null) contentType = "text/html";
            if (contentType.contains(";")) {
                String[] parts = contentType.split(";");
                for (int i = 1; i < parts.length; i++) {
                    String p = parts[i].trim();
                    if (p.toLowerCase().startsWith("charset=")) encoding = p.substring(8);
                }
                contentType = parts[0].trim();
            }
            if (encoding == null && contentType.startsWith("text/")) encoding = "utf-8";
            String reason = conn.getResponseMessage();
            InputStream body = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            return new WebResourceResponse(contentType, encoding, code, TextUtils.isEmpty(reason) ? "OK" : reason, headers, body);
        } catch (Exception e) {
            FileLog.e(e);
            if (conn != null) conn.disconnect();
            return null;
        }
    }

    public static void clearWebData() {
        try {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            WebStorage.getInstance().deleteAllData();
            WebView wv = lastWebView != null ? lastWebView.get() : null;
            if (wv != null) wv.clearCache(true);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
