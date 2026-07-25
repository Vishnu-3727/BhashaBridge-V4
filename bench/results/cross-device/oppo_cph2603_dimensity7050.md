# Benchmark report - CPH2603

- **CPU:** ARMv8.2 cores=8(perf=2[6, 7],eff=6[0, 1, 2, 3, 4, 5]) neon=true fp16=true dotprod=true i8mm=false sve=false sve2=false sme=false sme2=false
- **Policy:** arm-adaptive(threads=1) intra=1 arena=false affinity=OFF
- **Android SDK:** 36   ABI: arm64-v8a
- **Timestamp:** 1784866276024

## Metrics

| Group | Metric | Value | Unit |
|---|---|---|---|
| startup | cold.engineInitMs | 4818 | ms |
| startup | warm.engineInitMs | 1360 | ms |
| startup | hot.engineInitMs | 1193 | ms |
| startup | cold.modelLoadMs | 3913 | ms |
| startup | warm.tokenizerMs | 460 | ms |
| inference | firstTranslationMs | 330 | ms |
| inference | median | 243 | ms |
| inference | p95 | 398 | ms |
| inference | p99 | 421 | ms |
| inference | stdev | 124.07 | ms |
| inference | tokensPerSec | 151.86 | tok/s |
| memory | totalPssKb | 657236 | KB |
| memory | nativeHeapKb | 531160 | KB |
| memory | rssKb | 712428 | KB |
| storage | ortBytes | 472948560 | B |
| thermal | batteryTempC | 34 | C |

## Unavailable on this device (unrooted)

```
battery.energyCounter
```
