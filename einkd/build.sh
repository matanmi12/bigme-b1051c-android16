#!/bin/bash
# Build einkd (arm64, API 31) with the NDK. Output: einkd/einkd (goes to the module's bin/).
set -euo pipefail
cd "$(dirname "$0")"
NDK="${ANDROID_NDK_HOME:-/opt/homebrew/share/android-ndk}"
TC="$NDK/toolchains/llvm/prebuilt/$(ls "$NDK/toolchains/llvm/prebuilt" | head -1)/bin"
"$TC/aarch64-linux-android31-clang" -O2 -Wall -Wextra -o einkd einkd.c -llog
"$TC/llvm-strip" einkd
ls -l einkd
