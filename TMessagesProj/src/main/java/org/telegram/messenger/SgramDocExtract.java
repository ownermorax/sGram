package org.telegram.messenger;

import android.text.Html;
import android.text.TextUtils;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Текст из документов для Doc Viewer (по мотивам плагина Doc Viewer by Daxo-Developer). */
public class SgramDocExtract {

    public static final int MAX_TEXT = 12 * 1024 * 1024;
    private static final int MAX_ROWS = 3000, MAX_COLS = 60, CELL_MAX = 40, MAX_ENTRIES = 20000;

    public static final HashMap<String, String> EXT = new HashMap<>();

    private static void reg(String fmt, String exts) {
        for (String e : exts.split(" ")) EXT.put(e, fmt);
    }

    static {
        reg("pdf", "pdf");
        reg("image", "jpg jpeg jpe png webp bmp gif heic heif avif ico");
        reg("web", "html htm xhtml svg md markdown mdown");
        reg("csv", "csv tsv tab");
        reg("json", "json geojson");
        reg("ipynb", "ipynb");
        reg("docx", "docx docm dotx dotm");
        reg("xlsx", "xlsx xlsm xltx xltm");
        reg("pptx", "pptx pptm ppsx potx");
        reg("odf", "odt ott ods ots odp otp");
        reg("rtf", "rtf");
        reg("eml", "eml");
        reg("epub", "epub");
        reg("fb2", "fb2");
        reg("archive", "zip jar aar apk tar tgz");
        reg("text", "txt text log jsonl ndjson xml plist yaml yml toml ini cfg conf env properties gradle cmake " +
                "srt vtt ass ssa lrc tex bib rst adoc org nfo diff patch vcf ics gpx kml opml rss atom " +
                "xsd xsl xslt plugin py pyw pyi js mjs cjs jsx ts tsx java kt kts scala groovy c h cpp " +
                "cc cxx hpp hh cs go rs swift m mm php rb pl lua r dart sh bash zsh fish bat cmd ps1 " +
                "vbs sql css scss sass less vue svelte gql graphql proto tf hcl asm s v sv vhd vhdl hs ml " +
                "clj ex exs erl jl nim zig dockerfile makefile mk gitignore editorconfig lock sum mod pro qml smali " +
                "csproj sln xaml aidl pem crt csr pub asc reg inf desktop service ahk");
    }

    public static String ext(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : name.toLowerCase(Locale.ROOT);
    }

    public static String detect(String name, String mime) {
        String f = EXT.get(ext(name));
        if (f != null) return f;
        if (mime != null) {
            if (mime.equals("application/pdf")) return "pdf";
            if (mime.startsWith("text/")) return "text";
            if (mime.contains("wordprocessingml")) return "docx";
            if (mime.contains("spreadsheetml")) return "xlsx";
            if (mime.contains("presentationml")) return "pptx";
            if (mime.contains("opendocument")) return "odf";
            if (mime.equals("application/json")) return "json";
            if (mime.equals("application/epub+zip")) return "epub";
        }
        return null;
    }

    public static boolean looksLikeText(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            if (n <= 0) return false;
            int bad = 0;
            for (int i = 0; i < n; i++) {
                int b = buf[i] & 0xff;
                if (b == 0) return false;
                if (b < 9 || (b > 13 && b < 32)) bad++;
            }
            return bad * 20 < n;
        } catch (Exception e) {
            return false;
        }
    }

    public static byte[] readAll(InputStream in, int max) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > max) break;
        }
        return bos.toByteArray();
    }

    // UTF-8, иначе cp1251 (русские файлы чаще всего такие)
    public static String decode(byte[] raw) {
        if (raw.length >= 2 && (raw[0] == (byte) 0xff && raw[1] == (byte) 0xfe || raw[0] == (byte) 0xfe && raw[1] == (byte) 0xff)) {
            return new String(raw, StandardCharsets.UTF_16);
        }
        java.nio.charset.CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder();
        try {
            return dec.decode(java.nio.ByteBuffer.wrap(raw)).toString();
        } catch (Exception e) {
            try {
                return new String(raw, Charset.forName("windows-1251"));
            } catch (Exception e2) {
                return new String(raw, StandardCharsets.ISO_8859_1);
            }
        }
    }

    public static String readText(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            String s = decode(readAll(in, MAX_TEXT));
            return s.startsWith("﻿") ? s.substring(1) : s;
        }
    }

    /** Текст документа; monospace[0] = true, если показывать моноширинным. */
    public static String extract(File f, String fmt, boolean[] monospace) throws Exception {
        switch (fmt) {
            case "text": monospace[0] = true; return readText(f);
            case "json": monospace[0] = true; return prettyJson(readText(f));
            case "csv": monospace[0] = true; return csv(readText(f), f.getName().toLowerCase(Locale.ROOT).matches(".*\\.(tsv|tab)$") ? '\t' : 0);
            case "ipynb": return ipynb(readText(f));
            case "docx": return docx(f);
            case "xlsx": monospace[0] = true; return xlsx(f);
            case "pptx": return pptx(f);
            case "odf": return odf(f);
            case "rtf": return rtf(readText(f));
            case "eml": return eml(readText(f));
            case "epub": return epub(f);
            case "fb2": return fb2(readText(f));
            case "archive": monospace[0] = true; return archive(f);
        }
        return readText(f);
    }

    // --- JSON / CSV / Jupyter ---

    static String prettyJson(String s) {
        try {
            String t = s.trim();
            return t.startsWith("[") ? new JSONArray(t).toString(2) : new JSONObject(t).toString(2);
        } catch (Exception e) {
            return s;
        }
    }

    static String csv(String s, char sep) {
        String[] lines = s.split("\r?\n", -1);
        if (sep == 0) {
            String head = lines.length > 0 ? lines[0] : "";
            int c = count(head, ','), sc = count(head, ';'), t = count(head, '\t');
            sep = sc > c && sc >= t ? ';' : t > c ? '\t' : ',';
        }
        ArrayList<ArrayList<String>> rows = new ArrayList<>();
        for (String line : lines) {
            if (rows.size() >= MAX_ROWS) break;
            if (line.isEmpty()) continue;
            rows.add(splitCsv(line, sep));
        }
        return table(rows);
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    private static ArrayList<String> splitCsv(String line, char sep) {
        ArrayList<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else q = false;
                } else cur.append(ch);
            } else if (ch == '"') q = true;
            else if (ch == sep) {
                out.add(cur.toString());
                cur.setLength(0);
            } else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    // Строки выровнены по ширине столбцов
    static String table(ArrayList<ArrayList<String>> rows) {
        int cols = 0;
        for (ArrayList<String> r : rows) cols = Math.max(cols, Math.min(MAX_COLS, r.size()));
        int[] w = new int[cols];
        for (ArrayList<String> r : rows) {
            for (int i = 0; i < Math.min(cols, r.size()); i++) w[i] = Math.max(w[i], Math.min(CELL_MAX, cell(r.get(i)).length()));
        }
        StringBuilder sb = new StringBuilder();
        for (ArrayList<String> r : rows) {
            for (int i = 0; i < cols; i++) {
                String c = i < r.size() ? cell(r.get(i)) : "";
                if (c.length() > CELL_MAX) c = c.substring(0, CELL_MAX - 1) + "…";
                sb.append(c);
                if (i < cols - 1) for (int k = c.length(); k < w[i] + 2; k++) sb.append(' ');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String cell(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').trim();
    }

    static String ipynb(String s) {
        try {
            JSONObject nb = new JSONObject(s);
            JSONArray cells = nb.optJSONArray("cells");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; cells != null && i < cells.length(); i++) {
                JSONObject c = cells.getJSONObject(i);
                String type = c.optString("cell_type");
                sb.append(type.equals("code") ? "▶ In [" + i + "]\n" : "");
                sb.append(joinSource(c.opt("source"))).append("\n");
                JSONArray outs = c.optJSONArray("outputs");
                for (int o = 0; outs != null && o < outs.length(); o++) {
                    JSONObject out = outs.getJSONObject(o);
                    Object text = out.has("text") ? out.opt("text") : out.optJSONObject("data") != null ? out.optJSONObject("data").opt("text/plain") : null;
                    if (text != null) sb.append("◀ ").append(joinSource(text)).append('\n');
                }
                sb.append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return s;
        }
    }

    private static String joinSource(Object src) {
        if (src instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray a = (JSONArray) src;
            for (int i = 0; i < a.length(); i++) sb.append(a.optString(i));
            return sb.toString();
        }
        return src == null ? "" : src.toString();
    }

    // --- XML-документы в zip ---

    private static XmlPullParser parser(ZipFile z, String name) throws Exception {
        ZipEntry e = z.getEntry(name);
        if (e == null) return null;
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new BufferedInputStream(z.getInputStream(e)), null);
        return p;
    }

    private static String local(String tag) {
        int i = tag.indexOf(':');
        return i >= 0 ? tag.substring(i + 1) : tag;
    }

    static String docx(File f) throws Exception {
        try (ZipFile z = new ZipFile(f)) {
            XmlPullParser p = parser(z, "word/document.xml");
            if (p == null) throw new Exception("Not a DOCX file");
            StringBuilder sb = new StringBuilder();
            StringBuilder row = null;
            int depthTable = 0;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    String t = p.getName();
                    if (t.equals("w:tbl")) depthTable++;
                    else if (t.equals("w:tab")) sb.append('\t');
                    else if (t.equals("w:br") || t.equals("w:cr")) sb.append('\n');
                    else if (t.equals("w:t")) sb.append(p.nextText());
                } else if (ev == XmlPullParser.END_TAG) {
                    String t = p.getName();
                    if (t.equals("w:p")) sb.append(depthTable > 0 ? " " : "\n");
                    else if (t.equals("w:tc")) sb.append(" │ ");
                    else if (t.equals("w:tr")) sb.append('\n');
                    else if (t.equals("w:tbl")) {
                        depthTable--;
                        sb.append('\n');
                    }
                }
            }
            return sb.toString().replaceAll("\n{3,}", "\n\n");
        }
    }

    static String xlsx(File f) throws Exception {
        try (ZipFile z = new ZipFile(f)) {
            ArrayList<String> shared = new ArrayList<>();
            XmlPullParser p = parser(z, "xl/sharedStrings.xml");
            if (p != null) {
                StringBuilder cur = null;
                for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                    if (ev == XmlPullParser.START_TAG) {
                        if (p.getName().equals("si")) cur = new StringBuilder();
                        else if (p.getName().equals("t") && cur != null) cur.append(p.nextText());
                    } else if (ev == XmlPullParser.END_TAG && p.getName().equals("si") && cur != null) {
                        shared.add(cur.toString());
                        cur = null;
                    }
                }
            }
            // имена листов по порядку
            ArrayList<String> names = new ArrayList<>();
            p = parser(z, "xl/workbook.xml");
            if (p != null) {
                for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                    if (ev == XmlPullParser.START_TAG && local(p.getName()).equals("sheet")) names.add(p.getAttributeValue(null, "name"));
                }
            }
            TreeMap<Integer, String> sheets = new TreeMap<>();
            Pattern sp = Pattern.compile("xl/worksheets/sheet(\\d+)\\.xml");
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                Matcher m = sp.matcher(en.nextElement().getName());
                if (m.matches()) sheets.put(Integer.parseInt(m.group(1)), m.group(0));
            }
            StringBuilder sb = new StringBuilder();
            int idx = 0;
            for (String part : sheets.values()) {
                String title = idx < names.size() ? names.get(idx) : "Sheet " + (idx + 1);
                idx++;
                sb.append("━━ ").append(title).append(" ━━\n");
                ArrayList<ArrayList<String>> rows = new ArrayList<>();
                p = parser(z, part);
                ArrayList<String> row = null;
                String type = null;
                int col = 0;
                for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT && rows.size() < MAX_ROWS; ev = p.next()) {
                    if (ev == XmlPullParser.START_TAG) {
                        String t = p.getName();
                        if (t.equals("row")) row = new ArrayList<>();
                        else if (t.equals("c") && row != null) {
                            type = p.getAttributeValue(null, "t");
                            col = colIndex(p.getAttributeValue(null, "r"));
                            while (row.size() < col) row.add("");
                        } else if ((t.equals("v") || t.equals("t")) && row != null) {
                            String v = p.nextText();
                            if ("s".equals(type)) {
                                try {
                                    v = shared.get(Integer.parseInt(v.trim()));
                                } catch (Exception ignore) {
                                }
                            } else if (v.matches("-?\\d+\\.\\d{6,}")) {
                                v = String.valueOf(Math.round(Double.parseDouble(v) * 1e6) / 1e6);
                            }
                            if (row.size() > col) row.set(col, row.get(col) + v);
                            else row.add(v);
                        }
                    } else if (ev == XmlPullParser.END_TAG && p.getName().equals("row") && row != null) {
                        rows.add(row);
                        row = null;
                    }
                }
                sb.append(table(rows)).append('\n');
            }
            return sb.toString();
        }
    }

    private static int colIndex(String ref) {
        if (ref == null) return 0;
        int n = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c < 'A' || c > 'Z') break;
            n = n * 26 + (c - 'A' + 1);
        }
        return Math.max(0, n - 1);
    }

    static String pptx(File f) throws Exception {
        try (ZipFile z = new ZipFile(f)) {
            TreeMap<Integer, String> slides = new TreeMap<>();
            Pattern sp = Pattern.compile("ppt/slides/slide(\\d+)\\.xml");
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                Matcher m = sp.matcher(en.nextElement().getName());
                if (m.matches()) slides.put(Integer.parseInt(m.group(1)), m.group(0));
            }
            StringBuilder sb = new StringBuilder();
            for (HashMap.Entry<Integer, String> e : slides.entrySet()) {
                sb.append("━━ Slide ").append(e.getKey()).append(" ━━\n");
                XmlPullParser p = parser(z, e.getValue());
                for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                    if (ev == XmlPullParser.START_TAG && p.getName().equals("a:t")) sb.append(p.nextText());
                    else if (ev == XmlPullParser.END_TAG && p.getName().equals("a:p")) sb.append('\n');
                }
                sb.append('\n');
            }
            return sb.toString();
        }
    }

    static String odf(File f) throws Exception {
        try (ZipFile z = new ZipFile(f)) {
            XmlPullParser p = parser(z, "content.xml");
            if (p == null) throw new Exception("Not an OpenDocument file");
            StringBuilder sb = new StringBuilder();
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.TEXT) sb.append(p.getText());
                else if (ev == XmlPullParser.START_TAG) {
                    String t = p.getName();
                    if (t.equals("text:tab")) sb.append('\t');
                    else if (t.equals("text:line-break")) sb.append('\n');
                    else if (t.equals("text:s")) sb.append(' ');
                    else if (t.equals("table:table")) sb.append("━━ ").append(p.getAttributeValue(null, "table:name")).append(" ━━\n");
                } else if (ev == XmlPullParser.END_TAG) {
                    String t = p.getName();
                    if (t.equals("text:p") || t.equals("text:h")) sb.append(sb.length() > 0 && sb.charAt(sb.length() - 1) == '│' ? "" : "\n");
                    else if (t.equals("table:table-cell")) {
                        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') sb.setLength(sb.length() - 1);
                        sb.append(" │ ");
                    } else if (t.equals("table:table-row")) sb.append('\n');
                }
            }
            return sb.toString().replaceAll("\n{3,}", "\n\n");
        }
    }

    // RTF: управляющие слова выкидываем, \'xx — байты cp1251, \\uN — юникод
    static String rtf(String s) {
        StringBuilder out = new StringBuilder();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int depth = 0, skipDepth = -1, ucSkip = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
                continue;
            }
            if (c == '}') {
                if (depth == skipDepth) skipDepth = -1;
                depth--;
                continue;
            }
            boolean skipping = skipDepth >= 0;
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '\'' && i + 3 < s.length()) {
                    if (!skipping) {
                        try {
                            bytes.write(Integer.parseInt(s.substring(i + 2, i + 4), 16));
                        } catch (Exception ignore) {
                        }
                    }
                    i += 3;
                    continue;
                }
                flush(bytes, out);
                if (n == '\\' || n == '{' || n == '}') {
                    if (!skipping) out.append(n);
                    i++;
                    continue;
                }
                if (n == '*') {
                    skipDepth = depth;
                    i++;
                    continue;
                }
                int j = i + 1;
                while (j < s.length() && Character.isLetter(s.charAt(j))) j++;
                String word = s.substring(i + 1, j);
                int k = j;
                if (k < s.length() && (s.charAt(k) == '-' || Character.isDigit(s.charAt(k)))) {
                    k++;
                    while (k < s.length() && Character.isDigit(s.charAt(k))) k++;
                }
                String num = s.substring(j, k);
                if (k < s.length() && s.charAt(k) == ' ') k++;
                i = k - 1;
                if (word.equals("fonttbl") || word.equals("colortbl") || word.equals("stylesheet") || word.equals("info") || word.equals("pict")) {
                    skipDepth = depth;
                } else if (!skipping) {
                    if (word.equals("par") || word.equals("line")) out.append('\n');
                    else if (word.equals("tab")) out.append('\t');
                    else if (word.equals("cell")) out.append(" │ ");
                    else if (word.equals("row")) out.append('\n');
                    else if (word.equals("u") && !num.isEmpty()) {
                        int code = Integer.parseInt(num);
                        if (code < 0) code += 65536;
                        out.append((char) code);
                        ucSkip = 1;
                    }
                }
                continue;
            }
            if (c == '\r' || c == '\n') continue;
            if (ucSkip > 0) {
                ucSkip--;
                continue;
            }
            flush(bytes, out);
            if (!skipping) out.append(c);
        }
        flush(bytes, out);
        return out.toString();
    }

    private static void flush(ByteArrayOutputStream bytes, StringBuilder out) {
        if (bytes.size() == 0) return;
        out.append(new String(bytes.toByteArray(), Charset.forName("windows-1251")));
        bytes.reset();
    }

    // Письмо: заголовки From/To/Subject/Date и текстовая часть
    static String eml(String s) {
        int split = s.indexOf("\r\n\r\n");
        if (split < 0) split = s.indexOf("\n\n");
        String headers = split >= 0 ? s.substring(0, split) : s;
        String body = split >= 0 ? s.substring(split).trim() : "";
        StringBuilder sb = new StringBuilder();
        String unfolded = headers.replaceAll("\r?\n[ \t]+", " ");
        for (String line : unfolded.split("\r?\n")) {
            String low = line.toLowerCase(Locale.ROOT);
            if (low.startsWith("from:") || low.startsWith("to:") || low.startsWith("cc:") || low.startsWith("subject:") || low.startsWith("date:")) {
                sb.append(decodeMimeWords(line)).append('\n');
            }
        }
        sb.append("\n");
        Matcher m = Pattern.compile("boundary=\"?([^\";\\s]+)", Pattern.CASE_INSENSITIVE).matcher(unfolded);
        if (m.find()) {
            String best = null;
            for (String part : body.split("--" + Pattern.quote(m.group(1)))) {
                int ps = part.indexOf("\n\n");
                int ps2 = part.indexOf("\r\n\r\n");
                if (ps2 >= 0 && (ps < 0 || ps2 < ps)) ps = ps2;
                if (ps < 0) continue;
                String ph = part.substring(0, ps).toLowerCase(Locale.ROOT);
                String pb = decodeBody(ph, part.substring(ps).trim());
                if (ph.contains("text/plain")) {
                    best = pb;
                    break;
                }
                if (ph.contains("text/html") && best == null) best = Html.fromHtml(pb).toString();
            }
            sb.append(best != null ? best : body);
        } else {
            String b = decodeBody(unfolded.toLowerCase(Locale.ROOT), body);
            sb.append(unfolded.toLowerCase(Locale.ROOT).contains("text/html") ? Html.fromHtml(b).toString() : b);
        }
        return sb.toString();
    }

    private static String decodeBody(String headersLower, String body) {
        Matcher cs = Pattern.compile("charset=\"?([\\w-]+)").matcher(headersLower);
        Charset charset = StandardCharsets.UTF_8;
        try {
            if (cs.find()) charset = Charset.forName(cs.group(1));
        } catch (Exception ignore) {
        }
        try {
            if (headersLower.contains("base64")) {
                return new String(android.util.Base64.decode(body.replaceAll("\\s", ""), android.util.Base64.DEFAULT), charset);
            }
            if (headersLower.contains("quoted-printable")) {
                return new String(quotedPrintable(body), charset);
            }
        } catch (Exception ignore) {
        }
        return body;
    }

    private static byte[] quotedPrintable(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        s = s.replaceAll("=\r?\n", "");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' && i + 2 < s.length()) {
                try {
                    out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (Exception ignore) {
                }
            }
            byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            out.write(b, 0, b.length);
        }
        return out.toByteArray();
    }

    private static String decodeMimeWords(String s) {
        Matcher m = Pattern.compile("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String dec;
            try {
                Charset cs = Charset.forName(m.group(1));
                if (m.group(2).equalsIgnoreCase("b")) dec = new String(android.util.Base64.decode(m.group(3), android.util.Base64.DEFAULT), cs);
                else dec = new String(quotedPrintable(m.group(3).replace('_', ' ')), cs);
            } catch (Exception e) {
                dec = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(dec));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // EPUB: главы по spine из OPF, XHTML → текст
    static String epub(File f) throws Exception {
        try (ZipFile z = new ZipFile(f)) {
            String opf = null;
            XmlPullParser p = parser(z, "META-INF/container.xml");
            if (p != null) {
                for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                    if (ev == XmlPullParser.START_TAG && local(p.getName()).equals("rootfile")) {
                        opf = p.getAttributeValue(null, "full-path");
                        break;
                    }
                }
            }
            if (opf == null) throw new Exception("Not an EPUB file");
            String base = opf.contains("/") ? opf.substring(0, opf.lastIndexOf('/') + 1) : "";
            HashMap<String, String> manifest = new HashMap<>();
            ArrayList<String> spine = new ArrayList<>();
            p = parser(z, opf);
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev != XmlPullParser.START_TAG) continue;
                String t = local(p.getName());
                if (t.equals("item")) manifest.put(p.getAttributeValue(null, "id"), p.getAttributeValue(null, "href"));
                else if (t.equals("itemref")) spine.add(p.getAttributeValue(null, "idref"));
            }
            StringBuilder sb = new StringBuilder();
            for (String id : spine) {
                String href = manifest.get(id);
                if (href == null) continue;
                ZipEntry e = z.getEntry(base + android.net.Uri.decode(href));
                if (e == null) continue;
                String html = decode(readAll(z.getInputStream(e), MAX_TEXT));
                html = html.replaceAll("(?is)<head.*?</head>", "");
                sb.append(Html.fromHtml(html).toString().trim()).append("\n\n");
                if (sb.length() > MAX_TEXT) break;
            }
            return sb.toString();
        }
    }

    static String fb2(String s) {
        String body = s.replaceAll("(?is)<binary.*?</binary>", "");
        body = body.replaceAll("(?i)<title>", "\n\n").replaceAll("(?i)</title>", "\n").replaceAll("(?i)<empty-line\\s*/>", "\n")
                .replaceAll("(?i)<p>", "").replaceAll("(?i)</p>", "\n").replaceAll("(?i)</v>", "\n");
        body = body.replaceAll("(?s)<[^>]+>", "");
        return Html.fromHtml(body.replace("\n", "<br>")).toString();
    }

    // Архив: список файлов с размерами
    static String archive(File f) throws Exception {
        String name = f.getName().toLowerCase(Locale.ROOT);
        ArrayList<String> lines = new ArrayList<>();
        long total = 0;
        if (name.endsWith(".tar") || name.endsWith(".tgz") || name.endsWith(".tar.gz")) {
            try (InputStream raw = new BufferedInputStream(new FileInputStream(f))) {
                InputStream in = name.endsWith(".tar") ? raw : new GZIPInputStream(raw);
                byte[] header = new byte[512];
                while (lines.size() < MAX_ENTRIES) {
                    int n = 0;
                    while (n < 512) {
                        int r = in.read(header, n, 512 - n);
                        if (r <= 0) break;
                        n += r;
                    }
                    if (n < 512 || header[0] == 0) break;
                    String entry = new String(header, 0, 100, StandardCharsets.UTF_8).trim().replace("\0", "");
                    long size = Long.parseLong(new String(header, 124, 12, StandardCharsets.US_ASCII).replace("\0", "").trim().isEmpty() ? "0" : new String(header, 124, 12, StandardCharsets.US_ASCII).replace("\0", "").trim(), 8);
                    lines.add(String.format(Locale.US, "%10s  %s", size(size), entry));
                    total += size;
                    long skip = (size + 511) / 512 * 512;
                    while (skip > 0) {
                        long sk = in.skip(skip);
                        if (sk <= 0) break;
                        skip -= sk;
                    }
                }
            }
        } else {
            try (ZipFile z = new ZipFile(f)) {
                for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements() && lines.size() < MAX_ENTRIES; ) {
                    ZipEntry e = en.nextElement();
                    lines.add(String.format(Locale.US, "%10s  %s", e.isDirectory() ? "" : size(e.getSize()), e.getName()));
                    if (e.getSize() > 0) total += e.getSize();
                }
            }
        }
        Collections.sort(lines, (a, b) -> a.substring(12).compareToIgnoreCase(b.substring(12)));
        return lines.size() + " entries · " + size(total) + " unpacked\n\n" + TextUtils.join("\n", lines);
    }

    public static String size(long n) {
        if (n < 1024) return n + " B";
        int exp = (int) (Math.log(n) / Math.log(1024));
        return String.format(Locale.US, "%.1f %sB", n / Math.pow(1024, exp), "KMGTPE".charAt(exp - 1));
    }

    // --- веб: Markdown → HTML ---

    public static String markdownToHtml(String md, boolean dark) {
        StringBuilder sb = new StringBuilder();
        String[] lines = md.split("\r?\n");
        boolean code = false, list = false, olist = false;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.trim().startsWith("```")) {
                if (list) { sb.append("</ul>"); list = false; }
                if (olist) { sb.append("</ol>"); olist = false; }
                sb.append(code ? "</code></pre>" : "<pre><code>");
                code = !code;
                continue;
            }
            if (code) {
                sb.append(TextUtils.htmlEncode(l)).append('\n');
                continue;
            }
            Matcher h = Pattern.compile("^(#{1,6})\\s+(.*)").matcher(l);
            Matcher ul = Pattern.compile("^\\s*[-*+]\\s+(.*)").matcher(l);
            Matcher ol = Pattern.compile("^\\s*\\d+[.)]\\s+(.*)").matcher(l);
            if (!ul.matches() && list) { sb.append("</ul>"); list = false; }
            if (!ol.matches() && olist) { sb.append("</ol>"); olist = false; }
            if (h.matches()) {
                int n = h.group(1).length();
                sb.append("<h").append(n).append('>').append(inline(h.group(2))).append("</h").append(n).append('>');
            } else if (ul.matches()) {
                if (!list) { sb.append("<ul>"); list = true; }
                sb.append("<li>").append(inline(ul.group(1))).append("</li>");
            } else if (ol.matches()) {
                if (!olist) { sb.append("<ol>"); olist = true; }
                sb.append("<li>").append(inline(ol.group(1))).append("</li>");
            } else if (l.startsWith(">")) {
                sb.append("<blockquote>").append(inline(l.replaceFirst("^>\\s?", ""))).append("</blockquote>");
            } else if (l.matches("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$")) {
                sb.append("<hr>");
            } else if (l.contains("|") && i + 1 < lines.length && lines[i + 1].matches("^\\s*\\|?\\s*:?-{2,}.*")) {
                sb.append("<table>");
                sb.append(row(l, "th"));
                i++;
                while (i + 1 < lines.length && lines[i + 1].contains("|")) sb.append(row(lines[++i], "td"));
                sb.append("</table>");
            } else if (l.trim().isEmpty()) {
                sb.append("<p></p>");
            } else {
                sb.append("<p>").append(inline(l)).append("</p>");
            }
        }
        if (code) sb.append("</code></pre>");
        if (list) sb.append("</ul>");
        if (olist) sb.append("</ol>");
        return page(sb.toString(), dark);
    }

    private static String row(String l, String tag) {
        StringBuilder sb = new StringBuilder("<tr>");
        String t = l.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
        for (String c : t.split("\\|")) sb.append('<').append(tag).append('>').append(inline(c.trim())).append("</").append(tag).append('>');
        return sb.append("</tr>").toString();
    }

    private static String inline(String s) {
        s = TextUtils.htmlEncode(s);
        s = s.replaceAll("`([^`]+)`", "<code>$1</code>");
        s = s.replaceAll("!\\[([^\\]]*)\\]\\(([^)\\s]+)[^)]*\\)", "<img alt=\"$1\" src=\"$2\">");
        s = s.replaceAll("\\[([^\\]]+)\\]\\(([^)\\s]+)[^)]*\\)", "<a href=\"$2\">$1</a>");
        s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>").replaceAll("__([^_]+)__", "<b>$1</b>");
        s = s.replaceAll("(?<![*\\w])\\*([^*]+)\\*(?!\\*)", "<i>$1</i>");
        s = s.replaceAll("~~([^~]+)~~", "<s>$1</s>");
        return s;
    }

    public static String page(String body, boolean dark) {
        String bg = dark ? "#09090d" : "#fafaf7", fg = dark ? "#e8e8ee" : "#1c1c20", alt = dark ? "#1c1c26" : "#ecece6";
        return "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>" +
                "<style>body{background:" + bg + ";color:" + fg + ";font-family:sans-serif;line-height:1.5;padding:12px;word-wrap:break-word}" +
                "pre,code{background:" + alt + ";border-radius:6px;font-family:monospace}pre{padding:10px;overflow:auto}code{padding:1px 4px}" +
                "table{border-collapse:collapse}td,th{border:1px solid " + alt + ";padding:4px 8px}img{max-width:100%}a{color:#4ea4f6}" +
                "blockquote{border-left:3px solid " + alt + ";margin:0;padding-left:10px;opacity:.85}</style></head><body>" + body + "</body></html>";
    }
}
