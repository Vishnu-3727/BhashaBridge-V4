# Benchmark report - A015

- **CPU:** ARMv8.2 cores=8(perf=4[4, 5, 6, 7],eff=4[0, 1, 2, 3]) neon=true fp16=true dotprod=true i8mm=false sve=false sve2=false sme=false sme2=false
- **Policy:** arm-adaptive(threads=2,affinity) intra=2 arena=false affinity=5,6,7,8
- **Android SDK:** 36   ABI: arm64-v8a
- **Timestamp:** 1784864169451

## Metrics

| Group | Metric | Value | Unit |
|---|---|---|---|
| startup | cold.engineInitMs | 4430 | ms |
| startup | warm.engineInitMs | 1275 | ms |
| startup | hot.engineInitMs | 1242 | ms |
| startup | cold.modelLoadMs | 3587 | ms |
| startup | warm.tokenizerMs | 436 | ms |
| inference | firstTranslationMs | 242 | ms |
| inference | median | 229 | ms |
| inference | p95 | 237 | ms |
| inference | p99 | 238 | ms |
| inference | stdev | 86.851 | ms |
| inference | tokensPerSec | 200.498 | tok/s |
| memory | totalPssKb | 165792 | KB |
| memory | nativeHeapKb | 61996 | KB |
| memory | rssKb | 220872 | KB |
| storage | ortBytes | 472948560 | B |
| thermal | batteryTempC | 31 | C |

## Unavailable on this device (unrooted)

```
battery.energyCounter
```
