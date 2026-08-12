> **SUPERSEDED 2026-08-12 — kept as the historical entry #9 baseline; do not quote it as current.**
> This run predates two shipping changes to the very policy it records: the intra-op clamp (`intra=4`
> here, `intra=2` now — §3.21/§3.38) and disabling KleidiAI on SME parts (§3.40). The current entry
> for this device is `s26ultra_sd8elitegen5_2026-08-12.md` — **median 77 ms, 573.7 tok/s, 153.7 MB
> PSS**, against this file's 98 ms / 424.9 / 185.2 MB. Diff:
> `s26ultra_sd8elitegen5_2026-08-12.regression.md` (12 improved, 0 regressed).

# Benchmark report - SM-S948B

- **CPU:** ARMv9 cores=8(perf=8[0, 1, 2, 3, 4, 5, 6, 7],eff=0[]) neon=true fp16=true dotprod=true i8mm=true sve=true sve2=true sme=true sme2=false
- **Policy:** arm-adaptive(threads=4) intra=4 arena=false affinity=OFF
- **Android SDK:** 36   ABI: arm64-v8a
- **Timestamp:** 1785483828878

## Metrics

| Group | Metric | Value | Unit |
|---|---|---|---|
| startup | cold.engineInitMs | 2472 | ms |
| startup | warm.engineInitMs | 923 | ms |
| startup | hot.engineInitMs | 928 | ms |
| startup | cold.modelLoadMs | 1916 | ms |
| startup | warm.tokenizerMs | 291 | ms |
| inference | firstTranslationMs | 110 | ms |
| inference | median | 99 | ms |
| inference | p95 | 113 | ms |
| inference | p99 | 117 | ms |
| inference | stdev | 37.628 | ms |
| inference | tokensPerSec | 412.768 | tok/s |
| memory | totalPssKb | 178109 | KB |
| memory | nativeHeapKb | 74548 | KB |
| memory | rssKb | 256712 | KB |
| storage | ortBytes | 472948560 | B |
| thermal | batteryTempC | 30 | C |

## Unavailable on this device (unrooted)

```
battery.energyCounter
```
