# Regression vs baseline

**Overall: PASS**  (14 improved, 0 regressed)

| Metric | Baseline | Current | Delta | Verdict |
|---|---|---|---|---|
| startup.cold.engineInitMs | 6936 | 4430 | -36.1% | FASTER |
| startup.warm.engineInitMs | 1953 | 1275 | -34.7% | FASTER |
| startup.hot.engineInitMs | 1905 | 1242 | -34.8% | FASTER |
| startup.cold.modelLoadMs | 5392 | 3587 | -33.5% | FASTER |
| startup.warm.tokenizerMs | 552 | 436 | -21.0% | FASTER |
| inference.firstTranslationMs | 265 | 242 | -8.7% | FASTER |
| inference.median | 251 | 229 | -8.8% | FASTER |
| inference.p95 | 267 | 237 | -11.2% | FASTER |
| inference.p99 | 272 | 238 | -12.5% | FASTER |
| inference.stdev | 94.83 | 86.851 | -8.4% | FASTER |
| inference.tokensPerSec | 177.5 | 200.498 | +13.0% | FASTER |
| memory.totalPssKb | 659268 | 165792 | -74.9% | MEM- |
| memory.nativeHeapKb | 542820 | 61996 | -88.6% | MEM- |
| memory.rssKb | 739180 | 220872 | -70.1% | MEM- |
| cache.ortBytes | 472948568 | 472948560 | -0.0% | = |
| thermal.batteryTempC | 35 | 31 | -11.4% | = |
