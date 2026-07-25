# Regression vs baseline

**Overall: REGRESSED**  (7 improved, 6 regressed)

| Metric | Baseline | Current | Delta | Verdict |
|---|---|---|---|---|
| startup.cold.engineInitMs | 6936 | 6118 | -11.8% | FASTER |
| startup.warm.engineInitMs | 1953 | 1822 | -6.7% | FASTER |
| startup.hot.engineInitMs | 1905 | 1487 | -21.9% | FASTER |
| startup.cold.modelLoadMs | 5392 | 5154 | -4.4% | = |
| startup.warm.tokenizerMs | 552 | 514 | -6.9% | FASTER |
| inference.firstTranslationMs | 265 | 327 | +23.4% | SLOWER |
| inference.median | 251 | 288 | +14.7% | SLOWER |
| inference.p95 | 267 | 327 | +22.5% | SLOWER |
| inference.p99 | 272 | 336 | +23.5% | SLOWER |
| inference.stdev | 94.83 | 116.49 | +22.8% | SLOWER |
| inference.tokensPerSec | 177.5 | 152.397 | -14.1% | SLOWER |
| memory.totalPssKb | 659268 | 566992 | -14.0% | MEM- |
| memory.nativeHeapKb | 542820 | 480320 | -11.5% | MEM- |
| memory.rssKb | 739180 | 615968 | -16.7% | MEM- |
| cache.ortBytes | 472948568 | 472948560 | -0.0% | = |
| thermal.batteryTempC | 35 | 33.6 | -4.0% | = |
