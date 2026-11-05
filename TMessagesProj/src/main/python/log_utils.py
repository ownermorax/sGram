"""exteraGram plugin SDK: log_utils (sGram implementation). Logs go to logcat and to the
plugin's log view in sGram (Plugins → plugin → Logs)."""

import sys
import time
import traceback
from enum import IntEnum

from java import jclass

__all__ = ["LogLevel", "LogRecord", "Logger", "get_logger", "dump"]

_Log = jclass("android.util.Log")


class LogLevel(IntEnum):
    VERBOSE = 2
    DEBUG = 3
    INFO = 4
    WARNING = 5
    ERROR = 6
    OFF = 100


_LETTER = {LogLevel.VERBOSE: "V", LogLevel.DEBUG: "D", LogLevel.INFO: "I", LogLevel.WARNING: "W", LogLevel.ERROR: "E"}


def _level(value):
    if value is None:
        return None
    if isinstance(value, LogLevel):
        return value
    if isinstance(value, str):
        return LogLevel[value.upper()]
    return LogLevel(int(value))


class LogRecord:
    def __init__(self, level, tag, message, location, tb, created):
        self.level = level
        self.tag = tag
        self.message = message
        self.location = location
        self.traceback = tb
        self.created = created

    @property
    def text(self):
        out = self.message
        if self.location:
            out = "%s (%s)" % (out, self.location)
        if self.traceback:
            out = out + "\n" + self.traceback
        return out


class _Timer:
    def __init__(self, logger, label, level):
        self.logger = logger
        self.label = label
        self.level = level

    def __enter__(self):
        self.start = time.perf_counter()
        return self

    def __exit__(self, exc_type, exc, tb):
        ms = (time.perf_counter() - self.start) * 1000
        self.logger.log(self.level, "%s took %.1f ms", self.label, ms)
        return False


class Logger:
    def __init__(self, tag, *, show_location=True):
        self._tag = str(tag)
        self._level = None
        self._handlers = []
        self.show_location = show_location

    @property
    def tag(self):
        return self._tag

    @property
    def level(self):
        return self._level

    @level.setter
    def level(self, value):
        self._level = _level(value)

    @property
    def effective_level(self):
        return self._level if self._level is not None else LogLevel.VERBOSE

    def is_enabled_for(self, level):
        return _level(level) >= self.effective_level

    def child(self, name):
        logger = Logger("%s.%s" % (self._tag, name), show_location=self.show_location)
        logger._level = self._level
        return logger

    def _emit(self, level, msg, args, exc_info, stacklevel):
        level = _level(level)
        if level < self.effective_level or level >= LogLevel.OFF:
            return
        try:
            text = str(msg) % args if args else str(msg)
        except Exception:
            text = "%s %r" % (msg, args)
        tb = None
        if exc_info:
            if isinstance(exc_info, BaseException):
                tb = "".join(traceback.format_exception(type(exc_info), exc_info, exc_info.__traceback__))
            else:
                tb = traceback.format_exc()
            if tb and tb.strip() == "NoneType: None":
                tb = None
        location = None
        if self.show_location:
            try:
                # 0 = _emit, 1 = info()/debug()/..., 2 = the caller
                frame = sys._getframe(stacklevel + 1)
                location = "%s:%d" % (frame.f_code.co_name, frame.f_lineno)
            except Exception:
                pass
        record = LogRecord(level, self._tag, text, location, tb, time.time())
        _Log.println(int(level), self._tag[:23], record.text)
        try:
            import sgram_engine
            plugin_id = self._tag.split(".", 2)[1] if self._tag.startswith("plugin.") else None
            if plugin_id:
                sgram_engine.add_log(plugin_id, "%s %s %s" % (time.strftime("%H:%M:%S"), _LETTER.get(level, "?"), record.text))
        except Exception:
            pass
        for handler in list(self._handlers):
            try:
                handler(record)
            except Exception:
                pass

    def verbose(self, msg, *args, exc_info=None, stacklevel=1):
        self._emit(LogLevel.VERBOSE, msg, args, exc_info, stacklevel)

    def debug(self, msg, *args, exc_info=None, stacklevel=1):
        self._emit(LogLevel.DEBUG, msg, args, exc_info, stacklevel)

    def info(self, msg, *args, exc_info=None, stacklevel=1):
        self._emit(LogLevel.INFO, msg, args, exc_info, stacklevel)

    def warning(self, msg, *args, exc_info=None, stacklevel=1):
        self._emit(LogLevel.WARNING, msg, args, exc_info, stacklevel)

    warn = warning

    def error(self, msg, *args, exc_info=None, stacklevel=1):
        self._emit(LogLevel.ERROR, msg, args, exc_info, stacklevel)

    def exception(self, msg, *args, stacklevel=1):
        self._emit(LogLevel.ERROR, msg, args, True, stacklevel)

    def log(self, level, msg, *args, exc_info=None, stacklevel=1):
        self._emit(level, msg, args, exc_info, stacklevel)

    def dump(self, obj, label=None, *, level=LogLevel.DEBUG, depth=2, max_items=20, stacklevel=1):
        text = dump(obj, depth, max_items)
        self._emit(level, "%s: %s" % (label, text) if label else text, (), None, stacklevel)

    def timed(self, label, level=LogLevel.DEBUG):
        return _Timer(self, label, _level(level))

    def add_handler(self, handler):
        self._handlers.append(handler)

    def remove_handler(self, handler):
        if handler in self._handlers:
            self._handlers.remove(handler)
            return True
        return False


_loggers = {}


def get_logger(name=None):
    if name is None:
        try:
            from extera_utils.get_caller import get_plugin_id
            plugin_id = get_plugin_id()
            name = "plugin." + plugin_id if plugin_id else "plugins"
        except Exception:
            name = "plugins"
    if name not in _loggers:
        _loggers[name] = Logger(name)
    return _loggers[name]


def _java_fields(obj, depth, max_items, indent):
    cls = obj.getClass()
    lines = []
    seen = 0
    Modifier = jclass("java.lang.reflect.Modifier")
    while cls is not None and seen < max_items:
        for f in cls.getDeclaredFields():
            if Modifier.isStatic(f.getModifiers()):
                continue
            try:
                f.setAccessible(True)
                value = f.get(obj)
            except Exception as e:
                value = "<unreadable: %s>" % e
            lines.append("%s%s = %s" % (indent, f.getName(), _render(value, depth - 1, max_items, indent + "  ")))
            seen += 1
            if seen >= max_items:
                lines.append(indent + "...")
                break
        cls = cls.getSuperclass()
        if cls is not None and cls.getName() in ("java.lang.Object", "org.telegram.tgnet.TLObject"):
            break
    return lines


def _render(obj, depth, max_items, indent=""):
    if obj is None or isinstance(obj, (bool, int, float, str)):
        return repr(obj)
    if depth <= 0:
        return "<%s>" % type(obj).__name__
    if isinstance(obj, (list, tuple, set)):
        items = [_render(v, depth - 1, max_items, indent + "  ") for v in list(obj)[:max_items]]
        return "[" + ", ".join(items) + (", ..." if len(obj) > max_items else "") + "]"
    if isinstance(obj, dict):
        items = ["%r: %s" % (k, _render(v, depth - 1, max_items, indent + "  ")) for k, v in list(obj.items())[:max_items]]
        return "{" + ", ".join(items) + "}"
    if hasattr(obj, "getClass"):
        try:
            name = obj.getClass().getName()
            if name.startswith("java.lang."):
                return str(obj)
            return name + " {\n" + "\n".join(_java_fields(obj, depth, max_items, indent + "  ")) + "\n" + indent + "}"
        except Exception:
            return str(obj)
    return repr(obj)


def dump(obj, depth=2, max_items=20):
    return _render(obj, depth, max_items)
