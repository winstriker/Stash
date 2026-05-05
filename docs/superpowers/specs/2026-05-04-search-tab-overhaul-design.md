# v0.9.12 — Search-Tab Lossless-First Preview + Download Overhaul

**Date:** 2026-05-04
**Status:** Design
**Branch:** `feat/v0.9.12-search-overhaul` (worktree path: `.worktrees/v0.9.12-search-overhaul`)

## Problem

Search-tab and artist-profile preview/download have a ~90% failure rate per the user. Sync-tab downloads are fast (3x speedup landed in v0.9.10). The architectural gap: **search-tab actions bypass the entire `DownloadManager` pipeline** that makes sync fast.

Concrete differences mapped during context exploration:

| Aspect | Sync (fast) | Search (broken) |
|---|---|---|
| Lossless registry | Tried first (squid.wtf → kennyy.com.br) | Never consulted |
| URL pre-resolution | `resolveUrl()` with album matching + 4-tier verification | Builds `https://youtube.com/watch?v=<videoId>` directly from search result |
| Concurrency | `Semaphore(8)` parallel workers | Single coroutine per tap |
| Retry | Queue-backed with exponential backoff | None — fails silently |
| Match verification | `verifyMatch()` rejects wrong videoIds | Trusts search videoId blindly |
| Download impl | `DownloadManager.downloadTrack` (full pipeline) | `DownloadExecutor.download` (yt-dlp only) |
| Preview source | Same lossless registry could be reused | `PreviewUrlExtractor` (InnerTube vs yt-dlp race; 1-2s best, 15-35s worst) |

User's reference points: monochrome.tf-style "5 seconds max for FLAC preview" and Qobuz proxy "seamless instant download." Both are achievable by routing search-tab actions through the v0.9.10 lossless registry — same infrastructure, same rate-limited Qobuz proxies, same FLAC files served from the same CDNs.

## Goals

- Tapping preview on a search-result or artist-profile track starts FLAC playback within ~1s for matched rows (Qobuz hit), with the streamed bytes captured to a local cache for zero-cost finalization on download.
- Tapping download on a track that was previewed reuses the cached bytes — no second network fetch.
- Tapping download without preview resolves Qobuz directly, falls through to yt-dlp on miss with a visible "Downloading via YouTube (slower)" status.
- Search-tab match confidence is tightened (0.65 vs sync's 0.5) to account for missing ISRC/duration on YouTube Music search results.
- No UI changes; same buttons, same screens, same flow. Backend rewire only.

## Non-goals

- **No UI surface changes.** Per-row layout, button labels, snackbar text all stay as v0.9.11. Status flow surfaces existing spinner/snackbar state only.
- **No ISRC enrichment from YouTube Music.** YT Music doesn't reliably surface ISRC; defer until match false-positives surface in production.
- **No album metadata propagation to Qobuz query.** YT search results often lack album. Title+artist with confidence ≥ 0.65 is sufficient.
- **No user-configurable cache size.** Hard-coded 200MB. v0.9.13 polish if storage complaints surface.
- **No fine-grained yt-dlp progress reporting.** Existing `DownloadExecutor` returns on completion; byte-level progress is out of scope.
- **No re-download on quality-tier change** for tracks downloaded via search. Forward-only — same semantics as v0.9.11.
- **No batch "Download album" UI.** Existing per-row Download button works; album batch is auto-derived if the artist-profile UI loops over tracks.
- **No unit tests added.** Project precedent (v0.9.11 / v0.9.10 / etc. shipped without unit tests for UI/worker code). Discipline is on-device acceptance.

## Design

### 1. Architecture overview

Four new components, all reusing the v0.9.10 `LosslessSourceRegistry` infrastructure unchanged. One refactor of existing sync code to share finalization logic.

| Component | Location | Responsibility |
|---|---|---|
| `SearchPreviewMediaSource` | `core/media/.../preview/` | Returns an ExoPlayer `MediaSource` for a search-result track. Lossless on hit, falls through to existing `PreviewUrlExtractor` on miss. Both wrapped in `CacheDataSource`. |
| `SearchDownloadCoordinator` | `data/download/.../search/` | Single entry point for search-tab downloads. Replaces the direct `DownloadExecutor.download()` call inside `TrackActionsDelegate.downloadTrack`. |
| `LosslessUrlPrefetcher` | `core/media/.../preview/` | Visible-row prefetch of `LosslessMatch`. Bounded concurrency (4). 5-min TTL. Used by both preview and (for memoization only) download paths. |
| `PreviewCache` Hilt module | `core/media/.../preview/di/` | Provides `@Singleton SimpleCache` rooted at `<filesDir>/preview_cache/` with 200MB LRU evictor and a `TrackKeyCacheKeyFactory`. |
| `TrackFinalizer` (refactor) | `data/download/.../shared/` | Extracted from existing `DownloadManager.tryLosslessDownload`. Embeds metadata, organizes file, inserts/updates Track row, marks downloaded. Shared by sync and search. |

The `LosslessSourceRegistry`, `QobuzSource`, `KennyySource`, `AggregatorRateLimiter`, and `LosslessSourcePreferences` are unchanged. Search-tab callers post-filter results by `confidence >= 0.65` rather than asking the registry to filter — keeps sync semantics at 0.5 untouched.

### 2. Preview path detail

#### 2.1 `SearchPreviewMediaSource.create(track: TrackItem): MediaSource`

Called by the existing `PreviewPlayer` when the user taps a search-result row. Returns an ExoPlayer `MediaSource` the player binds to.

```kotlin
@Singleton
class SearchPreviewMediaSource @Inject constructor(
    private val prefetcher: LosslessUrlPrefetcher,
    private val previewUrlExtractor: PreviewUrlExtractor,
    private val previewCache: SimpleCache,
    private val httpDataSourceFactory: HttpDataSource.Factory,
    private val cacheKeyFactory: TrackKeyCacheKeyFactory,
) {
    suspend fun create(track: TrackItem): MediaSource {
        val match = prefetcher.lookup(track)
        return if (match != null && match.confidence >= MIN_SEARCH_CONFIDENCE) {
            buildCachedSource(
                upstreamUrl = match.downloadUrl,
                cacheKey = "lossless:${track.videoId}",
                upstreamHeaders = match.downloadHeaders,
            )
        } else {
            val ytUrl = previewUrlExtractor.extractStreamUrl(track.videoId) ?: error("no fallback URL")
            buildCachedSource(
                upstreamUrl = ytUrl,
                cacheKey = "ytdlp:${track.videoId}",
                upstreamHeaders = emptyMap(),
            )
        }
    }

    private fun buildCachedSource(
        upstreamUrl: String,
        cacheKey: String,
        upstreamHeaders: Map<String, String>,
    ): MediaSource {
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(previewCache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setCacheKeyFactory(cacheKeyFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val mediaItem = MediaItem.Builder()
            .setUri("$upstreamUrl${if (upstreamUrl.contains("?")) "&" else "?"}trackKey=$cacheKey")
            .build()
        return ProgressiveMediaSource.Factory(cacheDataSourceFactory).createMediaSource(mediaItem)
    }

    companion object { const val MIN_SEARCH_CONFIDENCE = 0.65f }
}
```

#### 2.2 `TrackKeyCacheKeyFactory`

```kotlin
class TrackKeyCacheKeyFactory @Inject constructor() : CacheKeyFactory {
    override fun buildCacheKey(spec: DataSpec): String =
        spec.customCacheKey
            ?: spec.uri.getQueryParameter("trackKey")
            ?: spec.uri.toString()
}
```

Falls back to the URI for any non-search-tab caller of the cache, defensively.

#### 2.3 Cache key namespacing

- `lossless:<videoId>` — Qobuz FLAC bytes
- `ytdlp:<videoId>` — yt-dlp Opus/AAC bytes

Distinct namespaces because the bytes are different files (different codecs, different containers). When `SearchDownloadCoordinator` finalizes, it uses the namespace matching the resolved source. If the user previewed via lossless and downloads via lossless, the cache hit. If they previewed via yt-dlp and downloaded via lossless (Qobuz now reachable), the lossless cache key is empty and we fetch fresh — that's correct, the bytes are different.

#### 2.4 ExoPlayer error handling

`ProgressiveMediaSource` retries upstream on transient errors via `LoadErrorHandlingPolicy` defaults (3 retries, exponential backoff). On unrecoverable error, the existing player error handler shows a snackbar — no change.

### 3. Download path detail

#### 3.1 New flow type: `SearchDownloadStatus`

The existing `core/model/.../DownloadStatus.kt` is an enum with values `{ PENDING, IN_PROGRESS, COMPLETED, FAILED, SKIPPED }` used by `TrackDownloadWorker`, `DownloadQueueDao`, etc. — not extensible without breaking sync. We introduce a **separate sealed class** for the search-tab status flow:

```kotlin
// data/download/.../search/SearchDownloadStatus.kt
sealed interface SearchDownloadStatus {
    data object Resolving : SearchDownloadStatus
    data class Downloading(val via: Source) : SearchDownloadStatus
    data object Completed : SearchDownloadStatus
    data class Failed(val message: String) : SearchDownloadStatus

    enum class Source { LOSSLESS, YOUTUBE }
}
```

The `Downloading(via = YOUTUBE)` state surfaces the "Downloading via YouTube (slower)" message — UI translates the typed value to the existing snackbar/spinner string.

#### 3.2 `SearchDownloadCoordinator.download(track: TrackItem): Flow<SearchDownloadStatus>`

Replaces the direct `DownloadExecutor.download()` call inside `TrackActionsDelegate.downloadTrack` (currently at `core/media/.../actions/TrackActionsDelegate.kt:227-283`).

```kotlin
@Singleton
class SearchDownloadCoordinator @Inject constructor(
    private val registry: LosslessSourceRegistry,
    private val previewCache: SimpleCache,
    private val httpDataSourceFactory: HttpDataSource.Factory,
    private val downloadExecutor: DownloadExecutor,
    private val trackFinalizer: TrackFinalizer,
    private val coroutineScope: CoroutineScope,
) {
    private val inFlight = mutableMapOf<String, Deferred<DownloadResult>>()
    private val mutex = Mutex()

    fun download(track: TrackItem): Flow<SearchDownloadStatus> = flow {
        val key = track.videoId
        val deferred = mutex.withLock {
            inFlight.getOrPut(key) {
                coroutineScope.async { performDownload(track) }
            }
        }
        emit(SearchDownloadStatus.Resolving)
        try {
            val result = deferred.await()
            emit(when (result) {
                is DownloadResult.Success -> SearchDownloadStatus.Completed
                is DownloadResult.Failure -> SearchDownloadStatus.Failed(result.message)
            })
        } finally {
            // Always clean up inFlight, even on cancellation or rethrown exception.
            mutex.withLock { inFlight.remove(key) }
        }
    }

    private suspend fun performDownload(track: TrackItem): DownloadResult {
        val match = registry.resolve(track.toQuery())
        return if (match != null && match.confidence >= MIN_SEARCH_CONFIDENCE) {
            // Status flow consumer sees Downloading(LOSSLESS) once we cross this branch.
            finalizeFromLossless(track, match)
        } else {
            finalizeFromYtDlp(track)
        }
    }

    private suspend fun finalizeFromLossless(track: TrackItem, match: SourceResult): DownloadResult {
        val cacheKey = "lossless:${track.videoId}"
        val tempFile = File(tempDir, "search_lossless_${track.videoId}.${match.format.codec}")
        // Fill any missing byte ranges (no-op if preview already filled the cache fully).
        CacheUtil.cache(
            previewCache,
            DataSpec.Builder()
                .setUri(match.downloadUrl)
                .setCustomCacheKey(cacheKey)
                .build(),
            /* progressListener = */ null,
            /* isCanceled = */ AtomicBoolean(false),
        )
        // Concatenate cached spans into tempFile.
        previewCache.getCachedSpans(cacheKey).forEach { span ->
            previewCache.startReadWrite(cacheKey, span.position, span.length).use { it.copyTo(tempFile) }
        }
        val result = trackFinalizer.finalize(
            sourceFile = tempFile,
            track = track,
            format = match.format,
            coverArtUrl = match.coverArtUrl,
            origin = TrackFinalizer.Origin.SEARCH,
        )
        previewCache.removeResource(cacheKey)
        return result
    }

    private suspend fun finalizeFromYtDlp(track: TrackItem): DownloadResult {
        // Existing yt-dlp path. Status flow upstream emits "Downloading via YouTube (slower)".
        val tempFile = File(tempDir, "search_ytdlp_${track.videoId}.opus")
        downloadExecutor.download(
            url = "https://www.youtube.com/watch?v=${track.videoId}",
            outputFile = tempFile,
        )
        val format = AudioFormat(codec = "opus", bitrateKbps = 0, sampleRateHz = 0, bitsPerSample = 0)
        return trackFinalizer.finalize(
            sourceFile = tempFile,
            track = track,
            format = format,
            coverArtUrl = null,
            origin = TrackFinalizer.Origin.SEARCH,
        )
    }
}
```

#### 3.3 `TrackFinalizer` (extracted from existing sync code)

```kotlin
@Singleton
class TrackFinalizer @Inject constructor(
    private val metadataEmbedder: MetadataEmbedder,
    private val fileOrganizer: FileOrganizer,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val audioExtractor: AudioDurationExtractor,
) {
    /**
     * Finalize a freshly-downloaded audio file:
     *   1. Embed metadata (title/artist/album) via ffmpeg
     *   2. Move temp file into the library at the canonical artist/album/title path
     *   3. Extract on-disk codec/bitrate/sample-rate/bit-depth via AudioDurationExtractor
     *   4. Insert or update the Track row with the full delivered metadata
     *   5. For search-tab origin: link the track to the hidden "Saved" playlist so
     *      the orphan-cleanup pass doesn't delete it on next sync (see Risk #11).
     *   6. Fill album art URL if absent.
     */
    suspend fun finalize(
        sourceFile: File,
        track: TrackItem,
        format: AudioFormat,
        coverArtUrl: String?,
        origin: Origin,
    ): DownloadResult { /* ... */ }

    enum class Origin { SYNC, SEARCH }
}
```

#### 3.4 New DAO method: `TrackDao.insertOrUpdateFromDownload`

Handles both "Track row already exists by `videoId` or `(canonical_title, canonical_artist)` — UPDATE in place" and "no row exists — INSERT" cases. Sync (origin = SYNC) and search (origin = SEARCH) both call it. Field set written:

| Column | Source | Notes |
|---|---|---|
| `title`, `artist`, `album` | `track: TrackItem` | Album falls back to `null` if blank |
| `canonical_title`, `canonical_artist` | normalized from track | Same normalizer as sync's `Title.canonical()` |
| `youtube_id` | `track.videoId` | Always set for search-origin |
| `file_path`, `file_size_bytes` | `committed: CommitResult` | From `FileOrganizer.commitDownload` |
| `file_format`, `quality_kbps` | `format: AudioFormat` | Codec normalized via `AudioDurationExtractor.normalizeFormat` |
| `sample_rate_hz`, `bits_per_sample` | `meta: AudioMetadata?` | From file probe (v0.9.11 path) — null-safe |
| `is_downloaded` | `true` | Forces Library visibility |
| `date_added` | `System.currentTimeMillis()` | Only on INSERT path; UPDATE preserves existing |
| `album_art_url` | `coverArtUrl` (Qobuz) or null (yt-dlp) | Filled via separate `fillMissingAlbumArtUrl` call (already exists) |
| `duration_ms` | `meta?.durationMs` if > 0 | Reconciled against existing if present |
| `match_confidence` | `format.matchConfidence` | 0 for yt-dlp; carries lossless registry's confidence on Qobuz path |
| `source` | `MusicSource.YOUTUBE` for search-origin (track came from YT Music search), `SPOTIFY` for sync | Existing column |

#### 3.5 Orphan-cleanup mitigation (search-origin only)

The existing orphan-cleanup pass (`MusicRepositoryImpl.cleanOrphanedMixTracks` at line 486) deletes downloaded tracks that have no `playlist_tracks` membership. The infrastructure to exempt search-tab downloads **already exists**:

- `PlaylistType.DOWNLOADS_MIX` is defined in `core/model/.../Playlist.kt` lines 50-57 with the comment: *"Protected system-owned playlist holding one-off user downloads from the Search tab and Artist Profile. Exactly one row per install, seeded at startup. Prevents `cleanOrphanedMixTracks` from deleting manually-downloaded tracks that would otherwise have no playlist membership. The Stash Mix engine ignores this type."*
- `MusicRepository.ensureDownloadsMixSeeded(): Long` (impl at `MusicRepositoryImpl.kt:281`) — idempotent. First call inserts a `PlaylistEntity(name = "Your Downloads", sourceId = "stash_downloads_mix", type = DOWNLOADS_MIX)`; subsequent calls return the existing id.
- `MusicRepository.linkTrackToDownloadsMix(trackId: Long)` (impl at `MusicRepositoryImpl.kt:294`) — idempotent. Calls `ensureDownloadsMixSeeded()`, checks `playlistDao.getCrossRef`, inserts `playlist_tracks` row if absent.
- `StashApplication.onCreate` already calls `musicRepository.ensureDownloadsMixSeeded()` on every cold start (line ~106 — verify during planning), so the playlist exists before any download fires.
- The current `TrackActionsDelegate.downloadTrack` already calls `linkTrackToDownloadsMix` at line 314. **The orphan-fix is shipped.** The v0.9.12 rewire must preserve this call.

What v0.9.12 does:

`TrackFinalizer.finalize(origin = Origin.SEARCH, ...)` calls `musicRepository.linkTrackToDownloadsMix(trackId)` after the Track row is inserted. `Origin.SYNC` skips it (sync downloads always have a real playlist membership via the playlist they're being synced from).

**No new DAO method, no schema migration, no new playlist type.** The existing `MusicRepository.linkTrackToDownloadsMix` is the single integration point.

#### 3.6 Sync refactor

`DownloadManager.tryLosslessDownload` (currently at `data/download/.../DownloadManager.kt:274-361`) refactors to call `TrackFinalizer.finalize(origin = Origin.SYNC, ...)` for the post-fetch steps. Logic moved verbatim except for the new `origin` arg — no behavior change for sync (origin = SYNC skips the `linkTrackToDownloadsMix` call; sync downloads already have a real playlist membership via the playlist they're being synced from). Acceptance test 8 (sync regression) confirms.

#### 3.7 What `TrackActionsDelegate.downloadTrack` becomes

The current method (lines 227-283 of `TrackActionsDelegate.kt`) inlines: build YouTube URL, run yt-dlp, organize file, insert Track entity, call `linkTrackToDownloadsMix`. v0.9.12 replaces the entire body with one call:

```kotlin
fun downloadTrack(track: TrackItem) {
    coroutineScope.launch {
        searchDownloadCoordinator.download(track).collect { status ->
            // Map SearchDownloadStatus → existing _downloadingIds / _downloadedIds
            // / snackbar emissions. No new state shapes.
        }
    }
}
```

The `linkTrackToDownloadsMix` call moves from this delegate into `TrackFinalizer.finalize(origin = SEARCH)`, so the same orphan-protection runs regardless of whether the download went via Qobuz or yt-dlp.

### 4. Prefetching

#### 4.1 `LosslessUrlPrefetcher`

```kotlin
@Singleton
class LosslessUrlPrefetcher @Inject constructor(
    private val registry: LosslessSourceRegistry,
    private val coroutineScope: CoroutineScope,
) {
    private val cache = ConcurrentHashMap<String, CachedDeferred>()
    private val concurrency = Semaphore(MAX_CONCURRENT)

    fun warmUp(track: TrackItem) {
        val key = track.videoId
        cache[key]?.let { if (it.isFresh()) return }
        cache[key] = CachedDeferred(
            deferred = coroutineScope.async {
                concurrency.withPermit { registry.resolve(track.toQuery()) }
            },
            createdAt = System.currentTimeMillis(),
        )
    }

    suspend fun lookup(track: TrackItem): SourceResult? {
        val cached = cache[track.videoId]
        if (cached != null && cached.isFresh()) return cached.deferred.await()
        warmUp(track)
        return cache[track.videoId]!!.deferred.await()
    }

    fun cancelStale() {
        val now = System.currentTimeMillis()
        cache.entries.removeAll { (_, c) -> now - c.createdAt > TTL_MS }
    }

    private data class CachedDeferred(
        val deferred: Deferred<SourceResult?>,
        val createdAt: Long,
    ) {
        fun isFresh() = System.currentTimeMillis() - createdAt < TTL_MS
    }

    companion object {
        const val MAX_CONCURRENT = 4
        const val TTL_MS = 5 * 60 * 1000L
    }
}
```

#### 4.2 Trigger

Inside `SearchScreen` and `ArtistProfileScreen`, each row Composable adds:

```kotlin
LaunchedEffect(track.videoId) {
    losslessPrefetcher.warmUp(track)
}
```

Compose disposes the effect when the row leaves the viewport, but the in-flight resolve continues on the app scope — `Semaphore(4)` and `AggregatorRateLimiter`'s 1/3s budget naturally serialize. Cancellation on scroll-out is YAGNI.

#### 4.3 Periodic stale cleanup

A single `coroutineScope.launch` in `StashApplication.onCreate`:

```kotlin
launch {
    while (true) {
        delay(60_000)
        losslessPrefetcher.cancelStale()
    }
}
```

### 5. Hilt module

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object PreviewCacheModule {
    @Provides @Singleton
    fun provideCacheDir(@ApplicationContext ctx: Context): File =
        File(ctx.filesDir, "preview_cache").also { it.mkdirs() }

    @Provides @Singleton
    fun provideEvictor(): CacheEvictor =
        LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES)

    @Provides @Singleton
    fun provideSimpleCache(cacheDir: File, evictor: CacheEvictor): SimpleCache =
        SimpleCache(cacheDir, evictor)

    @Provides @Singleton
    fun provideHttpDataSourceFactory(): HttpDataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setUserAgent("Stash/0.9.12")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)

    private const val MAX_CACHE_BYTES = 200L * 1024 * 1024
}
```

Cache lives at `<filesDir>/preview_cache/`. App-private; cleared on uninstall. No SAF complications.

**Dependencies:** No new artifacts. The cache primitives (`SimpleCache`, `CacheDataSource`, `CacheUtil`, `LeastRecentlyUsedCacheEvictor`) ship in `media3-datasource` which is already a transitive dependency of `media3-exoplayer` (declared in `gradle/libs.versions.toml` at version 1.9.2). For HTTP we use `DefaultHttpDataSource.Factory` (also in `media3-datasource`) rather than `OkHttpDataSource.Factory` — the latter would require adding the separate `media3-datasource-okhttp` artifact, and we don't need OkHttp's connection-pool / cookie features for Qobuz signed CDN URLs (they're stateless GETs of pre-signed URLs).

## Risks

| Risk | Mitigation |
|---|---|
| Wrong-version match (live cut → studio FLAC) when ISRC and duration are unknown. | Confidence threshold 0.65 (vs sync's 0.5) on search-tab callers. Empirical tune after on-device acceptance. |
| Qobuz signed URL expires mid-stream. | Observed TTL 5+ min; 4-min FLAC streams complete inside that window. CacheDataSource bytes already in cache resume fine. Edge case (long pause) re-fetches via fresh resolve at next read. |
| Preview cache fills disk. | `LeastRecentlyUsedCacheEvictor(200MB)` at SimpleCache construction. ~2-6 FLAC previews held; LRU evicts on use. |
| Two coroutines write same cache key (preview + download race). | `SimpleCache` is span-thread-safe; `CacheUtil.cache()` skips already-cached spans. No corruption, no duplicate fetch. |
| Prefetcher accumulates stale `Deferred` in memory. | `cancelStale()` runs every 60s in `StashApplication`, drops entries older than 5 min. |
| Yt-dlp fallback still slow (15-35s with QuickJS init). | Visible status `"Downloading via YouTube (slower)"` so user knows. Existing rate-limiter / sticky-disable behavior unchanged from v0.9.10. |
| `TrackFinalizer` extraction breaks sync's `tryLosslessDownload`. | Refactor under sync regression test (acceptance #8). Logic moves verbatim, no behavior change. v0.9.11 sync downloads have just shipped and are working. |
| Rate-limit pressure from prefetching all visible rows on artist profile open. | `Semaphore(4)` cap + AggregatorRateLimiter's 1/3s budget naturally serialize. Burst of 4, then ~3s/track. 30-track artist profile drains in ~90s; user's typical session uses far less. |
| Wrong-codec download when user previews via yt-dlp then taps Download with Qobuz now reachable. | Cache key namespaces are distinct (`lossless:` vs `ytdlp:`). Lossless lookup misses; fresh fetch via Qobuz. Yt-dlp partial bytes evict via LRU. |
| Coordinator's in-flight map leaks if a coroutine is canceled before completion. | `mutex.withLock { inFlight.remove(key) }` in a `try/finally` after await — see §3.2 implementation. |
| **(#11) Search-tab downloads vanish on next launch via orphan cleanup** (legacy bug fixed by the existing `DOWNLOADS_MIX` infrastructure; must be preserved across the v0.9.12 rewire). | `TrackFinalizer.finalize(origin = SEARCH)` calls existing `MusicRepository.linkTrackToDownloadsMix(trackId)` after Track row insert. The existing seeding (`StashApplication.onCreate` → `ensureDownloadsMixSeeded`), playlist type (`PlaylistType.DOWNLOADS_MIX`), and orphan-cleanup exemption stay untouched. See §3.5. |

## Testing

### Unit tests

None added. Pattern follows project precedent (`DownloadManager`, `LosslessSourceRegistry`, `PreviewUrlExtractor` are all untested at the unit level). Discipline is on-device acceptance.

### Manual acceptance (signed debug install)

1. **Prefetch fires on scroll.** Open an artist profile (e.g., Kendrick Lamar). Confirm logcat shows `LosslessUrlPrefetcher: warmUp(<videoId>)` for each row entering the viewport.
2. **Preview latency.** Tap preview on a track in the visible-row set. Playback should start within ~1s. Logcat: `lossless: hit, confidence=X.XX`.
3. **Preview→Download finalize without duplicate fetch.** Tap download immediately after preview. Logcat: `cache fully populated, finalizing`. File lands in library; no fresh Qobuz `getFileUrl` call.
4. **Cold download (no preview).** Tap download on a row not yet previewed. Fresh Qobuz fetch + finalize. Logcat shows full `registry.resolve` → fetch → `TrackFinalizer.finalize` chain.
5. **Yt-dlp fallback.** Tap preview on a niche track Qobuz can't match. Logcat: `lossless: miss, falling through`. Snackbar/spinner shows "Downloading via YouTube (slower)" status.
6. **Cache size cap.** Browse 30 tracks of previews. Check `adb shell ls -la /data/data/com.stash.app.debug/files/preview_cache/`. Total size stays under 200MB. Older entries evict when new ones arrive.
7. **Network drop recovery.** Disconnect network mid-preview, reconnect, retry playback. ExoPlayer's load-error retry handles it; cached partial bytes are reused on retry.
8. **Sync regression check.** With lossless on, queue a sync run. Confirm tracks still download via the lossless path (refactored `TrackFinalizer` shared with search). Bit-depth/sample-rate columns populate as v0.9.11. No degradation in sync speed (~8.7 tracks/min baseline).
9. **Album-batch download (if existing UI loops).** Open an album, tap "Download all" if present. Each track flows through `SearchDownloadCoordinator` independently. Concurrency limited by registry's rate limiter.
10. **Wrong-version edge case.** Search for a track that has both a live and studio version on YouTube. Tap preview on the live row; confirm Qobuz match (if found) is rejected at confidence 0.65 if the live version's title has "(Live at X)" decoration the matcher can't reconcile. Falls through to yt-dlp. (If matcher still passes, log the issue for threshold tuning.)
11. **Orphan-cleanup survival.** Download a track via search (no preview). Confirm a `playlist_tracks` row exists linking it to the existing "Your Downloads" playlist (`sourceId = "stash_downloads_mix"`, `type = DOWNLOADS_MIX`). Force-quit and relaunch the app; on next sync run, confirm the track is NOT deleted by `cleanOrphanedMixTracks`. Library still shows the track. Bonus check: confirm the "Your Downloads" playlist remains visible in Library tab as it does today (the `DOWNLOADS_MIX` type is shown by existing UI; we don't change visibility behavior).

## Out of scope

- UI changes (per Q4)
- ISRC enrichment from YouTube Music
- Album metadata in Qobuz query
- User-configurable cache size
- Fine-grained yt-dlp progress
- Re-download on quality-tier change for search-originated tracks
- Batch "Download album" UI button
- Unit tests

## Ship as

**v0.9.12.** Bumps `versionCode 48 → 49` and `versionName "0.9.11" → "0.9.12"` in `app/build.gradle.kts`. Single coherent release: search + artist profile fast, reliable, lossless-first preview and download.
