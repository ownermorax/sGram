"""exteraGram plugin SDK: intents (sGram implementation).

Handlers are registered for API compatibility; sGram does not route deep links to plugins yet.
"""

import uuid
from dataclasses import dataclass, field
from urllib.parse import parse_qsl, urlparse


class IntentsManager:
    _handlers = {}  # handler_id -> (plugin_id, hook_type, info)

    @dataclass
    class HandlerInfo:
        callback: object
        scheme: str = field(default=None)
        host: str = field(default=None)
        path: str = field(default=None)
        required_path_args_names: list = field(default=None)
        priority: int = field(default=0)
        action: str = field(default=None)
        whitelist_flags: list = field(default=None)
        blacklist_flags: list = field(default=None)
        type: str = field(default=None)
        categories: list = field(default=None)

    @dataclass
    class HandlerHandle:
        handler_id: str

        def unhandle(self):
            IntentsManager.unhandle(self.handler_id)

    class HandlerNotRegistered(Exception):
        def __init__(self, handler_id):
            super().__init__("Handler not registered: " + str(handler_id))

    @staticmethod
    def parse(url):
        return dict(parse_qsl(urlparse(url).query))

    @classmethod
    def _add(cls, info, hook_type, plugin_id):
        if plugin_id is None:
            from extera_utils.get_caller import get_plugin_id
            plugin_id = get_plugin_id()
        handler_id = uuid.uuid4().hex
        cls._handlers[handler_id] = (plugin_id, hook_type, info)
        return cls.HandlerHandle(handler_id)

    @classmethod
    def new_global_before_handler(cls, info, _plugin_id=None):
        return cls._add(info, "before", _plugin_id)

    @classmethod
    def new_global_after_handler(cls, info, _plugin_id=None):
        return cls._add(info, "after", _plugin_id)

    @classmethod
    def unhandle(cls, handler_id):
        if cls._handlers.pop(handler_id, None) is None:
            raise cls.HandlerNotRegistered(handler_id)

    @classmethod
    def _release(cls, plugin_id):
        for handler_id in [k for k, v in cls._handlers.items() if v[0] == plugin_id]:
            del cls._handlers[handler_id]
