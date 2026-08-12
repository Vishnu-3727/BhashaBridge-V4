# Regression vs baseline

**Overall: PASS**  (12 improved, 0 regressed)

| Metric | Baseline | Current | Delta | Verdict |
|---|---|---|---|---|
| startup.cold.engineInitMs | 2348 | 2091 | -10.9% | FASTER |
| startup.warm.engineInitMs | 912 | 644 | -29.4% | FASTER |
| startup.hot.engineInitMs | 909 | 632 | -30.5% | FASTER |
| startup.cold.modelLoadMs | 1816 | 1695 | -6.7% | FASTER |
| startup.warm.tokenizerMs | 296 | 224 | -24.3% | FASTER |
| inference.firstTranslationMs | 107 | 82 | -23.4% | FASTER |
| inference.median | 98 | 77 | -21.4% | FASTER |
| inference.p95 | 107 | 81 | -24.3% | FASTER |
| inference.p99 | 112 | 82 | -26.8% | FASTER |
| inference.stdev | 36.267 | 29.054 | -19.9% | FASTER |
| inference.tokensPerSec | 424.921 | 573.728 | +35.0% | FASTER |
| memory.totalPssKb | 185216 | 153746 | -17.0% | MEM- |
| memory.nativeHeapKb | 71596 | 70972 | -0.9% | = |
| memory.rssKb | 266032 | 254572 | -4.3% | = |
| cache.ortBytes | 472948568 | 472948560 | -0.0% | = |
| thermal.batteryTempC | 35.1 | 29.6 | -15.7% | = |
