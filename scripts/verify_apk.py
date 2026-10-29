#!/usr/bin/env python3
"""Check packaged legacy hooks, native launcher resources and bootstrap integrity."""
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

apk = Path(sys.argv[1])
aapt = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(os.environ.get("ANDROID_HOME", "")) / "build-tools/36.0.0/aapt"
if not aapt.is_file():
    raise SystemExit("Set ANDROID_HOME to the SDK, or pass the Build Tools aapt path as the second argument")
resources = subprocess.check_output([str(aapt), "dump", "--values", "resources", str(apk)], text=True)
icon = re.search(r'(?m)^\s*resource 0x[0-9a-f]+ com\.s9encryptor\.strongtoastextended:drawable/ic_strongtoast_native:[^\n]*\n\s*\(string8\) "([^"]+)"', resources)
assert icon, "Fallback icon resource is missing from the compiled table"
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None, "Corrupt APK entry"
    names = archive.namelist()
    entry = archive.read("assets/xposed_init").decode().strip()
    assert entry == "com.s9encryptor.strongtoastextended.StrongToastHook", entry
    bootstrap = archive.read("assets/strongtoast-bootstrap.dex")
    assert bootstrap.startswith(b"dex\n"), "Invalid bootstrap DEX"
    expected = archive.read("assets/strongtoast-bootstrap.sha256").decode().strip()
    assert hashlib.sha256(bootstrap).hexdigest() == expected, "Bootstrap integrity mismatch"
    dex_files = [archive.read(name) for name in names if name.startswith("classes") and name.endswith(".dex")]
    for descriptor in (
        b"Lcom/s9encryptor/strongtoastextended/app/MainActivity;",
        b"Lcom/s9encryptor/strongtoastextended/StrongToastHook;",
        b"Lcom/s9encryptor/strongtoastextended/StrongToastBridge;",
        b"Lcom/s9encryptor/strongtoastextended/NativeTextLayout;",
    ):
        assert any(descriptor in dex for dex in dex_files), descriptor
    assert archive.read(icon.group(1)).startswith(b"\x89PNG\r\n\x1a\n"), "Invalid native fallback PNG"
    assert not any(name.upper().startswith("META-INF/") and name.upper().endswith((".RSA", ".DSA", ".EC")) for name in names), "Unexpected APK signing certificate"
print(f"Verified packaged native app, Xposed entrypoint and bootstrap SHA-256: {apk.name}")
