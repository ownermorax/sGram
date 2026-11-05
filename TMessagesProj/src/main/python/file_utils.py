"""exteraGram plugin SDK: file_utils (sGram implementation).

FilesController (custom handlers for opening files with a given extension) is kept for API
compatibility; sGram opens files itself, so registered handlers are not called yet.
"""

import os
import uuid
from dataclasses import dataclass, field
from enum import Enum

from java import jclass

_ApplicationLoader = jclass("org.telegram.messenger.ApplicationLoader")
_FileLoader = jclass("org.telegram.messenger.FileLoader")


def get_plugins_dir():
    import sgram_engine
    return sgram_engine.PLUGINS_DIR


def get_cache_dir():
    return str(_ApplicationLoader.applicationContext.getCacheDir().getAbsolutePath())


def get_files_dir():
    return str(_ApplicationLoader.applicationContext.getFilesDir().getAbsolutePath())


def _media_dir(kind):
    try:
        return str(_FileLoader.getDirectory(kind).getAbsolutePath())
    except Exception:
        return get_cache_dir()


def get_images_dir():
    return _media_dir(_FileLoader.MEDIA_DIR_IMAGE)


def get_videos_dir():
    return _media_dir(_FileLoader.MEDIA_DIR_VIDEO)


def get_audios_dir():
    return _media_dir(_FileLoader.MEDIA_DIR_AUDIO)


def get_documents_dir():
    return _media_dir(_FileLoader.MEDIA_DIR_DOCUMENT)


def read_file(file_path):
    try:
        with open(file_path, "r", encoding="utf-8") as f:
            return f.read()
    except Exception:
        return None


def read_file_bytes(file_path):
    try:
        with open(file_path, "rb") as f:
            return f.read()
    except Exception:
        return None


def write_file(file_path, content):
    ensure_dir_exists(os.path.dirname(file_path))
    with open(file_path, "w", encoding="utf-8") as f:
        f.write(content)


def write_file_bytes(file_path, content):
    ensure_dir_exists(os.path.dirname(file_path))
    with open(file_path, "wb") as f:
        f.write(content)


def delete_file(file_path):
    try:
        os.remove(file_path)
        return True
    except OSError:
        return False


def ensure_dir_exists(dir_path):
    if dir_path:
        os.makedirs(dir_path, exist_ok=True)


def list_dir(path, recursive=False, include_files=True, include_dirs=False, extensions=None):
    exts = tuple(e.lower() if e.startswith(".") else "." + e.lower() for e in (extensions or []))
    out = []
    try:
        if recursive:
            for root, dirs, files in os.walk(path):
                if include_dirs:
                    out.extend(os.path.join(root, d) for d in dirs)
                if include_files:
                    out.extend(os.path.join(root, f) for f in files if not exts or f.lower().endswith(exts))
        else:
            for name in os.listdir(path):
                full = os.path.join(path, name)
                if os.path.isdir(full):
                    if include_dirs:
                        out.append(full)
                elif include_files and (not exts or name.lower().endswith(exts)):
                    out.append(full)
    except OSError:
        pass
    return out


class staticproperty:
    def __init__(self, fget):
        self.fget = fget

    def __get__(self, obj, owner):
        fget = self.fget.__func__ if isinstance(self.fget, staticmethod) else self.fget
        return fget()


class FilesController:
    _handlers = {}  # ext -> (secret, plugin_id, FileInfo)

    @staticproperty
    @staticmethod
    def DIRECT_FILE_ICONS():
        return False

    @staticproperty
    @staticmethod
    def SUPPORT_ICONS():
        return False

    class Place(Enum):
        UNKNOWN = 1
        ChatActivity = 2
        FilteredSearchView = 3
        SharedMediaLayout = 4
        SearchDownloadsContainer = 5
        ChannelAdminLogActivity = 6

    @dataclass
    class FileInfo:
        ext: str
        on_click: object = field(default=None, compare=False, repr=False)
        whitelist_places: list = field(default_factory=list, kw_only=True)
        blacklist_places: list = field(default_factory=list, kw_only=True)
        get_icon: object = field(default=None, compare=False, repr=False, kw_only=True)

        def __post_init__(self):
            self.ext = self.ext.lower().lstrip(".")

    @dataclass
    class OnClickArgs:
        place: object
        file: object = field(compare=False, repr=False)
        file_name: str = ""
        message: object = field(default=None, compare=False, repr=False)
        activity: object = field(default=None, compare=False, repr=False)
        parent_fragment: object = field(default=None, compare=False, repr=False)

    class ExtensionAlreadyRegistered(Exception):
        def __init__(self, ext):
            super().__init__("Extension already registered: " + ext)

    class ExtensionNotRegistered(Exception):
        def __init__(self, ext):
            super().__init__("Extension not registered: " + ext)

    class SecretInvalid(Exception):
        def __init__(self, ext, secret):
            super().__init__("Invalid secret for " + ext)

    @classmethod
    def _register(cls, file_info, plugin_id):
        if file_info.ext in cls._handlers:
            raise cls.ExtensionAlreadyRegistered(file_info.ext)
        secret = uuid.uuid4().hex
        cls._handlers[file_info.ext] = (secret, plugin_id, file_info)
        return secret

    @classmethod
    def register(cls, file_info):
        from extera_utils.get_caller import get_plugin_id
        return cls._register(file_info, get_plugin_id())

    @classmethod
    def unregister(cls, ext, secret):
        ext = ext.lower().lstrip(".")
        entry = cls._handlers.get(ext)
        if entry is None:
            raise cls.ExtensionNotRegistered(ext)
        if entry[0] != secret:
            raise cls.SecretInvalid(ext, secret)
        del cls._handlers[ext]

    @classmethod
    def _release(cls, plugin_id):
        for ext in [e for e, v in cls._handlers.items() if v[1] == plugin_id]:
            del cls._handlers[ext]
