"""exteraGram plugin SDK: android_utils (sGram implementation)."""

from java import dynamic_proxy, jclass

_AndroidUtilities = jclass("org.telegram.messenger.AndroidUtilities")
_Runnable = jclass("java.lang.Runnable")
_View = jclass("android.view.View")


class _R(dynamic_proxy(_Runnable)):
    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def run(self):
        try:
            self.fn()
        except Exception:
            import traceback
            traceback.print_exc()


def R(fn):
    return _R(fn)


class OnClickListener(dynamic_proxy(_View.OnClickListener)):
    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def onClick(self, view):
        self.fn(view)


class OnLongClickListener(dynamic_proxy(_View.OnLongClickListener)):
    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def onLongClick(self, view):
        return bool(self.fn(view))


def run_on_ui_thread(func, delay=0):
    _AndroidUtilities.runOnUIThread(_R(func), int(delay or 0))


def log(data):
    import log_utils
    if data is None or isinstance(data, (str, int, float, bool)):
        text = str(data)
    else:
        text = log_utils.dump(data)
    jclass("android.util.Log").d("plugins", text)
    try:
        from extera_utils.get_caller import get_plugin_id
        import sgram_engine
        plugin_id = get_plugin_id()
        if plugin_id:
            sgram_engine.add_log(plugin_id, "D " + text)
    except Exception:
        pass


def copy_to_clipboard(text):
    def run():
        if _AndroidUtilities.addToClipboard(str(text)):
            from ui.bulletin import BulletinHelper
            BulletinHelper.show_copied_to_clipboard()
    run_on_ui_thread(run)
