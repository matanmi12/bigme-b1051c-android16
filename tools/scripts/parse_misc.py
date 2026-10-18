#!/usr/bin/env python3
"""Decode the Android BCB and the A/B bootloader_control block of a misc partition dump (read-only)."""
import struct, sys, zlib
b = open(sys.argv[1], 'rb').read()
print("BCB command :", b[0:32].split(b'\0')[0]); print("BCB status  :", b[32:64].split(b'\0')[0]); print("BCB recovery:", b[64:832].split(b'\0')[0][:120])
bc = b[2048:2048+32]
suffix = bc[0:4].split(b'\0')[0]; magic, = struct.unpack_from('<I', bc, 4); version = bc[8]; nb = bc[9] & 7; rec = (bc[9] >> 3) & 7
print(f"bootctrl: slot_suffix={suffix!r} magic={'OK' if magic==0x42414342 else hex(magic)} version={version} nb_slot={nb} recovery_tries_remaining={rec}")
for i in range(nb):
    v, = struct.unpack_from('<H', bc, 12 + 2*i)
    print(f"  slot {'_ab'[i+1]}: priority={v & 0xF} tries_remaining={(v >> 4) & 7} successful_boot={(v >> 7) & 1} verity_corrupted={(v >> 8) & 1}")
crc, = struct.unpack_from('<I', bc, 28); print(f"crc32 {'OK' if crc == (zlib.crc32(bc[:28]) & 0xffffffff) else 'BAD'}")
print("raw:", bc.hex())
