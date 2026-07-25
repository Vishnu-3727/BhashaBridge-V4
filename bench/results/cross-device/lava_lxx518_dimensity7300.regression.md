# Regression vs baseline

**Overall: REGRESSED**  (6 improved, 3 regressed)

| Metric | Baseline | Current | Delta | Verdict |
|---|---|---|---|---|
| startup.cold.engineInitMs | 4430 | 3904 | -11.9% | FASTER |
| startup.warm.engineInitMs | 1275 | 1239 | -2.8% | = |
| startup.hot.engineInitMs | 1242 | 1175 | -5.4% | FASTER |
| startup.cold.modelLoadMs | 3587 | 3105 | -13.4% | FASTER |
| startup.warm.tokenizerMs | 436 | 415 | -4.8% | = |
| inference.firstTranslationMs | 242 | 227 | -6.2% | FASTER |
| inference.median | 229 | 212 | -7.4% | FASTER |
| inference.p95 | 237 | 233 | -1.7% | = |
| inference.p99 | 238 | 248 | +4.2% | = |
| inference.stdev | 86.851 | 82.296 | -5.2% | FASTER |
| inference.tokensPerSec | 200.498 | 207.474 | +3.5% | = |
| memory.totalPssKb | 165792 | 188248 | +13.5% | MEM+ |
| memory.nativeHeapKb | 61996 | 63576 | +2.5% | = |
| memory.rssKb | 220872 | 258276 | +16.9% | MEM+ |
| cache.ortBytes | 472948560 | 472948568 | +0.0% | = |
| thermal.batteryTempC | 31 | 35 | +12.9% | THERMAL+ |
