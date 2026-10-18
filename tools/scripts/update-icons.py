#!/usr/bin/env python3
"""One command to (re)build the private Bigme icon module for whatever is installed now.

  python3 -I tools/scripts/update-icons.py            # build + stage with Magisk (then reboot)
  python3 -I tools/scripts/update-icons.py --dry-run  # only show the plan

Steps: list launchable apps over adb -> pick an icon per work/icons-bigme/rules.json -> pull base + split APKs ->
build_icon_overlays.py (static RROs) -> work/icons-bigme/module/b1051c-icons (version +1) -> zip ->
magisk --install-module. Run it again after installing apps or after a Play update breaks an icon.
After the reboot: clear the launcher icon cache (force-stop launcher, delete databases/app_icons.db*).
Private: the module holds proprietary Bigme artwork; never stage it for the public repo.
"""
import json, os, re, shutil, subprocess, sys, tempfile

BIGME = "~/Desktop/Bigme"
ICONS = BIGME + "/work/icons-bigme/icons"
RULES = BIGME + "/work/icons-bigme/rules.json"
MOD = BIGME + "/work/icons-bigme/module/b1051c-icons"
KEYS = BIGME + "/work/sysui/keys"
BUILD = BIGME + "/tools/scripts/build_icon_overlays.py"
CUSTOM = BIGME + "/work/icons-bigme/custom"


def sh(*a, **kw):
    return subprocess.run(a, check=True, capture_output=True, text=True, **kw).stdout


def adb(cmd):
    return sh("adb", "shell", cmd).replace("\r", "")


def pick(pkg, rules, approved):
    def f(rel):
        for ext in (".xml", ".png"):
            if os.path.exists(os.path.join(ICONS, rel + ext)): return os.path.join(ICONS, rel + ext)
    if pkg in rules["overrides"]: return f(rules["overrides"][pkg])
    for d in rules["default_order"]:
        r = f(d + "/" + pkg)
        if r: return r
    # no Bigme icon: the drawing the owner approved for the home screen (e.g. KOReader A), so recents, settings
    # and notifications match the icon pack
    if pkg in approved:
        c = os.path.join(CUSTOM, "xml", approved[pkg] + ".xml")
        if os.path.exists(c): return c


def main():
    dry = "--dry-run" in sys.argv
    rules = json.load(open(RULES))
    approved = json.load(open(os.path.join(CUSTOM, "approved.json")))
    out = adb("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER")
    pkgs = sorted({l.strip().split("/")[0] for l in out.splitlines() if "/" in l})
    choices = {p: pick(p, rules, approved) for p in pkgs}
    for p in pkgs: print(f"{p:45s} {os.path.relpath(choices[p], BIGME + '/work/icons-bigme') if choices[p] else '— (squircle only)'}")
    choices = {p: c for p, c in choices.items() if c}
    if dry: return
    tmp = tempfile.mkdtemp(prefix="icons-")
    apks, built = os.path.join(tmp, "apks"), os.path.join(tmp, "built")
    os.makedirs(apks)
    for p in choices:
        for path in [l[len("package:"):] for l in adb("pm path " + p).split() if l.startswith("package:")]:
            name = p + ".apk" if path.endswith("/base.apk") or "/split_" not in path else p + "__" + os.path.basename(path)
            sh("adb", "pull", path, os.path.join(apks, name))
    json.dump(choices, open(os.path.join(tmp, "choices.json"), "w"), indent=1)
    print(sh(sys.executable, "-I", BUILD, "--choices", os.path.join(tmp, "choices.json"), "--apks", apks, "--out", built,
             "--key", KEYS + "/testkey.pk8", "--cert", KEYS + "/testkey.x509.pem"))
    ov = os.path.join(MOD, "system/product/overlay")
    for f in os.listdir(ov):
        if f.startswith("B1051cIcon_") and f.endswith(".apk"): os.remove(os.path.join(ov, f))
    for f in os.listdir(built):
        if f.endswith(".apk"): shutil.copy(os.path.join(built, f), ov)
    prop = open(os.path.join(MOD, "module.prop")).read()
    major, minor, patch = map(int, re.search(r"^version=(\d+)\.(\d+)\.(\d+)", prop, re.M).groups())
    code = int(re.search(r"^versionCode=(\d+)", prop, re.M).group(1)) + 1
    ver = f"{major}.{minor}.{patch + 1}"
    prop = re.sub(r"^version=.*", "version=" + ver, prop, flags=re.M)
    prop = re.sub(r"^versionCode=.*", f"versionCode={code}", prop, flags=re.M)
    open(os.path.join(MOD, "module.prop"), "w").write(prop)
    zipf = os.path.join(os.path.dirname(MOD), f"b1051c-icons-v{ver}.zip")
    sh("zip", "-q", "-r", "-X", zipf, ".", "-x", "*.DS_Store", cwd=MOD)
    sh("adb", "push", zipf, "/data/local/tmp/")
    print(adb("/data/adb/magisk/magisk --install-module /data/local/tmp/" + os.path.basename(zipf) + " 2>&1 | tail -1"))
    print(f"staged b1051c-icons v{ver} ({len(os.listdir(ov))} overlays): reboot to apply, then clear the launcher icon cache")
    shutil.rmtree(tmp)


if __name__ == "__main__":
    main()
