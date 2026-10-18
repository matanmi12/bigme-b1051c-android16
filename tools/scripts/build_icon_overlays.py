#!/usr/bin/env python3
"""Build one RRO per app that replaces its launcher icon with a Bigme icon (roadmap: Bigme icons, 2026-10-18).

For each target APK: read the icon resources the launcher can use (application icon/roundIcon and those of
LAUNCHER activities/aliases), find every configuration they exist in, and override each one with an
adaptive icon: white background + the Bigme vector scaled to 70 of the 72 visible dp (its own squircle ring
then sits just inside the launcher's icon mask). PNG-only Bigme icons are inset the same way.

  build_icon_overlays.py --choices choices.json --apks DIR --out DIR --key K.pk8 --cert C.x509.pem [--runtime]

choices.json: {"<package>": "<path to Bigme icon .xml or .png>"}; DIR/<package>.apk = the installed APK.
Default output = static overlays for a Magisk module (/system/product/overlay); --runtime = mutable overlays
to install in /data (only allowed when signed with the target's certificate).
Bigme icons are proprietary: keep the output out of the public repo (see work/icons-bigme/README.txt).
"""
import argparse, json, os, re, shutil, subprocess, sys

SDK = "/opt/homebrew/share/android-commandlinetools"
BT = SDK + "/build-tools/36.0.0"
AJ = SDK + "/platforms/android-35/android.jar"
AAPT2 = BT + "/aapt2"
VISIBLE, LAYER, ICON = 72.0, 108.0, 70.0   # adaptive icon geometry (dp)


def run(*a):
    return subprocess.run(a, check=True, capture_output=True, text=True).stdout


def launcher_icon_ids(apk):
    """Resource ids (hex) of application icon/roundIcon and of every LAUNCHER activity/alias icon."""
    tree = run(AAPT2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk)
    ids, app_ids, cur, launcher = set(), set(), None, False
    for line in tree.splitlines():
        m = re.match(r"\s*E: (\S+)", line)
        if m:
            tag = m.group(1)
            if tag in ("activity", "activity-alias", "application"):
                if cur and launcher: ids |= cur
                cur, launcher = set(), False
                is_app = tag == "application"
            elif tag in ("service", "receiver", "provider"):
                # their icons (QS tiles, widgets) are not launcher icons; before 2026-10-19 they were collected
                # into the application's set and overlaid with the app icon (E-Ink Control's tile glyphs)
                if cur and launcher: ids |= cur
                cur, launcher, is_app = None, False, False
            continue
        m = re.search(r"android:(icon|roundIcon)\(0x0101000[0-9a-f]\)=@(0x[0-9a-f]+)|android:(roundIcon)\(0x[0-9a-f]+\)=@(0x[0-9a-f]+)", line)
        if m and cur is not None:
            rid = m.group(2) or m.group(4)
            cur.add(rid)
            if is_app: app_ids.add(rid)
        if '"android.intent.category.LAUNCHER"' in line: launcher = True
    if cur and launcher: ids |= cur
    return ids | app_ids


REFS = {}   # (type, name) -> {(type, name), ...}: values that are plain references to another resource


def resources(apk):
    """{id: (type, name, [config, ...])} from aapt2 dump resources; fills REFS."""
    out, res, cur = run(AAPT2, "dump", "resources", apk), {}, None
    for line in out.splitlines():
        m = re.match(r"\s*resource (0x[0-9a-f]+) (\w+)/(\S+)", line)
        if m:
            cur = m.group(1); res[cur] = (m.group(2), m.group(3), []); continue
        m = re.match(r"\s*\(([^)]*)\) (?:@(\w+)/(\S+))?", line)
        if m and cur:
            res[cur][2].append(m.group(1))
            if m.group(2): REFS.setdefault(res[cur][:2], set()).add((m.group(2), m.group(3)))
    return res


def follow_refs(res, ids):
    """PackageManager stores the manifest icon after resolving references (Photos: photos_launcher (v26) ->
    @mipmap/photos_launchericon_release_adaptive is what LauncherActivityInfo reports), so overlay the whole chain."""
    by_name = {v[:2]: k for k, v in res.items()}
    todo, seen = [res[i][:2] for i in ids if i in res], set()
    while todo:
        tn = todo.pop()
        if tn in seen: continue
        seen.add(tn); todo.extend(REFS.get(tn, ()))
    return {by_name[tn] for tn in seen if tn in by_name}


def dynamic_icon_arrays(apk):
    """{array name: size} for the calendar dynamic-icon arrays the launcher uses instead of the app icon."""
    tree = run(AAPT2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk)
    ids = set(re.findall(r'"com\.google\.android\.calendar\.dynamic_icons[^"]*".*?\n\s*A: http://schemas.android.com/apk/res/android:resource\(0x01010025\)=@(0x[0-9a-f]+)', tree))
    out, dump = {}, run(AAPT2, "dump", "resources", apk)
    for rid in ids:
        m = re.search(r"resource %s array/(\S+)\n\s*\(\) \(array\) size=(\d+)" % rid, dump)
        if m: out[m.group(1)] = int(m.group(2))
    return out


def foreground_xml(src):
    """The Bigme icon as a 108dp foreground layer, scaled to ICON dp and centred."""
    off = (LAYER - ICON) / 2
    if src.endswith(".png"):
        pct = off / LAYER * 100
        return ('<inset xmlns:android="http://schemas.android.com/apk/res/android" android:inset="%.3f%%">\n'
                '  <bitmap android:src="@drawable/b1051c_icon_png" android:filter="true" android:gravity="fill" />\n'
                '</inset>\n' % pct)
    x = open(src, encoding="utf-8").read()
    vw = float(re.search(r'android:viewportWidth="([0-9.]+)"', x).group(1))
    vh = float(re.search(r'android:viewportHeight="([0-9.]+)"', x).group(1))
    body = x[x.index(">", x.index("<vector")) + 1: x.rindex("</vector>")]
    s = ICON / max(vw, vh)
    tx, ty = off + (ICON - vw * s) / 2, off + (ICON - vh * s) / 2
    return ('<vector xmlns:android="http://schemas.android.com/apk/res/android" '
            'android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'
            '  <group android:translateX="%.4f" android:translateY="%.4f" android:scaleX="%.6f" android:scaleY="%.6f">%s</group>\n'
            '</vector>\n' % (tx, ty, s, s, body))


ADAPTIVE = ('<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '  <background android:drawable="@android:color/white" />\n'
            '  <foreground android:drawable="@drawable/b1051c_icon_fg" />\n'
            '</adaptive-icon>\n')


def build(pkg, src, apk, out, key, cert, runtime):
    ids = launcher_icon_ids(apk)
    res = resources(apk)
    # An RRO can't help when (a) the target declares <overlayable> (only listed resources may be overlaid;
    # launcher icons never are: DocumentsUI, Chrome) or (b) the icon entry is not in the base APK's table
    # (idmap is built against base only; Play apps may keep it in a config split: Drive). Verified 2026-10-18.
    if "policies=" in run(AAPT2, "dump", "overlayable", apk):
        print(f"{pkg}: declares <overlayable> — icon cannot be overlaid, skipped (needs launcher-side mapping)"); return None
    if not any(i in res for i in ids):
        print(f"{pkg}: icon only in config splits — cannot be overlaid, skipped (needs launcher-side mapping)"); return None
    # Play-installed apps may keep the icon entries only in config splits: DIR/<pkg>__<split>.apk
    import glob
    for split in sorted(glob.glob(apk[:-4] + "__*.apk")):
        try:
            split_res = resources(split)
        except subprocess.CalledProcessError:       # feature/native splits without a readable resource table
            continue
        for rid, (typ, name, cfgs) in split_res.items():
            if rid in res: res[rid][2].extend(c for c in cfgs if c not in res[rid][2])
            else: res[rid] = (typ, name, list(cfgs))
    targets = [res[i] for i in sorted(follow_refs(res, ids)) if res[i][0] in ("drawable", "mipmap")]
    if not targets:
        print(f"{pkg}: no launcher icon resource found — skipped"); return None
    work = os.path.join(out, "src", pkg)
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(os.path.join(work, "res", "drawable"))
    open(os.path.join(work, "res", "drawable", "b1051c_icon_fg.xml"), "w").write(foreground_xml(src))
    if src.endswith(".png"):
        shutil.copy(src, os.path.join(work, "res", "drawable", "b1051c_icon_png.png"))
    for typ, name, configs in targets:
        # Also anydpi-v26: it beats every density bucket, including ones that live only in config splits of
        # Play-installed apps (not in the base APK we read). An overlay wins when its config is at least as good.
        for cfg in sorted(set(configs or [""]) | {"anydpi-v26"}):
            # every copy carries an API level >= 26 (<adaptive-icon> needs it at minSdk 24), which also makes it
            # at least as specific as the target's own config (xhdpi-v26 >= xhdpi, anydpi-v26 == anydpi-v26)
            if not re.search(r"(^|-)v\d+$", cfg): cfg = (cfg + "-v26") if cfg else "v26"
            d = os.path.join(work, "res", typ + "-" + cfg)
            os.makedirs(d, exist_ok=True)
            open(os.path.join(d, name + ".xml"), "w").write(ADAPTIVE)
    dyn = dynamic_icon_arrays(apk)
    if dyn:
        os.makedirs(os.path.join(work, "res", "values"), exist_ok=True)
        os.makedirs(os.path.join(work, "res", "drawable-v26"), exist_ok=True)
        open(os.path.join(work, "res", "drawable-v26", "b1051c_icon.xml"), "w").write(ADAPTIVE)
        open(os.path.join(work, "res", "values", "b1051c_dynamic_icons.xml"), "w").write(
            "<resources>\n" + "".join('  <array name="%s">%s</array>\n' % (n, "".join("<item>@drawable/b1051c_icon</item>" for _ in range(k)))
                                       for n, k in sorted(dyn.items())) + "</resources>\n")
        targets = targets + [("array", n, ["dynamic x%d" % k]) for n, k in sorted(dyn.items())]
    opkg = "org.b1051c.icon." + re.sub(r"[^A-Za-z0-9_.]", "_", pkg)
    open(os.path.join(work, "AndroidManifest.xml"), "w").write(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="%s" '
        'android:versionCode="1" android:versionName="1.0">\n'
        '  <overlay android:targetPackage="%s" android:isStatic="%s" android:priority="20" />\n'
        '  <application android:hasCode="false" />\n</manifest>\n' % (opkg, pkg, "false" if runtime else "true"))
    b = os.path.join(work, "build"); os.makedirs(b)
    run(AAPT2, "compile", "--dir", os.path.join(work, "res"), "-o", os.path.join(b, "res.zip"))
    run(AAPT2, "link", "-o", os.path.join(b, "u.apk"), "-I", AJ, "--manifest", os.path.join(work, "AndroidManifest.xml"),
        # minSdk 24, not 31: with minSdk >= 26 aapt2 drops "-v26" from our configs, and the target's
        # anydpi-v26 then wins over our plain anydpi (Gmail, 2026-10-18).
        os.path.join(b, "res.zip"), "--min-sdk-version", "24", "--target-sdk-version", "35")
    run(BT + "/zipalign", "-f", "-p", "4", os.path.join(b, "u.apk"), os.path.join(b, "a.apk"))
    apk_out = os.path.join(out, "B1051cIcon_" + pkg.replace(".", "_") + ".apk")
    run(BT + "/apksigner", "sign", "--key", key, "--cert", cert, "--out", apk_out, os.path.join(b, "a.apk"))
    print(f"{pkg}: {', '.join(t + '/' + n + '[' + ','.join(c or 'default' for c in cs) + ']' for t, n, cs in targets)} -> {os.path.basename(apk_out)}")
    return apk_out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--choices", required=True); ap.add_argument("--apks", required=True)
    ap.add_argument("--out", required=True); ap.add_argument("--key", required=True); ap.add_argument("--cert", required=True)
    ap.add_argument("--runtime", action="store_true")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    choices = json.load(open(a.choices))
    built = []
    for pkg, src in sorted(choices.items()):
        apk = os.path.join(a.apks, pkg + ".apk")
        if not os.path.exists(apk): print(f"{pkg}: not installed (no APK) — skipped"); continue
        r = build(pkg, src, apk, a.out, a.key, a.cert, a.runtime)
        if r: built.append(r)
    print(f"built {len(built)} overlay(s) in {a.out}")


if __name__ == "__main__":
    sys.exit(main())
