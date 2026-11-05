"""exteraGram plugin SDK: ui.bulletin (sGram implementation)."""

from java import jclass

_Api = jclass("org.telegram.messenger.SgramPluginApi")


def _runnable(fn):
    if fn is None:
        return None
    from android_utils import R
    return R(fn)


class BulletinHelper:
    DURATION_SHORT = 1500
    DURATION_LONG = 2750
    DURATION_PROLONG = 5000

    @classmethod
    def show_info(cls, message, fragment=None):
        _Api.bulletin(_Api.rawRes("info"), str(message), None, fragment)

    @classmethod
    def show_error(cls, message, fragment=None):
        _Api.bulletin(_Api.rawRes("error"), str(message), None, fragment)

    @classmethod
    def show_success(cls, message, fragment=None):
        _Api.bulletin(_Api.rawRes("contact_check"), str(message), None, fragment)

    @classmethod
    def show_simple(cls, text, icon_res_id, fragment=None):
        _Api.bulletin(int(icon_res_id), str(text), None, fragment)

    @classmethod
    def show_two_line(cls, title, subtitle, icon_res_id, fragment=None):
        _Api.bulletin(int(icon_res_id), str(title), str(subtitle), fragment)

    @classmethod
    def show_with_button(cls, text, icon_res_id, button_text, on_click, fragment=None, duration=DURATION_PROLONG):
        _Api.bulletinWithButton(int(icon_res_id), str(text), str(button_text), _runnable(on_click), int(duration), fragment)

    @classmethod
    def show_undo(cls, text, on_undo, on_action=None, subtitle=None, fragment=None):
        _Api.bulletinUndo(str(text), subtitle, _runnable(on_undo), _runnable(on_action), fragment)

    @classmethod
    def show_copied_to_clipboard(cls, message=None, fragment=None):
        _Api.bulletinCopied(message, fragment)

    @classmethod
    def show_link_copied(cls, is_private_link_info=False, fragment=None):
        _Api.bulletinCopied("Link copied to clipboard", fragment)

    @classmethod
    def show_file_saved_to_gallery(cls, is_video=False, amount=1, fragment=None):
        _Api.bulletin(_Api.rawRes("ic_save_to_gallery"), ("Video" if is_video else "Photo") + " saved to gallery", None, fragment)

    @classmethod
    def show_file_saved_to_downloads(cls, file_type_enum_name="UNKNOWN", amount=1, fragment=None):
        _Api.bulletin(_Api.rawRes("ic_download"), "Saved to downloads", None, fragment)
