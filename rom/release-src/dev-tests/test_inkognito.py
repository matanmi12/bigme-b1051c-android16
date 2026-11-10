#!/usr/bin/env python3
"""Tests for inkognito.py (developer side, not in the release zip). Never runs the real adb or mtkclient: PATH is
reduced to dev-tests/mockbin + system dirs and every device call goes to the mocks, which log each call.

  python3 -I dev-tests/test_inkognito.py unit   formats and guards on the real images (private inputs, read-only)
  python3 -I dev-tests/test_inkognito.py cli    --dry-run and argument scenarios: no mock may be called
  python3 -I dev-tests/test_inkognito.py sim    install -> update -> restore-stock on a simulated tablet
                                                (a sparse disk image filled from the stock dump; ~25 GB temp space)
Inputs (private, read-only): BIGME/BIGME_B1051C_system_dump (stock fw 4.9.0, slot b), the alpha 2 release images,
a device backup (TEST_DEVICE_BACKUP, from tools/scripts/backup-device.sh), and a release folder (TEST_REL) made by
build-rom.py release.
"""
import hashlib, os, re, shutil, struct, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.dirname(HERE)
BIGME = os.environ.get("BIGME") or os.path.abspath(os.path.join(SRC, "../../.."))   # work/rom/release-src -> repo root
DUMP = os.path.join(BIGME, "BIGME_B1051C_system_dump")
ALPHA2 = os.path.join(BIGME, "release/InKognito-B1051CPro-alpha2-20261027")
REL = os.environ.get("TEST_REL")
DEV = os.environ.get("TEST_DEVICE_BACKUP")
WORK = os.environ.get("TEST_WORK") or tempfile.mkdtemp(prefix="inkognito-tests-")
os.makedirs(WORK, exist_ok=True)
sys.dont_write_bytecode = True                        # never leave __pycache__ in a release folder
sys.path.insert(0, REL or SRC)
import inkognito as ik  # noqa: E402

KIT = os.path.join(REL, "kit") if REL else os.path.join(BIGME, "work/rom/out/kit")
P = F = 0


def ok(name, cond, detail=""):
    global P, F
    if cond:
        P += 1; print("PASS", name)
    else:
        F += 1; print("FAIL", name, detail)


def raises(name, fn, text=""):
    try:
        fn()
    except ik.Stop as e:
        ok(name, text.lower() in str(e).lower(), "message: %s" % e)
        return
    ok(name, False, "no Stop raised")


def rd(path):
    return open(path, "rb").read()


def sha(b):
    return hashlib.sha256(b).hexdigest()


# ---- unit ---------------------------------------------------------------------------------------------------------
def unit():
    stock, tested = rd(os.path.join(DUMP, "boot_b.bin")), rd(os.path.join(ALPHA2, "boot.img"))
    info = ik.kit_info(KIT)
    ok("classify stock", ik.classify_boot(stock, KIT) == "stock")
    ok("classify alpha2", ik.classify_boot(tested, KIT) == "inkognito")
    ok("classify magisk-only (WRITE 4 image)",
       ik.classify_boot(rd(os.path.join(BIGME, "work/sdcard/boot_b_backup_magisk_2026-10-18.bin")), KIT) == "magisk")
    b = ik.build_boot(stock, KIT)
    q, t = ik.boot_parse(b), ik.boot_parse(tested)
    ok("boot from stock: ramdisk == tested", ik.gunzip(q["ramdisk"]) == ik.gunzip(t["ramdisk"]))
    ok("boot from stock: kernel/dtb == tested", q["kernel"] == t["kernel"] and q["dtb"] == t["dtb"])
    ok("boot from stock: AVB vbmeta blob == tested", q["avb"]["vbmeta"] == t["avb"]["vbmeta"])
    ok("boot from stock: 40 MiB", len(b) == 0x2800000)
    ok("boot from alpha2 (update) == boot from stock", ik.gunzip(ik.boot_parse(ik.build_boot(tested, KIT))["ramdisk"])
       == ik.gunzip(q["ramdisk"]))
    raises("boot refuses a Magisk-only image",
           lambda: ik.build_boot(rd(os.path.join(BIGME, "work/sdcard/boot_b_backup_magisk_2026-10-18.bin")), KIT), "not the stock")
    bad = bytearray(stock); bad[4096 + 100] ^= 1        # one kernel byte
    raises("boot refuses a changed kernel", lambda: ik.build_boot(bytes(bad), KIT), "not the stock")
    sv = rd(os.path.join(DUMP, "vbmeta_b.bin"))
    ok("vbmeta: stock -> flag, 1 byte", sha(ik.build_vbmeta(sv, KIT)) == info["out"]["vbmeta"]
       and sum(x != y for x, y in zip(sv, ik.build_vbmeta(sv, KIT))) == 1)
    ok("vbmeta: already flagged stays", ik.build_vbmeta(ik.build_vbmeta(sv, KIT), KIT) == ik.build_vbmeta(sv, KIT))
    raises("vbmeta refuses another vbmeta", lambda: ik.build_vbmeta(rd(os.path.join(DUMP, "vbmeta_a.bin")), KIT), "not the stock")
    sl = rd(os.path.join(DUMP, "logo.bin"))
    ok("logo: stock -> tested, byte-identical", ik.build_logo(sl, KIT) == rd(os.path.join(ALPHA2, "logo.img")))
    raises("logo refuses a custom logo", lambda: ik.build_logo(sl[:100] + b"x" + sl[101:], KIT), "--no-logo")
    head = rd(os.path.join(DUMP, "super.bin"))[:0x100000] if os.path.getsize(os.path.join(DUMP, "super.bin")) else b""
    r, _ = ik.system_ranges(head, "b", 2437349376)
    ok("super: system_b = super+0x23d00000 (WRITE 2 offset)", r == [(0x23d00000, 2437349376)], r)
    r2, _ = ik.system_ranges(head, "b", 0x12d780000 + 4096)   # system_b has 5 extents; the 2nd is at 0x23c80000
    ok("super: image past extent 0 continues in extent 1", r2 == [(0x23d00000, 0x12d780000), (0x23c80000, 4096)], r2)
    total = sum(n for _, n in ik.lp_partitions(head, 1)[0]["system_b"])
    raises("super: image larger than system_b", lambda: ik.system_ranges(head, "b", total + 4096), "needs")
    mms = struct.unpack_from("<I", head, 4096 + 40)[0]
    stale = bytearray(head); b0, b1 = 4096 + 8192, 4096 + 8192 + mms
    stale[b1:b1 + mms] = head[b0:b0 + mms]             # slot-B view replaced by the stale slot-0 table (Phase A)
    raises("super: stale table (vendor_b missing) stops", lambda: ik.system_ranges(bytes(stale), "b", 4096), "vendor_b")
    misc = rd(os.path.join(DUMP, "misc.bin"))
    ok("misc: dump slot b", ik.misc_slot(misc) == "b")
    m = bytearray(misc); m[0x8000:0x8008] = bytes([2]) + struct.pack("<I", 0x56740AB0) + bytes([3, 0, 0])
    ok("misc: VAB merging detected", ik.misc_vab_status(bytes(m)) == 3)
    if DEV:
        dm = rd(os.path.join(DEV, "misc.bin"))
        ok("device backup: slot b, VAB none", ik.misc_slot(dm) == "b" and ik.misc_vab_status(dm) == 0)
        ok("device backup: boot_b is InKognito", ik.classify_boot(rd(os.path.join(DEV, "boot_b.bin")), KIT) == "inkognito")
        with open(os.path.join(DEV, "super.bin"), "rb") as f:
            dh = f.read(0x100000)
        ok("device backup: system_b range unchanged", ik.system_ranges(dh, "b", 2437349376)[0] == [(0x23d00000, 2437349376)])
    # guards
    mt = ik.Mtk(["false"], WORK, True, os.path.join(WORK, "guard.log"))
    raises("guard: seccfg", lambda: mt.write_part("seccfg", "/dev/null"), "refusing")
    raises("guard: waveform", lambda: mt.read_parts(["waveform"], ["/dev/null"]), "refusing")
    raises("guard: lk_a", lambda: mt.write_part("lk_a", "/dev/null"), "refusing")
    raises("guard: boot_a not on the write list", lambda: mt.write_part("boot_a", "/dev/null"), "write list")
    mt.allowed_ranges = [(0x4f000000 + 0x23d00000, 4096)]
    raises("guard: range outside system_b", lambda: mt.write_range(0x4f000000, 4096, "/dev/null"), "write list")
    raises("guard: range overlapping the end", lambda: mt.write_range(0x4f000000 + 0x23d00000, 8192, "/dev/null"), "write list")


# ---- cli (dry runs: no mock may be called) ------------------------------------------------------------------------
def env():
    e = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", PATH=os.path.join(HERE, "mockbin") + ":/usr/bin:/bin:/usr/sbin:/sbin",
             MOCK_LOG=os.path.join(WORK, "mock-calls.log"), MOCK_STATE=os.path.join(WORK, "mock-state"))
    return e


def cli_run(args, stdin="", extra_env=None):
    e = env(); e.update(extra_env or {})
    p = subprocess.run([sys.executable, "-I", os.path.join(REL, "inkognito.py")] + args, cwd=WORK, env=e,
                       input=stdin, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True)
    return p.returncode, p.stdout


def cli():
    log = os.path.join(WORK, "mock-calls.log")
    open(log, "w").close()
    mb = os.path.join(HERE, "mockbin")
    ok("mocks first in PATH", shutil.which("adb", path=env()["PATH"]) == os.path.join(mb, "adb")
       and shutil.which("mtk", path=env()["PATH"]) == os.path.join(mb, "mtk"))
    cases = [("help", ["--help"], 0), ("install dry", ["install", "--dry-run"], 0),
             ("install dry skip-adb no-logo", ["install", "--dry-run", "--skip-adb", "--slot", "b", "--no-logo"], 0),
             ("update dry", ["update", "--dry-run", "--variant", "gapps"], 0),
             ("restore dry", ["restore-stock", "--dry-run", "--backup", DUMP, "--slot", "b"], 0),
             ("backup dry", ["backup", "--dry-run"], 0),
             ("bad slot", ["install", "--dry-run", "--slot", "c"], 2),
             ("unknown option", ["install", "--frobnicate"], 2),
             ("restore without backup", ["restore-stock", "--dry-run"], 1)]
    for name, args, want in cases:
        rc, out = cli_run(args)
        open(os.path.join(WORK, "out-%s.txt" % name.replace(" ", "-")), "w").write(out)
        ok("cli: %s (rc %d)" % (name, rc), rc == want, out[-400:])
    rc, out = cli_run(["install", "--dry-run", "--variant", "gapps"])
    order = [l for l in out.splitlines() if "[dry-run]" in l and re.search(r"[/ ]mtk ", l)]
    ok("cli: dry-run prints printgpt first and reset last", order and "printgpt" in order[0] and order[-1].rstrip().endswith("reset"), order[:2])
    ok("cli: dry-run writes zeros to metadata", any("mtk w metadata " in l for l in order))
    ok("cli: dry-run without a backup shows no concrete super offset", "<super + system_b offset" in out and "wo 0x4f000000 " not in out)
    rc, out = cli_run(["install", "--dry-run", "--skip-adb", "--variant", "gapps", "--backup", DUMP])
    ok("cli: dry-run with --backup computes the real system_b offset",
       rc == 0 and "wo 0x72d00000 %#x " % os.path.getsize(os.path.join(REL, "system.img")) in out, out[-600:])
    ok("cli: no mock was called by any dry run", os.path.getsize(log) == 0, open(log).read()[:300])
    # damaged release file
    k = os.path.join(REL, "kit", "boot", "magiskinit")
    keep = rd(k)
    try:
        open(k, "ab").write(b"x")
        rc, out = cli_run(["install", "--dry-run"])
        ok("cli: damaged kit file -> MISMATCH, rc 1", rc == 1 and "MISMATCH" in out, out[-300:])
    finally:
        open(k, "wb").write(keep)


# ---- sim: the whole install / update / restore on a disk image -----------------------------------------------------
def sim():
    disk = os.path.join(WORK, "tablet.img")
    gpt = ik.GPT
    size = gpt["userdata"][0] + ik.USERDATA_HEAD + (1 << 20)
    with open(disk, "wb") as f:
        f.truncate(size)
    print("filling the simulated tablet from the stock dump ...")
    with open(disk, "r+b") as d:
        for p in ik.BACKUP_PARTS:
            with open(os.path.join(DUMP, p + ".bin"), "rb") as s:
                d.seek(gpt[p][0])
                for c in iter(lambda: s.read(1 << 24), b""):
                    d.write(c)
        d.seek(gpt["userdata"][0]); d.write(b"\xa5" * ik.USERDATA_HEAD)      # "old user data"

    def region(name=None, off=None, n=None):
        off, n = (gpt[name] if name else (off, n))
        with open(disk, "rb") as f:
            f.seek(off); return f.read(n)

    before = {p: sha(region(p)) for p in ("boot_a", "vbmeta_a", "dtbo_a", "dtbo_b", "misc")}
    e = {"MOCK_DISK": disk}
    bk = os.path.join(WORK, "B1051C-backup")
    rc, out = cli_run(["install", "--skip-adb", "--variant", "gapps", "--backup", bk, "--work", os.path.join(WORK, "w-install")],
                      stdin="FLASH\n", extra_env=e)
    open(os.path.join(WORK, "out-sim-install.txt"), "w").write(out)
    ok("sim install rc 0", rc == 0, out[-800:])
    info = ik.kit_info(KIT)
    sysimg = rd(os.path.join(REL, "system.img"))
    sup = gpt["super"][0]
    ok("sim: boot_b is the InKognito boot", ik.classify_boot(region("boot_b"), KIT) == "inkognito")
    ok("sim: vbmeta_b flagged", sha(region("vbmeta_b")) == info["out"]["vbmeta"])
    ok("sim: logo InKognito", sha(region("logo")) == info["out"]["logo"])
    ok("sim: system_b holds system.img", region(off=sup + 0x23d00000, n=len(sysimg)) == sysimg)
    ok("sim: metadata zero", region("metadata").count(0) == gpt["metadata"][1])
    ok("sim: userdata head zero", region(off=gpt["userdata"][0], n=ik.USERDATA_HEAD).count(0) == ik.USERDATA_HEAD)
    ok("sim: slot A, dtbo and misc untouched", all(sha(region(p)) == h for p, h in before.items()))
    ok("sim: super LP metadata untouched", region(off=sup, n=0x100000) == rd(os.path.join(DUMP, "super.bin"))[:0x100000])
    ok("sim: backup made with SHA256SUMS", os.path.isfile(os.path.join(bk, "SHA256SUMS")))

    rc, out = cli_run(["update", "--skip-adb", "--variant", "gapps", "--work", os.path.join(WORK, "w-update")], stdin="FLASH\n", extra_env=e)
    open(os.path.join(WORK, "out-sim-update.txt"), "w").write(out)
    ok("sim update rc 0", rc == 0, out[-800:])
    ok("sim update: boot/vbmeta/logo up to date, not written", out.count("already up to date") == 3, out[-800:])
    ok("sim update: no data wipe", "Erasing user data" not in out)

    rc, out = cli_run(["install", "--skip-adb", "--variant", "gapps", "--work", os.path.join(WORK, "w-again")], stdin="FLASH\n", extra_env=e)
    ok("sim: install over InKognito is refused (use update)", rc == 1 and "update" in out, out[-400:])

    os.environ["MOCK_CORRUPT_READ"] = ""
    rc, out = cli_run(["restore-stock", "--skip-adb", "--backup", bk, "--work", os.path.join(WORK, "w-restore")], stdin="RESTORE\n", extra_env=e)
    open(os.path.join(WORK, "out-sim-restore.txt"), "w").write(out)
    ok("sim restore rc 0", rc == 0, out[-800:])
    for p in ("boot_b", "vbmeta_b", "logo", "boot_a", "vbmeta_a", "misc"):
        ok("sim restore: %s == stock dump" % p, region(p) == rd(os.path.join(DUMP, p + ".bin")))
    ok("sim restore: system_b == stock dump",
       sha(region(off=sup + 0x23d00000, n=0x12d780000)) == sha(rd(os.path.join(DUMP, "super.bin"))[0x23d00000:0x23d00000 + 0x12d780000]))

    rc, out = cli_run(["install", "--skip-adb", "--variant", "gapps", "--backup", bk, "--work", os.path.join(WORK, "w-corrupt")],
                      stdin="FLASH\n", extra_env=dict(e, MOCK_CORRUPT_READ="boot_b"))
    ok("sim: readback mismatch stops with a restore hint", rc == 1 and ("changed since this backup" in out or "READBACK" in out), out[-500:])
    os.unlink(disk)


if __name__ == "__main__":
    what = sys.argv[1:] or ["unit", "cli"]
    print("work dir:", WORK)
    for w in what:
        {"unit": unit, "cli": cli, "sim": sim}[w]()
    print("passed=%d failed=%d" % (P, F))
    sys.exit(1 if F else 0)
