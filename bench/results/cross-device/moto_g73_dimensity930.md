# Benchmark report - moto g73 5G

- **CPU:** ARMv8.2 cores=8(perf=2[6, 7],eff=6[0, 1, 2, 3, 4, 5]) neon=true fp16=true dotprod=true i8mm=false sve=false sve2=false sme=false sme2=false
- **Policy:** arm-adaptive(threads=1) intra=1 arena=false affinity=OFF
- **Android SDK:** 34   ABI: arm64-v8a
- **Timestamp:** 1784823717771

## Metrics

| Group | Metric | Value | Unit |
|---|---|---|---|
| startup | cold.engineInitMs | 6936 | ms |
| startup | warm.engineInitMs | 1953 | ms |
| startup | hot.engineInitMs | 1905 | ms |
| startup | cold.modelLoadMs | 5392 | ms |
| startup | warm.tokenizerMs | 552 | ms |
| inference | firstTranslationMs | 265 | ms |
| inference | median | 251 | ms |
| inference | p95 | 267 | ms |
| inference | p99 | 272 | ms |
| inference | stdev | 94.83 | ms |
| inference | tokensPerSec | 177.5 | tok/s |
| memory | totalPssKb | 659268 | KB |
| memory | nativeHeapKb | 542820 | KB |
| memory | rssKb | 739180 | KB |
| storage | ortBytes | 472948568 | B |
| thermal | batteryTempC | 35 | C |

## Unavailable on this device (unrooted)

```
battery.energyCounter
```
