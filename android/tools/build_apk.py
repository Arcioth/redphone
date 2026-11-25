#!/usr/bin/env python3
"""Build the Red Phone APK without the Android SDK / Android Gradle Plugin.

Everything comes from Maven Central (plus the JDK and Python stdlib):
  * android.jar API surface : org.robolectric:android-all (compile-only)
  * dexer                    : com.jakewharton.android.repackaged:dalvik-dx (AOSP dx)
  * signer / verifier        : com.android.tools.build:apksig (APK Signature Scheme v1+v2)
  * binary XML + resources.arsc are produced by tools/axml.py and tools/arsc.py

The regular Gradle project (android/app/build.gradle.kts) builds the very same
sources with Android Studio; this script exists so the APK can also be produced
in environments where dl.google.com (SDK + AGP downloads) is unavailable.

usage: python3 android/tools/build_apk.py [--out android/dist/redphone.apk] [--api-check] [--keep-build]
"""
from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID_DIR = os.path.dirname(HERE)
REPO_ROOT = os.path.dirname(ANDROID_DIR)
APP_DIR = os.path.join(ANDROID_DIR, "app")
SRC_MAIN = os.path.join(APP_DIR, "src", "main")
sys.path.insert(0, HERE)

import axml  # noqa: E402
from arsc import ResourceTable  # noqa: E402

CACHE = os.environ.get("REDPHONE_TOOLCACHE") or os.path.join(os.path.expanduser("~"), ".cache", "redphone-android")
MAVEN = "https://repo.maven.apache.org/maven2"
TOOLS = {
    "android-all-14.jar": (
        "org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar",
        "6be2218c6a53fe3c57bc22ebdc723edcb7270a8a6f187545708aa5c0ed813977",
    ),
    "dalvik-dx-16.0.1.jar": (
        "com/jakewharton/android/repackaged/dalvik-dx/16.0.1/dalvik-dx-16.0.1.jar",
        "1e4b645628e3bdb097b5331d669e177ef235a551582a8c646dbe36865e541907",
    ),
    "apksig-2.3.0.jar": (
        "com/android/tools/build/apksig/2.3.0/apksig-2.3.0.jar",
        "9637078c0016244e4be0941836295365a7e2e5b164c59cb7885783c40460bfee",
    ),
}

# Files shared with the desktop Red Phone (single source of truth).
SHARED_ASSETS = [
    (os.path.join(REPO_ROOT, "ui", "index.html"), "assets/ui/index.html", True),
    (os.path.join(REPO_ROOT, "extension", "content.js"), "assets/web/content.js", True),
    (os.path.join(REPO_ROOT, "extension", "page-hook.js"), "assets/web/page-hook.js", True),
    (os.path.join(REPO_ROOT, "assets", "pickup.wav"), "assets/sounds/pickup.wav", True),
    (os.path.join(REPO_ROOT, "assets", "hangup.wav"), "assets/sounds/hangup.wav", True),
    # Optional yt-dlp clips (see README). Synthesized tones are used when absent.
    (os.path.join(REPO_ROOT, "assets", "dialtone.wav"), "assets/sounds/dialtone.wav", False),
    (os.path.join(REPO_ROOT, "assets", "ringtone.wav"), "assets/sounds/ringtone.wav", False),
]


def log(msg: str) -> None:
    print(f"[build_apk] {msg}", flush=True)


def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    res = subprocess.run(cmd, text=True, capture_output=True, **kw)
    if res.returncode != 0:
        sys.stderr.write(res.stdout + res.stderr)
        raise SystemExit(f"command failed ({res.returncode}): {' '.join(cmd[:6])} ...")
    return res


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fp:
        for chunk in iter(lambda: fp.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def ensure_tools() -> dict[str, str]:
    os.makedirs(CACHE, exist_ok=True)
    paths = {}
    for name, (rel, digest) in TOOLS.items():
        dst = os.path.join(CACHE, name)
        if not os.path.isfile(dst) or sha256(dst) != digest:
            url = f"{MAVEN}/{rel}"
            log(f"downloading {url}")
            tmp = dst + ".part"
            with urllib.request.urlopen(url, timeout=600) as r, open(tmp, "wb") as fp:
                shutil.copyfileobj(r, fp)
            if sha256(tmp) != digest:
                os.remove(tmp)
                raise SystemExit(f"checksum mismatch for {name}")
            os.replace(tmp, dst)
        paths[name] = dst
    return paths


def read_props(path: str) -> dict[str, str]:
    props = {}
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        props[k.strip()] = v.strip()
    return props


def collect_resources(table: ResourceTable) -> list[tuple[str, str, str, str]]:
    """Returns [(type, name, src_path, apk_path)] for res/<type>/<file> (default config only)."""
    res_dir = os.path.join(SRC_MAIN, "res")
    out = []
    if not os.path.isdir(res_dir):
        return out
    for tdir in sorted(os.listdir(res_dir)):
        full = os.path.join(res_dir, tdir)
        if not os.path.isdir(full):
            continue
        if "-" in tdir:
            raise SystemExit(f"res/{tdir}: configuration qualifiers are not supported by build_apk.py")
        if tdir == "values":
            for fname in sorted(os.listdir(full)):
                for name, text in parse_string_values(os.path.join(full, fname)):
                    table.add_value("string", name, text)
            continue
        for fname in sorted(os.listdir(full)):
            name, _ext = os.path.splitext(fname)
            apk_path = f"res/{tdir}/{fname}"
            table.add_file(tdir, name, apk_path)
            out.append((tdir, name, os.path.join(full, fname), apk_path))
    return out


def parse_string_values(path: str) -> list[tuple[str, str]]:
    """res/values/*.xml: plain <string name="x">text</string> entries only (aapt-style escapes)."""
    import xml.etree.ElementTree as ET

    root = ET.parse(path).getroot()
    out = []
    for el in root:
        if el.tag != "string":
            raise SystemExit(f"{path}: only <string> values are supported, found <{el.tag}>")
        if len(el):
            raise SystemExit(f"{path}: <string name={el.get('name')}> must be plain text")
        text = (el.text or "").strip()
        if len(text) >= 2 and text[0] == text[-1] == '"':
            text = text[1:-1]
        else:
            text = " ".join(text.split())
        text = text.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n").replace("\\@", "@")
        out.append((el.get("name"), text))
    return out


def inject_manifest(text: str, props: dict[str, str]) -> str:
    """Adds what AGP would merge in from build.gradle.kts: package, versions, <uses-sdk>."""
    m = re.search(r"<manifest\b([^>]*)>", text)
    if not m:
        raise SystemExit("AndroidManifest.xml: <manifest> not found")
    attrs = m.group(1)
    if "package=" in attrs:
        raise SystemExit("AndroidManifest.xml must not declare package= (set applicationId in app.properties)")
    extra = (
        f' package="{props["applicationId"]}"'
        f' android:versionCode="{props["versionCode"]}"'
        f' android:versionName="{props["versionName"]}"'
        f' android:compileSdkVersion="{props["compileSdk"]}"'
    )
    new_open = f"<manifest{attrs}{extra}>"
    uses_sdk = (
        f'\n    <uses-sdk android:minSdkVersion="{props["minSdk"]}"'
        f' android:targetSdkVersion="{props["targetSdk"]}" />'
    )
    return text[: m.start()] + new_open + uses_sdk + text[m.end() :]


def compile_java(jars: dict[str, str], build: str) -> str:
    src_root = os.path.join(SRC_MAIN, "java")
    sources = []
    for d, _dirs, files in os.walk(src_root):
        for f in files:
            if f.endswith(".java"):
                sources.append(os.path.join(d, f))
    if not sources:
        raise SystemExit("no Java sources found")
    classes = os.path.join(build, "classes")
    os.makedirs(classes, exist_ok=True)
    argfile = os.path.join(build, "sources.txt")
    with open(argfile, "w", encoding="utf-8") as fp:
        fp.write("\n".join(sources))
    log(f"javac {len(sources)} sources")
    res = run(
        [
            "javac",
            "-encoding", "UTF-8",
            # java.* = the Java 8 API surface (what Android's libcore provides), android.* = API 34.
            "--release", "8",
            "-Xlint:-options",
            "-Xlint:deprecation",
            "-Xlint:unchecked",
            "-classpath", jars["android-all-14.jar"],
            "-d", classes,
            f"@{argfile}",
        ]
    )
    if res.stderr.strip():
        sys.stderr.write(res.stderr)
    return classes


def dex(jars: dict[str, str], classes: str, build: str) -> str:
    out = os.path.join(build, "classes.dex")
    log("dx -> classes.dex")
    # min-sdk 25: default/static interface methods allowed, invokedynamic (lambdas,
    # method refs) rejected at build time — ART has no LambdaMetafactory.
    run(
        [
            "java",
            "-cp", jars["dalvik-dx-16.0.1.jar"],
            "com.android.dx.command.Main",
            "--dex",
            "--min-sdk-version=25",
            f"--output={out}",
            classes,
        ]
    )
    return out


class AlignedZip:
    """zipfile wrapper that 4-byte aligns STORED entries (what zipalign does)."""

    def __init__(self, path: str) -> None:
        self.zf = zipfile.ZipFile(path, "w")

    def add(self, apk_path: str, data: bytes, stored: bool = False) -> None:
        zi = zipfile.ZipInfo(apk_path, date_time=(2008, 1, 1, 0, 0, 0))
        zi.external_attr = 0o644 << 16
        if stored:
            zi.compress_type = zipfile.ZIP_STORED
            header_offset = self.zf.fp.tell()
            name_len = len(apk_path.encode("utf-8"))
            data_off = header_offset + 30 + name_len
            pad = (4 - data_off % 4) % 4
            if pad:
                # 0xD935 is the "alignment" extra-field id used by zipalign/apksig.
                pad_needed = pad
                while pad_needed < 6:
                    pad_needed += 4
                zi.extra = struct.pack("<HH", 0xD935, pad_needed - 4) + b"\x00" * (pad_needed - 4)
        else:
            zi.compress_type = zipfile.ZIP_DEFLATED
        self.zf.writestr(zi, data, compress_type=zi.compress_type, compresslevel=9 if not stored else None)

    def close(self) -> None:
        self.zf.close()


def ensure_keystore() -> tuple[str, str, str]:
    ks = os.environ.get("REDPHONE_KEYSTORE") or os.path.join(CACHE, "redphone-release.p12")
    pw = os.environ.get("REDPHONE_KEYSTORE_PASS", "redphone")
    alias = os.environ.get("REDPHONE_KEY_ALIAS", "redphone")
    if not os.path.isfile(ks):
        log(f"generating signing key {ks}")
        run(
            [
                "keytool", "-genkeypair", "-noprompt",
                "-keystore", ks, "-storetype", "PKCS12",
                "-storepass", pw, "-keypass", pw,
                "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "10000", "-dname", "CN=Red Phone, O=redphone",
            ]
        )
    return ks, pw, alias


def sign(jars: dict[str, str], unsigned: str, out: str, min_sdk: str, build: str) -> None:
    tool_dir = os.path.join(build, "signer")
    os.makedirs(tool_dir, exist_ok=True)
    run(["javac", "-cp", jars["apksig-2.3.0.jar"], "-d", tool_dir, os.path.join(HERE, "ApkSign.java")])
    ks, pw, alias = ensure_keystore()
    res = run(
        [
            "java",
            # apksig 2.3.0 (2017) builds the v1 PKCS#7 block with JDK-internal sun.security classes.
            "--add-exports", "java.base/sun.security.x509=ALL-UNNAMED",
            "--add-exports", "java.base/sun.security.pkcs=ALL-UNNAMED",
            "--add-exports", "java.base/sun.security.util=ALL-UNNAMED",
            "-cp", os.pathsep.join([tool_dir, jars["apksig-2.3.0.jar"]]),
            "ApkSign", unsigned, out, ks, pw, alias, min_sdk,
        ]
    )
    sys.stdout.write(res.stdout)
    if res.stderr.strip():
        sys.stderr.write(res.stderr)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(ANDROID_DIR, "dist", "redphone.apk"))
    ap.add_argument("--keep-build", action="store_true")
    ap.add_argument(
        "--api-check",
        action="store_true",
        help="NewApi-style check of every framework reference against API 26 (minSdk) and API 34",
    )
    args = ap.parse_args()

    if args.api_check:
        TOOLS["android-all-26.jar"] = (
            "org/robolectric/android-all/8.0.0_r4-robolectric-r1/android-all-8.0.0_r4-robolectric-r1.jar",
            "3707dc100381e3bbcc57b85255fd313ae20875fc65cf3130c47e86f044ad77f0",
        )
    jars = ensure_tools()
    props = read_props(os.path.join(APP_DIR, "app.properties"))
    build = tempfile.mkdtemp(prefix="redphone-apk-")
    try:
        fw = axml.FrameworkDb.from_android_all(jars["android-all-14.jar"])
        table = ResourceTable(props["applicationId"])
        resources = collect_resources(table)
        resolver = axml.ResourceResolver(fw, table.ids())

        manifest_txt = inject_manifest(open(os.path.join(SRC_MAIN, "AndroidManifest.xml"), encoding="utf-8").read(), props)
        manifest_bin = axml.compile_xml(manifest_txt, resolver)

        classes = compile_java(jars, build)
        if args.api_check:
            import api_check

            newer, missing = api_check.check(classes, jars["android-all-26.jar"], jars["android-all-14.jar"])
            log(f"api-check: {len(newer)} references newer than API 26 (each must sit behind an SDK_INT guard):")
            for line in newer:
                print("    " + line)
            if missing:
                for line in missing:
                    print("    MISSING " + line)
                raise SystemExit(f"api-check: {len(missing)} references do not exist in API 34")
        dex_path = dex(jars, classes, build)

        unsigned = os.path.join(build, "unsigned.apk")
        z = AlignedZip(unsigned)
        z.add("AndroidManifest.xml", manifest_bin)
        z.add("classes.dex", open(dex_path, "rb").read())
        z.add("resources.arsc", table.serialize(), stored=True)
        for rtype, _name, src, apk_path in resources:
            raw = open(src, "rb").read()
            if src.endswith(".xml"):
                z.add(apk_path, axml.compile_xml(raw.decode("utf-8"), resolver))
            else:
                z.add(apk_path, raw, stored=src.endswith(".png"))
        seen = set()
        for src, apk_path, required in SHARED_ASSETS:
            if not os.path.isfile(src):
                if required:
                    raise SystemExit(f"missing shared asset {src}")
                continue
            z.add(apk_path, open(src, "rb").read())
            seen.add(apk_path)
        assets_dir = os.path.join(SRC_MAIN, "assets")
        for d, _dirs, files in os.walk(assets_dir):
            for f in sorted(files):
                full = os.path.join(d, f)
                apk_path = "assets/" + os.path.relpath(full, assets_dir).replace(os.sep, "/")
                if apk_path in seen:
                    raise SystemExit(f"asset {apk_path} collides with a shared asset")
                z.add(apk_path, open(full, "rb").read())
        z.close()

        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        sign(jars, unsigned, args.out, props["minSdk"], build)
        log(f"wrote {args.out} ({os.path.getsize(args.out) / 1024:.0f} KiB) sha256={sha256(args.out)}")
    finally:
        if args.keep_build:
            log(f"build dir kept: {build}")
        else:
            shutil.rmtree(build, ignore_errors=True)


if __name__ == "__main__":
    main()
