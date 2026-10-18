#!/bin/bash
# Gradle-free build: aapt2 -> javac -> d8 -> zipalign -> apksigner. Output: build/einkctl.apk
set -euo pipefail
cd "$(dirname "$0")"
SDK=/opt/homebrew/share/android-commandlinetools
BT=$SDK/build-tools/36.0.0
AJ=$SDK/platforms/android-35/android.jar
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"
rm -rf build && mkdir -p build/gen build/classes build/dex
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$AJ" --manifest AndroidManifest.xml --java build/gen build/res.zip \
  --min-sdk-version 31 --target-sdk-version 35 --version-code "${VERSION_CODE:-1}" --version-name "${VERSION_NAME:-0.1.0}"
javac -Xlint:-options -source 17 -target 17 -encoding UTF-8 -classpath "$AJ" -d build/classes $(find src build/gen -name '*.java')
"$BT/d8" --lib "$AJ" --min-api 31 --release --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
KS=../debug.keystore
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias einkctl \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=einkctl local build" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --ks-key-alias einkctl --out build/einkctl.apk build/aligned.apk
"$BT/apksigner" verify build/einkctl.apk && shasum -a 256 build/einkctl.apk
