#!/usr/bin/env python3
"""InKognito ROM builder (private). Two steps, both reproducible from the same inputs:

  python3 -I tools/scripts/build-rom.py payload work/rom/payload.json work/rom/out/payload
      Stage /system/etc/inkognito from the payload spec: every entry is copied and its SHA-256 checked against the
      spec (the live, tested artifact); "tar" entries pack a directory (KOReader skeleton) into one file.
  python3 -I tools/scripts/build-rom.py image work/rom/out/payload work/rom/out
      TrebleDroid GSI (sha-checked) -> strip the AVB footer -> grow -> unshare blocks (the GSI is a deduplicated
      read-only ext4) -> debugfs: add /system/etc/inkognito + /system/etc/init/inkognito.rc, mode/uid/gid and
      security.selinux = system_file -> e2fsck must be clean -> shrink to the minimum -> read every added file back
      and compare its SHA-256 and label -> work/rom/out/system.img + build-info.json.
  python3 -I tools/scripts/build-rom.py kit <rom-boot.img> <rom-logo.img> <stock-dir> work/rom/out/kit
      The installer kit (alpha 3 on): only InKognito's own boot ramdisk files (Magisk 30.7 + charger rc) and logo
      images, taken from the tested images, plus the stock and expected hashes in kit.json. inkognito.py builds
      boot/vbmeta/logo from the user's own backup with them; the kit is checked by rebuilding the tested images
      from the stock dump (<stock-dir>/boot_b.bin, vbmeta_b.bin, logo.bin).
  python3 -I tools/scripts/build-rom.py image --no-gapps work/rom/out/payload work/rom/out/<tag>-nogapps
      Same image without the gapps module (AOSP Provision stays): payload entries with "gapps": true are left out.
  python3 -I tools/scripts/build-rom.py release <RELEASE_NAME>
      release/<RELEASE_NAME>/ + .zip + .zip.sha256 (+ -gapps.zip / -nogapps.zip, one image each, each under
      GitHub's 2 GiB limit) from work/rom/release-src (installer, front ends, docs, credits),
      work/rom/out/kit, work/rom/out/<tag>/system.img and (if built) work/rom/out/<tag>-nogapps/system.img as
      system-nogapps.img (tag = alpha3 etc. from the name; build them with "image ... work/rom/out/<tag>").
      Refuses when RELEASE_NAME differs from inkognito.py / the docs, or when the dev-tests fail.
"""
import hashlib, json, os, re, shutil, struct, subprocess, sys, tarfile, tempfile

BIGME = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
E2 = "/opt/homebrew/opt/e2fsprogs/sbin"
GSI = os.path.join(BIGME, "work/gsi/system-td-arm64-vanilla.img")
GSI_SHA = "a0704a7909328cc077f314f25ed47c5ce02b4a095cc932dd36060e4a90d333a8"
RC_SRC = os.path.join(BIGME, "work/rom/payload-src/inkognito.rc")
PAYLOAD_DIR_IN_FS = "/system/etc/inkognito"
LABEL = b"u:object_r:system_file:s0\x00"
# Owner decisions applied to the GSI's own files (path, [(old, new, expected count)]). Mode, owner and label are kept.
SYSTEM_EDITS = [
    # 2026-10-21: USB debugging stays on, but a computer must be allowed on the tablet first (RSA prompt)
    ("/system/build.prop", [(b"ro.adb.secure=0\n", b"ro.adb.secure=1\n", 2)]),
    ("/system/system_ext/etc/build.prop", [(b"ro.adb.secure=0\n", b"ro.adb.secure=1\n", 1)]),
    # 2026-11-11 (alpha 7): the device names itself. ro.product.model is derived from the product partition's model
    # (USB, Settings > About > Model, Google's device list; device_name and the Bluetooth name follow it on a first
    # boot). NEVER edit ro.build.fingerprint (gate of the eink-compat and launcher3 modules, Google Play) or
    # ro.product.device / name / brand / manufacturer.
    ("/system/product/etc/build.prop", [(b"ro.product.product.model=TrebleDroid vanilla\n",
                                         b"ro.product.product.model=" + b"InKognito B1051C Pro" + b"\n", 1)]),
]
GSI_DISPLAY_ID = b"ro.build.display.id=treble_arm64_bvS-userdebug 16 BP2A.250605.031.A3 250617 test-keys\n"


def system_edits(version):
    """SYSTEM_EDITS plus "Build number" (ro.build.display.id) made from the payload version:
    InKognito-B1051CPro-alpha7-20261111 -> "InKognito alpha 7 (TrebleDroid 16, BP2A.250605.031.A3)"."""
    m = re.fullmatch(r"InKognito-B1051CPro-(alpha|beta|rc)(\d+)-\d{8}", version)
    if not m:
        die(f"payload version {version!r}: cannot make a build number from it")
    disp = f"ro.build.display.id=InKognito {m[1]} {m[2]} (TrebleDroid 16, BP2A.250605.031.A3)\n".encode()
    edits = [(p, list(r)) for p, r in SYSTEM_EDITS]
    edits[0][1].append((GSI_DISPLAY_ID, disp, 1))
    assert edits[0][0] == "/system/build.prop"
    return edits


NO_GAPPS = [False]          # build-rom.py image --no-gapps


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def die(msg):
    sys.exit("build-rom: " + msg)


def run(*cmd, ok=(0,)):
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode not in ok:
        die(f"{' '.join(cmd)} -> {p.returncode}\n{p.stdout}\n{p.stderr}")
    return p.stdout + p.stderr


# ---- payload ---------------------------------------------------------------------------------------------------
def stage_payload(spec_path, out):
    spec = json.load(open(spec_path))
    if os.path.exists(out):
        die(f"{out} exists; remove it first (staging is never incremental)")
    os.makedirs(out)
    manifest = []
    for e in spec["entries"]:
        dest = os.path.join(out, e["dest"])
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        src = os.path.join(BIGME, e["src"]) if not os.path.isabs(e["src"]) else e["src"]
        if e.get("tar"):
            # deterministic tar: sorted names, root-owned, fixed mtime; excluded names never enter the archive
            excl = set(e.get("exclude", []))
            with tarfile.open(dest, "w", format=tarfile.USTAR_FORMAT) as t:
                for root, dirs, files in os.walk(src):
                    dirs[:] = sorted(d for d in dirs if d not in excl)
                    for n in sorted(files):
                        if n in excl or n == ".DS_Store":
                            continue
                        p = os.path.join(root, n)
                        ti = t.gettarinfo(p, arcname=os.path.join(e.get("arcroot", ""), os.path.relpath(p, src)))
                        ti.uid = ti.gid = 0; ti.uname = ti.gname = ""; ti.mtime = 1792540800
                        ti.mode = 0o644
                        with open(p, "rb") as f:
                            t.addfile(ti, f)
        else:
            got = sha256(src)
            want = e.get("sha256")
            if want and not got.startswith(want):
                die(f"{e['src']}: sha256 {got[:16]} != spec {want}")
            shutil.copyfile(src, dest)
        manifest.append(f"{sha256(dest)}  {e['dest']}")
    open(os.path.join(out, "VERSION"), "w").write(spec["version"] + "\n")
    open(os.path.join(out, "MANIFEST.sha256"), "w").write("\n".join(sorted(manifest)) + "\n")
    print(f"staged {len(manifest)} entries -> {out}")


# ---- image -----------------------------------------------------------------------------------------------------
def walk(payload):
    dirs, files = [], []
    for root, ds, fs in os.walk(payload):
        ds.sort()
        rel = os.path.relpath(root, payload)
        for d in ds:
            dirs.append(os.path.normpath(os.path.join(rel, d)))
        for f in sorted(fs):
            if f == ".DS_Store":
                continue
            files.append(os.path.normpath(os.path.join(rel, f)))
    return dirs, files


def debugfs(img, script, write=True):
    with tempfile.NamedTemporaryFile("w", suffix=".debugfs", delete=False) as f:
        f.write(script)
    args = [f"{E2}/debugfs"] + (["-w"] if write else []) + ["-f", f.name, img]
    out = run(*args)
    os.unlink(f.name)
    bad = [l for l in out.splitlines() if l and not l.startswith("debugfs ") and
           any(w in l.lower() for w in ("error", "not found", "exists", "invalid", "couldn't", "cannot", "failed"))]
    if bad:
        die("debugfs reported:\n" + "\n".join(bad[:20]))
    return out


def build_image(payload, out):
    if not os.path.isdir(payload):
        die(f"no payload dir {payload}")
    os.makedirs(out, exist_ok=True)
    if sha256(GSI) != GSI_SHA:
        die("GSI sha256 mismatch")
    with open(GSI, "rb") as f:
        f.seek(-64, 2)
        foot = f.read(64)
    if foot[:4] != b"AVBf":
        die("GSI has no AVB footer")
    fs_size = struct.unpack(">Q", foot[12:20])[0]
    img = os.path.join(out, "system.img.tmp")
    with open(GSI, "rb") as s, open(img, "wb") as d:
        left = fs_size
        while left:
            b = s.read(min(left, 1 << 22)); d.write(b); left -= len(b)

    dirs, files = walk(payload)
    if NO_GAPPS[0]:
        # MindTheGapps + Google Setup Wizard live in one module; without it AOSP Provision runs the first setup
        drop = [f for f in files if f.startswith("modules/") and "gapps" in f]
        if len(drop) != 1:
            die(f"--no-gapps: expected exactly one gapps module in the payload, found {drop}")
        files = [f for f in files if f not in drop]
        manifest = os.path.join(payload, "MANIFEST.sha256")
        nog = os.path.join(out, "payload-MANIFEST.sha256")
        open(nog, "w").write("".join(l for l in open(manifest) if drop[0] not in l))
        print(f"--no-gapps: left out {drop[0]}")
    need = sum((os.path.getsize(os.path.join(payload, f)) + 4095) // 4096 for f in files) + len(dirs) + 4096
    # grow generously (unsharing the GSI's deduplicated blocks needs ~13k blocks), write, then shrink to the minimum
    blocks = fs_size // 4096 + need + 65536
    os.truncate(img, blocks * 4096)
    run(f"{E2}/resize2fs", img, str(blocks))
    run(f"{E2}/e2fsck", "-fy", "-E", "unshare_blocks", img, ok=(0, 1))
    feats = run(f"{E2}/dumpe2fs", "-h", img)
    if "shared_blocks" in feats:
        die("shared_blocks still set after unshare")

    ctx = os.path.join(out, "selinux-system_file.bin")
    open(ctx, "wb").write(LABEL)
    s = [f"cd /system/etc", "mkdir inkognito"]
    for d in dirs:
        s.append(f"mkdir {PAYLOAD_DIR_IN_FS}/{d}")
    for d in [""] + dirs:
        p = f"{PAYLOAD_DIR_IN_FS}/{d}".rstrip("/")
        s += [f"sif {p} mode 040755", f"sif {p} uid 0", f"sif {p} gid 0", f"ea_set -f {ctx} {p} security.selinux"]
    for f in files:
        dname, base = os.path.split(f)
        p = f"{PAYLOAD_DIR_IN_FS}/{f}"
        src = os.path.join(out, "payload-MANIFEST.sha256") if NO_GAPPS[0] and f == "MANIFEST.sha256" else os.path.join(payload, f)
        s += [f"cd {PAYLOAD_DIR_IN_FS}/{dname}".rstrip("/"), f"write {src} {base}",
              f"sif {p} mode {'0100755' if f.endswith('.sh') else '0100644'}", f"sif {p} uid 0", f"sif {p} gid 0",
              f"ea_set -f {ctx} {p} security.selinux"]
    s += ["cd /system/etc/init", f"write {RC_SRC} inkognito.rc", "sif /system/etc/init/inkognito.rc mode 0100644",
          "sif /system/etc/init/inkognito.rc uid 0", "sif /system/etc/init/inkognito.rc gid 0",
          f"ea_set -f {ctx} /system/etc/init/inkognito.rc security.selinux"]
    debugfs(img, "\n".join(s) + "\n")

    edited = []
    tmpd = tempfile.mkdtemp()
    for i, (fsp, reps) in enumerate(system_edits(open(os.path.join(payload, "VERSION")).read().strip())):
        orig = os.path.join(tmpd, f"orig{i}")
        debugfs(img, f"dump {fsp} {orig}\n", write=False)
        data = open(orig, "rb").read()
        for old, new, n in reps:
            if data.count(old) != n:
                die(f"{fsp}: expected {n}x {old!r}, found {data.count(old)}")
            data = data.replace(old, new)
        mod = os.path.join(tmpd, f"mod{i}")
        open(mod, "wb").write(data)
        st = run(f"{E2}/debugfs", "-R", f"stat {fsp}", img)
        mode = [l for l in st.splitlines() if "Mode:" in l][0].split("Mode:")[1].split()[0]
        lab = run(f"{E2}/debugfs", "-R", f"ea_get {fsp} security.selinux", img)
        if "u:object_r:system_file:s0" not in lab:
            die(f"{fsp}: unexpected label {lab.strip()}")
        dname, base = os.path.split(fsp)
        debugfs(img, "\n".join([f"rm {fsp}", f"cd {dname}", f"write {mod} {base}", f"sif {fsp} mode 0100{mode[-3:]}",
                                 f"sif {fsp} uid 0", f"sif {fsp} gid 0", f"ea_set -f {ctx} {fsp} security.selinux"]) + "\n")
        edited.append((fsp, mod))

    run(f"{E2}/e2fsck", "-fy", img, ok=(0, 1))          # 1 = counts tidied after debugfs
    run(f"{E2}/e2fsck", "-fn", img)                     # second pass must be clean
    run(f"{E2}/resize2fs", "-M", img)
    run(f"{E2}/e2fsck", "-fn", img)
    bc = int([l for l in run(f"{E2}/dumpe2fs", "-h", img).splitlines() if l.startswith("Block count:")][0].split()[-1])
    os.truncate(img, bc * 4096)

    # read back every added file + its label
    chk = tempfile.mkdtemp()
    verify = [(f"{PAYLOAD_DIR_IN_FS}/{f}", os.path.join(out, "payload-MANIFEST.sha256") if NO_GAPPS[0] and f == "MANIFEST.sha256"
               else os.path.join(payload, f)) for f in files] + \
             [("/system/etc/init/inkognito.rc", RC_SRC)] + edited
    for i, (fsp, host) in enumerate(verify):
        o = os.path.join(chk, str(i))
        debugfs(img, f"dump {fsp} {o}\n", write=False)
        if sha256(o) != sha256(host):
            die(f"readback mismatch: {fsp}")
        lab = run(f"{E2}/debugfs", "-R", f"ea_get {fsp} security.selinux", img)
        if "u:object_r:system_file:s0" not in lab:
            die(f"label missing on {fsp}: {lab.strip()}")
    shutil.rmtree(chk)
    shutil.rmtree(tmpd)
    final = os.path.join(out, "system.img")
    os.replace(img, final)
    os.unlink(ctx)
    info = {"base": {"file": "system-td-arm64-vanilla.img", "sha256": GSI_SHA, "fs_size": fs_size},
            "system.img": {"sha256": sha256(final), "size": os.path.getsize(final), "blocks": bc},
            "added_files": len(verify) - len(edited), "edited_files": [f for f, _ in edited], "payload_version": open(os.path.join(payload, "VERSION")).read().strip(),
            "variant": "nogapps" if NO_GAPPS[0] else "gapps"}
    json.dump(info, open(os.path.join(out, "build-info.json"), "w"), indent=1)
    print(json.dumps(info, indent=1))


# ---- installer kit ---------------------------------------------------------------------------------------------
def make_kit(rom_boot, rom_logo, stock_dir, out):
    sys.path.insert(0, os.path.join(BIGME, "work/rom/release-src"))
    import inkognito as ik
    if os.path.exists(out):
        die(f"{out} exists; remove it first")
    os.makedirs(os.path.join(out, "boot")); os.makedirs(os.path.join(out, "logo"))
    rb, sb = open(rom_boot, "rb").read(), open(os.path.join(stock_dir, "boot_b.bin"), "rb").read()
    sv, sl, rl = (open(p, "rb").read() for p in (os.path.join(stock_dir, "vbmeta_b.bin"), os.path.join(stock_dir, "logo.bin"), rom_logo))
    rp, sp = ik.boot_parse(rb), ik.boot_parse(sb)
    rcpio, scpio = ik.gunzip(rp["ramdisk"]), ik.gunzip(sp["ramdisk"])
    ents = ik.cpio_parse(rcpio)
    files = {"magiskinit": ents["init"]["data"]}
    for name, mode, src in ik.MAGISK_ADDED + ik.INKOGNITO_ADDED:
        if src:
            if ents[name]["mode"] != mode:
                die(f"{name}: mode {oct(ents[name]['mode'])} in the tested image, kit says {oct(mode)}")
            files[src] = ents[name]["data"]
    for n, d in files.items():
        open(os.path.join(out, "boot", n), "wb").write(d)
    cfg = dict(l.split("=", 1) for l in ents[".backup/.magisk"]["data"].decode().split())
    if cfg["SHA1"] != hashlib.sha1(sb).hexdigest():
        die("the tested boot's Magisk config does not name the stock image's SHA1")
    vb = bytearray(sv); vb[123] = 1
    lr, ls = ik.logo_parse(rl), ik.logo_parse(sl)
    imgs = [i for i, (a, b) in enumerate(zip(ls["streams"], lr["streams"])) if a != b]
    for i in imgs:
        open(os.path.join(out, "logo", "logo-%02d.zlib" % i), "wb").write(lr["streams"][i])
    info = {"release_boot": sha256(rom_boot), "release_logo": sha256(rom_logo),
            "stock": {"kernel": ik.sha256(sp["kernel"]), "dtb_uncapped": ik.sha256(ik.sd_uncap(sp["dtb"])),
                      "ramdisk_cpio": ik.sha256(ik.cpio_canon(scpio)), "boot": ik.sha256(sb), "vbmeta": ik.sha256(sv),
                      "logo": ik.sha256(sl), "sha1": cfg["SHA1"]},
            "out": {"ramdisk_cpio": ik.sha256(rcpio), "dtb": ik.sha256(rp["dtb"]), "vbmeta": ik.sha256(bytes(vb)),
                    "logo": ik.sha256(rl)},
            "logo_images": imgs,
            "files": {n: ik.sha256(d) for n, d in sorted(files.items())}}
    json.dump(info, open(os.path.join(out, "kit.json"), "w"), indent=1)
    # self-test: the stock dump must give back exactly what was tested
    nb = ik.build_boot(sb, out)
    q = ik.boot_parse(nb)
    if q["kernel"] != rp["kernel"] or q["dtb"] != rp["dtb"] or ik.gunzip(q["ramdisk"]) != rcpio:
        die("kit self-test: rebuilt boot differs from the tested boot image")
    if q["avb"]["vbmeta"] != rp["avb"]["vbmeta"] or q["header"][:16] != rp["header"][:16] or q["header"][20:576] != rp["header"][20:576]:
        die("kit self-test: rebuilt boot header/AVB differ from the tested boot image")
    if ik.build_logo(sl, out) != rl:
        die("kit self-test: rebuilt logo differs")
    if ik.build_vbmeta(sv, out) != bytes(vb) or ik.classify_boot(rb, out) != "inkognito" or ik.classify_boot(sb, out) != "stock":
        die("kit self-test: vbmeta / classify")
    nb2 = ik.build_boot(rb, out)             # update path: from an InKognito boot
    if ik.gunzip(ik.boot_parse(nb2)["ramdisk"]) != rcpio:
        die("kit self-test: boot rebuilt from the InKognito boot differs")
    print(json.dumps(info, indent=1))
    print(f"kit OK: boot ramdisk from stock == tested ({info['out']['ramdisk_cpio'][:16]}), logo == tested, vbmeta flag OK")


# ---- release folder + zip -----------------------------------------------------------------------------------------
def make_release(name):
    src, outd = os.path.join(BIGME, "work/rom/release-src"), os.path.join(BIGME, "work/rom/out")
    dst = os.path.join(BIGME, "release", name)
    if os.path.exists(dst) or os.path.exists(dst + ".zip"):
        die(f"{dst} or its zip exists; remove it first")
    for f in ("inkognito.py", "README.md", "INSTALL.md", "KNOWN-ISSUES.md", "CHANGELOG.md"):
        if name not in open(os.path.join(src, f)).read():
            die(f"{f} does not name {name}")
    files = {"inkognito.py": "inkognito.py", "flash-inkognito.sh": "flash-inkognito.sh", "flash-inkognito.bat": "flash-inkognito.bat",
             "README.md": "README.md", "INSTALL.md": "INSTALL.md", "KNOWN-ISSUES.md": "KNOWN-ISSUES.md",
             "CHANGELOG.md": "CHANGELOG.md", "CREDITS.md": "credits/CREDITS.md", "LICENSES.md": "credits/LICENSES.md"}
    os.makedirs(dst)
    for d, s_ in files.items():
        shutil.copy2(os.path.join(src, s_), os.path.join(dst, d))
    os.chmod(os.path.join(dst, "flash-inkognito.sh"), 0o755)
    shutil.copytree(os.path.join(src, "credits/licenses"), os.path.join(dst, "licenses"))
    shutil.copytree(os.path.join(outd, "kit"), os.path.join(dst, "kit"))
    tag = name.split("-")[2]                                  # e.g. alpha3
    imgs = [("system.img", os.path.join(outd, tag, "system.img"))]
    if os.path.isfile(os.path.join(outd, tag + "-nogapps/system.img")):
        imgs.append(("system-nogapps.img", os.path.join(outd, tag + "-nogapps/system.img")))
    for _, s_ in imgs:
        bi = json.load(open(os.path.join(os.path.dirname(s_), "build-info.json")))
        if bi["payload_version"] != name or bi["system.img"]["sha256"] != sha256(s_):
            die(f"{s_}: build-info says {bi['payload_version']} / {bi['system.img']['sha256'][:16]}")
    for d, s_ in imgs:
        os.link(s_, os.path.join(dst, d)) if os.stat(s_).st_dev == os.stat(dst).st_dev else shutil.copy2(s_, os.path.join(dst, d))
    check = [d for d, _ in imgs] + sorted(os.path.relpath(os.path.join(r, f), dst) for r, _, fs in os.walk(os.path.join(dst, "kit")) for f in fs)
    open(os.path.join(dst, "SHA256SUMS"), "w").write("".join(f"{sha256(os.path.join(dst, f))}  {f}\n" for f in check))
    t = subprocess.run([sys.executable, "-I", os.path.join(src, "dev-tests/test_inkognito.py"), "unit", "cli"],
                       env=dict(os.environ, TEST_REL=dst, PYTHONDONTWRITEBYTECODE="1"), capture_output=True, text=True)
    print(t.stdout[-300:])
    if t.returncode:
        die("dev-tests failed on the assembled release:\n" + t.stdout[-3000:])
    stray = [os.path.join(r, d) for r, ds, _ in os.walk(dst) for d in ds if d == "__pycache__"]
    if stray:
        die(f"stray files in the release: {stray}")
    z = dst + ".zip"
    subprocess.run(["/usr/bin/zip", "-r", "-X", "-q", os.path.basename(z), name], cwd=os.path.dirname(dst), check=True)
    open(z + ".sha256", "w").write(f"{sha256(z)}  {os.path.basename(z)}\n")
    print(f"release: {dst}\nzip: {z} ({os.path.getsize(z)} B, sha256 {sha256(z)[:16]})")
    # One download per variant (2026-11-10): GitHub refuses release files over 2 GiB, and both images together are
    # bigger. inkognito.py works with one image in the folder (it checks only the chosen image + the kit).
    if len(imgs) == 2:
        for var, drop in (("gapps", "system-nogapps.img"), ("nogapps", "system.img")):
            vz = f"{dst}-{var}.zip"
            if os.path.exists(vz):
                die(f"{vz} exists; remove it first")
            subprocess.run(["/usr/bin/zip", "-r", "-X", "-q", os.path.basename(vz), name, "-x", f"{name}/{drop}"],
                           cwd=os.path.dirname(dst), check=True)
            if os.path.getsize(vz) >= 2 ** 31:
                die(f"{vz} is {os.path.getsize(vz)} B: over GitHub's 2 GiB limit for a release file")
            open(vz + ".sha256", "w").write(f"{sha256(vz)}  {os.path.basename(vz)}\n")
            print(f"zip ({var}): {vz} ({os.path.getsize(vz)} B, sha256 {sha256(vz)[:16]})")


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "release":
        make_release(sys.argv[2]); sys.exit(0)
    if len(sys.argv) == 6 and sys.argv[1] == "kit":
        make_kit(*sys.argv[2:6]); sys.exit(0)
    if len(sys.argv) == 5 and sys.argv[1:3] == ["image", "--no-gapps"]:
        NO_GAPPS[0] = True
        build_image(sys.argv[3], sys.argv[4]); sys.exit(0)
    if len(sys.argv) != 4 or sys.argv[1] not in ("payload", "image"):
        sys.exit(__doc__)
    (stage_payload if sys.argv[1] == "payload" else build_image)(sys.argv[2], sys.argv[3])
