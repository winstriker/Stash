# v0.9.35 — Metadata + Album-Art Embedding (Cluster B)

**Date:** 2026-05-23
**Status:** Design
**Branch:** `feat/metadata-embedding` (to be created)
**Closes:** #76 (part 1), #90

## Problem

Downloaded files in Stash come out of two pipelines:

1. **yt-dlp path** (`DownloadManager.executeDownload`): yt-dlp downloads the audio and is passed `--embed-metadata`. yt-dlp writes whatever YouTube reports — typically the video title (`"Artist - Song [Official Music Video]"`), uploader name, and a free-form description. No track-level album, no album artist, no embedded cover picture.
2. **Lossless path** (`DownloadManager.tryLosslessDownload` → `TrackFinalizer.finalizeFile`): calls `MetadataEmbedder.embedMetadata(sourceFile, track)` which writes clean TITLE/ARTIST/ALBUM. But the third parameter `albumArtFile: File? = null` defaults to null at every call site, so **no cover art is ever embedded**.

The result, reported in #76 ("the song is fully naked, it has no metadata and artwork") and #90 ("metadata and album artworks not showing"): files opened in Plex, Foobar2000, Symfonium, or in-car USB-stick players show blank fields and a missing-cover placeholder. Stash's pitch is "your music, yours forever" — naked files break that the moment a user opens them outside Stash.

The fix is bounded: `MetadataEmbedder` already supports artwork via ffmpeg `-disposition:v:0 attached_pic`. The plumbing exists; what's missing is (a) an album-art cache that produces a local JPEG, (b) calling the embedder on the yt-dlp path, (c) a one-time backfill so the existing library is repaired in place.

## Goals

- Every **new** download — yt-dlp or lossless — exits the pipeline with TITLE, ARTIST, ALBUMARTIST, ALBUM, ISRC tags and an embedded cover-art picture, sourced from the Track row that produced the download.
- The **existing** library is repaired in place by a one-time backfill pass that runs automatically on first launch after the v0.9.35 upgrade. Progress is surfaced through the existing Home-screen banner pattern. Failures are non-fatal — the worker logs and moves on.
- Identical tag set across containers (`.opus`, `.webm`, `.m4a`, `.flac`) — readers like Plex and Symfonium see the same fields regardless of source.
- Album-art HTTP fetch happens once per album (deduplicated on disk), not once per track. For a 12-track album this means one network round-trip, not twelve.
- Idempotent. Re-running the backfill on an already-tagged file is a no-op. Running on a file that's been deleted off disk is a no-op. Tag-embedding failure leaves the audio playable.

## Non-goals

- **No customisable filename patterns.** Issue #76 also asks for `%ARTIST%-%TITLE%` style output naming. Different UX surface (Settings + rename engine), independent of tag embedding, and the existing `FileOrganizer.slugify` path works correctly. Filed as a separate follow-up issue, not in this spec.
- **No track-number / disc-number / release-year tags.** The `Track` domain model has TITLE / ARTIST / ALBUM / ISRC / ALBUM_ART_URL but **no** `trackNumber` / `discNumber` / `releaseYear` exposed through the model and mapper. Adding them requires Spotify-sync changes, DB migration, and matcher updates that materially expand scope. ALBUMARTIST is the exception: `TrackEntity.albumArtist` (column `album_artist`, defaultValue `""`) was added in v0.9.26 to power Library album grouping, but it never surfaced through `core/model/Track.kt` or `TrackMapper`. This spec **does** thread it through (§1.2) and prefers it when non-blank, falling back to `track.artist` only when the column is empty. Track ordering in Plex/Foobar falls back to filename sort, which is good enough for the v0.9.35 fix. Filed as a separate follow-up.
- **No GENRE / COMPOSER / COMMENT / ENCODER tags.** Same reason: data isn't reliably available in the Track row.
- **No re-mux of containers.** ffmpeg `-c copy` is container-agnostic and mux-only. We do not transcode and we do not change the container. Opus stays Opus; M4A stays M4A; FLAC stays FLAC.
- **No "fix metadata" button per track.** Backfill is automatic and library-wide. A user with a single bad row can wait for backfill or re-download.
- **No interactive backfill cancellation.** Worker runs at default WorkManager priority and will pause/resume across reboots automatically. A user who wants to stop it can clear app data.
- **No Coil cache reuse.** Tempting (the same JPEG is already on disk for UI), but the Coil disk cache lives in `:core:ui` and reaching across the data→ui layer for a private cache format would couple them. We fetch independently into our own cache, ~100 KB per album.
- **No tag-only "Find in FLAC"-style action.** No Now-Playing button to "re-tag this track."
- **No SAF write-back for the backfill.** SAF (user-chosen external storage) files don't support in-place ffmpeg muxing — `ContentResolver` doesn't expose a stable path. For backfill, SAF-mode users are skipped and surfaced as a Home-banner footnote ("X tracks on external storage will be re-tagged on next download"). New downloads still tag correctly because tagging happens in the cache dir before the SAF copy.

## Design

### 1. Data model

#### 1.1 New column: `tracks.metadata_embedded_at`

Location: `core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt`

```kotlin
/**
 * Epoch-millis of the most recent successful tag + art embedding pass.
 * NULL = never tagged (legacy v0.9.34 and earlier rows, or v0.9.35+ rows
 * whose embed step failed). Backfill worker queries WHERE this IS NULL;
 * setting it non-null is the idempotency signal.
 *
 * Value of 0L specifically marks "backfill tried and failed unrecoverably
 * (file missing, ffmpeg error)." Distinguishes from NULL so the worker
 * doesn't retry hopeless rows on every launch.
 */
val metadataEmbeddedAt: Long? = null,
```

Migration: bumps the Room schema version. The current schema is **v26** (confirmed by `core/data/schemas/com.stash.core.data.db.StashDatabase/26.json` and `version = 26` in `StashDatabase.kt`). This spec adds Migration **v26 → v27**: a single `ALTER TABLE tracks ADD COLUMN metadata_embedded_at INTEGER`.

#### 1.2 Track domain model

Mirror the column on `core/model/Track.kt`:

```kotlin
val metadataEmbeddedAt: Long? = null,
```

Also expose the existing `albumArtist` column (already on `TrackEntity` since v0.9.26, but never surfaced through the domain layer):

```kotlin
/**
 * Album-level artist for grouping multi-artist releases in Plex / Foobar.
 * Distinct from [artist] (the track-level credit) — e.g. a feature credit
 * may say `artist = "Drake, 21 Savage"` while `albumArtist = "Drake"`.
 * Empty string when unknown; embedding code falls back to [artist].
 */
val albumArtist: String = "",
```

Update `TrackMapper` in both directions (`TrackEntity.toDomain()` and `Track.toEntity()`) to propagate `albumArtist` and `metadataEmbeddedAt`. The mapper change is mechanical but mandatory — without it the new tag-writing code reads a stale empty string.

#### 1.3 New DAO methods on `TrackDao`

```kotlin
@Query("UPDATE tracks SET metadata_embedded_at = :timestamp WHERE id = :trackId")
suspend fun setMetadataEmbeddedAt(trackId: Long, timestamp: Long)

@Query("""
    SELECT * FROM tracks
    WHERE is_downloaded = 1
      AND file_path IS NOT NULL
      AND metadata_embedded_at IS NULL
    ORDER BY id ASC
    LIMIT :limit OFFSET :offset
""")
suspend fun getTracksNeedingEmbed(limit: Int, offset: Int): List<TrackEntity>

@Query("""
    SELECT COUNT(*) FROM tracks
    WHERE is_downloaded = 1
      AND file_path IS NOT NULL
      AND metadata_embedded_at IS NULL
""")
fun observeTracksNeedingEmbedCount(): Flow<Int>
```

The observable count powers the Home banner. The paginated list is what the worker drains.

### 2. Album-art cache

#### 2.1 New class: `AlbumArtCache`

Location: `data/download/src/main/kotlin/com/stash/data/download/files/AlbumArtCache.kt`

```kotlin
/**
 * Lazily fetches and caches album-art JPEGs on the local filesystem so
 * MetadataEmbedder can attach them to downloaded audio files.
 *
 * Storage: `FileOrganizer.getAlbumArtDir()` (= `cacheDir/albumart/`).
 * Filenames: SHA-1 of the canonicalised `albumArtUrl`, 8-char prefix,
 * `.jpg` extension. Deterministic so two tracks on the same album hit
 * the same cache file.
 *
 * Network failures, decode failures, and missing-URL cases all return
 * null — embedding proceeds without art. We never block a download
 * because the art server is slow.
 */
@Singleton
class AlbumArtCache @Inject constructor(
    private val fileOrganizer: FileOrganizer,
    private val httpClient: OkHttpClient,
) {
    /**
     * Returns a local JPEG file for the album's cover, downloading and
     * caching on first call. Returns null if the URL is missing,
     * download fails, the response isn't an image, or the cached file
     * is corrupt (zero bytes).
     */
    suspend fun resolveArt(track: Track): File? { /* ... */ }

    /**
     * Best-effort prune. Run by a periodic worker once the library
     * exceeds N MB of cached art (default 50 MB). Removes files
     * unreferenced by any current track row. Not part of the v0.9.35
     * scope — the cache dir is in `cacheDir/` so Android will evict
     * under storage pressure regardless.
     */
    // TODO(v0.9.36+): private suspend fun prune() { ... }
}
```

URL canonicalisation: Spotify CDN URLs (`https://i.scdn.co/image/<hash>`) and YT-Music URLs (`lh3.googleusercontent.com/...=w<size>-h<size>`) get their size suffix normalised to 640px before hashing, so requesting 64px and 640px for the same album produces one cache entry, not two.

#### 2.2 Lifecycle

`cacheDir/albumart/` is in app cache, so Android may evict under pressure. If a cached file disappears between `resolveArt` calls we re-download. We do not migrate art into `filesDir` — embedded copies live inside the audio files themselves, so the cache is genuinely transient.

#### 2.3 Hilt module

Bind in `DownloadModule`. No new DI graph node — reuses the unqualified `OkHttpClient` singleton provided by `core/network/.../NetworkModule.kt` (the same instance every other consumer in the data layer injects).

### 3. MetadataEmbedder changes

`MetadataEmbedder.embedMetadata` (in `data/download/.../files/MetadataEmbedder.kt`) already accepts `albumArtFile: File? = null` and threads it through the ffmpeg argv. Two changes:

#### 3.1 Expanded tag set

Inside the `args = buildList { ... }` block, after the existing `title=`/`artist=`/`album=` writes, add (when source values are non-blank):

```kotlin
val effectiveAlbumArtist = track.albumArtist.ifBlank { track.artist }
add("-metadata"); add("ALBUMARTIST=${sanitize(effectiveAlbumArtist)}")
add("-metadata"); add("album_artist=${sanitize(effectiveAlbumArtist)}")  // legacy lowercase alias
track.isrc?.takeIf { it.isNotBlank() }?.let {
    add("-metadata"); add("ISRC=${sanitize(it)}")
}
add("-metadata"); add("ENCODER=Stash ${BuildConfig.VERSION_NAME}")
```

**Vorbis-comment casing.** Vorbis-comment readers (FLAC, Opus, Ogg) are conventionally case-insensitive but **some real-world readers — Symfonium, several car head units — only match the canonical uppercase form** (`ALBUMARTIST`, `ISRC`, `ENCODER`). ffmpeg passes the key string through verbatim for Vorbis comments. We write each key twice — uppercase canonical form and a lowercase legacy alias (`album_artist`) — so both ID3-style readers (which match the lowercase form by convention) and strict Vorbis readers see the value. This duplication adds <100 bytes per file and removes a class of "tag invisible in one player but visible in another" support reports.

For M4A and MP3 containers, ffmpeg normalises both forms to the single canonical atom/frame (`aART` / `TPE2`); the duplicate write is a no-op.

`ENCODER=Stash <version>` makes it easy to identify Stash-tagged files in a mixed library. The version string is read from `BuildConfig.VERSION_NAME` — the `:data:download` module already exposes BuildConfig via its `buildFeatures { buildConfig = true }` setting in `build.gradle.kts`.

#### 3.2 Always-on callers

Both callers in the codebase are updated to pass the resolved art file:

- `TrackFinalizer.finalizeFile` (already calls embedder): inject `AlbumArtCache`, resolve art, pass it through.
- `DownloadManager.executeDownload` (yt-dlp path, currently bypasses embedder): inject `MetadataEmbedder` + `AlbumArtCache`, insert a post-download / pre-commit call.

The yt-dlp `--embed-metadata` flag in `QualityPreferencesManager.toYtDlpArgs()` **stays**. It's a free fallback — if our subsequent ffmpeg pass fails, the file still has yt-dlp's noisy tags rather than nothing. The KDoc comment on `executeDownload` line 244 ("Metadata is now embedded by yt-dlp via --embed-metadata flag. No separate ffmpeg step needed.") is updated.

#### 3.3 Container compatibility check

The existing embedder already verifies tagging worked by checking `outputFile.exists() && outputFile.length() > 0`. The only added risk is the `attached_pic` disposition with Opus, which has had ffmpeg support since 4.0 (and youtubedl-android bundles 6.x). Verified by the integration test in §6.

### 4. Pipeline integration

#### 4.1 yt-dlp path — `DownloadManager.executeDownload`

Current order (line 199 onward):

```
yt-dlp.download → commitDownload
```

New order:

```
yt-dlp.download → albumArtCache.resolveArt → metadataEmbedder.embedMetadata → commitDownload → trackDao.setMetadataEmbeddedAt
```

Both the art resolve and the embed are non-fatal: failures log and continue. The `setMetadataEmbeddedAt` write only happens on the success branch — a failed embed leaves `metadata_embedded_at` NULL, so backfill picks it up later.

#### 4.2 Lossless path — `TrackFinalizer.finalizeFile`

The finalizer gets `albumArtCache: AlbumArtCache` injected and passes the resolved art into the existing embedder call:

```kotlin
val art = runCatching { albumArtCache.resolveArt(track) }.getOrNull()
runCatching { metadataEmbedder.embedMetadata(sourceFile, track, art) }
    .onFailure { Log.w(TAG, "metadata embed failed: ${it.message}") }
```

Then on success, callers (`DownloadManager.tryLosslessDownload`, `SearchDownloadCoordinator`) stamp `setMetadataEmbeddedAt`. The stamp lives in the caller, not in the finalizer, because the finalizer is intentionally DB-free per its existing KDoc.

#### 4.3 Local-import path — `LocalImportCoordinator`

Locally-imported files already have correct tags (the importer reads them out of the file). They get `metadata_embedded_at = System.currentTimeMillis()` at import time so backfill skips them automatically.

### 5. Backfill worker

#### 5.1 `MetadataBackfillWorker`

Location: `data/download/src/main/kotlin/com/stash/data/download/backfill/MetadataBackfillWorker.kt`

```kotlin
@HiltWorker
class MetadataBackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
    private val metadataEmbedder: MetadataEmbedder,
    private val albumArtCache: AlbumArtCache,
    private val backfillState: MetadataBackfillState,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val total = trackDao.observeTracksNeedingEmbedCount().first()
        backfillState.markStarted(total)

        var processed = 0
        while (true) {
            // OFFSET stays 0 every batch — processed rows leave the result
            // set via the metadata_embedded_at stamp (success OR 0L failure
            // sentinel). See §5.3.
            val batch = trackDao.getTracksNeedingEmbed(BATCH_SIZE, offset = 0)
            if (batch.isEmpty()) break
            for (entity in batch) {
                processEntity(entity)
                processed++
                backfillState.publishProgress(processed, total)
            }
        }

        backfillState.markFinished()
        return Result.success()
    }

    private suspend fun processEntity(entity: TrackEntity) {
        val track = entity.toDomain()
        val pathString = track.filePath ?: return markFailed(track.id)

        // SAF check must precede the File() construction, because a
        // SAF row's filePath is a `content://...` URI string and
        // `File("content://...").exists()` always returns false —
        // wrapping it in File would collapse SAF rows indistinguishably
        // into the "file missing" branch and break the safSkipped
        // counter that the Home banner reads (§6.4).
        if (pathString.startsWith("content://")) {
            backfillState.incrementSafSkipped()
            return markFailed(track.id)
        }

        val file = File(pathString)
        if (!file.exists()) return markFailed(track.id)

        val art = runCatching { albumArtCache.resolveArt(track) }.getOrNull()
        val embedded = runCatching { metadataEmbedder.embedMetadata(file, track, art) }.isSuccess
        if (embedded) {
            trackDao.setMetadataEmbeddedAt(track.id, System.currentTimeMillis())
        } else {
            markFailed(track.id)
        }
    }

    private suspend fun markFailed(trackId: Long) =
        trackDao.setMetadataEmbeddedAt(trackId, 0L)

    companion object {
        private const val BATCH_SIZE = 50
        const val UNIQUE_WORK_NAME = "metadata_backfill"
    }
}
```

#### 5.2 Constraints

- `NetworkType.CONNECTED` — needed because art fetches go through the network. WiFi-only would block users on cellular indefinitely.
- No `requiresCharging` — backfill is CPU-light (ffmpeg `-c copy` is just mux, no transcode). On a Pixel 7 a 5-minute 16-bit FLAC re-muxes in ~150ms.
- `setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)` — try expedited first so progress is visible, fall back if quota exhausted.

#### 5.3 Pagination

Every batch query is `LIMIT 50 OFFSET 0`. As each row is processed, the worker stamps `metadata_embedded_at` either to `System.currentTimeMillis()` (success) or to `0L` (irrecoverable failure: file missing, SAF row, ffmpeg threw). Either value removes the row from the `WHERE metadata_embedded_at IS NULL` filter, so the next batch query naturally returns the next 50 unprocessed rows. The loop terminates when the batch comes back empty.

This avoids the offset bookkeeping a standard paginator would need, and guarantees no row is ever processed twice. Strip out the placeholder `offset += batch.size` line in the §5.1 sketch — it was leftover from a draft and is unused.

#### 5.4 `MetadataBackfillState`

Location: `data/download/src/main/kotlin/com/stash/data/download/backfill/MetadataBackfillState.kt`

Preferences DataStore-backed observable state for the Home banner, mirroring the file/key conventions of `LosslessSourcePreferences` (DataStore<Preferences>, top-level `Context.dataStore` extension defined alongside the class):

```kotlin
data class BackfillSnapshot(
    val state: State,           // IDLE, RUNNING, FINISHED
    val processed: Int,
    val total: Int,
    val safSkipped: Int,        // SAF/content:// rows skipped — see §6.4
    val finishedAt: Long?,
)

@Singleton
class MetadataBackfillState @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val snapshot: Flow<BackfillSnapshot>
    suspend fun markStarted(total: Int)
    suspend fun publishProgress(processed: Int, total: Int)
    suspend fun incrementSafSkipped()
    suspend fun markFinished()
}
```

Keys are scalar ints/longs in a private `Preferences` DataStore named `metadata_backfill_state` (file `metadata_backfill_state.preferences_pb`). The reactive `snapshot` is built by combining the four preference keys via `data.map { ... }`.

#### 5.5 Trigger: `MetadataBackfillScheduler`

Location: `data/download/src/main/kotlin/com/stash/data/download/backfill/MetadataBackfillScheduler.kt`

Called from `StashApp.onCreate()` after Hilt graph is up:

```kotlin
@Singleton
class MetadataBackfillScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val versionTracker: BackfillVersionTracker,
) {
    suspend fun scheduleIfNeeded() {
        if (versionTracker.shouldRunForCurrentVersion()) {
            workManager.enqueueUniqueWork(
                MetadataBackfillWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<MetadataBackfillWorker>()
                    .setConstraints(/* ... §5.2 */)
                    .setExpedited(...)
                    .build(),
            )
            versionTracker.markEnqueuedForCurrentVersion()
        }
    }
}
```

`BackfillVersionTracker` stores the highest version that has enqueued backfill in a Preferences-DataStore key (`backfill_enqueued_for_version`, int). The DataStore file is `metadata_backfill_state.preferences_pb` (same file as `MetadataBackfillState`, separate key). The current binary's version is read from `BuildConfig.VERSION_CODE` exposed by the `:data:download` module. Re-runs only happen when the stored value < current version — a clean upgrade path for future tagging fixes.

### 6. Home banner

#### 6.1 Banner state derivation

Location: `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt`

Add `MetadataBackfillState.snapshot` to the existing state combine. Add a new `HomeBannerState` variant alongside the existing ones (`NoSpotifyAuth`, `WaitingForLossless`, `LosslessSweepRunning`, etc. — see `HomeBannerState.kt` in the home feature for the current sealed hierarchy):

```kotlin
data class RetaggingLibrary(
    val processed: Int,
    val total: Int,
    val safSkipped: Int,
) : HomeBannerState
```

Priority ordering in the `reduce` function: shown only when no `NoSpotifyAuth` / `WaitingForLossless` / `LosslessSweepRunning` banner is active. Concretely: append `RetaggingLibrary` to the bottom of the existing `when`/`firstNotNullOf` priority chain. Dismisses itself when `processed == total` (worker calls `markFinished`, state transitions to FINISHED, banner hides after a 2-second "Done" pulse rendered by a `LaunchedEffect` in the home composable).

#### 6.2 Copy

- Running, `total > 0`: **"Re-tagging library — %d/%d"** (Title), **"Adding album art and metadata to existing files"** (subtitle).
- Finished, `total > 0`: **"Library re-tagged"** (Title), shown for 2s then dismissed.
- Empty library (`total == 0`): banner never shows.

#### 6.3 No banner action

No "skip" button. Backfill is fast (estimated ~30 minutes for a 1000-track library, dominated by art HTTP) and the user gets a tangibly better library at the end. A skip option would just trade a transient banner for a permanent half-tagged library.

#### 6.4 SAF-mode footnote

When the worker encounters a row with `filePath.startsWith("content://")` it marks `metadata_embedded_at = 0L` and increments a SAF-skip counter in `BackfillSnapshot`. The Home banner's finished-state copy switches to **"Library re-tagged. X tracks on external storage will be tagged on next download."** when `safSkipped > 0`. Those rows naturally re-acquire correct tags the next time the user re-downloads them or moves them back to internal storage.

### 7. Error handling

| Failure | Behaviour | Surfaced to user? |
|---|---|---|
| Art HTTP returns non-200 / non-image | `resolveArt` returns null, embed proceeds with tags only | No |
| `albumArtUrl` is null on Track | `resolveArt` returns null immediately | No |
| ffmpeg `-c copy` fails (corrupt input, unknown container) | `embedMetadata` catches, leaves untagged file in place, returns it | No on yt-dlp path; backfill marks `metadata_embedded_at = 0L` |
| Track file deleted between query and processing | Skip, mark `0L` | No |
| WorkManager kills the worker mid-batch | Next launch re-enqueues; in-progress row's stamp was never written, so it's re-processed | No |
| Migration v22→v23 fails | Room throws on first DB access; existing exception path | Yes — generic app failure (not new) |

### 8. Testing

#### 8.1 Unit tests

- `AlbumArtCacheTest`: cache hit returns cached file; cache miss fetches once and writes; same-album second call doesn't refetch; 404 returns null; non-image response returns null; same album URL with different size suffixes hits the same cache file.
- `MetadataEmbedderTest` (new): argv construction includes `album_artist` when track.artist non-blank; `isrc` only when present; `encoder` always; sanitisation strips control chars.
- `MetadataBackfillWorkerTest`: empty library → success no work; 100 rows → 100 stamps; failed embed → 0L stamp; SAF row → 0L + safSkipped++; 0L rows excluded from next pass.

#### 8.2 Integration test

Location: `data/download/src/androidTest/kotlin/com/stash/data/download/MetadataEmbeddingIntegrationTest.kt`

On-device test that runs against three real audio samples (placed in androidTest assets):

```kotlin
@Test fun embedsTagsAndArtIntoOpus() = runTest { ... }
@Test fun embedsTagsAndArtIntoM4a() = runTest { ... }
@Test fun embedsTagsAndArtIntoFlac() = runTest { ... }
```

Each test calls `MetadataEmbedder.embedMetadata(sampleFile, sampleTrack, sampleJpeg)`, then opens the result with `MediaMetadataRetriever` and asserts:

- `METADATA_KEY_TITLE`, `METADATA_KEY_ARTIST`, `METADATA_KEY_ALBUM`, `METADATA_KEY_ALBUMARTIST` match the Track values
- `getEmbeddedPicture()` returns non-null bytes matching the input JPEG length (within ±5% — ffmpeg may rewrite Exif)

#### 8.3 Manual verification

Per the project's `feedback_install_after_fix.md` memory: after each build, `./gradlew :app:installDebug`, run the app, sync a Spotify Daily Mix, then:

1. Open a downloaded file in **Plex** (via export over USB) — assert title/artist/album/cover visible.
2. Open the same file in **Foobar2000** on desktop — assert ALBUMARTIST groups correctly.
3. Plug device into a 2018+ car USB stick — assert track title and cover render on the head unit.
4. Trigger backfill on a pre-v0.9.35 library snapshot — assert banner shows progress, completes, files re-tagged.

### 9. Rollout

- Version bump `0.9.34 → 0.9.35`, versionCode `+1`.
- Room schema v22 → v23, single `ALTER TABLE` migration.
- Backfill auto-enqueues on first launch after upgrade. Idempotent: re-installing 0.9.35 over 0.9.35 doesn't re-enqueue (version tracker).
- No new permissions. No new third-party libraries.
- No flag — this is a behaviour fix, not an opt-in feature.

### 10. Open questions

- **Album-art quality vs file size.** Spotify's 640×640 JPEG is ~80 KB. M4A bloats by exactly that amount per file. Opus's `METADATA_BLOCK_PICTURE` is base64-encoded, so the actual bloat is ~108 KB. For a 1000-track library that's 80–110 MB of additional disk. Mitigation: we use the 640px tier (not 1500px), and Android's storage UI shows the audio files themselves rather than a side index, so the user perceives this as expected. If users complain, a future Settings toggle could prefer 300px or no embed.
- **ffmpeg version skew.** youtubedl-android bundles its own ffmpeg .so. The `JunkFood02/youtubedl-android` fork is currently on ffmpeg 6.x — `-disposition:v:0 attached_pic` was added in ffmpeg 4.0, so we're safe. If the bundled binary regresses, the integration tests catch it.
- **Backfill duration on huge libraries.** A user with 10,000 tracks waits for ~5 hours of background work. The Home banner shows progress, but a "Pause" or "Defer" option isn't in scope. We accept this — most users have <2,000 tracks based on the analytics-free intuition that this is a self-curated music app.

## Acceptance

Per the original audit (`project_next_work_after_v0934.md`): this closes #76 (the first feature ask — "ARTWORK AND METADATA INCLUDED WITH FLAC FILE") and #90 ("Missing Metadata"), and aligns Stash with its core "your music, yours forever" promise. The custom-filename-pattern half of #76 is filed as a follow-up.
