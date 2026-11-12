"""Benchmark readaloud_backend.extract_article on the Scrapinghub
article-extraction-benchmark (181 pages with ground truth).
Usage: python tools/bench_extractor.py /path/to/article-extraction-benchmark [--trafilatura]"""
import gzip, json, re, sys, time
from collections import Counter
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app/src/main/python"))
import readaloud_backend as rb

def tokens(s): return re.findall(r"\w+", (s or "").lower())
def prf(ref, hyp):
    r, h = Counter(tokens(ref)), Counter(tokens(hyp))
    tp = sum((r & h).values())
    p = tp / max(1, sum(h.values())); rc = tp / max(1, sum(r.values()))
    return p, rc, (2 * p * rc / (p + rc) if p + rc else 0)

root = Path(sys.argv[1]); gt = json.load(open(root / "ground-truth.json"))
use_traf = "--trafilatura" in sys.argv
if use_traf:
    import trafilatura
res = []; t0 = time.time()
for key, item in gt.items():
    raw = gzip.decompress((root / "html" / f"{key}.html.gz").read_bytes())
    if use_traf:
        hyp = trafilatura.extract(raw.decode("utf8", "replace")) or ""
    else:
        _, hyp = rb.extract_article(raw, item.get("url", ""))
    res.append(prf(item["articleBody"], hyp) + (key,))
n = len(res)
P = sum(r[0] for r in res) / n; R = sum(r[1] for r in res) / n; F = sum(r[2] for r in res) / n
print(f"{'trafilatura' if use_traf else 'readaloud'}: precision {P:.3f} recall {R:.3f} F1 {F:.3f} "
      f"| F1<0.5 on {sum(r[2] < 0.5 for r in res)}/{n} pages | {time.time()-t0:.1f}s")
if "-v" in sys.argv:
    for r in sorted(res, key=lambda r: r[2])[:12]: print(f"  F1 {r[2]:.2f} P {r[0]:.2f} R {r[1]:.2f} {r[3][:12]} {gt[r[3]]['url'][:70]}")
