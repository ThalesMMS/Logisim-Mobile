"""Parse the retained OpenStep inventory subset without Apple tools.

Values are dictionaries, arrays or strings; numeric-looking atoms stay strings.
Only quote/backslash escapes are accepted. Comments and data literals are not.
Arrays allow comma-separated values and an optional final comma.
"""

MAX_INPUT_BYTES = 1_048_576
MAX_DEPTH = 32
_WHITESPACE = frozenset(" \t\r\n")
_ATOM = frozenset("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_$./:-")


def parse(text: str) -> object:
    """Return one complete value, rejecting malformed or oversized input."""
    if not isinstance(text, str):
        raise TypeError("OpenStep input must be a string")
    if len(text) > MAX_INPUT_BYTES or len(text.encode("utf-8")) > MAX_INPUT_BYTES:
        raise ValueError("OpenStep input exceeds the byte limit")
    parser = _Parser(text)
    value = parser.value(0)
    parser.space()
    if parser.position != len(text):
        parser.fail("Trailing content")
    return value


class _Parser:
    def __init__(self, text: str):
        self.text = text
        self.position = 0

    def fail(self, message: str):
        raise ValueError(f"{message} at offset {self.position}")

    def space(self):
        while self.position < len(self.text) and self.text[self.position] in _WHITESPACE:
            self.position += 1

    def accept(self, token: str) -> bool:
        self.space()
        if self.text.startswith(token, self.position):
            self.position += len(token)
            return True
        return False

    def expect(self, token: str):
        if not self.accept(token):
            self.fail(f"Expected {token!r}")

    def value(self, depth: int) -> object:
        if depth > MAX_DEPTH:
            self.fail("Nesting exceeds the depth limit")
        self.space()
        if self.accept("{"):
            result = {}
            while not self.accept("}"):
                key = self.string()
                if key in result:
                    self.fail("Duplicate dictionary key")
                self.expect("=")
                result[key] = self.value(depth + 1)
                self.expect(";")
            return result
        if self.accept("("):
            result = []
            if self.accept(")"):
                return result
            while True:
                result.append(self.value(depth + 1))
                if self.accept(")"):
                    return result
                self.expect(",")
                if self.accept(")"):
                    return result
        return self.string()

    def string(self) -> str:
        self.space()
        if self.accept('"'):
            characters = []
            while self.position < len(self.text):
                character = self.text[self.position]
                self.position += 1
                if character == '"':
                    return "".join(characters)
                if character == "\\":
                    if self.position == len(self.text):
                        self.fail("Truncated escape")
                    character = self.text[self.position]
                    self.position += 1
                    if character not in ('"', "\\"):
                        self.fail("Unsupported escape")
                elif ord(character) < 0x20:
                    self.fail("Control character in quoted string")
                characters.append(character)
            self.fail("Unterminated quoted string")
        start = self.position
        while self.position < len(self.text) and self.text[self.position] in _ATOM:
            self.position += 1
        if start == self.position:
            self.fail("Expected a string")
        atom = self.text[start:self.position]
        if "//" in atom:
            self.fail("Comments are unsupported")
        return atom
