# Live playback evidence validation

Measured 2026-09-16. This evidence-only report supersedes the methodology and reporting claims in PR #12; it does not include PR #12's later SABR production fix.

## Result

- Authentication is now established for the rerun: all 24/24 bounded probes had credentials present, HTTP 200, and a parseable authenticated account-menu marker. No account details were retained.
- Premium entitlement was **not observed**. Every candidate Premium value below is an explicit entitlement hypothesis, never a cookie inference or caller confirmation.
- The negative normal-song result remains. Exact-format profile repeats were materially slower on the original PR #11 candidate because it selected WEB_REMIX instead of baseline VISIONOS.
- Forced WEB_REMIX_SABR first media succeeded 6/6, 15 s seeks succeeded 6/6, and 0/6 paced 30 s decodes completed; observed failures: `decode-30s:eof:attestation-required` (6).
- A faster removable cross-platform token runtime is still unproven. Existing Android WebView, iOS WKWebView, and optional desktop WebView already share the common page-bound minter; QuickJS 1.0.14 remains only a disabled-by-default proof-of-concept candidate.

## Exact revisions

- InnerTubeX baseline: `13f8e4d36d249024cfe356c9cd67cd7fd8ee6493`
- Original PR #11 candidate: `68ba2a9627c7c85ec04968ef1d1649c2547a64f2`
- Measured candidate snapshot `cbfd76d98d0fd0f6017c84f8b3400ad1afe572ed` has identical `src` and `api` trees to the original PR #11 revision.
- PR #12 head `59da579d6d8452f80ad4f8dbf57343b8b260a0c2` contains a later SABR fix and was not measured as the candidate.
- Metrolist-KMP harness host: `8835a0c374cf1c6efbb0e1f1150434db4672d5f3`

## Original observations, correctly classified

The raw source rows are immutable under `original/`. Their old `phase` field is retained as `source_phase` in the normalized rows, while `process_phase` and `profile_phase` are derived independently. A profile's first row is not called warm merely because another profile ran earlier. Client-level caches can still carry across profiles when they share a client.

All-row totals below preserve the observed three-row medians and ranges. They are directional observations, not significance claims.

| Case | Baseline | Candidate control | Candidate entitlement hypothesis |
|---|---:|---:|---:|
| normal | 88 [88..163] (n=3) | 817 [762..5397] (n=3) | 865 [855..5017] (n=3) |
| explicit | 666 [646..4997] (n=3) | 704 [684..763] (n=3) | 675 [641..756] (n=3) |
| kids | 552 [525..4183] (n=3) | 536 [509..552] (n=3) | 523 [520..539] (n=3) |

Profile-repeat-only totals use iterations 2 and 3, explicitly `n=2`:

| Case | Baseline | Candidate control | Candidate entitlement hypothesis |
|---|---:|---:|---:|
| normal | 88 [88..88] (n=2) | 789.5 [762..817] (n=2) | 860 [855..865] (n=2) |
| explicit | 656 [646..666] (n=2) | 723.5 [684..763] (n=2) | 698.5 [641..756] (n=2) |
| kids | 538.5 [525..552] (n=2) | 530.5 [509..552] (n=2) | 521.5 [520..523] (n=2) |

## Balanced targeted rerun

Each content case ran in three fresh processes per arm. Every process had one profile-first row and two profile-repeat rows, with baseline/candidate order alternated. Streams retained at least 10 s spacing and profile groups at least 20 s spacing. Values are total-to-first-media median [range].

| Case | Phase | Baseline | Candidate entitlement hypothesis | Matched candidate minus baseline |
|---|---|---:|---:|---:|
| normal | process-first / profile-first | 162 [158..247] (n=3) | 4925 [4901..5187] (n=3) | 4763 [4743..4940] (n=3) ms |
| normal | process-warm / profile-repeat | 95.5 [89..115] (n=6) | 813.5 [767..1268] (n=6) | 719.5 [667..1165] (n=6) ms |
| explicit | process-first / profile-first | 4504 [4483..4894] (n=3) | 4600 [4547..4818] (n=3) | 43 [-76..117] (n=3) ms |
| explicit | process-warm / profile-repeat | 694.5 [682..731] (n=6) | 694 [686..714] (n=6) | 3 [-43..21] (n=6) ms |
| kids | process-first / profile-first | 4565 [4525..4603] (n=3) | 4605 [4579..4747] (n=3) | 80 [14..144] (n=3) ms |
| kids | process-warm / profile-repeat | 534.5 [518..548] (n=6) | 523.5 [491..552] (n=6) | -11 [-27..17] (n=6) ms |

All matched rows kept the same per-case itag 251 Opus identity, 48 kHz sample rate, and bitrate across arms. Normal remained 141473 bps, explicit 151216 bps, and kids 148600 bps. The normal regression was not obtained by lowering candidate quality.

## Forced SABR decode and seek

The corrected harness advances the decoder playback clock for every PCM frame, paces it within 250 ms of wall time, places the 15 s seek before the long decode, bounds each decode to 60 s and each sample to 120 s, and closes resources on interruption. External cancellation still propagates.

| Arm | Runs | First media | 15 s seek | 30 s decode | Decoded position | Failure |
|---|---:|---:|---:|---:|---:|---|
| baseline | 3 | 3/3 | 3/3 | 0/3 | 19974 ms | `decode-30s:eof:attestation-required` (3) |
| candidate_entitlement_hypothesis | 3 | 3/3 | 3/3 | 0/3 | 19974 ms | `decode-30s:eof:attestation-required` (3) |

Both arms selected `WEB_REMIX_SABR__nopo`, itag 251, opus, 141473 bps, 48 kHz, stereo. Validated seeks succeeded 6/6 at median 14994 ms. The old frozen-clock harness defect is corrected; paced playback observed `decode-30s:eof:attestation-required` (6), with 0/6 30 s decodes completing. First-media success is not complete-playback evidence, and no production fix is claimed here.

## Sidecar conclusion

PR #13's feasibility report remains the applicable design evidence, with one correction: its cited live benchmark must use this report's phase labels and warm-state caveat. No QuickJS BotGuard implementation was benchmarked. Keep the desktop sidecar fallback until a disabled QuickJS adapter passes accepted-token, real-media, exact-quality, cancellation, expiry/session, packaged-target, latency, memory, and footprint gates.

## Reproducibility and artifacts

- `tools/live-evidence/metrolist-live-benchmark.patch`: privacy-safe opt-in patch compiled only in the host app; InnerTubeX source sets do not reference Metrolist classes.
- `tools/live-evidence/prepare-metrolist-checkout.sh`: validates exact revisions, applies the host patch, and wires the temporary composite checkout.
- `tools/live-evidence/run-targeted-reruns.sh`: exact balanced run order and bounded serialized Gradle invocation.
- `tools/live-evidence/derive_report.py`: normalizes phases and regenerates all aggregate CSV, JSON, and Markdown outputs.
- `original/`: immutable sanitized source observations from the first worker.
- `reruns/`: sanitized per-process rows from the corrected harness.
- `derived/`: normalized full rows and calculated summaries.

Cookie contents, account fields, media identifiers, titles, request headers, PO tokens, signed URLs, and raw responses are absent. The published cookie path is `<cookie-file-path>` only.

## Resolved review issues

1. Split process-first/process-warm from profile-first/profile-repeat and recomputed repeat-only `n=2` summaries.
2. Replaced SAPISID-plus-HTTP-200 inference with a bounded in-memory authenticated account-menu marker; Premium remains unobserved.
3. Added clock progression, pacing, deadlines, seek-position validation, cancellation propagation, and cleanup to the harness. SABR outcomes and failure categories are rendered from the observed rows.
4. Preserved a compilable opt-in host-app patch, exact checkout/fetch instructions, candidate tree identity, full row artifacts, and programmatic derivation without adding Metrolist dependencies to InnerTubeX.
5. Kept sidecar feasibility separate from production and made no unsupported speed or removability claim.
