# Benchmark report - LXX518

- **CPU:** ARMv8.2 cores=8(perf=4[4, 5, 6, 7],eff=4[0, 1, 2, 3]) neon=true fp16=true dotprod=true i8mm=false sve=false sve2=false sme=false sme2=false
- **Policy:** arm-adaptive(threads=2,affinity) intra=2 arena=false affinity=5,6,7,8
- **Android SDK:** 36   ABI: arm64-v8a
- **Timestamp:** 1784865135610

## Metrics

| Group | Metric | Value | Unit |
|---|---|---|---|
| startup | cold.engineInitMs | 3904 | ms |
| startup | warm.engineInitMs | 1239 | ms |
| startup | hot.engineInitMs | 1175 | ms |
| startup | cold.modelLoadMs | 3105 | ms |
| startup | warm.tokenizerMs | 415 | ms |
| inference | firstTranslationMs | 227 | ms |
| inference | median | 212 | ms |
| inference | p95 | 233 | ms |
| inference | p99 | 248 | ms |
| inference | stdev | 82.296 | ms |
| inference | tokensPerSec | 207.474 | tok/s |
| memory | totalPssKb | 188248 | KB |
| memory | nativeHeapKb | 63576 | KB |
| memory | rssKb | 258276 | KB |
| storage | ortBytes | 472948568 | B |
| thermal | batteryTempC | 35 | C |

## Unavailable on this device (unrooted)

```
battery.energyCounter, battery.currentNow
```
