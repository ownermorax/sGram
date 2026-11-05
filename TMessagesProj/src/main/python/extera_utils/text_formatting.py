"""HTML / Markdown -> Telegram entities (sGram implementation of the exteraGram SDK helper).

Offsets are in UTF-16 code units, like Telegram expects: text is processed with astral
characters split into surrogate pairs and joined back at the end.
"""

import html
import re
import struct
from dataclasses import dataclass
from enum import Enum
from html.parser import HTMLParser

from java import jclass

_SMP_RE = re.compile(r"[\U00010000-\U0010FFFF]")


def add_surrogates(text):
    return _SMP_RE.sub(lambda m: "".join(chr(i) for i in struct.unpack("<HH", m.group().encode("utf-16le"))), text)


def remove_surrogates(text):
    return text.encode("utf-16", "surrogatepass").decode("utf-16")


class TLEntityType(Enum):
    CODE = "code"
    PRE = "pre"
    STRIKETHROUGH = "strikethrough"
    TEXT_LINK = "text_link"
    BOLD = "bold"
    ITALIC = "italic"
    UNDERLINE = "underline"
    SPOILER = "spoiler"
    CUSTOM_EMOJI = "custom_emoji"
    BLOCKQUOTE = "blockquote"

    @classmethod
    def from_(cls, entity):
        name = entity.getClass().getSimpleName()
        for key, value in _CLASS_NAMES.items():
            if value == name:
                return key
        return None


_CLASS_NAMES = {
    TLEntityType.CODE: "TL_messageEntityCode",
    TLEntityType.PRE: "TL_messageEntityPre",
    TLEntityType.STRIKETHROUGH: "TL_messageEntityStrike",
    TLEntityType.TEXT_LINK: "TL_messageEntityTextUrl",
    TLEntityType.BOLD: "TL_messageEntityBold",
    TLEntityType.ITALIC: "TL_messageEntityItalic",
    TLEntityType.UNDERLINE: "TL_messageEntityUnderline",
    TLEntityType.SPOILER: "TL_messageEntitySpoiler",
    TLEntityType.CUSTOM_EMOJI: "TL_messageEntityCustomEmoji",
    TLEntityType.BLOCKQUOTE: "TL_messageEntityBlockquote",
}


@dataclass
class RawEntity:
    type: TLEntityType
    offset: int
    length: int
    language: str = None
    url: str = None
    document_id: int = None
    collapsed: bool = None

    @property
    def TLRPC_ENTITIES_MAP(self):
        return _CLASS_NAMES

    def to_tlrpc_object(self):
        cls = jclass("org.telegram.tgnet.TLRPC$" + _CLASS_NAMES[self.type])
        e = cls()
        e.offset = int(self.offset)
        e.length = int(self.length)
        if self.type == TLEntityType.PRE:
            e.language = self.language or ""
        elif self.type == TLEntityType.TEXT_LINK:
            e.url = self.url or ""
        elif self.type == TLEntityType.CUSTOM_EMOJI:
            e.document_id = int(self.document_id or 0)
        elif self.type == TLEntityType.BLOCKQUOTE and self.collapsed:
            e.collapsed = True
        return e


_TAGS = {
    "b": TLEntityType.BOLD, "strong": TLEntityType.BOLD,
    "i": TLEntityType.ITALIC, "em": TLEntityType.ITALIC,
    "u": TLEntityType.UNDERLINE, "ins": TLEntityType.UNDERLINE,
    "s": TLEntityType.STRIKETHROUGH, "strike": TLEntityType.STRIKETHROUGH, "del": TLEntityType.STRIKETHROUGH,
    "code": TLEntityType.CODE, "pre": TLEntityType.PRE,
    "spoiler": TLEntityType.SPOILER, "tg-spoiler": TLEntityType.SPOILER,
    "a": TLEntityType.TEXT_LINK, "blockquote": TLEntityType.BLOCKQUOTE,
    "emoji": TLEntityType.CUSTOM_EMOJI, "tg-emoji": TLEntityType.CUSTOM_EMOJI,
}


class Parser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.text = ""
        self.entities = []
        self.tag_entities = {}

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        etype = _TAGS.get(tag)
        if tag == "span" and attrs.get("class") == "tg-spoiler":
            etype = TLEntityType.SPOILER
        if etype is None:
            if tag == "br":
                self.text += "\n"
            return
        extra = {}
        if etype == TLEntityType.TEXT_LINK:
            url = attrs.get("href", "")
            m = re.match(r"^tg://emoji\?id=(\d+)", url)
            if m:
                etype, extra = TLEntityType.CUSTOM_EMOJI, {"document_id": int(m.group(1))}
            else:
                extra = {"url": url}
        elif etype == TLEntityType.PRE:
            extra = {"language": attrs.get("language", "")}
        elif etype == TLEntityType.CODE and self.tag_entities.get("pre"):
            # <pre><code class="language-x"> -> language of the pre block
            cls = attrs.get("class", "")
            if cls.startswith("language-"):
                self.tag_entities["pre"][-1].language = cls[9:]
            self.tag_entities.setdefault(tag, []).append(None)
            return
        elif etype == TLEntityType.CUSTOM_EMOJI:
            doc = attrs.get("emoji-id") or attrs.get("id") or 0
            extra = {"document_id": int(doc)}
        elif etype == TLEntityType.BLOCKQUOTE:
            extra = {"collapsed": "expandable" in attrs}
        entity = RawEntity(etype, len(self.text), 0, **extra)
        self.tag_entities.setdefault(tag, []).append(entity)

    def handle_data(self, data):
        self.text += data

    def handle_endtag(self, tag):
        stack = self.tag_entities.get(tag)
        if not stack:
            return
        entity = stack.pop()
        if entity is None:
            return
        entity.length = len(self.text) - entity.offset
        if entity.length > 0:
            self.entities.append(entity)


class HTML:
    @staticmethod
    def parse(text):
        parser = Parser()
        parser.feed(add_surrogates(text))
        parser.close()
        body = parser.text
        # strip like Telegram does, keeping offsets valid
        lead = len(body) - len(body.lstrip())
        body_stripped = body.strip()
        entities = []
        for e in parser.entities:
            e.offset -= lead
            if e.offset < 0:
                e.length += e.offset
                e.offset = 0
            if e.offset + e.length > len(body_stripped):
                e.length = len(body_stripped) - e.offset
            if e.length > 0:
                entities.append(e)
        entities.sort(key=lambda e: e.offset)
        return {"message": remove_surrogates(body_stripped), "entities": entities}

    @staticmethod
    def unparse(text, entities):
        text = add_surrogates(text)
        inserts = []
        for e in entities:
            t = e.type if isinstance(e, RawEntity) else TLEntityType.from_(e)
            off, ln = int(e.offset), int(e.length)
            if t is None:
                continue
            if t == TLEntityType.TEXT_LINK:
                start, end = '<a href="%s">' % html.escape(e.url, True), "</a>"
            elif t == TLEntityType.PRE:
                lang = getattr(e, "language", "") or ""
                start, end = ('<pre language="%s">' % lang if lang else "<pre>"), "</pre>"
            elif t == TLEntityType.CUSTOM_EMOJI:
                start, end = '<emoji id="%d">' % int(e.document_id), "</emoji>"
            else:
                tag = {TLEntityType.BOLD: "b", TLEntityType.ITALIC: "i", TLEntityType.UNDERLINE: "u",
                       TLEntityType.STRIKETHROUGH: "s", TLEntityType.CODE: "code", TLEntityType.SPOILER: "spoiler",
                       TLEntityType.BLOCKQUOTE: "blockquote"}[t]
                start, end = "<%s>" % tag, "</%s>" % tag
            inserts.append((off, 1, start))
            inserts.append((off + ln, 0, end))
        out = []
        pos = 0
        for off, _kind, tag in sorted(inserts, key=lambda x: (x[0], x[1])):
            out.append(html.escape(text[pos:off], False))
            out.append(tag)
            pos = off
        out.append(html.escape(text[pos:], False))
        return remove_surrogates("".join(out))


BOLD_DELIM = "**"
ITALIC_DELIM = "__"
UNDERLINE_DELIM = "--"
STRIKE_DELIM = "~~"
SPOILER_DELIM = "||"
CODE_DELIM = "`"
PRE_DELIM = "```"
BLOCKQUOTE_DELIM = ">"
BLOCKQUOTE_EXPANDABLE_DELIM = "**>"
BLOCKQUOTE_EXPANDABLE_END_DELIM = "<**"

MARKDOWN_RE = re.compile(r"({d})|(!?)\[(.+?)\]\((.+?)\)".format(
    d="|".join(["".join(i) for i in [
        [rf"\{j}" for j in i] for i in [PRE_DELIM, CODE_DELIM, STRIKE_DELIM, UNDERLINE_DELIM, ITALIC_DELIM, BOLD_DELIM, SPOILER_DELIM]
    ]])))
OPENING_TAG = "<{}>"
CLOSING_TAG = "</{}>"
URL_MARKUP = '<a href="{}">{}</a>'
EMOJI_MARKUP = '<emoji id="{}">{}</emoji>'
FIXED_WIDTH_DELIMS = [CODE_DELIM, PRE_DELIM]
MARKDOWN_ESCAPABLE_CHARS = "\\`*_~|[]()>-"
ESCAPED_MARKDOWN_RE = re.compile(r"\\([%s])" % re.escape(MARKDOWN_ESCAPABLE_CHARS))


def replace_once(source, old, new, start):
    return source[:start] + source[start:].replace(old, new, 1)


class Markdown:
    @staticmethod
    def protect_escaped_chars(text):
        escaped = {}

        def repl(m):
            key = "\x00%d\x00" % len(escaped)
            escaped[key] = m.group(1)
            return key
        return ESCAPED_MARKDOWN_RE.sub(repl, text), escaped

    @staticmethod
    def restore_escaped_chars(text, escaped_chars):
        for key, value in escaped_chars.items():
            text = text.replace(key, html.escape(value, False))
        return text

    @staticmethod
    def escape_and_create_quotes(text, strict):
        lines = text.split("\n")
        out = []
        in_quote = False
        for line in lines:
            if line.startswith(BLOCKQUOTE_EXPANDABLE_DELIM):
                out.append(("<blockquote expandable>" if not in_quote else "") + html.escape(line[3:], False))
                in_quote = True
            elif line.startswith(BLOCKQUOTE_DELIM) and not line.startswith(">>"):
                out.append(("<blockquote>" if not in_quote else "") + html.escape(line[1:].lstrip(), False))
                in_quote = True
            else:
                if in_quote:
                    out[-1] += "</blockquote>"
                    in_quote = False
                out.append(html.escape(line, False) if strict else line.replace("<", "&lt;"))
        if in_quote:
            if out[-1].endswith(BLOCKQUOTE_EXPANDABLE_END_DELIM):
                out[-1] = out[-1][:-len(BLOCKQUOTE_EXPANDABLE_END_DELIM)]
            out[-1] += "</blockquote>"
        return "\n".join(out)

    @classmethod
    def parse(cls, text, strict=False):
        text, escaped = cls.protect_escaped_chars(text)
        text = cls.escape_and_create_quotes(text, strict=True)
        delims = set()
        is_fixed_width = False
        for match in re.finditer(MARKDOWN_RE, text):
            start, _ = match.span()
            delim, is_emoji, text_url, url = match.groups()
            full = match.group(0)
            if delim in FIXED_WIDTH_DELIMS:
                is_fixed_width = not is_fixed_width
            if is_fixed_width and delim not in FIXED_WIDTH_DELIMS:
                continue
            if not is_emoji and text_url:
                text = replace_once(text, full, URL_MARKUP.format(url, text_url), start)
                continue
            if is_emoji:
                m = re.match(r"tg://emoji\?id=(\d+)", url)
                if m:
                    text = replace_once(text, full, EMOJI_MARKUP.format(m.group(1), text_url), start)
                continue
            tag = {BOLD_DELIM: "b", ITALIC_DELIM: "i", UNDERLINE_DELIM: "u", STRIKE_DELIM: "s",
                   CODE_DELIM: "code", PRE_DELIM: "pre", SPOILER_DELIM: "spoiler"}.get(delim)
            if tag is None:
                continue
            if delim not in delims:
                delims.add(delim)
                tag_str = OPENING_TAG.format(tag)
                if delim == PRE_DELIM:
                    # ```lang\n -> language
                    rest = text[start + 3:]
                    m = re.match(r"([\w+#-]+)\n", rest)
                    if m:
                        tag_str = '<pre language="%s">' % m.group(1)
                        text = text[:start + 3] + rest[len(m.group(0)):]
            else:
                delims.remove(delim)
                tag_str = CLOSING_TAG.format(tag)
            text = replace_once(text, delim, tag_str, start)
        text = cls.restore_escaped_chars(text, escaped)
        return HTML.parse(text)

    @staticmethod
    def unparse(text, entities):
        text = add_surrogates(text)
        inserts = []
        for e in entities:
            t = e.type if isinstance(e, RawEntity) else TLEntityType.from_(e)
            off, ln = int(e.offset), int(e.length)
            if t == TLEntityType.TEXT_LINK:
                inserts.append((off, 1, "["))
                inserts.append((off + ln, 0, "](%s)" % e.url))
                continue
            if t == TLEntityType.CUSTOM_EMOJI:
                inserts.append((off, 1, "!["))
                inserts.append((off + ln, 0, "](tg://emoji?id=%d)" % int(e.document_id)))
                continue
            d = {TLEntityType.BOLD: BOLD_DELIM, TLEntityType.ITALIC: ITALIC_DELIM, TLEntityType.UNDERLINE: UNDERLINE_DELIM,
                 TLEntityType.STRIKETHROUGH: STRIKE_DELIM, TLEntityType.CODE: CODE_DELIM, TLEntityType.PRE: PRE_DELIM,
                 TLEntityType.SPOILER: SPOILER_DELIM}.get(t)
            if d is None:
                continue
            inserts.append((off, 1, d))
            inserts.append((off + ln, 0, d))
        out = []
        pos = 0
        for off, _k, d in sorted(inserts, key=lambda x: (x[0], x[1])):
            out.append(text[pos:off])
            out.append(d)
            pos = off
        out.append(text[pos:])
        return remove_surrogates("".join(out))


def parse_text(text, parse_mode="HTML", is_caption=False):
    if parse_mode is None:
        return {"message": text, "entities": []}
    mode = str(parse_mode).lower()
    if mode == "html":
        return HTML.parse(text)
    if mode == "markdown":
        return Markdown.parse(text)
    raise ValueError("Unsupported parse_mode: %s" % parse_mode)
