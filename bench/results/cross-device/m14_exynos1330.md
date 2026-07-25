# Benchmark report - SM-E146B

- **CPU:** ARMv8.2 cores=8(perf=2[6, 7],eff=6[0, 1, 2, 3, 4, 5]) neon=true fp16=true dotprod=true i8mm=false sve=false sve2=false sme=false sme2=false
- **Policy:** arm-adaptive(threads=1) intra=1 arena=false affinity=OFF
- **Android SDK:** 35   ABI: arm64-v8a
- **Timestamp:** 1784824569467

## Metrics

| Group | Metric | Value | Unit |
|---|---|---|---|
| startup | cold.engineInitMs | 6118 | ms |
| startup | warm.engineInitMs | 1822 | ms |
| startup | hot.engineInitMs | 1487 | ms |
| startup | cold.modelLoadMs | 5154 | ms |
| startup | warm.tokenizerMs | 514 | ms |
| inference | firstTranslationMs | 327 | ms |
| inference | median | 288 | ms |
| inference | p95 | 327 | ms |
| inference | p99 | 336 | ms |
| inference | stdev | 116.49 | ms |
| inference | tokensPerSec | 152.397 | tok/s |
| memory | totalPssKb | 566992 | KB |
| memory | nativeHeapKb | 480320 | KB |
| memory | rssKb | 615968 | KB |
| storage | ortBytes | 472948560 | B |
| thermal | batteryTempC | 33.6 | C |

## Unavailable on this device (unrooted)

```
battery.energyCounter
```
