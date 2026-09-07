# Changelog

All notable changes are documented here. This project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) while recognizing
that `0.x` releases may contain breaking API changes.

## [Unreleased]

## [0.6.0] - 2026-09-07

### Added

- Add iOS device and simulator targets with native locale, hashing, and EJS
  implementations.
- Allow playlist deletion and unlike requests to use an explicit session
  snapshot so account changes cannot redirect a mutation.
- Add strict MP4 audio selection for native players.

### Fixed

- Refresh QuickJS native stack metadata before each evaluation so authenticated
  playback cannot crash after a coroutine changes workers.
- Build `Accept-Language` consistently without duplicating or inventing a
  region.
- Handle native locale discovery and SABR end-of-stream behavior safely.

## [0.5.2] - 2026-09-02

### Fixed

- Restore the upload content type expected by YouTube Music so song uploads can
  finalize successfully.
- Retry authenticated player-config page failures without cookies.
- Allow additional time for current large player scripts to evaluate.

## [0.4.1] - 2026-08-31

### Fixed

- Transform `n` challenges on uploaded-media URLs hosted by YouTube so their
  streams do not fail with HTTP 403.

## [0.4.0] - 2026-08-31

### Fixed

- Complete SABR streams at their declared final segment when YouTube omits the
  end-of-track marker.
- Preserve WEB_REMIX as the preferred automatic SABR client.
- Accept validated uploaded-media playback URLs and attach login cookies only
  to uploaded-media requests.

## [0.3.0] - 2026-08-30

### Fixed

- Prewarm authenticated and anonymous player configurations separately,
  preprocess the active player script, and mint the initial PO token concurrently
  to reduce cold protected-playback startup time.

## [0.2.6] - 2026-08-27

### Fixed

- Prefetch PO tokens for protected streams, avoid repeated unavailable-token
  work across client fallbacks, and solve signature and n-parameter challenges
  together.

## [0.2.5] - 2026-08-27

### Fixed

- Accept current root-path YouTube resumable upload session URLs while
  continuing to reject unexpected endpoints.

## [0.2.4] - 2026-08-27

### Fixed

- Retain streams whose n-parameters were successfully transformed by the
  cipher service.

## [0.2.3] - 2026-08-27

### Fixed

- Allow EJS to process current large player scripts without exhausting its bounded QuickJS memory budget.

## [0.2.2] - 2026-08-26

### Fixed

- Automatic extraction now exhausts eligible direct clients before trying every eligible SABR fallback.

## [0.2.1] - 2026-08-25

### Added

- Extracted media metadata and perceptual loudness in stream results.
- Per-request HLS, SABR, and bounded-range capability flags for direct-only hosts.

## [0.2.0] - 2026-08-25

### Added

- Reusable player extraction stack with content-aware client selection,
  format selection, direct/HLS/SABR transport support, PO-token contracts,
  client-health integration, bounded watch/player parsing, and diagnostics.
- Platform-neutral BotGuard/page-attestation parsing and transcript/caption
  helpers for Android and desktop hosts.
- Public bounded response decoders and hardened cookie normalization helpers.

### Fixed

- Bound player, watch-page, iframe, caption, transcript, token, and sidecar
  responses before materializing them in memory.
- Validate media, caption, player-script, SABR, upload, and playback-statistics
  URLs by HTTPS host, default port, and expected endpoint path.
- Reject unresolved cipher parameters, incomplete video selections, invalid
  video media hosts, and bounded-range streams without known lengths.
- Preserve coroutine cancellation across extraction and playback-recovery
  fallbacks, and bind player-config caching to the originating session.
- Redact signed URLs, visitor data, PO tokens, account identifiers, media IDs,
  and raw exception messages from reusable diagnostics.
- Restore `application/octet-stream` for finalized song-upload bodies.

## [0.1.2] - 2026-08-22

### Added

- Playlist custom thumbnail upload and removal.
- Adding one playlist to another playlist.
- Deleting privately owned library entities.
- Optional progress reporting for in-memory song uploads.

### Fixed

- Documented JitPack's variant-aware KMP coordinate instead of its aggregate
  repository POM coordinate.
- Accept-Language headers no longer duplicate a region already present in the
  language tag.
- `accountsList` omits the active-account sync id so every signed-in account
  can be enumerated.

## [0.1.1] - 2026-08-21

### Fixed

- Preserved the generated binary API baseline format so clean CI and JitPack
  builds pass. The failed `v0.1.0` JitPack lookup did not publish artifacts.

## [0.1.0] - 2026-08-21

### Added

- Android and JVM Kotlin Multiplatform InnerTubeX client.
- Session-safe authenticated requests and account operations.
- Player cipher handling with Faraday/Zemer, EJS, and parser fallbacks.
- Experimental SABR audio/video transport with bounded UMP parsing.
- Streaming YouTube Music uploads without full-file buffering.
- JitPack publication, API compatibility validation, and CI gates.

### Security

- Restricted authenticated playback-statistics requests to approved YouTube
  HTTPS endpoints.
- Bounded remote player, config, visitor-data, and SABR response bodies.
- Redacted raw URLs and exception text from library diagnostics.
- Restricted authenticated upload, media probing, and executable player-config
  requests to their expected HTTPS providers without following redirects.
- Removed account cookies from anonymous browse, next, and queue requests.
- Bound account mutations and both upload stages to their originating session.
- Bounded retained SABR contexts, playback cookies, bootstrap blobs, and total
  response bytes, with exact full-stream byte validation.
- Added a QuickJS evaluation timeout for malformed or hostile player code.
