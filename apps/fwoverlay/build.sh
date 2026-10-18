#!/bin/bash
# Static framework RRO for the b1051c-eink-compat module. Output: build/B1051cFrameworkOverlay.apk
# (copy to work/module/b1051c-eink-compat/system/product/overlay/). Signature is irrelevant for a preinstalled
# overlay; signed with the project debug key (work/app/debug.keystore) like v0.1.4–0.1.7, so the cert never changes.
set -euo pipefail
cd "$(dirname "$0")"
SDK=/opt/homebrew/share/android-commandlinetools
BT=$SDK/build-tools/36.0.0
AJ=$SDK/platforms/android-35/android.jar
rm -rf build && mkdir -p build
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/unsigned.apk -I "$AJ" --manifest AndroidManifest.xml build/res.zip \
  --min-sdk-version 31 --target-sdk-version 35
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks ../debug.keystore --ks-pass pass:android --ks-key-alias einkctl --out build/B1051cFrameworkOverlay.apk build/aligned.apk
shasum -a 256 build/B1051cFrameworkOverlay.apk
