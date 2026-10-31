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
rows = list(csv.DictReader(open(os.path.join(HERE, 'inputs/standards_filled.csv'))))
byname = {r['exercise']: r for r in rows}
COLS = [f'{c}{l}' for c in ('men_180lb_', 'women_140lb_') for l in LEVELS]

# Common-sense corrections (owner's review, Nov 2026). Each copies or scales another row of the same file.
def copy(dst, src, f=1.0, cols=COLS, why=''):
    d, s = byname[dst], byname[src]
    for c in cols: d[c] = str(round(float(s[c]) * f, 1))
    d['source'] = f'{why} (corrected: {f:g} x {src})'; d['confidence_1_to_10'] = s['confidence_1_to_10']
MEN, WOMEN = COLS[:5], COLS[5:]
copy('Standing Calf Raise (Weighted Vest)', 'Standing Barbell Calf Raise', why='a vest loads the body like a bar on the back')
copy('One-Leg Standing Calf Raise (Weighted Vest)', 'Standing Barbell Calf Raise', why='ranked as its two-leg equivalent, body weight + 2 x vest')
copy('Weighted Squat', 'Goblet Squat', why='one weight held at the chest, as in a goblet squat')
copy('Cable Shoulder Press', 'Seated Cable Shoulder Press', why='the same press, seated or standing')
copy('Kettlebell Thruster', 'Front Squats With Two Kettlebells', 0.85, why='a thruster can\'t beat a front squat with the same two bells')
copy('Barbell Hack Squat', 'Barbell Squat', 0.61, MEN, why='men at the women\'s hack squat / squat ratio (was equal to the back squat)')
copy('Snatch Deadlift', 'Clean Deadlift', 1.0, WOMEN, why='women matched to the clean deadlift, like the men')
copy('Muscle Snatch', 'Power Snatch', 0.85, why='no re-bend, so lighter than a power snatch')
DROP = {'Calf Raise On A Dumbbell'}                 # the dumbbell is the step, nothing is held
ONE_LEG = {'One-Leg Standing Calf Raise (Weighted Vest)'}
PER_IMPLEMENT = {'Around The Worlds', 'Double Kettlebell Windmill', 'Tate Press'}   # two dumbbells/bells, logged per one

out, skipped = [], []
for r in rows:
    if r['exercise'] in DROP: continue
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
                'r': reps, 'd': eq in ('Dumbbell', 'Kettlebell') and (logged.startswith('weight of ONE') or r['exercise'] in PER_IMPLEMENT), 'k': 1,
                'ai': conf <= 3, 'a': approx, 'note': f"Personal use: {r['source']} (confidence {conf}/10).", **({'u': True} if grip else {}), **({'t': True} if timed else {}), **({'ul': True} if r['exercise'] in ONE_LEG else {})})
out.sort(key=lambda s: s['lift'])
os.makedirs(os.path.dirname(OUT), exist_ok=True)
json.dump(out, open(OUT, 'w'), separators=(',', ':'))
print(len(out), 'exercises;', len(skipped), 'left out:', ', '.join(skipped))
