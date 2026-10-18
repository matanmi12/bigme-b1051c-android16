#!/bin/bash
# Assemble the Magisk module zip from the built pieces:
#   shim/out/hwcomposer.mtk_common.stripped.so  (shim/build.sh; needs shim/lut_data.h, see shim/tools/make_lut_header.py)
#   einkd/einkd                                  (einkd/build.sh)
#   apps/fwoverlay/build/B1051cFrameworkOverlay.apk (apps/fwoverlay/build.sh)
# Output: module/b1051c-eink-compat-<version>.zip  -> install with `magisk --install-module <zip>`.
set -euo pipefail
cd "$(dirname "$0")"
M=b1051c-eink-compat
mkdir -p "$M/system/vendor/lib64/hw"
install -m 0644 ../shim/out/hwcomposer.mtk_common.stripped.so "$M/system/vendor/lib64/hw/hwcomposer.mtk_common.so"
mkdir -p "$M/bin" "$M/system/product/overlay"
install -m 0755 ../einkd/einkd "$M/bin/einkd"
install -m 0644 ../apps/fwoverlay/build/B1051cFrameworkOverlay.apk "$M/system/product/overlay/"
V=$(sed -n 's/^version=//p' "$M/module.prop")
(cd "$M" && rm -f "../$M-v$V.zip" && zip -q -r -X "../$M-v$V.zip" META-INF module.prop customize.sh post-fs-data.sh service.sh sepolicy.rule system bin)
shasum -a 256 "$M-v$V.zip"
