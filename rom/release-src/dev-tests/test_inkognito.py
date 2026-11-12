#!/usr/bin/env python3
"""Tests for inkognito.py (developer side, not in the release zip). Never runs the real adb or mtkclient: PATH is
reduced to dev-tests/mockbin + system dirs and every device call goes to the mocks, which log each call.

  python3 -I dev-tests/test_inkognito.py unit   formats and guards on the real images (private inputs, read-only)
  python3 -I dev-tests/test_inkognito.py cli    --dry-run and argument scenarios: no mock may be called
  python3 -I dev-tests/test_inkognito.py sim    install -> update -> restore-stock on a simulated tablet
                                                (a sparse disk image filled from the stock dump; ~25 GB temp space)
Inputs (private, read-only): BIGME/BIGME_B1051C_system_dump (stock fw 4.9.0, slot b), the tested boot and logo images
(work/rom/out/images, the ones the kit was made from; TEST_TESTED_IMAGES overrides), a device backup (TEST_DEVICE_BACKUP, from tools/scripts/backup-device.sh), and a release folder (TEST_REL) made by
build-rom.py release.
"""
import contextlib, hashlib, io, os, re, shutil, struct, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.dirname(HERE)
BIGME = os.environ.get("BIGME") or os.path.abspath(os.path.join(SRC, "../../.."))   # work/rom/release-src -> repo root
DUMP = os.path.join(BIGME, "BIGME_B1051C_system_dump")
# boot.img / logo.img as tested on hardware (kit.json release_boot / release_logo). The alpha 2 release folder that
# held them was deleted on 2026-11-10; build-rom.py kit reads the same pair from work/rom/out/images.
ALPHA2 = os.environ.get("TEST_TESTED_IMAGES") or os.path.join(BIGME, "work/rom/out/images")
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


# ---- progress meter (needs no private input) ---------------------------------------------------------------------
# Lines copied from the installer logs of the install test and the restore-stock run on 2026-11-11 (mtkclient ends
# each with "\r"; the installer's text pipe delivers them as lines).
REAL = {
    "first_read": "Done |----------| 0.0% Read: (0x10000/0x300000000),0.00 MB/s\n",
    "read_start": "Progress: |----------| 0.0% Read: (0x20000/0x300000000), 11.83 MB/s 03m:43s left \n",
    "read_10": "Progress: |█---------| 10.0% Read: (0x4C6B0000/0x300000000), 23.09 MB/s 07m:13s left \n",
    "read_25": "Progress: |██--------| 25.0% Read: (0xBF9E0000/0x300000000), 25.12 MB/s 06m:00s left \n",
    "read_50": "Progress: |█████-----| 50.0% Read: (0x17F9E0000/0x300000000), 24.12 MB/s 04m:01s left \n",
    "read_999": "Progress: |██████████| 99.9% Read: (0x2FEDA0000/0x300000000), 28.8 MB/s  \n",
    "read_end": "Progress: |██████████| 100.0% Read: (0x300000000/0x300000000), 27.14 MB/s  \n",
    "read_noleft": "Progress: |████------| 38.0% Read: (0xF30000/0x2800000), 27.85 MB/s  \n",
    "read_secs": "Progress: |███-------| 30.0% Read: (0x2B800000/0x913D2000), 29.89 MB/s 59s left \n",
    "first_write": "Done |----------| 0.1% Write: (0x200000/0x913D2000),0.00 MB/s\n",
    "write_start": "Progress: |----------| 0.2% Write: (0x400000/0x913D2000), 14.06 MB/s 02m:42s left \n",
    "write_117": "Progress: |█---------| 11.7% Write: (0x11000000/0x913D2000), 7.41 MB/s 04m:32s left \n",
    "write_50": "Progress: |█████-----| 50.0% Write: (0x48A00000/0x913D2000), 7.82 MB/s 02m:34s left \n",
    "write_end": "Progress: |██████████| 100.0% Write: (0x913D2000/0x913D2000), 6.86 MB/s  \n",
    "small_write": "Progress: |█---------| 10.0% Write: (0x400000/0x2800000), 13.85 MB/s 02s left \n",
    "small_end": "Progress: |██████████| 100.0% Write: (0x2800000/0x2800000), 7.35 MB/s  \n",
    "restore_write": "Progress: |----------| 0.1% Write: (0x400000/0x12D780000), 14.01 MB/s 05m:38s left \n",
}
OTHER = ['DaHandler - Dumping partition "super"\n', "Main - Handling da commands ...\n", "\n",
         "DaHandler - Dumped sector 323584 with sector count 3145728 as /x/super.bin.\n",
         "Wrote /x/system.img to offset 0x72d00000 with length 0x913d2000.\n", "Progress report follows\n",
         "Preloader - \tMem write auth:\t\tFalse\n"]


class Clock:
    def __init__(self):
        self.t = 100.0

    def __call__(self):
        return self.t


def meter():
    pp = ik.parse_progress
    ok("progress: every real line parses", all(pp(l) for l in REAL.values()), [k for k, l in REAL.items() if not pp(l)])
    ok("progress: other mtkclient lines do not parse", not any(pp(l) for l in OTHER))
    r = pp(REAL["read_10"])
    ok("progress: read line fields", r == {"kind": "read", "pct": 10.0, "done": 0x4C6B0000, "total": 0x300000000,
                                           "speed": 23.09 * (1 << 20), "left": 7 * 60 + 13}, r)
    w = pp(REAL["write_117"])
    ok("progress: write line fields", (w["kind"], w["pct"], w["done"], w["total"], w["left"]) ==
       ("write", 11.7, 0x11000000, 0x913D2000, 4 * 60 + 32), w)
    ok("progress: no time left printed -> None", pp(REAL["read_noleft"])["left"] is None and pp(REAL["read_end"])["left"] is None)
    ok("progress: seconds only", pp(REAL["read_secs"])["left"] == 59)
    ok("progress: first line of a transfer ('Done', no space before the speed)",
       pp(REAL["first_write"])["done"] == 0x200000 and pp(REAL["first_write"])["speed"] == 0)
    h = pp("Progress: |----------| 0.1% Read: (0x400000/0x1a7aff8000), 512.0 KB/s 02h:05m:09s left \n")
    ok("progress: hours and KB/s (mtkclient's other formats)", h and h["left"] == 7509 and h["speed"] == 512 * 1024, h)
    ok("progress: 0B/s", pp("Progress: |----------| 0.1% Read: (0x400000/0x2800000), 0B/s  \n")["speed"] == 0)
    ok("sizes", (ik.fmt_size(0x11000000), ik.fmt_size(0x913D2000), ik.fmt_size(0x300000000)) == ("272 MB", "2.27 GB", "12.00 GB"),
       (ik.fmt_size(0x11000000), ik.fmt_size(0x913D2000), ik.fmt_size(0x300000000)))
    ok("time left", (ik.fmt_left(3), ik.fmt_left(42), ik.fmt_left(272), ik.fmt_left(3600 + 29 * 60)) ==
       ("~5 s left", "~40 s left", "~5 min left", "~1 h 29 min left"), ik.fmt_left(272))

    # terminal: the system write of the install test, replayed with a clock (7.4 MB/s as on the tablet)
    c, o = Clock(), io.StringIO()
    m = ik.Meter("system_b part 1", out=o, tty=True, clock=c, width=100)
    seq = [("first_write", 0), ("write_start", 0.3), ("write_117", 36.5), ("write_50", 155.0), ("write_end", 338.0)]
    for k, t in seq:
        c.t = 100.0 + t
        ok("meter tty: %s consumed" % k, m.feed(REAL[k]) is True)
    ok("meter tty: other lines are not consumed", not any(m.feed(l) for l in OTHER))
    m.close()
    v = o.getvalue()
    frames = [f.rstrip() for f in v.split("\r") if f.strip()]
    ok("meter tty: one self-updating line (\\r, a single newline at the end)", v.count("\n") == 1 and v.endswith("\n") and v.count("\r") >= 4, repr(v))
    ok("meter tty: live line = name, percent, done / total, speed, time left",
       "    writing system_b part 1: 11% (272 MB / 2.27 GB), 7.4 MB/s, ~5 min left" in frames, frames)
    ok("meter tty: half way", "    writing system_b part 1: 50% (1.13 GB / 2.27 GB), 7.5 MB/s, ~3 min left" in frames, frames)
    ok("meter tty: summary stays after a long transfer",
       frames[-1] == "    writing system_b part 1: 100% (2.27 GB) in 5 min 38 s, 6.9 MB/s", frames[-1])
    ok("meter tty: no mtkclient text on the screen", "Progress" not in v and "█" not in v and "Done |" not in v)
    raw = [f.rstrip("\n") for f in v.split("\r")[1:]]
    ok("meter tty: every redraw covers the line before it", all(len(b) >= len(a.rstrip()) for a, b in zip(raw, raw[1:])), raw)

    # terminal: a short transfer (boot, 40 MB, 5 s) shows its line and then clears it; tiny ones show nothing
    c, o = Clock(), io.StringIO()
    m = ik.Meter("boot_b", out=o, tty=True, clock=c, width=100)
    m.feed("Done |----------| 5.0% Write: (0x200000/0x2800000),0.00 MB/s\n"); c.t += 0.5
    m.feed(REAL["small_write"]); c.t += 5
    m.feed(REAL["small_end"]); m.close()
    v = o.getvalue()
    ok("meter tty: short transfer leaves no line behind", "writing boot_b: 10% (4 MB / 40 MB)" in v and "\n" not in v
       and v.endswith("\r") and v.rsplit("\r", 2)[-2].strip() == "", repr(v))
    o = io.StringIO()
    m = ik.Meter("vbmeta_b", out=o, tty=True, clock=Clock(), width=100)
    for l in ("Done |██--------| 25.0% Write: (0x200000/0x800000),0.00 MB/s\n",
              "Progress: |█████-----| 50.0% Write: (0x400000/0x800000), 13.71 MB/s  \n",
              "Progress: |██████████| 100.0% Write: (0x800000/0x800000), 7.57 MB/s  \n"):
        m.feed(l)
    m.close()
    ok("meter tty: an 8 MB transfer prints nothing", o.getvalue() == "", repr(o.getvalue()))

    # terminal: readback is called "checking"; a narrow terminal never wraps; an aborted transfer keeps its line
    c, o = Clock(), io.StringIO()
    m = ik.Meter("system_b part 1", verify=True, out=o, tty=True, clock=c, width=40)
    m.feed("Done |----------| 0.0% Read: (0x10000/0x913D2000),0.00 MB/s\n"); c.t += 20
    m.feed(REAL["read_secs"]); m.close()
    v = o.getvalue()
    ok("meter tty: readback = checking", "checking system_b part 1: 30%" in v, repr(v))
    ok("meter tty: cut to the terminal width", max(len(f) for f in v.replace("\n", "").split("\r")) <= 39, repr(v))
    ok("meter tty: a transfer that stops early keeps its last line", v.endswith("\n") and v.count("\n") == 1)

    # no terminal (pipe): the backup read of the install test; partition names come from mtkclient's own lines
    c, o = Clock(), io.StringIO()
    m = ik.Meter(None, out=o, tty=False, clock=c)
    m.feed('DaHandler - Dumping partition "boot_a"\n')
    for l in ("Done |----------| 0.2% Read: (0x10000/0x2800000),0.00 MB/s\n", REAL["read_noleft"],
              "Progress: |██████████| 100.0% Read: (0x2800000/0x2800000), 25.26 MB/s  \n"):
        m.feed(l)
    m.feed('DaHandler - Dumping partition "boot_b"\n')        # same size as boot_a: must count as a new transfer
    for l in ("Done |----------| 0.2% Read: (0x10000/0x2800000),0.00 MB/s\n",
              "Progress: |██████████| 100.0% Read: (0x2800000/0x2800000), 28.09 MB/s  \n"):
        m.feed(l)
    ok("meter pipe: short transfers print nothing", o.getvalue() == "", repr(o.getvalue()))
    m.feed(OTHER[0])
    total = 0x300000000
    m.feed(REAL["first_read"])
    for i in range(1, 2000):                              # 0.05 % steps like the real run, 27 MB/s
        c.t += 0.2275
        done = total * i // 2000
        m.feed("Progress: |----------| %.1f%% Read: (0x%X/0x%X), 27.0 MB/s 01m:00s left \n" % (100.0 * done / total, done, total))
    c.t += 0.2275
    m.feed("Progress: |██████████| 100.0% Read: (0x2FFFF0000/0x300000000), 27.0 MB/s  \n")   # rounds to 100.0 before the end
    m.feed(REAL["read_end"]); m.close()
    lines = o.getvalue().splitlines()
    ok("meter pipe: a line every 10 % and a summary (10 lines), no \\r", len(lines) == 10 and "\r" not in o.getvalue(), lines)
    ok("meter pipe: first line", lines[0] == "    reading super: 10% (1.20 GB / 12.00 GB), 27.0 MB/s, ~7 min left", lines[0])
    ok("meter pipe: 90 %", lines[8].startswith("    reading super: 90% (10.80 GB / 12.00 GB), 27.0 MB/s, ~"), lines[8])
    ok("meter pipe: summary", lines[9] == "    reading super: 100% (12.00 GB) in 7 min 35 s, 27.0 MB/s", lines[9])

    # Mtk._run with a stand-in for mtkclient that prints the real lines the way mtkclient does (overwriting with \r):
    # the log keeps every line, the screen gets the meter, the returned text and error tail carry no progress lines
    fake = os.path.join(WORK, "fake-mtk.py")
    body = [OTHER[1], OTHER[0], REAL["first_read"], REAL["read_start"], REAL["read_10"], REAL["read_25"], REAL["read_50"],
            REAL["read_999"], REAL["read_end"], OTHER[3]]
    open(fake, "w", encoding="utf-8").write(
        "import sys\nsys.stdout.reconfigure(encoding='utf-8')\nfor l in %r:\n"
        "    if l.startswith(('Progress', 'Done |')):\n        sys.stdout.write('\\r' + l.rstrip('\\n'))\n"
        "        if '100.0%%' in l: sys.stdout.write('\\n')\n    else:\n        sys.stdout.write(l)\n"
        "    sys.stdout.flush()\nsys.exit(int(sys.argv[-1]) if sys.argv[-1].isdigit() else 0)\n" % (body,))
    for rc in (0, 3):
        log = os.path.join(WORK, "run-%d.log" % rc)
        open(log, "w").close()
        mt = ik.Mtk([sys.executable, "-I", fake], os.path.join(WORK, "run-session-%d" % rc), False, log)
        mt.connected = True
        scr, text, err = io.StringIO(), None, None
        with contextlib.redirect_stdout(scr):
            try:
                text = mt._run(["r", "super", "x.bin", str(rc)])
            except ik.Stop as e:
                err = str(e)
        logged = open(log, encoding="utf-8").read()
        ok("run (exit %d): the log has every mtkclient line, progress included" % rc,
           all(l.strip() in logged for l in body) and logged.count("Progress: |") == 6 and logged.startswith("+ mtk r super x.bin"), logged[:300])
        ok("run (exit %d): the screen shows the meter, not mtkclient's lines" % rc,
           "Progress" not in scr.getvalue() and "    reading super: 10% (1.19 GB / 12.00 GB)" in scr.getvalue(), scr.getvalue())
        if rc == 0:
            ok("run: returned text has the other lines only", text is not None and "Progress" not in text and "Dumping partition" in text, text)
        else:
            ok("run: a failure reports mtkclient's last real lines, not progress lines",
               err and "exit 3" in err and "Dumped sector" in err and "Progress" not in err, err)


# ---- unit ---------------------------------------------------------------------------------------------------------
def unit():
    meter()
    stock, tested = rd(os.path.join(DUMP, "boot_b.bin")), rd(os.path.join(ALPHA2, "boot.img"))
    info = ik.kit_info(KIT)
    ok("classify stock", ik.classify_boot(stock, KIT) == "stock")
    ok("classify tested InKognito boot", ik.classify_boot(tested, KIT) == "inkognito")
    ok("classify magisk-only (WRITE 4 image)",
       ik.classify_boot(rd(os.path.join(BIGME, "work/sdcard/boot_b_backup_magisk_2026-10-18.bin")), KIT) == "magisk")
    b = ik.build_boot(stock, KIT)
    q, t = ik.boot_parse(b), ik.boot_parse(tested)
    ok("boot from stock: ramdisk == tested", ik.gunzip(q["ramdisk"]) == ik.gunzip(t["ramdisk"]))
    ok("boot from stock: kernel/dtb == tested", q["kernel"] == t["kernel"] and q["dtb"] == t["dtb"])
    ok("boot from stock: AVB vbmeta blob == tested", q["avb"]["vbmeta"] == t["avb"]["vbmeta"])
    ok("boot from stock: 40 MiB", len(b) == 0x2800000)
    ok("boot from the tested InKognito boot (update) == boot from stock", ik.gunzip(ik.boot_parse(ik.build_boot(tested, KIT))["ramdisk"])
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
    ok("cli: install ends with what to do next (USB debugging prompt, one restart after setup, USB debugging off)",
       all(t in out for t in ('"Allow USB debugging?"', "restart the tablet ONCE", "turn USB debugging off", "hold the power button for 3 to 5 seconds")), out[-900:])
    rc_u, out_u = cli_run(["update", "--dry-run", "--variant", "gapps"])
    ok("cli: update does not ask for the extra restart", rc_u == 0 and "restart the tablet ONCE" not in out_u and "Your apps and settings stay" in out_u, out_u[-500:])
    ok("cli: the banner names this release", ik.RELEASE_NAME in out and re.match(r"InKognito-B1051CPro-(alpha|beta|rc)\d+-\d{8}$", ik.RELEASE_NAME), out[:200])
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
    # progress meter, output piped (no terminal): a line every 10 % for the long transfers, mtkclient's own lines only in the log
    ilog = open(os.path.join(WORK, "w-install", "inkognito.log"), encoding="utf-8").read()
    ok("sim: backup progress on the screen, by partition", "    reading super: 10% (1.20 GB / 12.00 GB)" in out and "    reading super: 100% (12.00 GB) in " in out, out[:1500])
    ok("sim: system write and readback progress", "    writing system_b part 1: 50% (" in out and "    checking system_b part 1: 50% (" in out, out[-2500:])
    ok("sim: short transfers print no progress", "boot_b: 10%" not in out and "userdata head: 10%" not in out)
    ok("sim: mtkclient's progress lines stay in the log, none on the screen",
       "Progress: |" not in out and "Done |" not in out and ilog.count("Progress: |") >= 20 * 20 and "Done |" in ilog, ilog[:400])

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
