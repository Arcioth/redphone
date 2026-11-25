#!/usr/bin/env python3
"""Minimal, dependency-free Android binary XML (AXML) encoder.

Compiles a text AndroidManifest.xml / res/xml/*.xml into the binary format the
Android framework reads, the same job aapt2 does for the official toolchain.

Attribute ids, value formats and enum/flag symbols for the ``android:``
namespace are read straight from the framework ``resources.arsc`` shipped in
Robolectric's ``android-all`` jar (Maven Central), so values are typed exactly
like aapt2 would type them (booleans, ints, enums, flags, references...).
"""
from __future__ import annotations

import re
import struct
import xml.etree.ElementTree as ET
import zipfile

ANDROID_NS = "http://schemas.android.com/apk/res/android"
TOOLS_NS = "http://schemas.android.com/tools"

# Chunk types (frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h)
RES_STRING_POOL_TYPE = 0x0001
RES_TABLE_TYPE = 0x0002
RES_XML_TYPE = 0x0003
RES_XML_START_NAMESPACE_TYPE = 0x0100
RES_XML_END_NAMESPACE_TYPE = 0x0101
RES_XML_START_ELEMENT_TYPE = 0x0102
RES_XML_END_ELEMENT_TYPE = 0x0103
RES_XML_RESOURCE_MAP_TYPE = 0x0180
RES_TABLE_PACKAGE_TYPE = 0x0200
RES_TABLE_TYPE_TYPE = 0x0201
RES_TABLE_TYPE_SPEC_TYPE = 0x0202

# Res_value data types
TYPE_NULL = 0x00
TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_FLOAT = 0x04
TYPE_DIMENSION = 0x05
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
TYPE_INT_BOOLEAN = 0x12
TYPE_INT_COLOR_ARGB8 = 0x1C
TYPE_INT_COLOR_RGB8 = 0x1D
TYPE_INT_COLOR_ARGB4 = 0x1E
TYPE_INT_COLOR_RGB4 = 0x1F

# Attribute format bits (ResTable_map::TYPE_*)
FMT_REFERENCE = 1 << 0
FMT_STRING = 1 << 1
FMT_INTEGER = 1 << 2
FMT_BOOLEAN = 1 << 3
FMT_COLOR = 1 << 4
FMT_FLOAT = 1 << 5
FMT_DIMENSION = 1 << 6
FMT_FRACTION = 1 << 7
FMT_ENUM = 1 << 16
FMT_FLAGS = 1 << 17
ATTR_TYPE = 0x01000000

NO_INDEX = 0xFFFFFFFF


class AxmlError(Exception):
    pass


# --------------------------------------------------------------------------
# String pool
# --------------------------------------------------------------------------

def _pad4(b: bytes) -> bytes:
    return b + b"\x00" * ((4 - len(b) % 4) % 4)


def encode_string_pool(strings: list[str], utf8: bool) -> bytes:
    data = bytearray()
    offsets = []
    for s in strings:
        offsets.append(len(data))
        if utf8:
            raw = s.encode("utf-8")
            n_chars = len(s.encode("utf-16-le")) // 2
            for n in (n_chars, len(raw)):
                if n > 0x7F:
                    data += bytes([0x80 | ((n >> 8) & 0x7F), n & 0xFF])
                else:
                    data += bytes([n])
            data += raw + b"\x00"
        else:
            raw = s.encode("utf-16-le")
            n = len(raw) // 2
            if n > 0x7FFF:
                data += struct.pack("<HH", 0x8000 | ((n >> 16) & 0x7FFF), n & 0xFFFF)
            else:
                data += struct.pack("<H", n)
            data += raw + b"\x00\x00"
    data = _pad4(bytes(data))
    header_size = 28
    strings_start = header_size + 4 * len(strings)
    flags = 0x100 if utf8 else 0
    body = b"".join(struct.pack("<I", o) for o in offsets) + data
    size = header_size + len(body)
    head = struct.pack(
        "<HHIIIIII",
        RES_STRING_POOL_TYPE,
        header_size,
        size,
        len(strings),
        0,
        flags,
        strings_start,
        0,
    )
    return head + body


def decode_string_pool(buf: bytes, off: int) -> list[str]:
    _t, hsz, _size, count, _styles, flags, sstart, _ = struct.unpack_from("<HHIIIIII", buf, off)
    utf8 = bool(flags & 0x100)
    offs = struct.unpack_from(f"<{count}I", buf, off + hsz)
    base = off + sstart
    out = []
    for o in offs:
        p = base + o
        if utf8:
            n = buf[p]
            p += 2 if n & 0x80 else 1
            blen = buf[p]
            if blen & 0x80:
                blen = ((blen & 0x7F) << 8) | buf[p + 1]
                p += 2
            else:
                p += 1
            out.append(buf[p : p + blen].decode("utf-8", errors="replace"))
        else:
            n = struct.unpack_from("<H", buf, p)[0]
            p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", buf, p)[0]
                p += 2
            out.append(buf[p : p + 2 * n].decode("utf-16-le", errors="replace"))
    return out


# --------------------------------------------------------------------------
# Framework resource database (android:* attrs, styles, enums, flags)
# --------------------------------------------------------------------------

class FrameworkDb:
    """Parses the framework resources.arsc for attrs (format + enum/flag symbols)
    and for every public resource name -> id (used to resolve @android:*)."""

    def __init__(self, arsc: bytes) -> None:
        self.ids: dict[tuple[str, str], int] = {}  # (type, name) -> resid
        self.names: dict[int, tuple[str, str]] = {}
        self.attr_bags: dict[int, list[tuple[int, int, int]]] = {}  # resid -> [(name, type, data)]
        self._parse(arsc)
        self.attrs: dict[str, dict] = {}
        for (tname, ename), rid in self.ids.items():
            if tname != "attr":
                continue
            fmt = 0xFFFF
            symbols: dict[str, int] = {}
            for name_id, _dt, data in self.attr_bags.get(rid, []):
                if name_id == ATTR_TYPE:
                    fmt = data
                elif (name_id >> 24) == 0x01 and name_id in self.names:
                    symbols[self.names[name_id][1]] = data & 0xFFFFFFFF
            self.attrs[ename] = {"id": rid, "format": fmt, "symbols": symbols}

    @classmethod
    def from_android_all(cls, jar_path: str) -> "FrameworkDb":
        with zipfile.ZipFile(jar_path) as zf:
            return cls(zf.read("resources.arsc"))

    def _parse(self, buf: bytes) -> None:
        t, hsz, size, _pkg_count = struct.unpack_from("<HHII", buf, 0)
        if t != RES_TABLE_TYPE:
            raise AxmlError("not a resource table")
        off = hsz
        while off < size:
            ct, chsz, csize = struct.unpack_from("<HHI", buf, off)
            if ct == RES_TABLE_PACKAGE_TYPE:
                self._parse_package(buf, off)
            off += csize

    def _parse_package(self, buf: bytes, poff: int) -> None:
        _t, phsz, psize, pkg_id = struct.unpack_from("<HHII", buf, poff)
        type_strings_off, _lpt, key_strings_off = struct.unpack_from("<III", buf, poff + 12 + 256)
        type_names = decode_string_pool(buf, poff + type_strings_off)
        key_names = decode_string_pool(buf, poff + key_strings_off)
        off = poff + phsz
        end = poff + psize
        while off < end:
            ct, chsz, csize = struct.unpack_from("<HHI", buf, off)
            if ct == RES_TABLE_TYPE_TYPE:
                self._parse_type(buf, off, pkg_id, type_names, key_names)
            off += csize

    def _parse_type(self, buf, off, pkg_id, type_names, key_names) -> None:
        _t, hsz, _size = struct.unpack_from("<HHI", buf, off)
        type_id, flags, _res, entry_count, entries_start = struct.unpack_from("<BBHII", buf, off + 8)
        cfg_size = struct.unpack_from("<I", buf, off + 20)[0]
        # Only the default configuration is needed for names + attr bags.
        cfg = buf[off + 24 : off + 20 + cfg_size]
        if any(cfg):
            return
        tname = type_names[type_id - 1]
        idx_base = off + hsz
        pairs: list[tuple[int, int]] = []
        if flags & 0x01:  # FLAG_SPARSE
            for i in range(entry_count):
                idx, off4 = struct.unpack_from("<HH", buf, idx_base + 4 * i)
                pairs.append((idx, off4 * 4))
        elif flags & 0x02:  # FLAG_OFFSET16
            for i in range(entry_count):
                o = struct.unpack_from("<H", buf, idx_base + 2 * i)[0]
                if o != 0xFFFF:
                    pairs.append((i, o * 4))
        else:
            for i in range(entry_count):
                o = struct.unpack_from("<I", buf, idx_base + 4 * i)[0]
                if o != NO_INDEX:
                    pairs.append((i, o))
        for idx, eo in pairs:
            ep = off + entries_start + eo
            esize, eflags = struct.unpack_from("<HH", buf, ep)
            if eflags & 0x0008:  # FLAG_COMPACT
                key = esize
            else:
                key = struct.unpack_from("<I", buf, ep + 4)[0]
            rid = (pkg_id << 24) | (type_id << 16) | idx
            name = key_names[key]
            self.ids[(tname, name)] = rid
            self.names[rid] = (tname, name)
            if (eflags & 0x0001) and not (eflags & 0x0008):  # FLAG_COMPLEX bag
                _parent, count = struct.unpack_from("<II", buf, ep + 8)
                mp = ep + esize
                bag = []
                for j in range(count):
                    mname, _vs, _r0, dtype, data = struct.unpack_from("<IHBBI", buf, mp + 12 * j)
                    bag.append((mname, dtype, data))
                self.attr_bags[rid] = bag


# --------------------------------------------------------------------------
# Value encoding
# --------------------------------------------------------------------------

_DIM_UNITS = {"px": 0, "dp": 1, "dip": 1, "sp": 2, "pt": 3, "in": 4, "mm": 5}


def _parse_int(s: str) -> int | None:
    s = s.strip()
    try:
        if s.lower().startswith(("0x", "-0x")):
            return int(s, 16)
        if re.fullmatch(r"[-+]?\d+", s):
            return int(s, 10)
    except ValueError:
        return None
    return None


def _u32(v: int) -> int:
    return v & 0xFFFFFFFF


class ResourceResolver:
    def __init__(self, fw: FrameworkDb, app_ids: dict[tuple[str, str], int] | None = None) -> None:
        self.fw = fw
        self.app_ids = app_ids or {}

    def resolve_ref(self, value: str) -> int:
        m = re.fullmatch(r"@(?:\+)?(?:(android):)?([a-zA-Z_]+)/([A-Za-z0-9_.]+)", value)
        if not m:
            raise AxmlError(f"bad reference {value!r}")
        pkg, rtype, rname = m.groups()
        if pkg == "android":
            key = (rtype, rname)
            if key not in self.fw.ids:
                key = (rtype, rname.replace("_", "."))
            if key not in self.fw.ids:
                raise AxmlError(f"unknown framework resource {value!r}")
            return self.fw.ids[key]
        key = (rtype, rname)
        if key not in self.app_ids:
            raise AxmlError(f"unknown app resource {value!r} (known: {sorted(self.app_ids)})")
        return self.app_ids[key]

    def encode(self, attr_name: str, value: str, fmt: int | None, symbols: dict[str, int]) -> tuple[int, int, bool]:
        """Returns (dataType, data, keep_raw_string)."""
        v = value
        if v.startswith("@"):
            if v == "@null":
                return TYPE_REFERENCE, 0, False
            return TYPE_REFERENCE, self.resolve_ref(v), False
        if fmt is None:  # attribute outside the android namespace: plain string
            return TYPE_STRING, 0, True
        if fmt & (FMT_ENUM | FMT_FLAGS) and symbols:
            parts = [p.strip() for p in v.split("|")]
            if all(p in symbols for p in parts):
                acc = 0
                for p in parts:
                    acc |= symbols[p]
                return TYPE_INT_HEX if fmt & FMT_FLAGS else TYPE_INT_DEC, _u32(acc), False
        if fmt & FMT_BOOLEAN and v in ("true", "false"):
            return TYPE_INT_BOOLEAN, 0xFFFFFFFF if v == "true" else 0, False
        if fmt & FMT_INTEGER:
            iv = _parse_int(v)
            if iv is not None:
                return (TYPE_INT_HEX if v.lower().startswith("0x") else TYPE_INT_DEC), _u32(iv), False
        if fmt & FMT_COLOR and re.fullmatch(r"#[0-9a-fA-F]{3,8}", v):
            h = v[1:]
            if len(h) == 3:
                h = "ff" + "".join(c * 2 for c in h)
            elif len(h) == 4:
                h = "".join(c * 2 for c in h)
            elif len(h) == 6:
                h = "ff" + h
            return TYPE_INT_COLOR_ARGB8, int(h, 16), False
        if fmt & FMT_FLOAT:
            try:
                f = float(v)
                return TYPE_FLOAT, struct.unpack("<I", struct.pack("<f", f))[0], False
            except ValueError:
                pass
        if fmt & FMT_DIMENSION:
            m = re.fullmatch(r"([-+]?\d+)(px|dp|dip|sp|pt|in|mm)", v)
            if m:  # integer dimensions only (radix 23p0)
                mag = int(m.group(1))
                return TYPE_DIMENSION, _u32((mag << 8) | _DIM_UNITS[m.group(2)]), False
        if fmt & FMT_STRING or fmt == 0xFFFF:
            return TYPE_STRING, 0, True
        raise AxmlError(f"cannot encode android:{attr_name}={value!r} (format=0x{fmt:x})")


# --------------------------------------------------------------------------
# XML compiler
# --------------------------------------------------------------------------

def _split_tag(tag: str) -> tuple[str | None, str]:
    if tag.startswith("{"):
        ns, local = tag[1:].split("}", 1)
        return ns, local
    return None, tag


def compile_xml(text: str, resolver: ResourceResolver) -> bytes:
    # ElementTree drops xmlns declarations; we only support the android ns (+ tools, stripped).
    root = ET.fromstring(text)

    elements: list[tuple] = []  # flattened event list

    attr_res_names: list[tuple[str, int]] = []  # (name, resid) - must be first in pool
    attr_res_index: dict[tuple[str, int], int] = {}
    other_strings: list[str] = []
    other_index: dict[str, int] = {}

    def res_attr_ref(name: str, rid: int) -> tuple:
        key = (name, rid)
        if key not in attr_res_index:
            attr_res_index[key] = len(attr_res_names)
            attr_res_names.append(key)
        return ("R", key)

    def str_ref(s: str) -> tuple:
        if s not in other_index:
            other_index[s] = len(other_strings)
            other_strings.append(s)
        return ("S", s)

    uses_android_ns = False

    def walk(el: ET.Element) -> None:
        nonlocal uses_android_ns
        ns, local = _split_tag(el.tag)
        if ns is not None:
            raise AxmlError(f"namespaced elements are not supported: {el.tag}")
        attrs = []
        for k, v in el.attrib.items():
            ans, aname = _split_tag(k)
            if ans == TOOLS_NS:
                continue
            if ans == ANDROID_NS:
                uses_android_ns = True
                info = resolver.fw.attrs.get(aname)
                if info is None:
                    raise AxmlError(f"unknown attribute android:{aname}")
                dtype, data, keep_raw = resolver.encode(aname, v, info["format"], info["symbols"])
                attrs.append(
                    {
                        "ns": ANDROID_NS,
                        "name_ref": res_attr_ref(aname, info["id"]),
                        "rid": info["id"],
                        "raw": str_ref(v) if keep_raw else None,
                        "dtype": dtype,
                        "data": data,
                        "sort": (0, info["id"], aname),
                    }
                )
            elif ans is None:
                attrs.append(
                    {
                        "ns": None,
                        "name_ref": str_ref(aname),
                        "rid": None,
                        "raw": str_ref(v),
                        "dtype": TYPE_STRING,
                        "data": 0,
                        "sort": (1, 0, aname),
                    }
                )
            else:
                raise AxmlError(f"unsupported attribute namespace {ans}")
        # aapt2 orders attributes by resource id (required by the framework's
        # AttributeResolution, which walks both lists in ascending id order).
        attrs.sort(key=lambda a: a["sort"])
        elements.append(("start", str_ref(local), attrs))
        for child in el:
            walk(child)
        elements.append(("end", str_ref(local)))

    walk(root)
    if uses_android_ns:
        str_ref("android")
        str_ref(ANDROID_NS)

    pool = [n for n, _ in attr_res_names] + other_strings

    def idx(ref: tuple | None) -> int:
        if ref is None:
            return NO_INDEX
        kind, key = ref
        if kind == "R":
            return attr_res_index[key]
        return len(attr_res_names) + other_index[key]

    out = bytearray()
    out += encode_string_pool(pool, utf8=False)
    res_map = [rid for _n, rid in attr_res_names]
    out += struct.pack("<HHI", RES_XML_RESOURCE_MAP_TYPE, 8, 8 + 4 * len(res_map))
    out += b"".join(struct.pack("<I", r) for r in res_map)

    line = 1
    if uses_android_ns:
        out += struct.pack(
            "<HHIIIII",
            RES_XML_START_NAMESPACE_TYPE,
            16,
            24,
            line,
            NO_INDEX,
            idx(("S", "android")),
            idx(("S", ANDROID_NS)),
        )
    for ev in elements:
        line += 1
        if ev[0] == "start":
            _, name_ref, attrs = ev
            id_index = class_index = style_index = 0
            body = bytearray()
            for i, a in enumerate(attrs, start=1):
                ns_idx = idx(("S", ANDROID_NS)) if a["ns"] == ANDROID_NS else NO_INDEX
                raw_idx = idx(a["raw"]) if a["raw"] is not None else NO_INDEX
                data = a["data"]
                if a["dtype"] == TYPE_STRING:
                    data = raw_idx
                body += struct.pack("<IIIHBBI", ns_idx, idx(a["name_ref"]), raw_idx, 8, 0, a["dtype"], data)
                plain = a["ns"] is None
                aname = a["name_ref"][1] if plain else a["name_ref"][1][0]
                if plain and aname == "id":
                    id_index = i
                elif plain and aname == "class":
                    class_index = i
                elif plain and aname == "style":
                    style_index = i
            size = 16 + 20 + len(body)
            out += struct.pack("<HHIII", RES_XML_START_ELEMENT_TYPE, 16, size, line, NO_INDEX)
            out += struct.pack(
                "<IIHHHHHH",
                NO_INDEX,
                idx(name_ref),
                20,
                20,
                len(attrs),
                id_index,
                class_index,
                style_index,
            )
            out += body
        else:
            _, name_ref = ev
            out += struct.pack(
                "<HHIIIII", RES_XML_END_ELEMENT_TYPE, 16, 24, line, NO_INDEX, NO_INDEX, idx(name_ref)
            )
    if uses_android_ns:
        line += 1
        out += struct.pack(
            "<HHIIIII",
            RES_XML_END_NAMESPACE_TYPE,
            16,
            24,
            line,
            NO_INDEX,
            idx(("S", "android")),
            idx(("S", ANDROID_NS)),
        )
    return struct.pack("<HHI", RES_XML_TYPE, 8, 8 + len(out)) + bytes(out)
