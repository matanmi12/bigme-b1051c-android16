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
"""
import hashlib, json, os, shutil, struct, subprocess, sys, tarfile, tempfile

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
]


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
        s += [f"cd {PAYLOAD_DIR_IN_FS}/{dname}".rstrip("/"), f"write {os.path.join(payload, f)} {base}",
              f"sif {p} mode {'0100755' if f.endswith('.sh') else '0100644'}", f"sif {p} uid 0", f"sif {p} gid 0",
              f"ea_set -f {ctx} {p} security.selinux"]
    s += ["cd /system/etc/init", f"write {RC_SRC} inkognito.rc", "sif /system/etc/init/inkognito.rc mode 0100644",
          "sif /system/etc/init/inkognito.rc uid 0", "sif /system/etc/init/inkognito.rc gid 0",
          f"ea_set -f {ctx} /system/etc/init/inkognito.rc security.selinux"]
    debugfs(img, "\n".join(s) + "\n")

    edited = []
    tmpd = tempfile.mkdtemp()
    for i, (fsp, reps) in enumerate(SYSTEM_EDITS):
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
    verify = [(f"{PAYLOAD_DIR_IN_FS}/{f}", os.path.join(payload, f)) for f in files] + \
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
            "added_files": len(verify) - len(edited), "edited_files": [f for f, _ in edited], "payload_version": open(os.path.join(payload, "VERSION")).read().strip()}
    json.dump(info, open(os.path.join(out, "build-info.json"), "w"), indent=1)
    print(json.dumps(info, indent=1))


if __name__ == "__main__":
    if len(sys.argv) != 4 or sys.argv[1] not in ("payload", "image"):
        sys.exit(__doc__)
    (stage_payload if sys.argv[1] == "payload" else build_image)(sys.argv[2], sys.argv[3])
