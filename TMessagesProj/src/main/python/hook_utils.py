"""exteraGram plugin SDK: hook_utils (sGram implementation)."""

from java import jclass

_Class = jclass("java.lang.Class")


def _loader():
    return jclass("org.telegram.messenger.ApplicationLoader").getClass().getClassLoader()


def find_class(class_name):
    try:
        return _Class.forName(class_name, False, _loader())
    except Exception:
        return None


def _find_field(clazz, field_name):
    c = clazz
    while c is not None:
        try:
            f = c.getDeclaredField(field_name)
            f.setAccessible(True)
            return f
        except Exception:
            c = c.getSuperclass()
    return None


def get_private_field(obj, field_name):
    try:
        f = _find_field(obj.getClass(), field_name)
        return f.get(obj) if f is not None else None
    except Exception:
        return None


def set_private_field(obj, field_name, new_value):
    try:
        f = _find_field(obj.getClass(), field_name)
        if f is None:
            return False
        f.set(obj, new_value)
        return True
    except Exception:
        return False


def _static_class(clazz):
    # find_class returns java.lang.Class; jclass(...) returns a Python proxy class
    if hasattr(clazz, "__javaclass__") and not hasattr(clazz, "getDeclaredFields"):
        return find_class(clazz.__javaclass__.replace("/", "."))
    if isinstance(clazz, type):
        name = getattr(clazz, "__javaclass__", None) or getattr(clazz, "__name__", None)
        found = find_class(str(name).replace("/", ".")) if name else None
        if found is not None:
            return found
    return clazz


def get_static_private_field(clazz, field_name):
    try:
        f = _find_field(_static_class(clazz), field_name)
        return f.get(None) if f is not None else None
    except Exception:
        return None


def set_static_private_field(clazz, field_name, new_value):
    try:
        f = _find_field(_static_class(clazz), field_name)
        if f is None:
            return False
        f.set(None, new_value)
        return True
    except Exception:
        return False
