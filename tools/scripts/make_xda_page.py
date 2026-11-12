#!/usr/bin/env python3
"""Local copy-paste page for the XDA thread (private tool, 2026-11-10).

Usage: make_xda_page.py DIST_DIR RELEASE_NAME TAG DATE
  Reads DIST_DIR/XDA-POST.md (BBCode with an HTML comment on top), fills the download links (GitHub release assets of
  TAG), the SHA-256 values (from the .sha256 files in DIST_DIR) and the release date, splits the post at its
  [SIZE=5][B]...[/B][/SIZE] headings and writes DIST_DIR/XDA-POST.html: one copy button per section plus one for the
  whole post. Refuses to write while any {{...}} placeholder is left (the screenshot line becomes a note on the page).
"""
import html, os, re, sys

dist, name, tag, date = sys.argv[1:5]
REPO = "https://github.com/matanmi12/bigme-b1051c-android16"
VER = re.sub(r"^([a-z]+)(\d+)$", r"\1 \2", name.split("-")[2])      # "alpha7" -> "alpha 7"
src = open(os.path.join(dist, "XDA-POST.md")).read()

title = re.search(r"Suggested thread title:\s*\n\s*\n?\s*(.+)", src).group(1).strip()
body = re.sub(r"\A\s*<!--.*?-->\s*", "", src, flags=re.S)

def sha(var):
    return open(os.path.join(dist, f"{name}-{var}.zip.sha256")).read().split()[0]

link = lambda var: f"[URL]{REPO}/releases/download/{tag}/{name}-{var}.zip[/URL]"
body = (body.replace("{{DOWNLOAD LINK GAPPS}}", link("gapps")).replace("{{DOWNLOAD LINK NOGAPPS}}", link("nogapps"))
        .replace("{{RELEASE DATE}}", date))
# the SHA-256 line is rewritten from the files every time, so a rebuilt zip can never leave a stale value
body = re.sub(r"\[\*\]SHA-256: .*", f"[*]SHA-256: {sha('gapps')} (with Google), {sha('nogapps')} (without Google)", body)
shots = "{{SCREENSHOTS"
body = re.sub(r"\n*\{\{SCREENSHOTS[^}]*\}\}\n*", "\n\n@@SHOTS@@\n\n", body)
left = re.findall(r"\{\{[^}]*\}\}", body)
if left:
    sys.exit(f"placeholders left: {left}")

parts = re.split(r"(?m)^(?=\[SIZE=5\]\[B\])", body.strip())
sections = []
for i, p in enumerate(parts):
    m = re.match(r"\[SIZE=5\]\[B\](.+?)\[/B\]\[/SIZE\]", p)
    sections.append(((m.group(1) if m else "Opening (title block, alpha notice, intro)"), p.strip()))

def card(i, head, text):
    note = ""
    if "@@SHOTS@@" in text:
        text = text.replace("\n\n@@SHOTS@@\n\n", "\n\n").replace("@@SHOTS@@", "").strip()
        note = ('<p class="note">כאן מעלים בעורך של XDA 3 עד 5 צילומי מסך, אחרי הפסקה האחרונה של הקטע הזה. '
                'רק מהתקנה נקייה, לא מהטאבלט האישי (בלי שמות רשתות, חשבונות או ספרים).</p>')
    return (f'<section><div class="head"><h2>{html.escape(head)}</h2>'
            f'<button data-t="s{i}">Copy</button></div>{note}'
            f'<pre id="s{i}" dir="ltr">{html.escape(text)}</pre></section>')

full = "\n\n".join(t.replace("\n\n@@SHOTS@@\n\n", "\n\n") for _, t in sections)
cards = "\n".join(card(i, h, t) for i, (h, t) in enumerate(sections))
page = f"""<!doctype html>
<html lang="he" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>XDA post {VER}</title>
<style>
:root {{ --bg:#F7F4E7; --card:#FFFDF6; --ink:#1d1b16; --muted:#6b665a; --line:#d9d3bf; --accent:#1d1b16; --ok:#2f6b3a; }}
@media (prefers-color-scheme: dark) {{ :root:not([data-theme="light"]) {{ --bg:#16150f; --card:#1f1d16; --ink:#efe9d6;
  --muted:#a59e8a; --line:#3a372c; --accent:#efe9d6; --ok:#8fd19e; }} }}
:root[data-theme="dark"] {{ --bg:#16150f; --card:#1f1d16; --ink:#efe9d6; --muted:#a59e8a; --line:#3a372c; --accent:#efe9d6; --ok:#8fd19e; }}
* {{ box-sizing:border-box }}
body {{ margin:0; background:var(--bg); color:var(--ink); font:16px/1.55 -apple-system, "Segoe UI", Rubik, Arial, sans-serif; }}
main {{ max-width:900px; margin:0 auto; padding:24px 16px 64px; }}
h1 {{ font-size:1.5rem; margin:0 0 4px }} .sub {{ color:var(--muted); margin:0 0 20px }}
section {{ background:var(--card); border:1px solid var(--line); border-radius:12px; margin:0 0 16px; overflow:hidden }}
.head {{ display:flex; align-items:center; justify-content:space-between; gap:12px; padding:10px 14px; border-bottom:1px solid var(--line) }}
h2 {{ font-size:1rem; margin:0; direction:ltr; text-align:left; flex:1 }}
button {{ font:inherit; font-size:.9rem; padding:6px 14px; border-radius:8px; border:1px solid var(--accent);
  background:transparent; color:var(--ink); cursor:pointer; min-width:84px }}
button.done {{ border-color:var(--ok); color:var(--ok) }}
pre {{ margin:0; padding:12px 14px; white-space:pre-wrap; word-break:break-word; text-align:left; font:13px/1.5 ui-monospace, Menlo, Consolas, monospace; max-height:340px; overflow:auto }}
.note {{ margin:0; padding:10px 14px; border-bottom:1px dashed var(--line); color:var(--muted); font-size:.9rem }}
.steps {{ margin:0 0 20px; padding-inline-start:20px; color:var(--muted) }}
</style></head><body><main>
<h1>פוסט XDA: InKognito {VER}</h1>
<p class="sub">מקומי, לא פורסם. כל קטע מועתק כ-BBCode. בעורך של XDA עוברים קודם למצב BBCode (כפתור ה-[ ]).</p>
<ol class="steps">
<li>כותרת השרשור: הקטע הראשון.</li>
<li>אפשר להעתיק את כל הפוסט בבת אחת (הקטע השני), או קטע אחרי קטע.</li>
<li>צילומי המסך עולים דרך העורך של XDA, במקום שמסומן בקטע הפתיחה.</li>
</ol>
<section><div class="head"><h2>Thread title</h2><button data-t="title">Copy</button></div><pre id="title" dir="ltr">{html.escape(title)}</pre></section>
<section><div class="head"><h2>Whole post (all sections)</h2><button data-t="all">Copy</button></div><pre id="all" dir="ltr">{html.escape(full)}</pre></section>
{cards}
</main>
<script>
function copyText(t) {{
  if (navigator.clipboard && window.isSecureContext) return navigator.clipboard.writeText(t);
  return new Promise(function (ok, bad) {{
    var a = document.createElement("textarea"); a.value = t; a.style.position = "fixed"; a.style.opacity = "0";
    document.body.appendChild(a); a.select();
    try {{ document.execCommand("copy") ? ok() : bad(); }} catch (e) {{ bad(e); }} finally {{ a.remove(); }}
  }});
}}
document.querySelectorAll("button[data-t]").forEach(function (b) {{
  b.addEventListener("click", function () {{
    copyText(document.getElementById(b.dataset.t).textContent).then(function () {{
      b.textContent = "Copied"; b.classList.add("done");
      setTimeout(function () {{ b.textContent = "Copy"; b.classList.remove("done"); }}, 1600);
    }}, function () {{ b.textContent = "Select + Cmd-C"; }});
  }});
}});
</script></body></html>
"""
out = os.path.join(dist, "XDA-POST.html")
open(out, "w").write(page)
print(f"{out}: {len(sections)} sections, title: {title}")
