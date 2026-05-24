# v0.9.36 — Lyrics integration (LRCLIB + InnerTube + sidecar + Spotify-style sheet)

**Date:** 2026-05-23
**Status:** Design
**Branch:** `feat/lyrics-integration` (to be created)
**Follows:** v0.9.35 (`d61af9e`) — metadata + album-art embedding (Cluster B)
**Closes:** lyrics-integration tracking issue (to file)

## Problem

Users are asking for lyrics. The v0.9.35 ship session noted "lots of users asking about lyric integration" as the next surface after metadata embedding.

Stash has none today. Tracks are downloaded with TITLE / ARTIST / ALBUM tags (v0.9.35) and album art (v0.9.35), but nothing surfaces the words. Now Playing shows the art, the title, the artist, the album, the quality line, and playback controls — but there is no place a user can read along with what's playing. The FOSS reference players in Stash's adjacent space (InnerTune, ViMusic, RiMusic, Symfonium) all have lyrics; Stash's omission is felt.

The fix is bounded. LRCLIB ([lrclib.net/docs](https://lrclib.net/docs)) is a free, no-auth, community-maintained synced-lyrics database that mirrors Musixmatch coverage for popular catalog. InnerTube exposes a `browse` endpoint that returns YouTube Music's plain-text lyrics when a `get_watch_playlist` reply includes an `MPLY...` browseId — Stash already wires `get_watch_playlist` for queue building, so the fallback path costs no new client. Sidecar `.lrc` writes are the interop bonus: Plex, Symfonium, Poweramp, and car USB players auto-read `<basename>.lrc` next to the audio file, reinforcing the v0.9.35 "your music, yours forever" promise.

## Goals

- Every **new** download — yt-dlp or lossless — gets a lyrics-fetch enqueue on success, hooked next to the existing `metadata_embedded_at` stamp sites.
- The **existing** library is repaired in place by a one-time backfill pass that runs automatically on first launch after the v0.9.36 upgrade. Progress is surfaced through the existing Home-banner pattern. Failures are non-fatal — the worker logs and moves on.
- A new **LyricsBottomSheet** opens from a Lyrics IconButton in the Now Playing top bar. It auto-follows the playing track (lyrics swap when the song changes), auto-scrolls synced LRC with the current line emphasized, and supports **tap-to-seek** on synced lines (tap a line → `MediaSession.seekTo(timestampMs)`).
- Lyrics are written to **two surfaces** on success: a Room `lyrics` table (read by the sheet) and a sidecar `.lrc` file next to the audio (read by external players). Both internal storage and SAF tree paths are supported for sidecar writes.
- Idempotent. Re-running the backfill on an already-fetched track is a no-op. Tracks deleted off disk while a fetch is in flight produce no orphan rows (FK cascade). Sidecar write failure does not lose the in-memory result — Room is the source of truth.
- Existing UI surfaces are preserved (`feedback_preserve_existing_design.md`). The TopBar gains exactly one IconButton; no other Now Playing element moves.

## Non-goals

- **No "wrong lyrics?" manual edit or search override.** User explicitly out of scope: "I don't think it's necessary to change the lyrics at all tbh, at least not for right now. Ideally we won't get the lyrics wrong often enough to warrant any changes." Revisit if real-world miss rate becomes a complaint cluster.
- **No sidecar read at load time.** User declined the file-drop / user-provided `.lrc` flow. Lyrics come from Room or network only. The sidecar is write-only — Stash never reads it back.
- **No KuGou source.** Defer to Phase 2 if CJK coverage gaps show up in user reports. LRCLIB + InnerTube is Phase 1.
- **No Musixmatch / Genius.** Musixmatch is paid; Genius is annotations-only with no raw lyric body.
- **No word-level highlighting.** Phase 2. Phase 1 is line-level: current line emphasized, surrounding lines dimmed, auto-scroll. Tap-to-seek operates at line granularity.
- **No AI translation.** SimpMusic does this — out of scope.
- **No karaoke "big text" mode.**
- **No lyrics embedded in audio file (USLT/SYLT/LYRICS tag).** v0.9.35 just stabilised the embedder; do not re-touch the audio file. Sidecar `.lrc` is the interop format we ship.
- **No backfill cancellation UI.** Same model as v0.9.35 — worker pauses/resumes across reboots via WorkManager.

## Design

### 1. Module layout

A new Gradle module `:data:lyrics` is added as a sibling to `:data:download`. Nothing in `:data:download` imports from `:data:lyrics`; the wiring is one-way via an interface in `:data:download` (`LyricsFetchTrigger`) that is provided by `:app` and points at `:data:lyrics`'s `LyricsFetchWorker.enqueue(trackId)`. The dependency arrow is `:app → :data:lyrics → :core:data + :data:innertube`. No circular module dependency.

**`:data:lyrics` exports** (all classes live under `com.stash.data.lyrics`):

- `source/LyricsSource` interface + `LyricsResult` data class
- `source/LrclibLyricsSource`, `source/YtMusicLyricsSource`
- `LyricsRepository` — sole entrypoint for UI and workers
- `parser/LrcParser` + `LrcLine` data class
- `worker/LyricsFetchWorker` + `worker/LyricsBackfillWorker`
- `backfill/LyricsBackfillScheduler`, `backfill/LyricsBackfillState`
- `sidecar/LyricsSidecarWriter`

**Module dependencies:** `:core:common` (AppVersionProvider, dispatchers), `:core:data` (Room, TrackDao, new LyricsDao), `:core:network` (OkHttp), `:data:innertube` (existing wiring).

**Consumers:**
- `:feature:nowplaying` consumes `LyricsRepository` via Hilt; new files `LyricsBottomSheet.kt`, `LyricsView.kt`, `LyricsViewState.kt` in `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/ui/`.
- `:feature:home` consumes `LyricsBackfillState`; new files `LyricsBackfillBanner.kt` + `LyricsBackfillBannerState.kt` in `feature/home/src/main/kotlin/com/stash/feature/home/banner/`.
- `:app` provides the `LyricsFetchTrigger` Hilt binding and calls `LyricsBackfillScheduler.scheduleIfNeeded()` from `StashApplication.onCreate`.

### 2. Data model

#### 2.1 New column on `tracks`

Location: `core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt`

```kotlin
/**
 * Epoch-millis of the most recent lyrics fetch attempt that produced a result.
 * NULL  = never tried (legacy v0.9.35 and earlier rows, or new rows pre-fetch).
 * 0L    = tried, no lyrics available (LRCLIB miss + InnerTube miss). Keeps
 *         hopeless rows out of the backfill query until the next binary
 *         version bump re-fires the worker.
 * other = success epoch-millis.
 */
val lyricsFetchedAt: Long? = null,
```

Same sentinel semantics as `metadata_embedded_at` (v0.9.35). LRCLIB `instrumental: true` is treated as a successful fetch (Room row written with `instrumental = 1`, stamp set to the fetch timestamp) — the user sees a clean "♪ Instrumental" placard, not the empty state.

#### 2.2 New `lyrics` table

Location: `core/data/src/main/kotlin/com/stash/core/data/db/entity/LyricsEntity.kt`

```kotlin
@Entity(
    tableName = "lyrics",
    foreignKeys = [ForeignKey(
        entity = TrackEntity::class,
        parentColumns = ["id"],
        childColumns = ["track_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("track_id")],
)
data class LyricsEntity(
    @PrimaryKey @ColumnInfo("track_id")        val trackId: Long,
    @ColumnInfo("plain_text")                  val plainText: String?,
    @ColumnInfo("synced_lrc")                  val syncedLrc: String?,
    @ColumnInfo("instrumental")                val instrumental: Boolean,
    @ColumnInfo("language")                    val language: String?,
    @ColumnInfo("source")                      val source: String,           // "lrclib" | "innertube"
    @ColumnInfo("source_lyrics_id")            val sourceLyricsId: String?,  // LRCLIB numeric id stringified
    @ColumnInfo("fetched_at")                  val fetchedAt: Long,
)
```

A row exists only for **successful** fetches (text returned **or** LRCLIB confirmed instrumental). The 0L sentinel on `tracks.lyrics_fetched_at` carries the "tried-and-failed-no-row" state — no all-NULL rows in `lyrics`. CASCADE on track delete cleans up the lyrics row.

#### 2.3 Migration v27 → v28

Location: `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt` (companion object)

```kotlin
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN lyrics_fetched_at INTEGER")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS lyrics (
              track_id INTEGER NOT NULL PRIMARY KEY,
              plain_text TEXT,
              synced_lrc TEXT,
              instrumental INTEGER NOT NULL DEFAULT 0,
              language TEXT,
              source TEXT NOT NULL,
              source_lyrics_id TEXT,
              fetched_at INTEGER NOT NULL,
              FOREIGN KEY(track_id) REFERENCES tracks(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_lyrics_track_id ON lyrics(track_id)")
    }
}
```

Bump `StashDatabase.version = 28`. Register in the Hilt module's `addMigrations(MIGRATION_26_27, MIGRATION_27_28)` call. KSP produces `core/data/schemas/com.stash.core.data.db.StashDatabase/28.json` at build time — commit the JSON.

#### 2.4 `LyricsDao`

Location: `core/data/src/main/kotlin/com/stash/core/data/db/dao/LyricsDao.kt`

```kotlin
@Dao interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE track_id = :trackId")
    suspend fun get(trackId: Long): LyricsEntity?

    @Query("SELECT * FROM lyrics WHERE track_id = :trackId")
    fun observe(trackId: Long): Flow<LyricsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LyricsEntity)

    @Query("DELETE FROM lyrics WHERE track_id = :trackId")
    suspend fun delete(trackId: Long)
}
```

#### 2.5 `TrackDao` additions

Mirror the v0.9.35 trio used by `MetadataBackfillWorker`:

```kotlin
@Query("UPDATE tracks SET lyrics_fetched_at = :ts WHERE id = :trackId")
suspend fun setLyricsFetchedAt(trackId: Long, ts: Long)

@Query("SELECT * FROM tracks WHERE lyrics_fetched_at IS NULL ORDER BY id LIMIT :limit")
suspend fun getTracksNeedingLyrics(limit: Int): List<TrackEntity>

@Query("SELECT COUNT(*) FROM tracks WHERE lyrics_fetched_at IS NULL")
fun observeTracksNeedingLyricsCount(): Flow<Int>
```

OFFSET=0 is deliberate. Every stamp removes the row from the `WHERE lyrics_fetched_at IS NULL` filter, so the next `LIMIT 50` query naturally returns the next 50 unprocessed rows. The loop terminates when the batch comes back empty. No offset bookkeeping; no row processed twice.

### 3. Source layer

#### 3.1 `LyricsSource` interface

Location: `data/lyrics/src/main/kotlin/com/stash/data/lyrics/source/LyricsSource.kt`

```kotlin
interface LyricsSource {
    val id: String                          // "lrclib" | "innertube"
    val displayName: String                 // for diagnostic logs only
    suspend fun resolve(query: LyricsQuery): LyricsResult?
}

data class LyricsQuery(
    val trackId: Long,
    val title: String,
    val artist: String,
    val album: String?,
    val albumArtist: String?,
    val durationMs: Long?,
    val youtubeVideoId: String?,            // populates InnerTube fallback path
)

data class LyricsResult(
    val sourceId: String,                   // matches LyricsSource.id
    val plainText: String?,
    val syncedLrc: String?,                 // raw LRC body; null when source returned plain-only
    val instrumental: Boolean,
    val language: String?,
    val sourceLyricsId: String?,            // LRCLIB id stringified, null for InnerTube
)
```

A nullable `LyricsResult?` return is the miss signal — matches `LosslessSource.resolve` shape exactly. No sealed-result class.

The source chain is a `List<LyricsSource>` injected via Hilt multibinding, ordered: `LrclibLyricsSource` first, `YtMusicLyricsSource` second. `LyricsRepository` walks the list in order and returns the first non-null result.

#### 3.2 `LrclibLyricsSource`

Location: `data/lyrics/src/main/kotlin/com/stash/data/lyrics/source/LrclibLyricsSource.kt`

LRCLIB API (confirmed live May 2026):
- `GET https://lrclib.net/api/get?track_name=...&artist_name=...&album_name=...&duration=...` — exact-match endpoint. Returns 200 + JSON body on a hit, 404 otherwise.
- `GET https://lrclib.net/api/search?q=<query>` — fallback when `/get` misses. Returns an array; pick the highest-scoring row by Jaro-Winkler similarity against `"$artist $title"`, with a confidence floor.

**Response JSON** (camelCase):
```
{ "id": 12345, "trackName": "...", "artistName": "...", "albumName": "...",
  "duration": 234, "instrumental": false,
  "plainLyrics": "...",      // string | null
  "syncedLyrics": "..." }    // string | null (raw LRC body)
```

**HTTP client.** Reuse the `:core:network` OkHttp client; add a `User-Agent: Stash/<versionName> (https://github.com/rawnaldclark/Stash)` header per LRCLIB's request that descriptive UAs be sent. Version comes from `AppVersionProvider.versionName`.

**Duration-tolerance ladder** (the single biggest accuracy lever — flagged in the v0.9.35 plan-review). LRCLIB matching is exact by default and the most common miss cause is a duration mismatch from rip-vs-master variation:

1. **Exact:** call `/api/get` with `duration = durationMs / 1000` (integer seconds). If 200 → return.
2. **±2s, closer-to-exact first:** call `/api/get` with `duration - 1`, then `duration + 1`, then `duration - 2`, then `duration + 2`. Order is deliberate: ±1s is "integer-rounding noise" and is the most likely true match; ±2s is "different rip/master." First 200 wins.
3. **±5s, closer-to-exact first:** widen with `-3, +3, -4, +4, -5, +5` (excluding values already tried at rung 2). First 200 wins.
4. **Search fallback:** `/api/search?q=<artist title>`; pick top result whose duration is within ±5s AND whose Jaro-Winkler similarity over `"$artist $title"` ≥ 0.85.
5. **Miss:** return null.

The ladder is short-circuiting — as soon as any rung hits, the rest are skipped. Worst case is 11 HTTP requests for a single miss; nearly all real-world tracks resolve at rung 1 or 2.

**Instrumental handling.** If LRCLIB returns `instrumental: true`, `LrclibLyricsSource` builds a `LyricsResult` with `instrumental = true`, `plainText = null`, `syncedLrc = null`. The repository treats this as a successful fetch (Room row written, stamp set).

**Error policy.** Network exceptions / 5xx / malformed JSON → return null. Let the upstream worker decide whether to retry via WorkManager's backoff policy (transient) or stamp 0L (after retries exhausted).

#### 3.3 `YtMusicLyricsSource`

Location: `data/lyrics/src/main/kotlin/com/stash/data/lyrics/source/YtMusicLyricsSource.kt`

Uses the existing InnerTube wiring in `:data:innertube`. Two-call sequence:

1. `get_watch_playlist(videoId = query.youtubeVideoId)` — returns a playlist payload that may include a `MPLY...` browseId. Stash already calls this for queue building; reuse the existing client and response model.
2. If a `MPLY...` browseId is present: `browse(browseId)` — returns a plain-text lyrics block.

Returns `LyricsResult(sourceId = "innertube", plainText = <text>, syncedLrc = null, instrumental = false, language = null, sourceLyricsId = null)`. InnerTube does not provide synced LRC — this path only ever fills `plainText`.

If `query.youtubeVideoId` is null (e.g., lossless-only track), `YtMusicLyricsSource` returns null without making any HTTP calls.

#### 3.4 `LrcParser`

Location: `data/lyrics/src/main/kotlin/com/stash/data/lyrics/parser/LrcParser.kt`

Internal utility. Parses the raw LRC body into a list of timestamped lines.

```kotlin
data class LrcLine(val timestampMs: Long, val text: String)

object LrcParser {
    /**
     * Parses LRC content (e.g. "[01:23.45]hello world\n[01:25.10]...").
     * - Skips malformed lines (logs warn, does not throw).
     * - Strips LRC metadata tags ([ti:...], [ar:...], [al:...], [length:...], [by:...], [offset:...]).
     * - Sorts result by timestamp ascending.
     * - Returns empty list on completely malformed input.
     */
    fun parse(body: String): List<LrcLine>
}
```

Format is `[mm:ss.xx]text` or `[mm:ss.xxx]text`. A single line can carry multiple timestamps (`[00:10.00][00:30.00]foo`) which expands to two LrcLine entries with the same text. Lines with no leading timestamp are skipped (they are not synced lyrics). The parser is pure and stateless — covered by unit tests.

### 4. `LyricsRepository`

Location: `data/lyrics/src/main/kotlin/com/stash/data/lyrics/LyricsRepository.kt`

Sole entrypoint for both UI (sheet) and workers (fetch + backfill). Coordinates source chain → Room write → sidecar write → stamp.

```kotlin
@Singleton
class LyricsRepository @Inject constructor(
    private val sources: List<@JvmSuppressWildcards LyricsSource>,
    private val lyricsDao: LyricsDao,
    private val trackDao: TrackDao,
    private val sidecarWriter: LyricsSidecarWriter,
    private val clock: Clock,
) {
    /** Observe lyrics for the playing track. Used by the sheet. */
    fun observe(trackId: Long): Flow<LyricsEntity?> = lyricsDao.observe(trackId)

    /** Whether a fetch has been attempted (any non-NULL on tracks.lyrics_fetched_at). */
    suspend fun fetchAttempted(trackId: Long): Boolean

    /**
     * One-shot orchestration: source chain → Room upsert → sidecar write → stamp.
     * Idempotent. Returns the fetched LyricsEntity (or null on miss).
     *
     * Stamps tracks.lyrics_fetched_at on EVERY terminal outcome:
     *   - success (text or instrumental): clock.now()
     *   - miss (all sources returned null): 0L
     * Throws only on infrastructure failure (DB unreachable). Workers convert
     * those into WorkManager retries.
     */
    suspend fun resolveAndStore(query: LyricsQuery): LyricsEntity?
}
```

Algorithm of `resolveAndStore`:

1. Walk `sources` in order. First non-null `LyricsResult` wins. If all return null → stamp `setLyricsFetchedAt(trackId, 0L)`, return null.
2. On a hit, build `LyricsEntity` from the result and `lyricsDao.upsert(...)`.
3. Stamp `setLyricsFetchedAt(trackId, clock.now())`.
4. Trigger sidecar write via `sidecarWriter.write(trackId, result)`. Failure here is non-fatal: log warn, **do not** unwind the Room state.
5. Return the persisted `LyricsEntity`.

Race condition note. If two enqueues for the same `trackId` run concurrently (e.g., post-download + user-tapped Retry), the OnConflictStrategy.REPLACE upsert and the idempotent stamp prevent corruption. The second call may waste one source-chain walk; acceptable.

### 5. `LyricsSidecarWriter`

Location: `data/lyrics/src/main/kotlin/com/stash/data/lyrics/sidecar/LyricsSidecarWriter.kt`

Writes `<basename>.lrc` next to the audio file on every successful lyrics fetch. Failure is non-fatal — the Room row is the source of truth; the sidecar is interop polish.

**Body precedence.** Prefer `syncedLrc` if non-null; otherwise write `plainText` as a single block. If both are null (instrumental confirmed by LRCLIB) **do not write a sidecar** — there is nothing to write.

**Header.** Prepend canonical LRC metadata tags so external players display them:
```
[ti:Off The Grid]
[ar:Kanye West]
[al:DONDA]
[length:04:39]
[by:Stash 0.9.36]
```

**Path resolution.** Two cases:

- **Internal storage** (`storagePreference.externalTreeUri == null`): `CommittedTrack.filePath` is an absolute `java.io.File` path. Sidecar is `File(filePath).resolveSibling("${File(filePath).nameWithoutExtension}.lrc")`. Write via `File.writeText(body, Charsets.UTF_8)`.
- **SAF tree** (`externalTree != null`): `CommittedTrack.filePath` is a `content://…` URI string. Resolve the parent `DocumentFile` via `DocumentFile.fromTreeUri(...)` and walk `findOrCreateDir(artistSlug).findOrCreateDir(albumSlug).createFile("application/x-lrc", "$titleSlug.lrc")`. `DocumentFile` has no built-in `findOrCreateDir`; either reuse the equivalent helper already present in `FileOrganizer.writeToSafTree()` (preferred) or implement a local `existing ?: parent.createDirectory(name)` helper. Write the file body via `contentResolver.openOutputStream(...)`.

The track's artist/album/title slugs are needed for the SAF path. **`FileOrganizer.slugify` is currently private.** Recommended path: expose an `internal object FileOrganizerSlugs` in `:data:download` with the slug helpers, then `:data:lyrics` depends on it — keeps slug semantics in one place. (Duplicating the logic into `LyricsSidecarWriter` is a valid alternative if cross-module visibility proves awkward, but is the fallback, not the first move.)

**Non-goal restated:** Stash does not read sidecars back. The write is the entire interop story.

### 6. Workers

#### 6.1 `LyricsFetchWorker` — post-download + priority one-shot

Location: `data/lyrics/src/main/kotlin/com/stash/data/lyrics/worker/LyricsFetchWorker.kt`

```kotlin
@HiltWorker
class LyricsFetchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val lyricsRepository: LyricsRepository,
    private val trackDao: TrackDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val trackId = inputData.getLong(KEY_TRACK_ID, -1L).takeIf { it > 0L } ?: return Result.failure()
        val track = trackDao.get(trackId) ?: return Result.success()    // deleted mid-flight: no-op
        val query = LyricsQuery(
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album.ifBlank { null },
            albumArtist = track.albumArtist.ifBlank { null },
            durationMs = track.durationMs.takeIf { it > 0 },
            youtubeVideoId = track.youtubeVideoId,
        )
        return runCatching { lyricsRepository.resolveAndStore(query) }
            .fold(
                onSuccess = { Result.success() },
                onFailure = { if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success() },
            )
    }

    companion object {
        const val KEY_TRACK_ID = "track_id"
        private const val MAX_ATTEMPTS = 3
        const val UNIQUE_PREFIX_POST_DOWNLOAD = "lyrics_post_download_"
        const val UNIQUE_PREFIX_PRIORITY     = "lyrics_priority_"
    }
}
```

Two enqueue origins:
- **Post-download** (from `DownloadManager` / `SearchDownloadCoordinator` via `LyricsFetchTrigger`): unique work name `"lyrics_post_download_<trackId>"`, `ExistingWorkPolicy.KEEP`, network constraint. Non-expedited — runs in the background, no rush.
- **Priority on-open** (from the sheet, when a track with NULL stamp is opened): unique work name `"lyrics_priority_<trackId>"`, `ExistingWorkPolicy.REPLACE`, expedited via `setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)`. The two unique-name namespaces are intentional — a priority enqueue while a post-download job is queued does not collide; the priority job wins because it's enqueued separately and expedited.

On `Result.failure` after `MAX_ATTEMPTS` retries, the worker returns `Result.success()` deliberately — leaving the row NULL so the next-version backfill retries. Stamping 0L here would prevent that.

#### 6.2 `LyricsBackfillWorker` — once-per-version drain

Location: `data/lyrics/src/main/kotlin/com/stash/data/lyrics/worker/LyricsBackfillWorker.kt`

```kotlin
@HiltWorker
class LyricsBackfillWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
    private val lyricsRepository: LyricsRepository,
    private val backfillState: LyricsBackfillState,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val total = trackDao.observeTracksNeedingLyricsCount().first()
        backfillState.markStarted(total)
        var processed = 0
        while (true) {
            val batch = trackDao.getTracksNeedingLyrics(BATCH_SIZE)
            if (batch.isEmpty()) break
            for (track in batch) {
                runCatching { lyricsRepository.resolveAndStore(track.toQuery()) }
                    .onFailure { trackDao.setLyricsFetchedAt(track.id, 0L) }   // infra failure → 0L
                processed++
                backfillState.publishProgress(processed, total)
            }
        }
        backfillState.markFinished()
        return Result.success()
    }

    companion object {
        private const val BATCH_SIZE = 50
        const val UNIQUE_WORK_NAME = "lyrics_backfill"
    }
}
```

Pagination is `LIMIT 50 OFFSET 0`. Every row processed either stamps non-zero (success) or 0L (failure), removing it from `WHERE lyrics_fetched_at IS NULL`. Loop terminates when the batch is empty.

Constraints:
- `NetworkType.CONNECTED` — every fetch is a network call.
- No `requiresCharging` — fetches are I/O-light; LRCLIB responses are small JSON.
- `setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)` — try expedited so progress is visible early; gracefully fall back if quota exhausted.

#### 6.3 `LyricsBackfillState`

Location: `data/lyrics/src/main/kotlin/com/stash/data/lyrics/backfill/LyricsBackfillState.kt`

```kotlin
/** Local enum, defined in this file (separate from MetadataBackfillState's State). */
enum class State { IDLE, RUNNING, FINISHED }

data class LyricsBackfillSnapshot(
    val state: State,
    val processed: Int,
    val total: Int,
    val finishedAt: Long?,
)

@Singleton
class LyricsBackfillState @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val snapshot: Flow<LyricsBackfillSnapshot>
    suspend fun markStarted(total: Int)
    suspend fun publishProgress(processed: Int, total: Int)
    suspend fun markFinished()
    suspend fun markFinishedAcknowledged()
}
```

DataStore-backed. File: `lyrics_backfill_state.preferences_pb`. No `safSkipped` counter (unlike v0.9.35) — SAF rows are fetchable, sidecar SAF writes work via `DocumentFile`, and Room writes are storage-agnostic.

#### 6.4 `LyricsBackfillScheduler`

Location: `data/lyrics/src/main/kotlin/com/stash/data/lyrics/backfill/LyricsBackfillScheduler.kt`

```kotlin
@Singleton
class LyricsBackfillScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val versionTracker: BackfillVersionTracker,
) {
    suspend fun scheduleIfNeeded() {
        if (versionTracker.shouldRunForCurrentVersion(KEY)) {
            workManager.enqueueUniqueWork(
                LyricsBackfillWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LyricsBackfillWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build(),
            )
            versionTracker.markEnqueuedForCurrentVersion(KEY)
        }
    }

    private companion object { const val KEY = "lyrics_backfill_enqueued_for_version" }
}
```

**Reuse `BackfillVersionTracker`.** v0.9.35 defined `BackfillVersionTracker` for metadata; the existing class hard-codes its preference key to `backfill_enqueued_for_version`. This spec **generalises** `BackfillVersionTracker` by parameterising the key — `shouldRunForCurrentVersion(key)` and `markEnqueuedForCurrentVersion(key)` become `key: String` parameters; the no-arg overloads remain for the metadata caller (deprecate-and-keep, or update both call sites in this PR — implementer's choice; recommended: update both, single key per backfill type, simpler reasoning). Metadata key stays `backfill_enqueued_for_version` for migration compatibility; lyrics uses `lyrics_backfill_enqueued_for_version`. Both live in `backfillDataStore` (same file as metadata progress) — separate keys, no collision.

Called once from `StashApplication.onCreate()` on `applicationScope.launch { … }` after Hilt is ready, alongside the existing `metadataBackfillScheduler.scheduleIfNeeded()` call.

### 7. Hook into download success branches

A new interface in `:data:download`:

```kotlin
// data/download/src/main/kotlin/com/stash/data/download/lyrics/LyricsFetchTrigger.kt
interface LyricsFetchTrigger {
    fun enqueueFor(trackId: Long)
}
```

`:data:download` consumes this interface; the implementation provided in `:app`'s Hilt module delegates to `LyricsFetchWorker.enqueue(trackId)` with the post-download unique-name prefix. A no-op default binding lives in `:data:download`'s test module so unit tests don't need the lyrics module.

**Call sites.** Line numbers are at spec authoring time and will drift — the implementer should locate the hooks by symbol, not line:

- `DownloadManager` (lossless completion) — both branches that call `trackDao.setMetadataEmbeddedAt(track.id, ...)` (line numbers as of authoring: `:288-290` and `:429-431`). After each stamp, add `lyricsFetchTrigger.enqueueFor(track.id)`.
- `SearchDownloadCoordinator` (yt-dlp path) — the call site of `stampEmbeddedAt(track.videoId)` (line ~327 at authoring). After the stamp, call `lyricsFetchTrigger.enqueueFor(track.id)`. The trackId lookup is already done inside `stampEmbeddedAt`; recommended refactor: have `stampEmbeddedAt` return the trackId and reuse, instead of duplicating the lookup.

Neither call site blocks the download; `enqueueFor` is a fire-and-forget WorkManager enqueue.

### 8. UI

#### 8.1 Now Playing TopBar — new IconButton

Location: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt:241` (the `TopBar` Row).

Current order: `Dismiss · spacer · Flag · Like · Download · Save · Queue` (6 trailing IconButtons).

**New order:** `Dismiss · spacer · Flag · Like · Download · Save · Lyrics · Queue` (7 trailing).

Lyrics sits between Save and Queue. Rationale: keeps the "track operations" cluster (Flag / Like / Download / Save) and the "playback context" cluster (Queue) adjacent; Lyrics joins the playback-context cluster as another "view in-progress playback content" action. No existing icon moves.

**Icon:** `Icons.Outlined.Lyrics` (Material extended icon set; available via the existing `androidx.compose.material.icons:material-icons-extended` dependency). Fallback `Icons.Outlined.Subject` if the extended set is not on the classpath — verify in the implementing PR.

**Narrow-screen pressure.** The TopBar is already at the seam of the existing narrow-screen jank work (`feedback_preserve_existing_design.md`). Per the spec for `2026-05-23-narrow-screen-ui-jank-design.md`, the row already uses `Modifier.weight(1f)` on the spacer. Adding one more IconButton is layout-safe on screens ≥ 360dp; on the narrowest devices the icons stay compact (24dp) and the existing tap target spec (48dp) holds. No restructure proposed.

**Click handler:** opens `LyricsBottomSheet` via the existing `viewModel.onShowLyrics()` action (new on `NowPlayingViewModel`).

#### 8.2 `LyricsBottomSheet`

Location: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/ui/LyricsBottomSheet.kt`

```kotlin
@Composable
fun LyricsBottomSheet(
    state: LyricsViewState,
    currentPositionMs: Long,
    onSeek: (Long) -> Unit,                 // tap-to-seek line callback
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
)
```

Wraps `ModalBottomSheet` with `rememberModalBottomSheetState(skipPartiallyExpanded = true)` — identical wrapper to `QueueBottomSheet.kt:96`. The sheet pulls to ~95% height; the strip of player visible at top is intentional (matches the sheet pattern Stash already uses for Queue and Save).

**Auto-follow.** The sheet subscribes to the currently-playing track's id via `viewModel.currentTrackIdFlow` and calls `lyricsRepository.observe(trackId)` to feed the renderer. When the song advances, the underlying `Flow<LyricsEntity?>` emits the next track's row and the sheet re-renders. No close-on-track-change.

**Priority enqueue on open.** When the sheet is first composed (or when it observes a NULL-stamped track), it calls `viewModel.onLyricsRequested(trackId)` which enqueues a priority `LyricsFetchWorker` for that track. Idempotent — repeated calls hit `ExistingWorkPolicy.REPLACE`.

#### 8.3 `LyricsViewState` + renderer

`LyricsViewState` is a sealed interface:

```kotlin
sealed interface LyricsViewState {
    object Loading : LyricsViewState
    data class Synced(val lines: List<LrcLine>, val plainFallback: String) : LyricsViewState
    data class Plain(val text: String) : LyricsViewState
    object Instrumental : LyricsViewState
    object None : LyricsViewState
    data class Error(val retryable: Boolean) : LyricsViewState
}
```

State derivation (in `NowPlayingViewModel`):
- `track.lyrics_fetched_at == null` AND no row → `Loading` (and priority enqueue fires).
- Row present + `instrumental == true` → `Instrumental`.
- Row present + `syncedLrc != null` + `LrcParser.parse(syncedLrc).isNotEmpty()` → `Synced(lines, plainText.orEmpty())`.
- Row present + `plainText != null` → `Plain(plainText)`.
- `lyrics_fetched_at == 0L` (stamped failure) → `None`.

**Synced renderer** (`LyricsView.kt`):
- `LazyColumn` of `LrcLine`s. Each line is a `Text` composable with click handler `onSeek(line.timestampMs)`.
- **Current line index** = the largest `i` such that `lines[i].timestampMs <= currentPositionMs`. Recomputed via `derivedStateOf` from a position state passed as parameter (the viewModel exposes `currentPositionMs: StateFlow<Long>` polled at ~250ms cadence from the existing player controller).
- **Styling:** current line = `MaterialTheme.typography.headlineMedium`, alpha 1.0, semibold; surrounding lines = `bodyLarge`, alpha 0.45. The exact dim factor and font size are styling polish; the spec only requires that the current line is visually emphasised vs. the rest.
- **Auto-scroll:** a `LaunchedEffect(currentLineIndex)` calls `listState.animateScrollToItem(currentLineIndex, scrollOffset = <centering>)` whenever the current line changes.
- **User-scroll guard:** when `listState.isScrollInProgress` becomes true via a user gesture, auto-scroll suspends. A `LaunchedEffect` resets the suspend after 5 seconds of idle, then re-centers on the current line. Implementation: track `lastUserScrollAtMs` via `interactionSource.collectIsDraggedAsState()`; gate the auto-scroll `LaunchedEffect` on `now - lastUserScrollAtMs > 5_000`.

**Plain renderer:** a single scrollable `Text(text)`. No tap-to-seek (no timestamps). No auto-scroll.

**Loading state:** centered `CircularProgressIndicator` + "Fetching lyrics…" text. Sheet height stays at ~95% — no jump when the result arrives.

**Instrumental state:** centered "♪ Instrumental" placard, headline-medium typography, no actions.

**None state:** centered "No lyrics found" + a single `OutlinedButton("Retry")`. Tap calls `onRetry()` which enqueues a fresh priority `LyricsFetchWorker` (`ExistingWorkPolicy.REPLACE`) and transitions back to `Loading`.

**Error state:** rendered only if `viewModel` surfaces an explicit transient-error signal (network down, etc.). Same UI as None.

### 9. Home banner

Mirror v0.9.35's `MetadataBackfillBannerState` exactly.

#### 9.1 `LyricsBackfillBannerState`

Location: `feature/home/src/main/kotlin/com/stash/feature/home/banner/LyricsBackfillBannerState.kt`

```kotlin
sealed interface LyricsBackfillBannerState {
    object Hidden : LyricsBackfillBannerState
    data class Running(val processed: Int, val total: Int) : LyricsBackfillBannerState
    data class Finished(val total: Int) : LyricsBackfillBannerState
}

internal fun lyricsBackfillBannerStateFor(snapshot: LyricsBackfillSnapshot): LyricsBackfillBannerState = when {
    snapshot.state == State.RUNNING && snapshot.total > 0 ->
        LyricsBackfillBannerState.Running(snapshot.processed, snapshot.total)
    snapshot.state == State.FINISHED && snapshot.total > 0 ->
        LyricsBackfillBannerState.Finished(snapshot.total)
    else -> LyricsBackfillBannerState.Hidden
}
```

Pure mapper — covered by unit tests with the same DataStore-isolation Robolectric pattern as v0.9.35.

#### 9.2 `HomeUiState` field

Add a sibling field to `metadataBackfillBanner`:

```kotlin
val lyricsBackfillBanner: LyricsBackfillBannerState = LyricsBackfillBannerState.Hidden,
```

`HomeViewModel` collects `lyricsBackfillState.snapshot` and maps via `lyricsBackfillBannerStateFor`. The banner Composable in `HomeScreen.kt` renders it in source-order next to the existing metadata banner.

#### 9.3 `LyricsBackfillBanner` Composable

Location: `feature/home/src/main/kotlin/com/stash/feature/home/banner/LyricsBackfillBanner.kt`

Visual identical to `MetadataBackfillBanner` (GlassCard wrapper, progress indicator on `Running`, two-second "Done" pulse on `Finished`, dismiss on tap of pulse). Copy is "Fetching lyrics… (X / Y)" while running and "Lyrics fetched for X tracks" on finish.

**Concurrent-backfill decision (v0.9.34 → v0.9.36 upgraders).** A user skipping v0.9.35 will see both `MetadataBackfillWorker` and `LyricsBackfillWorker` enqueued on first launch. The two backfills run **concurrently**, not serially. Both are `NetworkType.CONNECTED` + `RUN_AS_NON_EXPEDITED_WORK_REQUEST`; WorkManager schedules them cooperatively, and the LRCLIB / album-art fetch shapes are both small JSON / small JPEG — network contention is negligible compared to user-visible latency from serialising. Both Home banners render simultaneously; both are short-lived. The "is everything still running?" UX cost of two banners beats the "lyrics didn't appear for 20 minutes" UX cost of serialising. No cross-worker dependency wired.

### 10. Error handling

| Failure | Where | Behaviour |
|---|---|---|
| LRCLIB transient 5xx / network | `LrclibLyricsSource.resolve` | Return null; `LyricsRepository` walks next source (InnerTube). If that also fails, `LyricsFetchWorker` `Result.retry()` up to 3 attempts; eventually leaves NULL stamp so backfill retries on next version. |
| LRCLIB miss + InnerTube miss (no `youtubeVideoId` or browse returns nothing) | `LyricsRepository.resolveAndStore` | Stamp 0L. No `lyrics` row. Sheet shows None state with Retry button. |
| LRCLIB returns `instrumental: true` | `LrclibLyricsSource` | Build `LyricsResult(instrumental = true, plainText = null, syncedLrc = null)`. `LyricsRepository` writes a row with `instrumental = 1` and stamps `clock.now()`. Sheet shows Instrumental state. No sidecar written. |
| Malformed LRC body | `LrcParser.parse` | Skips bad lines. If zero valid lines come out, the renderer falls back to plain-text rendering using the row's `plainText` (which LRCLIB usually populates alongside `syncedLrc`) — this is the `plainFallback` field on `LyricsViewState.Synced`. If `plainText` is also empty, `LyricsViewState` is `None` (a parser-fail without plain fallback is functionally a miss). |
| Sidecar write fails (permissions, full disk, SAF revoked) | `LyricsSidecarWriter.write` | Log warn with track id and path. **Do not** unwind the Room write — Stash's in-app rendering is unaffected. |
| Track deleted while fetch in flight | `LyricsFetchWorker.doWork` | Initial `trackDao.get(trackId) == null` check returns `Result.success()` immediately; FK CASCADE handles any race-window lyrics row written in `resolveAndStore`. |
| `seekTo` called when no active media session | `NowPlayingViewModel.onSeek` | Try/catch around player call; log warn; no-op. Sheet remains open. |
| DataStore corruption | `LyricsBackfillState` | Defer to v0.9.35's existing handling — `runBlocking { context.lyricsBackfillDataStore.edit { it.clear() } }` recovery in `@Before` for tests; production DataStore exceptions surface as worker `Result.retry()`. |

### 11. Testing

#### 11.1 Unit tests

- `LrcParserTest` (in `:data:lyrics:test`) — happy path, malformed lines, metadata tags stripped, multi-timestamp expansion, sort order, empty input.
- `LrclibLyricsSourceTest` — MockWebServer; verify duration-tolerance ladder (exact → ±2 → ±5 → search → null), User-Agent header, JSON decoding, instrumental flag preserved.
- `YtMusicLyricsSourceTest` — verify null `youtubeVideoId` short-circuits without HTTP; verify `MPLY` browseId path produces non-null `LyricsResult`.
- `LyricsRepositoryTest` — Fakes for sources, DAOs, sidecar writer, clock. Verify: success path writes row + stamps fetchedAt + invokes sidecar; miss path stamps 0L + no row + no sidecar; instrumental path writes row + stamps fetchedAt + does NOT invoke sidecar; sidecar failure does not unwind Room write.
- `LyricsBackfillBannerStateTest` — pure mapper; mirror `MetadataBackfillBannerStateTest` exactly.

#### 11.2 Integration tests (Robolectric)

- `LyricsBackfillWorkerTest` — in-memory Room + DataStore isolation in `@Before` / `@After`. Verify: empty library returns success; populated library drains exactly once; rows stamped 0L on infra failure; banner snapshot transitions IDLE → RUNNING → FINISHED.
- `LyricsFetchWorkerTest` — verify post-download enqueue + priority enqueue both reach `resolveAndStore`; `Result.retry` on transient failure up to MAX_ATTEMPTS, then `Result.success` (leaves NULL).

#### 11.3 On-device validation

Mirror the v0.9.35 validation rubric. Pixel 6 Pro post-merge:

1. **25-track Spotify sync** (same playlist that validated v0.9.35).
2. Open Now Playing for **Kanye - Off The Grid**: tap Lyrics icon. Verify: sheet opens, lyrics fetch, synced rendering with current line emphasized, auto-scroll tracks playback, tap any line seeks to that timestamp.
3. Open Now Playing for **Vince Guaraldi Trio - Linus And Lucy**: tap Lyrics. Verify: "♪ Instrumental" placard (LRCLIB knows this track is instrumental).
4. Pick a fresh-synced obscure track with no LRCLIB hit: tap Lyrics. Verify: spinner → "No lyrics found" + Retry button. Tap Retry, verify spinner returns then settles.
5. Verify sidecar files: `adb shell ls /sdcard/Android/data/com.stash/files/music/...` shows `Off The Grid.lrc` next to `Off The Grid.opus`. `adb shell cat` the sidecar; verify LRC header tags + body.
6. **Reboot mid-backfill:** start the backfill on a fresh upgrade, force-stop the app at ~30% progress, relaunch. Verify backfill resumes (OFFSET=0 + stamp-removes-from-result-set pattern handles this for free).
7. **SAF mode:** if SAF is the user's storage choice, run a single download and verify `.lrc` sidecar lands in the SAF tree under the same artist/album folder structure as the audio file.
8. **Track change while sheet open:** open the sheet on track A; let track A finish; verify sheet's lyrics swap to track B as B starts. No flash, no close.

Zero `LyricsRepository` / `LyricsFetchWorker` / `LrcParser` warnings in logcat across the test session is the bar for "clean."

## Phase 1 task sequence

Tasks are designed to be runnable as independent subagents under `superpowers:subagent-driven-development`. Dependencies are explicit so the launcher can parallelise where safe.

| # | Task | Depends on | Parallel-safe with |
|---|---|---|---|
| T1 | Room v27 → v28: TrackEntity column, LyricsEntity, MIGRATION_27_28, LyricsDao, TrackDao additions, schema JSON. Unit-test the migration via `MigrationTestHelper`. | — | T2, T3 |
| T2 | `:data:lyrics` module skeleton: Gradle config, Hilt module, `LyricsSource` interface + `LyricsQuery` + `LyricsResult`, `LrcParser` + `LrcLine` (+ tests). | — | T1, T3 |
| T3 | `BackfillVersionTracker` parameterise key: turn no-arg methods into `key: String` parameters, update metadata caller, default key constant unchanged. | — | T1, T2 |
| T4 | `LrclibLyricsSource` (with duration-tolerance ladder + User-Agent + search fallback) + tests. | T2 | T5 |
| T5 | `YtMusicLyricsSource` (reuse `:data:innertube`) + tests. | T2 | T4 |
| T6 | `LyricsRepository` orchestration: source chain walk, Room upsert, stamp, sidecar invocation. Tests with fakes. | T1, T2, T4, T5 | T7 |
| T7 | `LyricsSidecarWriter` (internal + SAF, slug exposure from FileOrganizer) + tests. | T1, T2 | T6 |
| T8 | `LyricsFetchWorker` (post-download + priority namespaces) + Robolectric tests. | T6, T7 | T9 |
| T9 | `LyricsBackfillWorker` + `LyricsBackfillState` + `LyricsBackfillScheduler` + DataStore + `StashApplication` wiring (alongside metadata scheduler call). Robolectric tests. | T3, T6 | T8 |
| T10 | `LyricsFetchTrigger` interface in `:data:download`, `:app` binding, hook into `DownloadManager.kt:288-290`, `:429-431`, and `SearchDownloadCoordinator.kt:327`. | T8 | T11 |
| T11 | `LyricsBackfillBannerState` + mapper + `LyricsBackfillBanner` Composable + `HomeUiState` field + `HomeViewModel` wiring + tests. | T9 | T10 |
| T12 | `LyricsBottomSheet` + `LyricsView` (synced + plain renderers, auto-scroll, user-scroll guard, tap-to-seek wiring, Loading / Instrumental / None / Error states) + `NowPlayingViewModel.onShowLyrics` / `.onLyricsRequested` / `.onSeek` / state derivation. | T6, T8 | T11 |
| T13 | Lyrics IconButton in `NowPlayingScreen.kt:241` TopBar (between Save and Queue) wired to `onShowLyrics`. | T12 | — |
| T14 | On-device validation pass per §11.3, log capture, follow-up issue file for any regressions. | T1–T13 | — |

T1, T2, T3 are independent and can run as the first parallel wave. T4 + T5 are the second wave. T6 / T7 / T8 / T9 form the data-layer build-out (some parallelism inside that block). T10 + T11 + T12 are independent feature surfaces. T13 then T14.

## Out of scope (Phase 2 and beyond)

- Manual lyric search / per-track edit override (the "wrong lyrics?" surface).
- Sidecar **read** (user-dropped `.lrc` files override Stash's fetch).
- KuGou source / CJK coverage gap.
- Word-level highlighting (sync below the line level).
- AI translation (SimpMusic-style).
- Karaoke / big-text immersive mode.
- USLT / SYLT / LYRICS tag embedded in the audio file.
- Lyrics share / export (right now external apps read the sidecar; no in-app share sheet).
- LRCLIB community contribution UX (Stash never submits back to LRCLIB).
