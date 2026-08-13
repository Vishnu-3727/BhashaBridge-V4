# Translation quality — INT8 against its FP32 reference

**The question this answers.** Every quality claim in this repository until now was *parity*:
`verify_cache.py --atol 1.0` passing 7/7 with "greedy tokens identical", and a handful of sentences
eyeballed on a phone. Parity proves the export did not break. It does not prove the model still
translates, and §3.6's INT8 conversion has never been scored against a corpus. This document scores it.

**The result in one line:** INT8 is **quality-neutral** against FP32 — the deltas are ±0.5 BLEU with
opposite signs in the two directions, which is quantization noise, not degradation.

And a finding that matters more than the scores: **exact token parity on real sentences is ~50%, not
100%.** The old gate's "tokens identical" claim held only for its short synthetic inputs.

---

## 1. Systems under test

| | EN→HI | HI→EN |
|---|---|---|
| Checkpoint | `ai4bharat/indictrans2-en-indic-dist-200M` | `ai4bharat/indictrans2-indic-en-dist-200M` |
| **FP32 reference** | `model_pipeline/onnx_cached/` (1.87 GB) | `model_pipeline/onnx_cached_hi_en/` |
| **INT8, as shipped** | `app/src/main/assets/*_int8.onnx` + `weights.bin` | `app/src/main/assets/hi_en_*_int8.onnx` + `hi_en_weights.bin` |
| Quantization | ORT `quantize_dynamic`, `QuantType.QInt8`, **no calibration** — 145 of 217 `MatMul` → `MatMulInteger`, KV cache stays float | same pipeline |
| Graph shape | `encoder` + `decoder_init` + `decoder_step`, 72 cache tensors (18 layers × 4), opset 14 | same |
| Vocabulary | `dict.SRC.json` / `dict.TGT.json` | `dict.SRC_HI.json` / `dict.TGT_EN.json` |
| Runtime | onnxruntime 1.27.0, CPU EP, `intra=4`, arena off, `ORT_ENABLE_ALL` | same |

Both precisions ran **the same graphs the app runs** — the INT8 side is the shipping asset with its
shared weight blob, not a lab copy. Provenance is §3.30 (content-addressed blob, output bit-identical)
and Phase 12's R-PROV closure.

## 2. Corpus — and why it is not FLORES

**FLORES-200 was the intended corpus and is not obtainable.** Both HuggingFace mirrors are gated:
`openlanguagedata/flores_plus` and `facebook/flores` each return **403 GatedRepoError** without an
access grant, and access requests are not same-day. Rather than fabricate a subset or wait, the
evaluation uses:

**WMT14 news test set (`newstest2014`), en-hi / hi-en, retrieved through sacreBLEU's own downloader.**

| | |
|---|---|
| Sentences available | 2,507 aligned pairs |
| **Sentences used** | **the first 500**, in corpus order |
| Selection rule | deterministic head of the file — no sampling, no filtering, no cherry-picking |
| Identical across systems | yes — all four runs read the same 500 lines |
| Reproduce | `sacrebleu -t wmt14 -l en-hi --echo src` / `--echo ref` |

WMT14 en-hi is a recognized, unseen, publicly redistributable benchmark, and it covers both
directions from one parallel corpus. It is a harder domain (news) than FLORES for a 200M distilled
model, so the absolute scores here should not be compared against published FLORES numbers — only
INT8 against FP32, which is what the question asks.

## 3. Decoding configuration — identical across all four systems

Ported from the app and verified against `bench/results/cross-device/bb_pi.py`, which is the same port
used for the Raspberry Pi 5 entry:

| | |
|---|---|
| Strategy | greedy (beam is dead code, §3.4) |
| `START` = `EOS` | 2 (mBART lineage reuses `</s>` as BOS) |
| `maxSteps` | 128 |
| `targetCap` | `min(max(14, 1.6 × srcLen + 8), 128)` |
| Repetition penalty | 1.1, first occurrence only |
| No-repeat n-gram | 3 |
| Tokenizer | the app's dict-based longest-match-first splitter, window 20, unmatched → `<unk>` |
| Language tags | EN→HI `(eng_Latn, hin_Deva)`; HI→EN `(hin_Deva, eng_Latn)` — looked up, not constants |

**Nothing was changed between systems.** The only variable is the model file.

Harness: `model_pipeline/quality_eval.py`.

## 4. Results

Metrics: sacreBLEU 2.6.0. Signatures
`BLEU|nrefs:1|case:mixed|eff:no|tok:13a|smooth:exp` and
`chrF2++|nrefs:1|case:mixed|eff:yes|nc:6|nw:2|space:no`.
chrF2++ is the more reliable of the two for a Devanagari target. Significance is a **paired bootstrap,
1,000 resamples, seed 12345**, FP32 as baseline.

### EN → HI (n = 500)

| System | BLEU (μ ± 95% CI) | chrF2++ (μ ± 95% CI) |
|---|---|---|
| FP32 reference | **21.88** (21.90 ± 1.31) | **49.21** (49.23 ± 1.14) |
| INT8, shipping | **21.85** (21.88 ± 1.25) | **48.93** (48.94 ± 1.10) |
| **Δ (INT8 − FP32)** | **−0.03** (−0.14%), p = 0.336 | **−0.28** (−0.57%), p = 0.037\* |

### HI → EN (n = 500)

| System | BLEU (μ ± 95% CI) | chrF2++ (μ ± 95% CI) |
|---|---|---|
| FP32 reference | **32.31** (32.30 ± 1.62) | **58.67** (58.68 ± 1.16) |
| INT8, shipping | **32.79** (32.77 ± 1.64) | **58.83** (58.84 ± 1.14) |
| **Δ (INT8 − FP32)** | **+0.48** (+1.5%), p = 0.039\* | **+0.16** (+0.27%), p = 0.134 |

### Interpretation

**INT8 ≈ FP32.** Read the two directions together, because either one alone invites the wrong
conclusion:

- EN→HI loses 0.28 chrF at p = 0.037; HI→EN *gains* 0.48 BLEU at p = 0.039.
- **The signs are opposite and the magnitudes are comparable.** A quantization scheme that genuinely
  damaged the model would not improve the other direction by a similar amount at a similar p-value.
- Both marginal p-values sit just under 0.05 on 1,000 resamples of 500 sentences — the regime where a
  paired test detects a real ±0.3–0.5 perturbation without that perturbation meaning anything.

The honest statement is **quality-neutral with a ±0.5 BLEU perturbation whose sign is not stable across
directions**, not "INT8 is 0.28 chrF worse" and certainly not "INT8 is better".

EN→HI also passes the pre-registered gate in `ENGINEERING_PLAN.md` §8 — *"chrF2 within 0.3 of
reference"* — at 0.28, by 0.02. That is a pass on a gate written before the measurement, which is the
only kind worth having, but it is a narrow one and is recorded as such.

**Cached vs uncached was not evaluated separately and does not need to be.** The uncached graphs were
deleted at §3.41 (dead since the weights were shared); the cached decoder *is* the model now, and
§3.6's gate already established cached-vs-uncached logit agreement at 9.06e-06 fp32 / 0.448 int8.

## 5. Correctness cross-check — and why parity was misleading

| | EN→HI | HI→EN |
|---|---|---|
| **Exact id-sequence match, INT8 vs FP32** | **253/500 = 50.6%** | **222/500 = 44.4%** |
| Same generated length | 329/500 = 65.8% | 316/500 = 63.2% |
| Mean length delta (INT8 − FP32) | −0.298 tokens | +0.236 tokens |
| Of those that differ: first divergence | token 12.4 of 32.2 | token 10.1 of 26.5 |
| Peak abs logit observed | 17.50 fp32 / 17.17 int8 | 16.16 int8 |
| EOS behaviour | no runaway generation; cap-hit behaviour unchanged | same |

**This is the finding worth carrying into the submission.** `verify_cache.py` reports "greedy tokens
identical" and that gate passes — on short synthetic inputs. On 500 real news sentences, **half the
outputs diverge at the token level**, and divergence begins around token 10–12, i.e. once the easy
prefix is done and argmax decisions get close.

Two conclusions follow, in opposite directions:

1. **Parity understates nothing about quality — it overstates the risk.** 50% token divergence and
   a statistically indistinguishable BLEU are the same fact seen twice: near-ties in argmax resolve
   differently under quantization noise and produce equally good translations. Anyone reading "half
   the tokens changed" as "the model degraded" would be wrong, and the corpus scores are the proof.
2. **Parity should never have been the quality claim.** It is a export-correctness gate. This
   document supersedes any reading of §3.6 or `verify_cache.py` as evidence about translation quality.

## 6. Limitations

- **Not FLORES.** WMT14 news, and the absolute scores are domain-specific. Cross-check against
  published IndicTrans2 FLORES numbers is invalid.
- **500 of 2,507 sentences.** The 95% CIs (±1.25–1.64 BLEU) are wide enough that only differences
  above ~1.3 BLEU would be individually conclusive; the paired bootstrap is what makes the ±0.3–0.5
  deltas readable at all.
- **Host CPU, not the phone.** Quality is device-independent — the same graphs and the same greedy
  contract — but these runs were on the Windows host under onnxruntime 1.27.0, not on the SM-S948B.
- **One reference per sentence.** Single-reference BLEU on Hindi is noisy by nature; chrF2++ is the
  more trustworthy column.
- **No human evaluation, no COMET.** Neither is achievable in the remaining time.
- **ASR and TTS quality are not measured here.** Word error rate on the speech path remains unmeasured
  (`HI_EN_IMPLEMENTATION.md` §6 still stands).

## 7. Reproducing

```bash
pip install sacrebleu

# corpus — 2,507 aligned pairs, first 500 used
sacrebleu -t wmt14 -l en-hi --echo src > wmt14.en.txt
sacrebleu -t wmt14 -l en-hi --echo ref > wmt14.hi.txt
head -500 wmt14.hi.txt > ref.hi.500.txt

cd model_pipeline
# INT8, exactly what ships
python quality_eval.py --direction en_hi --models ../app/src/main/assets --suffix _int8 \
    --dicts ../app/src/main/assets --src ../wmt14.en.txt \
    --out hyp.int8.en_hi.txt --ids-out ids.int8.en_hi.jsonl --limit 500
# FP32 reference
python quality_eval.py --direction en_hi --models onnx_cached --suffix "" --prefix "" \
    --dicts ../app/src/main/assets --src ../wmt14.en.txt \
    --out hyp.fp32.en_hi.txt --ids-out ids.fp32.en_hi.jsonl --limit 500

sacrebleu ref.hi.500.txt -i hyp.fp32.en_hi.txt hyp.int8.en_hi.txt \
    -m bleu chrf --chrf-word-order 2 -w 2 --paired-bs -f text
```

HI→EN swaps `--direction hi_en`, `--models onnx_cached_hi_en`, source `wmt14.hi.txt`, reference
`ref.en.500.txt`. Runtime on the host: ~170 s per 500 sentences INT8, ~270 s FP32.
