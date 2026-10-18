#!/bin/bash
# Static framework RRO (config_displayColorFadeDisabled=true): the panel keeps the last image while asleep.
# Output: build/B1051cFrameworkOverlay.apk (goes to the module's system/product/overlay/).
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_SDK:-/opt/homebrew/share/android-commandlinetools}
BT=$SDK/build-tools/36.0.0
AJ=$SDK/platforms/android-35/android.jar
rm -rf build && mkdir -p build
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/unsigned.apk -I "$AJ" --manifest AndroidManifest.xml build/res.zip
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
KS=../debug.keystore
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias einkctl \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=local build" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --ks-key-alias einkctl --out build/B1051cFrameworkOverlay.apk build/aligned.apk
echo "built build/B1051cFrameworkOverlay.apk"
