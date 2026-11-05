package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * sGram: local names for users, bots, groups and channels (port of the exteraGram plugin
 * "Local Contact Override" by Daxo-Developer & @Daxo_OS). Overrides never leave the device.
 *
 * Unlike the plugin, the override is applied to the user/chat objects as they enter
 * MessagesController (putUser / putChat), so the local name shows up everywhere: chat list,
 * chat header, profile, notifications, search. The original values are remembered from fresh
 * server data and restored on reset.
 */
public final class SgramLocalNames {

    public static final String PREF_ENABLED = "local_names_enabled";
    private static final String PREFS = "sgram_local_names";

    public static final class Override {
        public long dialogId;
        public String first, last, username, title;           // local values, null = keep original
        public String origFirst, origLast, origUsername, origTitle;

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", dialogId);
            put(o, "first", first);
            put(o, "last", last);
            put(o, "username", username);
            put(o, "title", title);
            put(o, "orig_first", origFirst);
            put(o, "orig_last", origLast);
            put(o, "orig_username", origUsername);
            put(o, "orig_title", origTitle);
            return o;
        }

        static Override fromJson(JSONObject o) {
            Override r = new Override();
            r.dialogId = o.optLong("id");
            r.first = opt(o, "first");
            r.last = opt(o, "last");
            r.username = opt(o, "username");
            r.title = opt(o, "title");
            r.origFirst = opt(o, "orig_first");
            r.origLast = opt(o, "orig_last");
            r.origUsername = opt(o, "orig_username");
            r.origTitle = opt(o, "orig_title");
            return r;
        }

        private static void put(JSONObject o, String key, String value) throws Exception {
            if (value != null) {
                o.put(key, value);
            }
        }

        private static String opt(JSONObject o, String key) {
            return o.has(key) && !o.isNull(key) ? o.optString(key) : null;
        }

        public String localName() {
            if (dialogId > 0) {
                return ContactsController.formatName(first != null ? first : origFirst, last != null ? last : origLast);
            }
            return title != null ? title : origTitle;
        }

        public String originalName() {
            if (dialogId > 0) {
                return ContactsController.formatName(origFirst, origLast);
            }
            return origTitle;
        }
    }

    private static final HashMap<Long, Override> overrides = new HashMap<>();
    private static final Map<Object, Boolean> patched = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile boolean loaded;
    private static volatile boolean enabled;

    private SgramLocalNames() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (overrides) {
            if (loaded) {
                return;
            }
            enabled = ApplicationLoader.applicationContext.getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE).getBoolean(PREF_ENABLED, true);
            for (Map.Entry<String, ?> e : prefs().getAll().entrySet()) {
                try {
                    Override o = Override.fromJson(new JSONObject(String.valueOf(e.getValue())));
                    overrides.put(o.dialogId, o);
                } catch (Exception ignore) {
                }
            }
            loaded = true;
        }
    }

    private static void save(Override o) {
        try {
            prefs().edit().putString(String.valueOf(o.dialogId), o.toJson().toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static boolean isEnabled() {
        ensureLoaded();
        return enabled;
    }

    public static boolean has(long dialogId) {
        ensureLoaded();
        synchronized (overrides) {
            return overrides.containsKey(dialogId);
        }
    }

    public static Override get(long dialogId) {
        ensureLoaded();
        synchronized (overrides) {
            return overrides.get(dialogId);
        }
    }

    public static ArrayList<Override> getAll() {
        ensureLoaded();
        synchronized (overrides) {
            return new ArrayList<>(overrides.values());
        }
    }

    // ---- applying to objects (called from MessagesController) ----

    /** Called for every user entering MessagesController. fromCache objects may already carry local values. */
    public static void apply(TLRPC.User user, boolean fromCache) {
        if (user == null) {
            return;
        }
        ensureLoaded();
        Override o;
        synchronized (overrides) {
            o = overrides.get(user.id);
        }
        if (o == null) {
            return;
        }
        if (!fromCache && patched.get(user) == null) {
            // fresh object from the server: remember the real values
            boolean changed = !TextUtils.equals(o.origFirst, user.first_name) || !TextUtils.equals(o.origLast, user.last_name) || !TextUtils.equals(o.origUsername, publicUsername(user));
            if (changed) {
                o.origFirst = user.first_name;
                o.origLast = user.last_name;
                o.origUsername = publicUsername(user);
                save(o);
            }
        }
        if (!enabled) {
            return;
        }
        if (o.first != null) {
            user.first_name = o.first;
        }
        if (o.last != null) {
            user.last_name = o.last;
        }
        if (o.username != null) {
            setUsername(user, o.username);
        }
        patched.put(user, Boolean.TRUE);
    }

    public static void apply(TLRPC.Chat chat, boolean fromCache) {
        if (chat == null) {
            return;
        }
        ensureLoaded();
        Override o;
        synchronized (overrides) {
            o = overrides.get(-chat.id);
        }
        if (o == null) {
            return;
        }
        if (!fromCache && patched.get(chat) == null && !chat.min) {
            boolean changed = !TextUtils.equals(o.origTitle, chat.title) || !TextUtils.equals(o.origUsername, publicUsername(chat));
            if (changed) {
                o.origTitle = chat.title;
                o.origUsername = publicUsername(chat);
                save(o);
            }
        }
        if (!enabled) {
            return;
        }
        if (o.title != null) {
            chat.title = o.title;
        }
        if (o.username != null) {
            setUsername(chat, o.username);
        }
        patched.put(chat, Boolean.TRUE);
    }

    /** updateUserName changed the live object in place: remember the server values and patch again. */
    public static void onUserNameUpdate(TLRPC.User user, String first, String last, String username) {
        if (user == null || !has(user.id)) {
            return;
        }
        Override o = get(user.id);
        o.origFirst = first;
        o.origLast = last;
        o.origUsername = username;
        save(o);
        patched.remove(user);
        apply(user, true);
    }

    private static String publicUsername(TLRPC.User user) {
        if (user.usernames != null) {
            for (TLRPC.TL_username u : user.usernames) {
                if (u != null && u.active && !TextUtils.isEmpty(u.username)) {
                    return u.username;
                }
            }
        }
        return user.username;
    }

    private static String publicUsername(TLRPC.Chat chat) {
        if (chat.usernames != null) {
            for (TLRPC.TL_username u : chat.usernames) {
                if (u != null && u.active && !TextUtils.isEmpty(u.username)) {
                    return u.username;
                }
            }
        }
        return chat.username;
    }

    private static ArrayList<TLRPC.TL_username> replaceFirstActive(ArrayList<TLRPC.TL_username> list, String username) {
        ArrayList<TLRPC.TL_username> copy = new ArrayList<>();
        boolean done = false;
        for (TLRPC.TL_username u : list) {
            if (u == null) {
                continue;
            }
            TLRPC.TL_username c = new TLRPC.TL_username();
            c.flags = u.flags;
            c.editable = u.editable;
            c.active = u.active;
            c.username = u.username;
            if (!done && u.active) {
                c.username = username;
                done = true;
            }
            copy.add(c);
        }
        return copy;
    }

    private static void setUsername(TLRPC.User user, String username) {
        if (user.usernames != null && !user.usernames.isEmpty()) {
            user.usernames = replaceFirstActive(user.usernames, username);
        }
        user.username = username;
        user.flags |= 8;
    }

    private static void setUsername(TLRPC.Chat chat, String username) {
        if (chat.usernames != null && !chat.usernames.isEmpty()) {
            chat.usernames = replaceFirstActive(chat.usernames, username);
        }
        chat.username = username;
        chat.flags |= 64;
    }

    // ---- editing ----

    public static void set(int account, long dialogId, String first, String last, String username, String title) {
        ensureLoaded();
        MessagesController mc = MessagesController.getInstance(account);
        Override o;
        synchronized (overrides) {
            o = overrides.get(dialogId);
            if (o == null) {
                o = new Override();
                o.dialogId = dialogId;
                if (dialogId > 0) {
                    TLRPC.User user = mc.getUser(dialogId);
                    if (user != null) {
                        o.origFirst = user.first_name;
                        o.origLast = user.last_name;
                        o.origUsername = publicUsername(user);
                    }
                } else {
                    TLRPC.Chat chat = mc.getChat(-dialogId);
                    if (chat != null) {
                        o.origTitle = chat.title;
                        o.origUsername = publicUsername(chat);
                    }
                }
                overrides.put(dialogId, o);
            }
        }
        o.first = emptyToNull(first);
        o.last = last != null ? last.trim() : null;
        o.username = emptyToNull(username != null ? username.replace("@", "") : null);
        o.title = emptyToNull(title);
        if (o.first == null && o.last == null && o.username == null && o.title == null) {
            reset(account, dialogId);
            return;
        }
        save(o);
        restoreLive(o);
        applyLive(o);
        refresh(account, dialogId);
    }

    public static void reset(int account, long dialogId) {
        Override o;
        synchronized (overrides) {
            o = overrides.remove(dialogId);
        }
        prefs().edit().remove(String.valueOf(dialogId)).apply();
        if (o != null) {
            restoreLive(o);
        }
        refresh(account, dialogId);
    }

    public static void resetAll() {
        ArrayList<Override> all = getAll();
        for (Override o : all) {
            reset(UserConfig.selectedAccount, o.dialogId);
        }
    }

    public static void setEnabled(boolean value) {
        ensureLoaded();
        ApplicationLoader.applicationContext.getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, value).apply();
        for (Override o : getAll()) {
            if (value) {
                enabled = true;
                applyLive(o);
            } else {
                restoreLive(o);
            }
        }
        enabled = value;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                refresh(a, 0);
            }
        }
    }

    private static String emptyToNull(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    // all accounts share the override: patch the objects every account holds
    private static void applyLive(Override o) {
        if (!enabled) {
            return;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                continue;
            }
            MessagesController mc = MessagesController.getInstance(a);
            if (o.dialogId > 0) {
                TLRPC.User user = mc.getUser(o.dialogId);
                if (user != null) {
                    patched.remove(user);
                    apply(user, true);
                }
            } else {
                TLRPC.Chat chat = mc.getChat(-o.dialogId);
                if (chat != null) {
                    patched.remove(chat);
                    apply(chat, true);
                }
            }
        }
    }

    private static void restoreLive(Override o) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                continue;
            }
            MessagesController mc = MessagesController.getInstance(a);
            if (o.dialogId > 0) {
                TLRPC.User user = mc.getUser(o.dialogId);
                if (user != null && patched.remove(user) != null) {
                    user.first_name = o.origFirst;
                    user.last_name = o.origLast;
                    if (o.username != null) {
                        setUsername(user, o.origUsername);
                        if (o.origUsername == null) {
                            user.flags &= ~8;
                        }
                    }
                }
            } else {
                TLRPC.Chat chat = mc.getChat(-o.dialogId);
                if (chat != null && patched.remove(chat) != null) {
                    chat.title = o.origTitle;
                    if (o.username != null) {
                        setUsername(chat, o.origUsername);
                        if (o.origUsername == null) {
                            chat.flags &= ~64;
                        }
                    }
                }
            }
        }
    }

    private static void refresh(int account, long dialogId) {
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (!UserConfig.getInstance(a).isClientActivated()) {
                    continue;
                }
                NotificationCenter nc = NotificationCenter.getInstance(a);
                nc.postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME | MessagesController.UPDATE_MASK_CHAT_NAME | MessagesController.UPDATE_MASK_CHAT);
                nc.postNotificationName(NotificationCenter.dialogsNeedReload);
            }
        });
    }

    // ---- UI ----

    private static EditTextBoldCursor field(Activity activity, String hint, String value) {
        EditTextBoldCursor input = new EditTextBoldCursor(activity);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        input.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        input.setSingleLine();
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint(hint);
        if (value != null) {
            input.setText(value);
        }
        return input;
    }

    /** «Local Name» from the profile menu. */
    public static void showEditDialog(BaseFragment fragment, long dialogId) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        int account = fragment.getCurrentAccount();
        MessagesController mc = MessagesController.getInstance(account);
        Override o = get(dialogId);
        boolean isUser = dialogId > 0;
        String origFirst, origLast, origTitle, origUsername;
        if (o != null) {
            origFirst = o.origFirst;
            origLast = o.origLast;
            origTitle = o.origTitle;
            origUsername = o.origUsername;
        } else if (isUser) {
            TLRPC.User user = mc.getUser(dialogId);
            if (user == null) {
                return;
            }
            origFirst = user.first_name;
            origLast = user.last_name;
            origTitle = null;
            origUsername = publicUsername(user);
        } else {
            TLRPC.Chat chat = mc.getChat(-dialogId);
            if (chat == null) {
                return;
            }
            origFirst = origLast = null;
            origTitle = chat.title;
            origUsername = publicUsername(chat);
        }

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        TextView original = new TextView(activity);
        original.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        original.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        String origName = isUser ? ContactsController.formatName(origFirst, origLast) : origTitle;
        original.setText("Original: " + (origName != null ? origName : "") + (!TextUtils.isEmpty(origUsername) ? "  @" + origUsername : "")
                + "\nOnly you see the local name, it is never sent to Telegram.");
        layout.addView(original, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 8));

        EditTextBoldCursor firstField = null, lastField = null, titleField = null;
        if (isUser) {
            firstField = field(activity, "First name", o != null && o.first != null ? o.first : origFirst);
            lastField = field(activity, "Last name", o != null && o.last != null ? o.last : origLast);
            layout.addView(firstField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, Gravity.TOP, 24, 6, 24, 0));
            layout.addView(lastField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, Gravity.TOP, 24, 6, 24, 0));
        } else {
            titleField = field(activity, "Title", o != null && o.title != null ? o.title : origTitle);
            layout.addView(titleField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, Gravity.TOP, 24, 6, 24, 0));
        }
        EditTextBoldCursor usernameField = field(activity, "Username", o != null && o.username != null ? o.username : origUsername);
        usernameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        layout.addView(usernameField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, Gravity.TOP, 24, 6, 24, 0));

        final EditTextBoldCursor f1 = firstField, f2 = lastField, f3 = titleField;
        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle("Local Name");
        b.setView(layout);
        b.setPositiveButton("Save", (d, w) -> {
            // a field equal to the original means "keep the original"; the last name may be emptied
            String first = f1 != null ? diff(f1.getText().toString(), origFirst) : null;
            String last = null;
            if (f2 != null) {
                last = f2.getText().toString().trim();
                if (last.equals(origLast == null ? "" : origLast)) {
                    last = null;
                }
            }
            String title = f3 != null ? diff(f3.getText().toString(), origTitle) : null;
            String username = diff(usernameField.getText().toString().replace("@", ""), origUsername);
            set(account, dialogId, first, last, username, title);
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, "Local name saved").show();
        });
        if (o != null) {
            b.setNeutralButton("Reset", (d, w) -> {
                reset(account, dialogId);
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, "Original name restored").show();
            });
        }
        b.setNegativeButton("Cancel", null);
        fragment.showDialog(b.create());
    }

    private static String diff(String value, String original) {
        value = value == null ? "" : value.trim();
        if (value.isEmpty() || value.equals(original)) {
            return null;
        }
        return value;
    }

    // ---- export / import (clipboard) ----

    public static String exportJson() {
        org.json.JSONArray array = new org.json.JSONArray();
        for (Override o : getAll()) {
            try {
                array.put(o.toJson());
            } catch (Exception ignore) {
            }
        }
        return array.toString();
    }

    /** @return number of imported overrides, or -1 if the text is not an export */
    public static int importJson(int account, String json) {
        try {
            org.json.JSONArray array = new org.json.JSONArray(json.trim());
            int count = 0;
            for (int i = 0; i < array.length(); i++) {
                Override in = Override.fromJson(array.getJSONObject(i));
                if (in.dialogId == 0) {
                    continue;
                }
                Override o;
                synchronized (overrides) {
                    o = overrides.get(in.dialogId);
                    if (o == null) {
                        overrides.put(in.dialogId, o = in);
                    } else {
                        o.first = in.first;
                        o.last = in.last;
                        o.username = in.username;
                        o.title = in.title;
                    }
                }
                save(o);
                applyLive(o);
                count++;
            }
            refresh(account, 0);
            return count;
        } catch (Exception e) {
            return -1;
        }
    }
}
