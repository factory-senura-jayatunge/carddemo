"""Spring Batch execution contexts as batch-service stores them.

batch-service keeps Spring Batch's DefaultExecutionContextSerializer: a context
row holds the base64 text of a Java ObjectOutputStream that wrote a HashMap.
This reads that stream without a JVM. Strings, Long, Integer, Short, Byte and
Boolean become Python values; any other class becomes an Opaque marker that
equals no plain value, so a check of that key fails instead of guessing. The
error messages name a structural problem only, never a value.
"""
from __future__ import annotations

import base64
import binascii
import struct
from dataclasses import dataclass
from typing import Any

MAGIC = b"\xac\xed\x00\x05"
BASE_HANDLE = 0x7E0000
TC_NULL, TC_REFERENCE, TC_CLASSDESC, TC_OBJECT = 0x70, 0x71, 0x72, 0x73
TC_STRING, TC_ARRAY, TC_CLASS, TC_BLOCKDATA = 0x74, 0x75, 0x76, 0x77
TC_ENDBLOCKDATA, TC_BLOCKDATALONG, TC_LONGSTRING, TC_ENUM = 0x78, 0x7A, 0x7C, 0x7E
SC_WRITE_METHOD, SC_SERIALIZABLE, SC_EXTERNALIZABLE, SC_BLOCK_DATA = 0x01, 0x02, 0x04, 0x08
PRIMITIVES = {"B": ">b", "C": ">H", "D": ">d", "F": ">f", "I": ">i", "J": ">q", "S": ">h", "Z": ">?"}
MAPS = ("java.util.HashMap", "java.util.LinkedHashMap")
INTEGRAL = {"java.lang.Long": "J", "java.lang.Integer": "I", "java.lang.Short": "S", "java.lang.Byte": "B"}
MAX_DEPTH = 32


class ContextError(ValueError):
    """Structural decoding failure; never carries context content."""


# Identity equality: two Opaque values of one class (two different Dates, say)
# must never compare equal, and the stream's object graph may hold cycles that
# a field-by-field __eq__ would follow forever.
@dataclass(frozen=True, eq=False)
class Opaque:
    """A value of a class this reader does not turn into Python; equal only to itself."""
    class_name: str


@dataclass(eq=False)
class _ClassDesc:
    name: str
    flags: int
    fields: list
    superclass: "_ClassDesc | None"


@dataclass(eq=False)
class _Object:
    desc: _ClassDesc
    # (class name, field values by name, annotation contents) from the top superclass down.
    data: list


@dataclass(eq=False)
class _Array:
    desc: _ClassDesc
    items: list


@dataclass(eq=False)
class _Enum:
    desc: _ClassDesc
    constant: str


@dataclass(frozen=True)
class _Block:
    data: bytes


def _modified_utf8(raw: bytes) -> str:
    units, i = [], 0
    while i < len(raw):
        a = raw[i]
        if a < 0x80 and a != 0:
            units.append(a)
            i += 1
        elif a & 0xE0 == 0xC0 and i + 1 < len(raw) and raw[i + 1] & 0xC0 == 0x80:
            units.append(((a & 0x1F) << 6) | (raw[i + 1] & 0x3F))
            i += 2
        elif (a & 0xF0 == 0xE0 and i + 2 < len(raw) and raw[i + 1] & 0xC0 == 0x80
              and raw[i + 2] & 0xC0 == 0x80):
            units.append(((a & 0x0F) << 12) | ((raw[i + 1] & 0x3F) << 6) | (raw[i + 2] & 0x3F))
            i += 3
        else:
            raise ContextError("CONTEXT_STRING_ENCODING")
    try:
        return b"".join(u.to_bytes(2, "little") for u in units).decode("utf-16-le")
    except UnicodeDecodeError:
        raise ContextError("CONTEXT_STRING_ENCODING") from None


class _Reader:
    def __init__(self, raw: bytes):
        self.raw = raw
        self.pos = 0
        self.handles: list = []

    def take(self, n: int) -> bytes:
        if n < 0 or self.pos + n > len(self.raw):
            raise ContextError("CONTEXT_TRUNCATED")
        out = self.raw[self.pos:self.pos + n]
        self.pos += n
        return out

    def unpack(self, fmt: str):
        return struct.unpack(fmt, self.take(struct.calcsize(fmt)))[0]

    def utf(self) -> str:
        return _modified_utf8(self.take(self.unpack(">H")))

    def new_handle(self, value) -> int:
        self.handles.append(value)
        return len(self.handles) - 1

    def reference(self):
        index = self.unpack(">i") - BASE_HANDLE
        if not 0 <= index < len(self.handles) or self.handles[index] is None:
            raise ContextError("CONTEXT_BAD_REFERENCE")
        return self.handles[index]

    def content(self, depth: int):
        if depth > MAX_DEPTH:
            raise ContextError("CONTEXT_DEPTH")
        tc = self.unpack(">B")
        if tc == TC_NULL:
            return None
        if tc == TC_REFERENCE:
            return self.reference()
        if tc == TC_STRING:
            value = self.utf()
            self.new_handle(value)
            return value
        if tc == TC_LONGSTRING:
            value = _modified_utf8(self.take(self.unpack(">q")))
            self.new_handle(value)
            return value
        if tc == TC_BLOCKDATA:
            return _Block(self.take(self.unpack(">B")))
        if tc == TC_BLOCKDATALONG:
            return _Block(self.take(self.unpack(">i")))
        if tc == TC_CLASSDESC:
            return self.class_desc_body(depth)
        if tc == TC_CLASS:
            desc = self.class_desc(depth)
            self.new_handle(desc)
            return desc
        if tc == TC_OBJECT:
            return self.new_object(depth)
        if tc == TC_ARRAY:
            return self.new_array(depth)
        if tc == TC_ENUM:
            desc = self.class_desc(depth)
            handle = self.new_handle(None)
            name = self.content(depth + 1)
            if not isinstance(name, str):
                raise ContextError("CONTEXT_ENUM")
            self.handles[handle] = _Enum(desc, name)
            return self.handles[handle]
        # Proxy class descriptors, exceptions and resets never occur in a context map.
        raise ContextError("CONTEXT_UNSUPPORTED_TYPECODE")

    def class_desc(self, depth: int) -> _ClassDesc | None:
        value = self.content(depth + 1)
        if value is not None and not isinstance(value, _ClassDesc):
            raise ContextError("CONTEXT_CLASS_DESCRIPTOR")
        return value

    def class_desc_body(self, depth: int) -> _ClassDesc:
        name = self.utf()
        self.take(8)  # serialVersionUID
        desc = _ClassDesc(name, 0, [], None)
        self.new_handle(desc)
        desc.flags = self.unpack(">B")
        for _ in range(self.unpack(">h")):
            code = chr(self.unpack(">B"))
            field_name = self.utf()
            if code in ("L", "["):
                if not isinstance(self.content(depth + 1), str):
                    raise ContextError("CONTEXT_FIELD_TYPE")
            elif code not in PRIMITIVES:
                raise ContextError("CONTEXT_FIELD_TYPE")
            desc.fields.append((code, field_name))
        self.annotation(depth)
        superclass = self.class_desc(depth)
        # A back reference can name this descriptor, or one whose superclass is
        # still being read, as the superclass: refuse the cycle (and any chain
        # longer than MAX_DEPTH) here, so new_object's walk always ends.
        ancestor, steps = superclass, 0
        while ancestor is not None:
            if ancestor is desc or steps >= MAX_DEPTH:
                raise ContextError("CONTEXT_CLASS_DESCRIPTOR")
            ancestor, steps = ancestor.superclass, steps + 1
        desc.superclass = superclass
        return desc

    def annotation(self, depth: int) -> list:
        items = []
        while True:
            if self.pos < len(self.raw) and self.raw[self.pos] == TC_ENDBLOCKDATA:
                self.pos += 1
                return items
            items.append(self.content(depth + 1))

    def value(self, code: str, depth: int):
        if code in PRIMITIVES:
            return self.unpack(PRIMITIVES[code])
        return self.content(depth + 1)

    def new_object(self, depth: int) -> _Object:
        desc = self.class_desc(depth)
        if desc is None:
            raise ContextError("CONTEXT_CLASS_DESCRIPTOR")
        obj = _Object(desc, [])
        self.new_handle(obj)
        chain, cls = [], desc
        while cls is not None:
            chain.append(cls)
            cls = cls.superclass
        for cls in reversed(chain):
            if cls.flags & SC_EXTERNALIZABLE:
                if not cls.flags & SC_BLOCK_DATA:
                    raise ContextError("CONTEXT_EXTERNALIZABLE")
                obj.data.append((cls.name, {}, self.annotation(depth)))
                continue
            if not cls.flags & SC_SERIALIZABLE:
                raise ContextError("CONTEXT_NOT_SERIALIZABLE")
            values = {name: self.value(code, depth) for code, name in cls.fields}
            extra = self.annotation(depth) if cls.flags & SC_WRITE_METHOD else []
            obj.data.append((cls.name, values, extra))
        return obj

    def new_array(self, depth: int) -> _Array:
        desc = self.class_desc(depth)
        if desc is None or not desc.name.startswith("["):
            raise ContextError("CONTEXT_CLASS_DESCRIPTOR")
        array = _Array(desc, [])
        self.new_handle(array)
        size = self.unpack(">i")
        if size < 0 or size > len(self.raw):
            raise ContextError("CONTEXT_ARRAY_SIZE")
        code = desc.name[1]
        array.items = [self.value(code, depth) for _ in range(size)]
        return array


def _fields(obj: _Object, class_name: str) -> tuple[dict, list]:
    for name, values, extra in obj.data:
        if name == class_name:
            return values, extra
    raise ContextError("CONTEXT_CLASS_SHAPE")


def _python(value) -> Any:
    if value is None or isinstance(value, str):
        return value
    if isinstance(value, _Object):
        name = value.desc.name
        if name in INTEGRAL or name == "java.lang.Boolean":
            values, extra = _fields(value, name)
            code = INTEGRAL.get(name, "Z")
            if list(values) != ["value"] or extra or [c for c, _ in value.desc.fields] != [code]:
                raise ContextError("CONTEXT_CLASS_SHAPE")
            return values["value"]
        return Opaque(name)
    if isinstance(value, (_Array, _Enum)):
        return Opaque(value.desc.name)
    raise ContextError("CONTEXT_UNSUPPORTED_VALUE")


def _map(obj) -> dict:
    if not isinstance(obj, _Object) or obj.desc.name not in MAPS:
        raise ContextError("CONTEXT_NOT_A_MAP")
    _, extra = _fields(obj, "java.util.HashMap")
    blocks = b"".join(item.data for item in extra if isinstance(item, _Block))
    items = [item for item in extra if not isinstance(item, _Block)]
    if len(blocks) != 8:
        raise ContextError("CONTEXT_MAP_SHAPE")
    size = struct.unpack(">i", blocks[4:])[0]
    if size < 0 or len(items) != 2 * size:
        raise ContextError("CONTEXT_MAP_SHAPE")
    result = {}
    for key, value in zip(items[::2], items[1::2]):
        if not isinstance(key, str):
            raise ContextError("CONTEXT_KEY_NOT_STRING")
        if key in result:
            raise ContextError("CONTEXT_DUPLICATE_KEY")
        result[key] = _python(value)
    return result


def decode_stream(raw: bytes) -> dict:
    """The map of one ObjectOutputStream that holds a HashMap or LinkedHashMap."""
    if not raw.startswith(MAGIC):
        raise ContextError("CONTEXT_MAGIC")
    reader = _Reader(raw)
    reader.pos = len(MAGIC)
    try:
        top = reader.content(0)
    except (struct.error, RecursionError):
        raise ContextError("CONTEXT_TRUNCATED") from None
    if reader.pos != len(raw):
        raise ContextError("CONTEXT_TRAILING_BYTES")
    return _map(top)


def decode(text: str) -> dict:
    """One stored context: the base64 text DefaultExecutionContextSerializer wrote."""
    if not isinstance(text, str):
        raise ContextError("CONTEXT_NOT_TEXT")
    try:
        raw = base64.b64decode(text.encode("ascii"), validate=True)
    except (UnicodeEncodeError, binascii.Error, ValueError):
        raise ContextError("CONTEXT_BASE64") from None
    return decode_stream(raw)


def stored_text(short_context: str | None, serialized_context: str | None) -> str:
    """JdbcExecutionContextDao's rule: SERIALIZED_CONTEXT when not NULL, else SHORT_CONTEXT.

    A context longer than the short column is written whole to SERIALIZED_CONTEXT,
    and SHORT_CONTEXT then holds a truncated prefix that must never be decoded.
    """
    text = serialized_context if serialized_context is not None else short_context
    if text is None:
        raise ContextError("CONTEXT_MISSING")
    return text
