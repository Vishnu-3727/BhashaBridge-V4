# Regression vs baseline

**Overall: REGRESSED**  (11 improved, 2 regressed)

| Metric | Baseline | Current | Delta | Verdict |
|---|---|---|---|---|
| startup.cold.engineInitMs | 17197 | 6936 | -59.7% | FASTER |
| startup.warm.engineInitMs | 3687 | 1953 | -47.0% | FASTER |
| startup.hot.engineInitMs | 6424 | 1905 | -70.3% | FASTER |
| startup.cold.modelLoadMs | 14099 | 5392 | -61.8% | FASTER |
| startup.warm.tokenizerMs | 1259 | 552 | -56.2% | FASTER |
| inference.firstTranslationMs | 1572 | 265 | -83.1% | FASTER |
| inference.median | 738 | 251 | -66.0% | FASTER |
| inference.p95 | 1060 | 267 | -74.8% | FASTER |
| inference.p99 | 1120 | 272 | -75.7% | FASTER |
| inference.stdev | 343.779 | 94.83 | -72.4% | FASTER |
| inference.tokensPerSec | 50.33 | 177.5 | +252.7% | FASTER |
| memory.totalPssKb | 617338 | 659268 | +6.8% | MEM+ |
| memory.nativeHeapKb | 519980 | 542820 | +4.4% | = |
| memory.rssKb | 674372 | 739180 | +9.6% | MEM+ |
| cache.ortBytes | 472948560 | 472948568 | +0.0% | = |
| thermal.batteryTempC | 33.1 | 35 | +5.7% | = |
