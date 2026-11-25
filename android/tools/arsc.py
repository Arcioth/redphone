#!/usr/bin/env python3
"""Minimal resources.arsc writer for file-based app resources.

Only what Red Phone needs: file resources (drawable PNGs, compiled res/xml) in
the default configuration, package id 0x7f. Produces the same table layout
aapt2 emits for these resources (UTF-8 global pool, typeSpec + type chunks).
"""
from __future__ import annotations

import struct

from axml import (
    RES_TABLE_PACKAGE_TYPE,
    RES_TABLE_TYPE,
    RES_TABLE_TYPE_SPEC_TYPE,
    RES_TABLE_TYPE_TYPE,
    TYPE_STRING,
    encode_string_pool,
)

CONFIG_SIZE = 64  # sizeof(ResTable_config) on current platforms (all zero == default)


class ResourceTable:
    def __init__(self, package_name: str, package_id: int = 0x7F) -> None:
        self.package_name = package_name
        self.package_id = package_id
        self.types: list[str] = []
        self.entries: dict[str, list[tuple[str, str]]] = {}  # type -> [(name, string value / file path)]

    def add_file(self, rtype: str, name: str, apk_path: str) -> int:
        """File resource (drawable/xml/...): the value is the path of the file inside the APK."""
        return self.add_value(rtype, name, apk_path)

    def add_value(self, rtype: str, name: str, text: str) -> int:
        """String-valued resource (file paths and <string> values share the same encoding)."""
        if rtype not in self.entries:
            self.types.append(rtype)
            self.entries[rtype] = []
        if any(n == name for n, _ in self.entries[rtype]):
            raise ValueError(f"duplicate resource {rtype}/{name}")
        self.entries[rtype].append((name, text))
        return self.resid(rtype, name)

    def resid(self, rtype: str, name: str) -> int:
        tid = self.types.index(rtype) + 1
        names = [n for n, _ in self.entries[rtype]]
        return (self.package_id << 24) | (tid << 16) | names.index(name)

    def ids(self) -> dict[tuple[str, str], int]:
        return {(t, n): self.resid(t, n) for t in self.types for n, _ in self.entries[t]}

    def serialize(self) -> bytes:
        values: list[str] = []
        keys: list[str] = []
        for t in self.types:
            for name, path in self.entries[t]:
                values.append(path)
                if name not in keys:
                    keys.append(name)
        global_pool = encode_string_pool(values, utf8=True)
        type_pool = encode_string_pool(self.types, utf8=True)
        key_pool = encode_string_pool(keys, utf8=True)

        chunks = bytearray()
        value_index = 0
        for tid, t in enumerate(self.types, start=1):
            ents = self.entries[t]
            n = len(ents)
            # ResTable_typeSpec
            spec = struct.pack("<HHIBBHI", RES_TABLE_TYPE_SPEC_TYPE, 16, 16 + 4 * n, tid, 0, 0, n)
            spec += b"\x00\x00\x00\x00" * n
            chunks += spec
            # ResTable_type (default config)
            header_size = 20 + CONFIG_SIZE
            entries_start = header_size + 4 * n
            offsets = bytearray()
            body = bytearray()
            for name, _path in ents:
                offsets += struct.pack("<I", len(body))
                # ResTable_entry (size=8, flags=0, key) + Res_value (size=8, res0, type, data)
                body += struct.pack("<HHI", 8, 0, keys.index(name))
                body += struct.pack("<HBBI", 8, 0, TYPE_STRING, value_index)
                value_index += 1
            size = entries_start + len(body)
            ttype = struct.pack("<HHIBBHII", RES_TABLE_TYPE_TYPE, header_size, size, tid, 0, 0, n, entries_start)
            ttype += struct.pack("<I", CONFIG_SIZE) + b"\x00" * (CONFIG_SIZE - 4)
            chunks += ttype + offsets + body

        pkg_header_size = 288
        name_utf16 = self.package_name.encode("utf-16-le")[: 127 * 2]
        name_field = name_utf16 + b"\x00" * (256 - len(name_utf16))
        type_strings_off = pkg_header_size
        key_strings_off = type_strings_off + len(type_pool)
        pkg_size = pkg_header_size + len(type_pool) + len(key_pool) + len(chunks)
        pkg = struct.pack("<HHII", RES_TABLE_PACKAGE_TYPE, pkg_header_size, pkg_size, self.package_id)
        pkg += name_field
        pkg += struct.pack("<IIIII", type_strings_off, len(self.types), key_strings_off, len(keys), 0)
        assert len(pkg) == pkg_header_size, len(pkg)
        pkg += type_pool + key_pool + chunks

        total = 12 + len(global_pool) + len(pkg)
        return struct.pack("<HHII", RES_TABLE_TYPE, 12, total, 1) + global_pool + pkg
