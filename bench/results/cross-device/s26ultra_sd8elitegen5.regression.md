# Regression vs baseline

**Overall: PASS**  (14 improved, 0 regressed)

| Metric | Baseline | Current | Delta | Verdict |
|---|---|---|---|---|
| startup.cold.engineInitMs | 17197 | 2472 | -85.6% | FASTER |
| startup.warm.engineInitMs | 3687 | 923 | -75.0% | FASTER |
| startup.hot.engineInitMs | 6424 | 928 | -85.6% | FASTER |
| startup.cold.modelLoadMs | 14099 | 1916 | -86.4% | FASTER |
| startup.warm.tokenizerMs | 1259 | 291 | -76.9% | FASTER |
| inference.firstTranslationMs | 1572 | 110 | -93.0% | FASTER |
| inference.median | 738 | 99 | -86.6% | FASTER |
| inference.p95 | 1060 | 113 | -89.3% | FASTER |
| inference.p99 | 1120 | 117 | -89.6% | FASTER |
| inference.stdev | 343.779 | 37.628 | -89.1% | FASTER |
| inference.tokensPerSec | 50.33 | 412.768 | +720.1% | FASTER |
| memory.totalPssKb | 617338 | 178109 | -71.1% | MEM- |
| memory.nativeHeapKb | 519980 | 74548 | -85.7% | MEM- |
| memory.rssKb | 674372 | 256712 | -61.9% | MEM- |
| cache.ortBytes | 472948560 | 472948560 | +0.0% | = |
| thermal.batteryTempC | 33.1 | 30 | -9.4% | = |
