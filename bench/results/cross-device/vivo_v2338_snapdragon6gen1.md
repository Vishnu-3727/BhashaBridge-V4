# Benchmark report - V2338

- **CPU:** ARMv8.2 cores=8(perf=4[4, 5, 6, 7],eff=4[0, 1, 2, 3]) neon=true fp16=true dotprod=true i8mm=false sve=false sve2=false sme=false sme2=false
- **Policy:** arm-adaptive(threads=2,affinity) intra=2 arena=false affinity=5,6,7,8
- **Android SDK:** 36   ABI: arm64-v8a
- **Timestamp:** 1784867808058

## Metrics

| Group | Metric | Value | Unit |
|---|---|---|---|
| startup | cold.engineInitMs | 7960 | ms |
| startup | warm.engineInitMs | 877843 | ms |
| startup | hot.engineInitMs | 2458 | ms |
| startup | cold.modelLoadMs | 6923 | ms |
| startup | warm.tokenizerMs | 571 | ms |
| inference | firstTranslationMs | 256 | ms |
| inference | median | 241 | ms |
| inference | p95 | 254 | ms |
| inference | p99 | 255 | ms |
| inference | stdev | 91.596 | ms |
| inference | tokensPerSec | 186.772 | tok/s |
| memory | totalPssKb | 714421 | KB |
| memory | nativeHeapKb | 554248 | KB |
| memory | rssKb | 777656 | KB |
| storage | ortBytes | 472948576 | B |
| thermal | batteryTempC | 34 | C |

## Unavailable on this device (unrooted)

```
battery.energyCounter
```
