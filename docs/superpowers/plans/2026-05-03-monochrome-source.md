# v0.9.10 — Monochrome (Tidal) Lossless Source Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `MonochromeSource` (id `"monochrome_tidal"`) as a sibling lossless source alongside the existing `QobuzSource`. Hits `https://api.monochrome.tf` for Tidal Lossless 16-bit/44.1 FLAC. Slots into the existing `LosslessSourceRegistry` via Hilt multibinding — no registry / interface / rate-limiter changes.

**Architecture:** Four new files in a new `data/download/lossless/monochrome/` package. One existing file modified (`LosslessSourcePreferences.kt`) to add a default priority order. App version bumped. No DB schema changes, no new UI, no new Settings surfaces.

**Tech Stack:** Kotlin, OkHttp (existing shared `:core:network` client), kotlinx-serialization (existing), Hilt multibinding (existing). Base64 decoding via `java.util.Base64`.

**Spec:** `docs/superpowers/specs/2026-05-03-monochrome-source-design.md`

**Implementation note vs spec:** During plan-writing we read the source of `dbv111m/musicgrabber` (the reference Python client for Monochrome) and discovered the actual API response shape differs from what the spec assumed:

- Spec assumed direct `tracks.items[]` shape and `manifestType: "BTS" | "MPD"` on the track endpoint.
- **Reality:** Both `/search/` and `/track/` wrap their payload in a `{"data": {...}}` envelope. Search returns `data.items[]` (not `data.tracks.items[]`). Track returns `data.manifest` as a **base64-encoded JSON string** that decodes to `{encryptionType: String, urls: List<String>}`.
- The spec's `manifestType != "BTS"` guard is replaced with `encryptionType != "NONE"` — same intent (skip Widevine/encrypted streams), correct field name.

The plan code blocks reflect the corrected reality. Behavior matches the spec; the data model differs.

---

## Pre-flight

The worktree at `.worktrees/amz-source` was created from `master` after v0.9.9 shipped. The spec has been committed on branch `feat/amz-squid-source`. (Branch name is a historical artifact from the original amz.squid.wtf framing — kept as-is to avoid worktree-rename overhead.)

- [ ] **Confirm branch + clean tree**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/amz-source
git branch --show-current
git status --short
```

Expected:
- Current branch: `feat/amz-squid-source`
- No `M`/`A`/`D` lines for production files (only `??` brainstorm/scratch artefacts are fine)

- [ ] **Confirm spec is committed on this worktree**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/amz-source
git log --oneline master..HEAD
```

Expected at least:
- `4ff46a3` — spec advisory cleanups
- `6735a26` — initial spec

---

## File-touched map

| File | Module | Why |
|---|---|---|
| `data/download/src/main/kotlin/com/stash/data/download/lossless/monochrome/MonochromeModels.kt` (new) | `:data:download` | `@Serializable` data classes for search + track manifest envelopes; `coverIdToUrl` helper; `decodeManifest` helper |
| `data/download/src/main/kotlin/com/stash/data/download/lossless/monochrome/MonochromeApiClient.kt` (new) | `:data:download` | HTTP client mirroring `QobuzApiClient` shape: derived `httpClient`, `baseUrl` test seam, `search()` + `track()` suspend methods |
| `data/download/src/main/kotlin/com/stash/data/download/lossless/monochrome/MonochromeSource.kt` (new) | `:data:download` | Implements `LosslessSource`. id = `"monochrome_tidal"`. Search → score → resolve to plain FLAC URL. Encryption check rejects non-`NONE` |
| `data/download/src/main/kotlin/com/stash/data/download/lossless/monochrome/di/MonochromeModule.kt` (new) | `:data:download` | Hilt `@Binds @IntoSet` registering MonochromeSource into `Set<LosslessSource>` |
| `data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessSourcePreferences.kt` (modify) | `:data:download` | Add `DEFAULT_PRIORITY = listOf("squid_qobuz", "monochrome_tidal")` constant; `priorityOrder` Flow uses it as `ifEmpty` fallback |
| `app/build.gradle.kts` (modify) | `:app` | `versionCode 46 → 47`, `versionName "0.9.9" → "0.9.10"` |

Net change: 4 new files (~250 LOC total), 1 file modified (~5 LOC), version bump.

---

## Task 1: Create `MonochromeModels.kt`

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/monochrome/MonochromeModels.kt`

- [ ] **Step 1: Read existing `QobuzModels.kt` for style reference**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/amz-source
cat data/download/src/main/kotlin/com/stash/data/download/lossless/qobuz/QobuzModels.kt | head -80
```

Note the conventions:
- `@Serializable` on every data class
- `kotlinx.serialization.SerialName` annotations where the JSON field name differs from the Kotlin property name
- KDoc comments at top of file naming the upstream API
- Default values on every nullable field (`null`) and every list (`emptyList()`)

- [ ] **Step 2: Write `MonochromeModels.kt`**

Create the file with the following content. The data classes correspond to the API contract observed in `dbv111m/musicgrabber` (commit at master HEAD as of plan-writing — fields documented in `search.py` and `downloads.py`).

```kotlin
package com.stash.data.download.lossless.monochrome

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Response shapes for `https://api.monochrome.tf` (running uimaxbai/hifi-api,
 * a fork of binimum/hifi-api, MIT-licensed). The API serves Tidal's catalog
 * with the operator-funded model: the operator runs hifi-api with their own
 * paid Tidal token, end users get unauthenticated access.
 *
 * All endpoints wrap their payload in a top-level `{"data": ...}` envelope.
 * Track-stream manifests are themselves base64-encoded JSON inside the
 * envelope — see [decodeManifest].
 *
 * Reference client (Python): github.com/dbv111m/musicgrabber — see
 * `search.py::_search_monochrome_api` and `downloads.py::_download_monochrome_direct`.
 */

// ── /search/ response ────────────────────────────────────────────────

@Serializable
data class TidalSearchResponse(
    val data: TidalSearchData? = null,
)

@Serializable
data class TidalSearchData(
    val items: List<TidalTrack> = emptyList(),
)

/**
 * Tidal track item from the search response. ISRC is sometimes present
 * (varies by upstream catalog row) — when both query and candidate carry
 * one we short-circuit confidence to 0.95.
 */
@Serializable
data class TidalTrack(
    val id: Long,
    val title: String,
    val duration: Int = 0,                   // seconds
    val isrc: String? = null,
    val streamReady: Boolean = false,
    val audioQuality: String? = null,        // "HI_RES_LOSSLESS" | "LOSSLESS" | "HIGH" | etc.
    val popularity: Int? = null,
    val artist: TidalArtist? = null,
    val artists: List<TidalArtist>? = null,  // multi-artist tracks
    val album: TidalAlbum? = null,
)

@Serializable
data class TidalArtist(
    val id: Long? = null,
    val name: String = "",
)

@Serializable
data class TidalAlbum(
    val id: Long? = null,
    val title: String? = null,
    val cover: String? = null,               // dash-separated UUID, e.g. "abc-def-ghi"
)

// ── /track/ response (envelope) ──────────────────────────────────────

@Serializable
data class TidalTrackResponse(
    val data: TidalTrackData? = null,
)

@Serializable
data class TidalTrackData(
    val manifest: String? = null,            // base64-encoded JSON; decode with decodeManifest()
)

/**
 * Decoded payload from [TidalTrackData.manifest]. The manifest itself is
 * base64-encoded JSON inside the API response.
 *
 * `encryptionType == "NONE"` means BTS (plain HTTP segments — what Stash
 * needs). Anything else (e.g. `"OLD_AES"` for legacy Tidal MPD) is a
 * Widevine/encrypted stream and must be skipped — Stash's download path
 * does not handle DRM.
 */
@Serializable
data class TidalDecodedManifest(
    val encryptionType: String = "NONE",
    val urls: List<String> = emptyList(),
    val mimeType: String? = null,            // typically "audio/flac" for LOSSLESS
)

// ── Helpers ──────────────────────────────────────────────────────────

/**
 * Decodes a base64-encoded JSON manifest string into [TidalDecodedManifest].
 * Returns null on any parse failure (caller treats as "skip this track").
 */
internal fun decodeManifest(base64Manifest: String, json: Json): TidalDecodedManifest? = runCatching {
    val decodedBytes = java.util.Base64.getDecoder().decode(base64Manifest)
    val decodedJson = String(decodedBytes, Charsets.UTF_8)
    json.decodeFromString(TidalDecodedManifest.serializer(), decodedJson)
}.getOrNull()

/**
 * Tidal cover IDs are stored as dash-separated UUIDs (e.g. "abc-def-ghi").
 * The CDN URL replaces dashes with slashes and appends a size + extension.
 *
 * Returns null if the input doesn't look like a Tidal cover UUID (basic
 * sanity guard — empty string, no dashes, etc.).
 */
internal fun coverIdToUrl(cover: String?, size: String = "1280x1280"): String? {
    if (cover.isNullOrBlank()) return null
    if (!cover.contains('-')) return null  // not a Tidal-shape UUID
    return "https://resources.tidal.com/images/${cover.replace('-', '/')}/$size.jpg"
}
```

- [ ] **Step 3: Build the `:data:download` module**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/amz-source
./gradlew :data:download:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. If kotlinx-serialization complains about a missing `@Serializable` annotation, re-check Step 2.

- [ ] **Step 4: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/amz-source
git add data/download/src/main/kotlin/com/stash/data/download/lossless/monochrome/MonochromeModels.kt
git commit -m "feat(monochrome): data models for api.monochrome.tf

Adds @Serializable response data classes for /search/ and /track/
endpoints. Includes:
- TidalSearchResponse / TidalSearchData / TidalTrack / TidalArtist / TidalAlbum
- TidalTrackResponse / TidalTrackData (envelope shape with base64 manifest)
- TidalDecodedManifest (post-base64-decode payload with encryptionType + urls)
- decodeManifest(): base64 → JSON → TidalDecodedManifest
- coverIdToUrl(): Tidal cover UUID → resources.tidal.com CDN URL

Reference: github.com/dbv111m/musicgrabber (Python implementation
of the same API).

Spec: docs/superpowers/specs/2026-05-03-monochrome-source-design.md
"
```

---

## Task 2: Create `MonochromeApiClient.kt`

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/monochrome/MonochromeApiClient.kt`

- [ ] **Step 1: Read existing `QobuzApiClient.kt` end-to-end for style reference**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/amz-source
cat data/download/src/main/kotlin/com/stash/data/download/lossless/qobuz/QobuzApiClient.kt
```

Note the patterns:
- `@Singleton` `@Inject constructor(sharedClient: OkHttpClient, ...)`
- `internal var httpClient: OkHttpClient = sharedClient.newBuilder()...build()` (test seam)
- `internal var baseUrl: String = DEFAULT_BASE_URL` (test seam)
- `internal var json: Json = DEFAULT_JSON` (test seam)
- `companion object { const val DEFAULT_BASE_URL = "https://..." }`
- Each endpoint method is a `suspend fun` doing `withContext(Dispatchers.IO)`
- Response unwrapping + `QobuzApiException` on non-2xx or `success: false` envelopes
- URL building via `okhttp3.HttpUrl.Companion.toHttpUrl()`

The Monochrome client mirrors all of this. Key difference: Monochrome's API doesn't use a `success` boolean — it uses HTTP status only. Throw `MonochromeApiException` on non-2xx or empty `data` envelope.

- [ ] **Step 2: Write `MonochromeApiClient.kt`**

```kotlin
package com.stash.data.download.lossless.monochrome

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * HTTP client for `https://api.monochrome.tf` (uimaxbai/hifi-api, MIT,
 * fork of binimum/hifi-api). Serves Tidal's catalog with the operator-
 * funded model — no auth required from callers.
 *
 * The API is unauthenticated for Stash's purposes, but some hosted
 * instances may filter by Origin / User-Agent. We send a stable
 * User-Agent to avoid being misidentified as bot traffic.
 *
 * Every response uses a `{"data": ...}` envelope; the client unwraps it
 * and throws [MonochromeApiException] on non-2xx, empty `data`, or parse
 * failure so callers can branch on a single signal.
 */
@Singleton
class MonochromeApiClient @Inject constructor(
    sharedClient: OkHttpClient,
) {

    /**
     * Test seam (test-only assignment). Derived from the shared client to
     * reuse its connection pool, dispatcher, TLS config. No interceptors
     * needed — the API is unauthenticated for callers.
     */
    internal var httpClient: OkHttpClient = sharedClient.newBuilder().build()

    /** Test seam (test-only assignment). */
    internal var baseUrl: String = DEFAULT_BASE_URL

    /** Test seam (test-only assignment). */
    internal var json: Json = DEFAULT_JSON

    /**
     * Search the Monochrome-proxied Tidal catalog. [query] is free text
     * (artist + title); the API returns up to ~25 candidates.
     *
     * Throws [MonochromeApiException] on network/parse failure.
     */
    suspend fun search(query: String): TidalSearchResponse = withContext(Dispatchers.IO) {
        val url = "$baseUrl/search/".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        executeAndParse(request, TidalSearchResponse.serializer())
    }

    /**
     * Resolve a Tidal track ID to a stream manifest. Quality is hardcoded
     * to `"LOSSLESS"` — Stash never wants HI_RES_LOSSLESS (Widevine) or
     * HIGH (lossy AAC).
     *
     * Throws [MonochromeApiException] on network/parse failure. Returns
     * a [TidalTrackResponse] whose `data.manifest` must be base64-decoded
     * via [decodeManifest] to get the actual download URLs.
     */
    suspend fun track(id: Long, quality: String = "LOSSLESS"): TidalTrackResponse =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/track/".toHttpUrl().newBuilder()
                .addQueryParameter("id", id.toString())
                .addQueryParameter("quality", quality)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            executeAndParse(request, TidalTrackResponse.serializer())
        }

    private fun <T> executeAndParse(
        request: Request,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T {
        val response = httpClient.newCall(request).execute()
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw MonochromeApiException(
                    status = it.code,
                    message = "HTTP ${it.code} from ${request.url}: ${body.take(200)}",
                )
            }
            return try {
                json.decodeFromString(serializer, body)
            } catch (e: Exception) {
                throw MonochromeApiException(
                    status = it.code,
                    message = "Parse failed for ${request.url}: ${e.message}",
                    cause = e,
                )
            }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.monochrome.tf"
        private const val USER_AGENT: String = "Stash-Android/1.0 (+https://github.com/rawnaldclark/Stash)"

        private val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }
}

/**
 * Exception thrown by [MonochromeApiClient] on any non-2xx or parse
 * failure. The [status] field lets callers branch on 429 vs 401/403 vs
 * 5xx for rate-limiter signaling.
 */
class MonochromeApiException(
    val status: Int,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
```

- [ ] **Step 3: Build**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/amz-source
./gradlew :data:download:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/amz-source
git add data/download/src/main/kotlin/com/stash/data/download/lossless/monochrome/MonochromeApiClient.kt
git commit -m "feat(monochrome): HTTP client for api.monochrome.tf

Mirrors QobuzApiClient.kt's shape:
- @Singleton @Inject constructor taking shared OkHttpClient
- internal var test seams for httpClient, baseUrl, json
- companion object DEFAULT_BASE_URL = 'https://api.monochrome.tf'
- search(query) and track(id, quality) suspend methods on Dispatchers.IO
- MonochromeApiException on non-2xx or parse failure

Sends a stable User-Agent (Stash-Android/1.0 + repo URL) to avoid bot
heuristics. No authentication needed; the operator's Tidal token is
server-side.
"
```

---

## Task 3: Create `MonochromeSource.kt`

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/monochrome/MonochromeSource.kt`

- [ ] **Step 1: Re-read existing `QobuzSource.kt` end-to-end**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/amz-source
cat data/download/src/main/kotlin/com/stash/data/download/lossless/qobuz/QobuzSource.kt
```

Note in particular:
- Lines 137-173: `callLimited<T>` private function. Direct port to MonochromeSource (with `MonochromeApiException` instead of `QobuzApiException`, and **no captcha-expired branch** — Monochrome has no captcha cookie).
- Lines 182-225: `confidence(query, candidate)` private function. Direct port with two changes: (a) operates on `TidalTrack` not `QobuzTrack`; (b) drops the `if (!candidate.streamable) return 0f` check — Tidal exposes `streamReady` instead, which is checked at the candidate-filter step before scoring.
- Lines 235-280+ in companion object: `normalize`, `jaccard`, `artistSimilarity`. **Direct copy** — these are pure-string helpers with no source-specific dependencies. (A future shared-helpers refactor would extract these once a third source joins; for two sources YAGNI says copy.)

- [ ] **Step 2: Write `MonochromeSource.kt`**

```kotlin
package com.stash.data.download.lossless.monochrome

import android.util.Log
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * [LosslessSource] backed by Tidal's catalog via `api.monochrome.tf`
 * (uimaxbai/hifi-api fork running at the Monochrome operator's hosted
 * instance). Searches for the requested track, scores candidates by
 * ISRC / title / artist / duration agreement, and resolves to a plain
 * FLAC URL from Tidal's CDN.
 *
 * Quality is hardcoded to `"LOSSLESS"` (16-bit/44.1 FLAC). Hi-Res
 * (`HI_RES_LOSSLESS`) requires Widevine DRM and is out of scope for v0.9.10.
 * If the manifest's `encryptionType` is anything other than `"NONE"`,
 * the source returns null and the registry tries the next source.
 *
 * Sibling to [com.stash.data.download.lossless.qobuz.QobuzSource]. Same
 * operator-credentialed risk model — different operator, different
 * upstream service, different account pool, uncorrelated outage risk.
 */
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
        // 1. Search.
        val searchTerm = "${query.artist} ${query.title}"
        val searchData = callLimited { apiClient.search(searchTerm) } ?: return null

        val candidates = searchData.data?.items.orEmpty()
            .filter { it.streamReady }
        if (candidates.isEmpty()) return null

        // 2. Score and pick the best candidate.
        val scored = candidates.map { it to confidence(query, it) }
        val best = scored
            .filter { it.second >= MIN_CONFIDENCE }
            .maxByOrNull { it.second }

        if (best == null) {
            val top = scored.sortedByDescending { it.second }.take(3)
            Log.d(
                TAG,
                "no candidate above $MIN_CONFIDENCE for '${query.artist} - ${query.title}': " +
                    top.joinToString(", ") { (c, s) ->
                        "[${"%.2f".format(s)} '${c.title}' by '${c.firstArtistName()}']"
                    },
            )
            return null
        }

        // 3. Resolve to stream manifest. Hardcoded LOSSLESS — we never
        //    want HI_RES (Widevine) or HIGH (lossy AAC).
        val manifestEnvelope = callLimited { apiClient.track(best.first.id, "LOSSLESS") }
            ?: return null
        val manifestB64 = manifestEnvelope.data?.manifest
        if (manifestB64.isNullOrBlank()) {
            Log.d(TAG, "empty manifest for track ${best.first.id}")
            return null
        }

        // 4. Decode base64 manifest + reject anything DRM-encrypted.
        val decoded = decodeManifest(manifestB64, manifestJson) ?: run {
            Log.w(TAG, "manifest decode failed for track ${best.first.id}")
            return null
        }
        if (decoded.encryptionType != "NONE") {
            Log.d(TAG, "skipping encrypted manifest (${decoded.encryptionType}) for track ${best.first.id}")
            return null
        }
        val downloadUrl = decoded.urls.firstOrNull()
        if (downloadUrl.isNullOrBlank()) {
            Log.d(TAG, "empty url list in decoded manifest for track ${best.first.id}")
            return null
        }

        return SourceResult(
            sourceId = id,
            downloadUrl = downloadUrl,
            // Tidal CDN URLs are pre-signed — no extra headers needed.
            downloadHeaders = emptyMap(),
            format = AudioFormat(
                codec = "flac",
                // CD-quality lossless: 16-bit / 44.1 kHz, ~1411 kbps.
                bitrateKbps = 1411,
                sampleRateHz = 44_100,
                bitsPerSample = 16,
            ),
            confidence = best.second,
            sourceTrackId = best.first.id.toString(),
            coverArtUrl = coverIdToUrl(best.first.album?.cover),
        )
    }

    override suspend fun rateLimitState(): RateLimitState = rateLimiter.stateOf(id)

    // ── Internals ───────────────────────────────────────────────────────

    /**
     * Wraps an API call with rate-limiter bookkeeping. Returns null on
     * any failure mode so callers can `?: return null` to skip cleanly.
     * Direct port of QobuzSource.callLimited minus the captcha-expired
     * branch (Monochrome has no captcha cookie).
     */
    private suspend fun <T> callLimited(block: suspend () -> T): T? {
        if (!rateLimiter.acquire(id)) return null
        return try {
            val result = block()
            rateLimiter.reportSuccess(id)
            result
        } catch (e: MonochromeApiException) {
            when (e.status) {
                429 -> rateLimiter.reportRateLimited(id)
                else -> rateLimiter.reportFailure(id)
            }
            Log.w(TAG, "monochrome API call failed: ${e.message}")
            null
        } catch (e: Exception) {
            rateLimiter.reportFailure(id)
            Log.w(TAG, "monochrome call threw: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Confidence score on [0.0, 1.0]. Direct port of QobuzSource.confidence
     * adapted to TidalTrack. ISRC equality short-circuits to 0.95.
     */
    private fun confidence(query: TrackQuery, candidate: TidalTrack): Float {
        // ISRC match → highest non-1.0 score.
        val queryIsrc = query.isrc?.takeIf { it.isNotBlank() }
        val candidateIsrc = candidate.isrc?.takeIf { it.isNotBlank() }
        if (queryIsrc != null && candidateIsrc != null &&
            queryIsrc.equals(candidateIsrc, ignoreCase = true)
        ) {
            return 0.95f
        }

        val titleSim = jaccard(normalize(query.title), normalize(candidate.title))
        val artistSim = artistSimilarity(
            normalize(query.artist),
            normalize(candidate.firstArtistName()),
        )

        // Duration similarity. Same scoring as QobuzSource.
        val durationFactor: Float = run {
            val queryMs = query.durationMs ?: return@run 1.0f
            if (queryMs <= 0 || candidate.duration <= 0) return@run 1.0f
            val candidateMs = candidate.duration * 1000L
            val drift = abs(queryMs - candidateMs).toDouble() / queryMs.toDouble()
            when {
                drift < 0.05 -> 1.0f
                drift < 0.10 -> 0.85f
                drift < 0.20 -> 0.6f
                else -> 0.3f
            }
        }

        return (titleSim * artistSim * durationFactor)
    }

    private fun TidalTrack.firstArtistName(): String =
        artist?.name?.takeIf { it.isNotBlank() }
            ?: artists?.firstOrNull()?.name?.takeIf { it.isNotBlank() }
            ?: ""

    companion object {
        /** Per LosslessSource KDoc convention. */
        const val SOURCE_ID = "monochrome_tidal"
        private const val TAG = "MonochromeSource"

        /** Threshold below which a candidate is rejected outright. */
        private const val MIN_CONFIDENCE = 0.5f

        /** Reused JSON parser for base64-decoded manifests. */
        private val manifestJson = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        // ── Pure-function helpers (kept package-internal for testing) ─
        // Direct copy from QobuzSource — same Jaccard + artist-subset
        // logic. A future refactor may extract to a shared helpers file
        // once a third source joins (YAGNI for two sources).

        /**
         * Lowercase + strip parenthetical content, "feat./featuring"
         * suffixes, and non-alphanumeric characters; collapse whitespace.
         */
        internal fun normalize(s: String): String =
            s.lowercase()
                .replace(Regex("\\([^)]*\\)"), " ")
                .replace(Regex("\\[[^]]*\\]"), " ")
                .replace(Regex("(?i)\\b(feat\\.?|ft\\.?|featuring)\\b.*"), " ")
                .replace(Regex("[''`]"), "")
                .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        /** Jaccard similarity on whitespace-tokenized strings. */
        internal fun jaccard(a: String, b: String): Float {
            val setA = a.split(" ").filter { it.isNotEmpty() }.toSet()
            val setB = b.split(" ").filter { it.isNotEmpty() }.toSet()
            if (setA.isEmpty() || setB.isEmpty()) return 0f
            val intersection = setA.intersect(setB).size.toFloat()
            val union = setA.union(setB).size.toFloat()
            return intersection / union
        }

        /**
         * Artist-aware similarity: max of plain jaccard and subset-coverage.
         * Direct copy of QobuzSource.artistSimilarity. See that file for
         * the full rationale on Spotify-expansion vs canonical-short-form
         * matching.
         */
        internal fun artistSimilarity(a: String, b: String): Float {
            if (a.isEmpty() || b.isEmpty()) return 0f
            val tokensA = a.split(" ").filter { it.isNotEmpty() }.toSet()
            val tokensB = b.split(" ").filter { it.isNotEmpty() }.toSet()
            if (tokensA.isEmpty() || tokensB.isEmpty()) return 0f

            val intersection = tokensA.intersect(tokensB).size.toFloat()
            val union = tokensA.union(tokensB).size.toFloat()
            val jaccard = if (union == 0f) 0f else intersection / union

            // Subset coverage with distinctive-token gate.
            val (smaller, larger) = if (tokensA.size <= tokensB.size) tokensA to tokensB else tokensB to tokensA
            val isSubset = smaller.all { it in larger }
            val hasDistinctive = smaller.any { it.length > 3 }
            val subsetScore = if (isSubset && hasDistinctive && smaller.size >= 1) 1.0f else 0f

            return maxOf(jaccard, subsetScore)
        }
    }
}
```

- [ ] **Step 3: Build**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/amz-source
./gradlew :data:download:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. If you get unresolved-reference errors:
- `LosslessSource`, `TrackQuery`, `SourceResult`, `AudioFormat`, `RateLimitState` — these come from `data/download/.../lossless/LosslessSource.kt`. The package import is `com.stash.data.download.lossless.*`.
- `AggregatorRateLimiter` — from `com.stash.data.download.lossless`. Already wired in the existing Hilt graph.

- [ ] **Step 4: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/amz-source
git add data/download/src/main/kotlin/com/stash/data/download/lossless/monochrome/MonochromeSource.kt
git commit -m "feat(monochrome): MonochromeSource implements LosslessSource

@Singleton class implementing LosslessSource interface. Mirrors
QobuzSource shape: search → score candidates → resolve to download URL.

Tidal-specific differences:
- Search by 'artist + title' free-text (Tidal API has no direct ISRC
  search index; ISRC matching happens via candidate.isrc field check
  in confidence scoring)
- Quality hardcoded to LOSSLESS (16/44.1 FLAC). HI_RES_LOSSLESS would
  return Widevine MPD manifests which Stash can't handle.
- After getting the track manifest, base64-decode it (decodeManifest
  helper from MonochromeModels) and check encryptionType == 'NONE'
  before using the urls. Anything else means encrypted/DRM stream
  → return null and registry tries next source.
- Cover art uses coverIdToUrl helper to translate Tidal's dash-
  separated UUID into resources.tidal.com CDN URL.

The internal scoring helpers (normalize, jaccard, artistSimilarity)
are direct copies from QobuzSource. A future refactor can extract to
a shared helpers file once a third source joins; for two sources YAGNI.
"
```

---

## Task 4: Create `MonochromeModule.kt` Hilt binding

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/monochrome/di/MonochromeModule.kt`

- [ ] **Step 1: Read existing `QobuzModule.kt` for style**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/amz-source
cat data/download/src/main/kotlin/com/stash/data/download/lossless/qobuz/di/QobuzModule.kt
```

It's a 30-line `@Module abstract class` with a single `@Binds @IntoSet` method.

- [ ] **Step 2: Write `MonochromeModule.kt`**

```kotlin
package com.stash.data.download.lossless.monochrome.di

import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.monochrome.MonochromeSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Hilt wiring for the Tidal-via-Monochrome lossless source.
 *
 * Binds [MonochromeSource] into the `Set<LosslessSource>` multibinding so
 * [com.stash.data.download.lossless.LosslessSourceRegistry] picks it up
 * alongside the existing [com.stash.data.download.lossless.qobuz.QobuzSource].
 *
 * No credentials wiring — `api.monochrome.tf` is operator-credentialed
 * (the operator runs uimaxbai/hifi-api with their own paid Tidal account).
 * End users supply nothing.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MonochromeModule {

    @Binds
    @IntoSet
    abstract fun bindMonochromeAsLosslessSource(impl: MonochromeSource): LosslessSource
}
```

- [ ] **Step 3: Build the full app to verify Hilt graph**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/amz-source
./gradlew :data:download:assembleDebug :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` for both. The `:app` build runs Hilt's annotation processor — if `MonochromeSource` has any unresolved injection or the multibinding setup is wrong, this is where it fails.

- [ ] **Step 4: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/amz-source
git add data/download/src/main/kotlin/com/stash/data/download/lossless/monochrome/di/MonochromeModule.kt
git commit -m "feat(monochrome): Hilt @IntoSet binding for MonochromeSource

Mirrors QobuzModule. Adds MonochromeSource to the
Set<LosslessSource> multibinding so LosslessSourceRegistry picks it
up at injection time. No user-credential wiring needed.
"
```

---

## Task 5: Update `LosslessSourcePreferences` default priority

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessSourcePreferences.kt`

- [ ] **Step 1: Read the current state of `LosslessSourcePreferences.kt`**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/amz-source
cat data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessSourcePreferences.kt | head -130
```

Locate:
- The `priorityOrder: Flow<List<String>>` declaration (around line 106 today, may have drifted since v0.9.7).
- The `companion object` (or, if there isn't one, the bottom of the class).

The existing `priorityOrder` reads:

```kotlin
val priorityOrder: Flow<List<String>> = context.losslessDataStore.data.map { prefs ->
    prefs[priorityKey]
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()
}
```

Empty list = "no order set", and `LosslessSourceRegistry` falls back to registration order. We're going to make the empty case fall back to a defined `DEFAULT_PRIORITY` instead.

- [ ] **Step 2: Add `DEFAULT_PRIORITY` constant + `ifEmpty` fallback**

The change is two-part:

a) Add a companion object (or extend the existing one) with the constant. Locate the end of the class (just before the final `}`) and insert:

```kotlin
    companion object {
        /**
         * Default priority order used by fresh installs (no DataStore
         * entry yet). Existing users with explicitly-saved priority
         * preserve their value.
         *
         * Order:
         * 1. squid_qobuz — Qobuz Hi-Res FLAC via qobuz.squid.wtf (existing
         *    integration since v0.9.0; proven matching, well-known catalog)
         * 2. monochrome_tidal — Tidal Lossless 16/44.1 FLAC via
         *    api.monochrome.tf (added in v0.9.10; different operator from
         *    squid.wtf so outages are uncorrelated)
         */
        val DEFAULT_PRIORITY: List<String> = listOf(
            "squid_qobuz",
            "monochrome_tidal",
        )
    }
```

If a `companion object` already exists at the bottom of `LosslessSourcePreferences.kt` (it likely does — `MinQuality` enum is sometimes defined there), add the `DEFAULT_PRIORITY` field inside the existing companion object instead of creating a new one. **Read the file first** to confirm the structure.

b) Modify the `priorityOrder` Flow to use `ifEmpty { DEFAULT_PRIORITY }`. Replace:

```kotlin
val priorityOrder: Flow<List<String>> = context.losslessDataStore.data.map { prefs ->
    prefs[priorityKey]
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()
}
```

with:

```kotlin
val priorityOrder: Flow<List<String>> = context.losslessDataStore.data.map { prefs ->
    prefs[priorityKey]
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.ifEmpty { DEFAULT_PRIORITY }
        ?: DEFAULT_PRIORITY
}
```

The `?: DEFAULT_PRIORITY` covers the case where `prefs[priorityKey]` is null (no DataStore entry, fresh install). The `?.ifEmpty { DEFAULT_PRIORITY }` covers the case where the key exists but parses to an empty list (rare, but possible if a prior write stored `""`).

`setPriorityOrder` (the writer) is **not modified** — users explicitly setting a priority list still get exactly what they wrote.

- [ ] **Step 3: Build**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/amz-source
./gradlew :data:download:assembleDebug :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/amz-source
git add data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessSourcePreferences.kt
git commit -m "feat(prefs): add DEFAULT_PRIORITY for lossless source order

DEFAULT_PRIORITY = [squid_qobuz, monochrome_tidal] for fresh installs.
priorityOrder Flow uses .ifEmpty { DEFAULT_PRIORITY } and ?: DEFAULT_PRIORITY
fallbacks so the new default applies whether the DataStore key is
absent (fresh install) or empty (rare edge).

Existing users with explicitly-saved priority are unaffected —
setPriorityOrder writes through unchanged. The user must explicitly
have called the writer to have a non-empty saved value, so 'their
explicit choice wins' is preserved.
"
```

---

## Task 6: Bump version + signed release build + sideload + manual acceptance

Per memory `feedback_install_after_fix.md`, on-device verification is the discipline. Per memory `feedback_ship_terminology.md`, sideload-and-test is **not** "ship" — Task 7 is.

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Bump versionCode + versionName**

Edit `app/build.gradle.kts`:
- Change `versionCode = 46` to `versionCode = 47`.
- Change `versionName = "0.9.9"` to `versionName = "0.9.10"`.

- [ ] **Step 2: Build the signed release APK**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/amz-source
./gradlew :app:assembleRelease
```

Expected: `BUILD SUCCESSFUL`. If signing fails because `keystore.properties` or `stash-release.jks` are missing in the worktree, copy them from the main checkout (this should already have been done during worktree setup):

```bash
cp C:/Users/theno/Projects/MP3APK/keystore.properties C:/Users/theno/Projects/MP3APK/.worktrees/amz-source/keystore.properties
cp C:/Users/theno/Projects/MP3APK/stash-release.jks C:/Users/theno/Projects/MP3APK/.worktrees/amz-source/stash-release.jks
./gradlew :app:assembleRelease
```

APK lands at `app/build/outputs/apk/release/app-release.apk` *inside the worktree*.

- [ ] **Step 3: Sideload over the user's existing v0.9.9 install**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/amz-source
adb devices
adb install -r app/build/outputs/apk/release/app-release.apk
```

Expected: device line shown in `adb devices`; install reports `Success`. The user's library + DataStore values are preserved.

If `adb devices` shows no device, ask the user to re-seat the cable + accept USB-debugging; do not proceed until the device shows up.

- [ ] **Step 4: Run the manual acceptance flow**

Open the **main `com.stash.app`** (not `.debug`). For each scenario, confirm the observed behaviour matches:

1. **Existing-track refresh:** Open Stash, find a track that's already downloaded as FLAC (likely served by qobuz.squid in v0.9.9). Confirm Storage stays consistent — no regressions, the LibrarySizeHolder still reports correct totals.

2. **Search → download a popular track in both Qobuz and Tidal catalogs:** A widely-released Western single (e.g. anything in the top-200 streaming charts post-2010). Confirm it downloads as FLAC. Most likely served by qobuz.squid (priority position 0).

3. **Search → download a Tidal-exclusive or Qobuz-missing track:** Pick something Qobuz is known to not carry — some Tidal-exclusive releases, certain Asian-market K-pop or J-pop, certain hip-hop deluxe-edition tracks. The exact catalog gap varies by region, so this may take a couple of tries to find. When you do, confirm Stash falls over to Monochrome and delivers a FLAC download.

4. **Confirm Tidal CDN cover art:** After (3), check that the downloaded track has cover art in Library → Tracks. Source is either `resources.tidal.com` (via `coverIdToUrl`) or the existing Last.fm fallback path — both acceptable.

5. **Force qobuz.squid offline (optional):** Toggle airplane mode briefly during a download attempt to simulate qobuz timing out, OR temporarily clear the squid.wtf captcha cookie via Settings → Audio Quality → Advanced → captcha field (paste a bogus value, save, attempt download). Confirm Monochrome takes over after the rate-limiter circuit-breaks Qobuz. Restore the captcha cookie afterward.

6. **yt-dlp fallback still works** when both Qobuz + Monochrome miss: pick a track on neither (a YouTube-only upload). Confirm Stash downloads at MP3 320 as today.

7. **Settings → Audio Quality:** confirm the toggle text and existing UI are unchanged. v0.9.10 adds no new UI surfaces.

If any scenario fails, **stop and report**. Do not proceed to merge/tag. Pay particular attention to the network behaviour during scenario (3) — if Monochrome's API returns 401 from the device's network (matching the WebFetch probe during plan-writing), we may need to add Origin/Referer headers in `MonochromeApiClient`.

- [ ] **Step 5: Commit the version bump**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/amz-source
git add app/build.gradle.kts
git commit -m "chore(release): bump versionCode 46→47, versionName 0.9.9→0.9.10"
```

---

## Task 7: Merge + tag + GitHub release

Only run after Task 6 acceptance passed.

Per memory `feedback_check_worktrees_before_release.md`: survey worktrees first.
Per memory `feedback_release_notes.md`: lightweight tag + omit `--notes` so the release body comes from the tagged commit's message body.

- [ ] **Step 1: Survey worktrees for v0.9.10-relevant WIP**

Run from the main checkout (not the worktree):

```bash
cd C:/Users/theno/Projects/MP3APK
git worktree list
for w in auto-advance-fix crossfade crossfader playlist-images-liked-songs preview-latency-fix yt-history-sync yt-sync-pagination playlist-banlist amz-source; do
  echo "=== $w ==="
  git -C ".worktrees/$w" status --short 2>&1 | head -10
  echo "--- log vs master ---"
  git -C ".worktrees/$w" log --oneline master..HEAD 2>&1 | head -5
done
```

Expected: same WIP state as before v0.9.9 ship for older worktrees, plus the new `amz-source` worktree showing the spec, plan, Task 1-6 commits, and version-bump commit. If anything unrelated has appeared (new branch, lossless-related WIP elsewhere), stop and ask the user.

- [ ] **Step 2: Create the consolidated release-notes commit on the branch tip**

Run inside the worktree:

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/amz-source
git commit --allow-empty -m "$(cat <<'EOF'
feat: 0.9.10 — Tidal lossless via Monochrome as a sibling source

Adds api.monochrome.tf as a second LosslessSource alongside the
existing qobuz.squid.wtf. When one source's operator is down (account
banned, infrastructure flaky, captcha expired), the registry now falls
over to the other automatically — both deliver lossless FLAC.

Why two sources: qobuz.squid.wtf has been intermittently down (the
operator's status page has shown ~46% uptime on the Tidal sibling).
Adding a second operator-credentialed proxy reduces correlated outage
risk. Monochrome runs uimaxbai/hifi-api on top of Tidal's catalog —
different operator, different upstream service, different account
pool than squid.wtf.

Why Tidal at LOSSLESS quality (not HI_RES): Tidal Hi-Res requires
Widevine DRM + MPD manifest handling on Android, which Stash's
download path doesn't support. LOSSLESS = 16-bit/44.1 FLAC = CD
quality, served as plain HTTP segments without DRM. Hi-Res deferred
indefinitely.

Architecture: drop-in sibling to QobuzSource. New MonochromeSource
implements LosslessSource interface; MonochromeApiClient handles HTTP;
MonochromeModels has the @Serializable shapes (including base64-decoded
stream manifests). Existing LosslessSourceRegistry handles priority +
circuit-breaking unchanged. No new UI surfaces, no DB migration.

Default priority for fresh installs:
  squid_qobuz → monochrome_tidal
Existing users with explicit priority preserved.

Source list deliberately locked at 2 (Qobuz + Tidal) after a
multi-pass probe ruled out lucida.to (no public REST API),
doubledouble.top (anti-bot), cobalt-tools (no FLAC support), and
SpotiFLAC's third-party APIs (single-anonymous-operator with
obfuscated rotating keys). Adding any of those would import their
fragility.

Spec: docs/superpowers/specs/2026-05-03-monochrome-source-design.md

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
RELEASE_SHA=$(git rev-parse HEAD)
echo "release commit: $RELEASE_SHA"
```

- [ ] **Step 3: Push the feature branch**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/amz-source
git push origin feat/amz-squid-source
```

(Branch name is the worktree's historical artifact; the commit content is unambiguous about what's actually shipping.)

- [ ] **Step 4: Switch to master in main checkout, fast-forward, merge --no-ff**

```bash
cd C:/Users/theno/Projects/MP3APK
git checkout master
git pull --ff-only origin master
git merge --no-ff feat/amz-squid-source -m "Merge branch 'feat/amz-squid-source'"
```

Expected: clean merge commit. Anything other than `Merge made by the 'ort' strategy.` (or similar) means a conflict — stop and resolve manually.

- [ ] **Step 5: Push master**

```bash
cd C:/Users/theno/Projects/MP3APK
git push origin master
```

- [ ] **Step 6: Create + push lightweight tag**

Tag the consolidated release-notes commit (`$RELEASE_SHA` from Step 2). Lightweight tag — per memory `feedback_release_notes.md`.

```bash
cd C:/Users/theno/Projects/MP3APK
git tag v0.9.10 "$RELEASE_SHA"
git push origin v0.9.10
```

If `$RELEASE_SHA` isn't in scope:

```bash
cd C:/Users/theno/Projects/MP3APK
git log master --oneline | head -5
# Find the "feat: 0.9.10 — Tidal lossless via Monochrome" commit. Tag that SHA.
```

- [ ] **Step 7: Create GitHub release using the tagged-commit body**

```bash
cd C:/Users/theno/Projects/MP3APK
gh release create v0.9.10 \
  --title "v0.9.10 — Tidal lossless via Monochrome as a sibling source"
```

(No `--notes`. Per memory `feedback_release_notes.md`.)

Expected: gh prints the release URL.

- [ ] **Step 8: Verify on GitHub**

Open the URL gh printed (or `https://github.com/rawnaldclark/Stash/releases/tag/v0.9.10`). Confirm:
- Title reads "v0.9.10 — Tidal lossless via Monochrome as a sibling source"
- Body contains the natural-language release notes from Step 2
- The auto-generated release APK appears (after CI build completes — typically 5-10 minutes)

- [ ] **Step 9: Clean up worktree (optional)**

After the merge lands and tag is published:

```bash
cd C:/Users/theno/Projects/MP3APK
git worktree remove .worktrees/amz-source
git branch -d feat/amz-squid-source
```

Skip if you want to keep the worktree for fixup commits — the gitignored `keystore.properties`, `stash-release.jks`, and Gradle build directories take up some disk space but are harmless to leave.

---

## Skills reference

- @superpowers:verification-before-completion — before claiming Task 6 / Task 7 done. Don't skip the on-device acceptance flow.
- Memory `feedback_install_after_fix.md` — always sideload after a fix; compile-pass alone isn't enough.
- Memory `feedback_release_notes.md` — release body comes from the tagged commit's message body, not the tag annotation. Use lightweight tags + omit `--notes`.
- Memory `feedback_check_worktrees_before_release.md` — survey worktrees before tagging.
- Memory `feedback_no_time_estimates.md` — no dev-time estimates anywhere in commits or release notes.
- Memory `feedback_worktree_local_properties.md` — `git worktree add` does not copy `local.properties` / `keystore.properties` / `stash-release.jks`; copy them manually if release builds need them.

## Risks & rollback

- **API 401 from device** (during plan-writing, WebFetch hits to `api.monochrome.tf/search/?s=...` returned 401 — possibly bot-detection by User-Agent / Origin / IP). The `MonochromeApiClient` already sends a stable `User-Agent`; if 401s persist on the device, add an `Origin: https://monochrome.tf` header in `MonochromeApiClient.kt:Step 2` and rebuild. The reference Python client (`musicgrabber`) hits the API with no auth headers and works — the 401 was likely WebFetch-IP-related.
- **Operator's Tidal account blocked** (`uimaxbai/hifi-api` README warns this is happening "en masse"). `AggregatorRateLimiter` circuit-breaks `monochrome_tidal` after consecutive 401/403/5xx; registry skips and falls over to qobuz.squid (or yt-dlp if both are down). User-facing: silent failover identical to qobuz being down today. No regression vs v0.9.9.
- **`encryptionType != "NONE"` returned for some tracks** (regional licensing variance — a Hi-Res-only-in-this-region track without lossless rights). Source returns null; registry tries next. Logged as `Log.d` so we have signal if common.
- **Tidal cover URL pattern changes** (`resources.tidal.com/images/<dashes-to-slashes>/<size>.jpg` is convention, not contract). `coverArtUrl` is a soft dependency — null falls back to existing Last.fm path.
- **`MonochromeApiClient` body shape drift in the upstream API** (`uimaxbai/hifi-api` is actively committed). Single-file API client; `Json { ignoreUnknownKeys = true }` makes additions backward-compatible. If a field rename breaks parsing, fix is a one-file edit.
- **Both sources fail simultaneously** (correlated outage from a wide industry shock). Existing yt-dlp/MP3 fallback catches this. Same UX as v0.9.9 today when squid is down. No regression.
- **License obligation:** Stash is GPL-3.0; `uimaxbai/hifi-api` is MIT, `monochrome-music/monochrome` is Apache-2.0. Stash makes HTTP calls — does not bundle either project's code. Add a one-line credit to Settings → About alongside the existing squid.wtf credit. **Out of scope for v0.9.10** (the About-screen credit edit), can land in any later release. License-clean by the wire-call analysis.
- **Rollback:** revert the merge commit + delete the tag (`git push --delete origin v0.9.10`). User data is unaffected — no schema change, no destructive DataStore writes. The new `monochrome_tidal` priority entry in fresh-install defaults will be ignored by users on the rolled-back v0.9.9 because the source isn't registered.
