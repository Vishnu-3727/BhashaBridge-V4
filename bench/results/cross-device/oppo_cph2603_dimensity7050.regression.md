# Regression vs baseline

**Overall: REGRESSED**  (5 improved, 5 regressed)

| Metric | Baseline | Current | Delta | Verdict |
|---|---|---|---|---|
| startup.cold.engineInitMs | 6936 | 4818 | -30.5% | FASTER |
| startup.warm.engineInitMs | 1953 | 1360 | -30.4% | FASTER |
| startup.hot.engineInitMs | 1905 | 1193 | -37.4% | FASTER |
| startup.cold.modelLoadMs | 5392 | 3913 | -27.4% | FASTER |
| startup.warm.tokenizerMs | 552 | 460 | -16.7% | FASTER |
| inference.firstTranslationMs | 265 | 330 | +24.5% | SLOWER |
| inference.median | 251 | 243 | -3.2% | = |
| inference.p95 | 267 | 398 | +49.1% | SLOWER |
| inference.p99 | 272 | 421 | +54.8% | SLOWER |
| inference.stdev | 94.83 | 124.07 | +30.8% | SLOWER |
| inference.tokensPerSec | 177.5 | 151.86 | -14.4% | SLOWER |
| memory.totalPssKb | 659268 | 657236 | -0.3% | = |
| memory.nativeHeapKb | 542820 | 531160 | -2.1% | = |
| memory.rssKb | 739180 | 712428 | -3.6% | = |
| cache.ortBytes | 472948568 | 472948560 | -0.0% | = |
| thermal.batteryTempC | 35 | 34 | -2.9% | = |
