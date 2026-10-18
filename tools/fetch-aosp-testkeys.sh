#!/bin/bash
# Download the public AOSP test platform key into keys/ (needed to sign apps/einkui for a test-keys GSI).
set -euo pipefail
cd "$(dirname "$0")/../keys"
for f in platform.pk8 platform.x509.pem; do
  curl -sf "https://android.googlesource.com/platform/build/+/refs/heads/main/target/product/security/$f?format=TEXT" \
    | base64 -d > "$f"
done
openssl x509 -in platform.x509.pem -noout -fingerprint -sha256
echo "expected: C8:A2:E9:BC:CF:59:7C:2F:B6:DC:66:BE:E2:93:FC:13:F2:FC:47:EC:77:BC:6B:2B:0D:52:C1:1F:51:19:2A:B8"
