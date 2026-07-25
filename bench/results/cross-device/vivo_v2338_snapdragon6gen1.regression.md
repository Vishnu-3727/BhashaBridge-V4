# Regression vs baseline

**Overall: REGRESSED**  (0 improved, 15 regressed)

| Metric | Baseline | Current | Delta | Verdict |
|---|---|---|---|---|
| startup.cold.engineInitMs | 4430 | 7960 | +79.7% | STARTUP-REGRESSION |
| startup.warm.engineInitMs | 1275 | 877843 | +68750.4% | STARTUP-REGRESSION |
| startup.hot.engineInitMs | 1242 | 2458 | +97.9% | STARTUP-REGRESSION |
| startup.cold.modelLoadMs | 3587 | 6923 | +93.0% | STARTUP-REGRESSION |
| startup.warm.tokenizerMs | 436 | 571 | +31.0% | STARTUP-REGRESSION |
| inference.firstTranslationMs | 242 | 256 | +5.8% | SLOWER |
| inference.median | 229 | 241 | +5.2% | SLOWER |
| inference.p95 | 237 | 254 | +7.2% | SLOWER |
| inference.p99 | 238 | 255 | +7.1% | SLOWER |
| inference.stdev | 86.851 | 91.596 | +5.5% | SLOWER |
| inference.tokensPerSec | 200.498 | 186.772 | -6.8% | SLOWER |
| memory.totalPssKb | 165792 | 714421 | +330.9% | MEM+ |
| memory.nativeHeapKb | 61996 | 554248 | +794.0% | MEM+ |
| memory.rssKb | 220872 | 777656 | +252.1% | MEM+ |
| cache.ortBytes | 472948560 | 472948576 | +0.0% | = |
| thermal.batteryTempC | 31 | 34 | +9.7% | THERMAL+ |
