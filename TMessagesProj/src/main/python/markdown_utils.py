"""exteraGram plugin SDK: markdown_utils (sGram implementation)."""

from dataclasses import dataclass

from extera_utils.text_formatting import Markdown, RawEntity, TLEntityType

__all__ = ["parse_markdown"]


@dataclass
class ParsedMessage:
    text: str
    entities: tuple


def parse_markdown(markdown):
    parsed = Markdown.parse(markdown)
    return ParsedMessage(parsed["message"], tuple(parsed["entities"]))
