# Sidecar-free PO-token feasibility

- **Research date:** 2026-09-16
- **InnerTubeX baseline:** `13f8e4d36d249024cfe356c9cd67cd7fd8ee6493`
- **Metrolist-KMP baseline inspected read-only:** `8835a0c374cf1c6efbb0e1f1150434db4672d5f3`
- **Decision:** keep the production desktop sidecar. Build one opt-in QuickJS proof of concept before considering removal.

## Executive finding

A fast, sidecar-free implementation is **plausible but not demonstrated**.
The best candidate is the KMP `quickjs-kt` runtime that InnerTubeX already ships
for cipher/EJS work, connected to Metrolist-KMP's existing common page-bound
BotGuard flow. It could provide one literal execution runtime on Android, iOS,
Linux, Windows, and macOS without adding another JavaScript engine dependency.

The hard part is not the token API or the documented request sequence. Those
are already substantially shared. The hard part is making the opaque,
server-supplied BotGuard interpreter accept a small non-browser runtime over
future challenge revisions. Maintained upstream implementations use a real
browser, or emulate browser facilities with JSDOM, canvas, and web-runtime
extensions. No maintained, turnkey QuickJS BotGuard implementation was found.

Therefore:

1. Do not remove or weaken the current sidecar fallback.
2. Do not port the opaque interpreter to Kotlin. It changes remotely and still
   requires a JavaScript runtime.
3. Do not replace the executable with `bgutil-rs`'s current dynamic library.
   That removes process management but not the large V8/Deno payload, is
   desktop-only, and has a poor per-call lifecycle for this use.
4. Prototype a bounded QuickJS adapter behind a disabled-by-default switch.
5. Remove the sidecar only if every gate in [Removal gates](#removal-gates)
   passes on every supported target.

## What is already shared

It would be inaccurate to say that the project has no shared token logic.
There are three different meanings of "shared" here:

| Layer | Current state |
|---|---|
| Extraction contract | Shared. InnerTubeX's public `TokenProvider` returns player-request and video-bound tokens, advertises provider capabilities, prewarms, invalidates attestation, and closes resources. |
| Protocol and algorithms | Partly shared. InnerTubeX owns common challenge parsing and JavaScript builders. Metrolist-KMP owns a common page-bound challenge, attestation, minter, cache, and token-generation flow used by iOS and desktop. Android has an older parallel implementation of the same flow. |
| Literal execution runtime | Not shared. Android uses Android WebView, iOS uses WKWebView, desktop uses ComposeWebView/system web engines and falls back to `bgutil-rs` in a separate process. |

The existing `TokenProvider` boundary is already sufficient for a replacement.
No extractor redesign is needed. InnerTubeX also already depends on
`quickjs-kt` 1.0.14 in `commonMain`, and the published Gradle metadata exposes
that runtime dependency to consumers. Its current `QuickJsEngine` has a 192 MiB
memory limit, interrupt handling, coroutine confinement, and cross-target
support. A PO-token prototype should reuse those safety patterns, not expose
cipher internals or introduce a second engine abstraction.

## Current runtime inventory

### Android

- `AndroidTokenProvider` uses an off-screen Android `WebView`.
- It owns a platform-specific `BotGuardPoTokenMinter` that fetches a bounded
  watch page, extracts `window.ytAtN`, executes the interpreter, obtains an
  integrity token, creates a minter, and generates visitor-data and video-ID
  bound tokens.
- The provider caches token pairs for ten minutes and recreates the WebView on
  attestation invalidation.
- There is no desktop sidecar on Android.

### iOS

- `IosTokenProvider` uses the common `PageBoundPoTokenMinter` and
  `PageBoundPoTokenJavaScriptRuntime` script builders.
- `IosPageBoundTokenRuntime` evaluates the scripts in a hidden WKWebView.
- There is no sidecar. Provider capability is advertised only while the host
  runtime is available.

### Desktop Linux, Windows, and macOS

- `DesktopTokenProvider` first tries the same common page-bound minter through a
  hidden ComposeWebView host.
- If that path is unavailable or fails, it starts or contacts the
  `bgutil-ytdlp-pot-provider-rs` HTTP service.
- Managed endpoints are loopback-only. The provider validates scheme, host,
  port, user-info, path, query, and fragment; verifies the bundled executable's
  SHA-256; checks readiness with `GET /ping`; bounds response bodies; and
  discards child output.
- It concurrently requests the visitor-data token and video-ID token, caches
  the former for ten minutes, and preserves an externally configured provider
  endpoint.
- Linux and macOS package an executable named
  `bgutil-ytdlp-pot-provider-rs`; Windows packages the `.exe`.

The desktop WebView path is already sidecar-free when it succeeds. The sidecar
exists because that platform-backed path cannot yet be assumed reliable or
available across all packaged desktop environments.

## Candidate comparison

| Candidate | Android/iOS/desktop | One runtime | Removes process | Removes large payload | Finding |
|---|---:|---:|---:|---:|---|
| Existing platform WebViews | Yes | No | Yes when usable | Yes | Keep as the proven primary path. It does not guarantee one implementation or eliminate the fallback. |
| `quickjs-kt` plus the common minter | Yes | Yes | Yes | Likely | **Prototype.** The engine dependency and target binaries already come through InnerTubeX, but browser-integrity compatibility is unproven. |
| `bgutil-rs` dynamic library | Desktop only | No | Yes | No | Reject. It embeds the same Deno/V8 stack and has an unsuitable FFI lifecycle. |
| Original TypeScript bgutil | Desktop only in practice | No | No in server mode | No | Reject. It requires Node or Deno plus JSDOM, canvas, and other packages. |
| Rustypipe/Deno integration | No supported KMP bridge | No | Potentially | No | Reject. It embeds V8 and Deno web extensions and adds a Rust/native build matrix. |
| JavaScriptCore directly | Apple only | No | Yes | Yes on Apple | Useful only as another platform adapter, not a cross-platform answer. |
| Remote token service | Technically | Yes | Yes locally | Yes | Reject. It adds a sensitive network trust boundary and can break IP/proxy binding. |
| Pure Kotlin token algorithm | No | Yes | Yes | Yes | Not viable. The server supplies changing JavaScript interpreter code and performs runtime-integrity checks. |

## Why QuickJS is the only worthwhile proof of concept

### Advantages

- `quickjs-kt` supports the repository's Android, JVM desktop, iOS, macOS,
  Linux, and MinGW targets.
- It is already a transitive InnerTubeX dependency. The resolved JVM 1.0.14
  artifact is about 2.3 MB before packaging, so this route should not add a new
  engine to Metrolist-KMP.
- It supports promises, pending jobs, and Kotlin sync/async bindings. Network
  requests can remain in bounded Ktor code while JavaScript performs only the
  challenge computations.
- The current common minter already defines the required sequence and cache
  lifecycle. Only the execution environment should be replaced.
- It avoids child-process startup, loopback HTTP, resource extraction,
  executable permissions, checksum staging, and process cleanup.

### Unproven gaps

- QuickJS supplies ECMAScript, not a browser. A minimal environment must cover
  the exact globals the current interpreter observes, potentially including
  `window`, `document`, `navigator`, `location`, `origin`, timers, crypto,
  encoding, event APIs, and selected canvas behavior.
- BotGuard explicitly evaluates runtime characteristics. A shim that executes
  successfully may still produce rejected integrity or PO tokens.
- The required surface can change without a library release because YouTube
  supplies the interpreter and challenge remotely.
- A long-running or hostile interpreter must be interruptible. Memory, source
  size, response size, execution time, and pending jobs must remain bounded.
- Kotlin/Native lifecycle and thread confinement must be proven on physical iOS
  hardware, not only simulators.
- Token acquisition and media playback must use equivalent proxy and egress
  routing. A valid token generated through another IP is not a reliable result.
- Executing remotely supplied JavaScript in a bundled non-WebKit engine needs
  an App Review and distribution-policy assessment on iOS before release.

### Upstream evidence

The current maintained ecosystem reinforces these gaps:

- BgUtils documents that BotGuard performs runtime-integrity checks and that a
  compatible browser-like environment is required.
- The original maintained `Brainicism/bgutil-ytdlp-pot-provider` 2.0.0 uses
  Node 22 or Deno 2 and depends on BgUtils 4.0.3, JSDOM 29, canvas 3, and
  YouTube.js 18. Its session manager installs JSDOM globals before running
  BotGuard.
- YouTube.js demonstrates direct PO-token acquisition in a real browser
  context, not a small standalone interpreter runtime.
- `rustypipe-botguard`, used by the Rust sidecar, embeds Deno Core/V8 and adds
  Deno web, URL, WebIDL, console, and crypto extensions.

These are not proof that QuickJS cannot work. They are proof that evaluating
JavaScript alone is insufficient evidence.

## `bgutil-rs` FFI assessment

The v0.8.1 release includes dynamic libraries beside the executable for all
five desktop release artifacts. They export only:

```text
ffi_generate(content_binding, proxy, bypass_cache, source_address, disable_tls)
ffi_free_string(pointer)
```

This is not a drop-in replacement for the current service:

- Each call creates a Tokio runtime, loads file cache, constructs a new
  `SessionManager`, generates one token, writes cache, and shuts down V8.
- The HTTP server instead keeps one `Arc<SessionManager>` alive. Metrolist's two
  token bindings currently run concurrently against that persistent manager.
- The FFI has no persistent handle, explicit user-agent input, paired-token
  operation, structured error ABI, or cache-invalidation operation.
- A native crash or ABI error would terminate Metrolist instead of only the
  helper process.
- The current FFI logs request settings including content binding at debug
  level. Any integration would need a no-sensitive-logging audit.
- JVM Native Access would solve symbol loading, not the lifecycle, size,
  cross-mobile, or safety problems.

Raw upstream artifact sizes show almost no bloat win:

| Target | Executable | Dynamic library | Difference |
|---|---:|---:|---:|
| Linux arm64 | 54,147,304 B | 53,043,560 B | 1,103,744 B |
| Linux x64 | 50,990,856 B | 49,612,616 B | 1,378,240 B |
| macOS arm64 | 43,353,024 B | 42,215,520 B | 1,137,504 B |
| macOS x64 | 46,407,960 B | 44,918,792 B | 1,489,168 B |
| Windows x64 | 45,795,328 B | 44,195,840 B | 1,599,488 B |

The library is only 1.1 to 1.6 MB smaller because both forms contain the large
Rust, Deno, and V8 implementation. A redesigned persistent native ABI could
remove process and HTTP overhead, but it would still be desktop-specific and
would preserve nearly all packaged bloat. It is not the recommended route.

## Performance and footprint evidence

### Measured facts

- The bundled sidecar costs 42.2 to 53.0 MB of raw executable payload per
  desktop target before installer compression.
- The corresponding in-process `bgutil-rs` library costs 43.4 to 54.1 MB, so
  changing the container format does not materially reduce size.
- `quickjs-kt-jvm` 1.0.14 resolves to a 2,305,376-byte JAR and is already a
  published transitive dependency of InnerTubeX 0.6.0.
- The current InnerTubeX QuickJS wrapper permits up to 192 MiB because real EJS
  preprocessing can exceed 128 MiB. That is a safety ceiling, not measured
  steady-state BotGuard memory.

### Existing live A/B benchmark

The parallel live benchmark compared baseline
`13f8e4d36d249024cfe356c9cd67cd7fd8ee6493` with reachable PR #11 candidate
source snapshot `cbfd76d98d0fd0f6017c84f8b3400ad1afe572ed`. The authenticated cookie was
accepted by `accountMenu` with HTTP 200, but the existing account API exposed
no safe Premium entitlement. The candidate Premium value was therefore a
caller-confirmed hint hypothesis, not subscription detection.

The run used one sanitized normal, explicit, and kids sample, three repetitions
per arm, `AudioQuality.HIGH`, identical network/proxy settings, bounded first
media, and one 30-second decode plus 15-second seek per case. All primary rows
resolved, returned first media, decoded, and sought successfully. Quality was
unchanged across arms: DIRECT itag 251, Opus, 48 kHz, two channels, and the same
per-case bitrate.

| Case | Baseline total p50 | Candidate, no hint | Candidate, Premium hint |
|---|---:|---:|---:|
| Normal | 88 ms | 817 ms | 865 ms |
| Explicit | 666 ms | 704 ms | 675 ms |
| Kids | 552 ms | 536 ms | 523 ms |

There was **no broad speed gain**. The candidate selected WEB_REMIX instead of
baseline VISIONOS for normal content, added an attempt and fallback hop, and
regressed warm total time by 772 ms and process-cold total time by 4,854 ms.
The hint changed normal and explicit from `WEB_REMIX__po` to
`WEB_REMIX__nopo`, but explicit warm total was still 9 ms slower than baseline;
kids, which used no PO token in any arm, was 29 ms faster. In a separate
prewarmed normal run, baseline token/extractor prewarm cost 474/5,918 ms and
first total was 128 ms; the hinted candidate cost 467/5,747 ms and first total
was 913 ms.

A separate forced `WEB_REMIX_SABR` probe preserved SABR and the same exact
format in both revisions. It was already `__nopo` in both arms. Candidate cold
total was 460 ms slower, while warm p50 was 74 ms faster. Both arms shared the
same bounded 30-second decode-probe failure after first PCM, so it was not a
candidate-only regression.

These three-sample, sequential results are directional. They support only the
narrow claim that a caller-confirmed entitlement can suppress eligible token
requests without lowering selected quality. They do not support calling PR #11
a general startup optimization, do not provide an entitlement source for
Metrolist-KMP, and do not benchmark QuickJS token generation. The sanitized
report and rows are recorded in [PR #12](https://github.com/MetrolistGroup/innertubex/pull/12).

### Not measured

No QuickJS BotGuard implementation exists in this investigation, so there are
no honest cold-start, warm-mint, RSS, or playback measurements for it. No claim
that QuickJS is faster than the sidecar is warranted yet. The expected wins are
architectural: no child process, no extraction, no loopback request, and no
second JavaScript engine payload. The proof of concept must measure whether
browser shims and integrity retries erase those wins.

## PR #11: token need can shrink, but not disappear

PR #11 adds an explicit Premium entitlement hint. For a client that supports
login and whose manifest permits Premium bypass, the selector can skip required
PO-token generation. Its tests correctly keep tokens required when the caller
is signed out, when the client is anonymous-only, or when only a login cookie
exists without a verified entitlement.

Metrolist-KMP currently has no Premium entitlement signal in the inspected
source. Therefore the optimization is real but not yet generally usable by the
app. Even after wiring a trustworthy entitlement source, it applies only to
eligible authenticated Premium playback. Signed-out playback, non-Premium
accounts, stale/logout transitions, anonymous-only clients, and clients whose
rules do not permit bypass still need a token provider.

PR #11 can reduce token frequency and startup work. It cannot justify removing
the provider or sidecar by itself.

## Minimal proof of concept

Keep this experimental and disabled by default. Do not change production
provider ordering or remove bundled resources.

1. Add one common `QuickJsPageBoundPoTokenRuntime` implementing the existing
   page-bound runtime interface. Reuse `quickjs-kt`; add no engine dependency.
2. Bind only the browser facilities observed by a live challenge. Start with
   globals, encoding, timers, crypto/random bytes, and Ktor-backed fetch. Do not
   import a DOM implementation unless a captured failure proves it necessary.
3. Reuse `PageBoundPoTokenMinter`, its challenge parser, bounded HTTP reads,
   attestation TTL, and visitor/video binding flow. Do not fork BgUtils or write
   another token state machine.
4. Give each runtime one confined execution context, a hard 192 MiB ceiling or
   lower measured safe value, an 8-second execution deadline, source-size
   limits, and deterministic close/invalidate behavior.
5. Add a non-production switch that selects QuickJS first and preserves the
   current WebView and sidecar fallbacks. Emit only sanitized timing, outcome,
   runtime, and fallback-reason fields.
6. First prove one cold and one warm visitor/video token pair on Linux x64.
   Then run the complete target matrix before proposing production wiring.

Stop the prototype if passing BotGuard requires a broad DOM/canvas clone or
per-challenge fingerprint patches. At that point the browser-backed route is
smaller and more maintainable.

## Removal gates

All gates are mandatory. A failure on one supported target keeps the sidecar.

### Functional

- Targets: physical Android arm64, Android x86_64 emulator, physical iOS arm64,
  iOS arm64 simulator, Linux x64 and arm64, Windows x64, and macOS x64 and
  arm64.
- On each target, run 50 cold runtime starts and 500 warm visitor/video token
  pairs using at least 30 public samples covering normal, explicit, and kids
  selection paths.
- Token-mint success must be at least 99.5%, with zero native crashes, hangs,
  leaked workers, or unbounded retries.
- Paired playback trials must not reduce successful extraction plus first-media
  reads by more than 1 percentage point versus the current provider on the same
  host, network, account state, client profile, and exact selected format.
- Signed-out, signed-in non-Premium, verified Premium, logout, cookie rotation,
  visitor-data rotation, proxy, and attestation invalidation cases must all
  select the correct token requirement and binding.

### Performance

- Cold visitor/video token pair: p95 at most 5 seconds and no more than 10%
  slower than the current provider on the same target.
- Warm visitor/video token pair: p95 at most 250 ms and no more than 10% slower
  than the current provider.
- End-to-end extraction to first media: candidate median may regress by at most
  250 ms and p95 by at most 500 ms.
- Aggregate peak RSS during minting may be at most 10% above the current app
  plus sidecar aggregate on desktop. Post-mint steady RSS must return to within
  10% of baseline.

### Packaging, policy, and safety

- No sidecar executable, checksum, extraction task, launcher, or stale process
  remains in any desktop package.
- Installed desktop image size must shrink by at least 35 MB on every target.
- Response/source/allocation limits, timeouts, proxy consistency, cache
  invalidation, and cancellation tests must pass in common, Android, iOS, and
  desktop source sets.
- Logs and diagnostics must contain no cookies, auth values, PO tokens,
  visitor data, content bindings, signed media URLs, media titles, or account
  identifiers.
- Dependency notices and source obligations must be reviewed. iOS App Review
  policy for remotely supplied JavaScript in QuickJS must receive an explicit
  release sign-off.
- Keep the sidecar through at least one release-candidate cycle with QuickJS
  enabled only for opted-in validation. Remove it only in the following cycle
  after the complete matrix passes from packaged artifacts.

## Security, licensing, and maintenance

### Security

QuickJS removes an unauthenticated loopback service and executable-launch
surface, but moves remotely supplied JavaScript into the app process. The
current sidecar and most browser engines give a crash/isolation boundary that
an in-process engine does not. Limits and interrupt handling are release
requirements, not optional hardening.

Do not pass credentials into JavaScript. Ktor should own origin validation,
redirect policy, cookies, proxy selection, and bounded network bodies. The
runtime should receive only the challenge/interpreter data required to mint.

### Licensing

- Metrolist-KMP and InnerTubeX: GPL-3.0.
- `bgutil-ytdlp-pot-provider-rs`: GPL-3.0-or-later.
- Original bgutil provider: GPL-3.0-only.
- BgUtils and Rustypipe BotGuard: MIT.
- `quickjs-kt`: Apache-2.0; upstream QuickJS carries its permissive notice.

No obvious license incompatibility blocks the QuickJS prototype in this GPL
project. Shipping or copying code still requires preserving notices and the
normal GPL source obligations. This is an engineering review, not legal advice.

### Maintenance

BgUtils, the original provider, YouTube.js, and `quickjs-kt` all had current
September 2026 activity during this review. That is positive, but no upstream
project offers the proposed KMP QuickJS environment. Metrolist would own its
browser-compatibility shims and emergency response when BotGuard changes.

Keep the adapter small and failure-driven. If it grows into a DOM or persistent
fingerprint compatibility project, stop and retain the platform browser plus
sidecar design.

## Recommendation

**Now:** retain the production sidecar fallback and existing WebView-first
behavior.

**Next experiment:** implement the minimum QuickJS adapter described above on
an opt-in branch. It is the only candidate that can plausibly improve startup,
remove 42 to 53 MB of desktop payload, and use one runtime across every target
without adding another engine.

**Do not pursue:** current `bgutil-rs` FFI, Node/Deno embedding, a pure Kotlin
interpreter rewrite, or a remote token service.

**Removal decision:** only after the exact functional, performance, packaging,
policy, and safety gates pass. Until then, sidecar removal would trade a known,
isolated fallback for an unproven in-process browser emulation layer.

## Sources

### Project sources

- [InnerTubeX `TokenProvider` contract](https://github.com/MetrolistGroup/innertubex/blob/13f8e4d36d249024cfe356c9cd67cd7fd8ee6493/src/commonMain/kotlin/com/metrolist/innertubex/extraction/TokenProvider.kt)
- [InnerTubeX PO-token JavaScript helpers](https://github.com/MetrolistGroup/innertubex/blob/13f8e4d36d249024cfe356c9cd67cd7fd8ee6493/src/commonMain/kotlin/com/metrolist/innertubex/extraction/potoken/PoTokenJavaScript.kt)
- [InnerTubeX challenge parser](https://github.com/MetrolistGroup/innertubex/blob/13f8e4d36d249024cfe356c9cd67cd7fd8ee6493/src/commonMain/kotlin/com/metrolist/innertubex/extraction/potoken/WebPageAttestationChallenge.kt)
- [InnerTubeX QuickJS wrapper](https://github.com/MetrolistGroup/innertubex/blob/13f8e4d36d249024cfe356c9cd67cd7fd8ee6493/src/commonMain/kotlin/com/metrolist/innertubex/cipher/QuickJsEngine.kt)
- [InnerTubeX QuickJS dependency](https://github.com/MetrolistGroup/innertubex/blob/13f8e4d36d249024cfe356c9cd67cd7fd8ee6493/build.gradle.kts)
- Metrolist-KMP common page-bound minter at `8835a0c374cf1c6efbb0e1f1150434db4672d5f3`: `shared/src/commonMain/kotlin/com/metrolist/shared/youtube/playback/PageBoundPoTokenMinter.kt`
- Metrolist-KMP common page-bound runtime scripts at the same commit: `shared/src/commonMain/kotlin/com/metrolist/shared/youtube/playback/PageBoundPoTokenJavaScriptRuntime.kt`
- Metrolist-KMP Android provider and minter at the same commit: `shared/src/androidMain/kotlin/com/metrolist/shared/youtube/playback/AndroidTokenProvider.kt` and `BotGuardPoTokenMinter.kt`
- Metrolist-KMP iOS provider and WKWebView runtime at the same commit: `shared/src/iosMain/kotlin/com/metrolist/shared/youtube/playback/IosTokenProvider.kt` and `IosPageBoundTokenRuntime.kt`
- Metrolist-KMP desktop provider and WebView runtime at the same commit: `shared/src/desktopMain/kotlin/com/metrolist/shared/youtube/playback/DesktopTokenProvider.kt` and `desktopApp/src/jvmMain/kotlin/com/metrolist/desktop/youtube/DesktopPageBoundTokenRuntime.kt`
- [InnerTubeX PR #11](https://github.com/MetrolistGroup/innertubex/pull/11)
- [Sanitized Premium A/B benchmark report](https://github.com/MetrolistGroup/innertubex/blob/3d0fe40ca1e30f758f60e847d13602d2cabfe756/reports/live-premium-ab-20260916/LIVE-PREMIUM-AB-BENCHMARK.md)

### Upstream sources

- [`bgutil-ytdlp-pot-provider-rs` v0.8.1 FFI](https://github.com/jim60105/bgutil-ytdlp-pot-provider-rs/blob/v0.8.1/src/ffi.rs)
- [`bgutil-ytdlp-pot-provider-rs` persistent server state](https://github.com/jim60105/bgutil-ytdlp-pot-provider-rs/blob/v0.8.1/src/server/app.rs)
- [`bgutil-ytdlp-pot-provider-rs` v0.8.1 artifacts](https://github.com/jim60105/bgutil-ytdlp-pot-provider-rs/releases/tag/v0.8.1)
- [Original bgutil provider 2.0.0 dependencies](https://github.com/Brainicism/bgutil-ytdlp-pot-provider/blob/37169ee2656e08c5c2e5dc9df4c598c0cb4c88a8/server/package.json)
- [Original bgutil JSDOM setup](https://github.com/Brainicism/bgutil-ytdlp-pot-provider/blob/37169ee2656e08c5c2e5dc9df4c598c0cb4c88a8/server/src/session_manager.ts#L202-L227)
- [BgUtils runtime-integrity note](https://github.com/LuanRT/BgUtils/tree/84e3705ccbbf1224c8df0502fdf2c712a666b04f)
- [YouTube.js browser PO-token example](https://github.com/LuanRT/YouTube.js/blob/d252b36f7e0bf5926a52a589a73d4aa6392a9683/examples/browser/web/src/main.ts)
- [`rustypipe-botguard` runtime](https://docs.rs/crate/rustypipe-botguard/0.1.2/source/src/runtime.rs)
- [`quickjs-kt` targets and API](https://github.com/dokar3/quickjs-kt/tree/a081755bcdb18ee6afa7ffee25b8fa976f06d31f)
- [yt-dlp PO Token Guide](https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide)
- [Apple App Review Guidelines 2.5.2 and 2.5.6](https://developer.apple.com/app-store/review/guidelines/)
