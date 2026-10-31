// Builds the app's strength standards (app/src/main/assets/standards.json) from openly licensed inputs only.
// Run: node tools/standards/build_standards.js   (writes the asset and tools/standards/build_report.txt)
//      FAVOR=gemini node tools/standards/build_standards.js   (the developer variant, standards_gemini.json)
//      V2=1 node tools/standards/build_standards.js            (the V2 trial, standards_v2.json and build_report_v2.txt)
//
// Levels: Beginner / Novice / Intermediate / Advanced / Elite = 5th / 20th / 50th / 80th / 95th percentile of people who
// do resistance training, at the lifter's sex and bodyweight. 1RM in lb (dumbbells and kettlebells per hand, cables on
// a 1:1 pulley); bodyweight moves in max reps, counting only people who can do at least one.
//
//   gold    research_lifts.json: published research only (see that file's sources).
//   blue    research lifts that rest on an assumption; exercises estimated as a ratio to a research anchor from the four
//           AI-generated databases, each calibrated against the research lifts, where 2+ sources agree within 25%;
//           bodyweight-rep moves from a strength model.
//   purple  AI-only rough estimates: one source, sources more than 25% apart, or moves the strength model can't cover.
// Men's and women's stars are set separately (a / ai men's, af / aif / nf women's when they differ).
const fs = require('fs'), path = require('path');
const HERE = __dirname, IN = f => JSON.parse(fs.readFileSync(path.join(HERE, 'inputs', f), 'utf8'));
const ASSETS = process.env.ASSETS || path.resolve(HERE, '../../app/src/main/assets');
const AI = IN('ai_databases.json').exercises, RES = IN('research_lifts.json'), CURVES = IN('openpowerlifting_curves.json');
const GRIP = IN('grip_tomkinson_2025.json'), CAND = IN('ai_corrections.json');
const EXS = JSON.parse(fs.readFileSync(path.join(ASSETS, 'exercises.json'), 'utf8'));
const SRC = ['claude', 'gemini', 'chatgpt', 'deepseek'], SN = { claude: 'Claude', gemini: 'Gemini', chatgpt: 'ChatGPT', deepseek: 'DeepSeek' };
// V2 trial (developer setting "V2 rankings"): the fixes listed in README.md, section "V2".
const V2 = !!process.env.V2;
const SEXES = ['male', 'female'], REF = { male: 180, female: 140 }, LBS = 2.20462;
// Spread (SD of ln 1RM at a given body weight). V2 keeps it: Isenmann et al. 2026 (Sci Rep 16:20797, CC BY), beginners to
// elite, measured 0.19-0.23 for men and 0.26-0.28 for women (Supplement S16-S17), no wider than these.
const SIG = { male: 0.26, female: 0.29 };
const Z_OUT = [-1.6449, -0.8416, 0, 0.8416, 1.6449];   // the app's levels: 5/20/50/80/95th percentile
const Z_AI = [-1.6449, -0.6745, 0, 0.6745, 1.6449];    // the AI databases' levels: 5/25/50/75/95th
const GRID = { male: [], female: [] }; for (let b = 110; b <= 310; b += 10) GRID.male.push(b); for (let b = 90; b <= 260; b += 10) GRID.female.push(b);
const report = [];
const median = a => { a = [...a].sort((x, y) => x - y); const n = a.length; return n ? (n % 2 ? a[(n - 1) / 2] : (a[n / 2 - 1] + a[n / 2]) / 2) : null; };
const quant = (a, f) => { a = [...a].sort((x, y) => x - y); const i = (a.length - 1) * f, lo = Math.floor(i); return a[lo] + (a[Math.ceil(i)] - a[lo]) * (i - lo); };
const Phi = z => { const t = 1 / (1 + 0.2316419 * Math.abs(z)), d = 0.3989423 * Math.exp(-z * z / 2), p = d * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274)))); return z > 0 ? 1 - p : p; };
const iPhi = u => { let lo = -8, hi = 8; for (let i = 0; i < 80; i++) { const m = (lo + hi) / 2; if (Phi(m) < u) lo = m; else hi = m; } return (lo + hi) / 2; };

// ---- Bodyweight shape: OpenPowerlifting / OpenWeightlifting median curves ----
function shape(sex, lift, bw) { const [a, b, c, lo, hi] = CURVES.bodyweight[(sex == 'male' ? 'M' : 'F') + lift], x = Math.log(Math.min(Math.max(bw, lo), hi));
  return Math.exp(a * x * x + b * x + c) * (bw > hi ? Math.pow(bw / hi, 0.15) : 1) * (bw < lo ? Math.pow(bw / lo, 0.9) : 1); }
const curve0 = (sex, lift, bw) => shape(sex, lift, bw) / shape(sex, lift, REF[sex]);
// V2: below the reference weight, levels never fall faster than body weight (log-log slope at most 1). Strength scales
// with body mass to about the 2/3 power (Jaric 2002, Sports Med 32:615-631), so a steeper slope at the light end is an
// artefact of the light powerlifting classes, and it made light lifters' standards too easy.
const capMemo = {};
function curve(sex, lift, bw) { if (!V2 || bw >= REF[sex]) return curve0(sex, lift, bw);
  const key = sex + lift; if (!capMemo[key]) { const m = {}; let prev = 1, pb = REF[sex];
    for (let b = REF[sex] - 1; b >= 60; b--) { prev = Math.max(curve0(sex, lift, b), prev * b / pb); pb = b; m[b] = prev; } capMemo[key] = m; }
  const lo = Math.floor(bw), m = capMemo[key]; if (lo < 60) return m[60] * bw / 60;
  const a = lo == REF[sex] ? 1 : m[lo], b = lo + 1 >= REF[sex] ? 1 : m[lo + 1]; return a + (b - a) * (bw - lo); }

// ---- Reading the AI databases at the reference bodyweight (each by its own stated rules) ----
function table(t, bw) { if (!t || t.length < 2) return null; let i = 0; for (let j = 0; j < t.length - 1; j++) if (t[j][0] <= bw) i = j;
  return [1, 2, 3, 4, 5].map(k => { const a = t[i][k], b = t[i + 1][k], x0 = t[i][0], x1 = t[i + 1][0];
    if (a > 0 && b > 0) return Math.exp(Math.log(a) + (Math.log(b) - Math.log(a)) * (Math.log(bw) - Math.log(x0)) / (Math.log(x1) - Math.log(x0)));
    return Math.max(0, a + (b - a) * (bw - x0) / (x1 - x0)); }); }
const DSW = [[50, 1.30], [60, 1.20], [70, 1.08], [80, 1.00], [90, 0.93], [100, 0.87], [110, 0.81], [120, 0.76], [130, 0.71], [140, 0.67], [150, 0.63]];
const DSR = [[50, 1.35], [60, 1.22], [70, 1.10], [80, 1.00], [90, 0.88], [100, 0.76], [110, 0.64], [120, 0.52], [130, 0.42], [140, 0.34]];
function lin(t, x) { let i = 0; for (let j = 0; j < t.length - 1; j++) if (t[j][0] <= x) i = j; const [x0, y0] = t[i], [x1, y1] = t[i + 1]; return Math.max(0.15, y0 + (y1 - y0) * (x - x0) / (x1 - x0)); }
// Sources that measured an exercise a different way from the app (inputs/ai_conventions.json), converted or left out.
const CONV = Object.fromEntries(IN('ai_conventions.json').entries.map(c => [c.exercise + '|' + c.source, c.multiplier]));
// V2: Gemini, ChatGPT and DeepSeek gave the two-kettlebell front squat as both bells together (each is above the same
// source's goblet squat with one bell); the app logs it per bell, like Claude's table.
if (V2) for (const k of ['gemini', 'chatgpt', 'deepseek']) CONV['Front Squats With Two Kettlebells|' + k] = 0.5;
function rawSource(e, src, sex) { const v = rawSource0(e, src, sex), c = CONV[e.exercise + '|' + src]; return c === undefined || !v ? v : c === 0 ? null : v.map(x => x * c); }
function rawSource0(e, src, sex) { const bw = REF[sex], reps = e.unit == 'reps';
  if (src == 'claude') return e.claude ? table(e.claude[sex == 'male' ? 'm' : 'f'], bw) : null;
  if (src == 'chatgpt') return e.chatgpt ? table(e.chatgpt[sex == 'male' ? 'm' : 'f'], bw) : null;
  if (src == 'gemini') { const g = e.gemini; if (!g) return null; const f = sex == 'male' ? 1 : (reps ? 0.85 : (g.body == 'l' ? 0.65 : 0.55));
    return g.median_180lb_man_levels.map(v => v * f * (reps ? Math.pow(180 / bw, 0.5) : Math.pow(bw / 180, 0.67))); }
  if (src == 'deepseek') { const s = e.deepseek; if (!s) return null; const kg = bw / LBS, f = sex == 'male' ? 1 : s.sf;
    return s.v.map(v => s.reps ? v * lin(DSR, kg) * f : v * bw * lin(DSW, kg) * f); }
}
const research = Object.fromEntries(RES.map(r => [r.exercise, r]));
if (V2) {
  // Women's leg press: Piper et al.'s women's 10-rep-max ratio (4.5 x bench) disagrees with their own group means, so V2
  // uses the men's leg press / squat ratio applied to the women's squat (Isenmann et al. 2026).
  const lp = research['Leg Press'], sq = research['Barbell Squat'];
  lp.female_median_lb_at_140 = lp.male_median_lb_at_180 / sq.male_median_lb_at_180 * sq.female_median_lb_at_140;
  lp.source += '; V2: women = men\'s leg press/squat ratio x the women\'s squat (assumed)';
  // Women's lat pulldown: Piper's women's ratio (1.34 x bench) has the same problem; V2 takes the men's ratio and the
  // women/men difference measured on the seated row in the same people (Seo et al. 2012: 1.07 vs 0.97 x bench).
  const pd = research['Wide-Grip Lat Pulldown'], row = research['Seated Cable Rows'], bp0 = research['Barbell Bench Press - Medium Grip'];
  pd.female_median_lb_at_140 = pd.male_median_lb_at_180 / bp0.male_median_lb_at_180 * (row.female_median_lb_at_140 / bp0.female_median_lb_at_140) /
    (row.male_median_lb_at_180 / bp0.male_median_lb_at_180) * bp0.female_median_lb_at_140;
  pd.source += '; V2: women = men\'s ratio x the women/men seated-row difference (Seo et al. 2012)';
  // Variants of research lifts, assumed equal to them (no study compares them).
  research['Barbell Full Squat'] = { ...sq, exercise: 'Barbell Full Squat', gold: { male: false, female: false }, source: 'assumed equal to the back squat (same lift, below parallel)' };
  const bp = research['Barbell Bench Press - Medium Grip'];
  research['Bench Press - Powerlifting'] = { ...bp, exercise: 'Bench Press - Powerlifting', gold: { male: false, female: false }, source: 'assumed equal to the bench press (the same lift with competition technique)' };
}
const resMed = (n, sex) => research[n] ? research[n][sex == 'male' ? 'male_median_lb_at_180' : 'female_median_lb_at_140'] : null;

// ---- Corrections: unit mix-ups and extreme outliers, each re-checked against the other AI sources and research only ----
const fixes = {}; const fixLog = [];
const mono = v => v.every((x, i) => i == 0 || x >= v[i - 1] - 0.01);
function othersMedian(e, src, sex) { const o = SRC.filter(k => k != src).map(k => rawSource(e, k, sex)).filter(v => v && v[2] > 0).map(v => v[2]);
  const r = resMed(e.exercise, sex); if (r) o.push(r); return o.length ? median(o) : null; }
for (const c of CAND) { const e = AI.find(x => x.exercise == c.exercise); if (!e) continue; const v = rawSource(e, c.source, c.sex);
  const om = othersMedian(e, c.source, c.sex); if (!v || !om || !(v[2] > 0)) continue; const q = v[2] / om;
  const ok = c.multiplier == 0.5 ? q >= 1.6 : c.multiplier == 2 ? q <= 0.62 : c.multiplier == 0 ? (q >= 2.2 || q <= 0.45 || !mono(v)) : Math.abs(Math.log(q * c.multiplier)) < Math.abs(Math.log(q));
  fixLog.push(`${ok ? 'kept   ' : 'dropped'} x${c.multiplier} ${SN[c.source]} ${c.sex} ${c.exercise} (source is ${q.toFixed(2)}x the others)`);
  if (ok) fixes[c.exercise + '|' + c.source + '|' + c.sex] = c.multiplier; }
function source(e, src, sex) { const v = rawSource(e, src, sex); if (!v || !(v[2] > 0)) return null; const f = fixes[e.exercise + '|' + src + '|' + sex];
  if (f === 0) return null; return f ? v.map(x => x * f) : v; }
// Remaining extreme outliers (over 2.5x or under 0.4x everyone else) are left out.
// At most the single worst source per exercise and sex, only with 3+ others to compare and never on counts under 5 reps.
const dropped = new Set();
for (const e of AI) for (const sex of SEXES) { let worst = null;
  for (const k of SRC) { const v = source(e, k, sex); if (!v) continue;
    const o = SRC.filter(j => j != k).map(j => source(e, j, sex)).filter(Boolean).map(x => x[2]); const r = resMed(e.exercise, sex); if (r) o.push(r);
    if (o.length < 3 || (e.unit == 'reps' && median(o) < 5)) continue; const q = v[2] / median(o);
    if ((q > 2.5 || q < 0.4) && (!worst || Math.abs(Math.log(q)) > Math.abs(Math.log(worst.q)))) worst = { k, q }; }
  if (worst) { dropped.add(e.exercise + '|' + worst.k + '|' + sex); fixLog.push(`dropped outlier ${SN[worst.k]} ${sex} ${e.exercise} (${worst.q.toFixed(2)}x the others)`); } }
const val = (e, k, sex) => dropped.has(e.exercise + '|' + k + '|' + sex) ? null : source(e, k, sex);
const at = (e, sex) => Object.fromEntries(SRC.map(k => [k, val(e, k, sex)]).filter(x => x[1]));

// ---- Anchors, movement patterns and equipment ----
const ANCHOR = { B: 'Barbell Bench Press - Medium Grip', S: 'Barbell Squat', D: 'Barbell Deadlift', O: 'Standing Military Press', P: 'Wide-Grip Lat Pulldown' };
const byName = Object.fromEntries(AI.map(e => [e.exercise, e]));
for (const n of Object.values(ANCHOR)) if (!research[n]) throw new Error('anchor is not a research lift: ' + n);
function anchorOf(n, bl) {
  if (/leg curl|hamstring curl|nordic/i.test(n)) return 'S';
  if (/curl/i.test(n)) return 'B';
  if (/pulldown|pull-down|pull down|\brow\b|rows\b|rowing|pull-?up|\bchin|pullover|face pull|reverse fly|rear delt|muscle up|\blat\b/i.test(n)) return 'P';
  if (/shoulder press|military|overhead|arnold|lateral raise|front raise|side raise|upright row|handstand|landmine press|push press|behind the neck|bradford|z press|cuban/i.test(n)) return 'O';
  if (/squat|lunge|leg press|step-?up|split|leg extension|calf|hack|adduct|abduct|sled/i.test(n)) return 'S';
  if (/deadlift|\brdl\b|romanian|good morning|hip thrust|glute bridge|shrug|hyperextension|back extension|pull through|swing|clean|snatch|rack pull|high pull/i.test(n)) return 'D';
  if (bl == 'S' || /jerk|thruster|glute/i.test(n)) return 'S';
  return 'B';
}
function patternOf(n) { const t = [['curl', /curl/i], ['raise', /raise|fly|flye|pec deck|reverse fly/i], ['extension', /extension|pushdown|push-down|kickback|skull|jm press/i],
  ['row', /\brow|rows\b|face pull|rear delt|pullover|straight-arm/i], ['pulldown', /pulldown|pull-down|pull down|pull-?up|\bchin/i], ['olympic', /clean|snatch|jerk|high pull/i],
  ['hinge', /deadlift|\brdl\b|romanian|good morning|hip thrust|glute bridge|hyperextension|back extension|pull through|swing|rack pull/i], ['calf', /calf/i],
  ['squat', /squat|lunge|leg press|step-?up|split|hack|sled/i], ['shrug', /shrug/i], ['press', /press|push|dip/i]];
  for (const [k, r] of t) if (r.test(n)) return k; return 'other'; }
function familyOf(e) { if (e.category == 'olympic weightlifting') return 'olympic';
  return { Barbell: 'barbell', 'EZ Bar': 'barbell', Dumbbell: 'dumbbell', Kettlebell: 'kettlebell', Cable: 'cable', Machine: 'machine' }[e.equipment] || 'other'; }
const weighted = AI.filter(e => e.unit != 'reps');
for (const e of weighted) { e.anchor = anchorOf(e.exercise, e.curve); e.pat = patternOf(e.exercise); e.fam = familyOf(e); }
const isAnchor = n => Object.values(ANCHOR).includes(n);
const goldLift = (e, sex) => research[e.exercise] && research[e.exercise].gold[sex];
// Lifts the AI sources are calibrated against: gold ones plus research lifts with a measured ratio that isn't gold for a
// stated reason (a 10-rep-max ratio, measured in the other sex); not assumptions or low-confidence studies.
const calLift = (e, sex) => goldLift(e, sex) || (research[e.exercise] && !/assumed|low confidence|commonly cited/.test(research[e.exercise].source));

// ---- Calibration: each source's bias against the research lifts (calLift) ----
function ratios(e, sex) { const s = at(e, sex), a = at(byName[ANCHOR[e.anchor]], sex), o = {}; for (const k of SRC) if (s[k] && a[k]) o[k] = s[k][2] / a[k][2]; return o; }
function biases(exclude) { const out = {};
  for (const sex of SEXES) { out[sex] = {}; for (const k of SRC) { const all = [], byP = {}, byPF = {};
    for (const e of weighted) { if (e === exclude || isAnchor(e.exercise) || !calLift(e, sex)) continue;
      const r = ratios(e, sex)[k]; if (!r) continue; const lg = Math.log(r / (resMed(e.exercise, sex) / resMed(ANCHOR[e.anchor], sex)));
      all.push(lg); (byP[e.pat] = byP[e.pat] || []).push(lg); (byPF[e.pat + e.fam] = byPF[e.pat + e.fam] || []).push(lg); }
    const b0 = median(all) || 0, sh = (v, to) => v && v.length ? (v.length * median(v) + 5 * to) / (v.length + 5) : to;   // 5 pseudo-lifts of shrinkage
    // Per-pattern biases (CAL=pattern) over-fit the few research lifts in the hold-out test, so one bias per source and sex is used.
    const mode = process.env.CAL || 'overall';
    out[sex][k] = { all: b0, get: (p, f) => mode == 'none' ? 0 : mode == 'overall' ? b0 : sh(byPF[p + f], sh(byP[p], b0)) }; } }
  return out; }
const BIAS = biases(null);
// V2: the owner's own tested ratios (inputs/owner_tests.json), one vote each, men only (the owner is a man).
const OWNER = V2 ? Object.fromEntries(IN('owner_tests.json').ratios.map(o => [o.exercise, o])) : {};
function pooled(e, sex, bias) { const r = ratios(e, sex), cal = SRC.filter(k => r[k]).map(k => r[k] / Math.exp(bias[sex][k].get(e.pat, e.fam)));
  const ow = OWNER[e.exercise]; if (ow && sex == 'male' && ow.anchor == e.anchor) cal.push(ow.ratio);
  if (!cal.length) return null; const m = median(cal);
  // "Agree" = at least two sources within a 25% band around the median (within 12% either side).
  return { ratio: m, n: cal.length, agree: cal.filter(v => Math.abs(Math.log(v / m)) <= Math.log(1.12)).length }; }
// Developer variant (FAVOR=gemini, written to standards_gemini.json): every weighted exercise Gemini covers, research
// lifts included, takes its middle level 80% from Gemini's own number and 20% from the normal build (geometric), with the
// normal spread and body-weight shape. Stars are unchanged; bodyweight-rep moves are unchanged.
const FAVOR = process.env.FAVOR || '';

// Hold-out test: predict each calibration research lift from the calibrated AI ratios without using it.
const loo = { male: {}, female: {} };
for (const sex of SEXES) for (const e of weighted) { if (isAnchor(e.exercise) || !calLift(e, sex)) continue; const p = pooled(e, sex, biases(e)); if (!p) continue;
  const err = 100 * (p.ratio * resMed(ANCHOR[e.anchor], sex) / resMed(e.exercise, sex) - 1); for (const g of ['all', e.fam]) (loo[sex][g] = loo[sex][g] || []).push(err); }

// ---- Spread between levels: the research spread, scaled per equipment family by the AI sources' own relative spreads ----
const aiSpread = {}; for (const e of weighted) for (const sex of SEXES) { const s = at(e, sex);
  for (const k of SRC) if (s[k] && s[k][0] > 0) (aiSpread[sex + e.fam] = aiSpread[sex + e.fam] || []).push(Math.log(s[k][4] / s[k][0]) / (2 * 1.6449)); }
const sigmaOf = (sex, fam) => SIG[sex] * Math.min(1.4, Math.max(0.8, (median(aiSpread[sex + fam] || []) || median(aiSpread[sex + 'barbell'])) / median(aiSpread[sex + 'barbell'])));

// ---- Weighted exercises ----
const out = [];
const levelRows = (sex, m50, sig, lift) => GRID[sex].map(bw => [bw, ...Z_OUT.map(z => m50 * Math.exp(z * sig) * curve(sex, lift, bw))]);
const sourcesUsed = n => `the Claude, Gemini, ChatGPT and DeepSeek databases (${n} of 4 cover it)`;
for (const e of weighted) { const o = { e, sex: {} };
  for (const sex of SEXES) { const r = research[e.exercise], lift = r ? r.curve : (e.curve || 'B');
    if (r) { const gold = r.gold[sex]; o.sex[sex] = { m50: resMed(e.exercise, sex), conf: gold ? 'gold' : 'blue', rows: levelRows(sex, resMed(e.exercise, sex), SIG[sex], lift),
        note: (gold ? 'Research: ' : 'Estimate: ') + r.source + '.', approx: gold ? null : 'An estimate: ' + r.source + '.' }; continue; }
    const p = pooled(e, sex, BIAS), ca = resMed(ANCHOR[e.anchor], sex);
    if (p) { const agree = p.agree >= 2; const m50 = p.ratio * ca;
      o.sex[sex] = { m50, conf: agree ? 'blue' : 'purple', rows: levelRows(sex, m50, sigmaOf(sex, e.fam), lift), nsrc: p.n,
        note: `Estimated as a ratio to the ${ANCHOR[e.anchor]} research standard, from ${sourcesUsed(p.n)}, each calibrated against the research lifts.`,
        approx: agree ? 'No research measures this exercise directly, so it is estimated from AI-generated databases calibrated to research lifts.'
                      : 'A rough estimate: ' + (p.n == 1 ? 'only one AI-generated database covers this exercise.' : 'the AI-generated databases disagree by more than 25%.') }; continue; }
    const s = at(e, sex), cal = SRC.filter(k => s[k]).map(k => s[k][2] / Math.exp(BIAS[sex][k].all));
    if (cal.length) { const m50 = median(cal); o.sex[sex] = { m50, conf: 'purple', rows: levelRows(sex, m50, sigmaOf(sex, e.fam), lift), nsrc: cal.length,
      note: `Estimated from ${sourcesUsed(cal.length)}, calibrated against the research lifts.`, approx: 'A rough estimate from AI-generated databases.' }; } }
  if (FAVOR) for (const sex of SEXES) { const t = o.sex[sex], v = t && val(e, FAVOR, sex); if (!v || !(t.m50 > 0)) continue;
    const k = Math.exp(0.8 * Math.log(v[2] / t.m50)); t.m50 *= k; t.rows = t.rows.map(r => [r[0], ...r.slice(1).map(x => x * k)]); }
  if (Object.keys(o.sex).length) out.push(o); }

// ---- Bodyweight-rep moves: strength model ----
// Reps at relative load I (load / 1RM): reps = NA x (1/I - 1)^NB, a weighted least-squares fit (log reps on log(1/I - 1),
// weighted by sample size; upper-body rows at 30-99% of 1RM) to the meta-analysis data of Nuzzo et al. 2024 (Sports Med,
// CC BY 4.0; data on OSF). Only the two fitted numbers are kept here, not the dataset.
const NA = 26.63788204070436, NB = 0.7733977090692796;
report.push(`Reps vs relative load (Nuzzo 2024 fit, upper body): reps = ${NA.toFixed(2)} x (1/I - 1)^${NB.toFixed(3)}`);
const repsAt = I => I >= 1 ? 0 : NA * Math.pow(1 / I - 1, NB), Iat = reps => 1 / (Math.pow(reps / NA, 1 / NB) + 1);
function repLevels(q, S50, sig, bw) { const L = q * bw, z1 = Math.log(L / (Iat(1) * S50)) / sig, u0 = Phi(z1);
  return Z_OUT.map(z => Math.max(1, repsAt(L / (S50 * Math.exp(iPhi(u0 + Phi(z) * (1 - u0)) * sig))))); }
function repAnchor(n) { if (/sit-?up|crunch|leg raise|muscle up/i.test(n)) return null; if (/handstand/i.test(n)) return 'O'; if (/pull|chin|inverted row/i.test(n)) return 'P'; return 'B'; }
const repRows = AI.filter(e => e.unit == 'reps'), repInfo = [];
for (const e of repRows) { const a = repAnchor(e.exercise), o = { e, sex: {} };
  const s = { male: at(e, 'male'), female: at(e, 'female') }, p75 = sex => median(SRC.filter(k => s[sex][k]).map(k => s[sex][k][3])), n = sex => SRC.filter(k => s[sex][k]).length;
  if (a && n('male')) { const C = sex => resMed(ANCHOR[a], sex), lift = research[ANCHOR[a]].curve;
    // Load as a share of bodyweight, calibrated so the untruncated 75th percentile matches the AI sources' (clear of the zeros).
    const q = sex => Iat(p75(sex)) * C(sex) * Math.exp(Z_AI[3] * SIG[sex]) / REF[sex];
    o.q = { male: q('male'), female: n('female') && p75('female') >= 2 ? q('female') : null }; o.a = a; o.lift = lift; repInfo.push(o); }
  else for (const sex of SEXES) { const ks = SRC.filter(k => s[sex][k]); if (!ks.length) continue; const lv = [0, 1, 2, 3, 4].map(i => median(ks.map(k => s[sex][k][i])));
    const atZ = z => { let i = 0; while (i < 3 && z > Z_AI[i + 1]) i++; return Math.max(1, lv[i] + (lv[i + 1] - lv[i]) * (z - Z_AI[i]) / (Z_AI[i + 1] - Z_AI[i])); };
    o.sex[sex] = { conf: 'purple', rows: GRID[sex].map(bw => [bw, ...Z_OUT.map(z => atZ(z) * Math.pow(bw / REF[sex], -1 / 3))]),
      note: `Median of ${sourcesUsed(ks.length)} (this move isn't limited by strength alone).`, approx: 'A rough estimate from AI-generated databases.' }; }
  out.push(o); }
const qRatio = {}; for (const o of repInfo) if (o.q.female) (qRatio[o.a] = qRatio[o.a] || []).push(o.q.female / o.q.male);
// V2: women's share of body weight lifted, set so women's median pull-ups and push-ups are the measured share of men's
// (REP_W below, with its sources); every other move with the same anchor takes the same women/men factor.
// Pull-ups 0.41: firefighter trainees, men 12.5 vs women 5.1 (Lockie, Orr & Dawes 2022, IJERPH 19:13918, CC BY); larger
// samples have many women at zero, which this model leaves out (it counts people who can do one). Push-ups 0.55: FBI
// trainees, unpaced max reps, men 37.7-40.6 vs women 19.8-23.6 (Knapik et al. 2011, J Occup Med Toxicol 6:26, CC BY).
const REP_W = { P: { ex: 'Pullups', w: 0.41 }, B: { ex: 'Pushups', w: 0.55 } };
if (V2) { const fac = {};
  for (const [a, t] of Object.entries(REP_W)) { const o = repInfo.find(x => x.e.exercise == t.ex), C = sex => resMed(ANCHOR[a], sex);
    const med = (q, sex) => repLevels(q, C(sex), SIG[sex], REF[sex])[2], goal = t.w * med(o.q.male, 'male');
    let lo = 0.05, hi = 2; for (let i = 0; i < 60; i++) { const m = (lo + hi) / 2; if (med(m, 'female') > goal) lo = m; else hi = m; }
    fac[a] = (lo + hi) / 2 / o.q.male; report.push(`V2 men's ${t.ex}: load ${o.q.male.toFixed(2)} x BW, median ${med(o.q.male, 'male').toFixed(1)}; at the same load women ${med(o.q.male, 'female').toFixed(1)}`); report.push(`V2 women's ${t.ex}: load ${((lo + hi) / 2).toFixed(2)} x BW, ${(fac[a]).toFixed(2)} x men's (median reps ${t.w} x men's)`); }
  fac.O = Math.sqrt(fac.P * fac.B);
  for (const o of repInfo) { o.q.female = o.q.male * fac[o.a]; o.scaled = false; } }
for (const o of repInfo) { if (!o.q.female) { o.q.female = o.q.male * (median(qRatio[o.a] || []) || median(Object.values(qRatio).flat())); o.scaled = true; }
  for (const sex of SEXES) o.sex[sex] = { conf: 'blue', rows: GRID[sex].map(bw => [bw, ...repLevels(o.q[sex], resMed(ANCHOR[o.a], sex) * curve(sex, o.lift, bw), SIG[sex], bw)]),
    note: `Modelled from ${ANCHOR[o.a]} strength (research standard) and the reps-to-load relation (Nuzzo et al. 2024), counting people who can do at least one rep.`,
    approx: 'Estimated with a strength model rather than measured directly.' }; }

// ---- Write the asset ----
const exById = Object.fromEntries(EXS.map(x => [x.n, x]));
// One implement held in both hands: the load is the whole dumbbell or kettlebell, not one of a pair.
// V2: the Tate press uses two dumbbells (its directions), so it's per dumbbell like other dumbbell lifts.
const SINGLE = new RegExp('goblet|pullover|swing|plie|one dumbbell|both hands|single dumbbell|windmill|turkish|halo|around the world|' + (V2 ? '' : 'tate press|') +
  '^standing dumbbell triceps extension|^seated triceps press|^kettlebell overhead triceps extension', 'i');
const r1 = v => Math.round(v * 10) / 10, std = [];
// Not ranked: the sources split into groups far apart, with nothing to tell which is right.
const NOT_RANKED = ['Muscle Up', 'Kipping Muscle Up', 'Calf Press'];
// V2: Dumbbell Shoulder Press and Seated Dumbbell Press are the same seated movement (their directions), so both take
// the geometric mean of their two middle levels.
if (V2) { const a = out.find(o => o.e.exercise == 'Dumbbell Shoulder Press'), b = out.find(o => o.e.exercise == 'Seated Dumbbell Press');
  for (const sex of SEXES) { const x = a && a.sex[sex], y = b && b.sex[sex]; if (!x || !y) continue; const g = Math.sqrt(x.m50 * y.m50);
    for (const t of [x, y]) { const k = g / t.m50; t.m50 = g; t.rows = t.rows.map(r => [r[0], ...r.slice(1).map(v => v * k)]); }
    a.sex[sex].note += ' V2: averaged with Seated Dumbbell Press (the same movement).'; } }
// V2: on machine, cable and isolation lifts, sets over 12 reps count as 12 for the estimated max: rep-based estimates
// are only validated up to about 10 reps (Reynolds et al. 2006, J Strength Cond Res 20:584-592), and reps at a given
// share of the max vary most on machines and small muscles (Nuzzo et al. 2024, CC BY).
const repCap = e => V2 && e.unit != 'reps' && (['machine', 'cable'].includes(e.fam) || ['curl', 'raise', 'extension', 'calf'].includes(e.pat)) ? 12 : null;
for (const o of out) { if (NOT_RANKED.includes(o.e.exercise)) continue; const x = exById[o.e.exercise]; if (!x) continue; /* removed from the exercise list */ const m = o.sex.male, f = o.sex.female; if (!m && !f) continue;
  // Stars can differ by sex (e.g. research on men only): a / ai are men's, af / aif women's when they differ.
  const sm = m || f, sf = f || m, star = s => ({ a: s.approx || null, ai: s.conf == 'purple' }), M = star(sm), F = star(sf);
  std.push({ i: x.i, lift: o.e.exercise, ln: o.e.exercise, m: m ? m.rows.map(r => r.map(r1)) : null, f: f ? f.rows.map(r => r.map(r1)) : null,
    r: o.e.unit == 'reps', d: /^(Dumbbell|Kettlebell)$/.test(x.e) && !SINGLE.test(o.e.exercise), k: 1, ...(repCap(o.e) ? { rc: repCap(o.e) } : {}), ai: M.ai, a: M.a,
    ...(F.a != M.a ? { af: F.a } : {}), ...(F.ai != M.ai ? { aif: F.ai } : {}),
    note: sm.note, ...(sf.note != sm.note ? { nf: sf.note } : {}), conf: { male: m ? m.conf : null, female: f ? f.conf : null } }); }
const grip = EXS.find(x => /Grip_Dynamometer/.test(x.i));
if (grip) std.push({ i: grip.i, lift: 'Grip Dynamometer', ln: 'Hand Grip Dynamometer', m: [[1, ...GRIP.male_lb], [1000, ...GRIP.male_lb]], f: [[1, ...GRIP.female_lb], [1000, ...GRIP.female_lb]],
  r: false, d: false, k: 1, ai: false, a: null, u: true, note: null, conf: { male: 'gold', female: 'gold' } });
std.sort((a, b) => a.lift.localeCompare(b.lift));
for (const s of std) for (const t of [s.m, s.f]) if (t) for (const row of t) for (let i = 2; i <= 5; i++) if (row[i] < row[i - 1]) throw new Error('levels not rising: ' + s.lift);
fs.writeFileSync(path.join(ASSETS, FAVOR ? `standards_${FAVOR}.json` : V2 ? 'standards_v2.json' : 'standards.json'), JSON.stringify(std.map(({ conf, ...s }) => s), null, 0));
if (FAVOR) process.exit(0);   // the variant doesn't rewrite the report

// ---- Report ----
const pc = (a, f) => a && a.length ? quant(a.map(Math.abs), f).toFixed(1) + '%' : '-';
report.unshift('LOADOUT STRENGTH STANDARDS - BUILD REPORT', '');
report.push('', 'Hold-out test (each calibration research lift predicted from the calibrated AI ratios without using it): median | 90th percentile miss');
for (const g of ['all', 'barbell', 'dumbbell', 'cable', 'machine', 'olympic', 'other']) report.push(`  ${g.padEnd(9)} men ${String((loo.male[g] || []).length).padStart(3)}: ${pc(loo.male[g], .5)} | ${pc(loo.male[g], .9)}    women ${String((loo.female[g] || []).length).padStart(3)}: ${pc(loo.female[g], .5)} | ${pc(loo.female[g], .9)}`);
report.push('', 'Source bias vs the research lifts (removed by calibration), men / women: ' + SRC.map(k => `${SN[k]} ${(100 * (Math.exp(BIAS.male[k].all) - 1)).toFixed(1)}% / ${(100 * (Math.exp(BIAS.female[k].all) - 1)).toFixed(1)}%`).join(', '));
report.push('', 'Bodyweight-rep model (men 180 lb / women 140 lb, Beginner..Elite):');
for (const o of repInfo) { const lv = (sex, bw) => o.sex[sex].rows.find(r => r[0] == bw).slice(1).map(Math.round).join('/');
  report.push(`  ${o.e.exercise.padEnd(40)} load ${o.q.male.toFixed(2)} / ${o.q.female.toFixed(2)} x BW${o.scaled ? '*' : ' '}  men ${lv('male', 180).padEnd(15)} women ${lv('female', 140)}`); }
report.push('  * no AI women\'s data: women scaled from men by the median women/men load ratio of the same anchor');
const tally = {}; for (const s of std) for (const sex of SEXES) if (s.conf[sex]) tally[s.conf[sex]] = (tally[s.conf[sex]] || 0) + 1;
report.push('', `Coverage: ${std.length} exercises; exercise x sex by star: ` + Object.entries(tally).map(([k, v]) => `${k} ${v}`).join(', '));
report.push('', 'Corrections to the AI databases:', ...fixLog.map(l => '  ' + l));
fs.writeFileSync(path.join(HERE, V2 ? 'build_report_v2.txt' : 'build_report.txt'), report.join('\n') + '\n');
console.log(report.slice(0, 40).join('\n'));
