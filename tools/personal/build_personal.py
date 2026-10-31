"""PERSONAL-USE (removable): builds app/src/main/assets/personal/standards_personal.json from inputs/standards_filled.csv.

The CSV was filled in by another AI for the owner, mostly from Strength Level's tables (not free for commercial use).
Nothing here uses V1, V2 or Gemini data. Body weight: the CSV gives a 180 lb man and a 140 lb woman; other weights
scale as body weight^0.67 for weights (Jaric 2002, Sports Med 32:615-631) and ^-1/3 for body-weight reps.
Stars from the row's own confidence: 7-10 no star note, 4-6 "estimate", 1-3 rough (purple, "All on + AI" mode only).
Timed holds rank on seconds (flag t) and scale like reps. Run: python3 tools/personal/build_personal.py
"""
import csv, json, os, re

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '../../app/src/main/assets/personal/standards_personal.json')
LEVELS = ['beginner', 'novice', 'intermediate', 'advanced', 'elite']
GRID = {'m': list(range(110, 311, 10)), 'f': list(range(90, 261, 10))}
REF = {'m': 180, 'f': 140}

ids = {e['i'] for e in json.load(open(os.path.join(HERE, '../../app/src/main/assets/exercises.json')))}
out, skipped = [], []
for r in csv.DictReader(open(os.path.join(HERE, 'inputs/standards_filled.csv'))):
    unit = r['unit']
    if unit not in ('1RM_lb', 'max_reps', 'max_seconds') or r['id'] not in ids:
        if r['men_180lb_intermediate'] or r['women_140lb_intermediate']: skipped.append(f"{r['exercise']} ({unit})")
        continue
    reps, timed, grip = unit == 'max_reps', unit == 'max_seconds', 'Grip_Dynamometer' in r['id']
    tables = {}
    for sex, col in (('m', 'men_180lb_'), ('f', 'women_140lb_')):
        v = [r[col + l] for l in LEVELS]
        if not all(v): continue
        v = [float(x) for x in v]
        assert all(b > a for a, b in zip(v, v[1:])), r['exercise']
        if grip: tables[sex] = [[1, *v], [1000, *v]]; continue
        p = -1 / 3 if reps or timed else 0.67
        tables[sex] = [[bw, *[round(x * (bw / REF[sex]) ** p, 1) for x in v]] for bw in GRID[sex]]
    if not tables: continue
    conf = int(r['confidence_1_to_10'] or 1)
    approx = None if conf >= 7 else f"Personal-use estimate (confidence {conf}/10)."
    eq, logged = r['equipment'], r['weight_logged_as']
    out.append({'i': r['id'], 'lift': r['exercise'], 'ln': r['exercise'], 'm': tables.get('m'), 'f': tables.get('f'),
                'r': reps, 'd': eq in ('Dumbbell', 'Kettlebell') and logged.startswith('weight of ONE'), 'k': 1,
                'ai': conf <= 3, 'a': approx, 'note': f"Personal use: {r['source']} (confidence {conf}/10).", **({'u': True} if grip else {}), **({'t': True} if timed else {})})
out.sort(key=lambda s: s['lift'])
os.makedirs(os.path.dirname(OUT), exist_ok=True)
json.dump(out, open(OUT, 'w'), separators=(',', ':'))
print(len(out), 'exercises;', len(skipped), 'left out:', ', '.join(skipped))
