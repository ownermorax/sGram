package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

/** Plugin Guard report before installing a plugin: score, threats, sector map, audit, code inspector. */
public class SgramGuardSheet {

    private static final int RED = 0xFFE5484D, ORANGE = 0xFFF5A524, GREEN = 0xFF2FA36B, PURPLE = 0xFFA855F7;

    private final BaseFragment fragment;
    private final Context context;
    private final JSONObject meta;
    private final JSONObject scan;
    private final String source;
    private final String fileName;
    private BottomSheet sheet;

    public static void show(BaseFragment fragment, File file, JSONObject meta, Runnable onInstall) {
        new SgramGuardSheet(fragment, file, meta).open(onInstall);
    }

    private SgramGuardSheet(BaseFragment fragment, File file, JSONObject meta) {
        this.fragment = fragment;
        this.context = fragment.getParentActivity();
        this.meta = meta;
        JSONObject s = meta.optJSONObject("scan");
        this.scan = s != null ? s : new JSONObject();
        this.fileName = file.getName();
        String text = "";
        try {
            byte[] data = new byte[(int) Math.min(file.length(), 4 * 1024 * 1024)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int off = 0, r;
                while (off < data.length && (r = in.read(data, off, data.length - off)) > 0) {
                    off += r;
                }
            }
            text = new String(data, "UTF-8");
            if (text.startsWith("﻿")) {
                text = text.substring(1);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        this.source = text;
    }

    private int textColor() {
        return Theme.getColor(Theme.key_dialogTextBlack);
    }

    private int grayColor() {
        return Theme.getColor(Theme.key_dialogTextGray3);
    }

    private int cardColor() {
        return Theme.multAlpha(textColor(), 0.06f);
    }

    private static GradientDrawable round(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) {
            d.setStroke(dp(1), stroke);
        }
        return d;
    }

    private TextView text(CharSequence s, float size, int color, boolean bold) {
        TextView t = new TextView(context);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(AndroidUtilities.bold());
        }
        return t;
    }

    private String level() {
        return scan.optString("level", "clean");
    }

    private int levelColor() {
        String l = level();
        return "danger".equals(l) ? RED : "warning".equals(l) ? ORANGE : GREEN;
    }

    private void open(Runnable onInstall) {
        sheet = new BottomSheet(context, false);
        sheet.fixNavigationBar(Theme.getColor(Theme.key_dialogBackground));
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(context);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(8), dp(16), dp(8));
        scroll.addView(list);
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        boolean danger = "danger".equals(level());
        buildHeader(list);
        buildScore(list);
        JSONArray findings = scan.optJSONArray("findings");
        ArrayList<JSONObject> crit = new ArrayList<>(), warn = new ArrayList<>();
        if (findings != null) {
            for (int i = 0; i < findings.length(); i++) {
                JSONObject f = findings.optJSONObject(i);
                if (f != null) {
                    ("CRITICAL".equals(f.optString("severity")) ? crit : warn).add(f);
                }
            }
        }
        if (!crit.isEmpty()) {
            buildThreats(list, "Критические угрозы (" + crit.size() + ")", crit, RED, true);
        }
        if (!warn.isEmpty()) {
            buildThreats(list, "Предупреждения (" + warn.size() + ")", warn, ORANGE, crit.isEmpty());
        }
        buildSectors(list);
        buildAudit(list);
        buildNotes(list);

        LinearLayout tools = new LinearLayout(context);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        TextView inspect = button("Инспектор кода", cardColor(), textColor());
        inspect.setOnClickListener(v -> openInspector(0, 0));
        TextView all = button("Все угрозы (" + (crit.size() + warn.size()) + ")", cardColor(), textColor());
        all.setOnClickListener(v -> openAllThreats());
        tools.addView(inspect, LayoutHelper.createLinear(0, 44, 1f, 0, 0, 4, 0));
        tools.addView(all, LayoutHelper.createLinear(0, 44, 1f, 4, 0, 0, 0));
        list.addView(tools, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 12, 0, 4));

        // Bottom: risk checkbox (danger only) + install / cancel.
        LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(16), dp(8), dp(16), dp(12));
        boolean installed = meta.optBoolean("installed");
        String label = danger ? "Установить всё равно" : installed ? "Обновить" : "Установить";
        TextView install = button(label, danger ? Theme.multAlpha(RED, 0.25f) : Theme.getColor(Theme.key_featuredStickers_addButton),
                danger ? RED : Theme.getColor(Theme.key_featuredStickers_buttonText));
        install.setOnClickListener(v -> {
            if (!install.isEnabled()) {
                return;
            }
            sheet.dismiss();
            onInstall.run();
        });
        if (danger) {
            install.setEnabled(false);
            install.setAlpha(0.45f);
            TextView risk = text("☐  Я понимаю все риски, всё равно установить", 15, RED, true);
            risk.setGravity(Gravity.CENTER);
            risk.setBackground(round(Theme.multAlpha(RED, 0.12f), Theme.multAlpha(RED, 0.5f), 14));
            risk.setPadding(dp(12), dp(12), dp(12), dp(12));
            risk.setOnClickListener(v -> {
                boolean on = !install.isEnabled();
                install.setEnabled(on);
                install.setAlpha(on ? 1f : 0.45f);
                risk.setText((on ? "☑" : "☐") + "  Я понимаю все риски, всё равно установить");
            });
            bottom.addView(risk, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        }
        bottom.addView(install, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        TextView cancel = button(danger ? "Не устанавливать" : "Отмена", 0, Theme.getColor(Theme.key_dialogTextBlue2));
        cancel.setOnClickListener(v -> sheet.dismiss());
        bottom.addView(cancel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 4, 0, 0));
        root.addView(bottom, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (AndroidUtilities.displaySize.y * 0.88f)));
        FrameLayout holder = new FrameLayout(context);
        holder.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (AndroidUtilities.displaySize.y * 0.88f)));
        sheet.setCustomView(holder);
        fragment.showDialog(sheet);
    }

    private TextView button(String s, int bg, int fg) {
        TextView b = text(s, 15, fg, true);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        if (bg != 0) {
            b.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(22), bg, Theme.multAlpha(fg, 0.15f)));
        } else {
            b.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(fg, 0.1f), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
        }
        return b;
    }

    private void buildHeader(LinearLayout list) {
        String l = level();
        FrameLayout iconBox = new FrameLayout(context);
        String name = meta.optString("name", "?");
        TextView icon = text(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(), 30, levelColor(), true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(Theme.multAlpha(levelColor(), 0.12f), Theme.multAlpha(levelColor(), 0.6f), 36));
        iconBox.addView(icon, LayoutHelper.createFrame(72, 72, Gravity.CENTER));
        TextView badge = text("danger".equals(l) ? "⛔" : "warning".equals(l) ? "⚠" : "✓", 14, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(round(levelColor(), 0, 13));
        iconBox.addView(badge, LayoutHelper.createFrame(26, 26, Gravity.CENTER, 50, 50, 0, 0));
        list.addView(iconBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 84, 0, 8, 0, 4));

        String title = "danger".equals(l) ? "PluginGuard: Опасность!" : "warning".equals(l) ? "PluginGuard: Внимание" : "PluginGuard: Безопасно";
        TextView t = text(title, 20, textColor(), true);
        t.setGravity(Gravity.CENTER);
        list.addView(t, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        StringBuilder sub = new StringBuilder(name);
        if (!TextUtils.isEmpty(meta.optString("version"))) {
            sub.append(" ").append(meta.optString("version"));
        }
        if (!TextUtils.isEmpty(meta.optString("author"))) {
            sub.append(" • ").append(meta.optString("author"));
        }
        TextView s = text(sub, 15, grayColor(), false);
        s.setGravity(Gravity.CENTER);
        list.addView(s, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        JSONObject audit = scan.optJSONObject("audit");
        TextView v = text(fileName + "\n" + scan.optString("version", "PG sGram") + (audit != null ? " | " + audit.optInt("rules") + " правил, " + audit.optInt("signatures") + " сигнатур" : ""), 12, grayColor(), false);
        v.setGravity(Gravity.CENTER);
        list.addView(v, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        TextView chip = text("danger".equals(l) ? "Вредоносный плагин" : "warning".equals(l) ? "Подозрительный плагин" : "Угроз не найдено", 14, levelColor(), true);
        chip.setBackground(round(Theme.multAlpha(levelColor(), 0.15f), 0, 14));
        chip.setPadding(dp(14), dp(6), dp(14), dp(6));
        list.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 10, 0, 6));

        if (!TextUtils.isEmpty(meta.optString("description"))) {
            TextView d = text(meta.optString("description"), 15, textColor(), false);
            d.setBackground(round(cardColor(), 0, 14));
            d.setPadding(dp(14), dp(12), dp(14), dp(12));
            list.addView(d, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 4));
        }
    }

    private void buildScore(LinearLayout list) {
        int score = scan.optInt("score", 100);
        FrameLayout row = new FrameLayout(context);
        row.addView(text("Оценка безопасности PluginGuard", 15, textColor(), false), LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL));
        int c = score >= 70 ? GREEN : score >= 40 ? ORANGE : RED;
        row.addView(text(score + "/100", 17, c, true), LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        list.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 6));
        View bar = new View(context) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            final RectF r = new RectF();

            @Override
            protected void onDraw(Canvas canvas) {
                float h = getHeight();
                p.setColor(Theme.multAlpha(c, 0.2f));
                r.set(0, 0, getWidth(), h);
                canvas.drawRoundRect(r, h / 2, h / 2, p);
                p.setColor(c);
                r.set(0, 0, Math.max(h, getWidth() * score / 100f), h);
                canvas.drawRoundRect(r, h / 2, h / 2, p);
            }
        };
        list.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 6, 0, 0, 0, 10));
    }

    private static String linesText(JSONObject f) {
        JSONArray lines = f.optJSONArray("lines");
        if (lines == null || lines.length() == 0) {
            return "";
        }
        StringBuilder b = new StringBuilder(lines.length() == 1 ? " (строка " : " (строки ");
        for (int i = 0; i < Math.min(lines.length(), 6); i++) {
            b.append(i > 0 ? ", " : "").append(lines.optInt(i));
        }
        if (lines.length() > 6) {
            b.append(", …");
        }
        if (f.optInt("layer") > 0) {
            b.append(", слой ").append(f.optInt("layer"));
        }
        return b.append(")").toString();
    }

    private void buildThreats(LinearLayout list, String title, ArrayList<JSONObject> items, int color, boolean expanded) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(Theme.multAlpha(color, 0.10f), Theme.multAlpha(color, 0.45f), 18));
        TextView head = text((expanded ? "▴  " : "▾  ") + title, 16, color, true);
        head.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.addView(head);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), 0, dp(16), dp(8));
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        for (JSONObject f : items) {
            LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(12), dp(8), 0, dp(8));
            item.setBackground(new android.graphics.drawable.ColorDrawable(0) {
                final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

                @Override
                public void draw(Canvas canvas) {
                    p.setColor(color);
                    canvas.drawRoundRect(new RectF(0, getBounds().top + dp(10), dp(3), getBounds().top + dp(30)), dp(2), dp(2), p);
                }
            });
            item.addView(text(f.optString("title") + linesText(f), 15, textColor(), true));
            String t = f.optString("text");
            if (!TextUtils.isEmpty(f.optString("detail"))) {
                t = (TextUtils.isEmpty(t) ? "" : t + "\n") + "→ " + f.optString("detail");
            }
            if (!TextUtils.isEmpty(t)) {
                item.addView(text(t, 14, grayColor(), false), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            }
            item.setOnClickListener(v -> openInspector(f.optInt("layer"), f.optInt("line")));
            body.addView(item);
        }
        card.addView(body);
        head.setOnClickListener(v -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            head.setText((show ? "▴  " : "▾  ") + title);
        });
        list.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 6));
    }

    private LinearLayout card(LinearLayout list, String title) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(cardColor(), Theme.multAlpha(textColor(), 0.12f), 18));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.addView(text(title, 17, textColor(), true), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        list.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 6));
        return card;
    }

    private void buildSectors(LinearLayout list) {
        JSONArray sectors = scan.optJSONArray("sectors");
        if (sectors == null || sectors.length() == 0) {
            return;
        }
        LinearLayout card = card(list, "Карта секторов кода");
        TextView info = text("●  Нажмите на сектор для просмотра деталей", 14, grayColor(), false);
        info.setBackground(round(cardColor(), 0, 12));
        info.setPadding(dp(12), dp(10), dp(12), dp(10));
        int perRow = 10;
        LinearLayout row = null;
        for (int i = 0; i < sectors.length(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(context);
                card.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 30, 0, 0, 0, 6));
            }
            JSONObject s = sectors.optJSONObject(i);
            String state = s.optString("state");
            int c = "critical".equals(state) ? RED : "obfuscated".equals(state) ? PURPLE : "warning".equals(state) ? ORANGE
                    : "empty".equals(state) ? Theme.multAlpha(textColor(), 0.06f) : 0xFF1F6B4E;
            View cell = new View(context);
            cell.setBackground(round(c, 0, 8));
            cell.setOnClickListener(v -> {
                if (s.optInt("from") == 0) {
                    info.setText("●  Пустой сектор");
                    return;
                }
                StringBuilder b = new StringBuilder("●  Строки ").append(s.optInt("from")).append("–").append(s.optInt("to")).append(": ");
                JSONArray titles = s.optJSONArray("titles");
                if ("obfuscated".equals(state)) {
                    b.append("упакованные/зашифрованные данные");
                    if (titles != null && titles.length() > 0) {
                        b.append("; ");
                    }
                }
                if (titles != null && titles.length() > 0) {
                    for (int k = 0; k < titles.length(); k++) {
                        b.append(k > 0 ? ", " : "").append(titles.optString(k));
                    }
                } else if (!"obfuscated".equals(state)) {
                    b.append("угроз нет");
                }
                info.setText(b);
                info.setOnClickListener(x -> openInspector(0, s.optInt("from")));
            });
            row.addView(cell, LayoutHelper.createLinear(0, 30, 1f, 3, 0, 3, 0));
        }
        card.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
    }

    private void auditRow(LinearLayout card, String s) {
        TextView t = text(s, 15, textColor(), false);
        t.setPadding(dp(10), dp(6), 0, dp(6));
        t.setBackground(new android.graphics.drawable.ColorDrawable(0) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas canvas) {
                p.setColor(grayColor());
                canvas.drawRoundRect(new RectF(0, getBounds().top + dp(8), dp(3), getBounds().top + dp(26)), dp(2), dp(2), p);
            }
        });
        card.addView(t);
    }

    private void buildAudit(LinearLayout list) {
        JSONObject a = scan.optJSONObject("audit");
        if (a == null) {
            return;
        }
        LinearLayout card = card(list, "Аудит");
        auditRow(card, "Вердикт: " + a.optString("verdict"));
        int obf = a.optInt("obfuscation");
        auditRow(card, "Обфускация: " + (obf > 0 ? "обнаружена (" + obf + " сл.)" : "не обнаружена"));
        double e = a.optDouble("entropy");
        auditRow(card, String.format(java.util.Locale.US, "Энтропия: %.4f%s", e, e > 5.2 ? " (обфускация)" : " (норма)"));
        if (a.optDouble("max_literal_entropy") > 0) {
            auditRow(card, String.format(java.util.Locale.US, "Макс. энтропия строки: %.4f", a.optDouble("max_literal_entropy")));
        }
        auditRow(card, "Угрозы: " + a.optInt("critical") + " критических, " + a.optInt("warnings") + " предупреждений");
        auditRow(card, "Строк кода: " + a.optInt("lines") + ", функций: " + a.optInt("functions"));
        JSONArray layers = scan.optJSONArray("layers");
        if (layers != null && layers.length() > 0) {
            auditRow(card, "Распаковано скрытого кода: " + layers.length() + " бл.");
        }
        JSONArray imports = a.optJSONArray("imports");
        if (imports != null && imports.length() > 0) {
            auditRow(card, "Импорты: " + join(imports));
        }
        JSONArray urls = a.optJSONArray("urls");
        if (urls != null && urls.length() > 0) {
            StringBuilder b = new StringBuilder("Адреса в коде:");
            for (int i = 0; i < urls.length(); i++) {
                b.append("\n").append(urls.optString(i));
            }
            auditRow(card, b.toString());
        }
        auditRow(card, "Проверено: " + a.optInt("rules") + " правил, " + a.optInt("signatures") + " сигнатур");
    }

    private static String join(JSONArray a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; a != null && i < a.length(); i++) {
            b.append(i > 0 ? ", " : "").append(a.optString(i));
        }
        return b.toString();
    }

    private void buildNotes(LinearLayout list) {
        StringBuilder b = new StringBuilder();
        if (meta.optBoolean("installed")) {
            b.append("Установлена версия ").append(meta.optString("installed_version")).append(" — она будет заменена.\n");
        }
        if (meta.has("missing")) {
            b.append("⚠ Нужны пакеты, которых нет в sGram: ").append(join(meta.optJSONArray("missing"))).append("\n");
        }
        if (!org.telegram.messenger.SgramPlugins.isEngineEnabled()) {
            b.append("Движок плагинов выключен: плагин заработает после включения.\n");
        }
        b.append("Плагин получает полный доступ к sGram и вашему аккаунту.");
        TextView t = text(b.toString().trim(), 13, grayColor(), false);
        t.setPadding(dp(4), dp(6), dp(4), 0);
        list.addView(t);
    }

    // ---------------- All threats ----------------

    private void openAllThreats() {
        JSONArray findings = scan.optJSONArray("findings");
        BottomSheet s = new BottomSheet(context, false);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        int n = findings != null ? findings.length() : 0;
        root.addView(text("Все угрозы (" + n + ")", 20, textColor(), true));
        root.addView(text("Нажмите на угрозу для перехода к строке", 14, grayColor(), false), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 10));
        ScrollView scroll = new ScrollView(context);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        for (int i = 0; i < n; i++) {
            JSONObject f = findings.optJSONObject(i);
            boolean crit = "CRITICAL".equals(f.optString("severity"));
            int c = crit ? RED : ORANGE;
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(10), dp(10), dp(10));
            row.setBackground(round(Theme.multAlpha(c, 0.10f), Theme.multAlpha(c, 0.35f), 14));
            int line = f.optInt("line");
            TextView tag = text(line > 0 ? (f.optInt("layer") > 0 ? "S" + f.optInt("layer") + ":" : "L") + line : "⚠", 13, c, true);
            tag.setBackground(round(Theme.multAlpha(c, 0.2f), 0, 8));
            tag.setPadding(dp(6), dp(3), dp(6), dp(3));
            row.addView(tag);
            TextView title = text(f.optString("title"), 15, textColor(), true);
            row.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 10, 0, 8, 0));
            TextView sev = text(crit ? "CRITICAL" : "WARNING", 11, c, true);
            sev.setBackground(round(Theme.multAlpha(c, 0.2f), 0, 6));
            sev.setPadding(dp(6), dp(3), dp(6), dp(3));
            row.addView(sev);
            row.setOnClickListener(v -> openInspector(f.optInt("layer"), line));
            list.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        }
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, (int) (AndroidUtilities.displaySize.y * 0.7f / AndroidUtilities.density)));
        s.setCustomView(root);
        s.show();
    }

    // ---------------- Code inspector ----------------

    private String layerCode(int layer) {
        if (layer <= 0) {
            return source;
        }
        JSONArray layers = scan.optJSONArray("layers");
        JSONObject l = layers != null ? layers.optJSONObject(layer - 1) : null;
        return l != null ? l.optString("code") : "";
    }

    private void openInspector(int startLayer, int startLine) {
        BottomSheet s = new BottomSheet(context, false);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        JSONArray layers = scan.optJSONArray("layers");
        int layerCount = layers != null ? layers.length() : 0;

        FrameLayout header = new FrameLayout(context);
        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("Инспектор кода", 20, textColor(), true));
        titles.addView(text(layerCount > 0 ? "Полезная нагрузка: " + layerCount + " сл." : "Скрытого кода нет", 13, grayColor(), false));
        header.addView(titles, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT));
        LinearLayout actions = new LinearLayout(context);
        TextView wrapBtn = pill("Перенос", false);
        TextView copyBtn = pill("Копировать", false);
        actions.addView(wrapBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 6, 0));
        actions.addView(copyBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34));
        header.addView(actions, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        HorizontalScrollView layerScroll = new HorizontalScrollView(context);
        layerScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout layerRow = new LinearLayout(context);
        layerScroll.addView(layerRow);
        HorizontalScrollView chipScroll = new HorizontalScrollView(context);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(context);
        chipScroll.addView(chipRow);
        if (layerCount > 0) {
            root.addView(layerScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0, 0, 0, 6));
        }
        root.addView(chipScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0, 0, 0, 8));

        ScrollView vScroll = new ScrollView(context);
        vScroll.setBackground(round(0xFF15171C, 0x33FFFFFF, 14));
        HorizontalScrollView hScroll = new HorizontalScrollView(context);
        TextView code = new TextView(context);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        code.setTextColor(0xFFE6E6E6);
        code.setPadding(dp(8), dp(8), dp(8), dp(8));
        code.setTextIsSelectable(true);
        hScroll.addView(code);
        vScroll.addView(hScroll);
        root.addView(vScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, (int) (AndroidUtilities.displaySize.y * 0.62f / AndroidUtilities.density)));

        TextView close = button("Закрыть", Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_buttonText));
        close.setOnClickListener(v -> s.dismiss());
        root.addView(close, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, 0, 10, 0, 0));

        final int[] state = {startLayer, 0}; // layer, wrap
        final int[][] lineStarts = new int[1][];
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            int layer = state[0];
            String src = layerCode(layer);
            String[] lines = src.split("\n", -1);
            int max = Math.min(lines.length, 8000);
            HashMap<Integer, String> marks = new HashMap<>();
            JSONArray findings = scan.optJSONArray("findings");
            chipRow.removeAllViews();
            int count = 0;
            if (findings != null) {
                for (int i = 0; i < findings.length(); i++) {
                    JSONObject f = findings.optJSONObject(i);
                    if (f.optInt("layer") != layer) {
                        continue;
                    }
                    count++;
                    JSONArray ls = f.optJSONArray("lines");
                    for (int k = 0; ls != null && k < ls.length(); k++) {
                        int ln = ls.optInt(k);
                        String prev = marks.get(ln);
                        marks.put(ln, prev == null ? f.optString("id") : prev + ", " + f.optString("id"));
                    }
                }
                TextView allChip = pill("☰ Все (" + count + ")", true);
                allChip.setOnClickListener(v -> vScroll.smoothScrollTo(0, 0));
                chipRow.addView(allChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 6, 0));
                for (int i = 0; i < findings.length(); i++) {
                    JSONObject f = findings.optJSONObject(i);
                    if (f.optInt("layer") != layer) {
                        continue;
                    }
                    boolean crit = "CRITICAL".equals(f.optString("severity"));
                    int line = f.optInt("line");
                    TextView chip = pill((line > 0 ? "L" + line + "  " : "⚠ ") + f.optString("title"), false);
                    chip.setTextColor(crit ? RED : ORANGE);
                    chip.setBackground(round(Theme.multAlpha(crit ? RED : ORANGE, 0.12f), Theme.multAlpha(crit ? RED : ORANGE, 0.5f), 17));
                    chip.setOnClickListener(v -> scrollToLine(vScroll, code, lineStarts[0], line));
                    chipRow.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 6, 0));
                }
            }
            SpannableStringBuilder sb = new SpannableStringBuilder();
            int width = String.valueOf(max).length();
            int[] starts = new int[max + 2];
            for (int i = 0; i < max; i++) {
                int ln = i + 1;
                starts[ln] = sb.length();
                String num = String.format(java.util.Locale.US, "%" + width + "d  ", ln);
                int a = sb.length();
                sb.append(num);
                String mark = marks.get(ln);
                sb.setSpan(new ForegroundColorSpan(mark != null ? RED : 0xFF6B7280), a, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                String l = lines[i];
                if (l.length() > 600) {
                    l = l.substring(0, 600) + " …[+" + (lines[i].length() - 600) + "]";
                }
                sb.append(l);
                if (mark != null) {
                    int m = sb.length();
                    sb.append("  [").append(mark).append("]");
                    sb.setSpan(new ForegroundColorSpan(0xFFFF6B6B), m, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new BackgroundColorSpan(0x55B91C1C), a, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (i < max - 1) {
                    sb.append("\n");
                }
            }
            if (lines.length > max) {
                sb.append("\n… [пропущено строк: ").append(String.valueOf(lines.length - max)).append("]");
            }
            lineStarts[0] = starts;
            code.setText(sb);
            ViewGroup.LayoutParams lp = code.getLayoutParams();
            lp.width = state[1] == 1 ? vScroll.getWidth() > 0 ? vScroll.getWidth() : AndroidUtilities.displaySize.x - dp(24) : ViewGroup.LayoutParams.WRAP_CONTENT;
            code.setLayoutParams(lp);
            code.setHorizontallyScrolling(state[1] == 0);
            wrapBtn.setTextColor(state[1] == 1 ? Theme.getColor(Theme.key_dialogTextBlue2) : textColor());
            layerRow.removeAllViews();
            for (int k = 0; k <= layerCount; k++) {
                final int idx = k;
                String name = k == 0 ? "Исходник" : layers.optJSONObject(k - 1).optString("title", "Слой " + k)
                        + " (из стр. " + layers.optJSONObject(k - 1).optInt("line") + ")";
                TextView p = pill(name, k == layer);
                p.setOnClickListener(v -> {
                    state[0] = idx;
                    render[0].run();
                    vScroll.scrollTo(0, 0);
                });
                layerRow.addView(p, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 6, 0));
            }
        };
        wrapBtn.setOnClickListener(v -> {
            state[1] = 1 - state[1];
            render[0].run();
        });
        copyBtn.setOnClickListener(v -> {
            AndroidUtilities.addToClipboard(layerCode(state[0]));
            BulletinFactory.of(s.getContainer(), null).createCopyBulletin("Код скопирован").show();
        });
        s.setCustomView(root);
        s.show();
        render[0].run();
        if (startLine > 0) {
            code.postDelayed(() -> scrollToLine(vScroll, code, lineStarts[0], startLine), 250);
        }
    }

    private TextView pill(String s, boolean selected) {
        TextView t = text(s, 14, selected ? Theme.getColor(Theme.key_dialogTextBlue2) : textColor(), true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(14), 0, dp(14), 0);
        t.setBackground(round(selected ? Theme.multAlpha(Theme.getColor(Theme.key_dialogTextBlue2), 0.15f) : 0,
                Theme.multAlpha(textColor(), 0.3f), 17));
        return t;
    }

    private static void scrollToLine(ScrollView vScroll, TextView code, int[] starts, int line) {
        if (starts == null || line <= 0 || line >= starts.length || code.getLayout() == null) {
            return;
        }
        int l = code.getLayout().getLineForOffset(starts[line]);
        int y = code.getLayout().getLineTop(l) + code.getPaddingTop() - dp(40);
        vScroll.smoothScrollTo(0, Math.max(0, y));
    }
}
