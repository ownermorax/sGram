package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

/**
 * sGram: helpers behind the plugin SDK (client_utils, ui.bulletin). Telegram's internal signatures
 * change between versions, so Python only calls these stable wrappers.
 */
public final class SgramPluginApi {

    public static final int MEDIA_PHOTO = 0;
    public static final int MEDIA_VIDEO = 1;
    public static final int MEDIA_DOCUMENT = 2;
    public static final int MEDIA_AUDIO = 3;

    private SgramPluginApi() {
    }

    public static BaseFragment getLastFragment() {
        return LaunchActivity.getLastFragment();
    }

    public static SendMessagesHelper.SendMessageParams newParams(String message, long peer) {
        return SendMessagesHelper.SendMessageParams.of(message, peer);
    }

    /** A stand-in MessageObject that is enough for a reply header. */
    public static MessageObject replyStub(int account, long peer, int messageId) {
        if (messageId == 0) {
            return null;
        }
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = messageId;
        message.peer_id = MessagesController.getInstance(account).getPeer(peer);
        message.dialog_id = peer;
        message.message = "";
        return new MessageObject(account, message, false, false);
    }

    public static void sendMessage(int account, SendMessagesHelper.SendMessageParams params) {
        AndroidUtilities.runOnUIThread(() -> SendMessagesHelper.getInstance(account).sendMessage(params));
    }

    public static void sendMedia(int account, long peer, String path, String caption, ArrayList<TLRPC.MessageEntity> entities,
                                 MessageObject replyTo, int kind, boolean highQuality, boolean notify, int scheduleDate, boolean spoiler) {
        AndroidUtilities.runOnUIThread(() -> {
            AccountInstance instance = AccountInstance.getInstance(account);
            if (kind == MEDIA_DOCUMENT || kind == MEDIA_AUDIO) {
                ArrayList<String> paths = new ArrayList<>();
                paths.add(path);
                SendMessagesHelper.prepareSendingDocuments(instance, paths, paths, null, caption, entities, null, peer,
                        replyTo, null, null, null, null, notify, scheduleDate, 0, null, SendMessageChatArguments.EMPTY, 0, false, 0, 0, null);
                return;
            }
            SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
            info.path = path;
            info.caption = caption;
            info.entities = entities;
            info.isVideo = kind == MEDIA_VIDEO;
            info.highQuality = highQuality;
            info.hasMediaSpoilers = spoiler;
            ArrayList<SendMessagesHelper.SendingMediaInfo> media = new ArrayList<>();
            media.add(info);
            SendMessagesHelper.prepareSendingMedia(instance, media, peer, replyTo, null, null, null, false, false, null,
                    notify, scheduleDate, 0, 0, false, null, SendMessageChatArguments.EMPTY, 0, false, 0, 0, null);
        });
    }

    /** Edits text/caption; with filePath replaces the media with that image. */
    public static void editMessage(int account, MessageObject messageObject, String text, ArrayList<TLRPC.MessageEntity> entities, String filePath, boolean spoiler) {
        AndroidUtilities.runOnUIThread(() -> {
            SendMessagesHelper helper = SendMessagesHelper.getInstance(account);
            if (!TextUtils.isEmpty(filePath)) {
                TLRPC.TL_photo photo = helper.generatePhotoSizes(filePath, null);
                if (text != null) {
                    messageObject.editingMessage = text;
                    messageObject.editingMessageEntities = entities;
                }
                helper.editMessage(messageObject, photo, null, null, filePath, null, null, false, spoiler, null);
                return;
            }
            BaseFragment fragment = getLastFragment();
            helper.editMessage(messageObject, text, false, fragment, entities, 0, 0);
        });
    }

    // ---- bulletins ----

    private static BulletinFactory factory(BaseFragment fragment) {
        if (fragment == null) {
            fragment = getLastFragment();
        }
        return fragment != null ? BulletinFactory.of(fragment) : BulletinFactory.global();
    }

    public static void bulletin(int rawRes, CharSequence text, CharSequence subtitle, BaseFragment fragment) {
        AndroidUtilities.runOnUIThread(() -> {
            BulletinFactory f = factory(fragment);
            Bulletin b = subtitle != null ? f.createSimpleBulletin(rawRes, text, subtitle) : f.createSimpleBulletin(rawRes, text);
            safeShow(b);
        });
    }

    public static void bulletinWithButton(int rawRes, CharSequence text, CharSequence button, Runnable onClick, int duration, BaseFragment fragment) {
        AndroidUtilities.runOnUIThread(() -> safeShow(factory(fragment).createSimpleBulletin(rawRes, text, button, duration, onClick != null ? onClick : () -> {})));
    }

    public static void bulletinUndo(CharSequence text, CharSequence subtitle, Runnable onUndo, Runnable onAction, BaseFragment fragment) {
        AndroidUtilities.runOnUIThread(() -> {
            BulletinFactory f = factory(fragment);
            Runnable undo = onUndo != null ? onUndo : () -> {};
            Runnable action = onAction != null ? onAction : () -> {};
            Bulletin b = subtitle != null ? f.createUndoBulletin(text, subtitle, undo, action) : f.createUndoBulletin(text, undo, action);
            safeShow(b);
        });
    }

    public static void bulletinCopied(CharSequence text, BaseFragment fragment) {
        AndroidUtilities.runOnUIThread(() -> safeShow(factory(fragment).createCopyBulletin(text != null ? text.toString() : LocaleController.getString(R.string.TextCopied))));
    }

    /** The app can be in background (no window token) when a plugin shows a bulletin. */
    private static void safeShow(Bulletin b) {
        try {
            b.show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static int rawRes(String name) {
        return ApplicationLoader.applicationContext.getResources().getIdentifier(name, "raw", ApplicationLoader.applicationContext.getPackageName());
    }

    public static int drawableRes(String name) {
        return ApplicationLoader.applicationContext.getResources().getIdentifier(name, "drawable", ApplicationLoader.applicationContext.getPackageName());
    }
}
