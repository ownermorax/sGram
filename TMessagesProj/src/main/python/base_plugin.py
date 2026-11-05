"""exteraGram plugin SDK: base_plugin (sGram implementation)."""

import functools
import threading
import traceback
import uuid
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, List, Optional

from java import dynamic_proxy, jclass

_XposedBridge = jclass("de.robv.android.xposed.XposedBridge")
_PyMethodHook = jclass("org.telegram.messenger.SgramPyMethodHook")

_loading_plugin_id = None


@dataclass
class PluginMetadata:
    id: str
    name: str
    description: str
    author: str
    version: str
    icon: str
    min_version: str
    requirements: list


class HookStrategy(Enum):
    CANCEL = "CANCEL"
    MODIFY = "MODIFY"
    DEFAULT = "DEFAULT"
    MODIFY_FINAL = "MODIFY_FINAL"


class IntentHookType(Enum):
    BEFORE = 0
    AFTER = 1


@dataclass
class HookResult:
    strategy: HookStrategy = HookStrategy.DEFAULT
    request: Any = None
    response: Any = None
    update: Any = None
    updates: Any = None
    error: Any = None
    params: Any = None


class PluginError(Exception):
    def __init__(self, message, plugin_id=None):
        super().__init__(message)
        self.plugin_id = plugin_id


class AppEvent(Enum):
    START = "START"
    STOP = "STOP"
    PAUSE = "PAUSE"
    RESUME = "RESUME"


class MenuItemType(Enum):
    MESSAGE_CONTEXT_MENU = "MESSAGE_CONTEXT_MENU"
    DRAWER_MENU = "DRAWER_MENU"
    MAIN_MENU = "MAIN_MENU"
    CHAT_ACTION_MENU = "CHAT_ACTION_MENU"
    PROFILE_ACTION_MENU = "PROFILE_ACTION_MENU"


@dataclass
class MenuItemData:
    menu_type: MenuItemType
    text: str
    on_click: Callable
    item_id: Optional[str] = None
    icon: Optional[str] = None
    subtext: Optional[str] = None
    condition: Optional[str] = None
    priority: int = 0


# ---------------------------------------------------------------- Xposed hooks

class XposedHook(ABC):
    pass


class MethodReplacement(XposedHook):
    @abstractmethod
    def replace_hooked_method(self, param):
        ...


class MethodHook(XposedHook):
    def before_hooked_method(self, param):
        pass

    def after_hooked_method(self, param):
        pass


@dataclass
class HookFilterData:
    filter_type: str
    arg_index: Optional[int] = None
    or_filters: Any = None
    mvel_expression: Optional[str] = None
    instance_of: Any = None
    object: Any = None

    def matches(self, param):
        t = self.filter_type
        try:
            if t == "RESULT_IS_NULL":
                return param.getResult() is None
            if t == "RESULT_NOT_NULL":
                return param.getResult() is not None
            if t == "RESULT_IS_TRUE":
                return param.getResult() is True or param.getResult() == True  # noqa: E712
            if t == "RESULT_IS_FALSE":
                return param.getResult() is False or param.getResult() == False  # noqa: E712
            if t == "RESULT_INSTANCE_OF":
                return self.instance_of.isInstance(param.getResult())
            if t == "RESULT_EQUAL":
                return param.getResult() == self.object
            if t == "RESULT_NOT_EQUAL":
                return param.getResult() != self.object
            if t.startswith("ARG_"):
                arg = param.args[self.arg_index]
                if t == "ARG_IS_NULL":
                    return arg is None
                if t == "ARG_NOT_NULL":
                    return arg is not None
                if t == "ARG_IS_TRUE":
                    return arg is True or arg == True  # noqa: E712
                if t == "ARG_IS_FALSE":
                    return arg is False or arg == False  # noqa: E712
                if t == "ARG_INSTANCE_OF":
                    return self.instance_of.isInstance(arg)
                if t == "ARG_EQUAL":
                    return arg == self.object
                if t == "ARG_NOT_EQUAL":
                    return arg != self.object
            if t == "OR":
                return any(_filter_data(f).matches(param) for f in self.or_filters)
            if t == "CONDITION":
                return _eval_condition(self.mvel_expression, param, self.object)
        except Exception:
            traceback.print_exc()
            return False
        return True

    def to_java_filter(self):
        return self


def _eval_condition(expression, param, obj):
    """MVEL is not available; evaluate simple Java-like expressions in Python instead."""
    expr = expression
    for a, b in (("&&", " and "), ("||", " or "), ("!=", " __NE__ "), ("!", " not "), ("__NE__", "!="),
                 ("null", "None"), ("true", "True"), ("false", "False")):
        expr = expr.replace(a, b)
    import re
    expr = re.sub(r"(\S+)\s+instanceof\s+([\w.$]+)", r"_isinstance(\1, '\2')", expr)

    def _isinstance(value, class_name):
        try:
            return jclass("java.lang.Class").forName(class_name).isInstance(value)
        except Exception:
            return False
    env = {"param": param, "this": param.thisObject, "args": param.args, "object": obj,
           "result": param.getResult(), "_isinstance": _isinstance}
    return bool(eval(expr, {"__builtins__": {}}, env))


def _filter_data(f):
    return f.filter_data if isinstance(f, HookFilter) else f


class HookFilter(Enum):
    RESULT_IS_NULL = "RESULT_IS_NULL"
    RESULT_IS_TRUE = "RESULT_IS_TRUE"
    RESULT_IS_FALSE = "RESULT_IS_FALSE"
    RESULT_NOT_NULL = "RESULT_NOT_NULL"

    @property
    def filter_data(self):
        return HookFilterData(self.value)

    @staticmethod
    def ResultIsInstanceOf(clazz):
        return HookFilterData("RESULT_INSTANCE_OF", instance_of=clazz)

    @staticmethod
    def ResultEqual(value):
        return HookFilterData("RESULT_EQUAL", object=value)

    @staticmethod
    def ResultNotEqual(value):
        return HookFilterData("RESULT_NOT_EQUAL", object=value)

    @staticmethod
    def ArgumentIsNull(index):
        return HookFilterData("ARG_IS_NULL", arg_index=index)

    @staticmethod
    def ArgumentIsTrue(index):
        return HookFilterData("ARG_IS_TRUE", arg_index=index)

    @staticmethod
    def ArgumentIsFalse(index):
        return HookFilterData("ARG_IS_FALSE", arg_index=index)

    @staticmethod
    def ArgumentNotNull(index):
        return HookFilterData("ARG_NOT_NULL", arg_index=index)

    @staticmethod
    def ArgumentIsInstanceOf(index, clazz):
        return HookFilterData("ARG_INSTANCE_OF", arg_index=index, instance_of=clazz)

    @staticmethod
    def ArgumentEqual(index, value):
        return HookFilterData("ARG_EQUAL", arg_index=index, object=value)

    @staticmethod
    def ArgumentNotEqual(index, value):
        return HookFilterData("ARG_NOT_EQUAL", arg_index=index, object=value)

    @staticmethod
    def Condition(condition, object=None):
        return HookFilterData("CONDITION", mvel_expression=condition, object=object)

    @staticmethod
    def Or(*filters):
        return HookFilterData("OR", or_filters=list(filters))


def hook_filters(*filters):
    def decorator(fn):
        fn.__sg_filters__ = [_filter_data(f) for f in filters]
        return fn
    return decorator


def fn_hook_filters(field_name):
    def decorator(fn):
        fn.__sg_filters_field__ = field_name
        return fn
    return decorator


def _passes(fn, param, extra=None):
    filters = list(getattr(fn, "__sg_filters__", ()) or ())
    if extra:
        filters.extend(_filter_data(f) for f in extra)
    return all(f.matches(param) for f in filters)


class BaseHook(MethodHook):
    def __init__(self, plugin=None, *, before=None, after=None, before_filters=None, after_filters=None):
        self.plugin = plugin
        self.before = before
        self.after = after
        self.before_filters = before_filters or []
        self.after_filters = after_filters or []

    def before_hooked_method(self, param):
        if self.before is not None and _passes(self.before, param, self.before_filters):
            self.before(param)

    def after_hooked_method(self, param):
        if self.after is not None and _passes(self.after, param, self.after_filters):
            self.after(param)


class _HookCallbacks(dynamic_proxy(jclass("org.telegram.messenger.SgramPyMethodHook$Callbacks"))):
    """Java XC_MethodHook -> Python MethodHook / MethodReplacement."""

    def __init__(self, plugin, hook):
        super().__init__()
        self.plugin = plugin
        self.hook = hook
        self.replace = isinstance(hook, MethodReplacement)

    def _guard(self, fn, param):
        if self.plugin is not None and not self.plugin.enabled:
            return
        try:
            fn(param)
        except Exception:
            if self.plugin is not None:
                self.plugin._sg_log_exception()
            else:
                traceback.print_exc()

    def before(self, param):
        if self.replace:
            def run(p):
                p.setResult(self.hook.replace_hooked_method(p))
            self._guard(run, param)
        else:
            fn = self.hook.before_hooked_method
            if _passes(fn, param):
                self._guard(fn, param)

    def after(self, param):
        if not self.replace:
            fn = self.hook.after_hooked_method
            if _passes(fn, param):
                self._guard(fn, param)

    def isReplacement(self):
        return self.replace


def _while_plugin_active(fn):
    @functools.wraps(fn)
    def wrapper(self, *args, **kwargs):
        return fn(self, *args, **kwargs)
    return wrapper


# ---------------------------------------------------------------- BasePlugin

class BasePlugin:
    def __new__(cls, *args, **kwargs):
        self = super().__new__(cls)
        self._sg_init_state()
        return self

    def __init_subclass__(cls, **kwargs):
        super().__init_subclass__(**kwargs)

    def __init__(self):
        pass

    def _sg_init_state(self):
        if getattr(self, "_sg_ready", False):
            return
        self._sg_ready = True
        self.id = _loading_plugin_id or ""
        self.name = ""
        self.description = ""
        self.author = ""
        self.version = "1.0"
        self.min_version = ""
        self.requirements = []
        self.icon = None
        self.error_message = None
        self.enabled = False
        self.initialized = False
        self._sg_hooks = {}
        self._sg_send_hook = None
        self._sg_unhooks = []
        self._sg_menu_items = []
        self._sg_cleanups = []
        self._sg_stop = threading.Event()
        self._sg_logger = None

    def _sg_attach(self, rec):
        self._sg_init_state()
        meta = rec.meta
        self.id = meta["id"]
        self.name = meta["name"]
        self.description = meta["description"]
        self.author = meta["author"]
        self.version = meta["version"]
        self.icon = meta["icon"]
        self.min_version = meta["app_version"]
        self.requirements = meta["requirements"]

    def _sg_detach(self):
        self._sg_stop.set()
        for unhook in self._sg_unhooks:
            try:
                unhook.unhook()
            except Exception:
                pass
        self._sg_unhooks = []
        self._sg_hooks = {}
        self._sg_send_hook = None
        import sgram_engine
        for item_id in self._sg_menu_items:
            sgram_engine.unregister_menu_item(item_id)
        self._sg_menu_items = []
        for cleanup in reversed(self._sg_cleanups):
            try:
                cleanup()
            except Exception:
                traceback.print_exc()
        self._sg_cleanups = []
        import file_utils
        import intents
        file_utils.FilesController._release(self.id)
        intents.IntentsManager._release(self.id)

    def _sg_log_exception(self, already_logged=False):
        if not already_logged:
            import sgram_engine
            sgram_engine.add_log(self.id, "E " + traceback.format_exc())
        self.logger.error("Unhandled exception", exc_info=True)

    @property
    def stop_event(self):
        return self._sg_stop

    def register_cleanup(self, cleanup):
        self._sg_cleanups.append(cleanup)
        return cleanup

    # lifecycle / callbacks to override
    def on_plugin_load(self):
        pass

    def on_plugin_unload(self):
        pass

    def create_settings(self) -> List[Any]:
        return []

    def on_app_event(self, event_type):
        pass

    def pre_request_hook(self, request_name, account, request):
        return HookResult()

    def post_request_hook(self, request_name, account, response, error):
        return HookResult()

    def on_update_hook(self, update_name, account, update):
        return HookResult()

    def on_updates_hook(self, container_name, account, updates):
        return HookResult()

    def on_send_message_hook(self, account, params):
        return HookResult()

    # event hooks
    @_while_plugin_active
    def add_hook(self, name, match_substring=False, priority=0):
        self._sg_hooks[str(name)] = (bool(match_substring), int(priority or 0))
        self._sg_publish()

    @_while_plugin_active
    def add_on_send_message_hook(self, priority=0):
        self._sg_send_hook = int(priority or 0)
        self._sg_publish()

    def remove_hook(self, name):
        if name == "on_send_message_hook":
            self._sg_send_hook = None
        self._sg_hooks.pop(str(name), None)
        self._sg_publish()

    def _sg_publish(self):
        import sgram_engine
        if self.enabled or self.id:
            sgram_engine._publish_hooks()

    @_while_plugin_active
    def add_file_hook(self, file_info):
        import file_utils
        return file_utils.FilesController._register(file_info, self.id)

    def remove_file_hook(self, ext, secret):
        import file_utils
        file_utils.FilesController.unregister(ext, secret)

    @_while_plugin_active
    def add_intent_hook(self, info, type):
        import intents
        if type == IntentHookType.AFTER:
            return intents.IntentsManager.new_global_after_handler(info, _plugin_id=self.id)
        return intents.IntentsManager.new_global_before_handler(info, _plugin_id=self.id)

    def remove_intent_hook(self, handler_id):
        import intents
        intents.IntentsManager.unhandle(getattr(handler_id, "handler_id", handler_id))

    # settings
    def get_setting(self, key, default=None):
        import plugin_settings
        return plugin_settings.get_setting(self.id, key, default)

    def set_setting(self, key, value, reload_settings=False):
        import plugin_settings
        plugin_settings.set_setting(self.id, key, value)
        if reload_settings:
            from org.telegram.messenger import SgramPlugins
            SgramPlugins.notifyChanged()

    def export_settings(self):
        import plugin_settings
        return dict(plugin_settings.get_all_settings(self.id))

    def import_settings(self, settings, reload_settings=True):
        import plugin_settings
        plugin_settings.set_all_settings(self.id, settings or {})
        if reload_settings:
            from org.telegram.messenger import SgramPlugins
            SgramPlugins.notifyChanged()

    # Xposed
    def _sg_make_hook(self, xposed_hook, priority, before, after, before_filters, after_filters):
        if xposed_hook is None:
            xposed_hook = BaseHook(self, before=before, after=after, before_filters=before_filters, after_filters=after_filters)
        callbacks = _HookCallbacks(self, xposed_hook)
        if priority is None:
            priority = 50
        return _PyMethodHook(callbacks, int(priority))

    @_while_plugin_active
    def hook_method(self, method_or_constructor, xposed_hook=None, priority=None, *, before=None, after=None,
                    before_filters=None, after_filters=None):
        try:
            hook = self._sg_make_hook(xposed_hook, priority, before, after, before_filters, after_filters)
            unhook = _XposedBridge.hookMethod(method_or_constructor, hook)
            self._sg_unhooks.append(unhook)
            return unhook
        except Exception:
            self._sg_log_exception()
            return None

    @_while_plugin_active
    def hook_all_methods(self, hook_class, method_name, xposed_hook=None, priority=None, *, before=None, after=None,
                         before_filters=None, after_filters=None):
        try:
            hook = self._sg_make_hook(xposed_hook, priority, before, after, before_filters, after_filters)
            unhooks = list(_XposedBridge.hookAllMethods(hook_class, method_name, hook).toArray())
            self._sg_unhooks.extend(unhooks)
            return unhooks
        except Exception:
            self._sg_log_exception()
            return None

    @_while_plugin_active
    def hook_all_constructors(self, hook_class, xposed_hook=None, priority=None, *, before=None, after=None,
                              before_filters=None, after_filters=None):
        try:
            hook = self._sg_make_hook(xposed_hook, priority, before, after, before_filters, after_filters)
            unhooks = list(_XposedBridge.hookAllConstructors(hook_class, hook).toArray())
            self._sg_unhooks.extend(unhooks)
            return unhooks
        except Exception:
            self._sg_log_exception()
            return None

    def unhook_method(self, unhook):
        try:
            unhook.unhook()
        finally:
            if unhook in self._sg_unhooks:
                self._sg_unhooks.remove(unhook)

    # logging
    @property
    def logger(self):
        if self._sg_logger is None:
            import log_utils
            self._sg_logger = log_utils.Logger("plugin." + (self.id or "unknown"))
        return self._sg_logger

    @logger.setter
    def logger(self, value):
        self._sg_logger = value

    def log(self, message):
        self.logger.debug(message)

    def client(self, account=None):
        import client_utils
        return client_utils.get_client(account)

    # menu items
    @_while_plugin_active
    def add_menu_item(self, menu_item_data):
        import sgram_engine
        item_id = menu_item_data.item_id or ("%s_%s" % (self.id, uuid.uuid4().hex[:10]))
        item_id = "%s:%s" % (self.id, item_id) if not item_id.startswith(self.id + ":") else item_id
        sgram_engine.register_menu_item(self.id, item_id, menu_item_data)
        if item_id not in self._sg_menu_items:
            self._sg_menu_items.append(item_id)
        return item_id

    def remove_menu_item(self, item_id):
        import sgram_engine
        full = item_id if item_id.startswith(self.id + ":") else "%s:%s" % (self.id, item_id)
        removed = sgram_engine.unregister_menu_item(full) or sgram_engine.unregister_menu_item(item_id)
        for i in (full, item_id):
            if i in self._sg_menu_items:
                self._sg_menu_items.remove(i)
        return removed
