"""Fits how much the fusion trusts each evidence source, from real labels.

    ./gradlew :mltools:test --tests '*PhoneLike*'          # writes mltools/build/fusion.csv
    LR_NO_FULL=1 TAG=-nofull ./gradlew ...                 # intact labels too (recovery forced)
    python training/fit_fusion.py mltools/build/fusion*.csv

Model (per printed position): score(d) = sum over sources s of w_s * log(floor_s(p_s(d))), with
floor_s(p) = eps_s/10 + (1 - eps_s) p as in Fusion.fuse. Today every w_s is 1. This fits the
weights (one per source, plus a separate OCR weight where a dead line crosses the digit) by
maximum likelihood of the true digit, and checks them leave-one-label-out: each label is scored
with weights fitted on the others only, so a gain isn't just memorising 20 labels.
"""
import csv
import math
import sys
from collections import defaultdict

EPS = {"OCR": 0.08, "OCR2": 0.08, "DIGIT_MODEL": 0.25, "BARCODE_PARTIAL": 0.03, "SELF_TEMPLATE": 0.2}
PARAMS = ["OCR", "OCR2", "DIGIT_MODEL", "BARCODE_PARTIAL", "SELF_TEMPLATE"]


def load(paths):
    rows = defaultdict(dict)  # (file, pos) -> {source: probs}, plus truth
    truth = {}
    for path in paths:
        tag = path.split("fusion")[-1]
        for r in csv.DictReader(open(path)):
            key = (r["file"] + tag, int(r["pos"]))
            rows[key][r["source"]] = [float(r[f"p{d}"]) for d in range(10)]
            truth[key] = int(r["truth"])
    return [(k[0], rows[k], truth[k]) for k in rows]


def nll(data, w):
    tot = 0.0
    for _, srcs, t in data:
        sc = [0.0] * 10
        for s, p in srcs.items():
            if s not in w:
                continue
            e = EPS[s]
            for d in range(10):
                sc[d] += w[s] * math.log(e / 10 + (1 - e) * p[d])
        m = max(sc)
        z = m + math.log(sum(math.exp(x - m) for x in sc))
        tot += z - sc[t]
    return tot / max(1, len(data))


def acc(data, w):
    ok = 0
    for _, srcs, t in data:
        sc = [0.0] * 10
        for s, p in srcs.items():
            if s not in w:
                continue
            e = EPS[s]
            for d in range(10):
                sc[d] += w[s] * math.log(e / 10 + (1 - e) * p[d])
        ok += max(range(10), key=lambda d: sc[d]) == t
    return ok / max(1, len(data))


def fit(data, reg=0.5):
    """Coordinate search on the weights, with a pull towards today's 1.0 (few labels)."""
    w = {s: 1.0 for s in PARAMS}
    def obj(w):
        return nll(data, w) + reg * sum((v - 1.0) ** 2 for v in w.values()) / max(1, len(data)) * 10
    best = obj(w)
    step = 0.5
    while step > 0.02:
        improved = False
        for s in PARAMS:
            for d in (+step, -step):
                c = dict(w); c[s] = max(0.0, c[s] + d)
                v = obj(c)
                if v < best - 1e-9:
                    best, w, improved = v, c, True
        if not improved:
            step /= 2
    return w


def main():
    data = load(sys.argv[1:])
    labels = sorted({k for k, _, _ in data})
    base = {s: 1.0 for s in PARAMS}
    print(f"{len(data)} positions from {len(labels)} labels")
    print(f"today's weights: NLL {nll(data, base):.3f}, digit accuracy {acc(data, base):.3f}")
    w = fit(data)
    print("fitted on all:", {k: round(v, 2) for k, v in w.items()}, f"NLL {nll(data, w):.3f}, acc {acc(data, w):.3f}")
    # Leave one label out.
    tb = tf = 0.0; ab = af = 0; n = 0
    for lab in labels:
        test = [x for x in data if x[0] == lab]
        train = [x for x in data if x[0] != lab]
        wl = fit(train)
        tb += nll(test, base) * len(test); tf += nll(test, wl) * len(test)
        ab += acc(test, base) * len(test); af += acc(test, wl) * len(test); n += len(test)
        print(f"  held out {lab:40s} today NLL {nll(test, base):.3f} acc {acc(test, base):.2f} | fitted NLL {nll(test, wl):.3f} acc {acc(test, wl):.2f}")
    print(f"leave-one-label-out: today NLL {tb / n:.3f} acc {ab / n:.3f} | fitted NLL {tf / n:.3f} acc {af / n:.3f}")


if __name__ == "__main__":
    main()
