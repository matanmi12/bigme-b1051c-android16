#!/usr/bin/env python3
"""Cross-check privileged permissions requested by priv-app APKs against privapp allowlists.

Usage:
  python3 -I privapp_check.py <aapt2> <framework-res.apk> <module_dir> [<orig_mtg_system_dir>]

For every APK under <module_dir>/system/{product,system_ext}/priv-app/*/ it:
  * reads requested permissions (aapt2 dump permissions), dropping those with maxSdkVersion < 36
  * looks up each permission's protectionLevel in the platform manifest (framework-res.apk)
  * reports platform permissions with the 'privileged' flag (or legacy signatureOrSystem)
    that are not granted by a <privapp-permissions> entry in the same partition's
    etc/permissions/*.xml of the module (Android only consults the APK's own partition).
  * optionally does the same against the original MTG XMLs to prove the subset lost nothing.

Runs aapt2 as a subprocess on the given files; executes nothing from the inputs.
"""
import glob
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

SDK = 36
ANDROID_NS_NAME = re.compile(r'A: http://schemas.android.com/apk/res/android:(\w+)\(0x[0-9a-f]+\)=(.*)$')


def platform_permissions(aapt2, fwres):
    out = subprocess.run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", fwres],
                         capture_output=True, text=True, check=True).stdout
    perms, cur = {}, None
    for line in out.splitlines():
        s = line.strip()
        if s.startswith("E: "):
            if cur and cur.get("name"):
                perms[cur["name"]] = cur
            cur = {"tag": s.split()[1]} if s.split()[1] == "permission" else None
            continue
        if cur is None or not s.startswith("A: "):
            continue
        m = ANDROID_NS_NAME.match(s)
        if not m:
            continue
        key, val = m.groups()
        if key == "name":
            cur["name"] = val.split('"')[1]
        elif key == "protectionLevel":
            cur["level"] = int(val.split(")")[-1], 16)
        elif key == "featureFlag":
            cur["flag"] = val.split('"')[1]
    if cur and cur.get("name"):
        perms[cur["name"]] = cur
    return perms


def is_privileged(level):
    return bool(level & 0x10) or (level & 0xF) == 3


def requested(aapt2, apk):
    out = subprocess.run([aapt2, "dump", "permissions", apk],
                         capture_output=True, text=True, check=True).stdout
    pkg, req = None, set()
    for line in out.splitlines():
        if line.startswith("package:"):
            pkg = line.split(":", 1)[1].strip()
        m = re.match(r"uses-permission(?:-sdk-23)?: name='([^']+)'(?: maxSdkVersion='(\d+)')?", line)
        if m:
            name, mx = m.groups()
            if mx is None or int(mx) >= SDK:
                req.add(name)
    return pkg, req


def allowlist(xml_dir, pkg):
    allow, deny, files = set(), set(), []
    for f in sorted(glob.glob(os.path.join(xml_dir, "*.xml"))):
        root = ET.parse(f).getroot()
        for el in root.iter("privapp-permissions"):
            if el.get("package") == pkg:
                files.append(os.path.basename(f))
                allow |= {p.get("name") for p in el.iter("permission")}
                deny |= {p.get("name") for p in el.iter("deny-permission")}
    return allow, deny, files


def main():
    if len(sys.argv) < 4:
        raise SystemExit(__doc__)
    aapt2, fwres, moddir = sys.argv[1:4]
    orig = sys.argv[4] if len(sys.argv) > 4 else None
    perms = platform_permissions(aapt2, fwres)
    priv = {n for n, p in perms.items() if "level" in p and is_privileged(p["level"])}
    print("platform permissions parsed: %d, privileged: %d" % (len(perms), len(priv)))
    worst = 0
    for part in ("product", "system_ext"):
        for apk in sorted(glob.glob(os.path.join(moddir, "system", part, "priv-app", "*", "*.apk"))):
            pkg, req = requested(aapt2, apk)
            need = sorted(req & priv)
            allow, deny, files = allowlist(os.path.join(moddir, "system", part, "etc", "permissions"), pkg)
            missing = [p for p in need if p not in allow]
            denied = [p for p in need if p in deny]
            extra = sorted(allow - req)
            print("\n== %s (%s, /%s/priv-app)" % (pkg, os.path.basename(apk), part))
            print("   requested (effective on SDK %d): %d; privileged platform perms: %d" % (SDK, len(req), len(need)))
            print("   allowlist files: %s; allowlisted: %d" % (", ".join(files) or "NONE", len(allow)))
            print("   NOT allowlisted (%d): %s" % (len(missing), ", ".join(
                p + (" [featureFlag %s]" % perms[p]["flag"] if perms[p].get("flag") else "") for p in missing) or "-"))
            if denied:
                print("   explicitly denied: %s" % ", ".join(denied))
            print("   allowlisted but not requested (harmless, %d): %s" % (len(extra), ", ".join(extra) or "-"))
            if orig:
                oallow, odeny, ofiles = allowlist(os.path.join(orig, part, "etc", "permissions"), pkg)
                print("   original MTG allowlist identical to module subset: %s (orig files: %s)" % (
                    oallow == allow and odeny == deny, ", ".join(ofiles) or "NONE"))
            worst = max(worst, len(missing))
    sys.exit(0)


if __name__ == "__main__":
    main()
