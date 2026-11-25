#!/usr/bin/env python3
"""NewApi check without the Android SDK (a stand-in for `lint --check NewApi`).

Resolves every framework class/method/field referenced by the app's compiled classes against the
real framework of the minSdk (Robolectric android-all for API 26) and of the compileSdk (API 34),
walking superclasses and interfaces like the VM does. Anything missing at minSdk is reported with
its source file:line so the call site can be checked for a Build.VERSION.SDK_INT guard; anything
missing even at compileSdk is reported as an error. (android-all also contains @hide APIs, so use of
hidden APIs is not detected by this tool.)

usage: python3 android/tools/api_check.py <classes-dir> [--all]
  (classes-dir: python3 android/tools/build_apk.py --keep-build prints the build dir; use <dir>/classes)
"""
from __future__ import annotations

import os
import struct
import sys
import zipfile

MIN_JAR = "android-all-26.jar"
MIN_JAR_URL = (
    "org/robolectric/android-all/8.0.0_r4-robolectric-r1/android-all-8.0.0_r4-robolectric-r1.jar",
    "3707dc100381e3bbcc57b85255fd313ae20875fc65cf3130c47e86f044ad77f0",
)
FRAMEWORK_PREFIXES = ("android/", "com/android/", "dalvik/", "org/json/", "org/xmlpull/", "org/w3c/", "org/xml/")


class ClassInfo:
    __slots__ = ("name", "super", "interfaces", "methods", "fields", "refs")

    def __init__(self) -> None:
        self.name = ""
        self.super = None
        self.interfaces: list[str] = []
        self.methods: set[tuple[str, str]] = set()
        self.fields: set[str] = set()
        # (kind, owner, name, desc, source_line, in_method)
        self.refs: list[tuple] = []


def _u2(b, o):
    return struct.unpack_from(">H", b, o)[0]


def _u4(b, o):
    return struct.unpack_from(">I", b, o)[0]


def parse_class(data: bytes, want_refs: bool) -> ClassInfo:
    ci = ClassInfo()
    if _u4(data, 0) != 0xCAFEBABE:
        raise ValueError("bad class magic")
    n = _u2(data, 8)
    cp: list = [None] * n
    o = 10
    i = 1
    while i < n:
        tag = data[o]
        if tag == 1:
            ln = _u2(data, o + 1)
            cp[i] = ("utf8", data[o + 3 : o + 3 + ln].decode("utf-8", errors="replace"))
            o += 3 + ln
        elif tag in (3, 4):
            o += 5
        elif tag in (5, 6):
            o += 9
            i += 1
        elif tag == 7:
            cp[i] = ("class", _u2(data, o + 1))
            o += 3
        elif tag == 8:
            o += 3
        elif tag in (9, 10, 11):
            cp[i] = ({9: "field", 10: "method", 11: "imethod"}[tag], _u2(data, o + 1), _u2(data, o + 3))
            o += 5
        elif tag == 12:
            cp[i] = ("nat", _u2(data, o + 1), _u2(data, o + 3))
            o += 5
        elif tag == 15:
            o += 4
        elif tag == 16:
            o += 3
        elif tag in (17, 18):
            o += 5
        elif tag in (19, 20):
            o += 3
        else:
            raise ValueError(f"bad cp tag {tag}")
        i += 1

    def utf(idx):
        return cp[idx][1]

    def cls(idx):
        return utf(cp[idx][1])

    def member(idx):
        kind, ci_idx, nat = cp[idx]
        _, ni, di = cp[nat]
        return kind, cls(ci_idx), utf(ni), utf(di)

    o_access = o
    ci.name = cls(_u2(data, o_access + 2))
    sup = _u2(data, o_access + 4)
    ci.super = cls(sup) if sup else None
    ic = _u2(data, o_access + 6)
    o = o_access + 8
    for _ in range(ic):
        ci.interfaces.append(cls(_u2(data, o)))
        o += 2

    def skip_attrs(o):
        cnt = _u2(data, o)
        o += 2
        for _ in range(cnt):
            o += 6 + _u4(data, o + 2)
        return o

    fc = _u2(data, o)
    o += 2
    for _ in range(fc):
        ci.fields.add(utf(_u2(data, o + 2)))
        o = skip_attrs(o + 6)

    mc = _u2(data, o)
    o += 2
    for _ in range(mc):
        mname = utf(_u2(data, o + 2))
        mdesc = utf(_u2(data, o + 4))
        ci.methods.add((mname, mdesc))
        acnt = _u2(data, o + 6)
        a = o + 8
        for _ in range(acnt):
            aname = utf(_u2(data, a))
            alen = _u4(data, a + 2)
            if want_refs and aname == "Code":
                _scan_code(data, a + 6, cp, member, cls, ci, mname, utf)
            a += 6 + alen
        o = a
    return ci


# opcode -> operand length (excluding opcode) for fixed-size instructions
_FIXED = {}
for _op in range(0x00, 0x10):
    _FIXED[_op] = 0
_FIXED.update({0x10: 1, 0x11: 2, 0x12: 1, 0x13: 2, 0x14: 2})
for _op in range(0x15, 0x1A):
    _FIXED[_op] = 1
for _op in range(0x1A, 0x36):
    _FIXED[_op] = 0
for _op in range(0x36, 0x3B):
    _FIXED[_op] = 1
for _op in range(0x3B, 0x84):
    _FIXED[_op] = 0
_FIXED[0x84] = 2
for _op in range(0x85, 0x99):
    _FIXED[_op] = 0
for _op in range(0x99, 0xA9):
    _FIXED[_op] = 2
_FIXED.update({0xA9: 1, 0xAC: 0, 0xAD: 0, 0xAE: 0, 0xAF: 0, 0xB0: 0, 0xB1: 0})
for _op in range(0xB2, 0xB9):
    _FIXED[_op] = 2
_FIXED.update({0xB9: 4, 0xBA: 4, 0xBB: 2, 0xBC: 1, 0xBD: 2, 0xBE: 0, 0xBF: 0, 0xC0: 2, 0xC1: 2,
               0xC2: 0, 0xC3: 0, 0xC5: 3, 0xC6: 2, 0xC7: 2, 0xC8: 4, 0xC9: 4})

_MEMBER_OPS = {0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9}
_CLASS_OPS = {0xBB, 0xBD, 0xC0, 0xC1, 0xC5}


def _scan_code(data, o, cp, member, cls, ci, mname, utf):
    code_len = _u4(data, o + 4)
    code = o + 8
    end = code + code_len
    # line number table
    p = end
    exc_len = _u2(data, p)
    p += 2 + 8 * exc_len
    lines: list[tuple[int, int]] = []
    acnt = _u2(data, p)
    p += 2
    for _ in range(acnt):
        an = utf(_u2(data, p))
        al = _u4(data, p + 2)
        if an == "LineNumberTable":
            n = _u2(data, p + 6)
            for k in range(n):
                lines.append((_u2(data, p + 8 + 4 * k), _u2(data, p + 10 + 4 * k)))
        p += 6 + al
    lines.sort()

    def line_at(pc):
        ln = 0
        for start, l in lines:
            if start <= pc:
                ln = l
            else:
                break
        return ln

    pc = code
    while pc < end:
        op = data[pc]
        rel = pc - code
        if op in _MEMBER_OPS:
            kind, owner, name, desc = member(_u2(data, pc + 1))
            ci.refs.append((kind, owner, name, desc, line_at(rel), mname))
        elif op in _CLASS_OPS:
            idx = _u2(data, pc + 1)
            if cp[idx] and cp[idx][0] == "class":
                ci.refs.append(("class", cls(idx), "", "", line_at(rel), mname))
        if op == 0xAA:  # tableswitch
            q = code + ((rel + 4) & ~3)
            low, high = struct.unpack_from(">ii", data, q + 4)
            pc = q + 12 + 4 * (high - low + 1)
            continue
        if op == 0xAB:  # lookupswitch
            q = code + ((rel + 4) & ~3)
            npairs = struct.unpack_from(">i", data, q + 4)[0]
            pc = q + 8 + 8 * npairs
            continue
        if op == 0xC4:  # wide
            pc += 6 if data[pc + 1] == 0x84 else 4
            continue
        if op not in _FIXED:
            raise ValueError(f"unknown opcode 0x{op:02x} in {ci.name}.{mname}")
        pc += 1 + _FIXED[op]


class Framework:
    def __init__(self, jar: str) -> None:
        self.zf = zipfile.ZipFile(jar)
        self.names = set(self.zf.namelist())
        self.cache: dict[str, ClassInfo | None] = {}

    def get(self, name: str) -> ClassInfo | None:
        if name in self.cache:
            return self.cache[name]
        path = name + ".class"
        ci = parse_class(self.zf.read(path), False) if path in self.names else None
        self.cache[name] = ci
        return ci

    def has_class(self, name: str) -> bool:
        return self.get(name) is not None

    OBJECT_METHODS = {"toString", "equals", "hashCode", "getClass", "notify", "notifyAll", "wait", "clone", "finalize"}

    def has_member(self, owner: str, kind: str, name: str, desc: str, seen=None) -> bool:
        top = seen is None
        seen = seen if seen is not None else set()
        if owner in seen:
            return False
        seen.add(owner)
        ci = self.get(owner)
        if ci is None:
            if owner.startswith(FRAMEWORK_PREFIXES):
                return False
            # A JDK type (not in android-all; its API is checked by javac --release 8).
            if top:
                return True
            if owner == "java/lang/Object":
                return kind != "field" and name in self.OBJECT_METHODS
            # other JDK interfaces/superclasses of framework types (Closeable, Iterable, ...)
            return kind != "field" and owner.startswith(("java/", "javax/")) and owner != "java/lang/Object"
        if kind == "field":
            if name in ci.fields:
                return True
        elif (name, desc) in ci.methods:
            return True
        for parent in ([ci.super] if ci.super else []) + ci.interfaces:
            if self.has_member(parent, kind, name, desc, seen):
                return True
        return False


def load_app_classes(root: str) -> dict[str, ClassInfo]:
    out = {}
    for d, _dirs, files in os.walk(root):
        for f in files:
            if f.endswith(".class"):
                ci = parse_class(open(os.path.join(d, f), "rb").read(), True)
                out[ci.name] = ci
    return out


def framework_owner(app: dict[str, ClassInfo], owner: str) -> str | None:
    """Walks app classes up to the first framework ancestor (for calls through app subclasses)."""
    seen = set()
    while owner in app and owner not in seen:
        seen.add(owner)
        owner = app[owner].super or "java/lang/Object"
    return owner


def check(classes_dir: str, min_jar: str, max_jar: str) -> tuple[list[str], list[str]]:
    app = load_app_classes(classes_dir)
    fmin = Framework(min_jar)
    fmax = Framework(max_jar)
    newer, missing = set(), set()

    def is_fw(name):
        return name.startswith(FRAMEWORK_PREFIXES)

    for ci in app.values():
        src = ci.name.split("$")[0] + ".java"
        for parent in ([ci.super] if ci.super else []) + ci.interfaces:
            if parent and is_fw(parent) and not fmin.has_class(parent):
                (newer if fmax.has_class(parent) else missing).add(f"{src}: class {ci.name} extends/implements {parent}")
        for kind, owner, name, desc, line, meth in ci.refs:
            fw_owner = framework_owner(app, owner) if owner in app else owner
            if not fw_owner or not is_fw(fw_owner) and owner not in app:
                continue
            if owner in app:
                # member declared by the app class itself (or its app ancestors)?
                o2, declared = owner, False
                while o2 in app:
                    a = app[o2]
                    if (kind == "field" and name in a.fields) or (kind != "field" and (name, desc) in a.methods):
                        declared = True
                        break
                    o2 = a.super
                if declared or not is_fw(fw_owner):
                    continue
            where = f"{src}:{line} ({meth})"
            if kind == "class":
                if not is_fw(owner):
                    continue
                if not fmin.has_class(owner):
                    (newer if fmax.has_class(owner) else missing).add(f"{where}: class {owner}")
                continue
            if not fmin.has_member(fw_owner, kind, name, desc):
                label = f"{where}: {fw_owner.replace('/', '.')}.{name}{'' if kind == 'field' else desc}"
                (newer if fmax.has_member(fw_owner, kind, name, desc) else missing).add(label)
    return sorted(newer), sorted(missing)


def all_framework_refs(classes_dir: str) -> list[str]:
    """Every distinct framework member/class the app touches (for a public-SDK review)."""
    app = load_app_classes(classes_dir)
    out = set()
    for ci in app.values():
        for parent in ([ci.super] if ci.super else []) + ci.interfaces:
            if parent and parent.startswith(FRAMEWORK_PREFIXES):
                out.add(f"extends {parent.replace('/', '.')}")
        for kind, owner, name, desc, _line, _m in ci.refs:
            fw = framework_owner(app, owner) if owner in app else owner
            if owner in app:
                o2, declared = owner, False
                while o2 in app:
                    a = app[o2]
                    if (kind == "field" and name in a.fields) or (kind != "field" and (name, desc) in a.methods):
                        declared = True
                        break
                    o2 = a.super
                if declared:
                    continue
            if not fw or not fw.startswith(FRAMEWORK_PREFIXES):
                continue
            out.add(f"{fw.replace('/', '.')}" + ("" if kind == "class" else f".{name}{'' if kind == 'field' else desc}"))
    return sorted(out)


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    if "--all" in sys.argv:
        for line in all_framework_refs(sys.argv[1]):
            print(line)
        return
    cache = os.environ.get("REDPHONE_TOOLCACHE") or os.path.join(os.path.expanduser("~"), ".cache", "redphone-android")
    newer, missing = check(sys.argv[1], os.path.join(cache, MIN_JAR), os.path.join(cache, "android-all-14.jar"))
    print(f"== {len(newer)} references newer than API 26 (each needs a Build.VERSION.SDK_INT guard):")
    for line in newer:
        print("  " + line)
    print(f"== {len(missing)} references missing even at API 34 (nonexistent API; note android-all also"
          " contains @hide APIs, so hidden-API use is NOT detected here):")
    for line in missing:
        print("  " + line)
    sys.exit(1 if missing else 0)


if __name__ == "__main__":
    main()
