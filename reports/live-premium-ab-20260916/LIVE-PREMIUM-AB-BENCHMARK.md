# Live Premium A/B Benchmark

Measured 2026-09-16 against exact local InnerTubeX revisions:

- Baseline: `13f8e4d36d249024cfe356c9cd67cd7fd8ee6493`
- Candidate: `68ba2a9627c7c85ec04968ef1d1649c2547a64f2` (`fix/mu3d1odh-1-verified-premium-playback`)

## Result

The cookie authenticated successfully for both arms (`accountMenu` HTTP 200; boolean only). The existing account capability API did not expose a safe Premium entitlement result, so the candidate Premium value is a **caller-confirmed hint hypothesis**, not subscription validation.

**No broad speed gain was measured.** The candidate no-hint control regressed normal-song resolution substantially because it selected `WEB_REMIX__po` instead of baseline `VISIONOS_0_1__nopo`. The candidate Premium-hint hypothesis removed the PO-token marker for normal/explicit, but still selected `WEB_REMIX__nopo` and remained slower than baseline. Explicit and kids warm paths were near parity, with kids modestly faster.

## Setup

- Existing `LiveInnerTubeSuite` sample definitions: one runtime-selected normal, explicit, and kids sample; media identifiers are intentionally absent from artifacts.
- Three repetitions per case, `AudioQuality.HIGH`, identical HTTP engine, configured network/proxy environment, `DesktopTokenProvider`, local InnerTubeX composite (`-PuseLocalInnerTubeX`), and `--no-daemon --no-configuration-cache`.
- Main runs had prewarm disabled. A separate normal-only prewarm run measured token startup and extractor/EJS preparation. Existing 10-second stream and 20-second group delays were retained.
- The first row is the fresh test-process/extractor path; later rows are extractor-warm. The second resolve in each row measured repeat-resolve cache timing.
- First media used a bounded request. The first repetition of each case decoded approximately 30 seconds of PCM and attempted a seek to 15 seconds. No history, watchtime, like, playlist, UI, device, or adb activity was used.

## Timing summary

`p50 [range]` is the median and observed min-max across three repetitions. All primary rows resolved and produced first media (`3/3`).

| Case | Arm | Resolve p50 ms [range] | First media p50 ms [range] | Total p50 ms [range] | Cache p50 ms [range] |
|---|---|---:|---:|---:|---:|
| normal | Baseline | 70 [70-136] | 18 [17-27] | 88 [88-163] | 0 [0-1] |
| normal | Candidate, no hint | 812 [758-5374] | 4 [4-22] | 817 [762-5397] | 0 [0-1] |
| normal | Candidate, premium hint | 850 [849-4991] | 14 [5-25] | 865 [855-5017] | 0 [0-1] |
| explicit | Baseline | 653 [632-4982] | 13 [12-14] | 666 [646-4997] | 0 [0-0] |
| explicit | Candidate, no hint | 689 [671-750] | 13 [13-15] | 704 [684-763] | 0 [0-0] |
| explicit | Candidate, premium hint | 661 [628-743] | 13 [12-13] | 675 [641-756] | 0 [0-0] |
| kids | Baseline | 541 [513-4170] | 12 [11-12] | 552 [525-4183] | 0 [0-0] |
| kids | Candidate, no hint | 523 [496-539] | 12 [12-13] | 536 [509-552] | 0 [0-0] |
| kids | Candidate, premium hint | 507 [493-509] | 13 [12-45] | 523 [520-539] | 0 [0-0] |

The normal range includes the process-cold first row. Explicit and kids rows are extractor-warm because the first process-cold row is the first normal sample.

## Before/after deltas

`Δ = candidate minus baseline`; negative is faster. Warm rows exclude the one process-cold normal row.

| Case / phase | No-hint Δ resolve ms | Premium-hint Δ resolve ms | No-hint Δ total ms | Premium-hint Δ total ms |
|---|---:|---:|---:|---:|
| normal / process-cold | +5238 | +4855 | +5234 | +4854 |
| normal / extractor-warm p50 | +715 | +780 | +702 | +772 |
| explicit / extractor-warm p50 | +36 | +8 | +38 | +9 |
| kids / extractor-warm p50 | -18 | -34 | -16 | -29 |

First-media warm deltas were small: normal no-hint -14 ms and Premium-hint -8 ms; explicit 0 ms for both; kids 0 ms no-hint and +1 ms with the hint. Cache p50 was 0 ms in every arm/case.

## Selected quality and path

| Case | Baseline | Candidate, no hint | Candidate, premium hint hypothesis |
|---|---|---|---|
| normal | VISIONOS / `VISIONOS_0_1__nopo` / DIRECT / itag 251 / audio/webm Opus / 141473 bps / 48000 Hz / 2 ch / PO no / auth-watch no / cipher none-observed / n none-observed / 1 attempt, 0 hops | WEB_REMIX / `WEB_REMIX__po` / DIRECT / itag 251 / audio/webm Opus / 141473 bps / 48000 Hz / 2 ch / PO yes / auth-watch yes / cipher transformed / n none-observed / 2 attempts, 1 hop | WEB_REMIX / `WEB_REMIX__nopo` / DIRECT / itag 251 / audio/webm Opus / 141473 bps / 48000 Hz / 2 ch / PO no / auth-watch yes / cipher transformed / n none-observed / 2 attempts, 1 hop |
| explicit | WEB_REMIX / `WEB_REMIX__po` / DIRECT / itag 251 / audio/webm Opus / 151216 bps / 48000 Hz / 2 ch / PO yes / auth-watch yes / cipher transformed / n none-observed / 1 attempt, 0 hops | WEB_REMIX / `WEB_REMIX__po` / DIRECT / itag 251 / audio/webm Opus / 151216 bps / 48000 Hz / 2 ch / PO yes / auth-watch yes / cipher transformed / n none-observed / 1 attempt, 0 hops | WEB_REMIX / `WEB_REMIX__nopo` / DIRECT / itag 251 / audio/webm Opus / 151216 bps / 48000 Hz / 2 ch / PO no / auth-watch yes / cipher transformed / n none-observed / 1 attempt, 0 hops |
| kids | WEB_KIDS / `WEB_KIDS__nopo` / DIRECT / itag 251 / audio/webm Opus / 148600 bps / 48000 Hz / 2 ch / PO no / auth-watch no / cipher transformed / n none-observed / 1 attempt, 0 hops | WEB_KIDS / `WEB_KIDS__nopo` / DIRECT / itag 251 / audio/webm Opus / 148600 bps / 48000 Hz / 2 ch / PO no / auth-watch yes / cipher transformed / n none-observed / 1 attempt, 0 hops | WEB_KIDS / `WEB_KIDS__nopo` / DIRECT / itag 251 / audio/webm Opus / 148600 bps / 48000 Hz / 2 ch / PO no / auth-watch yes / cipher transformed / n none-observed / 1 attempt, 0 hops |

All primary rows returned HTTP 206 for the bounded direct-media probe. The selected audio identity stayed at itag 251 with Opus, 48 kHz, two channels, and the same per-case bitrate across arms: normal 141473 bps, explicit 151216 bps, kids 148600 bps. The candidate was not called faster by lowering quality.

## Decode and seek evidence

Each primary arm decoded 30 seconds successfully for all three representative cases (3/3 cases; one decode per case). Values are `first PCM ms / 30 s decode ms / first PCM after 15 s seek ms`.

| Case | Baseline | Candidate no hint | Candidate premium hint |
|---|---:|---:|---:|
| normal | 115 / 203 / 24 | 119 / 196 / 15 | 117 / 194 / 18 |
| explicit | 15 / 80 / 15 | 12 / 78 / 14 | 14 / 78 / 14 |
| kids | 12 / 76 / 12 | 16 / 79 / 12 | 17 / 80 / 12 |

The selected clients remained DIRECT; no SABR row was selected by this automatic HIGH-quality run.

### Forced SABR probe

A separate three-repetition normal-only probe forced `WEB_REMIX_SABR` with the same HIGH quality. Both revisions selected `WEB_REMIX_SABR__nopo`, itag 251 / Opus / 141473 bps / 48000 Hz / 2 channels, HTTP 200 SABR bootstrap media, one attempt, zero hops, `n=transformed`, and no signature cipher. Baseline process-cold resolve/total was 4456/4504 ms; candidate Premium-hint process-cold was 4857/4964 ms (`+401/+460`). Warm p50 resolve/total was 814/825 ms baseline versus 740/751 ms candidate (`-74/-74`).

The forced SABR first-iteration decode produced first PCM in 90 ms baseline and 92 ms candidate, then both stopped before the requested 30 seconds with the same bounded `decode-30s:IllegalStateException`; this is a shared probe failure, not a candidate-only regression. Automatic DIRECT playback above decoded 30 seconds successfully in all cases. The forced SABR row-level artifacts retain only this sanitized stage/type result.

## Prewarm cost

| Arm | Token prewarm ms | Extractor prewarm ms | Normal first resolve / total ms after prewarm |
|---|---:|---:|---:|
| Baseline | 474 | 5918 | 108 / 128 |
| Candidate, no hint | 476 | 5265 | 931 / 953 |
| Candidate, premium hint hypothesis | 467 | 5747 | 891 / 913 |

Prewarm timing is separate from playback timing. The sidecar readiness path was the existing DesktopTokenProvider HTTP readiness implementation, not a raw TCP probe.

## Sanitization and limitations

- Cookie contents, account identifiers, media IDs, titles, signed URLs, headers, and raw responses were not written to the report. The cookie was loaded only from the authorized file path inside the local test process.
- `PO yes/no` is the existing profile suffix marker (`__po`), not a captured token. Cipher/n statuses come from safe aggregate logger events; no challenge values or URLs were recorded. `n` was none-observed in these selected paths; cipher transformation was observed for candidate normal and for explicit/kids in all arms.
- Premium entitlement was not inferred from the login cookie or SAPISID. The candidate hint is intentionally labeled a hypothesis and should only be enabled by a host after independently confirming entitlement.
- Three samples per case and sequential network timing are directional, not a production-wide benchmark. The primary balanced pair ran candidate first, then baseline; the no-hint candidate control was a separate run. YouTube/CDN/player state can vary.

## Reproduction command template

This template contains the cookie **file path only**. Run one Gradle command at a time under the shared lock:

```sh
flock -x /tmp/metrolist-gradle.lock timeout 1800s env RUN_LIVE_INNERTUBE_CLIENT_BENCHMARKS=true INNERTUBE_TOKEN_FILE=/home/nyx/projects/innertubex/innertube_cookie.txt INNERTUBE_BENCHMARK_LABEL=<label> INNERTUBE_BENCHMARK_ITERATIONS=3 INNERTUBE_BENCHMARK_DECODE=true INNERTUBE_BENCHMARK_PREMIUM_HINT=<true-or-false> ./gradlew :shared:desktopTest --tests com.metrolist.shared.youtube.innertube.LivePremiumAbBenchmarkTest.runSanitizedPremiumAbBenchmark -PuseLocalInnerTubeX --no-daemon --no-configuration-cache --max-workers=2 --no-parallel -DinnerTubeTokenFile=/home/nyx/projects/innertubex/innertube_cookie.txt -Dorg.gradle.jvmargs='-Xmx2048m -XX:MaxMetaspaceSize=512m' -Pkotlin.compiler.execution.strategy=in-process -DliveInnerTubeBenchmarkReportDir=<report-dir> --console=plain
```

For the forced SABR probe, add `INNERTUBE_BENCHMARK_PROFILE=WEB_REMIX_SABR INNERTUBE_CONTENT_TYPE_FILTER=normal` to the `env` list and use `INNERTUBE_BENCHMARK_PREMIUM_HINT=false` for baseline or `true` for the candidate hypothesis.

Raw sanitized row artifacts:

- `playback-ab-baseline_forced_sabr.{json,csv}`
- `playback-ab-candidate_forced_sabr_premium.{json,csv}`
- `playback-ab-baseline_primary_balanced.{json,csv}`
- `playback-ab-candidate_primary_control.{json,csv}`
- `playback-ab-candidate_primary_balanced.{json,csv}`
- `playback-ab-baseline_prewarm.{json,csv}`
- `playback-ab-candidate_prewarm_control.{json,csv}`
- `playback-ab-candidate_prewarm.{json,csv}`

See `provenance.json` for exact revisions and measured command variants.
