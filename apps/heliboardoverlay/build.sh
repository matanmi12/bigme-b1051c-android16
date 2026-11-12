#!/bin/bash
# Static RRO: InKognito keyboard look for HeliBoard (module b1051c-heliboard). Output: build/B1051cHeliBoardSkin.apk
# Drawn from scratch (XML shapes) in the style of Bigme's stock keyboard; contains no Bigme files.
# Preinstalled overlay: the signature does not matter; signed with the project debug key like the other overlays.
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
"$BT/apksigner" sign --ks ../debug.keystore --ks-pass pass:android --ks-key-alias einkctl --out build/B1051cHeliBoardSkin.apk build/aligned.apk
shasum -a 256 build/B1051cHeliBoardSkin.apk
