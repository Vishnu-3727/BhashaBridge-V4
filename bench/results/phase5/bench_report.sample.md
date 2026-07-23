# Benchmark report - SM-M315F

- **CPU:** ARMv8.0 cores=8(perf=4[4, 5, 6, 7],eff=4[0, 1, 2, 3]) neon=true fp16=false dotprod=false i8mm=false sve=false sve2=false sme=false sme2=false
- **Policy:** arm-adaptive(threads=2,affinity) intra=2 arena=false affinity=5,6,7,8
- **Android SDK:** 31   ABI: arm64-v8a
- **Timestamp:** 1784794019878

## Metrics

| Group | Metric | Value | Unit |
|---|---|---|---|
| startup | cold.engineInitMs | 17197 | ms |
| startup | warm.engineInitMs | 3687 | ms |
| startup | hot.engineInitMs | 6424 | ms |
| startup | cold.modelLoadMs | 14099 | ms |
| startup | warm.tokenizerMs | 1259 | ms |
| inference | firstTranslationMs | 1572 | ms |
| inference | median | 738 | ms |
| inference | p95 | 1060 | ms |
| inference | p99 | 1120 | ms |
| inference | stdev | 343.779 | ms |
| inference | tokensPerSec | 50.33 | tok/s |
| memory | totalPssKb | 617338 | KB |
| memory | nativeHeapKb | 519980 | KB |
| memory | rssKb | 674372 | KB |
| storage | ortBytes | 472948560 | B |
| thermal | batteryTempC | 33.1 | C |

## Unavailable on this device (unrooted)

```
battery.energyCounter, battery.currentNow
```
