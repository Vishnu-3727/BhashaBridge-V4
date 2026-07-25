# Benchmark report - SM-S908E

- **CPU:** ARMv8.6 cores=8(perf=1[7],eff=7[0, 1, 2, 3, 4, 5, 6]) neon=true fp16=true dotprod=true i8mm=true sve=false sve2=false sme=false sme2=false
- **Policy:** arm-adaptive(threads=1) intra=1 arena=false affinity=OFF
- **Android SDK:** 36   ABI: arm64-v8a
- **Timestamp:** 1784868782448

## Metrics

| Group | Metric | Value | Unit |
|---|---|---|---|
| startup | cold.engineInitMs | 16205 | ms |
| startup | warm.engineInitMs | 1063 | ms |
| startup | hot.engineInitMs | 1136 | ms |
| startup | cold.modelLoadMs | 15346 | ms |
| startup | warm.tokenizerMs | 393 | ms |
| inference | firstTranslationMs | 181 | ms |
| inference | median | 176 | ms |
| inference | p95 | 332 | ms |
| inference | p99 | 390 | ms |
| inference | stdev | 99.434 | ms |
| inference | tokensPerSec | 211.822 | tok/s |
| memory | totalPssKb | 231368 | KB |
| memory | nativeHeapKb | 113520 | KB |
| memory | rssKb | 313584 | KB |
| storage | ortBytes | 472948568 | B |
| thermal | batteryTempC | 40.7 | C |

## Unavailable on this device (unrooted)

```
battery.energyCounter
```
