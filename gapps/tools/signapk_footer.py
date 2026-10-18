#!/usr/bin/env python3
"""Split a SignApk whole-file-signed zip (signapk -w) into signed length + PKCS#7 signature.

Usage: python3 -I signapk_footer.py <signed.zip> <out_sig.der>
Prints the number of leading bytes that are covered by the signature (as RecoverySystem
.verifyPackage computes it). Verify afterwards with openssl cms using that byte count.
Reads data only.
"""
import os
import sys


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    path, out = sys.argv[1:]
    size = os.path.getsize(path)
    with open(path, "rb") as f:
        f.seek(size - 6)
        footer = f.read(6)
        if footer[2:4] != b"\xff\xff":
            raise SystemExit("no SignApk footer")
        sig_start = footer[0] | (footer[1] << 8)
        comment_size = footer[4] | (footer[5] << 8)
        f.seek(size - comment_size - 22)
        eocd = f.read(4)
        if eocd != b"PK\x05\x06":
            raise SystemExit("EOCD not where expected")
        f.seek(size - sig_start)
        sig = f.read(sig_start - 6)
    with open(out, "wb") as o:
        o.write(sig)
    print(size - comment_size - 2)


if __name__ == "__main__":
    main()
