"""exteraGram plugin SDK: plugin_settings (sGram implementation).

Values are stored JSON-encoded in the SharedPreferences file "sgram_plugin_settings"
under "<plugin_id>/<key>", so every JSON type survives a restart unchanged.
"""

import json
import threading

from java import jclass

_lock = threading.RLock()
_cache = {}
_prefs = None


def _sp():
    global _prefs
    if _prefs is None:
        ApplicationLoader = jclass("org.telegram.messenger.ApplicationLoader")
        Context = jclass("android.content.Context")
        _prefs = ApplicationLoader.applicationContext.getSharedPreferences("sgram_plugin_settings", Context.MODE_PRIVATE)
    return _prefs


def init(plugins_dir_path, all_shared_prefs):
    _sp()


def _key(plugin_id, key):
    return "%s/%s" % (plugin_id, key)


def get_setting(plugin_id, key, default):
    k = _key(plugin_id, key)
    with _lock:
        if k in _cache:
            return _cache[k]
        raw = _sp().getString(k, None)
        if raw is None:
            return default
        try:
            value = json.loads(raw)
        except ValueError:
            value = raw
        _cache[k] = value
        return value


def set_setting(plugin_id, key, value):
    k = _key(plugin_id, key)
    with _lock:
        if value is None:
            _cache.pop(k, None)
            _sp().edit().remove(k).apply()
            return
        _cache[k] = value
        _sp().edit().putString(k, json.dumps(value)).apply()


def _keys(plugin_id):
    prefix = plugin_id + "/"
    return [str(k) for k in _sp().getAll().keySet().toArray() if str(k).startswith(prefix)]


def clear_settings(plugin_id):
    with _lock:
        editor = _sp().edit()
        for k in _keys(plugin_id):
            _cache.pop(k, None)
            editor.remove(k)
        editor.apply()


def get_all_settings(plugin_id):
    out = {}
    prefix = len(plugin_id) + 1
    for k in _keys(plugin_id):
        out[k[prefix:]] = get_setting(plugin_id, k[prefix:], None)
    return out


def set_all_settings(plugin_id, settings):
    clear_settings(plugin_id)
    for key, value in dict(settings).items():
        set_setting(plugin_id, key, value)
