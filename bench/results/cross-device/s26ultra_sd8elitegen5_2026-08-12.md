# Benchmark report - SM-S948B

- **CPU:** ARMv9 cores=8(perf=8[0, 1, 2, 3, 4, 5, 6, 7],eff=0[]) neon=true fp16=true dotprod=true i8mm=true sve=true sve2=true sme=true sme2=false
- **Policy:** arm-adaptive(threads=2,noKleidiAI) intra=2 arena=false affinity=OFF
- **Android SDK:** 36   ABI: arm64-v8a
- **Timestamp:** 1786515953589

## Metrics

| Group | Metric | Value | Unit |
|---|---|---|---|
| startup | cold.engineInitMs | 2091 | ms |
| startup | warm.engineInitMs | 644 | ms |
| startup | hot.engineInitMs | 632 | ms |
| startup | cold.modelLoadMs | 1695 | ms |
| startup | warm.tokenizerMs | 224 | ms |
| inference | firstTranslationMs | 82 | ms |
| inference | median | 77 | ms |
| inference | p95 | 81 | ms |
| inference | p99 | 82 | ms |
| inference | stdev | 29.054 | ms |
| inference | tokensPerSec | 573.728 | tok/s |
| memory | totalPssKb | 153746 | KB |
| memory | nativeHeapKb | 70972 | KB |
| memory | rssKb | 254572 | KB |
| storage | ortBytes | 472948560 | B |
| thermal | batteryTempC | 29.6 | C |

## Unavailable on this device (unrooted)

```
battery.energyCounter
```
