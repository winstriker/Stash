# v0.9.10 — Monochrome (Tidal) as a Sibling Lossless Source

**Date:** 2026-05-03
**Status:** Design
**Branch:** `feat/amz-squid-source` (worktree path: `.worktrees/amz-source`; branch name is a historical artifact from when the brainstorm started against amz.squid.wtf — final design uses Monochrome's API instead)

## Problem

Stash currently has exactly one lossless source: `qobuz.squid.wtf`. Both `qobuz.squid.wtf` and the related `tidal.squid.wtf` have been intermittently down (the operator's public status page shows Tidal at **46.8% uptime** since mid-March 2026; Qobuz outages have been reported by users). When the source goes down, downloads silently fall back to MP3 via yt-dlp — functionally a regression for users who have explicitly opted into lossless.

The squid.wtf model is operator-credentialed: a single anonymous developer pays for upstream Qobuz/Tidal accounts and proxies them on everyone's behalf. The architecture is structurally fragile because (a) Qobuz/Tidal block accounts en masse, (b) one developer's infrastructure has correlated failure modes, and (c) there is no operational redundancy.

A research pass (commit history, GitHub repo audits, live endpoint probes) ruled out most candidate alternatives:
- `lucida.to` and `doubledouble.top` — no public REST APIs (TS library and explicitly anti-bot, respectively).
- `cobalt-tools/cobalt` — does not output FLAC; only `mp3/ogg/wav/opus`.
- SpotiFLAC's "free no-auth" APIs (`amazon.spotbye.qzz.io`, `musicdl.me`, `dabmusic.xyz`, `dab.yeet.su`) — all single-anonymous-operator with obfuscated rotating `X-Debug-Key` headers baked into the SpotiFLAC binary, OR currently dead/in-maintenance. Adding any would import their fragility.
- Credentialed sources (yt-dlp Qobuz extractor, deemix Deezer) — rejected by the user; Stash's no-auth posture is a load-bearing UX choice.

The one surviving candidate is **Monochrome** (`monochrome-music/monochrome`, 3.3k⭐, Apache-2.0, daily commits as of May 2026). Its hosted API at `https://api.monochrome.tf` runs `uimaxbai/hifi-api` (MIT, fork of `binimum/hifi-api`) and serves Tidal's catalog — Lossless 16-bit/44.1kHz FLAC delivered as plain HTTP `BTS` (Bytes-Track-Stream) URLs without DRM at the lossless tier. Different operator from squid.wtf; different upstream service (Tidal vs Qobuz); different account pool. The risk model is the same shape (operator-credentialed, account-block-vulnerable) but the failure modes are uncorrelated with squid.wtf's.

## Goals

- Stash has **two operator-credentialed lossless sources** registered in `LosslessSourceRegistry`: `squid_qobuz` (existing) and `monochrome_tidal` (new).
- The existing registry's priority order + `AggregatorRateLimiter` circuit-breaker handle failover automatically. When one source is down, the other is tried; when both are down, downloads fall through to yt-dlp/MP3 as today.
- Different upstream catalogs mean tracks Qobuz lacks may exist on Tidal — a usability win on top of the redundancy win.
- No new user-facing UI surfaces. The existing `losslessEnabled` Settings toggle continues to gate the entire chain.
- v0.9.10 ships as a focused fix-and-extend release; no scope creep.

## Non-goals

- **No Hi-Res (24-bit) support.** Tidal Hi-Res requires Widevine DRM + MPD manifest handling on Android — significant Android-platform complexity. Lossless 16/44.1 (CD-quality FLAC) is the target. Hi-Res is deferred indefinitely; may never make sense to add.
- **No per-source UI** — toggles, priority reorder, source-health display in Settings. Defer to a future release where 3+ sources make a UI worthwhile.
- **No self-hosted instance picker.** The hosted `api.monochrome.tf` is hardcoded as the only base URL for v0.9.10. A configurable `MONOCHROME_API_URL` setting can land later.
- **No `stash-aggregator` server-side router.** Two sources don't justify Stash running its own infrastructure.
- **No Tidal-specific feature surfaces** — lyrics via `/lyrics/`, recommendations via `/recommendations/`. Out of scope.
- **No metadata-enhancement adoption** of song.link / Deezer ISRC / MusicBrainz / lrclib (which SpotiFLAC also uses). Separate spec, separate value proposition.
- **No "What's new" modal.** Silent improvement; the user sees their lossless downloads succeed more often.

## Design

### 1. New `MonochromeApiClient`

**Location:** `data/download/src/main/kotlin/com/stash/data/download/lossless/monochrome/MonochromeApiClient.kt`

Mirrors the shape of the existing `QobuzApiClient.kt`:

- `@Singleton` class with `@Inject` constructor taking `OkHttpClient` (from `:core:network`).
- `internal var httpClient: OkHttpClient` — test seam (test-only assignment). Derived client built from the shared client (`sharedClient.newBuilder().build()`). No interceptor needed (Monochrome's API is unauthenticated for callers; the operator's Tidal token is server-side).
- `internal var baseUrl: String = DEFAULT_BASE_URL` — test seam (test-only assignment). `companion object { const val DEFAULT_BASE_URL = "https://api.monochrome.tf" }`.
- `internal var json: Json = DEFAULT_JSON` — test seam (test-only assignment). `Json { ignoreUnknownKeys = true; coerceInputValues = true }`. Defensive against upstream API additions.

**Endpoints implemented** (from the probe of `uimaxbai/hifi-api`):

```kotlin
suspend fun search(query: String, limit: Int = 10): TidalSearchResponse
// GET /search/?s={url-encoded query}&limit={limit}
// Returns TidalSearchResponse with tracks.items: List<TidalTrack>

suspend fun track(id: Long, quality: String = "LOSSLESS"): TidalStreamManifest
// GET /track/?id={id}&quality={quality}
// Returns TidalStreamManifest with manifestType + urls
```

Both methods throw `MonochromeApiException` on non-2xx or parse failure — caller (`MonochromeSource.callLimited`) catches and reports failure to `AggregatorRateLimiter` cleanly.

### 2. New `MonochromeModels`

**Location:** `data/download/.../lossless/monochrome/MonochromeModels.kt`

`@Serializable` data classes for the response shapes:

```kotlin
@Serializable
data class TidalSearchResponse(
    val tracks: TidalTrackList? = null,
)

@Serializable
data class TidalTrackList(
    val items: List<TidalTrack> = emptyList(),
)

@Serializable
data class TidalTrack(
    val id: Long,
    val title: String,
    val duration: Int,                  // seconds
    val isrc: String? = null,
    val artist: TidalArtist? = null,
    val artists: List<TidalArtist>? = null,  // multi-artist
    val album: TidalAlbum? = null,
)

@Serializable
data class TidalArtist(val id: Long? = null, val name: String)

@Serializable
data class TidalAlbum(val id: Long? = null, val title: String? = null, val cover: String? = null)

@Serializable
data class TidalStreamManifest(
    val manifest: String? = null,           // base64-encoded for MPD; not used for BTS
    @SerialName("manifestType") val manifestType: String,  // "BTS" | "MPD"
    val urls: List<String> = emptyList(),
)
```

Plus a private `coverIdToUrl(cover: String, size: String = "1280x1280"): String?` helper:
- Tidal cover IDs are dash-separated (`"abc-def-ghi"`).
- URL pattern: `https://resources.tidal.com/images/abc/def/ghi/1280x1280.jpg` (replace dashes with slashes, append size + `.jpg`).
- Returns null if the cover string format doesn't match.

### 3. New `MonochromeSource`

**Location:** `data/download/.../lossless/monochrome/MonochromeSource.kt`

Implements `LosslessSource`. Mirrors `QobuzSource.kt`'s structure exactly (search → score → resolve → build SourceResult), with three Tidal-specific differences:

1. **Search uses `artist + " " + title`**, not ISRC. ISRC isn't a Tidal-native search index. Confidence scoring still boosts ISRC matches when the candidate's `track.isrc` happens to match the snapshot's ISRC.
2. **Quality fixed to `"LOSSLESS"`.** No `QobuzQuality` enum equivalent — the only quality this source serves is 16/44.1 FLAC. If `manifestType` returns anything other than `"BTS"` (e.g. `"MPD"` for a Hi-Res-only track in a region without lossless rights), return null and let the registry try the next source.
3. **Format is hardcoded `AudioFormat(codec = "flac", bitrateKbps = 1411, sampleRateHz = 44100, bitsPerSample = 16)`.** Tidal Lossless is uncompressed-equivalent CD FLAC — these numbers are constant for this tier. No per-track variability.

```kotlin
@Singleton
class MonochromeSource @Inject constructor(
    private val apiClient: MonochromeApiClient,
    private val rateLimiter: AggregatorRateLimiter,
) : LosslessSource {

    override val id: String = SOURCE_ID
    override val displayName: String = "Tidal (via Monochrome)"

    override suspend fun isEnabled(): Boolean =
        !rateLimiter.stateOf(id).isCircuitBroken

    override suspend fun resolve(query: TrackQuery): SourceResult? {
        val searchTerm = "${query.artist} ${query.title}"
        val searchData = callLimited { apiClient.search(searchTerm) } ?: return null

        val candidates = searchData.tracks?.items.orEmpty()
        if (candidates.isEmpty()) return null

        val scored = candidates.map { it to confidence(query, it) }
        val best = scored.filter { it.second >= MIN_CONFIDENCE }.maxByOrNull { it.second }
            ?: run {
                val top = scored.sortedByDescending { it.second }.take(3)
                Log.d(TAG, "no candidate above $MIN_CONFIDENCE for '${query.artist} - ${query.title}': " +
                    top.joinToString(", ") { (c, s) -> "[${"%.2f".format(s)} '${c.title}' by '${c.firstArtist()}']" })
                return null
            }

        val manifest = callLimited { apiClient.track(best.first.id, "LOSSLESS") } ?: return null

        if (manifest.manifestType != "BTS") {
            Log.d(TAG, "non-BTS manifest type=${manifest.manifestType} for track ${best.first.id}")
            return null
        }
        val downloadUrl = manifest.urls.firstOrNull() ?: return null

        return SourceResult(
            sourceId = id,
            downloadUrl = downloadUrl,
            downloadHeaders = emptyMap(),
            format = AudioFormat(codec = "flac", bitrateKbps = 1411, sampleRateHz = 44100, bitsPerSample = 16),
            confidence = best.second,
            sourceTrackId = best.first.id.toString(),
            coverArtUrl = best.first.album?.cover?.let { coverIdToUrl(it) },
        )
    }

    override suspend fun rateLimitState(): RateLimitState = rateLimiter.stateOf(id)

    // ── Confidence scoring (mirrors QobuzSource) ─────────────────────
    private fun confidence(query: TrackQuery, candidate: TidalTrack): Float { /* … */ }

    // ── Rate-limited call wrapper (mirrors QobuzSource.callLimited) ──
    private suspend fun <T> callLimited(block: suspend () -> T): T? { /* … */ }

    companion object {
        const val SOURCE_ID = "monochrome_tidal"
        private const val TAG = "MonochromeSource"
        private const val MIN_CONFIDENCE = 0.7f
    }
}
```

Both `confidence(...)` and `callLimited<T>(...)` are direct ports of QobuzSource's implementations — same scoring weights, same rate-limiter signal flow (`acquire`, `reportSuccess`, `reportRateLimited` on 429, `reportFailure` on other exceptions).

### 4. New Hilt module

**Location:** `data/download/.../lossless/monochrome/di/MonochromeModule.kt`

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class MonochromeModule {
    @Binds
    @IntoSet
    abstract fun bindMonochromeAsLosslessSource(impl: MonochromeSource): LosslessSource
}
```

Mirrors `QobuzModule.kt`. The `@IntoSet` adds `MonochromeSource` to the `Set<LosslessSource>` multibinding consumed by `LosslessSourceRegistry`. No changes needed to `LosslessSourceRegistry` or `LosslessModule`.

### 5. Default priority order update

**Location:** `data/download/.../lossless/LosslessSourcePreferences.kt`

The `priorityOrder` Flow currently reads from DataStore with empty list as fallback. The registry treats empty as "use registration order." For v0.9.10, we want a defined default that prefers `squid_qobuz` first (the historically-integrated source with proven matching) and `monochrome_tidal` second:

**Approach:** add a constant `DEFAULT_PRIORITY = listOf("squid_qobuz", "monochrome_tidal")` and have `priorityOrder.map { it.ifEmpty { DEFAULT_PRIORITY } }`. Existing users with explicitly-saved priority preserve their value. Fresh installs (DataStore empty) get the new default.

This is a one-method-one-constant change in `LosslessSourcePreferences.kt`. Verified that no other caller writes to `priorityKey` outside `setPriorityOrder`, so the migration is purely additive.

### 6. Version bump

`app/build.gradle.kts`: `versionCode 46 → 47`, `versionName "0.9.9" → "0.9.10"`.

## Risks

| Risk | Mitigation |
|---|---|
| **Operator's Tidal account blocked** (`uimaxbai/hifi-api` README explicitly warns this is happening "en masse"). | `AggregatorRateLimiter` circuit-breaks `monochrome_tidal` after consecutive 401/403/5xx. Registry skips it; falls over to qobuz.squid (or yt-dlp if both are down). User-facing: silent failover identical to qobuz being down today. No regression vs v0.9.9. |
| **`quality=LOSSLESS` returns Widevine `MPD` manifest for some tracks** (regional licensing variance — a track may be Hi-Res-only in a country and have no lossless tier). | Source returns null for `manifestType != "BTS"`. Registry skips, tries next source. Logged via `Log.d` so we have signal if this is common. |
| **Tidal track ID returns the wrong variant** (remix/explicit/album version mismatch with what the user asked for). | Same scoring + `MIN_CONFIDENCE = 0.7f` threshold as QobuzSource. Mis-matched candidates fall below threshold; source returns null and registry tries next. Same proven pattern as Qobuz. |
| **Monochrome's API contract shifts** (`uimaxbai/hifi-api` has 800 commits — actively moving). | Single-file API client (`MonochromeApiClient.kt`) — an API change is a one-file edit. `Json { ignoreUnknownKeys = true }` makes additions backward-compatible. |
| **Tidal cover URL pattern changes** (the `resources.tidal.com/images/<dashes-to-slashes>/<size>.jpg` pattern is convention, not contract). | `coverArtUrl` is a soft dependency. If `coverIdToUrl` returns null or the URL 404s, the existing Last.fm cover-art resolution path takes over. No download regression. |
| **Both sources fail simultaneously** (correlated outage from a wide industry shock — Qobuz + Tidal both rotate APIs in the same week). | Existing yt-dlp/MP3 fallback catches this. Same UX as v0.9.9 today when squid is down. No regression. |
| **License obligation on attribution** (`uimaxbai/hifi-api` is MIT, `monochrome-music/monochrome` is Apache-2.0). | Stash makes HTTP calls; doesn't ship their code. Add a one-line credit to the Settings → About screen alongside the existing squid.wtf credit. No GPL conflict (Stash is GPL-3.0; both source licenses are GPL-compatible). |
| **Branch name `feat/amz-squid-source` is misleading** — design pivoted to Monochrome during brainstorming. | Acknowledged in spec header; commits + spec contents are clearly labeled. Renaming the branch + worktree directory adds churn for no benefit. |

## Testing

### Unit tests

None added. Same precedent as `QobuzSource` and the rest of the project. Stash's discipline is on-device acceptance, not unit-test coverage of single-class HTTP clients (which are mostly thin wrappers over Retrofit-style calls).

### Manual acceptance (signed release sideload)

1. **Cold start with `losslessEnabled = true`:** Open Stash, search and download a track known to be in BOTH Qobuz and Tidal catalogs (any popular Western release post-2010 should qualify). Confirm it downloads as FLAC. Most likely served by qobuz.squid (priority position 0).

2. **Force qobuz.squid miss:** Pick a track Qobuz doesn't carry (some Tidal-exclusive releases, certain Asian-region albums, some artist deluxe-edition variants). Confirm Stash returns from QobuzSource with null and falls over to MonochromeSource, which delivers FLAC.

3. **Tidal-specific cover art:** After (2), confirm the downloaded track has cover art (sourced from Tidal's CDN via `coverIdToUrl`, or via existing Last.fm fallback).

4. **Network-flake / circuit-breaker:** Disable network mid-Monochrome download. Confirm `AggregatorRateLimiter` reports failure correctly. Re-enable network; confirm next download attempt succeeds (circuit hasn't tripped from a single failure).

5. **yt-dlp fallback still works** when both Qobuz and Monochrome miss: pick a track on neither (a YouTube-only upload). Confirm Stash downloads at MP3 320 as today.

6. **Settings → About:** confirm the "Monochrome" credit line is present.

### Regression check

- Downloads that previously succeeded via qobuz.squid still succeed via qobuz.squid (`squid_qobuz` retains priority position 0 by default).
- The `losslessEnabled` Settings toggle still gates the entire chain.
- `LosslessSourceRegistry.allWithState()` (the API used by any Settings → diagnostics surface) returns both sources without error.

## Out of scope

- Settings UI for per-source toggle / reorder / health display (deferred to a future release once 3+ sources warrant the surface area).
- Self-hostable instance picker (configurable `MONOCHROME_API_URL` and per-instance health state).
- Hi-Res 24-bit support (Widevine + MPD on Android — major separate effort, may never ship).
- Tidal lyrics, recommendations, or other catalog-side features.
- A `stash-aggregator` server-side router project.
- Adding a third lossless source. The architecture supports it, but every additional source carries operator-fragility risk; ship two well first, add a third only if it passes the same selection framework (public REST API, no user creds, active maintenance, license-compatible, different operator).
- Metadata-enhancement work using SpotiFLAC's stable third-party APIs (song.link, MusicBrainz, lrclib, Deezer ISRC). Separate spec.

## Ship as

v0.9.10. Standalone fix-and-extend release. Bumps `versionCode 46 → 47` and `versionName "0.9.9" → "0.9.10"` in `app/build.gradle.kts`.
