# InnerTubeX

[![CI](https://github.com/MetrolistGroup/innertubex/actions/workflows/ci.yml/badge.svg)](https://github.com/MetrolistGroup/innertubex/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/MetrolistGroup/innertubex.svg)](https://jitpack.io/#MetrolistGroup/innertubex)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)

InnerTubeX is an extended Android, JVM, and iOS Kotlin Multiplatform client for
YouTube's InnerTube APIs. It retains standard browse, search, account,
playlist, and player operations while adding:

- SABR/UMP audio and video streaming with seekable segmented sources.
- SABR video representation selection for host video playback.
- Kotlin-only player cipher deobfuscation through hosted [QuackJS](https://github.com/MetrolistGroup/quackjs).
- Optional validated Faraday landmarks, hint-free discovery, and bounded compiled-player caches.
- Isolated authenticated sessions with generation-safe cancellation.
- Channel discovery, account switching support, and streaming music uploads.
- Bounded remote bodies and protocol state with strict media URL validation.
- Injectable logging, player configuration, diagnostics, and token providers.


## Used by

InnerTubeX powers YouTube API access and stream extraction in a growing number of open-source music applications.

| Project | Stars | Downloads |
| --- | --- | --- |
| [Metrolist](https://github.com/MetrolistGroup/Metrolist) | ![Stars](https://img.shields.io/github/stars/MetrolistGroup/Metrolist) | ![Downloads](https://img.shields.io/github/downloads/MetrolistGroup/Metrolist/total) |
| [BitChord](https://github.com/kushagrasinghx/BitChord) | ![Stars](https://img.shields.io/github/stars/kushagrasinghx/BitChord) | ![Downloads](https://img.shields.io/github/downloads/kushagrasinghx/BitChord/total) |
| [Vivi Music](https://github.com/vivizzz007/vivi-music) | ![Stars](https://img.shields.io/github/stars/vivizzz007/vivi-music) | ![Downloads](https://img.shields.io/github/downloads/vivizzz007/vivi-music/total) |
| [LastWave Native](https://github.com/Clash-Projects/LastWave-Native) | ![Stars](https://img.shields.io/github/stars/Clash-Projects/LastWave-Native) | ![Downloads](https://img.shields.io/github/downloads/Clash-Projects/LastWave-Native/total) |
| [Echo Music](https://github.com/EchoMusicApp/Echo-Music) | ![Stars](https://img.shields.io/github/stars/EchoMusicApp/Echo-Music) | ![Downloads](https://img.shields.io/github/downloads/EchoMusicApp/Echo-Music/total) |
| [Kreate](https://github.com/knighthat/Kreate) | ![Stars](https://img.shields.io/github/stars/knighthat/Kreate) | ![Downloads](https://img.shields.io/github/downloads/knighthat/Kreate/total) |
| [Muzza](https://github.com/Maloy-Android/Muzza) | ![Stars](https://img.shields.io/github/stars/Maloy-Android/Muzza) | ![Downloads](https://img.shields.io/github/downloads/Maloy-Android/Muzza/total) |
| [N-Zik](https://github.com/N-Zik-Group/N-Zik) | ![Stars](https://img.shields.io/github/stars/N-Zik-Group/N-Zik) | ![Downloads](https://img.shields.io/github/downloads/N-Zik-Group/N-Zik/total) |
| [OpenTune](https://github.com/justshivv/Opentune) | ![Stars](https://img.shields.io/github/stars/justshivv/Opentune) | ![Downloads](https://img.shields.io/github/downloads/justshivv/Opentune/total) |
| [Natune](https://github.com/NatuneGroup/Natune) | ![Stars](https://img.shields.io/github/stars/NatuneGroup/Natune) | ![Downloads](https://img.shields.io/github/downloads/NatuneGroup/Natune/total) |
| [Sonora](https://github.com/gero-dev/sonora) | ![Stars](https://img.shields.io/github/stars/gero-dev/sonora) | ![Downloads](https://img.shields.io/github/downloads/gero-dev/sonora/total) |
| [PixelMusic](https://github.com/Saurav-02/PixelMusic) | ![Stars](https://img.shields.io/github/stars/Saurav-02/PixelMusic) | ![Downloads](https://img.shields.io/github/downloads/Saurav-02/PixelMusic/total) |
| [Avidtune](https://github.com/cgens67/avidtune) | ![Stars](https://img.shields.io/github/stars/cgens67/avidtune) | ![Downloads](https://img.shields.io/github/downloads/cgens67/avidtune/total) |
| [Wavvy](https://github.com/Zev-Kaapor/Wavvy) | ![Stars](https://img.shields.io/github/stars/Zev-Kaapor/Wavvy) | ![Downloads](https://img.shields.io/github/downloads/Zev-Kaapor/Wavvy/total) |
| [Mimikyu](https://github.com/szzsuke/Mimikyu) | ![Stars](https://img.shields.io/github/stars/szzsuke/Mimikyu) | ![Downloads](https://img.shields.io/github/downloads/szzsuke/Mimikyu/total) |
| [Riff](https://github.com/leovaldez2k22/riff) | ![Stars](https://img.shields.io/github/stars/leovaldez2k22/riff) | ![Downloads](https://img.shields.io/github/downloads/leovaldez2k22/riff/total) |
| [Utaloom](https://github.com/cabrata/utaloom) | ![Stars](https://img.shields.io/github/stars/cabrata/utaloom) | ![Downloads](https://img.shields.io/github/downloads/cabrata/utaloom/total) |
| [PaltaSound](https://github.com/AndresTarma1/PaltaSound) | ![Stars](https://img.shields.io/github/stars/AndresTarma1/PaltaSound) | ![Downloads](https://img.shields.io/github/downloads/AndresTarma1/PaltaSound/total) |

>[!NOTE]
> Some projects use InnerTubeX as a dependency, while others vendor or adapt its source code. This is a non-exhaustive list.


## Install

```kotlin
repositories {
    maven("https://jitpack.io") {
        content {
            includeGroup("com.github.MetrolistGroup.innertubex")
            includeGroup("com.github.MetrolistGroup.quackjs")
        }
    }
}

dependencies {
    implementation("com.github.MetrolistGroup.innertubex:innertubex:v0.11.5")
}
```

## Minimal Usage

```kotlin
val client = InnerTube(
    httpClient = HttpClient(OkHttp),
)

val response = client.search(YouTubeClient.WEB, query = "query").body<SearchResponse>()
client.close()
```

The caller owns the supplied `HttpClient`. `InnerTube.close()` cancels only
library-owned visitor-data work and session-bound requests. Authenticated
sessions contain sensitive cookies and SAPISID values; follow
[`SECURITY.md`](SECURITY.md) when storing or logging them.

## Stream Extraction

The reusable extraction stack handles client selection, player configuration,
cipher transforms, PO-token contracts, direct/HLS/SABR transport selection,
format selection, and bounded media probing:

```kotlin
val configStore = RemotePlayerConfigStore(
    httpClient = client.httpClient,
    repository = PlayerConfigRepository.disabled(),
)
val cipher = YouTubeCipherService(client.httpClient, configStore)
val extractor = InnerTubeExtractor(
    configParser = YtConfigParserImpl(client.httpClient, client, configStore, cipherService = cipher),
    cipherService = cipher,
    innerTube = client,
)

val stream = extractor.extract(
    videoId = "dQw4w9WgXcQ",
    hints = ContentHints(wantVideo = false),
    audioQuality = AudioQuality.HIGH,
)
```

### Player cipher backend

The common backend uses hosted `com.github.MetrolistGroup.quackjs:quackjs:0.1.4`,
without JavaScript engines, JNI, or bundled solver scripts. `preloadPlayerCode(url)`
downloads and compiles the player off the UI thread; compiled programs are reused
across batches and single calls. Unsupported players fail closed: affected formats
have no URL and single helpers return null, while unaffected direct URLs remain usable.

`prewarmEjs()` and `setPreprocessedPlayerCache(read, write)` remain binary-compatible
no-ops. The callbacks are never invoked, and existing persistent EJS data is left untouched.

### Optional authenticated TV discovery

Hosts with an independently confirmed Premium entitlement may opt into one
additional, scoped TV comparison:

```kotlin
val stream = extractor.extractWithAuthenticatedTvDiscovery(
    videoId = "dQw4w9WgXcQ",
    confirmedPremium = hostConfirmedPremium,
    credentialProvider = object : TvBearerCredentialProvider {
        override suspend fun getCredential(videoId: String, sessionGeneration: Long) =
            hostAcquireShortLivedTvCredential(sessionGeneration)
        override suspend fun isCredentialCurrent(credential: TvBearerCredential) =
            hostCredentialIsStillCurrent(credential)
    },
    hints = ContentHints(wantVideo = false),
    audioQuality = AudioQuality.HIGH,
)
```

The provider owns consent, acquisition, secure storage, refresh, and revocation.
It should return a short-lived credential scoped to the existing `TVHTML5` or
`TVHTML5_DOWNGRADED` manifest and current session generation. The library calls
this path only when explicitly requested, isolates the bearer from cookies and
media requests, and keeps the ordinary result unless the TV audio result is
strictly higher quality. This is experimental and audio-only: video requests
use ordinary extraction, and Premium does not guarantee a better
response, and no OAuth or credential harvesting is provided.

The catalog also exposes four explicit, unverified probes through the existing
`playbackClientOverrideId` hint: `IOS_MUSIC` (26), `ANDROID_KIDS` (18),
`ANDROID_PRODUCER` (91), and `MEDIA_CONNECT_FRONTEND` (95). They are `PROBE_ONLY`, never
automatic fallbacks, and their content/transport support remains conservative.
No DASH playback is implemented by these probe profiles.

Applications can inject `TokenProvider`, `ClientHealthMonitor`,
`ClientFallbackStrategy`, and `InnerTubeLogger` implementations. Platform token
minting, persistence, playback engines, UI, and application settings remain
host-owned. Never log an `ExtractedStream`, PO-token values, signed URLs, or
authenticated session fields; library model `toString()` implementations redact
those values as defense in depth.

## Standalone playback diagnostics

Run `./gradlew :harness:run --args='--help'` to see the offline CLI help, or
`--args='--list-clients'` for the offline profile catalog. The source-built
JVM harness performs opt-in 90-second paced audio playback with real backward/forward
transport seeks, HLS/direct/SABR profiles, a quick `--seconds` smoke mode and
sanitized local reports. Run `--help` for options. Live checks are never part
of `allTests`.

## Development

To test changes in the sibling `../quackjs` checkout without changing the hosted
dependency, pass `-PuseLocalQuackJS`. This also works when Metrolist includes
InnerTubeX with `-PuseLocalInnerTubeX -PuseLocalQuackJS`.

Watch configs that advertise `player_es6` first try the standard `player_ias`
build for the same player hash, including when remote hints are unavailable.
A failed preferred build still falls back to the exact supplied variant.

Unsupported player compilations retain their bounded cooldown, but subsequent
attempts report the cached diagnostic reason. Compile diagnostics are bounded and
URLs are redacted. A playable response with unusable audio is reported as
`NO_PLAYABLE_STREAM`, including explicit tracks; an explicit metadata hint alone
does not establish a content restriction.

```bash
./gradlew ktlintFormat
./gradlew allTests ktlintCheck apiCheck assemble publishToMavenLocal
```

Enable the repository hook with `git config core.hooksPath .githooks`.
Contributions and releases are documented in [`CONTRIBUTING.md`](CONTRIBUTING.md)
and [`RELEASING.md`](RELEASING.md).

## Status

InnerTubeX uses experimental `0.x` versioning. YouTube can change private APIs,
player scripts, and SABR behavior without notice, so pin exact versions and
review [`CHANGELOG.md`](CHANGELOG.md) before upgrading.

## Disclaimer

InnerTubeX is an independent open-source project. It is not affiliated with,
endorsed by, or sponsored by YouTube or Google. YouTube and Google are
trademarks of their respective owners. Users are responsible for complying
with applicable terms of service and law.

## License

[GNU General Public License v3.0](LICENSE)
