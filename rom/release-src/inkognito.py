#!/usr/bin/env python3
"""InKognito installer for the Bigme B1051C Pro (Windows, macOS, Linux; Python 3.8+, standard library only).

Every partition access goes through mtkclient (https://github.com/bkerler/mtkclient), the same tool used to unlock
the bootloader. There is no fastboot and no fastbootd: on this tablet fastbootd once came up on the other slot and
rewrote the boot slot, which ended in a boot loop.

  python inkognito.py install        back up, then install InKognito (erases the tablet's data)
  python inkognito.py update         install a newer InKognito release over an older one (keeps your data)
  python inkognito.py restore-stock  put the stock firmware back from the backup made by "install"
  python inkognito.py backup         only make the backup
  python inkognito.py prepare        offline: check a backup folder and build the images from it, touch nothing

Add --dry-run to any command to see every step and mtkclient command without running mtkclient or adb.
Add --help after a command for its options. Read INSTALL.md first.

Nothing of Bigme's is in the release. boot, vbmeta and logo are built on your computer from your own tablet's
backup: the stock kernel and device tree stay byte-identical (plus the 50 MHz microSD cap), and the InKognito parts
are added the way Magisk's own boot_patch.sh adds them. Licensed MIT, like the rest of InKognito's own code.
"""
import argparse, datetime, gzip, hashlib, json, os, re, shlex, shutil, struct, subprocess, sys, threading, time, zlib

RELEASE_NAME = "InKognito-B1051CPro-beta1-20261112"
HERE = os.path.dirname(os.path.abspath(__file__))
KIT = os.path.join(HERE, "kit")

# ---- device facts: Bigme B1051C Pro, firmware B1051C_R_4.9.0 ----------------------------------------------------
EXPECT_FW = "B1051C_R_4.9.0"            # ro.build.versionName on stock Android
EXPECT_VENDOR_INC = "mp1V15125"         # ro.vendor.build.version.incremental on any system over fw 4.9.0
EXPECT_WM = "1860x2480"
MIN_BATTERY = 50
# GPT of the UFS user area (mtkclient printgpt): name -> (offset, length). Checked before anything is read or written.
GPT = {
    "misc": (0x8000, 0x80000), "vbmeta_a": (0x8a08000, 0x800000), "vbmeta_b": (0xa208000, 0x800000),
    "metadata": (0xba10000, 0x25f0000), "boot_a": (0x24d00000, 0x2800000), "dtbo_a": (0x2bd00000, 0x800000),
    "logo": (0x32900000, 0xf00000), "boot_b": (0x45d00000, 0x2800000), "dtbo_b": (0x4dd00000, 0x800000),
    "super": (0x4f000000, 0x300000000), "userdata": (0x34f000000, 0x1a7aff8000),
}
BACKUP_PARTS = ["boot_a", "boot_b", "vbmeta_a", "vbmeta_b", "dtbo_a", "dtbo_b", "logo", "misc", "metadata", "super"]
USERDATA_HEAD = 0x4000000               # zeroing the first 64 MiB makes Android format /data on the next start
# Never read, written or named by this tool, whatever the arguments.
FORBIDDEN = ("preloader", "lk", "seccfg", "tee", "sec1", "otp", "nvram", "nvdata", "nvcfg", "proinfo", "persist",
             "frp", "waveform", "vcom", "efuse", "md", "protect", "gz", "spm", "scp", "sspm", "mcupm", "dpm")


class Stop(Exception):
    """A check failed. The message says what the tablet's state is and what to do."""


def say(*a):
    print(*a, flush=True)


def step(msg):
    say("\n==> " + msg)


def note(msg):
    say("    " + msg)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 22), b""):
            h.update(b)
    return h.hexdigest()


def sha256(b):
    return hashlib.sha256(b).hexdigest()


# =================================================================================================================
# Formats. Pure functions on bytes; no device access. dev-tests/test_inkognito.py runs them on the real images.
# =================================================================================================================

# ---- Android boot image, header v2 (kernel 4.19, no init_boot/vendor_boot) -----------------------------------------
def boot_parse(b):
    if b[:8] != b"ANDROID!":
        raise Stop("not an Android boot image")
    ks, rs, ss = (struct.unpack_from("<I", b, o)[0] for o in (8, 16, 24))
    page, ver = struct.unpack_from("<II", b, 36)
    if ver != 2:
        raise Stop("boot image header version %d (expected 2)" % ver)
    rdo, dtbs = struct.unpack_from("<I", b, 1632)[0], struct.unpack_from("<I", b, 1648)[0]
    al = lambda n: (n + page - 1) // page * page
    off = page
    kernel = b[off:off + ks]; off += al(ks)
    ramdisk = b[off:off + rs]; off += al(rs)
    second = b[off:off + ss]; off += al(ss)
    recovery_dtbo = b[off:off + rdo]; off += al(rdo)
    dtb, dtb_off = b[off:off + dtbs], off; off += al(dtbs)
    p = {"header": b[:page], "page": page, "kernel": kernel, "ramdisk": ramdisk, "second": second,
         "recovery_dtbo": recovery_dtbo, "dtb": dtb, "dtb_off": dtb_off, "end": off, "size": len(b), "avb": None}
    foot = b[-64:]
    if foot[:4] == b"AVBf":
        _, _, orig, vo, vs = struct.unpack(">II3Q", foot[4:36])
        p["avb"] = {"footer": foot, "vbmeta": b[vo:vo + vs], "orig": orig}
    return p


def boot_build(p, ramdisk):
    """Repack like magiskboot: same header except ramdisk size and id, sections page-aligned, the AVB vbmeta blob
    copied unchanged to the next 4 KiB boundary and the footer pointing at it, zero-filled to the partition size."""
    page = p["page"]
    al = lambda n: (n + page - 1) // page * page
    pad = lambda s: s + b"\0" * (al(len(s)) - len(s))
    hdr = bytearray(p["header"])
    struct.pack_into("<I", hdr, 16, len(ramdisk))
    h = hashlib.sha1()
    for s in (p["kernel"], ramdisk, p["second"], p["recovery_dtbo"], p["dtb"]):
        h.update(s); h.update(struct.pack("<I", len(s)))
    hdr[576:608] = h.digest() + b"\0" * 12
    out = bytearray(hdr) + pad(p["kernel"]) + pad(ramdisk) + pad(p["second"]) + pad(p["recovery_dtbo"]) + pad(p["dtb"])
    end = len(out)
    if p["avb"]:
        vo = (end + 4095) // 4096 * 4096
        out += b"\0" * (vo - end) + p["avb"]["vbmeta"]
        foot = bytearray(p["avb"]["footer"])
        struct.pack_into(">QQQ", foot, 12, end, vo, len(p["avb"]["vbmeta"]))
        if len(out) > p["size"] - 64:
            raise Stop("the new boot image does not fit the boot partition")
        out += b"\0" * (p["size"] - 64 - len(out)) + foot
    else:
        out += b"\0" * (p["size"] - len(out))
    if len(out) != p["size"]:
        raise Stop("the new boot image does not fit the boot partition")
    return bytes(out)


def boot_hash_id(p):
    """sha256 over the parts that identify a boot image independent of how its ramdisk is compressed."""
    return sha256(p["kernel"]) + sha256(p["dtb"])


# ---- flattened device tree: the microSD clock cap (mmc@11240000/max-frequency) -------------------------------------
SD_NODE, SD_PROP, SD_CAP = b"mmc@11240000", "max-frequency", 50000000
FDT_MAGIC = b"\xd0\x0d\xfe\xed"


def _fdts(b):
    i = b.find(FDT_MAGIC)
    while i != -1:
        if i + 40 <= len(b):
            total, o_struct, o_strings, _, ver = struct.unpack_from(">5I", b, i + 4)
            if 0 < total <= len(b) - i and ver in (16, 17) and o_struct < total and o_strings < total:
                yield i, total
                i = b.find(FDT_MAGIC, i + total)
                continue
        i = b.find(FDT_MAGIC, i + 4)


def sd_offsets(b):
    """Offsets of every 4-byte mmc@11240000/max-frequency value in every FDT of b."""
    found = []
    for base, total in _fdts(b):
        o_struct, o_strings = struct.unpack_from(">2I", b, base + 8)
        pos, path = base + o_struct, []
        while pos < base + total:
            tok = struct.unpack_from(">I", b, pos)[0]; pos += 4
            if tok == 1:
                e = b.index(b"\0", pos); path.append(b[pos:e]); pos = base + ((e + 1 - base + 3) & ~3)
            elif tok == 2:
                path.pop()
            elif tok == 3:
                ln, nameoff = struct.unpack_from(">2I", b, pos); v = pos + 8
                s = base + o_strings + nameoff
                if path and path[-1] == SD_NODE and b[s:b.index(b"\0", s)].decode() == SD_PROP:
                    if ln != 4:
                        raise Stop("unexpected microSD clock property length")
                    found.append(v)
                pos = base + ((v + ln - base + 3) & ~3)
            elif tok == 4:
                pass
            elif tok == 9:
                break
            else:
                raise Stop("malformed device tree in the boot image")
    return found


def sd_cap(dtb):
    offs = sd_offsets(dtb)
    if not offs:
        raise Stop("the boot image's device tree has no microSD clock setting; is this a B1051C Pro boot image?")
    out = bytearray(dtb)
    for o in offs:
        struct.pack_into(">I", out, o, SD_CAP)
    return bytes(out)


def sd_uncap(dtb, value=100000000):
    out = bytearray(dtb)
    for o in sd_offsets(dtb):
        struct.pack_into(">I", out, o, value)
    return bytes(out)


# ---- cpio (newc), written the way AOSP mkbootfs and magiskboot write it ---------------------------------------------
def cpio_parse(data):
    ents, o = {}, 0
    while True:
        h = data[o:o + 110]
        if h[:6] != b"070701":
            raise Stop("bad cpio entry in the boot ramdisk")
        f = [int(h[6 + 8 * i:14 + 8 * i], 16) for i in range(13)]
        ns, fs = f[11], f[6]
        name = data[o + 110:o + 110 + ns - 1].decode()
        o2 = (o + 110 + ns + 3) & ~3
        body = data[o2:o2 + fs]
        o = (o2 + fs + 3) & ~3
        if name == "TRAILER!!!":
            return ents
        ents[name] = {"mode": f[1], "uid": f[2], "gid": f[3], "rdevmajor": f[9], "rdevminor": f[10], "data": body}


def cpio_write(ents):
    """Sorted by name, inodes from 300000, nlink 1, mtime 0, a 0755 TRAILER!!!, no padding to 512."""
    out = bytearray()

    def rec(ino, name, e):
        nb = name.encode() + b"\0"
        fields = (ino, e["mode"], e["uid"], e["gid"], 1, 0, len(e["data"]), 0, 0, e["rdevmajor"], e["rdevminor"], len(nb), 0)
        out.extend(b"070701" + b"".join(b"%08x" % v for v in fields) + nb)
        out.extend(b"\0" * (-len(out) % 4))
        out.extend(e["data"])
        out.extend(b"\0" * (-len(out) % 4))

    names = sorted(ents, key=lambda n: n.encode())
    for i, n in enumerate(names):
        rec(300000 + i, n, ents[n])
    rec(300000 + len(names), "TRAILER!!!", {"mode": 0o755, "uid": 0, "gid": 0, "rdevmajor": 0, "rdevminor": 0, "data": b""})
    return bytes(out)


def cpio_canon(data):
    """The same archive as cpio_write writes it (the stock ramdisk is padded to 512 bytes; the entries are equal)."""
    return cpio_write(cpio_parse(data))


def gunzip(b):
    if b[:2] != b"\x1f\x8b":
        raise Stop("the boot ramdisk is not gzip-compressed (unexpected for firmware %s)" % EXPECT_FW)
    return gzip.decompress(b)


def gzip9(b):
    return gzip.compress(b, 9, mtime=0)


# ---- the InKognito boot ramdisk: Magisk 30.7 (boot_patch.sh, KEEPVERITY/KEEPFORCEENCRYPT) + off-mode charging rc ----
MAGISK_ADDED = [  # (name, mode, kit file) added by boot_patch.sh; listed in .backup/.rmlist
    ("overlay.d", 0o40750, None), ("overlay.d/sbin", 0o40750, None),
    ("overlay.d/sbin/init-ld.xz", 0o100644, "init-ld.xz"), ("overlay.d/sbin/magisk.xz", 0o100644, "magisk.xz"),
    ("overlay.d/sbin/stub.xz", 0o100644, "stub.xz"),
]
INKOGNITO_ADDED = [  # our charger-mode rc: keeps the bootloader's charging picture, logs to /metadata/b1051c
    ("overlay.d/b1051c-charger.rc", 0o100644, "b1051c-charger.rc"),
    ("overlay.d/sbin/b1051c_charger_diag.sh", 0o100755, "b1051c_charger_diag.sh"),
]
PREINIT_DEVICE = "sdc14"                # the metadata partition's block device (GPT index), as Magisk detects it here


def _kit_file(kit, name):
    with open(os.path.join(kit, "boot", name), "rb") as f:
        return f.read()


def ramdisk_patch(stock_cpio, kit, stock_sha1):
    ents = cpio_parse(stock_cpio)
    if ".backup" in ents or "overlay.d" in ents:
        raise Stop("the boot image is already patched (Magisk or InKognito); a stock boot image is needed")
    e = lambda mode, data: {"mode": mode, "uid": 0, "gid": 0, "rdevmajor": 0, "rdevminor": 0, "data": data}
    ents[".backup/init"] = dict(ents["init"])
    ents["init"] = e(0o100750, _kit_file(kit, "magiskinit"))
    for name, mode, src in MAGISK_ADDED + INKOGNITO_ADDED:
        ents[name] = e(mode, _kit_file(kit, src) if src else b"")
    ents[".backup"] = e(0o40000, b"")
    ents[".backup/.rmlist"] = e(0o100000, b"".join(n.encode() + b"\0" for n, _, _ in MAGISK_ADDED))
    cfg = ("KEEPVERITY=true\nKEEPFORCEENCRYPT=true\nRECOVERYMODE=false\nVENDORBOOT=false\n"
           "PREINITDEVICE=%s\nSHA1=%s\n" % (PREINIT_DEVICE, stock_sha1))
    ents[".backup/.magisk"] = e(0o100000, cfg.encode())
    return cpio_write(ents)


def ramdisk_unpatch(cpio):
    """Undo ramdisk_patch (what Magisk's uninstall does): the original /init back, everything added removed."""
    ents = cpio_parse(cpio)
    if ".backup/init" not in ents:
        return cpio
    ents["init"] = ents.pop(".backup/init")
    for n in [n for n in ents if n == ".backup" or n.startswith(".backup/") or n == "overlay.d" or n.startswith("overlay.d/")]:
        del ents[n]
    return cpio_write(ents)


def kit_info(kit=KIT):
    with open(os.path.join(kit, "kit.json")) as f:
        return json.load(f)


def classify_boot(b, kit=KIT):
    """'stock', 'inkognito' (any release, stock underneath), 'magisk' (stock kernel, other ramdisk) or 'unknown'."""
    k = kit_info(kit)["stock"]
    p = boot_parse(b)
    if sha256(p["kernel"]) != k["kernel"]:
        return "unknown"
    if sha256(sd_uncap(p["dtb"])) != k["dtb_uncapped"]:
        return "unknown"
    cpio = cpio_canon(gunzip(p["ramdisk"]))
    if sha256(cpio) == k["ramdisk_cpio"]:
        return "stock"
    ents = cpio_parse(cpio)
    if "overlay.d/b1051c-charger.rc" in ents and sha256(ramdisk_unpatch(cpio)) == k["ramdisk_cpio"]:
        return "inkognito"
    return "magisk" if ".backup/init" in ents else "unknown"


def build_boot(src_boot, kit=KIT):
    """InKognito boot image from the tablet's own boot image (stock, or an earlier InKognito build)."""
    info = kit_info(kit)
    kind = classify_boot(src_boot, kit)
    if kind not in ("stock", "inkognito"):
        raise Stop("the tablet's boot image is not the stock %s one (found: %s). InKognito only installs over the "
                   "stock firmware %s. If you rooted it with Magisk, restore the stock boot image first." % (EXPECT_FW, kind, EXPECT_FW))
    p = boot_parse(src_boot)
    stock_cpio = ramdisk_unpatch(gunzip(p["ramdisk"]))
    stock_capped = dict(p, dtb=sd_cap(p["dtb"]))
    # .backup/.magisk records the SHA1 of the untouched stock image (it names Magisk's restore backup). An InKognito
    # boot no longer holds the stock ramdisk bytes, so then the value comes from the kit; classify_boot proved the
    # stock parts are the same.
    sha1 = hashlib.sha1(src_boot).hexdigest() if kind == "stock" else info["stock"]["sha1"]
    cpio = ramdisk_patch(stock_cpio, kit, sha1)
    if sha256(cpio) != info["out"]["ramdisk_cpio"]:
        raise Stop("internal check failed: the new boot ramdisk does not match this release (%s)" % sha256(cpio)[:16])
    out = boot_build(stock_capped, gzip9(cpio))
    q = boot_parse(out)
    if q["kernel"] != p["kernel"] or sha256(q["dtb"]) != info["out"]["dtb"] or gunzip(q["ramdisk"]) != cpio:
        raise Stop("internal check failed: the new boot image did not read back as built")
    return out


# ---- vbmeta: flags = 1 (HASHTREE_DISABLED), one byte, as on the developer's tablet since 17.10 ----------------------
def build_vbmeta(src, kit=KIT):
    info = kit_info(kit)
    if sha256(src) == info["out"]["vbmeta"]:
        return src
    if sha256(src) != info["stock"]["vbmeta"]:
        raise Stop("the tablet's vbmeta is not the stock %s one; it was changed by something else" % EXPECT_FW)
    out = bytearray(src)
    out[123] = 1
    if sha256(bytes(out)) != info["out"]["vbmeta"]:
        raise Stop("internal check failed: vbmeta")
    return bytes(out)


# ---- MTK logo partition (layout: research/boot-logo/LOGO-FORMAT.md) -------------------------------------------------
LOGO_MAGIC = 0x58881688


def logo_parse(b):
    magic, dsize = struct.unpack_from("<II", b, 0)
    if magic != LOGO_MAGIC:
        raise Stop("the logo partition has an unknown format")
    count, total = struct.unpack_from("<II", b, 0x200)
    offs = list(struct.unpack_from("<%dI" % count, b, 0x208))
    ends = offs[1:] + [total]
    end = 0x200 + total
    t0 = (end + 15) & ~15
    nz = len(b.rstrip(b"\0"))
    return {"header": b[:0x200], "streams": [b[0x200 + o:0x200 + e] for o, e in zip(offs, ends)],
            "trailer": b[t0:nz] if nz > t0 else b"", "size": len(b)}


def logo_build(p):
    count = len(p["streams"])
    pos, offs = 8 + 4 * count, []
    for s in p["streams"]:
        offs.append(pos); pos += len(s)
    out = bytearray(p["header"])
    struct.pack_into("<I", out, 4, pos)
    out += struct.pack("<II", count, pos) + struct.pack("<%dI" % count, *offs)
    for s in p["streams"]:
        out += s
    out += b"\0" * (-len(out) % 16) + p["trailer"]
    if len(out) > p["size"]:
        raise Stop("the new logo does not fit the logo partition")
    return bytes(out) + b"\0" * (p["size"] - len(out))


def build_logo(src, kit=KIT):
    info = kit_info(kit)
    if sha256(src) == info["out"]["logo"]:
        return src
    if sha256(src) != info["stock"]["logo"]:
        raise Stop("the tablet's boot logo is not the stock one. Use --no-logo to keep it.")
    p = logo_parse(src)
    for i in info["logo_images"]:
        with open(os.path.join(kit, "logo", "logo-%02d.zlib" % i), "rb") as f:
            p["streams"][i] = f.read()
    out = logo_build(p)
    if sha256(out) != info["out"]["logo"]:
        raise Stop("internal check failed: logo")
    return out


# ---- super: LP metadata (which bytes of super belong to system_<slot>) ----------------------------------------------
def lp_partitions(head, slot_index):
    if struct.unpack_from("<I", head, 4096)[0] != 0x616C4467:
        raise Stop("super has no valid partition table (LP geometry)")
    mms = struct.unpack_from("<I", head, 4096 + 40)[0]
    base = 4096 + 8192 + slot_index * mms
    h = head[base:base + mms]
    if h[:4] != b"0PLA":
        raise Stop("super's partition table for this slot is missing")
    hsize, tsize = struct.unpack_from("<I", h, 8)[0], struct.unpack_from("<I", h, 44)[0]
    t = h[hsize:hsize + tsize]
    (po, pn, ps), (eo, en, es) = struct.unpack_from("<III", h, 80), struct.unpack_from("<III", h, 92)
    parts = {}
    for i in range(pn):
        name, attrs, fei, ne, gi = struct.unpack_from("<36sIIII", t, po + i * ps)
        exts = []
        for j in range(ne):
            nsec, ttype, tdata, tsrc = struct.unpack_from("<QIQI", t, eo + (fei + j) * es)
            if ttype != 0 or tsrc != 0:
                raise Stop("super uses an extent layout this installer does not handle")
            exts.append((tdata * 512, nsec * 512))
        parts[name.split(b"\0")[0].decode()] = exts
    return parts, sha256(h[:hsize + tsize])


def system_ranges(head, slot, image_size):
    """[(offset in super, length)] that the system image is written to, in order."""
    parts, md = lp_partitions(head, "ab".index(slot))
    for need in ("system_" + slot, "vendor_" + slot):
        if sum(l for _, l in parts.get(need, [])) == 0:
            raise Stop("super's partition table for slot %s has no %s. The tablet may be in the middle of a system "
                       "update. Start stock Android, let any update finish, restart once, and try again." % (slot, need))
    total = sum(l for _, l in parts["system_" + slot])
    if image_size > total:
        raise Stop("system_%s is %d bytes, the system image needs %d" % (slot, total, image_size))
    ranges, left = [], image_size
    for off, ln in parts["system_" + slot]:
        if left <= 0:
            break
        ranges.append((off, min(ln, left))); left -= ln
    return ranges, md


# ---- misc: which slot boots, and whether a Virtual A/B update is pending --------------------------------------------
def misc_slot(b):
    bc = b[0x800:0x820]
    if struct.unpack_from("<I", bc, 4)[0] != 0x42414342:
        raise Stop("misc has no boot slot information")
    if struct.unpack_from("<I", bc, 28)[0] != zlib.crc32(bc[:28]) & 0xFFFFFFFF:
        raise Stop("misc boot slot information is damaged (CRC)")
    a, bb = (struct.unpack_from("<H", bc, 12 + 2 * i)[0] for i in range(2))
    pa, pb = a & 0xF, bb & 0xF
    if pa == pb:
        raise Stop("both slots have the same boot priority; cannot tell which one is active")
    return "a" if pa > pb else "b"


def misc_vab_status(b):
    """0 none, 1 unknown, 2 snapshotted, 3 merging, 4 cancelled (MiscVirtualABMessage at misc+32 KiB)."""
    m = b[0x8000:0x8040]
    if struct.unpack_from("<I", m, 1)[0] != 0x56740AB0:
        return 0
    return m[5]


# =================================================================================================================
# The backup folder: <part>.bin for every partition in BACKUP_PARTS, SHA256SUMS, backup.json
# =================================================================================================================
class Backup:
    def __init__(self, path):
        self.path = os.path.abspath(path)

    def f(self, part):
        return os.path.join(self.path, part + ".bin")

    def read(self, part):
        with open(self.f(part), "rb") as fh:
            return fh.read()

    def super_head(self):
        with open(self.f("super"), "rb") as fh:
            return fh.read(0x100000)

    def write_sums(self, meta):
        meta = dict(meta, created=datetime.datetime.now().isoformat(timespec="seconds"), tool=RELEASE_NAME)
        with open(os.path.join(self.path, "backup.json"), "w") as fh:
            json.dump(meta, fh, indent=1)
        lines = ["%s  %s" % (sha256_file(self.f(p)), p + ".bin") for p in BACKUP_PARTS]
        with open(os.path.join(self.path, "SHA256SUMS"), "w") as fh:
            fh.write("\n".join(lines) + "\n")

    def verify(self):
        """Every partition file present, the right size, and matching SHA256SUMS when the folder has one."""
        missing = [p for p in BACKUP_PARTS if not os.path.isfile(self.f(p))]
        if missing:
            raise Stop("the backup folder %s lacks %s" % (self.path, ", ".join(p + ".bin" for p in missing)))
        for p in BACKUP_PARTS:
            if os.path.getsize(self.f(p)) != GPT[p][1]:
                raise Stop("%s.bin in the backup has the wrong size (%d, expected %d)" % (p, os.path.getsize(self.f(p)), GPT[p][1]))
        sums = os.path.join(self.path, "SHA256SUMS")
        if not os.path.isfile(sums):
            note("The backup folder has no SHA256SUMS (made by hand with mtkclient?). Sizes are fine; contents unchecked.")
            return False
        want = {}
        for line in open(sums):
            parts = line.split()
            if len(parts) >= 2:
                want[parts[1].lstrip("*").lstrip("./")] = parts[0].lower()
        for p in BACKUP_PARTS:
            note("checking %s.bin ..." % p)
            if want.get(p + ".bin") != sha256_file(self.f(p)):
                raise Stop("%s.bin in the backup does not match its SHA256SUMS line: the backup is damaged" % p)
        return True


def analyze(bk, slot_hint=None, want_logo=True, for_update=False, kit=KIT):
    """Read the backup and decide what would be written. Raises Stop on anything unexpected."""
    misc = bk.read("misc")
    slot = misc_slot(misc)
    if slot_hint and slot_hint != slot:
        raise Stop("the active slot in the backup (%s) is not the one Android reported (%s). Do not continue; "
                   "ask in the XDA thread." % (slot, slot_hint))
    vab = misc_vab_status(misc)
    if vab in (2, 3):
        raise Stop("a system update is still pending on the tablet (Virtual A/B status %d). Start stock Android, let "
                   "it finish, restart once, then make a new backup." % vab)
    boot = bk.read("boot_" + slot)
    kind = classify_boot(boot, kit)
    if for_update and kind != "inkognito":
        raise Stop("boot_%s is not an InKognito boot image (found: %s); use install, not update" % (slot, kind))
    if not for_update and kind != "stock":
        if kind == "inkognito":
            raise Stop("InKognito is already installed on slot %s. Use 'update' to install a newer release." % slot)
        raise Stop("boot_%s is not the stock %s boot image (found: %s). Restore the stock boot image first." % (slot, EXPECT_FW, kind))
    return {"slot": slot, "vab": vab, "boot_kind": kind, "want_logo": want_logo}


# =================================================================================================================
# Device access. Every adb and mtkclient call goes through these two classes; --dry-run prints instead of running.
# =================================================================================================================
class Adb:
    def __init__(self, exe, dry):
        self.exe, self.dry = exe, dry

    def run(self, *args, check=True):
        cmd = [self.exe] + list(args)
        if self.dry:
            say("    [dry-run] " + " ".join(shlex.quote(c) for c in cmd))
            return ""
        p = subprocess.run(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           universal_newlines=True)
        if check and p.returncode != 0:
            raise Stop("adb %s failed:\n%s" % (" ".join(args), p.stdout.strip()))
        return p.stdout.replace("\r", "")

    def prop(self, name):
        return self.run("shell", "getprop", name).strip()


class Mtk:
    """mtkclient, run from a session folder of its own (it keeps its connection state in the working directory).
    Writes are refused unless the partition or byte range was allowed for this run."""

    def __init__(self, cmd, session, dry, log):
        self.cmd, self.session, self.dry, self.log = cmd, session, dry, log
        self.allowed_parts, self.allowed_ranges = set(), []
        self.connected = False

    def _guard_name(self, name):
        if re.sub(r"_[ab]$", "", name) in FORBIDDEN:
            raise Stop("internal safety check: refusing to touch partition %s" % name)

    def _run(self, args, catch=False, adb=None, what=None, verify=False):
        if not self.cmd:
            if not self.dry:
                raise Stop("mtkclient not found. Install it (INSTALL.md, step 2) and either make the 'mtk' command "
                           "available or pass --mtk \"python /path/to/mtk.py\".")
            self.cmd = ["mtk"]
        line = " ".join(shlex.quote(c) for c in self.cmd + args)
        with open(self.log, "a") as fh:
            fh.write("+ mtk %s\n" % " ".join(args))
        if self.dry:
            say("    [dry-run] " + line)
            return ""
        os.makedirs(self.session, exist_ok=True)
        if not self.connected:
            st = os.path.join(self.session, ".state")
            if os.path.exists(st):
                os.replace(st, st + ".old")
        p = subprocess.Popen(self.cmd + args, cwd=self.session, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT,
                             universal_newlines=True, bufsize=1)
        if catch and not self.connected:
            def kick():
                time.sleep(4)
                if adb and adb.run("get-state", check=False).strip() == "device":
                    say("    restarting the tablet (adb reboot) so mtkclient can catch it ...")
                    adb.run("reboot", check=False)
            say("    mtkclient is waiting for the tablet. If nothing happens within a minute: hold the power button "
                "for about 10 to 12 seconds with the USB cable connected (or press it if the tablet is off).")
            threading.Thread(target=kick, daemon=True).start()
        out = []
        meter = Meter(what, verify)
        with open(self.log, "a") as fh:
            for l in p.stdout:
                l = redact(l)
                fh.write(l)                          # the log keeps every line, progress included
                if meter.feed(l):                    # a progress line: one self-updating line on the screen
                    continue
                out.append(l)
                if catch and not self.connected:
                    sys.stdout.write("    | " + l)
        meter.close()
        rc = p.wait()
        text = "".join(out)
        if rc != 0:
            raise Stop("mtkclient failed (exit %d) running: mtk %s\nThe last lines of its output:\n%s"
                       % (rc, " ".join(args), "".join(out[-12:])))
        self.connected = True
        return text

    def printgpt(self, adb=None):
        out = self._run(["printgpt"], catch=True, adb=adb)
        if self.dry:
            return dict(GPT)
        seen = {}
        for line in out.splitlines():
            m = re.match(r"\s*([\w.-]+):\s+Offset (0x[0-9a-fA-F]+), Length (0x[0-9a-fA-F]+)", line)
            if m:
                seen[m.group(1)] = (int(m.group(2), 16), int(m.group(3), 16))
        return seen

    def read_parts(self, parts, files, verify=False):
        for p in parts:
            self._guard_name(p)
            if p not in GPT:
                raise Stop("internal: read of unknown partition %s" % p)
        self._run(["r", ",".join(parts), ",".join(files)], what=parts[0] if len(parts) == 1 else None, verify=verify)

    def read_range(self, off, length, path, what=None, verify=False):
        self._run(["ro", hex(off), hex(length), path], what=what, verify=verify)

    def write_part(self, part, path):
        self._guard_name(part)
        if part not in self.allowed_parts:
            raise Stop("internal safety check: %s is not on this run's write list" % part)
        if not self.dry and os.path.getsize(path) != GPT[part][1]:
            raise Stop("internal safety check: %s image has the wrong size" % part)
        self._run(["w", part, path], what=part)

    def write_range(self, off, length, path, what=None):
        if not any(a <= off and off + length <= a + n for a, n in self.allowed_ranges):
            raise Stop("internal safety check: range %#x+%#x is not on this run's write list" % (off, length))
        if not self.dry and os.path.getsize(path) != length:
            raise Stop("internal safety check: file for range %#x has the wrong size" % off)
        self._run(["wo", hex(off), hex(length), path], what=what)

    def reset(self):
        self._run(["reset"])


# ---- progress meter ---------------------------------------------------------------------------------------------
# mtkclient reports a transfer on one line that it overwrites in place ("\r"); through the pipe every update arrives
# as a line of its own (a 12.5 GB backup: 240,000 of them). Real lines (install test, 2026-11-11):
#   Done |----------| 0.2% Read: (0x10000/0x2800000),0.00 MB/s                        first line of every transfer
#   Progress: |----------| 0.1% Write: (0x400000/0x12D780000), 14.01 MB/s 05m:38s left
#   Progress: |████------| 38.0% Read: (0xF30000/0x2800000), 27.85 MB/s
# All of them go to the log, as before. The screen gets one line that updates itself:
#   writing system_b part 1: 12% (279 MB / 2.27 GB), 7.5 MB/s, ~5 min left
_PROGRESS = re.compile(r"^\s*(?:Progress:|Done)\s*\|[^|]*\|\s*([\d.]+)%\s*(Read|Write):?\s*"
                       r"\(0x([0-9A-Fa-f]+)/0x([0-9A-Fa-f]+)\),\s*([\d.]+)\s*([KMGT]?B)/s"
                       r"(?:\s+(?:(\d+)h:)?(?:(\d+)m:)?(\d+)s left)?")
_DUMPING = re.compile(r'Dumping partition "([\w.-]+)"')
_UNITS = {"B": 1, "KB": 1 << 10, "MB": 1 << 20, "GB": 1 << 30, "TB": 1 << 40}


def parse_progress(line):
    """One mtkclient progress line -> {"kind": "read" | "write", "pct", "done", "total" (bytes), "speed" (bytes/s,
    mtkclient's own momentary figure), "left" (seconds, or None when mtkclient printed none)}; None for other lines."""
    m = _PROGRESS.match(line)
    if not m or int(m.group(4), 16) == 0:
        return None
    left = None
    if m.group(9) is not None:
        left = int(m.group(7) or 0) * 3600 + int(m.group(8) or 0) * 60 + int(m.group(9))
    return {"kind": m.group(2).lower(), "pct": float(m.group(1)), "done": int(m.group(3), 16),
            "total": int(m.group(4), 16), "speed": float(m.group(5)) * _UNITS[m.group(6)], "left": left}


def fmt_size(n):
    """Bytes in the units mtkclient and these docs use (1 GB = 1024 MB)."""
    if n < 1 << 30:
        return "%d MB" % round(n / (1 << 20))
    return ("%.2f GB" if n < 100 << 30 else "%.0f GB") % (n / (1 << 30))


def fmt_time(sec):
    sec = int(round(sec))
    if sec < 60:
        return "%d s" % sec
    if sec < 3600:
        return "%d min %02d s" % divmod(sec, 60)
    return "%d h %02d min" % divmod(sec // 60, 60)


def fmt_left(sec):
    if sec < 55:
        return "~%d s left" % max(5, 5 * int(round(sec / 5.0)))
    if sec < 3570:
        return "~%d min left" % int(round(sec / 60.0))
    return "~%d h %02d min left" % divmod(int(round(sec / 60.0)), 60)


class Meter:
    """Turns mtkclient's progress lines into one line on the screen. On a terminal the line updates in place and a
    summary stays for the long transfers; without a terminal (output piped to a file) a plain line is printed every
    10 %. Speed and time left are measured here from the bytes done: mtkclient's own figures jump around and its
    hours are wrong."""
    MIN_LIVE = 32 << 20        # terminal: a shorter transfer is over before its line could be read
    MIN_PLAIN = 256 << 20      # no terminal: only the long transfers are reported
    KEEP_S = 10                # terminal: a transfer that took at least this long leaves a summary line
    REDRAW_S = 0.2

    def __init__(self, what=None, verify=False, out=None, tty=None, clock=time.monotonic, width=None):
        self.what, self.verify, self.clock = what, verify, clock
        self.out = out if out is not None else sys.stdout
        if tty is None:
            try:
                tty = self.out.isatty()
            except Exception:
                tty = False
        self.tty = tty
        self.width = width or (shutil.get_terminal_size((100, 24)).columns if tty else 0)
        self.name = what
        self.cur, self.done = None, 0   # (kind, total) of the transfer in progress, its bytes so far
        self.live = 0              # length of the line now on the terminal (0 = none)

    def feed(self, line):
        """True when the line was a progress line (shown here, not to be printed by the caller)."""
        p = parse_progress(line)
        if not p:
            m = _DUMPING.search(line)
            if m and not self.what:
                self.name = m.group(1)
            return False
        now = self.clock()
        if self.cur != (p["kind"], p["total"]) or p["done"] < self.done:      # a new transfer starts
            self._end_line()
            self.cur, self.t0, self.d0 = (p["kind"], p["total"]), now, p["done"]
            self.drawn, self.decile, self.finished = None, 0, False
        self.done = p["done"]
        if self.finished:
            return True
        if p["done"] >= p["total"]:
            self.finished = True
            self._finish(p, now)
        elif self.tty:
            if p["total"] >= self.MIN_LIVE and (self.drawn is None or now - self.drawn >= self.REDRAW_S):
                self._draw(self._text(p, now))
                self.drawn = now
        elif p["total"] >= self.MIN_PLAIN and min(int(p["pct"]) // 10, 9) > self.decile:
            self.decile = int(p["pct"]) // 10
            self.out.write("    " + self._text(p, now) + "\n"); self.out.flush()
        return True

    def close(self):
        """The mtkclient call ended: a line still on the terminal (a transfer that stopped early) stays visible."""
        self._end_line()

    def _label(self, kind):
        verb = "checking" if self.verify and kind == "read" else {"read": "reading", "write": "writing"}[kind]
        return "%s %s" % (verb, self.name or "the tablet")

    def _speed(self, p, now):
        el = now - self.t0
        return (p["done"] - self.d0) / el if el >= 1 and p["done"] > self.d0 else p["speed"]

    def _text(self, p, now):
        sp = self._speed(p, now)
        s = "%s: %d%% (%s / %s)" % (self._label(p["kind"]), min(int(p["pct"]), 99), fmt_size(p["done"]), fmt_size(p["total"]))
        if sp > 0:
            s += ", %.1f MB/s, %s" % (sp / (1 << 20), fmt_left((p["total"] - p["done"]) / sp))
        return s

    def _finish(self, p, now):
        el = now - self.t0
        s = "%s: 100%% (%s) in %s" % (self._label(p["kind"]), fmt_size(p["total"]), fmt_time(el))
        if el >= 1:
            s += ", %.1f MB/s" % ((p["total"] - self.d0) / el / (1 << 20))
        if self.tty:
            if self.live and el >= self.KEEP_S:
                self._draw(s); self.out.write("\n"); self.live = 0
            else:
                self._erase()
        elif p["total"] >= self.MIN_PLAIN:
            self.out.write("    " + s + "\n")
        self.out.flush()

    def _draw(self, text):
        text = ("    " + text)[:max(20, self.width - 1)]
        self.out.write("\r" + text + " " * max(0, self.live - len(text)))
        self.out.flush()
        self.live = len(text)

    def _erase(self):
        if self.live:
            self.out.write("\r" + " " * self.live + "\r")
            self.live = 0

    def _end_line(self):
        if self.live:
            self.out.write("\n"); self.out.flush()
            self.live = 0


# mtkclient prints the chip's unique IDs and the storage serial while it connects; they never reach the log or screen
_IDS = re.compile(r"((?:ME_?ID|SOC_?ID|HW_?ID|CID|[Ss]erial(?: number)?|UFS (?:CID|Serial))\s*[:=]\s*)(\S+)")


def redact(line):
    return _IDS.sub(lambda m: m.group(1) + "<removed>", line)


def find_mtk(opt):
    if opt:
        return shlex.split(opt, posix=(os.name != "nt"))
    env = os.environ.get("MTK")
    if env:
        return shlex.split(env, posix=(os.name != "nt"))
    if shutil.which("mtk"):
        return [shutil.which("mtk")]
    for d in (os.path.join(HERE, "mtkclient"), os.path.join(os.path.dirname(HERE), "mtkclient")):
        if os.path.isfile(os.path.join(d, "mtk.py")):
            return [sys.executable, os.path.join(d, "mtk.py")]
    return None


# =================================================================================================================
# Steps shared by the commands
# =================================================================================================================
class Run:
    def __init__(self, args):
        self.a = args
        self.dry = args.dry_run
        stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        self.work = os.path.abspath(args.work or os.path.join(os.getcwd(), "inkognito-work-" + stamp))
        os.makedirs(self.work, exist_ok=True)
        self.log = os.path.join(self.work, "inkognito.log")
        self.mtk = Mtk(find_mtk(args.mtk), os.path.join(self.work, "mtk-session"), self.dry, self.log)
        adb = args.adb or shutil.which("adb")
        self.adb = Adb(adb, self.dry) if adb and not args.skip_adb else None
        self.state = "local"            # what has been done to the tablet, for the error message
        self.slot = None
        self.backup = None

    # ---- adb pre-checks (stock or InKognito Android running, USB debugging allowed) ----
    def adb_checks(self, for_update=False):
        if self.a.skip_adb:
            note("--skip-adb: no Android-side checks. Make sure of firmware %s and %d%% battery yourself." % (EXPECT_FW, MIN_BATTERY))
            return None
        if not self.adb:
            raise Stop("adb was not found. Install platform-tools (INSTALL.md, step 2), or use --skip-adb.")
        step("Checking the tablet over adb")
        if self.dry:
            for c in (["devices"], ["shell", "getprop", "ro.build.versionName"],
                      ["shell", "getprop", "ro.vendor.build.version.incremental"], ["shell", "wm", "size"],
                      ["shell", "dumpsys", "battery"], ["shell", "getprop", "ro.boot.slot_suffix"]):
                self.adb.run(*c)
            return None
        devs = [l.split() for l in self.adb.run("devices").splitlines()[1:] if l.strip() and not l.startswith("*")]
        if len(devs) != 1:
            raise Stop("exactly one device must be connected over adb (found %d). Unplug other phones." % len(devs))
        if devs[0][1] == "unauthorized":
            raise Stop("the tablet has not allowed this computer yet. Unlock it and accept 'Allow USB debugging?'.")
        if devs[0][1] != "device":
            raise Stop("the tablet is in state '%s' over adb" % devs[0][1])
        fw, inc = self.adb.prop("ro.build.versionName"), self.adb.prop("ro.vendor.build.version.incremental")
        if fw != EXPECT_FW and inc != EXPECT_VENDOR_INC:
            raise Stop("this tablet is not on firmware %s (versionName '%s', vendor '%s')" % (EXPECT_FW, fw, inc))
        wm = re.search(r"Physical size:\s*(\S+)", self.adb.run("shell", "wm", "size"))
        if not wm or wm.group(1) != EXPECT_WM:
            raise Stop("the screen is not %s: this is not a B1051C Pro" % EXPECT_WM)
        lvl = re.search(r"^\s*level:\s*(\d+)", self.adb.run("shell", "dumpsys", "battery"), re.M)
        if not lvl or int(lvl.group(1)) < MIN_BATTERY:
            raise Stop("charge the tablet to at least %d%% first (now %s%%)" % (MIN_BATTERY, lvl.group(1) if lvl else "?"))
        slot = self.adb.prop("ro.boot.slot_suffix").lstrip("_")
        if slot not in ("a", "b"):
            raise Stop("could not read the active slot over adb")
        note("firmware %s, screen %s, battery %s%%, active slot %s" % (fw or inc, wm.group(1), lvl.group(1), slot))
        return slot

    # ---- catch the tablet with mtkclient and check its partition table ----
    def connect(self):
        step("Connecting with mtkclient")
        self.state = "connecting"
        seen = self.mtk.printgpt(self.adb)
        for name, want in GPT.items():
            if seen.get(name) != want:
                raise Stop("the partition table does not match a B1051C Pro on firmware %s (%s: %s, expected %s). "
                           "Nothing was written." % (EXPECT_FW, name, seen.get(name), tuple(map(hex, want))))
        note("partition table OK (%d partitions checked)" % len(GPT))
        self.state = "connected"

    def make_backup(self, dest):
        bk = Backup(dest)
        if os.path.exists(os.path.join(bk.path, "SHA256SUMS")):
            raise Stop("%s already holds a backup; pick a new folder" % bk.path)
        if not self.dry:
            os.makedirs(bk.path, exist_ok=True)
        step("Backing up %s to %s" % (", ".join(BACKUP_PARTS), bk.path))
        note("About 12.5 GB (super is 12 GiB); this takes a while. Your data partition (userdata) is not included.")
        self.mtk.read_parts(BACKUP_PARTS, [bk.f(p) for p in BACKUP_PARTS])
        if self.dry:
            return bk
        for p in BACKUP_PARTS:
            if not os.path.isfile(bk.f(p)) or os.path.getsize(bk.f(p)) != GPT[p][1]:
                raise Stop("mtkclient did not write a complete %s.bin" % p)
        bk.write_sums({"gpt": {k: [hex(v[0]), hex(v[1])] for k, v in GPT.items()}, "slot": misc_slot(bk.read("misc"))})
        note("backup written and checksummed: %s" % os.path.join(bk.path, "SHA256SUMS"))
        return bk

    def write_verified(self, part, path):
        self.mtk.write_part(part, path)
        rb = os.path.join(self.work, part + ".readback.bin")
        self.mtk.read_parts([part], [rb], verify=True)
        if not self.dry:
            if sha256_file(rb) != sha256_file(path):
                raise Stop("READBACK MISMATCH on %s" % part)
            os.unlink(rb)
            note("%s written and read back OK" % part)

    def write_range_verified(self, off, length, path, label):
        self.mtk.write_range(off, length, path, what=label)
        rb = os.path.join(self.work, "range.readback.bin")
        self.mtk.read_range(off, length, rb, what=label, verify=True)
        if not self.dry:
            if sha256_file(rb) != sha256_file(path):
                raise Stop("READBACK MISMATCH on %s" % label)
            os.unlink(rb)
            note("%s written and read back OK" % label)

    def zeros(self, name, length):
        path = os.path.join(self.work, name)
        if not self.dry:
            with open(path, "wb") as f:
                f.truncate(length)
        return path

    def system_image(self):
        imgs = [n for n in ("system.img", "system-nogapps.img") if os.path.isfile(os.path.join(HERE, n))]
        if not imgs:
            raise Stop("no system image next to the installer")
        want = self.a.variant
        if want is None:
            if len(imgs) == 1:
                want = "gapps" if imgs[0] == "system.img" else "nogapps"
            elif self.dry or not sys.stdin.isatty():
                want = "gapps"
            else:
                say("\nThis release has two system images:")
                say("  1  with Google Play (Play Store, Play services, Google setup)")
                say("  2  without Google (no Google apps or services at all)")
                want = {"1": "gapps", "2": "nogapps"}.get(ask("Choose 1 or 2: "))
                if not want:
                    raise Stop("no system image chosen")
        name = "system.img" if want == "gapps" else "system-nogapps.img"
        path = os.path.join(HERE, name)
        if not os.path.isfile(path):
            raise Stop("%s is not in this release folder" % name)
        return path

    def check_release_files(self, system_img):
        step("Checking the release files")
        sums = os.path.join(HERE, "SHA256SUMS")
        if not os.path.isfile(sums):
            raise Stop("SHA256SUMS is missing from the release folder")
        want = {}
        for line in open(sums):
            parts = line.split()
            if len(parts) >= 2:
                want[parts[1].lstrip("*").lstrip("./")] = parts[0].lower()
        files = [os.path.relpath(system_img, HERE)] + sorted(
            os.path.relpath(os.path.join(r, f), HERE).replace(os.sep, "/") for r, _, fs in os.walk(KIT) for f in fs)
        for rel in files:
            got = sha256_file(os.path.join(HERE, rel))
            if want.get(rel) != got:
                raise Stop("%s: SHA-256 MISMATCH (download damaged?). Download the release again." % rel)
        note("%d files match SHA256SUMS" % len(files))

    def build_images(self, bk, info, slot):
        step("Building boot, vbmeta and logo from your tablet's own partitions")
        out = {}
        b = build_boot(bk.read("boot_" + slot))
        out["boot"] = os.path.join(self.work, "boot_%s.inkognito.img" % slot)
        open(out["boot"], "wb").write(b)
        note("boot_%s: stock kernel + device tree (microSD cap 50 MHz) + Magisk 30.7 + InKognito charger rc" % slot)
        v = build_vbmeta(bk.read("vbmeta_" + slot))
        out["vbmeta"] = os.path.join(self.work, "vbmeta_%s.inkognito.img" % slot)
        open(out["vbmeta"], "wb").write(v)
        note("vbmeta_%s: stock with dm-verity hashtree checking off (1 byte)" % slot)
        if info["want_logo"]:
            lg = build_logo(bk.read("logo"))
            if sha256(lg) != sha256(bk.read("logo")):
                out["logo"] = os.path.join(self.work, "logo.inkognito.img")
                open(out["logo"], "wb").write(lg)
                note("logo: InKognito boot picture (images 0, 2, 38); the charging pictures stay")
            else:
                note("logo already up to date; not written")
        return out

    def confirm(self, phrase, lines):
        say("\n" + "-" * 70)
        for l in lines:
            say("  " + l)
        say("-" * 70)
        if self.dry:
            say("  [dry-run] would ask you to type %s here" % phrase)
            return
        if ask("Type %s (capital letters) and press Enter to start. Anything else cancels: " % phrase) != phrase:
            raise Stop("cancelled; nothing was written")


# Seen on the test flash (2026-11-10): after mtkclient's reset the tablet stays dark, or shows the charging screen,
# instead of starting Android.
POWER_ON = ("    If the screen stays dark or shows the charging picture: hold the power button for 3 to 5 seconds and let go.\n"
            "    Do not hold it for 10 seconds or longer, and do not press it while the boot logo or animation is shown.")

# The restart in step 3 is a precaution: on the first clean install (2026-11-11) the modules' SELinux rules were only
# loaded from the second start on. alpha 7 / beta 1 fix that; the step stays until another clean install has proven it.
AFTER_INSTALL = """
Done. What happens next:
  1. The first start takes about 5 minutes and restarts once by itself. On e-ink the screen can sit unchanged
     for a minute or more.
  2. Google setup appears (without Google: Android's own setup), then InKognito Setup.
     If the tablet asks "Allow USB debugging?", tap Allow.
  3. After InKognito Setup, restart the tablet ONCE: hold the power button, then tap Restart.
  4. When you no longer need the computer, turn USB debugging off (Settings > System > Developer options).
Keep the backup folder %s: it is your way back (python inkognito.py restore-stock --backup <folder>)."""


def ask(prompt):
    """input(), but a closed stdin (no terminal, piped input already used up) cancels instead of a traceback.
    Child processes get stdin=DEVNULL so they can never read the answer meant for this prompt."""
    try:
        return input(prompt).strip()
    except EOFError:
        say("")
        raise Stop("no answer (input closed); nothing was written")


def hint(run, err):
    s = run.slot or "<slot>"
    say("\n" + "-" * 70)
    st = run.state
    if st in ("local", "adb"):
        say("Nothing was written to your tablet.")
    elif st in ("connecting", "connected", "backup"):
        say("Nothing was written to your tablet. It is connected to mtkclient; to start it normally run:")
        say("    mtk reset      (or hold the power button for about 12 seconds)")
    elif st == "writing":
        say("Some partitions may already be written. Do NOT start the tablet yet.")
        if run.backup:
            say("To go back to how it was, restore your backup (the tablet stays connected):")
            say("    python inkognito.py restore-stock --backup %s" % shlex.quote(run.backup.path))
        say("Or fix the problem above and run the same command again. Ask in the XDA thread if unsure,")
        say("with the file %s (it holds no personal data)." % run.log)
    elif st == "written":
        say("Everything was written. Only the final restart did not happen: hold power for about 12 seconds.")
    say("-" * 70)


# =================================================================================================================
# Commands
# =================================================================================================================
def cmd_backup(run):
    run.state = "adb"
    run.adb_checks()
    run.connect()
    run.state = "backup"
    bk = run.make_backup(run.a.backup or os.path.join(os.getcwd(), "B1051C-backup-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S")))
    if not run.dry:
        slot = misc_slot(bk.read("misc"))
        note("active slot %s; boot_%s is %s" % (slot, slot, classify_boot(bk.read("boot_" + slot))))
    run.mtk.reset()
    say(POWER_ON)
    say("\nBackup done: %s\nKeep this folder until you are sure you want to stay on InKognito." % bk.path)


def cmd_prepare(run):
    """Offline: verify a backup and build the images, as install would. No adb, no mtkclient."""
    if not run.a.backup:
        raise Stop("prepare needs --backup FOLDER")
    bk = Backup(run.a.backup)
    step("Checking the backup %s" % bk.path)
    bk.verify()
    info = analyze(bk, want_logo=not run.a.no_logo, for_update=run.a.update)
    run.slot = info["slot"]
    note("active slot %s, boot %s, Virtual A/B status %d" % (info["slot"], info["boot_kind"], info["vab"]))
    img = run.system_image()
    ranges, md = system_ranges(bk.super_head(), info["slot"], os.path.getsize(img))
    note("system image %s -> system_%s: %s" % (os.path.basename(img), info["slot"],
         ", ".join("super+%#x (%d B)" % r for r in ranges)))
    out = run.build_images(bk, info, info["slot"])
    for k, v in out.items():
        note("%-7s %s  %s" % (k, sha256_file(v)[:16], v))
    say("\nOK: this backup can be used for an install. Nothing was sent to the tablet.")


def cmd_install(run, update=False):
    sysimg = run.system_image()
    run.check_release_files(sysimg)
    run.state = "adb"
    adb_slot = run.adb_checks(for_update=update)
    if run.a.slot and adb_slot and run.a.slot != adb_slot:
        raise Stop("--slot %s given, but Android reports slot %s" % (run.a.slot, adb_slot))
    slot_hint = run.a.slot or adb_slot
    run.connect()
    run.state = "backup"
    if run.a.backup and os.path.isfile(os.path.join(run.a.backup, "SHA256SUMS")):
        bk = Backup(run.a.backup)
        step("Using the existing backup %s" % bk.path)
        if not run.dry:
            bk.verify()
            fresh = os.path.join(run.work, "now")
            os.makedirs(fresh, exist_ok=True)
            # the backup must still describe this tablet: compare what the install depends on
            check = ["misc", "boot_a", "boot_b", "vbmeta_a", "vbmeta_b"]
            run.mtk.read_parts(check, [os.path.join(fresh, p + ".bin") for p in check])
            run.mtk.read_range(GPT["super"][0], 0x100000, os.path.join(fresh, "super_head.bin"))
            for p in check:
                if sha256_file(os.path.join(fresh, p + ".bin")) != sha256_file(bk.f(p)):
                    raise Stop("the tablet's %s changed since this backup was made; make a new backup" % p)
            if open(os.path.join(fresh, "super_head.bin"), "rb").read() != bk.super_head():
                raise Stop("super's partition table changed since this backup was made; make a new backup")
            note("the backup matches the tablet")
    elif update:
        bk = Backup(os.path.join(run.work, "current"))
        if not run.dry:
            os.makedirs(bk.path, exist_ok=True)
        step("Reading the current InKognito partitions (no full backup needed for an update)")
        parts = ["misc", "boot_a", "boot_b", "vbmeta_a", "vbmeta_b", "logo"]
        run.mtk.read_parts(parts, [bk.f(p) for p in parts])
        run.mtk.read_range(GPT["super"][0], 0x100000, bk.f("super"))
    else:
        bk = run.make_backup(run.a.backup or os.path.join(os.getcwd(), "B1051C-backup-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S")))
    run.backup = bk if not update else (bk if run.a.backup else None)

    offline = run.dry and not (run.a.backup and os.path.isfile(Backup(run.a.backup).f("misc")))
    if offline:
        slot = run.slot = slot_hint or "b"
        step("[dry-run] the backup is analysed here: active slot, stock boot/vbmeta, super partition table")
        out = {"boot": "boot_%s.inkognito.img" % slot, "vbmeta": "vbmeta_%s.inkognito.img" % slot}
        if not run.a.no_logo:
            out["logo"] = "logo.inkognito.img"
        ranges = None
    else:                                # a real run, or a dry run with --backup: everything but the device is real
        info = analyze(bk, slot_hint, want_logo=not run.a.no_logo, for_update=update)
        slot = run.slot = info["slot"]
        note("active slot %s, boot: %s, no pending system update" % (slot, info["boot_kind"]))
        ranges, _ = system_ranges(bk.read("super")[:0x100000] if update and not run.a.backup else bk.super_head(),
                                  slot, os.path.getsize(sysimg))
        out = run.build_images(bk, info, slot)
        if update:
            for k, part in (("boot", "boot_" + slot), ("vbmeta", "vbmeta_" + slot), ("logo", "logo")):
                if k in out and sha256_file(out[k]) == sha256_file(bk.f(part)):
                    note("%s already up to date; not written" % part)
                    del out[k]

    sup = GPT["super"][0]
    run.mtk.allowed_parts = {"boot_" + slot, "vbmeta_" + slot, "logo", "metadata"}
    run.mtk.allowed_ranges = [(sup + o, n) for o, n in ranges or []] + [(GPT["userdata"][0], USERDATA_HEAD)]
    lines = ["Release   : %s (%s)" % (RELEASE_NAME, os.path.basename(sysimg)),
             "Slot      : %s  (write it down)" % slot]
    for k in ("boot", "vbmeta", "logo"):
        if k in out:
            lines.append("Write     : %s" % (k + "_" + slot if k != "logo" else "logo"))
    lines.append("Write     : system_%s inside super (%d MB)" % (slot, os.path.getsize(sysimg) // 1000000))
    if update:
        lines.append("Keep      : your apps, settings and files (no data wipe)")
    else:
        lines += ["ERASE     : metadata and userdata. EVERYTHING on the tablet's internal storage is lost.",
                  "Backup    : %s" % (run.backup.path if run.backup else "-")]
    lines.append("Never     : preloader, lk, seccfg, nvram, waveform or any other partition")
    run.confirm("FLASH", lines)

    run.state = "writing"
    step("Writing")
    for k in ("boot", "vbmeta", "logo"):
        if k in out:
            run.write_verified(k + "_" + slot if k != "logo" else "logo", out[k] if os.path.isabs(out[k]) else os.path.join(run.work, out[k]))
    if ranges is None:
        say("    [dry-run] mtk wo <super + system_%s offset, from super's partition table> %#x %s  (+ readback with mtk ro)"
            % (slot, os.path.getsize(sysimg), sysimg))
    pos = 0
    for i, (o, n) in enumerate(ranges or []):
        part = sysimg
        if len(ranges) > 1:
            part = os.path.join(run.work, "system.part%d.img" % i)
            if not run.dry:
                with open(sysimg, "rb") as s, open(part, "wb") as d:
                    s.seek(pos); left = n
                    while left:
                        c = s.read(min(left, 1 << 24)); d.write(c); left -= len(c)
        note("system_%s part %d: %d bytes at super+%#x (this takes several minutes)" % (slot, i + 1, n, o))
        run.write_range_verified(sup + o, n, part, "system_%s part %d" % (slot, i + 1))
        if part != sysimg and not run.dry:
            os.unlink(part)
        pos += n
    if not update:
        step("Erasing user data (metadata + the start of userdata; Android formats both on the first start)")
        run.write_verified("metadata", run.zeros("zeros-metadata.bin", GPT["metadata"][1]))
        run.write_range_verified(GPT["userdata"][0], USERDATA_HEAD, run.zeros("zeros-userdata-head.bin", USERDATA_HEAD), "userdata head")
    run.state = "written"
    step("Restarting the tablet")
    run.mtk.reset()
    say(POWER_ON)
    if update:
        say("\nDone. The first start of the new release installs the updated InKognito layer and restarts once by "
            "itself. Your apps and settings stay.")
    else:
        say(AFTER_INSTALL % (run.backup.path if run.backup else ""))


def cmd_restore(run):
    if not run.a.backup:
        raise Stop("restore-stock needs --backup FOLDER (the backup made by install)")
    bk = Backup(run.a.backup)
    step("Checking the backup %s" % bk.path)
    if not run.dry:
        has_sums = bk.verify()
        slot = misc_slot(bk.read("misc"))
        kind = classify_boot(bk.read("boot_" + slot))
        note("backup: active slot %s, boot %s, checksums %s" % (slot, kind, "OK" if has_sums else "not available"))
        if kind != "stock":
            note("WARNING: the boot image in this backup is not the stock one (%s). It is restored as it is." % kind)
    else:
        slot = run.a.slot or "b"
    run.slot = slot
    run.state = "adb"
    run.connect()
    sup = GPT["super"][0]
    if not run.dry:
        now = os.path.join(run.work, "super_head.now.bin")
        run.mtk.read_range(sup, 0x100000, now)
        head_now, head_bk = open(now, "rb").read(), bk.super_head()
        _, md_now = lp_partitions(head_now, "ab".index(slot))
        pb, md_bk = lp_partitions(head_bk, "ab".index(slot))
        if md_now != md_bk and not run.a.full_super:
            raise Stop("super's partition table on the tablet differs from the backup's. Restore the full super with "
                       "--full-super (12 GiB) instead.")
        ranges = [] if run.a.full_super else pb["system_" + slot]
    else:
        ranges = [(0, 0)]
    with_ud = os.path.isfile(bk.f("userdata"))
    run.mtk.allowed_parts = {"boot_" + slot, "vbmeta_" + slot, "logo", "metadata", "super", "userdata"} if run.a.full_super \
        else {"boot_" + slot, "vbmeta_" + slot, "logo", "metadata"}
    run.mtk.allowed_ranges = [(sup + o, n) for o, n in ranges] + [(GPT["userdata"][0], USERDATA_HEAD)]
    lines = ["Restore   : boot_%s, vbmeta_%s, logo from %s" % (slot, slot, bk.path),
             "Restore   : %s" % ("the whole super partition (12 GiB)" if run.a.full_super else "system_%s inside super" % slot),
             ("Restore   : metadata + userdata from the backup" if with_ud else
              "ERASE     : metadata and userdata (stock Android starts fresh, like after a factory reset)"),
             "Never     : preloader, lk, seccfg, nvram, waveform or any other partition"]
    run.confirm("RESTORE", lines)
    run.state = "writing"
    step("Restoring")
    for part in ("boot_" + slot, "vbmeta_" + slot, "logo"):
        run.write_verified(part, bk.f(part))
    if run.a.full_super:
        run.write_verified("super", bk.f("super"))
    else:
        for i, (o, n) in enumerate(ranges):
            chunk = os.path.join(run.work, "system.restore%d.img" % i)
            if not run.dry:
                with open(bk.f("super"), "rb") as s, open(chunk, "wb") as d:
                    s.seek(o); left = n
                    while left:
                        c = s.read(min(left, 1 << 24)); d.write(c); left -= len(c)
            run.write_range_verified(sup + o, n, chunk, "system_%s part %d" % (slot, i + 1))
            if not run.dry:
                os.unlink(chunk)
    if with_ud:
        run.write_verified("metadata", bk.f("metadata"))
        run.mtk.allowed_parts.add("userdata")
        run.write_verified("userdata", bk.f("userdata"))
    else:
        run.write_verified("metadata", run.zeros("zeros-metadata.bin", GPT["metadata"][1]))
        run.write_range_verified(GPT["userdata"][0], USERDATA_HEAD, run.zeros("zeros-userdata-head.bin", USERDATA_HEAD), "userdata head")
    run.state = "written"
    run.mtk.reset()
    say(POWER_ON)
    say("\nDone. The tablet starts the stock firmware. The first start formats the data partition and can take "
        "several minutes.")


def main(argv=None):
    ap = argparse.ArgumentParser(prog="inkognito.py", description=__doc__.split("\n\n")[0],
                                 formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__.split("\n\n", 1)[1])
    sub = ap.add_subparsers(dest="cmd")
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--dry-run", action="store_true", help="print every step and command; run neither mtkclient nor adb")
    common.add_argument("--mtk", help='how to run mtkclient, e.g. "python C:\\mtkclient\\mtk.py" (default: the mtk command)')
    common.add_argument("--adb", help="path to adb (default: adb from PATH)")
    common.add_argument("--skip-adb", action="store_true", help="no Android-side checks (tablet does not start, or no USB debugging)")
    common.add_argument("--slot", choices=("a", "b"), help="safety check: stop unless this is the active slot")
    common.add_argument("--backup", help="backup folder (install: reuse it or create it there; restore-stock: read from it)")
    common.add_argument("--work", help="folder for logs and built images (default: a new inkognito-work-<time> folder)")
    p = sub.add_parser("install", parents=[common], help="back up, then install (erases the tablet's data)")
    p.add_argument("--no-logo", action="store_true", help="keep the current boot logo")
    p.add_argument("--variant", choices=("gapps", "nogapps"), help="system image with or without Google")
    p = sub.add_parser("update", parents=[common], help="install a newer InKognito over an older one (keeps data)")
    p.add_argument("--no-logo", action="store_true", help="keep the current boot logo")
    p.add_argument("--variant", choices=("gapps", "nogapps"), help="system image with or without Google")
    p = sub.add_parser("restore-stock", parents=[common], help="put the stock firmware back from your backup")
    p.add_argument("--full-super", action="store_true", help="restore all of super (12 GiB), not only system_<slot>")
    sub.add_parser("backup", parents=[common], help="only make the backup")
    p = sub.add_parser("prepare", parents=[common], help="offline: check a backup and build the images from it")
    p.add_argument("--no-logo", action="store_true")
    p.add_argument("--variant", choices=("gapps", "nogapps"))
    p.add_argument("--update", action="store_true", help="the backup is of an InKognito tablet (update check)")
    a = ap.parse_args(argv)
    if not a.cmd:
        ap.print_help()
        return 1
    for k in ("no_logo", "variant", "full_super", "update"):
        if not hasattr(a, k):
            setattr(a, k, None)
    say("InKognito installer, %s%s" % (RELEASE_NAME, "  [DRY RUN: nothing is run on the tablet]" if a.dry_run else ""))
    run = None
    try:
        run = Run(a)
        say("Log and built images: %s" % run.work)
        {"install": cmd_install, "update": lambda r: cmd_install(r, update=True), "restore-stock": cmd_restore,
         "backup": cmd_backup, "prepare": cmd_prepare}[a.cmd](run)
        return 0
    except Stop as e:
        say("\nSTOP: %s" % e)
        if run:
            hint(run, e)
        return 1
    except KeyboardInterrupt:
        say("\nInterrupted.")
        if run:
            hint(run, None)
        return 1


if __name__ == "__main__":
    sys.exit(main())
