"""Java class generation (class_proxy / JHelper) is not supported in sGram: Chaquopy can only
implement interfaces at runtime. Plugins that need it fail to load with a clear error."""

class Base:
    pass


def _unsupported(*args, **kwargs):
    raise NotImplementedError("class_proxy / JHelper (generated Java subclasses) are not supported in sGram")


class_proxy = _unsupported


class JHelper:
    Override = staticmethod(_unsupported)
    Overload = staticmethod(_unsupported)
    Method = staticmethod(_unsupported)
    MVELMethod = staticmethod(_unsupported)
    MVELOverride = staticmethod(_unsupported)
    Constructor = staticmethod(_unsupported)


class JField:
    def __init__(self, *args, **kwargs):
        _unsupported()


def collect_peers():
    pass


def release_plugin(plugin_id=None):
    pass
