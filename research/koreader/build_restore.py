#!/usr/bin/env python3
"""Build a KOReader restore for the A16 GSI from the stock backup (settings-backup-2026-10-17/koreader-*.tar).

Books moved from /storage/emulated/0/{Books,Download} to the SD card (/storage/EEDD-A114/Books) and got new names
(calibre transliteration, then Hebrew names: rename-plan.json), so everything that names a book is remapped:
  * books are matched by KOReader's partial MD5 (sd-pmd5.tsv, computed on the device), or by title where the SD copy
    is a different file of the same book (TITLE_MATCH; their statistics rows get the new MD5);
  * sidecars (document_metadata_folder = "doc") go next to the new files, doc_path / partial_md5 updated;
  * history.lua keeps only books that are on the SD card;
  * every other path in settings files: exact book paths first, then the Books folder prefix;
  * bookinfo_cache (cover browser) = the device's current cache with renamed files remapped.

Usage: build_restore.py BACKUP_KOREADER BACKUP_SIDECARS CURRENT_KOREADER SD_PMD5_TSV RENAME_PLAN OUT
OUT/koreader -> /storage/emulated/0/koreader, OUT/sidecars/<name>.sdr -> /storage/EEDD-A114/Books/<name>.sdr
"""
import json, os, re, shutil, sqlite3, sys

BAK, BAK_SIDE, CUR, PMD5, PLAN, OUT = sys.argv[1:7]
OLD_BOOKS, NEW_BOOKS = "/storage/emulated/0/Books", "/storage/EEDD-A114/Books"
# stock sidecar -> substring of the SD file name holding the same book as a different file (checked by title/pages)
TITLE_MATCH = {"DontMessWithADragon-fixed.sdr": "<book>",
               "D&D vs Rick and Morty - l ydv`.sdr": "Dungeons & Dragons vs Rick and Morty"}
SKIP_TOP = {"ota", "cache"}          # taken from the current install


def lua_str(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n") + '"'


sd = {}
for line in open(PMD5, encoding="utf-8"):
    h, p = line.rstrip("\n").split("\t", 1)
    sd[h] = p
renamed = {os.path.basename(a): os.path.basename(b) for a, b in json.load(open(PLAN, encoding="utf-8"))}

# ---- match sidecars --------------------------------------------------------------------------------
mapping, sidecars, md5_fix, report = {}, [], {}, []
for d in sorted(os.listdir(BAK_SIDE)):
    luas = [f for f in os.listdir(os.path.join(BAK_SIDE, d)) if f.startswith("metadata.") and f.endswith(".lua")]
    main = [f for f in luas if not f.endswith(".old")]
    if not main:
        continue
    t = open(os.path.join(BAK_SIDE, d, main[0]), encoding="utf-8").read()
    doc = re.search(r'\["doc_path"\] = "((?:[^"\\]|\\.)*)"', t).group(1)
    md5 = re.search(r'\["partial_md5_checksum"\] = "([0-9a-f]+)"', t).group(1)
    new, how = sd.get(md5), "md5"
    if not new and d in TITLE_MATCH:
        hits = [p for p in sd.values() if TITLE_MATCH[d] in os.path.basename(p)]
        if len(hits) == 1:
            new, how = hits[0], "title"
            md5_fix[md5] = next(h for h, p in sd.items() if p == new)
    report.append((d, how if new else "not on SD", new))
    if new:
        mapping[doc] = new
        sidecars.append((d, luas, doc, new, md5_fix.get(md5)))

# ---- settings tree --------------------------------------------------------------------------------
dst = os.path.join(OUT, "koreader")
if os.path.exists(OUT):
    sys.exit("OUT exists: " + OUT)
shutil.copytree(CUR, dst)                                  # current install first (new-version files, caches)
for name in os.listdir(BAK):                               # then the backup on top
    if name in SKIP_TOP:
        continue
    s, t = os.path.join(BAK, name), os.path.join(dst, name)
    if os.path.isdir(s):
        shutil.copytree(s, t, dirs_exist_ok=True)
    else:
        shutil.copy2(s, t)
for junk in ("settings.reader.lua.before-homedir", "settings/docprops_cache.sqlite"):
    if os.path.exists(os.path.join(dst, junk)):
        os.remove(os.path.join(dst, junk))
# cover browser cache: the device's (already has the SD books), names remapped; drop the backup's (old paths)
for ext in ("", "-wal", "-shm"):
    p = os.path.join(dst, "settings", "bookinfo_cache.sqlite3" + ext)
    if os.path.exists(p):
        os.remove(p)
db = sqlite3.connect(os.path.join(CUR, "settings", "bookinfo_cache.sqlite3"))
db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
out_db = sqlite3.connect(os.path.join(dst, "settings", "bookinfo_cache.sqlite3"))
db.backup(out_db)
db.close()
n = 0
for old, new in renamed.items():
    n += out_db.execute("update bookinfo set filename=? where filename=?", (new, old)).rowcount
out_db.commit()
out_db.execute("PRAGMA journal_mode=DELETE")
out_db.close()
report.append(("bookinfo_cache rows renamed", n, ""))

# statistics: same book, new file -> new md5 (unless the new md5 already has a row)
st = sqlite3.connect(os.path.join(dst, "settings", "statistics.sqlite3"))
for old, new in md5_fix.items():
    if not st.execute("select 1 from book where md5=?", (new,)).fetchone():
        report.append(("statistics md5 " + old[:8] + " -> " + new[:8], st.execute(
            "update book set md5=? where md5=?", (new, old)).rowcount, ""))
st.commit()
st.close()

# paths in settings/history/plugin data (never in plugin code or help)
pairs = sorted(mapping.items(), key=lambda kv: -len(kv[0])) + [(OLD_BOOKS, NEW_BOOKS)]
for root, dirs, files in os.walk(dst):
    rel = os.path.relpath(root, dst)
    if rel.split(os.sep)[0] in ("plugins", "help", "data", "cache"):
        continue
    for f in files:
        if not f.endswith(".lua") and not f.endswith(".lua.old"):
            continue
        p = os.path.join(root, f)
        t = open(p, encoding="utf-8", errors="surrogateescape").read()
        u = t
        for a, b in pairs:
            u = u.replace(a, b)
        if u != t:
            open(p, "w", encoding="utf-8", errors="surrogateescape").write(u)

# history: only books that exist on the SD card, newest first, as KOReader writes it
hist = open(os.path.join(BAK, "history.lua"), encoding="utf-8").read()
entries = re.findall(r'\["time"\] = (\d+),\s*\["file"\] = "((?:[^"\\]|\\.)*)"', hist)
keep, seen = [], set()
for tm, f in entries:
    if f in mapping and mapping[f] not in seen:
        seen.add(mapping[f])
        keep.append((int(tm), mapping[f]))
with open(os.path.join(dst, "history.lua"), "w", encoding="utf-8") as h:
    h.write("-- /storage/emulated/0/koreader/history.lua\nreturn {\n")
    for i, (tm, f) in enumerate(keep, 1):
        h.write(f"    [{i}] = {{\n        [\"time\"] = {tm},\n        [\"file\"] = {lua_str(f)},\n    }},\n")
    h.write("}\n")
report.append(("history entries kept", len(keep), ""))

# no quickstart pop-up for the version that is installed now
sr = os.path.join(dst, "settings.reader.lua")
cur_q = re.search(r'\["quickstart_shown_version"\] = (\d+)', open(os.path.join(CUR, "settings.reader.lua"), encoding="utf-8").read())
t = open(sr, encoding="utf-8").read()
if cur_q:
    t = re.sub(r'\["quickstart_shown_version"\] = \d+', '["quickstart_shown_version"] = ' + cur_q.group(1), t)
open(sr, "w", encoding="utf-8").write(t)

# sidecars next to the new files
for d, luas, doc, new, newmd5 in sidecars:
    base = os.path.splitext(os.path.basename(new))[0] + ".sdr"
    os.makedirs(os.path.join(OUT, "sidecars", base))
    ext = os.path.splitext(new)[1].lstrip(".").lower()
    for f in luas:
        t = open(os.path.join(BAK_SIDE, d, f), encoding="utf-8").read()
        t = t.replace(lua_str(doc), lua_str(new))
        if newmd5:
            t = re.sub(r'(\["partial_md5_checksum"\] = )"[0-9a-f]+"', r'\1"' + newmd5 + '"', t)
        name = re.sub(r"^metadata\.[^.]+\.lua", f"metadata.{ext}.lua", f)
        open(os.path.join(OUT, "sidecars", base, name), "w", encoding="utf-8").write(t)

for r in report:
    print(*r, sep="  |  ")
