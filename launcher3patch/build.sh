#!/bin/bash
# Launcher3QuickStep with a transparent taskbar on a third-party home screen (org.b1051c.l3.HomeBg).
# Input: the GSI's own Launcher3QuickStep.apk (sha bf92e8d5…, from work/gsi/system-td-arm64-vanilla.img).
# Steps: apktool d -r (code only) -> smali hooks in TaskbarDragLayerController.updateBackgroundAlpha() and
# FallbackTaskbarUIController.createAnimToRecentsState() ->
# apktool b -> + classes2.dex (HomeBg, built with javac + d8 against compile-only stubs) -> dex stored, zipalign,
# signed with the public AOSP testkey (= Launcher3's own certificate a40da80a…, so it updates in place).
# Output: build/Launcher3QuickStep.apk, then a Magisk module zip (module/). Never touches a device.
set -euo pipefail
cd "$(dirname "$0")"
SDK=/opt/homebrew/share/android-commandlinetools
BT=$SDK/build-tools/36.0.0
AJ=$SDK/platforms/android-35/android.jar
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"
APKTOOL=../../tools/bin/apktool_3.0.3.jar
KEYS=../sysui/keys
SRC_APK=${SRC_APK:-build/Launcher3QuickStep.orig.apk}
ORIG_SHA=bf92e8d5beadf5162660ce80c0e310354f3dccd84e077d7cf13c7f70c963f8cd

mkdir -p build
if [ ! -f "$SRC_APK" ]; then
  /opt/homebrew/opt/e2fsprogs/sbin/debugfs -R \
    "dump /system/system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk $SRC_APK" \
    ../gsi/system-td-arm64-vanilla.img >/dev/null 2>&1
fi
[ "$(shasum -a 256 "$SRC_APK" | cut -d' ' -f1)" = "$ORIG_SHA" ] || { echo "unexpected source APK"; exit 1; }

# 1. helper dex
rm -rf build/classes build/stubs build/dex && mkdir -p build/classes build/stubs build/dex
javac -Xlint:-options -source 17 -target 17 -encoding UTF-8 -classpath "$AJ" -d build/stubs $(find stubs -name '*.java')
javac -Xlint:-options -source 17 -target 17 -encoding UTF-8 -classpath "$AJ:build/stubs" -d build/classes $(find src -name '*.java')
"$BT/d8" --lib "$AJ" --classpath build/stubs --min-api 31 --release --output build/dex $(find build/classes -name '*.class')

# 2. smali hooks
#  a) TaskbarDragLayerController.updateBackgroundAlpha(): alpha -> HomeBg.alpha(this, alpha)   (transparent on home)
#  b) FallbackTaskbarUIController.createAnimToRecentsState(state, duration): the isIn3pHomeOrRecents() answer ->
#     HomeBg.stashLauncherState(state, answer)   (no launcher stash for HOME / BG_LAUNCHER, fix 2026-10-20)
rm -rf build/dec
java -jar "$APKTOOL" d -q -r -o build/dec "$SRC_APK"
python3 - build/dec/smali/com/android/launcher3/taskbar <<'PY'
import sys, os
d = sys.argv[1]
hooks = [
    ("TaskbarDragLayerController.smali",
     "    mul-float/2addr v2, v0\n\n"
     "    iput v2, p0, Lcom/android/launcher3/taskbar/TaskbarDragLayerController;->mLastSetBackgroundAlpha:F\n",
     "    mul-float/2addr v2, v0\n\n"
     "    invoke-static {p0, v2}, Lorg/b1051c/l3/HomeBg;->alpha(Ljava/lang/Object;F)F\n\n"
     "    move-result v2\n\n"
     "    iput v2, p0, Lcom/android/launcher3/taskbar/TaskbarDragLayerController;->mLastSetBackgroundAlpha:F\n"),
    ("FallbackTaskbarUIController.smali",
     ".method private createAnimToRecentsState(Lcom/android/quickstep/fallback/RecentsState;J)Landroid/animation/Animator;\n"
     "    .locals 3\n\n"
     "    .line 115\n"
     "    invoke-direct {p0}, Lcom/android/launcher3/taskbar/FallbackTaskbarUIController;->isIn3pHomeOrRecents()Z\n\n"
     "    move-result p1\n\n"
     "    const/4 v0, 0x1\n",
     ".method private createAnimToRecentsState(Lcom/android/quickstep/fallback/RecentsState;J)Landroid/animation/Animator;\n"
     "    .locals 3\n\n"
     "    .line 115\n"
     "    invoke-direct {p0}, Lcom/android/launcher3/taskbar/FallbackTaskbarUIController;->isIn3pHomeOrRecents()Z\n\n"
     "    move-result v0\n\n"
     "    invoke-static {p1, v0}, Lorg/b1051c/l3/HomeBg;->stashLauncherState(Ljava/lang/Object;Z)Z\n\n"
     "    move-result p1\n\n"
     "    const/4 v0, 0x1\n"),
]
for name, anchor, hook in hooks:
    p = os.path.join(d, name)
    s = open(p).read()
    assert s.count(anchor) == 1, "hook anchor not found exactly once in " + name
    open(p, "w").write(s.replace(anchor, hook))
    print("hook inserted:", name)
PY

# 3. rebuild, add the helper dex (stored, like the original classes.dex), align, sign
java -jar "$APKTOOL" b -q -o build/unsigned.apk build/dec
cp build/dex/classes.dex build/classes2.dex
(cd build && zip -q -0 unsigned.apk classes2.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --key "$KEYS/testkey.pk8" --cert "$KEYS/testkey.x509.pem" --out build/Launcher3QuickStep.apk build/aligned.apk
"$BT/apksigner" verify --print-certs build/Launcher3QuickStep.apk | grep -m1 "SHA-256"
unzip -v build/Launcher3QuickStep.apk | grep -E "classes"
shasum -a 256 build/Launcher3QuickStep.apk
