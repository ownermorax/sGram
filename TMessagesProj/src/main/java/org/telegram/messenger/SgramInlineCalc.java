package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * sGram: inline-калькулятор в строке ввода («2*3 =», «1$ =», «0.1 btc в eur =», «1.2 kg =», «10 звёзд =»).
 * Курсы: open.er-api.com (фиат, база USD) + CoinGecko (крипта в USD), кэш в prefs на 10 минут.
 */
public class SgramInlineCalc {

    public static final String PREF = "inline_calc";
    private static final long TTL = 10 * 60 * 1000L;
    private static final double STAR_USD = 0.013;

    private static final String[][] CRYPTO = {
            {"BTC", "bitcoin"}, {"ETH", "ethereum"}, {"TON", "the-open-network"}, {"USDT", "tether"},
            {"SOL", "solana"}, {"BNB", "binancecoin"}, {"XRP", "ripple"}, {"DOGE", "dogecoin"},
            {"TRX", "tron"}, {"LTC", "litecoin"}, {"NOT", "notcoin"}, {"USDC", "usd-coin"}};

    private static final HashMap<String, String> ALIAS = new HashMap<>();
    private static final HashMap<String, double[]> UNITS = new HashMap<>(); // unit -> {factor to base, group}
    private static final HashMap<String, String> UNIT_DEFAULT = new HashMap<>();
    private static final HashMap<String, String> SYM = new HashMap<>();

    static {
        String[][] a = {
                {"$", "USD"}, {"usd", "USD"}, {"долл", "USD"}, {"бакс", "USD"},
                {"€", "EUR"}, {"eur", "EUR"}, {"евро", "EUR"},
                {"₽", "RUB"}, {"rub", "RUB"}, {"руб", "RUB"}, {"р", "RUB"}, {"р.", "RUB"},
                {"£", "GBP"}, {"фунт", "GBP"}, {"¥", "CNY"}, {"юан", "CNY"}, {"йен", "JPY"}, {"иен", "JPY"},
                {"₴", "UAH"}, {"грн", "UAH"}, {"гривн", "UAH"}, {"₸", "KZT"}, {"тенге", "KZT"},
                {"лир", "TRY"}, {"₺", "TRY"}, {"бел", "BYN"}, {"сум", "UZS"}, {"драм", "AMD"}, {"лари", "GEL"},
                {"⭐", "STARS"}, {"⭐️", "STARS"}, {"star", "STARS"}, {"stars", "STARS"}, {"звезд", "STARS"}, {"звёзд", "STARS"},
                {"биткоин", "BTC"}, {"биток", "BTC"}, {"эфир", "ETH"}, {"тон", "TON"}, {"тонкоин", "TON"},
        };
        for (String[] p : a) ALIAS.put(p[0], p[1]);
        unit(0, 1, "kg", "кг"); unit(0, 0.001, "g", "г"); unit(0, 0.45359237, "lb", "lbs", "фунт.");
        unit(0, 0.028349523125, "oz", "унц"); unit(0, 1000, "t", "т");
        unit(1, 1, "m", "м"); unit(1, 1000, "km", "км"); unit(1, 0.01, "cm", "см"); unit(1, 0.001, "mm", "мм");
        unit(1, 1609.344, "mi", "mile", "miles", "миль", "мил"); unit(1, 0.3048, "ft", "фут"); unit(1, 0.0254, "in", "inch", "дюйм");
        unit(1, 0.9144, "yd", "ярд");
        unit(2, 1, "l", "л"); unit(2, 0.001, "ml", "мл"); unit(2, 3.785411784, "gal", "галлон");
        unit(3, 1, "km/h", "кмч", "км/ч"); unit(3, 1.609344, "mph");
        unit(4, 0, "°c", "c", "℃", "цельс"); unit(4, 1, "°f", "f", "℉", "фаренг"); unit(4, 2, "k", "кельв");
        String[][] d = {{"kg", "lb"}, {"lb", "kg"}, {"g", "oz"}, {"oz", "g"}, {"t", "lb"}, {"km", "mi"}, {"mi", "km"},
                {"m", "ft"}, {"ft", "m"}, {"cm", "in"}, {"in", "cm"}, {"mm", "in"}, {"yd", "m"}, {"l", "gal"}, {"gal", "l"},
                {"ml", "oz"}, {"km/h", "mph"}, {"mph", "km/h"}, {"°c", "°f"}, {"°f", "°c"}, {"k", "°c"}};
        for (String[] p : d) UNIT_DEFAULT.put(p[0], p[1]);
        String[][] s = {{"USD", "$"}, {"EUR", "€"}, {"RUB", "₽"}, {"GBP", "£"}, {"CNY", "¥"}, {"JPY", "¥"}, {"UAH", "₴"},
                {"KZT", "₸"}, {"TRY", "₺"}, {"STARS", "⭐"}};
        for (String[] p : s) SYM.put(p[0], p[1]);
    }

    private static void unit(int group, double f, String... names) {
        for (String n : names) UNITS.put(n, new double[]{f, group});
    }

    private static final HashMap<String, Double> usdRate = new HashMap<>(); // 1 unit = x USD
    private static long ratesTime;
    private static boolean loading;
    private static Runnable onRatesLoaded;

    public static boolean isEnabled() {
        return prefs().getBoolean(PREF, true);
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE);
    }

    /** Результат для текста строки ввода или null. callback вызывается на UI, когда подтянутся курсы. */
    public static String compute(CharSequence text, Runnable callback) {
        if (text == null || text.length() < 3 || !isEnabled()) return null;
        String s = text.toString();
        int nl = s.lastIndexOf('\n');
        if (nl >= 0) s = s.substring(nl + 1);
        s = s.trim();
        if (!s.endsWith("=") || s.length() > 200) return null;
        s = s.substring(0, s.length() - 1).trim().toLowerCase(Locale.ROOT).replaceAll("(?<=\\d)[   '](?=\\d)", "");
        if (s.isEmpty() || s.contains("=")) return null;
        try {
            String conv = convert(s, callback);
            if (conv != null) return conv.isEmpty() ? null : conv;
            Parser p = new Parser(s);
            double v = p.parse();
            if (!p.hadOp || Double.isNaN(v) || Double.isInfinite(v)) return null;
            return fmt(v, 10, false);
        } catch (Exception e) {
            return null;
        }
    }

    private static final Pattern CONV = Pattern.compile("^(.*?[\\d)a-zπ])\\s*([^\\s\\d()+*/^,.-][^\\s\\d()]*)(?:\\s*(?:to|in|в|во|->|→|=>)\\s*([^\\s\\d()]+))?$");

    /** null — это не конвертация; "" — конвертация, но результата пока нет. */
    private static String convert(String s, Runnable callback) {
        // «$100», «€5»
        Matcher pre = Pattern.compile("^([$€₽£¥₴₸₺⭐])\\s*([\\d.,]+)(.*)$").matcher(s);
        if (pre.matches()) s = pre.group(2) + pre.group(1) + pre.group(3);
        Matcher m = CONV.matcher(s);
        if (!m.matches()) return null;
        String from = m.group(2), to = m.group(3);
        String fromU = unitKey(from), toU = to == null ? null : unitKey(to);
        if (fromU != null && (to == null || toU != null)) {
            double v = new Parser(m.group(1)).parse();
            if (toU == null) toU = UNIT_DEFAULT.get(fromU);
            double[] a = UNITS.get(fromU), b = UNITS.get(toU);
            if (b == null || a[1] != b[1]) return null;
            double r;
            if (a[1] == 4) {
                double c = a[0] == 0 ? v : a[0] == 1 ? (v - 32) * 5 / 9 : v - 273.15;
                r = b[0] == 0 ? c : b[0] == 1 ? c * 9 / 5 + 32 : c + 273.15;
            } else {
                r = v * a[0] / b[0];
            }
            return fmt(r, 4, false) + " " + toU;
        }
        String fc = currency(from), tc = to == null ? null : currency(to);
        if (fc == null || (to != null && tc == null)) return null;
        if (tc == null) tc = fc.equals("RUB") ? "USD" : "RUB";
        double v = new Parser(m.group(1)).parse();
        ensureRates(callback);
        Double ra = rate(fc), rb = rate(tc);
        if (ra == null || rb == null) return "";
        double r = v * ra / rb;
        boolean crypto = isCrypto(tc);
        String sym = SYM.get(tc);
        String num = fmt(r, crypto ? 8 : 2, !crypto && !tc.equals("STARS"));
        if (tc.equals("STARS")) return num + " ⭐";
        return sym != null ? num + " " + sym : num + " " + tc;
    }

    private static String unitKey(String u) {
        if (UNITS.containsKey(u)) return u;
        return null;
    }

    private static String currency(String u) {
        String a = ALIAS.get(u);
        if (a != null) return a;
        for (String k : ALIAS.keySet()) {
            if (k.length() >= 3 && Character.isLetter(k.charAt(0)) && u.startsWith(k)) return ALIAS.get(k);
        }
        String up = u.toUpperCase(Locale.ROOT);
        for (String[] c : CRYPTO) if (c[0].equals(up)) return up;
        if (up.matches("[A-Z]{3}") && (usdRate.isEmpty() || usdRate.containsKey(up))) return up;
        return null;
    }

    private static boolean isCrypto(String c) {
        for (String[] x : CRYPTO) if (x[0].equals(c) && !c.startsWith("USD")) return true;
        return false;
    }

    private static Double rate(String c) {
        if (c.equals("USD")) return 1.0;
        if (c.equals("STARS")) return STAR_USD;
        return usdRate.get(c);
    }

    private static synchronized void ensureRates(Runnable callback) {
        if (usdRate.isEmpty()) {
            String saved = prefs().getString("inline_calc_rates", null);
            if (saved != null) {
                try {
                    JSONObject o = new JSONObject(saved);
                    ratesTime = o.optLong("_t");
                    for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                        String k = it.next();
                        if (!k.equals("_t")) usdRate.put(k, o.getDouble(k));
                    }
                } catch (Exception ignore) {
                }
            }
        }
        if (System.currentTimeMillis() - ratesTime < TTL || loading) {
            if (loading) onRatesLoaded = callback;
            return;
        }
        loading = true;
        onRatesLoaded = callback;
        Utilities.globalQueue.postRunnable(() -> {
            HashMap<String, Double> map = new HashMap<>();
            try {
                JSONObject fiat = new JSONObject(get("https://open.er-api.com/v6/latest/USD")).getJSONObject("rates");
                for (Iterator<String> it = fiat.keys(); it.hasNext(); ) {
                    String k = it.next();
                    double r = fiat.getDouble(k);
                    if (r > 0) map.put(k, 1 / r);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            try {
                StringBuilder ids = new StringBuilder();
                for (String[] c : CRYPTO) ids.append(ids.length() > 0 ? "," : "").append(c[1]);
                JSONObject cr = new JSONObject(get("https://api.coingecko.com/api/v3/simple/price?vs_currencies=usd&ids=" + ids));
                for (String[] c : CRYPTO) {
                    JSONObject o = cr.optJSONObject(c[1]);
                    if (o != null) map.put(c[0], o.getDouble("usd"));
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> {
                synchronized (SgramInlineCalc.class) {
                    loading = false;
                    if (!map.isEmpty()) {
                        usdRate.putAll(map);
                        ratesTime = System.currentTimeMillis();
                        try {
                            JSONObject o = new JSONObject();
                            for (String k : usdRate.keySet()) o.put(k, usdRate.get(k));
                            o.put("_t", ratesTime);
                            prefs().edit().putString("inline_calc_rates", o.toString()).apply();
                        } catch (Exception ignore) {
                        }
                    } else {
                        ratesTime = System.currentTimeMillis() - TTL + 60_000; // повтор через минуту
                    }
                    Runnable r = onRatesLoaded;
                    onRatesLoaded = null;
                    if (r != null) r.run();
                }
            });
        });
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 sGram");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static String fmt(double v, int maxFrac, boolean fixed2) {
        double a = Math.abs(v);
        if (a != 0 && (a >= 1e15 || a < 1e-8)) return String.format(Locale.ROOT, "%.6e", v).replaceAll("\\.?0+e", "e");
        java.math.BigDecimal bd = new java.math.BigDecimal(v).setScale(maxFrac, java.math.RoundingMode.HALF_UP);
        if (fixed2 && a >= 0.01) bd = bd.setScale(2, java.math.RoundingMode.HALF_UP);
        else bd = bd.stripTrailingZeros();
        String plain = bd.toPlainString();
        boolean neg = plain.startsWith("-");
        if (neg) plain = plain.substring(1);
        int dot = plain.indexOf('.');
        String ip = dot < 0 ? plain : plain.substring(0, dot), fp = dot < 0 ? "" : plain.substring(dot + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ip.length(); i++) {
            if (i > 0 && (ip.length() - i) % 3 == 0) sb.append(' ');
            sb.append(ip.charAt(i));
        }
        if (!fp.isEmpty()) sb.append(',').append(fp);
        return (neg ? "-" : "") + sb;
    }

    private static class Parser {
        final String s;
        int pos;
        boolean hadOp;

        Parser(String s) {
            this.s = s;
        }

        double parse() {
            double v = expr();
            skip();
            if (pos != s.length()) throw new IllegalArgumentException();
            return v;
        }

        void skip() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        boolean eat(char c) {
            skip();
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        double expr() {
            double v = term();
            while (true) {
                boolean plus = eat('+');
                if (!plus && !eat('-') && !eat('−')) return v;
                hadOp = true;
                int save = pos;
                double t = term();
                if (pos > 0 && s.charAt(pos - 1) == '%' && s.substring(save, pos).trim().matches("[\\d.,]+%")) t = v * t; // 100 + 10% = 110
                v = plus ? v + t : v - t;
            }
        }

        double term() {
            double v = factor();
            while (true) {
                skip();
                if (eat('*') || eat('×') || eat('·')) {
                    hadOp = true;
                    v *= factor();
                } else if (eat('/') || eat('÷') || eat(':')) {
                    hadOp = true;
                    v /= factor();
                } else if (pos < s.length() && (s.charAt(pos) == '(' || Character.isLetter(s.charAt(pos)) || s.charAt(pos) == '√' || s.charAt(pos) == 'π')) {
                    hadOp = true;
                    v *= factor();
                } else {
                    return v;
                }
            }
        }

        double factor() {
            double v = unary();
            if (eat('^')) {
                hadOp = true;
                return Math.pow(v, factor());
            }
            return v;
        }

        double unary() {
            if (eat('-') || eat('−')) return -unary();
            if (eat('+')) return unary();
            if (eat('√')) {
                hadOp = true;
                return Math.sqrt(unary());
            }
            double v = primary();
            while (true) {
                if (eat('%')) {
                    hadOp = true;
                    v /= 100;
                } else if (eat('!')) {
                    hadOp = true;
                    double r = 1;
                    for (int i = 2; i <= (int) v && i <= 170; i++) r *= i;
                    v = r;
                } else {
                    return v;
                }
            }
        }

        double primary() {
            skip();
            if (eat('(')) {
                double v = expr();
                if (!eat(')') && pos < s.length()) throw new IllegalArgumentException();
                return v;
            }
            if (eat('π')) return Math.PI;
            int st = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.' || s.charAt(pos) == ',')) pos++;
            if (pos > st) return Double.parseDouble(s.substring(st, pos).replace(',', '.'));
            while (pos < s.length() && Character.isLetter(s.charAt(pos))) pos++;
            String id = s.substring(st, pos);
            switch (id) {
                case "pi": case "пи": return Math.PI;
                case "e": return Math.E;
            }
            if (id.isEmpty()) throw new IllegalArgumentException();
            hadOp = true;
            double a = unary();
            switch (id) {
                case "sqrt": case "корень": return Math.sqrt(a);
                case "cbrt": return Math.cbrt(a);
                case "sin": return Math.sin(Math.toRadians(a));
                case "cos": return Math.cos(Math.toRadians(a));
                case "tan": case "tg": return Math.tan(Math.toRadians(a));
                case "asin": case "arcsin": return Math.toDegrees(Math.asin(a));
                case "acos": case "arccos": return Math.toDegrees(Math.acos(a));
                case "atan": case "arctan": case "arctg": return Math.toDegrees(Math.atan(a));
                case "ln": return Math.log(a);
                case "log": case "lg": return Math.log10(a);
                case "exp": return Math.exp(a);
                case "abs": return Math.abs(a);
                case "round": return Math.round(a);
                case "floor": return Math.floor(a);
                case "ceil": return Math.ceil(a);
            }
            throw new IllegalArgumentException();
        }
    }
}
