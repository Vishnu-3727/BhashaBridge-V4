# Regression vs baseline

**Overall: REGRESSED**  (6 improved, 9 regressed)

| Metric | Baseline | Current | Delta | Verdict |
|---|---|---|---|---|
| startup.cold.engineInitMs | 4430 | 16205 | +265.8% | STARTUP-REGRESSION |
| startup.warm.engineInitMs | 1275 | 1063 | -16.6% | FASTER |
| startup.hot.engineInitMs | 1242 | 1136 | -8.5% | FASTER |
| startup.cold.modelLoadMs | 3587 | 15346 | +327.8% | STARTUP-REGRESSION |
| startup.warm.tokenizerMs | 436 | 393 | -9.9% | FASTER |
| inference.firstTranslationMs | 242 | 181 | -25.2% | FASTER |
| inference.median | 229 | 176 | -23.1% | FASTER |
| inference.p95 | 237 | 332 | +40.1% | SLOWER |
| inference.p99 | 238 | 390 | +63.9% | SLOWER |
| inference.stdev | 86.851 | 99.434 | +14.5% | SLOWER |
| inference.tokensPerSec | 200.498 | 211.822 | +5.6% | FASTER |
| memory.totalPssKb | 165792 | 231368 | +39.6% | MEM+ |
| memory.nativeHeapKb | 61996 | 113520 | +83.1% | MEM+ |
| memory.rssKb | 220872 | 313584 | +42.0% | MEM+ |
| cache.ortBytes | 472948560 | 472948568 | +0.0% | = |
| thermal.batteryTempC | 31 | 40.7 | +31.3% | THERMAL+ |
