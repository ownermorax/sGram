package org.telegram.messenger;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.LaunchActivity;
import org.telegram.utils.proxy.ProxySettings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Locale;


/**
 * Xray-клиент как в Happ: подписки, серверы по ссылкам (vless/vmess/trojan/ss/hysteria2), пинг.
 * Режимы: только sGram (ядро с локальным SOCKS, сам sGram ходит через него) или весь телефон
 * (SgramXrayService поднимает VPN и отдаёт tun ядру). sGram из VPN исключён всегда — иначе ядро
 * завернуло бы в туннель само себя, поэтому сам sGram и в этом режиме ходит через локальный SOCKS.
 */
public class SgramXray {

    public static final String PREF_ENABLED = "xray_enabled";
    public static final String PREF_ONLY_TG = "xray_only_tg";
    public static final String PREF_UA = "xray_user_agent";
    public static final String DEFAULT_UA = "Happ/3.10.2";
    public static final String TEST_URL = "https://www.gstatic.com/generate_204";
    private static final int SOCKS_PORT = 10818;

    public static class Subscription {
        public String id;
        public String name;
        public String url;
        public long updated;
        // из заголовка subscription-userinfo
        public long upload, download, total, expire;
    }

    public static class Server {
        public String id;
        public String subId;
        public String name;
        public String link;
        // полный JSON-конфиг (некоторые подписки отдают Xray JSON вместо ссылок)
        public String json;
        public int ping = -2; // -2 не проверяли, -1 ошибка
    }

    private static volatile SgramXray instance;
    public final ArrayList<Subscription> subscriptions = new ArrayList<>();
    public final ArrayList<Server> servers = new ArrayList<>();
    public String selectedId;
    private boolean running;
    private final String socksUser, socksPass;
    private final DispatchQueue queue = new DispatchQueue("sgramXray");

    public static SgramXray getInstance() {
        SgramXray local = instance;
        if (local == null) {
            synchronized (SgramXray.class) {
                local = instance;
                if (local == null) instance = local = new SgramXray();
            }
        }
        return local;
    }

    private SgramXray() {
        SharedPreferences p = prefs();
        String user = p.getString("xray_socks_user", null);
        String pass = p.getString("xray_socks_pass", null);
        if (user == null || pass == null) {
            user = randomToken(8);
            pass = randomToken(16);
            p.edit().putString("xray_socks_user", user).putString("xray_socks_pass", pass).apply();
        }
        socksUser = user;
        socksPass = pass;
        // старый UA по умолчанию — панели отвечали «App not supported»
        if ("v2rayNG/1.10.23".equals(p.getString(PREF_UA, null))) p.edit().remove(PREF_UA).apply();
        load();
    }

    private static String randomToken(int len) {
        String abc = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(abc.charAt(r.nextInt(abc.length())));
        return sb.toString();
    }

    static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE);
    }

    public static void prefs_putBoolean(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
    }

    public static void prefs_putString(String key, String value) {
        prefs().edit().putString(key, value).apply();
    }

    public static String prefs_getString(String key, String def) {
        return prefs().getString(key, def);
    }

    public static boolean isEnabled() {
        return prefs().getBoolean(PREF_ENABLED, false);
    }

    public static boolean isOnlyTg() {
        return prefs().getBoolean(PREF_ONLY_TG, true);
    }

    public synchronized boolean isRunning() {
        return running;
    }

    // --- хранение ---

    private static File storeFile() {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), "sgram_xray.json");
    }

    private synchronized void load() {
        subscriptions.clear();
        servers.clear();
        try {
            File f = storeFile();
            if (!f.exists()) return;
            byte[] data = readAll(new java.io.FileInputStream(f));
            JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
            selectedId = root.optString("selected", null);
            JSONArray subs = root.optJSONArray("subs");
            if (subs != null) for (int i = 0; i < subs.length(); i++) {
                JSONObject o = subs.getJSONObject(i);
                Subscription s = new Subscription();
                s.id = o.getString("id");
                s.name = o.optString("name");
                s.url = o.getString("url");
                s.updated = o.optLong("updated");
                s.upload = o.optLong("up");
                s.download = o.optLong("down");
                s.total = o.optLong("total");
                s.expire = o.optLong("expire");
                subscriptions.add(s);
            }
            JSONArray list = root.optJSONArray("servers");
            if (list != null) for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                Server s = new Server();
                s.id = o.getString("id");
                s.subId = o.optString("sub", null);
                s.name = o.optString("name");
                s.link = o.optString("link", null);
                s.json = o.optString("json", null);
                s.ping = o.optInt("ping", -2);
                servers.add(s);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public synchronized void save() {
        try {
            JSONObject root = new JSONObject();
            root.put("selected", selectedId);
            JSONArray subs = new JSONArray();
            for (Subscription s : subscriptions) {
                JSONObject o = new JSONObject();
                o.put("id", s.id).put("name", s.name).put("url", s.url).put("updated", s.updated)
                        .put("up", s.upload).put("down", s.download).put("total", s.total).put("expire", s.expire);
                subs.put(o);
            }
            root.put("subs", subs);
            JSONArray list = new JSONArray();
            for (Server s : servers) {
                JSONObject o = new JSONObject();
                o.put("id", s.id).put("sub", s.subId).put("name", s.name).put("ping", s.ping);
                if (s.link != null) o.put("link", s.link);
                if (s.json != null) o.put("json", s.json);
                list.put(o);
            }
            root.put("servers", list);
            File tmp = new File(storeFile().getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            tmp.renameTo(storeFile());
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public synchronized Server getSelected() {
        for (Server s : servers) if (s.id.equals(selectedId)) return s;
        return null;
    }

    public synchronized Subscription getSubscription(String id) {
        for (Subscription s : subscriptions) if (s.id.equals(id)) return s;
        return null;
    }

    // --- добавление ---

    /** Текст из буфера/поля: ссылка подписки (http/https), одна или несколько ссылок серверов, или base64. */
    public String addFromText(String text, Utilities.Callback<String> done) {
        text = text == null ? "" : text.trim();
        if (text.startsWith("http://") || text.startsWith("https://")) {
            addSubscription(text, null, done);
            return null;
        }
        int added;
        synchronized (this) {
            added = parseServers(text, null, servers);
            if (added > 0 && getSelected() == null) selectedId = servers.get(servers.size() - added).id;
        }
        save();
        String result = added > 0 ? null : "No supported links found";
        if (done != null) done.run(result);
        return result;
    }

    public void addSubscription(String url, String name, Utilities.Callback<String> done) {
        Subscription s = new Subscription();
        s.id = "s" + System.currentTimeMillis();
        s.url = url;
        s.name = name;
        synchronized (this) {
            subscriptions.add(s);
        }
        updateSubscription(s, done);
    }

    public void updateSubscription(Subscription s, Utilities.Callback<String> done) {
        queue.postRunnable(() -> {
            String error = null;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(s.url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", prefs().getString(PREF_UA, DEFAULT_UA));
                conn.setRequestProperty("Accept", "*/*");
                // Заголовки, как у Happ: панели (Remnawave/Marzban) по ним узнают клиент и считают устройства
                conn.setRequestProperty("X-HWID", hwid());
                conn.setRequestProperty("X-Device-OS", "Android");
                conn.setRequestProperty("X-Ver-OS", Build.VERSION.RELEASE);
                conn.setRequestProperty("X-Device-Model", Build.MODEL);
                int code = conn.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                String body = new String(readAll(conn.getInputStream()), StandardCharsets.UTF_8);
                String title = conn.getHeaderField("profile-title");
                String info = conn.getHeaderField("subscription-userinfo");
                ArrayList<Server> fresh = new ArrayList<>();
                int n = parseServers(body, s.id, fresh);
                if (n == 0) throw new Exception("Subscription has no supported servers");
                synchronized (this) {
                    if (TextUtils.isEmpty(s.name)) s.name = decodeTitle(title);
                    if (TextUtils.isEmpty(s.name)) s.name = Uri.parse(s.url).getHost();
                    parseUserInfo(s, info);
                    s.updated = System.currentTimeMillis();
                    // выбранный сервер остаётся выбранным, если в обновлённой подписке есть сервер с тем же именем
                    Server selected = getSelected();
                    String selectedName = selected != null && s.id.equals(selected.subId) ? selected.name : null;
                    servers.removeIf(x -> s.id.equals(x.subId));
                    servers.addAll(fresh);
                    if (selectedName != null) {
                        for (Server x : fresh) if (selectedName.equals(x.name)) { selectedId = x.id; break; }
                    }
                    if (getSelected() == null && !fresh.isEmpty()) selectedId = fresh.get(0).id;
                }
                save();
            } catch (Exception e) {
                FileLog.e(e);
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            } finally {
                if (conn != null) conn.disconnect();
            }
            final String err = error;
            if (done != null) AndroidUtilities.runOnUIThread(() -> done.run(err));
        });
    }

    public void removeSubscription(Subscription s) {
        synchronized (this) {
            subscriptions.remove(s);
            servers.removeIf(x -> s.id.equals(x.subId));
        }
        save();
    }

    public void removeServer(Server s) {
        synchronized (this) {
            servers.remove(s);
        }
        save();
    }

    private static String hwid() {
        String id = prefs().getString("xray_hwid", null);
        if (id == null) {
            try {
                id = android.provider.Settings.Secure.getString(ApplicationLoader.applicationContext.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            } catch (Exception ignore) {
            }
            if (TextUtils.isEmpty(id)) id = randomToken(16).toLowerCase(Locale.ROOT);
            prefs().edit().putString("xray_hwid", id).apply();
        }
        return id;
    }

    private static String decodeTitle(String title) {
        if (title == null) return null;
        if (title.startsWith("base64:")) {
            try {
                return new String(Base64.decode(title.substring(7), Base64.DEFAULT), StandardCharsets.UTF_8).trim();
            } catch (Exception ignore) {
                return null;
            }
        }
        return title.trim();
    }

    private static void parseUserInfo(Subscription s, String info) {
        if (info == null) return;
        for (String part : info.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            long v;
            try {
                v = Long.parseLong(kv[1].trim());
            } catch (Exception e) {
                continue;
            }
            switch (kv[0].trim()) {
                case "upload": s.upload = v; break;
                case "download": s.download = v; break;
                case "total": s.total = v; break;
                case "expire": s.expire = v; break;
            }
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static String tryBase64(String s) {
        String clean = s.replaceAll("\\s", "");
        if (clean.isEmpty() || !clean.matches("[A-Za-z0-9+/=_-]+")) return null;
        try {
            return new String(Base64.decode(clean, Base64.URL_SAFE | Base64.NO_WRAP), StandardCharsets.UTF_8);
        } catch (Exception e) {
            try {
                return new String(Base64.decode(clean, Base64.DEFAULT), StandardCharsets.UTF_8);
            } catch (Exception ignore) {
                return null;
            }
        }
    }

    // Ссылки построчно, base64-список или Xray JSON (объект или массив конфигов)
    private int parseServers(String body, String subId, ArrayList<Server> out) {
        String text = body.trim();
        int count = 0;
        if (text.startsWith("[") || text.startsWith("{")) {
            try {
                JSONArray arr = text.startsWith("[") ? new JSONArray(text) : new JSONArray().put(new JSONObject(text));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject cfg = arr.optJSONObject(i);
                    if (cfg == null || cfg.optJSONArray("outbounds") == null) continue;
                    Server s = new Server();
                    s.id = newId(out.size() + count);
                    s.subId = subId;
                    s.name = cfg.optString("remarks", "Server " + (i + 1));
                    s.json = cfg.toString();
                    out.add(s);
                    count++;
                }
                if (count > 0) return count;
            } catch (Exception ignore) {
            }
        }
        if (!text.contains("://")) {
            String decoded = tryBase64(text);
            if (decoded != null) text = decoded;
        }
        for (String line : text.split("[\\r\\n]+")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                JSONObject outbound = linkToOutbound(line);
                if (outbound == null) continue;
                Server s = new Server();
                s.id = newId(out.size() + count);
                s.subId = subId;
                s.name = linkName(line);
                s.link = line;
                out.add(s);
                count++;
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return count;
    }

    private static String newId(int salt) {
        return "x" + System.currentTimeMillis() + "_" + salt;
    }

    private static String linkName(String link) {
        int hash = link.lastIndexOf('#');
        if (link.startsWith("vmess://")) {
            try {
                JSONObject o = new JSONObject(tryBase64(link.substring(8)));
                return o.optString("ps", "VMess");
            } catch (Exception ignore) {
                return "VMess";
            }
        }
        if (hash >= 0 && hash < link.length() - 1) return Uri.decode(link.substring(hash + 1));
        Uri u = Uri.parse(link);
        return u.getHost() != null ? u.getHost() : link;
    }

    // --- ссылка → outbound ---

    static JSONObject linkToOutbound(String link) throws Exception {
        String scheme = link.substring(0, Math.max(0, link.indexOf("://"))).toLowerCase(Locale.ROOT);
        switch (scheme) {
            case "vless": return vless(link);
            case "vmess": return vmess(link);
            case "trojan": return trojan(link);
            case "ss": return shadowsocks(link);
            case "hysteria2":
            case "hy2": return hysteria2(link);
            default: return null;
        }
    }

    private static String q(Uri u, String key) {
        String v = u.getQueryParameter(key);
        return TextUtils.isEmpty(v) ? null : v;
    }

    private static JSONObject vless(String link) throws Exception {
        Uri u = Uri.parse(link);
        JSONObject settings = new JSONObject()
                .put("address", u.getHost())
                .put("port", u.getPort())
                .put("id", Uri.decode(u.getUserInfo()))
                .put("encryption", q(u, "encryption") != null ? q(u, "encryption") : "none")
                .put("level", 8);
        if (q(u, "flow") != null) settings.put("flow", q(u, "flow"));
        JSONObject out = new JSONObject().put("tag", "proxy").put("protocol", "vless").put("settings", settings);
        out.put("streamSettings", stream(u, q(u, "type"), q(u, "security")));
        return out;
    }

    private static JSONObject trojan(String link) throws Exception {
        Uri u = Uri.parse(link);
        JSONObject settings = new JSONObject()
                .put("address", u.getHost())
                .put("port", u.getPort())
                .put("password", Uri.decode(u.getUserInfo()))
                .put("level", 8);
        if (q(u, "flow") != null) settings.put("flow", q(u, "flow"));
        String security = q(u, "security") != null ? q(u, "security") : "tls";
        return new JSONObject().put("tag", "proxy").put("protocol", "trojan").put("settings", settings)
                .put("streamSettings", stream(u, q(u, "type"), security));
    }

    private static JSONObject vmess(String link) throws Exception {
        JSONObject v = new JSONObject(tryBase64(link.substring(8)));
        JSONObject settings = new JSONObject()
                .put("address", v.getString("add"))
                .put("port", Integer.parseInt(v.optString("port", "443")))
                .put("id", v.getString("id"))
                .put("security", v.optString("scy", "auto").isEmpty() ? "auto" : v.optString("scy", "auto"))
                .put("level", 8);
        // vmess хранит параметры транспорта в JSON — переводим их в query, как у vless
        Uri.Builder b = new Uri.Builder().scheme("vmess").encodedAuthority(v.getString("add"));
        putIf(b, "host", v.optString("host"));
        putIf(b, "path", v.optString("path"));
        putIf(b, "sni", v.optString("sni"));
        putIf(b, "alpn", v.optString("alpn"));
        putIf(b, "fp", v.optString("fp"));
        putIf(b, "headerType", v.optString("type"));
        String net = v.optString("net", "tcp");
        if ("grpc".equals(net)) putIf(b, "serviceName", v.optString("path"));
        String tls = v.optString("tls");
        return new JSONObject().put("tag", "proxy").put("protocol", "vmess").put("settings", settings)
                .put("streamSettings", stream(b.build(), net, TextUtils.isEmpty(tls) || "none".equals(tls) ? null : tls));
    }

    private static void putIf(Uri.Builder b, String key, String value) {
        if (!TextUtils.isEmpty(value)) b.appendQueryParameter(key, value);
    }

    private static JSONObject shadowsocks(String link) throws Exception {
        String body = link.substring(5);
        int hash = body.indexOf('#');
        if (hash >= 0) body = body.substring(0, hash);
        int q = body.indexOf('?');
        if (q >= 0) body = body.substring(0, q);
        String method, password, host;
        int port;
        int at = body.lastIndexOf('@');
        if (at < 0) {
            // старый формат: всё в base64
            String decoded = tryBase64(body);
            at = decoded.lastIndexOf('@');
            String creds = decoded.substring(0, at);
            String hp = decoded.substring(at + 1);
            method = creds.substring(0, creds.indexOf(':'));
            password = creds.substring(creds.indexOf(':') + 1);
            host = hp.substring(0, hp.lastIndexOf(':'));
            port = Integer.parseInt(hp.substring(hp.lastIndexOf(':') + 1).replaceAll("/.*", ""));
        } else {
            String creds = Uri.decode(body.substring(0, at));
            if (!creds.contains(":")) creds = tryBase64(creds);
            method = creds.substring(0, creds.indexOf(':'));
            password = creds.substring(creds.indexOf(':') + 1);
            String hp = body.substring(at + 1).replaceAll("/.*", "");
            host = hp.substring(0, hp.lastIndexOf(':')).replace("[", "").replace("]", "");
            port = Integer.parseInt(hp.substring(hp.lastIndexOf(':') + 1));
        }
        JSONObject settings = new JSONObject()
                .put("address", host).put("port", port).put("method", method).put("password", password).put("level", 8);
        return new JSONObject().put("tag", "proxy").put("protocol", "shadowsocks").put("settings", settings)
                .put("streamSettings", new JSONObject().put("network", "tcp"));
    }

    private static JSONObject hysteria2(String link) throws Exception {
        Uri u = Uri.parse(link);
        JSONObject settings = new JSONObject().put("address", u.getHost()).put("port", u.getPort() > 0 ? u.getPort() : 443).put("version", 2);
        JSONObject stream = new JSONObject().put("network", "hysteria")
                .put("hysteriaSettings", new JSONObject().put("version", 2).put("auth", Uri.decode(u.getUserInfo() != null ? u.getUserInfo() : "")));
        JSONObject tls = new JSONObject().put("serverName", q(u, "sni") != null ? q(u, "sni") : u.getHost())
                .put("alpn", new JSONArray().put("h3"));
        if (q(u, "pinSHA256") != null) tls.put("pinnedPeerCertSha256", q(u, "pinSHA256").replace(":", "").toLowerCase(Locale.ROOT));
        stream.put("security", "tls").put("tlsSettings", tls);
        if ("salamander".equals(q(u, "obfs")) && q(u, "obfs-password") != null) {
            stream.put("finalmask", new JSONObject().put("udp", new JSONArray().put(new JSONObject().put("type", "salamander")
                    .put("settings", new JSONObject().put("password", q(u, "obfs-password"))))));
        }
        return new JSONObject().put("tag", "proxy").put("protocol", "hysteria").put("settings", settings).put("streamSettings", stream);
    }

    // Транспорт и TLS/REALITY по параметрам ссылки (имена — как в v2rayN/Xray share links)
    private static JSONObject stream(Uri u, String network, String security) throws Exception {
        if (TextUtils.isEmpty(network)) network = "tcp";
        if ("http".equals(network) || "h2".equals(network)) network = "xhttp";
        JSONObject s = new JSONObject().put("network", network);
        String host = q(u, "host");
        String path = q(u, "path");
        String sniFromTransport = host;
        switch (network) {
            case "tcp":
            case "raw":
                if ("http".equals(q(u, "headerType"))) {
                    JSONObject request = new JSONObject().put("path", new JSONArray().put(path != null ? path : "/"));
                    if (host != null) request.put("headers", new JSONObject().put("Host", new JSONArray().put(host)));
                    s.put("tcpSettings", new JSONObject().put("header", new JSONObject().put("type", "http").put("request", request)));
                }
                break;
            case "ws":
                s.put("wsSettings", new JSONObject().put("path", path != null ? path : "/").put("host", host != null ? host : ""));
                break;
            case "httpupgrade":
                s.put("httpupgradeSettings", new JSONObject().put("path", path != null ? path : "/").put("host", host != null ? host : ""));
                break;
            case "xhttp":
            case "splithttp": {
                s.put("network", "xhttp");
                JSONObject x = new JSONObject().put("path", path != null ? path : "/").put("host", host != null ? host : "");
                if (q(u, "mode") != null) x.put("mode", q(u, "mode"));
                if (q(u, "extra") != null) {
                    try {
                        x.put("extra", new JSONObject(q(u, "extra")));
                    } catch (Exception ignore) {
                    }
                }
                s.put("xhttpSettings", x);
                break;
            }
            case "grpc": {
                JSONObject g = new JSONObject().put("serviceName", q(u, "serviceName") != null ? q(u, "serviceName") : "")
                        .put("multiMode", "multi".equals(q(u, "mode")));
                if (q(u, "authority") != null) g.put("authority", q(u, "authority"));
                sniFromTransport = q(u, "authority");
                s.put("grpcSettings", g);
                break;
            }
            case "kcp":
                s.put("kcpSettings", new JSONObject());
                break;
        }
        if (!TextUtils.isEmpty(security) && !"none".equals(security)) {
            String sni = q(u, "sni");
            if (sni == null) sni = q(u, "peer");
            if (sni == null) sni = sniFromTransport;
            if (sni == null && u.getHost() != null && !u.getHost().matches("[0-9.:\\[\\]]+")) sni = u.getHost();
            JSONObject t = new JSONObject();
            if (sni != null) t.put("serverName", sni);
            if (q(u, "fp") != null) t.put("fingerprint", q(u, "fp"));
            if (q(u, "alpn") != null) {
                JSONArray alpn = new JSONArray();
                for (String a : q(u, "alpn").split(",")) if (!a.trim().isEmpty()) alpn.put(a.trim());
                t.put("alpn", alpn);
            }
            if ("reality".equals(security)) {
                if (q(u, "pbk") != null) t.put("publicKey", q(u, "pbk"));
                if (q(u, "sid") != null) t.put("shortId", q(u, "sid"));
                if (q(u, "spx") != null) t.put("spiderX", q(u, "spx"));
                if (q(u, "pqv") != null) t.put("mldsa65Verify", q(u, "pqv"));
                if (!t.has("fingerprint")) t.put("fingerprint", "chrome");
                s.put("security", "reality").put("realitySettings", t);
            } else {
                // allowInsecure в Xray 26 убран — вместо него пин сертификата или проверка по имени
                if (q(u, "pcs") != null) t.put("pinnedPeerCertSha256", q(u, "pcs"));
                if (q(u, "vcn") != null) t.put("verifyPeerCertByName", q(u, "vcn"));
                if (q(u, "ech") != null) t.put("echConfigList", q(u, "ech"));
                s.put("security", "tls").put("tlsSettings", t);
            }
        }
        return s;
    }

    // --- конфиг ---

    private JSONObject proxyOutbound(Server server) throws Exception {
        if (server.json != null) {
            JSONArray outs = new JSONObject(server.json).getJSONArray("outbounds");
            for (int i = 0; i < outs.length(); i++) {
                JSONObject o = outs.getJSONObject(i);
                String p = o.optString("protocol");
                if (!"freedom".equals(p) && !"blackhole".equals(p) && !"dns".equals(p)) {
                    JSONObject copy = new JSONObject(o.toString());
                    copy.put("tag", "proxy");
                    JSONObject ss = copy.optJSONObject("streamSettings");
                    if (ss != null) {
                        if (ss.optJSONObject("tlsSettings") != null) ss.getJSONObject("tlsSettings").remove("allowInsecure");
                        if (ss.optJSONObject("realitySettings") != null) ss.getJSONObject("realitySettings").remove("allowInsecure");
                    }
                    return copy;
                }
            }
            throw new Exception("No proxy outbound in config");
        }
        JSONObject out = linkToOutbound(server.link);
        if (out == null) throw new Exception("Unsupported link");
        return out;
    }

    /** tun=true — с tun-входом для режима «весь телефон». */
    String buildConfig(Server server, boolean tun) throws Exception {
        JSONObject sniffing = new JSONObject().put("enabled", true)
                .put("destOverride", new JSONArray().put("http").put("tls").put("quic"));
        JSONArray inbounds = new JSONArray();
        inbounds.put(new JSONObject().put("tag", "socks").put("listen", "127.0.0.1").put("port", SOCKS_PORT).put("protocol", "socks")
                .put("settings", new JSONObject().put("auth", "password").put("udp", true).put("userLevel", 8)
                        .put("accounts", new JSONArray().put(new JSONObject().put("user", socksUser).put("pass", socksPass))))
                .put("sniffing", sniffing));
        if (tun) {
            inbounds.put(new JSONObject().put("tag", "tun").put("protocol", "tun")
                    .put("settings", new JSONObject().put("name", "xray0").put("MTU", 1500).put("userLevel", 8))
                    .put("sniffing", sniffing));
        }
        JSONArray outbounds = new JSONArray()
                .put(proxyOutbound(server))
                .put(new JSONObject().put("tag", "direct").put("protocol", "freedom"))
                .put(new JSONObject().put("tag", "block").put("protocol", "blackhole"))
                .put(new JSONObject().put("tag", "dns-out").put("protocol", "dns"));
        JSONArray rules = new JSONArray()
                .put(new JSONObject().put("type", "field").put("inboundTag", new JSONArray().put("tun")).put("port", "53").put("outboundTag", "dns-out"))
                .put(new JSONObject().put("type", "field").put("ip", new JSONArray()
                        .put("10.0.0.0/8").put("172.16.0.0/12").put("192.168.0.0/16").put("127.0.0.0/8").put("169.254.0.0/16").put("fc00::/7").put("fe80::/10"))
                        .put("outboundTag", "direct"));
        JSONObject cfg = new JSONObject()
                .put("log", new JSONObject().put("loglevel", "warning"))
                .put("dns", new JSONObject().put("servers", new JSONArray().put("1.1.1.1").put("8.8.8.8")))
                .put("inbounds", inbounds)
                .put("outbounds", outbounds)
                .put("routing", new JSONObject().put("domainStrategy", "AsIs").put("rules", rules));
        return cfg.toString();
    }

    /** Конфиг только с outbound — для замера задержки. */
    String pingConfig(Server server) throws Exception {
        return new JSONObject()
                .put("log", new JSONObject().put("loglevel", "none"))
                .put("outbounds", new JSONArray().put(proxyOutbound(server))).toString();
    }

    // --- пинг (в процессе :xray) ---

    private final java.util.HashMap<String, Server> pingWaiting = new java.util.HashMap<>();
    private Runnable pingOnEach, pingOnDone;

    public void measureAll(ArrayList<Server> list, Runnable onEach, Runnable onDone) {
        ensureReceiver();
        ArrayList<String> ids = new ArrayList<>(), configs = new ArrayList<>();
        synchronized (pingWaiting) {
            for (Server s : list) {
                try {
                    configs.add(pingConfig(s));
                    ids.add(s.id);
                    s.ping = -3;
                    pingWaiting.put(s.id, s);
                } catch (Exception e) {
                    s.ping = -1;
                }
            }
            pingOnEach = onEach;
            pingOnDone = onDone;
        }
        if (ids.isEmpty()) {
            onDone.run();
            return;
        }
        Intent intent = new Intent(ApplicationLoader.applicationContext, SgramXrayService.class);
        intent.setAction(SgramXrayService.ACTION_PING);
        intent.putStringArrayListExtra("ids", ids);
        intent.putStringArrayListExtra("configs", configs);
        try {
            ApplicationLoader.applicationContext.startService(intent);
        } catch (Exception e) {
            FileLog.e(e);
            onDone.run();
        }
    }

    // --- запуск: ядро живёт в отдельном процессе :xray ---
    // (в основном процессе уже есть Go-рантайм WireGuard, второй Go-рантайм рядом падает с SIGSEGV)

    private boolean receiverRegistered;
    private String runningError;

    private synchronized void ensureReceiver() {
        if (receiverRegistered) return;
        receiverRegistered = true;
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(SgramXrayService.BROADCAST_STATE);
        filter.addAction(SgramXrayService.BROADCAST_PING);
        androidx.core.content.ContextCompat.registerReceiver(ApplicationLoader.applicationContext, new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (SgramXrayService.BROADCAST_STATE.equals(intent.getAction())) {
                    onState(intent.getBooleanExtra("running", false), intent.getStringExtra("error"));
                } else {
                    onPing(intent.getStringExtra("id"), intent.getIntExtra("ms", -1), intent.getBooleanExtra("done", false));
                }
            }
        }, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void onState(boolean nowRunning, String error) {
        boolean was;
        synchronized (this) {
            was = running;
            running = nowRunning;
        }
        if (nowRunning != was) applyTelegramProxy(nowRunning);
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.sgramXrayStateChanged, error != null ? "Xray: " + error : null));
    }

    private void onPing(String id, int ms, boolean done) {
        Runnable each, fin = null;
        synchronized (pingWaiting) {
            Server s = id != null ? pingWaiting.remove(id) : null;
            if (s != null) s.ping = ms > 0 ? ms : -1;
            each = pingOnEach;
            if (done) {
                for (Server left : pingWaiting.values()) left.ping = -1;
                pingWaiting.clear();
                fin = pingOnDone;
                pingOnEach = pingOnDone = null;
            }
        }
        if (each != null) AndroidUtilities.runOnUIThread(each);
        if (fin != null) {
            save();
            AndroidUtilities.runOnUIThread(fin);
        }
    }

    /** Включить по кнопке. Для «весь телефон» может понадобиться разрешение на VPN. */
    public void start() {
        prefs().edit().putBoolean(PREF_ENABLED, true).apply();
        if (!isOnlyTg()) {
            Intent prepare = VpnService.prepare(ApplicationLoader.applicationContext);
            if (prepare != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (LaunchActivity.instance != null) {
                        try {
                            LaunchActivity.instance.startActivityForResult(prepare, 51821);
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    }
                });
                return;
            }
        }
        startService();
    }

    /** После выдачи разрешения на VPN (LaunchActivity, requestCode 51821). */
    public void onVpnPermissionGranted() {
        if (isEnabled() && !isOnlyTg()) startService();
    }

    /** Автозапуск при открытии приложения. */
    public void ensureStarted() {
        if (!isEnabled()) return;
        if (!isOnlyTg() && VpnService.prepare(ApplicationLoader.applicationContext) != null) return;
        startService();
    }

    private void startService() {
        ensureReceiver();
        Server server = getSelected();
        if (server == null) {
            onState(false, "Select a server first");
            return;
        }
        String config;
        try {
            config = buildConfig(server, !isOnlyTg());
        } catch (Exception e) {
            onState(false, e.getMessage());
            return;
        }
        Context context = ApplicationLoader.applicationContext;
        Intent intent = new Intent(context, SgramXrayService.class);
        intent.setAction(SgramXrayService.ACTION_START);
        intent.putExtra("config", config);
        intent.putExtra("tun", !isOnlyTg());
        intent.putExtra("name", server.name);
        intent.putStringArrayListExtra("excluded", new ArrayList<>(prefs().getStringSet("vpn_excluded_apps", new java.util.HashSet<>())));
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Exception e) {
            FileLog.e(e);
            onState(false, e.getMessage());
        }
    }

    public void stop() {
        prefs().edit().putBoolean(PREF_ENABLED, false).apply();
        ensureReceiver();
        Context context = ApplicationLoader.applicationContext;
        Intent intent = new Intent(context, SgramXrayService.class);
        intent.setAction(SgramXrayService.ACTION_STOP);
        try {
            context.startService(intent);
        } catch (Exception e) {
            context.stopService(new Intent(context, SgramXrayService.class));
        }
        onState(false, null);
    }

    /** Перезапуск с текущим сервером/режимом — сервис сам остановит старое ядро. */
    public void restart() {
        if (isEnabled()) start();
    }

    // Сам sGram ходит через локальный SOCKS ядра; при выключении — обратно к своим настройкам прокси
    private void applyTelegramProxy(boolean on) {
        AndroidUtilities.runOnUIThread(() -> {
            if (on) {
                ConnectionsManager.setProxySettings(true, ProxySettings.builder().setType(ProxySettings.Type.SOCKS5).setAddress("127.0.0.1").setPort(SOCKS_PORT).setUser(socksUser).setPassword(socksPass).build());
            } else {
                boolean enabled = MessagesController.getGlobalMainSettings().getBoolean("proxy_enabled", false);
                ConnectionsManager.setProxySettings(enabled && SharedConfig.currentProxy != null, SharedConfig.currentProxy != null ? SharedConfig.currentProxy.settings : null);
            }
        });
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        return String.format(Locale.ENGLISH, "%.1f %sB", bytes / Math.pow(1024, exp), "KMGTPE".charAt(exp - 1));
    }
}
