"""exteraGram plugin SDK: client_utils (sGram implementation)."""

import contextlib
import threading
import traceback

from java import dynamic_proxy, jclass, jint, jlong

_Utilities = jclass("org.telegram.messenger.Utilities")
_UserConfig = jclass("org.telegram.messenger.UserConfig")
_AccountInstance = jclass("org.telegram.messenger.AccountInstance")
_ConnectionsManager = jclass("org.telegram.tgnet.ConnectionsManager")
_RequestDelegate = jclass("org.telegram.tgnet.RequestDelegate")
_NotificationCenter = jclass("org.telegram.messenger.NotificationCenter")
_MediaController = jclass("org.telegram.messenger.MediaController")
_SgramPlugins = jclass("org.telegram.messenger.SgramPlugins")
_Api = jclass("org.telegram.messenger.SgramPluginApi")
_ArrayList = jclass("java.util.ArrayList")
_HashMap = jclass("java.util.HashMap")

STAGE_QUEUE = "stageQueue"
GLOBAL_QUEUE = "globalQueue"
CACHE_CLEAR_QUEUE = "cacheClearQueue"
SEARCH_QUEUE = "searchQueue"
PHONE_BOOK_QUEUE = "phoneBookQueue"
THEME_QUEUE = "themeQueue"
EXTERNAL_NETWORK_QUEUE = "externalNetworkQueue"
PLUGINS_QUEUE = "pluginsQueue"

_scope = threading.local()


def get_selected_account():
    return int(_UserConfig.selectedAccount)


def get_hook_account():
    return getattr(_scope, "account", None)


@contextlib.contextmanager
def account_scope(account):
    previous = getattr(_scope, "account", None)
    _scope.account = None if account is None else int(account)
    try:
        yield
    finally:
        _scope.account = previous


def _account(account=None):
    if account is not None:
        return int(account)
    hooked = get_hook_account()
    return hooked if hooked is not None else get_selected_account()


def get_queue_by_name(queue_name):
    if queue_name == PLUGINS_QUEUE:
        return _SgramPlugins.getPluginsQueue()
    try:
        return getattr(_Utilities, queue_name)
    except Exception:
        return None


def run_on_queue(fn, queue_name=PLUGINS_QUEUE, delay=0):
    from android_utils import R
    queue = get_queue_by_name(queue_name) or _SgramPlugins.getPluginsQueue()
    account = get_hook_account()

    def task():
        with account_scope(account):
            fn()
    queue.postRunnable(R(task), jlong(int(delay or 0)))


class RequestCallback(dynamic_proxy(_RequestDelegate)):
    def __init__(self, fn, account=None):
        super().__init__()
        self.fn = fn
        self.account = account

    def run(self, response, error):
        try:
            with account_scope(self.account):
                self.fn(response, error)
        except Exception:
            traceback.print_exc()


def send_request(request, fn, *, account=None):
    acc = _account(account)
    return int(_ConnectionsManager.getInstance(acc).sendRequest(request, RequestCallback(fn, acc)))


def get_last_fragment():
    return _Api.getLastFragment()


def get_account_instance(account=None):
    return _AccountInstance.getInstance(_account(account))


def get_messages_controller(account=None):
    return get_account_instance(account).getMessagesController()


def get_contacts_controller(account=None):
    return get_account_instance(account).getContactsController()


def get_media_data_controller(account=None):
    return get_account_instance(account).getMediaDataController()


def get_connections_manager(account=None):
    return get_account_instance(account).getConnectionsManager()


def get_location_controller(account=None):
    return get_account_instance(account).getLocationController()


def get_notifications_controller(account=None):
    return get_account_instance(account).getNotificationsController()


def get_messages_storage(account=None):
    return get_account_instance(account).getMessagesStorage()


def get_send_messages_helper(account=None):
    return get_account_instance(account).getSendMessagesHelper()


def get_file_loader(account=None):
    return get_account_instance(account).getFileLoader()


def get_secret_chat_helper(account=None):
    return get_account_instance(account).getSecretChatHelper()


def get_download_controller(account=None):
    return get_account_instance(account).getDownloadController()


def get_notifications_settings(account=None):
    return get_account_instance(account).getNotificationsSettings()


def get_notification_center(account=None):
    return get_account_instance(account).getNotificationCenter()


def get_media_controller():
    return _MediaController.getInstance()


def get_user_config(account=None):
    return get_account_instance(account).getUserConfig()


# ---------------------------------------------------------------- sending

def _parse(text, parse_mode):
    if parse_mode is None or text is None:
        return text, None
    mode = str(parse_mode).lower()
    if mode not in ("html", "markdown"):
        raise ValueError("Unsupported parse_mode: %s (use HTML or Markdown)" % parse_mode)
    from extera_utils.text_formatting import parse_text
    parsed = parse_text(text, "HTML" if mode == "html" else "Markdown")
    entities = _ArrayList()
    for e in parsed["entities"]:
        entities.add(e.to_tlrpc_object())
    return parsed["message"], entities


def _reply(acc, peer, reply):
    if reply is None or reply == 0:
        return None
    if isinstance(reply, int):
        return _Api.replyStub(jint(acc), jlong(peer), jint(reply))
    return reply


def _to_java(value):
    if isinstance(value, dict):
        m = _HashMap()
        for k, v in value.items():
            m.put(str(k), str(v))
        return m
    if isinstance(value, (list, tuple)):
        a = _ArrayList()
        for v in value:
            a.add(v)
        return a
    return value


def send_message(params, parse_mode=None, *, account=None):
    acc = _account(account)
    params = dict(params)
    peer = int(params.pop("peer"))
    message = params.pop("message", None)
    caption = params.pop("caption", None)
    message, entities = _parse(message, parse_mode)
    if caption is not None:
        caption, cap_entities = _parse(caption, parse_mode)
        entities = cap_entities if entities is None else entities
    p = _Api.newParams(message, jlong(peer))
    if caption is not None:
        p.caption = caption
    if entities is not None:
        p.entities = entities
    if "entities" in params and params["entities"] is not None:
        p.entities = _to_java(params.pop("entities"))
    if "replyToMsg" in params:
        p.replyToMsg = _reply(acc, peer, params.pop("replyToMsg"))
    if "replyToTopMsg" in params:
        p.replyToTopMsg = _reply(acc, peer, params.pop("replyToTopMsg"))
    for key, value in params.items():
        if key == "params":
            value = _to_java(value)
        try:
            setattr(p, key, value)
        except Exception:
            traceback.print_exc()
    _Api.sendMessage(jint(acc), p)


def send_text(peer, text, *, account=None, parse_mode=None, **kwargs):
    kwargs.update({"peer": peer, "message": text})
    send_message(kwargs, parse_mode, account=account)


def _send_media(kind, peer, file_path, caption, high_quality, account, parse_mode, kwargs):
    acc = _account(account)
    caption, entities = _parse(caption or "", parse_mode)
    reply = _reply(acc, int(peer), kwargs.get("replyToMsg"))
    _Api.sendMedia(jint(acc), jlong(int(peer)), str(file_path), caption or None, entities, reply, jint(kind),
                   bool(high_quality or kwargs.get("sendingHighQuality", False)), bool(kwargs.get("notify", True)),
                   jint(int(kwargs.get("scheduleDate", 0) or 0)), bool(kwargs.get("hasMediaSpoilers", False)))


def send_photo(peer, file_path, caption="", high_quality=False, *, account=None, parse_mode=None, **kwargs):
    _send_media(_Api.MEDIA_PHOTO, peer, file_path, caption, high_quality, account, parse_mode, kwargs)


def send_document(peer, file_path, caption="", *, account=None, parse_mode=None, **kwargs):
    _send_media(_Api.MEDIA_DOCUMENT, peer, file_path, caption, False, account, parse_mode, kwargs)


def send_video(peer, file_path, caption="", *, account=None, parse_mode=None, **kwargs):
    _send_media(_Api.MEDIA_VIDEO, peer, file_path, caption, False, account, parse_mode, kwargs)


def send_audio(peer, file_path, caption="", *, account=None, parse_mode=None, **kwargs):
    _send_media(_Api.MEDIA_AUDIO, peer, file_path, caption, False, account, parse_mode, kwargs)


def edit_message(message_obj, text=None, file_path=None, with_spoiler=False, *, account=None, parse_mode=None, **kwargs):
    acc = _account(account if account is not None else getattr(message_obj, "currentAccount", None))
    entities = None
    if text is not None:
        text, entities = _parse(text, parse_mode)
        if entities is None:
            entities = _ArrayList()
    _Api.editMessage(jint(acc), message_obj, text, entities, file_path, bool(with_spoiler))


class AccountClient:
    def __init__(self, account):
        self.account = int(account)

    def __eq__(self, other):
        return isinstance(other, AccountClient) and other.account == self.account

    def __hash__(self):
        return hash(self.account)

    def __repr__(self):
        return "AccountClient(%d)" % self.account

    def get_account_instance(self):
        return get_account_instance(self.account)

    def get_messages_controller(self):
        return get_messages_controller(self.account)

    def get_contacts_controller(self):
        return get_contacts_controller(self.account)

    def get_media_data_controller(self):
        return get_media_data_controller(self.account)

    def get_connections_manager(self):
        return get_connections_manager(self.account)

    def get_location_controller(self):
        return get_location_controller(self.account)

    def get_notifications_controller(self):
        return get_notifications_controller(self.account)

    def get_messages_storage(self):
        return get_messages_storage(self.account)

    def get_send_messages_helper(self):
        return get_send_messages_helper(self.account)

    def get_file_loader(self):
        return get_file_loader(self.account)

    def get_secret_chat_helper(self):
        return get_secret_chat_helper(self.account)

    def get_download_controller(self):
        return get_download_controller(self.account)

    def get_notifications_settings(self):
        return get_notifications_settings(self.account)

    def get_notification_center(self):
        return get_notification_center(self.account)

    def get_user_config(self):
        return get_user_config(self.account)

    def send_request(self, request, fn):
        return send_request(request, fn, account=self.account)

    def send_message(self, params, parse_mode=None):
        return send_message(params, parse_mode, account=self.account)

    def send_text(self, peer, text, *, parse_mode=None, **kwargs):
        return send_text(peer, text, account=self.account, parse_mode=parse_mode, **kwargs)

    def send_photo(self, peer, file_path, caption="", high_quality=False, *, parse_mode=None, **kwargs):
        return send_photo(peer, file_path, caption, high_quality, account=self.account, parse_mode=parse_mode, **kwargs)

    def send_document(self, peer, file_path, caption="", *, parse_mode=None, **kwargs):
        return send_document(peer, file_path, caption, account=self.account, parse_mode=parse_mode, **kwargs)

    def send_video(self, peer, file_path, caption="", *, parse_mode=None, **kwargs):
        return send_video(peer, file_path, caption, account=self.account, parse_mode=parse_mode, **kwargs)

    def send_audio(self, peer, file_path, caption="", *, parse_mode=None, **kwargs):
        return send_audio(peer, file_path, caption, account=self.account, parse_mode=parse_mode, **kwargs)

    def edit_message(self, message_obj, text=None, file_path=None, with_spoiler=False, *, parse_mode=None, **kwargs):
        return edit_message(message_obj, text, file_path, with_spoiler, account=self.account, parse_mode=parse_mode, **kwargs)


def get_client(account=None):
    return AccountClient(_account(account))


class NotificationCenterDelegate(dynamic_proxy(_NotificationCenter.NotificationCenterDelegate)):
    def __init__(self):
        super().__init__()

    def didReceivedNotification(self, id, account, args):
        pass
