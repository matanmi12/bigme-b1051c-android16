#!/usr/bin/env python3
"""Private Bigme icon pack for Lawnchair (ADW/Nova appfilter format), rebuilt from what is installed now.

  python3 -I tools/scripts/build-iconpack.py           # render frames on the device, build, install
  python3 -I tools/scripts/build-iconpack.py --no-install

Per launchable app (component):
  - Bigme icon (work/icons-bigme/rules.json + approved custom icons in work/icons-bigme/custom/approved.json)
    as a VectorDrawable, unless the package is a launcher (owner: launchers keep their own icon, ring only);
  - otherwise the app's own icon in Bigme's squircle + ring, rendered on the device by E-Ink Control
    (am broadcast ... RENDER_FRAMES -> files/frames).
Lawnchair does not implement iconback/iconmask/iconupon (docs, 2026-10), so apps installed later need a re-run;
no reboot. Select the pack once in Lawnchair settings. PRIVATE: proprietary Bigme artwork — never publish.
"""
import json, os, re, shutil, subprocess, sys, tempfile, time

BIGME = "~/Desktop/Bigme"
ICONS = BIGME + "/work/icons-bigme"
SDK = "/opt/homebrew/share/android-commandlinetools"
BT = SDK + "/build-tools/36.0.0"
AJ = SDK + "/platforms/android-35/android.jar"
KS = BIGME + "/work/app/debug.keystore"
PKG = "org.b1051c.iconpack"
LAUNCHERS = {"app.lawnchair", "com.android.launcher3"}


def run(*a, **kw):
    return subprocess.run(a, check=True, capture_output=True, text=True, **kw).stdout


def adb(cmd):
    return run("adb", "shell", cmd)


def bigme_source(pkg, rules, approved):
    """Vector source for a package, or None (PNG-only Bigme icons are skipped: the original frame looks sharper)."""
    if pkg in LAUNCHERS: return None
    if pkg in approved: return os.path.join(ICONS, "custom", "xml", approved[pkg] + ".xml")
    rel = rules["overrides"].get(pkg)
    cands = [rel] if rel else [d + "/" + pkg for d in rules["default_order"]]
    for c in cands:
        p = os.path.join(ICONS, "icons", c + ".xml")
        if os.path.exists(p): return p
    return None


def main():
    install = "--no-install" not in sys.argv
    rules = json.load(open(os.path.join(ICONS, "rules.json")))
    ap = os.path.join(ICONS, "custom", "approved.json")
    approved = json.load(open(ap)) if os.path.exists(ap) else {}
    approved = {k: v for k, v in approved.items() if not k.startswith("_")}

    # 1. fresh frames from the device
    adb("rm -f /data/user/0/org.b1051c.einkctl/files/frames/index.json; am broadcast -n org.b1051c.einkctl/.ActionReceiver "
        "-a org.b1051c.einkctl.RENDER_FRAMES >/dev/null")
    for _ in range(60):
        if "index.json" in adb("ls /data/user/0/org.b1051c.einkctl/files/frames 2>/dev/null"): break
        time.sleep(1)
    tmp = tempfile.mkdtemp(prefix="iconpack-")
    with open(os.path.join(tmp, "frames.tar"), "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "tar -C /data/user/0/org.b1051c.einkctl/files -cf - frames"],
                               check=True, capture_output=True).stdout)
    run("tar", "-xf", os.path.join(tmp, "frames.tar"), "-C", tmp)
    index = json.load(open(os.path.join(tmp, "frames", "index.json")))

    # 2. resources + appfilter
    res = os.path.join(tmp, "res")
    for d in ("drawable", "drawable-nodpi", "xml", "values"): os.makedirs(os.path.join(res, d))
    items, drawables, vec_for = [], [], {}
    for n, e in enumerate(sorted(index, key=lambda e: (e["package"], e["activity"]))):
        comp = "ComponentInfo{%s/%s}" % (e["package"], e["activity"])
        src = bigme_source(e["package"], rules, approved)
        if src:
            name = vec_for.get(src)
            if not name:
                name = vec_for[src] = "bigme_%d" % len(vec_for)
                x = open(src, encoding="utf-8").read()
                for k, v in (("0", "linear"), ("1", "radial"), ("2", "sweep")): x = x.replace('android:type="%s"' % k, 'android:type="%s"' % v)
                open(os.path.join(res, "drawable", name + ".xml"), "w", encoding="utf-8").write(x)
                drawables.append(name)
            how = "bigme " + os.path.relpath(src, ICONS)
        else:
            name = "frame_%d" % n
            shutil.copy(os.path.join(tmp, "frames", e["file"]), os.path.join(res, "drawable-nodpi", name + ".png"))
            drawables.append(name)
            how = "frame"
        items.append('    <item component="%s" drawable="%s" />' % (comp, name))
        if e["package"] == "com.google.android.calendar":
            # Lawnchair's dynamic calendar: <prefix>1..31 (no zero padding). The approved "31" design has a dated
            # variant per day (custom/xml/calendar_day/cal_<d>.xml); otherwise the same drawing every day.
            dated = os.path.join(ICONS, "custom", "xml", "calendar_day")
            use_dated = src and src.endswith("com.google.android.calendar__B.xml") and os.path.isdir(dated)
            for day in range(1, 32):
                if use_dated:
                    shutil.copy(os.path.join(dated, "cal_%d.xml" % day), os.path.join(res, "drawable", "cal_%d.xml" % day))
                else:
                    shutil.copy(os.path.join(res, "drawable" if src else "drawable-nodpi", name + (".xml" if src else ".png")),
                                os.path.join(res, "drawable" if src else "drawable-nodpi", "cal_%d%s" % (day, ".xml" if src else ".png")))
            items.append('    <calendar component="%s" prefix="cal_" />' % comp)
        print(f"{e['package']:45s} {how}")
    open(os.path.join(res, "xml", "appfilter.xml"), "w").write("<resources>\n" + "\n".join(items) + "\n</resources>\n")
    open(os.path.join(res, "xml", "drawable.xml"), "w").write(
        "<resources>\n" + "".join('    <item drawable="%s" />\n' % d for d in drawables) + "</resources>\n")
    open(os.path.join(res, "values", "strings.xml"), "w").write(
        '<resources><string name="app_name">Bigme icons</string></resources>\n')
    man = os.path.join(tmp, "AndroidManifest.xml")
    open(man, "w").write(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{PKG}">
    <application android:label="@string/app_name" android:hasCode="false" android:allowBackup="false">
        <!-- discovery only (Lawnchair / ADW / Nova icon-pack intents); never launched -->
        <activity android:name="android.app.Activity" android:exported="true" android:label="@string/app_name">
            <intent-filter>
                <action android:name="org.adw.ActivityStarter.THEMES" />
                <action android:name="com.novalauncher.THEME" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>
    </application>
</manifest>
''')

    # 3. build, sign, install
    code = int(time.time()) // 60
    run(BT + "/aapt2", "compile", "--dir", res, "-o", os.path.join(tmp, "res.zip"))
    run(BT + "/aapt2", "link", "-o", os.path.join(tmp, "u.apk"), "-I", AJ, "--manifest", man, os.path.join(tmp, "res.zip"),
        "--min-sdk-version", "31", "--target-sdk-version", "35", "--version-code", str(code), "--version-name", "1." + str(code))
    run(BT + "/zipalign", "-f", "-p", "4", os.path.join(tmp, "u.apk"), os.path.join(tmp, "a.apk"))
    out = os.path.join(ICONS, "iconpack", "BigmeIconPack.apk")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    run(BT + "/apksigner", "sign", "--ks", KS, "--ks-pass", "pass:android", "--ks-key-alias", "einkctl", "--out", out,
        os.path.join(tmp, "a.apk"))
    print(f"built {out} ({len(items)} entries, {len(vec_for)} Bigme drawings)")
    if install:
        print(run("adb", "install", "-r", "--no-incremental", out).strip().splitlines()[-1])
    shutil.rmtree(tmp)


if __name__ == "__main__":
    main()
