"""Finds which plugin is calling (sGram implementation)."""

import sys

ELYX_PLUGIN_NAMESPACE = "sgplugin_"


def get_plugin_id(fn=None):
    if fn is not None:
        module = sys.modules.get(getattr(fn, "__module__", "") or "")
        plugin_id = getattr(module, "__plugin_id__", None)
        if plugin_id:
            return plugin_id
    frame = sys._getframe(1)
    while frame is not None:
        # __name__ of a plugin module is usually overwritten by its metadata, __plugin_id__ is ours
        plugin_id = frame.f_globals.get("__plugin_id__")
        if plugin_id:
            return plugin_id
        frame = frame.f_back
    import base_plugin
    return base_plugin._loading_plugin_id
