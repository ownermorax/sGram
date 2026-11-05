"""exteraGram plugin SDK: ui.alert (sGram implementation over Telegram's AlertDialog)."""

import traceback

from java import dynamic_proxy, jarray, jclass, jint

_AlertDialog = jclass("org.telegram.ui.ActionBar.AlertDialog")
_DialogInterface = jclass("android.content.DialogInterface")
_CharSequence = jclass("java.lang.CharSequence")


def _safe(fn, *args):
    try:
        fn(*args)
    except Exception:
        traceback.print_exc()


class _ButtonClickListenerProxy(dynamic_proxy(_AlertDialog.OnButtonClickListener)):
    def __init__(self, py_callable, builder_instance):
        super().__init__()
        self.fn = py_callable
        self.builder = builder_instance

    def onClick(self, dialog_java_instance, which):
        if self.fn is not None:
            _safe(self.fn, self.builder, which)


class _ItemsClickListenerProxy(dynamic_proxy(_DialogInterface.OnClickListener)):
    def __init__(self, py_callable, builder_instance):
        super().__init__()
        self.fn = py_callable
        self.builder = builder_instance

    def onClick(self, dialog_java_instance, which):
        if self.fn is not None:
            _safe(self.fn, self.builder, which)


class _DismissListenerProxy(dynamic_proxy(_DialogInterface.OnDismissListener)):
    def __init__(self, py_callable, builder_instance):
        super().__init__()
        self.fn = py_callable
        self.builder = builder_instance

    def onDismiss(self, dialog_java_instance):
        if self.fn is not None:
            _safe(self.fn, self.builder)


class _CancelListenerProxy(dynamic_proxy(_DialogInterface.OnCancelListener)):
    def __init__(self, py_callable, builder_instance):
        super().__init__()
        self.fn = py_callable
        self.builder = builder_instance

    def onCancel(self, dialog_java_instance):
        if self.fn is not None:
            _safe(self.fn, self.builder)


class AlertDialogBuilder:
    ALERT_TYPE_MESSAGE = 0
    ALERT_TYPE_LOADING = 2
    ALERT_TYPE_SPINNER = 3
    BUTTON_POSITIVE = -1
    BUTTON_NEGATIVE = -2
    BUTTON_NEUTRAL = -3

    def __init__(self, context, progress_style=ALERT_TYPE_MESSAGE, resources_provider=None):
        self._context = context
        self._builder = _AlertDialog.Builder(context, jint(progress_style), resources_provider)
        self._dialog = None
        self._listeners = []  # keep Python proxies alive while the dialog exists

    def _keep(self, proxy):
        self._listeners.append(proxy)
        return proxy

    def get_context(self):
        return self._context

    def set_title(self, title):
        self._builder.setTitle(title)
        return self

    def set_message(self, message):
        self._builder.setMessage(message)
        return self

    def set_message_text_view_clickable(self, clickable):
        self._builder.setMessageTextViewClickable(bool(clickable))
        return self

    def set_positive_button(self, text, listener=None):
        self._builder.setPositiveButton(text, self._keep(_ButtonClickListenerProxy(listener, self)))
        return self

    def set_negative_button(self, text, listener=None):
        self._builder.setNegativeButton(text, self._keep(_ButtonClickListenerProxy(listener, self)))
        return self

    def set_neutral_button(self, text, listener=None):
        self._builder.setNeutralButton(text, self._keep(_ButtonClickListenerProxy(listener, self)))
        return self

    def make_button_red(self, button_type):
        self._builder.makeRed(jint(button_type))
        return self

    def set_on_back_button_listener(self, listener=None):
        self._builder.setOnBackButtonListener(self._keep(_ButtonClickListenerProxy(listener, self)))
        return self

    def set_view(self, view, height=-2):
        self._builder.setView(view, jint(height))
        return self

    def set_items(self, items, listener=None, icons=None):
        arr = jarray(_CharSequence)([str(i) for i in items])
        proxy = self._keep(_ItemsClickListenerProxy(listener, self))
        if icons:
            self._builder.setItems(arr, jarray(jint)(list(icons)), proxy)
        else:
            self._builder.setItems(arr, proxy)
        return self

    def set_on_dismiss_listener(self, listener=None):
        self._builder.setOnDismissListener(self._keep(_DismissListenerProxy(listener, self)))
        return self

    def set_on_cancel_listener(self, listener=None):
        self._builder.setOnCancelListener(self._keep(_CancelListenerProxy(listener, self)))
        return self

    def set_top_image(self, res_id, background_color):
        self._builder.setTopImage(jint(res_id), jint(background_color))
        return self

    def set_top_drawable(self, drawable, background_color):
        self._builder.setTopImage(drawable, jint(background_color))
        return self

    def set_top_animation(self, res_id, size, auto_repeat, background_color, layer_colors=None):
        if layer_colors:
            m = jclass("java.util.HashMap")()
            for k, v in layer_colors.items():
                m.put(k, jclass("java.lang.Integer")(int(v)))
            self._builder.setTopAnimation(jint(res_id), jint(size), bool(auto_repeat), jint(background_color), m)
        else:
            self._builder.setTopAnimation(jint(res_id), jint(size), bool(auto_repeat), jint(background_color))
        return self

    def set_top_animation_is_new(self, is_new):
        self._builder.setTopAnimationIsNew(bool(is_new))
        return self

    def set_dim_enabled(self, enabled):
        self._builder.setDimEnabled(bool(enabled))
        return self

    def set_dialog_button_color_key(self, theme_key):
        self._builder.setDialogButtonColorKey(jint(theme_key))
        return self

    def set_blurred_background(self, blur, blur_behind_if_possible=True):
        self._builder.setBlurredBackground(bool(blur))
        return self

    def create(self):
        self._dialog = self._builder.create()
        return self

    def show(self):
        if self._dialog is None:
            self.create()
        try:
            from client_utils import get_last_fragment
            fragment = get_last_fragment()
            if fragment is not None and fragment.getParentActivity() is not None:
                fragment.showDialog(self._dialog)
            else:
                self._dialog.show()
        except Exception:
            self._dialog.show()
        return self

    def dismiss(self):
        if self._dialog is not None:
            try:
                self._dialog.dismiss()
            except Exception:
                pass

    def get_dialog(self):
        return self._dialog

    def get_button(self, button_type):
        return self._dialog.getButton(jint(button_type)) if self._dialog is not None else None

    def set_progress(self, progress):
        if self._dialog is not None:
            self._dialog.setProgress(jint(progress))

    def set_cancelable(self, cancelable):
        if self._dialog is not None:
            self._dialog.setCanCancel(bool(cancelable))
            self._dialog.setCancelable(bool(cancelable))

    def set_canceled_on_touch_outside(self, cancel):
        if self._dialog is not None:
            self._dialog.setCanceledOnTouchOutside(bool(cancel))
