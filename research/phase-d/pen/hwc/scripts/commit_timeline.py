#!/usr/bin/env python3
"""Parse a logcat -b all capture: per e-ink commit, policy time (dirty ratio line), commit start
([normal commit]/[rect commit]), commit end (commit OK), ratio, mode; pen activity from wacom kernel lines.
usage: commit_timeline.py LOG [t_start t_end]   (times as HH:MM:SS.mmm)"""
import re, sys, statistics

def t2ms(t):
    h, m, s = t.split(':'); return (int(h) * 3600 + int(m) * 60 + float(s)) * 1000.0

log = sys.argv[1]
lo = t2ms(sys.argv[2]) if len(sys.argv) > 2 else 0
hi = t2ms(sys.argv[3]) if len(sys.argv) > 3 else 1e12
ts_re = re.compile(r'^\d\d-\d\d (\d\d:\d\d:\d\d\.\d\d\d) ')
commits = []; cur = None; pending_ratio = None; pen = []; skips = 0; same = 0; policy = 0
for line in open(log, errors='replace'):
    m = ts_re.match(line)
    if not m: continue
    t = t2ms(m.group(1))
    if not (lo <= t <= hi): continue
    if 'wacom report' in line:
        p = re.search(r'p=(\d+)', line)
        pen.append((t, int(p.group(1)) if p else -1)); continue
    if 'hwcomposer' not in line and 'hwcshim' not in line: continue
    if 'dirty ratio:' in line:
        policy += 1
        pending_ratio = (t, float(line.split('dirty ratio:')[1]))
    elif 'No dirty region' in line:
        skips += 1
    elif 'skip same frame' in line:
        same += 1
    elif '[normal commit]' in line or '[rect commit]:' in line:
        mm = re.search(r'mode=([0-9a-f]+)', line)
        cur = dict(policy_t=pending_ratio[0] if pending_ratio else None, ratio=pending_ratio[1] if pending_ratio else None,
                   start=t, mode=mm.group(1), kind='rect' if 'rect commit' in line else 'normal', area=re.search(r'\(([-0-9,]+)\)', line).group(1) if 'update_area' in line else '')
        commits.append(cur)
    elif 'commit OK' in line and cur is not None and 'end' not in cur:
        cur['end'] = t

print('window %s..%s  policy runs=%d  no-dirty skips=%d  skip-same-frame=%d  commits=%d  pen samples=%d (p>0: %d)' % (
    sys.argv[2] if len(sys.argv) > 2 else '-', sys.argv[3] if len(sys.argv) > 3 else '-', policy, skips, same,
    len(commits), len(pen), sum(1 for _, p in pen if p > 0)))
dur = [c['end'] - c['start'] for c in commits if 'end' in c]
gap = [b['start'] - a['start'] for a, b in zip(commits, commits[1:])]
pol = [c['start'] - c['policy_t'] for c in commits if c['policy_t']]
idle = [b['start'] - a['end'] for a, b in zip(commits, commits[1:]) if 'end' in a]
def st(name, v):
    if v: print('%-38s n=%4d min=%6.1f med=%6.1f mean=%6.1f p90=%6.1f max=%6.1f ms' % (name, len(v), min(v), statistics.median(v), statistics.mean(v), sorted(v)[int(0.9 * (len(v) - 1))], max(v)))
st('commit start -> commit OK (ioctl)', dur)
st('commit start -> next commit start', gap)
st('policy (dirty ratio) -> commit start', pol)
st('commit OK -> next commit start (idle)', idle)
modes = {}
for c in commits: modes[(c['kind'], c['mode'], c['area'])] = modes.get((c['kind'], c['mode'], c['area']), 0) + 1
for k, v in sorted(modes.items(), key=lambda x: -x[1]): print('  %5d x %s mode=%s area=(%s)' % (v, k[0], k[1], k[2]))
ratios = {}
for c in commits: ratios[c['ratio']] = ratios.get(c['ratio'], 0) + 1
print('  dirty ratio of committed frames:', ', '.join('%s:%d' % (r, n) for r, n in sorted(ratios.items(), key=lambda x: -x[1])))
if pen:
    pd = [b[0] - a[0] for a, b in zip(pen, pen[1:])]
    st('pen sample interval', pd)
    # commits overlapping pen-down periods
    down = [t for t, p in pen if p > 0]
    if down:
        print('pen-down span %.0f ms (%s samples); commits in span: %d' % (down[-1] - down[0], len(down), sum(1 for c in commits if down[0] <= c['start'] <= down[-1] + 200)))
