"""sGram plugin engine: loads exteraGram plugins (.plugin / .py) and dispatches hooks.

Java (org.telegram.messenger.SgramPlugins) calls the module-level functions below; plugins
talk to the app through our own implementation of the exteraGram SDK modules.
"""

import ast
import importlib.machinery
import importlib.util
import json
import os
import re
import sys
import threading
import time
import traceback

from java import jclass

SgramPlugins = jclass("org.telegram.messenger.SgramPlugins")

SDK_VERSION = "1.4.6.3"
APP_VERSION = "12.10.5"

PLUGINS_DIR = ""
_lock = threading.RLock()
_plugins = {}          # id -> PluginRecord
_suspended = False
_menu_callbacks = {}   # item_id -> (plugin_id, callable)

_ID_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_-]{1,31}$")
_META_KEYS = ("__id__", "__name__", "__description__", "__author__", "__version__", "__icon__",
              "__app_version__", "__min_version__", "__sdk_version__", "__requirements__", "__category__")
_PREINSTALLED = {"requests", "beautifulsoup4", "bs4", "lxml", "packaging", "pillow", "pil", "pyyaml", "yaml", "debugpy"}

STRATEGY_DEFAULT, STRATEGY_CANCEL, STRATEGY_MODIFY, STRATEGY_MODIFY_FINAL = 0, 1, 2, 3


class PluginRecord:
    def __init__(self, path, meta):
        self.path = path
        self.meta = meta
        self.id = meta["id"]
        self.module = None
        self.instance = None
        self.error = None
        self.logs = []  # last log lines, shown in the plugin screen


# ---------------------------------------------------------------- metadata

def parse_metadata(source):
    """Reads plain top-level metadata constants without running the plugin (like exteraGram)."""
    tree = ast.parse(source)
    values = {}
    for node in tree.body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
            name = node.targets[0].id
            if name in _META_KEYS:
                try:
                    values[name] = ast.literal_eval(node.value)
                except Exception:
                    pass
    plugin_id = values.get("__id__")
    if not isinstance(plugin_id, str) or not _ID_RE.match(plugin_id):
        raise ValueError("Invalid or missing __id__ (2-32 chars: latin letters, digits, _ or -, starting with a letter)")
    if not isinstance(values.get("__name__"), str):
        raise ValueError("Missing __name__")
    app_version = values.get("__app_version__")
    if app_version is None and values.get("__min_version__"):
        app_version = ">=" + str(values["__min_version__"])
    reqs = values.get("__requirements__") or []
    if isinstance(reqs, str):
        reqs = [reqs]
    return {
        "id": plugin_id,
        "name": values["__name__"],
        "description": str(values.get("__description__") or ""),
        "author": str(values.get("__author__") or ""),
        "version": str(values.get("__version__") or "1.0"),
        "icon": values.get("__icon__"),
        "app_version": app_version or "",
        "sdk_version": values.get("__sdk_version__") or "",
        "requirements": [str(r) for r in reqs],
    }


def _read(path):
    with open(path, "r", encoding="utf-8-sig") as f:
        return f.read()


def inspect_file(path):
    """For the install dialog: JSON with metadata or {"error": ...}."""
    try:
        source = _read(path)
        meta = parse_metadata(source)
        try:
            import sgram_guard
            meta["scan"] = sgram_guard.scan(source)
        except Exception as e:
            meta["scan"] = {"level": "warning", "findings": [{"severity": "WARNING", "title": "Scan failed", "text": "%s: %s" % (type(e).__name__, e), "line": 0}]}
        meta["installed"] = meta["id"] in _plugins
        if meta["installed"]:
            meta["installed_version"] = _plugins[meta["id"]].meta["version"]
        missing = _missing_requirements(meta["requirements"])
        if missing:
            meta["missing"] = missing
        return json.dumps(meta)
    except Exception as e:
        return json.dumps({"error": "%s: %s" % (type(e).__name__, e)})


def _req_name(req):
    return re.split(r"[<>=!~\[; ]", req.strip(), 1)[0].lower().replace("_", "-")


def _missing_requirements(reqs):
    missing = []
    for req in reqs:
        name = _req_name(req)
        if not name or name in _PREINSTALLED:
            continue
        module = name.replace("-", "_")
        if importlib.util.find_spec(module) is None and importlib.util.find_spec(name) is None:
            missing.append(req)
    return missing


# ---------------------------------------------------------------- lifecycle

def _prefs():
    return SgramPlugins.prefs()


def is_enabled(plugin_id):
    return bool(_prefs().getBoolean("enabled_" + plugin_id, False))


def _set_enabled_pref(plugin_id, enabled):
    _prefs().edit().putBoolean("enabled_" + plugin_id, bool(enabled)).apply()


def start(plugins_dir, app_version=None):
    global PLUGINS_DIR, APP_VERSION
    PLUGINS_DIR = plugins_dir
    if app_version:
        APP_VERSION = str(app_version)
    sys.dont_write_bytecode = True
    import plugin_settings
    plugin_settings.init(plugins_dir, None)
    for name in sorted(os.listdir(plugins_dir)):
        if name.endswith(".py"):
            try:
                _register(os.path.join(plugins_dir, name))
            except Exception as e:
                _log_engine("Failed to read %s: %s" % (name, e))
    if SgramPlugins.isEngineEnabled():
        for plugin_id in list(_plugins):
            if is_enabled(plugin_id):
                _load(plugin_id)
    _publish_hooks()


def _register(path):
    meta = parse_metadata(_read(path))
    with _lock:
        old = _plugins.get(meta["id"])
        if old is not None and old.instance is not None:
            _unload(meta["id"])
        rec = PluginRecord(path, meta)
        _plugins[meta["id"]] = rec
    return rec


def _module_name(plugin_id):
    return "sgplugin_" + plugin_id.replace("-", "_")


def _load(plugin_id):
    import base_plugin
    from extera_utils import lifecycle
    rec = _plugins.get(plugin_id)
    if rec is None:
        return "Plugin not found"
    if rec.instance is not None:
        return None
    rec.error = None
    try:
        missing = _missing_requirements(rec.meta["requirements"])
        if missing:
            raise base_plugin.PluginError("Missing packages (only preinstalled ones are available in sGram): " + ", ".join(missing), plugin_id)
        name = _module_name(plugin_id)
        loader = importlib.machinery.SourceFileLoader(name, rec.path)
        spec = importlib.util.spec_from_file_location(name, rec.path, loader=loader)
        module = importlib.util.module_from_spec(spec)
        module.__plugin_id__ = plugin_id
        sys.modules[name] = module
        lifecycle.begin(plugin_id)
        base_plugin._loading_plugin_id = plugin_id
        try:
            spec.loader.exec_module(module)
        finally:
            base_plugin._loading_plugin_id = None
        # plugins set __name__ = "Plugin Name" at module level, so classes get that as __module__
        own_modules = (name, getattr(module, "__name__", None))
        cls = None
        for value in vars(module).values():
            if isinstance(value, type) and issubclass(value, base_plugin.BasePlugin) and value is not base_plugin.BasePlugin \
                    and getattr(value, "__module__", None) in own_modules:
                cls = value
        if cls is None:
            raise base_plugin.PluginError("No class inheriting from BasePlugin", plugin_id)
        base_plugin._loading_plugin_id = plugin_id
        try:
            instance = cls()
        finally:
            base_plugin._loading_plugin_id = None
        instance._sg_attach(rec)
        rec.module = module
        rec.instance = instance
        instance.enabled = True
        instance.on_plugin_load()
        instance.initialized = True
        return None
    except BaseException as e:
        rec.error = "%s: %s" % (type(e).__name__, e)
        rec.logs.append("E " + traceback.format_exc())
        _log_engine("Plugin %s failed to load:\n%s" % (plugin_id, traceback.format_exc()))
        _cleanup(rec)
        return rec.error
    finally:
        _publish_hooks()


def _cleanup(rec):
    from extera_utils import lifecycle
    inst = rec.instance
    if inst is not None:
        try:
            inst._sg_detach()
        except Exception:
            _log_engine(traceback.format_exc())
    try:
        lifecycle.stop(rec.id)
    except Exception:
        pass
    SgramPlugins.removePluginMenuItems(rec.id)
    for item_id in [k for k, v in _menu_callbacks.items() if v[0] == rec.id]:
        _menu_callbacks.pop(item_id, None)
    rec.instance = None
    rec.module = None
    sys.modules.pop(_module_name(rec.id), None)


def _unload(plugin_id):
    rec = _plugins.get(plugin_id)
    if rec is None or rec.instance is None:
        return
    inst = rec.instance
    try:
        inst.on_plugin_unload()
    except Exception:
        rec.logs.append("E " + traceback.format_exc())
    inst.enabled = False
    _cleanup(rec)
    _publish_hooks()


def set_enabled(plugin_id, enabled):
    """Returns an error string or None."""
    with _lock:
        _set_enabled_pref(plugin_id, enabled)
        if enabled:
            if _suspended:
                return None
            return _load(plugin_id)
        _unload(plugin_id)
        return None


def install(path):
    """Copies a plugin file into the plugins dir and (re)loads it. Returns JSON {id, error}."""
    try:
        source = _read(path)
        meta = parse_metadata(source)
        target = os.path.join(PLUGINS_DIR, meta["id"] + ".py")
        with _lock:
            was_enabled = meta["id"] not in _plugins or is_enabled(meta["id"])
            if meta["id"] in _plugins:
                _unload(meta["id"])
                old_path = _plugins[meta["id"]].path
                if old_path != target and os.path.exists(old_path):
                    os.remove(old_path)
            with open(target, "w", encoding="utf-8") as f:
                f.write(source)
            _register(target)
            error = None
            if was_enabled:
                _set_enabled_pref(meta["id"], True)
                if not _suspended:
                    error = _load(meta["id"])
        return json.dumps({"id": meta["id"], "error": error})
    except Exception as e:
        return json.dumps({"error": "%s: %s" % (type(e).__name__, e)})


def delete(plugin_id):
    with _lock:
        rec = _plugins.get(plugin_id)
        if rec is None:
            return
        _unload(plugin_id)
        try:
            os.remove(rec.path)
        except OSError:
            pass
        _plugins.pop(plugin_id, None)
        _prefs().edit().remove("enabled_" + plugin_id).apply()
        import plugin_settings
        plugin_settings.clear_settings(plugin_id)
    _publish_hooks()


def suspend_all():
    global _suspended
    with _lock:
        _suspended = True
        for plugin_id in list(_plugins):
            _unload(plugin_id)


def resume_all():
    global _suspended
    with _lock:
        _suspended = False
        for plugin_id in list(_plugins):
            if is_enabled(plugin_id):
                _load(plugin_id)


def list_plugins():
    out = []
    with _lock:
        for rec in sorted(_plugins.values(), key=lambda r: r.meta["name"].lower()):
            inst = rec.instance
            out.append({
                "id": rec.id,
                "name": rec.meta["name"],
                "author": rec.meta["author"],
                "version": rec.meta["version"],
                "description": rec.meta["description"],
                "enabled": is_enabled(rec.id),
                "loaded": inst is not None,
                "error": rec.error,
                "has_settings": bool(inst is not None and type(inst).create_settings is not _base_create_settings()),
                "path": rec.path,
            })
    return json.dumps(out)


def _base_create_settings():
    import base_plugin
    return base_plugin.BasePlugin.create_settings


def get_logs(plugin_id):
    rec = _plugins.get(plugin_id)
    return "\n".join(rec.logs[-300:]) if rec else ""


def add_log(plugin_id, line):
    rec = _plugins.get(plugin_id)
    if rec is not None:
        rec.logs.append(line)
        if len(rec.logs) > 500:
            del rec.logs[:100]


def _log_engine(text):
    jclass("android.util.Log").w("sGramPlugins", text)


def get_instance(plugin_id):
    rec = _plugins.get(plugin_id)
    return rec.instance if rec else None


def active():
    return [r.instance for r in _plugins.values() if r.instance is not None]


# ---------------------------------------------------------------- settings UI

def get_settings(plugin_id):
    inst = get_instance(plugin_id)
    if inst is None:
        return None
    try:
        return list(inst.create_settings() or [])
    except Exception:
        add_log(plugin_id, "E " + traceback.format_exc())
        return None


def get_setting_value(plugin_id, key, default):
    inst = get_instance(plugin_id)
    if inst is None:
        return default
    return inst.get_setting(key, default)


def setting_changed(plugin_id, item, value):
    inst = get_instance(plugin_id)
    if inst is None:
        return
    key = getattr(item, "key", None)
    if key:
        inst.set_setting(key, value)
    cb = getattr(item, "on_change", None)
    if cb is not None:
        try:
            cb(value)
        except Exception:
            add_log(plugin_id, "E " + traceback.format_exc())


def call_safely(plugin_id, fn, *args):
    try:
        return fn(*args)
    except Exception:
        add_log(plugin_id, "E " + traceback.format_exc())
        return None


# ---------------------------------------------------------------- hooks

def _publish_hooks():
    exact, substrings, send = set(), set(), False
    for inst in active():
        for name, (substring, _prio) in inst._sg_hooks.items():
            (substrings if substring else exact).add(name)
        if inst._sg_send_hook is not None:
            send = True
    SgramPlugins.setHooks(list(exact), list(substrings), send)


def _hooked(name):
    """Plugins subscribed to name, highest priority first."""
    result = []
    for inst in active():
        for hook_name, (substring, prio) in inst._sg_hooks.items():
            if hook_name == name or (substring and hook_name in name):
                result.append((prio, inst))
                break
    result.sort(key=lambda p: -p[0])
    return [inst for _p, inst in result]


def _run_chain(plugins, call, field, value, account):
    import base_plugin
    import client_utils
    strategy = STRATEGY_DEFAULT
    error = None
    for inst in plugins:
        try:
            with client_utils.account_scope(account):
                res = call(inst, value)
        except Exception:
            inst._sg_log_exception()
            continue
        if not isinstance(res, base_plugin.HookResult):
            continue
        s = res.strategy
        if s == base_plugin.HookStrategy.CANCEL:
            return (STRATEGY_CANCEL, None, None)
        if s in (base_plugin.HookStrategy.MODIFY, base_plugin.HookStrategy.MODIFY_FINAL):
            new_value = getattr(res, field, None)
            if new_value is not None:
                value = new_value
            if field == "response" and res.error is not None:
                error = res.error
            strategy = STRATEGY_MODIFY
            if s == base_plugin.HookStrategy.MODIFY_FINAL:
                break
    return (strategy, value, error)


def pre_request(name, account, request):
    return _run_chain(_hooked(name), lambda p, v: p.pre_request_hook(name, account, v), "request", request, account)


def post_request(name, account, response, error):
    plugins = _hooked(name)
    result = _run_chain(plugins, lambda p, v: p.post_request_hook(name, account, v, error), "response", response, account)
    return result


def on_update(name, account, update):
    return _run_chain(_hooked(name), lambda p, v: p.on_update_hook(name, account, v), "update", update, account)


def on_updates(name, account, updates):
    return _run_chain(_hooked(name), lambda p, v: p.on_updates_hook(name, account, v), "updates", updates, account)


def on_send_message(account, params):
    plugins = sorted([i for i in active() if i._sg_send_hook is not None], key=lambda i: -i._sg_send_hook)
    return _run_chain(plugins, lambda p, v: p.on_send_message_hook(account, v), "params", params, account)


def on_app_event(event):
    import base_plugin
    try:
        ev = base_plugin.AppEvent[event]
    except KeyError:
        return
    for inst in active():
        try:
            inst.on_app_event(ev)
        except Exception:
            inst._sg_log_exception()


# ---------------------------------------------------------------- menu items

def register_menu_item(plugin_id, item_id, data):
    _menu_callbacks[item_id] = (plugin_id, data.on_click)
    SgramPlugins.addMenuItem(plugin_id, item_id, data.menu_type.name, str(data.text), data.icon, data.subtext, int(data.priority or 0))


def unregister_menu_item(item_id):
    _menu_callbacks.pop(item_id, None)
    return bool(SgramPlugins.removeMenuItem(item_id))


def on_menu_item_click(item_id, java_context):
    entry = _menu_callbacks.get(item_id)
    if entry is None:
        return
    plugin_id, callback = entry
    context = {}
    try:
        for key in java_context.keySet().toArray():
            context[str(key)] = java_context.get(key)
    except Exception:
        pass
    import client_utils
    try:
        with client_utils.account_scope(context.get("account")):
            callback(context)
    except Exception:
        add_log(plugin_id, "E " + traceback.format_exc())
        inst = get_instance(plugin_id)
        if inst is not None:
            inst._sg_log_exception(already_logged=True)
