"""Per-plugin scopes: callbacks bound to a plugin stop running once it is unloaded."""

import contextlib
import threading

_scopes = {}
_lock = threading.RLock()


class PluginScope:
    def __init__(self, plugin_id):
        self.plugin_id = plugin_id
        self.stop_event = threading.Event()
        self._cleanups = []

    def close(self):
        self.stop_event.set()
        cleanups, self._cleanups = self._cleanups, []
        for cleanup in reversed(cleanups):
            try:
                cleanup()
            except Exception:
                pass

    def register_cleanup(self, cleanup):
        self._cleanups.append(cleanup)
        return cleanup


def _current_scope():
    from extera_utils.get_caller import get_plugin_id
    plugin_id = get_plugin_id()
    return _scopes.get(plugin_id) if plugin_id else None


class ManagedCallback:
    def __init__(self, fn, scope=None, once=False, allow_stopped=False, cleanup_task=False):
        self.fn = fn
        self.scope = scope or _current_scope()
        self.once = once
        self.allow_stopped = allow_stopped
        self._cancelled = False
        self._cancel = None

    @property
    def cancelled(self):
        return self._cancelled or (self.scope is not None and self.scope.stop_event.is_set() and not self.allow_stopped)

    def set_cancel(self, cancel):
        self._cancel = cancel

    def cancel(self):
        self._cancelled = True
        if self._cancel is not None:
            try:
                self._cancel()
            except Exception:
                pass

    def __call__(self, *args, **kwargs):
        if self.cancelled:
            return None
        if self.once:
            self._cancelled = True
        return self.fn(*args, **kwargs)


def begin(plugin_id):
    with _lock:
        old = _scopes.get(plugin_id)
        if old is not None:
            old.close()
        _scopes[plugin_id] = PluginScope(plugin_id)


def stop(plugin_id):
    with _lock:
        scope = _scopes.pop(plugin_id, None)
    if scope is not None:
        scope.close()


def stop_all():
    for plugin_id in list(_scopes):
        stop(plugin_id)


def capture_scope(fn=None):
    return _current_scope()


def bind(fn, *, once=False):
    return ManagedCallback(fn, once=once)


@contextlib.contextmanager
def cleanup_context(scope):
    yield


def bind_task(fn, *, once=False, scope=None):
    return ManagedCallback(fn, scope=scope, once=once)


def bind_request(fn):
    return ManagedCallback(fn, once=True)
