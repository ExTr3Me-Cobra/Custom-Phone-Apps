"""PERSONAL-USE (removable): builds app/src/main/assets/personal/standards_purged.json, the "Purged" developer option.

inputs/purged_tables.json holds the app's standards from before the Oct 2026 purge (Strength Level-derived), recovered
from the owner's comparison page: shared tables of [body weight lb, Beginner..Elite] rows and, per exercise, its men's
and women's table and its scale factor k (applied by the app, as before). Only exercises still in the app are kept.
Run: python3 tools/personal/build_purged.py
"""
import json, os, re

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, '../../app/src/main/assets')
OUT = os.path.join(ASSETS, 'personal/standards_purged.json')
src = json.load(open(os.path.join(HERE, 'inputs/purged_tables.json')))
ex = {e['n']: e for e in json.load(open(os.path.join(ASSETS, 'exercises.json')))}
# Per-dumbbell flag as the app uses it today (one of a pair, or the one used), else from the equipment.
now = {s['i']: s['d'] for s in json.load(open(os.path.join(ASSETS, 'standards.json')))}
SINGLE = re.compile('goblet|pullover|swing|plie|one dumbbell|both hands|single dumbbell|windmill|turkish|halo|around the world|'
                    '^standing dumbbell triceps extension|^seated triceps press|^kettlebell overhead triceps extension', re.I)
out = []
for r in src['rows']:
    e = ex.get(r['n'])
    if not e: continue
    tab = lambda i: src['tabs'][i] if i is not None else None
    m, f = tab(r['tm']), tab(r['tf'])
    if not m and not f: continue
    d = now.get(e['i'], e['e'] in ('Dumbbell', 'Kettlebell') and not SINGLE.search(r['n']))
    out.append({'i': e['i'], 'lift': r['n'], 'ln': r['n'], 'm': m, 'f': f, 'r': r['u'] == 'reps', 'd': d, 'k': r['k'],
                'ai': False, 'a': None, 'note': 'Purged dataset: the app\'s standards before October 2026.'})
# Owner's corrections (Nov 2026). One-arm and two-arm dumbbell preacher curls are the same lift per dumbbell: both use
# Strength Level's one-arm dumbbell preacher curl table (inputs/sl_one_arm_dumbbell_preacher_curl.json) as it is.
# The preacher hammer curl (one dumbbell per arm only) is 1.1x that: hammer curls run about 10% heavier than curls.
pc = json.load(open(os.path.join(HERE, 'inputs/sl_one_arm_dumbbell_preacher_curl.json')))
for s in out:
    if s['lift'] in ('One Arm Dumbbell Preacher Curl', 'Two-Arm Dumbbell Preacher Curl'):
        s.update(m=pc['m'], f=pc['f'], k=1.0, d=True)
    elif s['lift'] == 'Preacher Hammer Dumbbell Curl':
        s.update(m=[[r[0]] + [round(v * 1.1, 1) for v in r[1:]] for r in pc['m']],
                 f=[[r[0]] + [round(v * 1.1, 1) for v in r[1:]] for r in pc['f']], k=1.0, d=True)
out.sort(key=lambda s: s['lift'])
json.dump(out, open(OUT, 'w'), separators=(',', ':'))
print(len(out), 'exercises;', sum(1 for s in out if not s['m'] or not s['f']), 'with one sex only')
