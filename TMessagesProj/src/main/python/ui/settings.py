"""exteraGram plugin SDK: ui.settings (sGram implementation).
The items are rendered by org.telegram.ui.SgramPluginSettingsActivity."""

from dataclasses import dataclass, field
from typing import Any, Callable, List

TYPE_SWITCH = "switch"
TYPE_SELECTOR = "selector"
TYPE_INPUT = "input"
TYPE_TEXT = "text"
TYPE_HEADER = "header"
TYPE_DIVIDER = "divider"
TYPE_EDIT_TEXT = "edit_text"
TYPE_CUSTOM = "custom"


@dataclass
class Switch:
    key: str
    text: str
    default: bool
    subtext: str = None
    icon: str = None
    on_change: Callable = field(default=None, compare=False, repr=False)
    type: str = field(default=TYPE_SWITCH, init=False)
    on_long_click: Callable = field(default=None, compare=False, repr=False)
    link_alias: str = None


@dataclass
class Selector:
    key: str
    text: str
    default: int
    items: List[str]
    icon: str = None
    on_change: Callable = field(default=None, compare=False, repr=False)
    type: str = field(default=TYPE_SELECTOR, init=False)
    on_long_click: Callable = field(default=None, compare=False, repr=False)
    link_alias: str = None


@dataclass
class Input:
    key: str
    text: str
    default: str = ""
    subtext: str = None
    icon: str = None
    on_change: Callable = field(default=None, compare=False, repr=False)
    type: str = field(default=TYPE_INPUT, init=False)
    on_long_click: Callable = field(default=None, compare=False, repr=False)
    link_alias: str = None


@dataclass
class Text:
    text: str
    subtext: str = None
    icon: str = None
    accent: bool = False
    red: bool = False
    on_click: Callable = field(default=None, compare=False, repr=False)
    create_sub_fragment: Callable = field(default=None, compare=False, repr=False)
    type: str = field(default=TYPE_TEXT, init=False)
    on_long_click: Callable = field(default=None, compare=False, repr=False)
    link_alias: str = None


@dataclass
class Header:
    text: str
    type: str = field(default=TYPE_HEADER, init=False)


@dataclass
class Divider:
    text: str = None
    type: str = field(default=TYPE_DIVIDER, init=False)


@dataclass
class EditText:
    key: str
    hint: str
    default: str = ""
    multiline: bool = False
    max_length: int = 0
    mask: str = None
    on_change: Callable = field(default=None, compare=False, repr=False)
    type: str = field(default=TYPE_EDIT_TEXT, init=False)


@dataclass
class Custom:
    type: str = field(default=TYPE_CUSTOM, init=False)
    item: Any = field(default=None, compare=False, repr=False)
    view: Any = field(default=None, compare=False, repr=False)
    factory: Any = field(default=None, compare=False, repr=False)
    factory_args: Any = field(default=None, compare=False, repr=False)
    on_click: Callable = field(default=None, compare=False, repr=False)
    on_long_click: Callable = field(default=None, compare=False, repr=False)
    create_sub_fragment: Callable = field(default=None, compare=False, repr=False)
    link_alias: str = None

    def __post_init__(self):
        pass


class SimpleSettingFactory:
    """Custom views built by Python callbacks; sGram shows the view from create_view()."""

    def __init__(self, create_view, bind_view, *, is_clickable=False, is_shadow=False, create_item=None,
                 on_click=None, on_long_click=None, attached_view=None, equals=None, content_equals=None):
        self.create_view = create_view
        self.bind_view = bind_view
        self.is_clickable = is_clickable
        self.is_shadow = is_shadow
        self.create_item = create_item
        self.on_click = on_click
        self.on_long_click = on_long_click
        self.attached_view = attached_view
        self.equals = equals
        self.content_equals = content_equals
        self.instance = None

    def release(self):
        pass

    def __call__(self, *args, create_sub_fragment=None, link_alias=None):
        return Custom(factory=self, factory_args=args, on_click=self.on_click, on_long_click=self.on_long_click,
                      create_sub_fragment=create_sub_fragment, link_alias=link_alias)
