"""Resolve desktop factory names from source, without loading desktop Java code.

This is a deliberately small source reader, not a Java interpreter. It accepts
literal IDs, constant references, getName() returns, and constructor super/this
arguments. Unsupported or ambiguous expressions return None, never a guessed ID.
"""
from __future__ import annotations

from dataclasses import dataclass
import json
from pathlib import Path
import re


TOKEN = re.compile(r'/\*[\s\S]*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[A-Za-z_$][\w$]*|[^\s]')


def tokens(source: str) -> list[str]:
    return [match[0] for match in TOKEN.finditer(source) if not match[0].startswith(("//", "/*"))]


def closing(parts: list[str], start: int, left: str, right: str) -> int | None:
    depth = 0
    for index in range(start, len(parts)):
        if parts[index] == left:
            depth += 1
        elif parts[index] == right:
            depth -= 1
            if depth == 0:
                return index
    return None


def split_arguments(parts: list[str], separator: str = ",") -> list[list[str]]:
    if not parts:
        return []
    result, start, depth = [], 0, 0
    for index, part in enumerate(parts):
        if part in ("(", "[", "{"):
            depth += 1
        elif part in (")", "]", "}"):
            depth -= 1
        elif part == separator and depth == 0:
            result.append(parts[start:index])
            start = index + 1
    return result + [parts[start:]]


@dataclass
class ClassSource:
    path: Path
    name: str
    enclosing: str | None
    superclass: str | None
    imports: list[str]
    fields: dict[str, list[str]]
    public_static_fields: set[str]
    methods: list[tuple[list[str], list[str]]]


def read_class(path: Path, class_name: str) -> ClassSource | None:
    parts = tokens(path.read_text())
    imports = []
    for index, part in enumerate(parts):
        if part == "import":
            end = parts.index(";", index)
            imports.append("".join(parts[index + 1:end]))
    declarations = [index for index in range(len(parts) - 1) if parts[index:index + 2] == ["class", class_name]]
    if len(declarations) != 1:
        return None
    start = declarations[0]
    enclosing = None
    for index in range(start):
        if parts[index] == "class" and re.fullmatch(r"[A-Za-z_$][\w$]*", parts[index + 1]):
            parent_start = parts.index("{", index)
            parent_end = closing(parts, parent_start, "{", "}")
            if parent_end is not None and parent_start < start < parent_end:
                enclosing = parts[index + 1]
    class_header = start
    start = parts.index("{", start)
    header = parts[class_header + 2:start]
    superclass = None
    if "extends" in header:
        index = header.index("extends") + 1
        names = []
        while index < len(header) and (header[index] == "." or re.fullmatch(r"[A-Za-z_$][\w$]*", header[index])):
            if header[index] == "implements":
                break
            names.append(header[index])
            index += 1
        superclass = "".join(names) or None
    end = closing(parts, start, "{", "}")
    if end is None:
        return None
    body = parts[start + 1:end]
    fields, public_static, methods = {}, set(), []
    member_start, index = 0, 0
    while index < len(body):
        if body[index] == "{":
            member_end = closing(body, index, "{", "}")
            if member_end is None:
                return None
            methods.append((body[member_start:index], body[index + 1:member_end]))
            index = member_end + 1
            if index < len(body) and body[index] == ";":
                index += 1
            member_start = index
        elif body[index] == ";":
            declaration = body[member_start:index]
            if "=" in declaration:
                equals = declaration.index("=")
                if equals > 0:
                    fields[declaration[equals - 1]] = declaration[equals + 1:]
                    if "public" in declaration[:equals] and "static" in declaration[:equals]:
                        public_static.add(declaration[equals - 1])
            index += 1
            member_start = index
        else:
            index += 1
    return ClassSource(path, class_name, enclosing, superclass, imports, fields, public_static, methods)


def signature(header: list[str]) -> tuple[str, list[str]] | None:
    if not header or header[-1] != ")":
        return None
    for index, part in enumerate(header):
        if part == "(" and closing(header, index, "(", ")") == len(header) - 1:
            if index == 0:
                return None
            parameters = split_arguments(header[index + 1:-1])
            if any(not parameter for parameter in parameters):
                return None
            return header[index - 1], [parameter[-1] for parameter in parameters]
    return None


class SourceIds:
    def __init__(self, desktop: Path):
        self.desktop = desktop.resolve()
        self.classes: dict[tuple[Path, str], ClassSource | None] = {}

    def read(self, path: Path, name: str) -> ClassSource | None:
        path = (path if path.is_absolute() else self.desktop / path).resolve()
        if not path.is_relative_to(self.desktop) or not path.is_file():
            return None
        key = path, name
        if key not in self.classes:
            self.classes[key] = read_class(path, name)
        return self.classes[key]

    def referenced_class(self, source: ClassSource, name: str) -> ClassSource | None:
        qualified = name if "." in name else next((value for value in source.imports
                    if not value.startswith("static") and value.endswith("." + name)), None)
        path = (self.desktop / "src/main/java" / (qualified.replace(".", "/") + ".java")
                if qualified else source.path.with_name(name + ".java"))
        return self.read(path, name.rsplit(".", 1)[-1])

    def evaluate(self, source: ClassSource, expression: list[str], environment: dict, seen: set):
        if not expression:
            return None
        if expression[0] == "(" and closing(expression, 0, "(", ")") == len(expression) - 1:
            return self.evaluate(source, expression[1:-1], environment, seen)
        branches = split_arguments(expression, "?")
        if len(branches) == 2:
            choices = split_arguments(branches[1], ":")
            condition = self.evaluate(source, branches[0], environment, seen)
            if isinstance(condition, bool) and len(choices) == 2:
                return self.evaluate(source, choices[0 if condition else 1], environment, seen)
            return None
        concatenation = split_arguments(expression, "+")
        if len(concatenation) > 1:
            values = [self.evaluate(source, item, environment, seen) for item in concatenation]
            return "".join(values) if all(isinstance(value, str) for value in values) else None
        if len(expression) == 1:
            name = expression[0]
            if name.startswith('"'):
                return json.loads(name)
            if name in ("true", "false"):
                return name == "true"
            if name in environment:
                return environment[name]
            key = source.path, source.name, name
            if name in source.fields and key not in seen:
                return self.evaluate(source, source.fields[name], environment, seen | {key})
            if source.enclosing:
                outer = self.read(source.path, source.enclosing)
                if outer:
                    return self.evaluate(outer, expression, {}, seen)
            return None
        if len(expression) >= 3 and expression[-2] == "." and all(
                part == "." if index % 2 else bool(re.fullmatch(r"[A-Za-z_$][\w$]*", part))
                for index, part in enumerate(expression)):
            owner = "".join(expression[:-2])
            referenced = source if owner == source.name else self.referenced_class(source, owner)
            if referenced:
                return self.evaluate(referenced, expression[-1:], {}, seen)
        return None

    def constructor_id(self, source: ClassSource, arguments: list, visited: set[int]) -> str | None:
        found = []
        for index, (header, body) in enumerate(source.methods):
            method = signature(header)
            if method is None or method[0] != source.name or len(method[1]) != len(arguments) or index in visited:
                continue
            if len(body) < 3 or body[0] not in ("super", "this") or body[1] != "(":
                continue
            end = closing(body, 1, "(", ")")
            if end is None:
                continue
            environment = dict(zip(method[1], arguments))
            args = [self.evaluate(source, item, environment, set()) for item in split_arguments(body[2:end])]
            value = (self.constructor_id(source, args, visited | {index}) if body[0] == "this"
                     else args[0] if args and isinstance(args[0], str) else None)
            found.append(value)
        return found[0] if found and found[0] is not None and all(value == found[0] for value in found) else None

    def factory(self, source: ClassSource, expression: str | None) -> str | None:
        arguments = []
        if expression and expression != source.name:
            parts = expression.split(".")
            if len(parts) != 2 or parts[0] != source.name or parts[1] not in source.fields:
                return None
            construction = source.fields[parts[-1]]
            if len(construction) < 4 or construction[0] != "new" or construction[2] != "(" or construction[-1] != ")":
                return None
            arguments = [self.evaluate(source, item, {}, set()) for item in split_arguments(construction[3:-1])]
            if construction[1] != source.name:
                source = (self.read(source.path, construction[1]) or self.referenced_class(source, construction[1]))
                if source is None:
                    return None
        return self.resolved_factory(source, arguments)

    def resolved_factory(self, source: ClassSource, arguments: list) -> str | None:
        for header, body in source.methods:
            if signature(header) == ("getName", []):
                if body[:1] == ["return"] and body[-1:] == [";"]:
                    value = self.evaluate(source, body[1:-1], {}, set())
                    return value if isinstance(value, str) else None
                return None
        value = self.constructor_id(source, arguments, set())
        if value is not None:
            return value
        # Base tools name themselves through Tool.getName()/LibraryUtil, not a
        # super argument. Only retain that fallback when its source is verified.
        parent = source
        visited = set()
        while parent.superclass and (parent.path, parent.name) not in visited:
            visited.add((parent.path, parent.name))
            parent = self.referenced_class(parent, parent.superclass)
            if parent is None:
                return None
            getter = next((body for header, body in parent.methods if signature(header) == ("getName", [])), None)
            if getter is not None:
                return self.reflected_id(source, parent, getter)
        has_constructor = any(method is not None and method[0] == source.name
                              for method in (signature(header) for header, _ in source.methods))
        if not source.superclass and not has_constructor:
            value = self.evaluate(source, ["_ID"], {}, set())
        return value if isinstance(value, str) else None

    def reflected_id(self, source: ClassSource, owner: ClassSource, getter: list[str]) -> str | None:
        if getter != tokens("return LibraryUtil.getName(getClass());") or "_ID" not in source.public_static_fields:
            return None
        library = self.referenced_class(owner, "LibraryUtil")
        if library is None or self.evaluate(library, ["ID_FIELD_NAME"], {}, set()) != "_ID":
            return None
        body = next((body for header, body in library.methods if signature(header) == ("getName", ["cls"])), [])
        required = ("cls.getDeclaredFields()", "field.getName().equals(ID_FIELD_NAME)", "field.get(null)", "return id;")
        for text in required:
            pattern = tokens(text)
            if not any(body[index:index + len(pattern)] == pattern for index in range(len(body) - len(pattern) + 1)):
                return None
        value = self.evaluate(source, ["_ID"], {}, set())
        return value if isinstance(value, str) else None


def factory_id(desktop: Path, source: Path, class_name: str, expression: str | None = None) -> str | None:
    """Read a factory ID; expression identifies entries such as ControlledBuffer.FACTORY_BUFFER.

    source is the factory's Java file, absolute or relative to desktop, not its
    library registration file. No Java code is executed. Missing source and
    unsupported syntax yield None so the caller can require a reviewed mapping
    instead of silently inventing a name.
    """
    try:
        resolver = SourceIds(desktop)
        model = resolver.read(source, class_name)
        return resolver.factory(model, expression) if model else None
    except (OSError, ValueError, RecursionError):
        return None
