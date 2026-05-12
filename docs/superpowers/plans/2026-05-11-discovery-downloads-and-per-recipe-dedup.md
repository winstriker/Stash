# Discovery Downloads + Per-Recipe Dedup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make per-recipe mix refreshes respect other mixes' contents, and make Last.fm-discovered tracks actually download (so the mix isn't "39 tracks but only 4 playable").

**Architecture:** Three components — (1) `StashMixRefreshWorker` pre-populates `excludeIds` from other mixes' playlist contents when invoked with a single `KEY_RECIPE_ID`; (2) new `DiscoveryDownloadWorker` drains `download_queue WHERE sync_id IS NULL` via the existing `TrackDownloader` abstraction, chained from `StashDiscoveryWorker`'s tail with a final mix-refresh chained on; (3) `getDoneTrackIdsForRecipe` adds `AND t.is_downloaded = 1` as defense-in-depth. Sync feeders gain `AND dq.sync_id IS NOT NULL` so the partition between sync-chain and discovery-chain workers is real.

**Tech Stack:** Kotlin, Room, WorkManager, Hilt, MockK + JUnit4 for unit tests, Robolectric + Room in-memory for DAO behavior tests.

**Spec:** `docs/superpowers/specs/2026-05-11-discovery-downloads-and-per-recipe-dedup-design.md` (committed at `d919a43`).

---

## Pre-flight notes for the implementer

Worktree: `C:\Users\theno\Projects\MP3APK\.worktrees\first-listen-tag-fallback`. Branch: `feat/first-listen-tag-fallback`. PR 3 already shipped 6 commits on this branch (tip is `207c93a feat(mix): version-gated retune migration for the recipe pivot` followed by docs/plan commits). PR 4 lands on top.

### Repository conventions

1. **TDD with bite-sized commits.** One commit per task; tests pass before each commit.
2. **MockK for unit tests over collaborators** (`MixGenerator`, `StashMixRefreshWorker`, `DiscoveryDownloadWorker`). **Robolectric + Room in-memory for DAO behavior tests**. Match existing precedents:
   - DAO test: `core/data/src/test/kotlin/com/stash/core/data/db/dao/DiscoveryQueueDaoCapTest.kt`
   - Worker test (MockK with manual construction): `data/download/src/test/kotlin/com/stash/data/download/lossless/LosslessRetryWorkerTest.kt` and `core/data/src/test/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorkerDedupTest.kt`
3. **`ORDER BY created_at ASC` for queue queries** — matches the existing TrackDownloadWorker feeder queries (`getAllPendingBySources`, `getRetryableBySources`).
4. **No push, no tag.** Implementation ends at APK installed on Pixel + manual verification.
5. **Always `:app:installDebug` after a fix.** Per memory `feedback_install_after_fix.md`.

### Files you will touch

| Path | What changes |
|---|---|
| `core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt` | Add `getTrackIdsForPlaylists(playlistIds: List<Long>): List<Long>`. |
| `core/data/src/test/kotlin/com/stash/core/data/db/dao/PlaylistDaoOtherMixTracksTest.kt` | **CREATE** — Robolectric test for the new query. |
| `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt` | `doWork`: when `targetId > 0L`, pre-populate `excludeIds` from other mixes' current playlist contents. |
| `core/data/src/test/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorkerPerRecipeDedupTest.kt` | **CREATE** — MockK test for the new behavior. |
| `core/data/src/main/kotlin/com/stash/core/data/db/dao/DownloadQueueDao.kt` | (a) Add `AND dq.sync_id IS NOT NULL` to `getAllPendingBySources` (lines 76-95) and `getRetryableBySources` (lines 105-124). (b) Add new `pendingDiscoveryDownloads(): List<DownloadQueueEntity>` query. |
| `core/data/src/test/kotlin/com/stash/core/data/db/dao/DownloadQueueDaoPartitionTest.kt` | **CREATE** — Robolectric test for the partition + new query. |
| `data/download/src/main/kotlin/com/stash/data/download/DiscoveryDownloadWorker.kt` | **CREATE** — the new worker. |
| `data/download/src/test/kotlin/com/stash/data/download/DiscoveryDownloadWorkerTest.kt` | **CREATE** — MockK test for the new worker. |
| `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashDiscoveryWorker.kt` | Chain `DiscoveryDownloadWorker.enqueueOneTime(...)` at the end of `doWork()`. |
| `core/data/src/main/kotlin/com/stash/core/data/db/dao/DiscoveryQueueDao.kt` | Extend `getDoneTrackIdsForRecipe` query with `AND t.is_downloaded = 1`. |
| `core/data/src/test/kotlin/com/stash/core/data/db/dao/DiscoveryQueueDaoCapTest.kt` | Update `trackEntity` helper to default `isDownloaded = true`; add new "stub excluded" test. |

### Reference precedents you should re-read before starting

- `LosslessRetryWorker.kt` (`data/download/.../lossless/`) — the MockK-with-hand-constructed-worker test precedent.
- `TrackDownloadWorker.kt` lines 240-385 — the per-track flow `DiscoveryDownloadWorker` mirrors (blocklist guard → `trackDownloader.downloadTrack` → outcome handling).
- `TrackDownloadWorker.kt` lines 497-515 — the `createForegroundInfo` helper pattern `DiscoveryDownloadWorker` duplicates inline.
- `SyncNotificationManager.kt` lines 33, 141-146 — confirm `NOTIFICATION_ID_PROGRESS = 9001` and `buildProgressNotification` signature before using.

---

## Task 1: Per-recipe dedup (PlaylistDao + StashMixRefreshWorker)

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt`
- Create: `core/data/src/test/kotlin/com/stash/core/data/db/dao/PlaylistDaoOtherMixTracksTest.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt`
- Create: `core/data/src/test/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorkerPerRecipeDedupTest.kt`

### Step 1: Add the new DAO query

In `PlaylistDao.kt`, add (location: alongside the other track-id queries; if no obvious clustering, add at the end of the interface):

```kotlin
/**
 * Returns DISTINCT track ids that appear in any of the given playlists.
 * Used by [com.stash.core.data.sync.workers.StashMixRefreshWorker]'s
 * single-recipe refresh path to seed `excludeIds` from the user's
 * currently-materialized OTHER mixes — without this, a manual refresh
 * of one mix sees an empty exclude set and naturally produces overlap
 * with the others (the very symptom PR 3's batch-mode dedup was meant
 * to fix).
 */
@Query("SELECT DISTINCT track_id FROM playlist_tracks WHERE playlist_id IN (:playlistIds)")
suspend fun getTrackIdsForPlaylists(playlistIds: List<Long>): List<Long>
```

### Step 2: Write the failing DAO test

Create `PlaylistDaoOtherMixTracksTest.kt`:

```kotlin
package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaylistDaoOtherMixTracksTest {

    private lateinit var db: StashDatabase
    private lateinit var playlistDao: PlaylistDao
    private lateinit var trackDao: TrackDao

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        playlistDao = db.playlistDao()
        trackDao = db.trackDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `returns DISTINCT track ids across multiple playlists`() = runTest {
        // Seed 3 tracks
        trackDao.insert(track(1L))
        trackDao.insert(track(2L))
        trackDao.insert(track(3L))

        // Two playlists: A = [1, 2], B = [2, 3] (track 2 in both)
        val playlistA = playlistDao.insert(stashMixPlaylist(name = "Mix A"))
        val playlistB = playlistDao.insert(stashMixPlaylist(name = "Mix B"))
        playlistDao.insertCrossRef(crossRef(playlistA, trackId = 1L, position = 0))
        playlistDao.insertCrossRef(crossRef(playlistA, trackId = 2L, position = 1))
        playlistDao.insertCrossRef(crossRef(playlistB, trackId = 2L, position = 0))
        playlistDao.insertCrossRef(crossRef(playlistB, trackId = 3L, position = 1))

        val result = playlistDao.getTrackIdsForPlaylists(listOf(playlistA, playlistB))

        assertEquals(setOf(1L, 2L, 3L), result.toSet())
        assertEquals("expected DISTINCT — track 2 once", 3, result.size)
    }

    @Test fun `empty input returns empty list`() = runTest {
        val result = playlistDao.getTrackIdsForPlaylists(emptyList())
        assertTrue("expected empty, got $result", result.isEmpty())
    }

    @Test fun `filters by the given playlist ids — tracks in OTHER playlists are not returned`() = runTest {
        trackDao.insert(track(1L))
        trackDao.insert(track(2L))

        val included = playlistDao.insert(stashMixPlaylist(name = "Included"))
        val excluded = playlistDao.insert(stashMixPlaylist(name = "Excluded"))
        playlistDao.insertCrossRef(crossRef(included, trackId = 1L, position = 0))
        playlistDao.insertCrossRef(crossRef(excluded, trackId = 2L, position = 0))

        val result = playlistDao.getTrackIdsForPlaylists(listOf(included))

        assertEquals(listOf(1L), result)
    }

    private fun track(id: Long) = TrackEntity(
        id = id,
        title = "Track $id",
        artist = "Artist $id",
        canonicalTitle = "track $id",
        canonicalArtist = "artist $id",
        isDownloaded = true,
    )

    private fun stashMixPlaylist(name: String) = PlaylistEntity(
        name = name,
        source = MusicSource.BOTH,
        sourceId = "stash_mix_$name",
        type = PlaylistType.STASH_MIX,
        trackCount = 0,
        syncEnabled = true,
        isActive = true,
    )

    private fun crossRef(playlistId: Long, trackId: Long, position: Int) =
        PlaylistTrackCrossRef(
            playlistId = playlistId,
            trackId = trackId,
            position = position,
            addedAt = Instant.EPOCH,
        )
}
```

### Step 3: Run the DAO test — expect compile error then pass

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.dao.PlaylistDaoOtherMixTracksTest"
```

Expected: compile error if you haven't added the query yet; or 3/3 pass after Step 1.

### Step 4: Read the current StashMixRefreshWorker.doWork iteration

Open `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt`. After PR 3's `5538a66`, `doWork` around lines 219-225 looks like (lines may have drifted slightly — find by the `val orderedRecipes = active.sortedBy { recipeDedupPriority(it) }` declaration):

```kotlin
val orderedRecipes = active.sortedBy { recipeDedupPriority(it) }
val excludeIds = mutableSetOf<Long>()
for (recipe in orderedRecipes) {
    val excludeSnapshot = excludeIds.toSet()
    val tracks = mixGenerator.generate(recipe, excludeSnapshot)
    // ... rest unchanged
}
```

`targetId` is already declared just above at lines 179-180 (or wherever it currently is — find the `val targetId = inputData.getLong(KEY_RECIPE_ID, -1L)` line). We reuse it.

### Step 5: Write the failing worker test

Create `core/data/src/test/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorkerPerRecipeDedupTest.kt`:

```kotlin
package com.stash.core.data.sync.workers

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.lastfm.LastFmSessionPreference
import com.stash.core.data.mix.MixGenerator
import com.stash.core.data.mix.MixSeedGenerator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MockK end-to-end test for [StashMixRefreshWorker]'s v0.9.20 single-
 * recipe dedup. When `KEY_RECIPE_ID` is set, the worker pre-populates
 * `excludeIds` from the OTHER builtin mixes' current playlist contents
 * so a manual long-press refresh doesn't produce overlap with the
 * other mixes.
 */
class StashMixRefreshWorkerPerRecipeDedupTest {

    private val appContext: Context = mockk(relaxed = true)
    private val recipeDao: StashMixRecipeDao = mockk(relaxed = true)
    private val playlistDao: PlaylistDao = mockk(relaxed = true)
    private val discoveryQueueDao: DiscoveryQueueDao = mockk(relaxed = true)
    private val listeningEventDao: ListeningEventDao = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val mixGenerator: MixGenerator = mockk()
    private val seedGenerator: MixSeedGenerator = mockk(relaxed = true)
    private val lastFmApiClient: LastFmApiClient = mockk(relaxed = true)
    private val lastFmCredentials: LastFmCredentials = mockk {
        coEvery { isConfigured } returns false
    }
    private val sessionPreference: LastFmSessionPreference = mockk {
        coEvery { session } returns flowOf(null)
    }
    private val blocklistGuard: BlocklistGuard = mockk {
        coEvery { isBlockedByTrackId(any()) } returns false
    }

    private fun newWorker(recipeId: Long): StashMixRefreshWorker {
        val params: WorkerParameters = mockk(relaxed = true) {
            coEvery { inputData } returns workDataOf(
                StashMixRefreshWorker.KEY_RECIPE_ID to recipeId,
            )
        }
        return StashMixRefreshWorker(
            appContext, params,
            recipeDao, playlistDao, discoveryQueueDao, listeningEventDao,
            trackDao, mixGenerator, seedGenerator, lastFmApiClient,
            lastFmCredentials, sessionPreference, blocklistGuard,
        )
    }

    @Test fun `single-recipe path pre-populates excludeIds from other mixes' playlist contents`() = runTest {
        val targetRecipe = recipe(id = 1L, name = "Daily Discover", playlistId = 100L)
        val otherRecipe1 = recipe(id = 2L, name = "Deep Cuts", playlistId = 200L)
        val otherRecipe2 = recipe(id = 3L, name = "First Listen", playlistId = 300L)

        coEvery { recipeDao.getById(1L) } returns targetRecipe
        coEvery { recipeDao.getActive() } returns listOf(targetRecipe, otherRecipe1, otherRecipe2)
        coEvery {
            playlistDao.getTrackIdsForPlaylists(match { it.toSet() == setOf(200L, 300L) })
        } returns listOf(50L, 51L, 52L)
        coEvery { playlistDao.getById(any()) } returns null
        coEvery { playlistDao.insert(any()) } returns 999L

        val excludeCapture = slot<Set<Long>>()
        coEvery { mixGenerator.generate(targetRecipe, capture(excludeCapture)) } returns emptyList()
        coEvery {
            discoveryQueueDao.getDoneTrackIdsForRecipe(any(), any())
        } returns emptyList()

        newWorker(recipeId = 1L).doWork()

        assertEquals(
            "single-recipe path must pre-populate excludeIds from other mixes",
            setOf(50L, 51L, 52L),
            excludeCapture.captured,
        )
    }

    @Test fun `single-recipe path skips lookup when no other mixes have playlist ids`() = runTest {
        val targetRecipe = recipe(id = 1L, name = "Daily Discover", playlistId = 100L)
        val otherRecipe = recipe(id = 2L, name = "Deep Cuts", playlistId = null)  // not yet materialized

        coEvery { recipeDao.getById(1L) } returns targetRecipe
        coEvery { recipeDao.getActive() } returns listOf(targetRecipe, otherRecipe)
        coEvery { playlistDao.getById(any()) } returns null
        coEvery { playlistDao.insert(any()) } returns 999L

        val excludeCapture = slot<Set<Long>>()
        coEvery { mixGenerator.generate(targetRecipe, capture(excludeCapture)) } returns emptyList()
        coEvery {
            discoveryQueueDao.getDoneTrackIdsForRecipe(any(), any())
        } returns emptyList()

        newWorker(recipeId = 1L).doWork()

        assertTrue("expected empty excludeIds when no other mixes have playlists", excludeCapture.captured.isEmpty())
        coVerify(exactly = 0) { playlistDao.getTrackIdsForPlaylists(any()) }
    }

    private fun recipe(id: Long, name: String, playlistId: Long?) = StashMixRecipeEntity(
        id = id,
        name = name,
        discoveryRatio = 0.85f,
        targetLength = 40,
        playlistId = playlistId,
        isBuiltin = true,
        isActive = true,
    )
}
```

### Step 6: Run the worker test — expect failure

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.workers.StashMixRefreshWorkerPerRecipeDedupTest"
```

Expected: tests fail. The first assertion fails because `excludeIds` starts empty (the current code doesn't seed it from other mixes).

### Step 7: Implement the production change

In `StashMixRefreshWorker.kt`, find the iteration block (after PR 3 it starts with `val orderedRecipes = active.sortedBy { recipeDedupPriority(it) }`). Add the per-recipe seed BEFORE the loop:

```kotlin
val orderedRecipes = active.sortedBy { recipeDedupPriority(it) }
val excludeIds = mutableSetOf<Long>()

// v0.9.20 follow-up: single-recipe path needs explicit seeding from the
// OTHER mixes' current playlist contents. The batch-mode loop accumulates
// excludeIds naturally as it iterates; the single-element loop has nothing
// to accumulate, so we seed it manually from the materialized state of the
// other builtin mixes. Effect: manual refresh of one mix no longer overlaps
// with whatever is currently in the others.
if (targetId > 0L) {
    val otherPlaylistIds = recipeDao.getActive()
        .filter { it.id != targetId && it.playlistId != null }
        .mapNotNull { it.playlistId }
    if (otherPlaylistIds.isNotEmpty()) {
        excludeIds += playlistDao.getTrackIdsForPlaylists(otherPlaylistIds)
    }
}

for (recipe in orderedRecipes) {
    val excludeSnapshot = excludeIds.toSet()
    val tracks = mixGenerator.generate(recipe, excludeSnapshot)
    // ... rest unchanged
}
```

### Step 8: Run both tests — expect pass

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.dao.PlaylistDaoOtherMixTracksTest"
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.workers.StashMixRefreshWorkerPerRecipeDedupTest"
```

Expected: 3/3 + 2/2 pass.

### Step 9: Run the full :core:data module test sweep

```bash
./gradlew :core:data:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. The existing `StashMixRefreshWorkerDedupTest` (from PR 3) must still pass — it tests the batch-mode path which is unchanged.

### Step 10: Commit

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt \
        core/data/src/test/kotlin/com/stash/core/data/db/dao/PlaylistDaoOtherMixTracksTest.kt \
        core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt \
        core/data/src/test/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorkerPerRecipeDedupTest.kt
git commit -m "feat(mix): per-recipe refresh pre-populates excludeIds from other mixes

PR 3's cross-mix dedup only fired in batch mode (no KEY_RECIPE_ID).
The single-recipe path that fires on manual long-press refresh had
nothing to accumulate, so each mix's refresh was effectively
unaware of the others — producing overlap ('Hold it Down' by
Jerreau topped both Daily Discover and Deep Cuts on the user's
device). Now: when the worker is invoked with a target recipe id,
it seeds excludeIds from the materialized state of the OTHER
builtin mixes via a new PlaylistDao query.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Partition predicate on sync feeders + new `pendingDiscoveryDownloads` query

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/DownloadQueueDao.kt`
- Create: `core/data/src/test/kotlin/com/stash/core/data/db/dao/DownloadQueueDaoPartitionTest.kt`

### Step 1: Read the existing feeders

Open `DownloadQueueDao.kt`. Confirm:
- `getAllPendingBySources` at lines 76-95.
- `getRetryableBySources` at lines 105-124.

Both filter by `t.source IN (:sources)` + EXISTS-clause on sync_enabled playlists. Neither filters by `sync_id` — adding the predicate is what makes the partition real.

### Step 2: Write the failing test

Create `DownloadQueueDaoPartitionTest.kt`:

```kotlin
package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DownloadQueueDaoPartitionTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: DownloadQueueDao
    private lateinit var trackDao: TrackDao
    private lateinit var playlistDao: PlaylistDao
    private lateinit var syncHistoryDao: SyncHistoryDao

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.downloadQueueDao()
        trackDao = db.trackDao()
        playlistDao = db.playlistDao()
        syncHistoryDao = db.syncHistoryDao()

        // DownloadQueueEntity has FK sync_id → sync_history.id (Room enables
        // FK enforcement by default). Seed a single sync_history row so the
        // sync-side test fixtures can reference syncId = 5L without crashing.
        syncHistoryDao.insert(SyncHistoryEntity(id = 5L))
    }

    @After fun tearDown() { db.close() }

    @Test fun `getAllPendingBySources excludes rows with sync_id IS NULL`() = runTest {
        seedTrackInSyncEnabledPlaylist(trackId = 1L, source = MusicSource.YOUTUBE)
        seedTrackInSyncEnabledPlaylist(trackId = 2L, source = MusicSource.YOUTUBE)

        // Two PENDING rows for the same source: one with sync_id (sync), one without (discovery).
        dao.insert(pendingRow(id = 100L, trackId = 1L, syncId = 5L))    // sync row
        dao.insert(pendingRow(id = 101L, trackId = 2L, syncId = null))   // discovery row

        val result = dao.getAllPendingBySources(listOf("YOUTUBE"))

        assertEquals("expected only sync row", listOf(100L), result.map { it.id })
    }

    @Test fun `getRetryableBySources excludes rows with sync_id IS NULL`() = runTest {
        seedTrackInSyncEnabledPlaylist(trackId = 1L, source = MusicSource.YOUTUBE)
        seedTrackInSyncEnabledPlaylist(trackId = 2L, source = MusicSource.YOUTUBE)

        dao.insert(failedRow(id = 100L, trackId = 1L, syncId = 5L, retryCount = 1))     // sync row
        dao.insert(failedRow(id = 101L, trackId = 2L, syncId = null, retryCount = 1))   // discovery row

        val result = dao.getRetryableBySources(listOf("YOUTUBE"))

        assertEquals("expected only sync row", listOf(100L), result.map { it.id })
    }

    @Test fun `pendingDiscoveryDownloads returns only sync_id IS NULL rows in PENDING or retryable FAILED`() = runTest {
        seedTrack(trackId = 1L)
        seedTrack(trackId = 2L)
        seedTrack(trackId = 3L)
        seedTrack(trackId = 4L)

        dao.insert(pendingRow(id = 100L, trackId = 1L, syncId = 5L))            // sync — excluded
        dao.insert(pendingRow(id = 101L, trackId = 2L, syncId = null))          // discovery PENDING — included
        dao.insert(failedRow(id = 102L, trackId = 3L, syncId = null, retryCount = 1))  // discovery retry — included
        dao.insert(failedRow(id = 103L, trackId = 4L, syncId = null, retryCount = 3))  // discovery exhausted — excluded

        val result = dao.pendingDiscoveryDownloads()

        assertEquals(setOf(101L, 102L), result.map { it.id }.toSet())
    }

    @Test fun `pendingDiscoveryDownloads excludes WAITING_FOR_LOSSLESS and IN_PROGRESS`() = runTest {
        seedTrack(trackId = 1L)
        seedTrack(trackId = 2L)

        dao.insert(
            DownloadQueueEntity(
                id = 100L,
                trackId = 1L,
                status = DownloadStatus.WAITING_FOR_LOSSLESS,
                syncId = null,
                searchQuery = "q",
            )
        )
        dao.insert(
            DownloadQueueEntity(
                id = 101L,
                trackId = 2L,
                status = DownloadStatus.IN_PROGRESS,
                syncId = null,
                searchQuery = "q",
            )
        )

        val result = dao.pendingDiscoveryDownloads()

        assertTrue("expected empty, got $result", result.isEmpty())
    }

    // ---- helpers ----

    private suspend fun seedTrack(trackId: Long) {
        trackDao.insert(
            TrackEntity(
                id = trackId,
                title = "Track $trackId",
                artist = "Artist $trackId",
                canonicalTitle = "track $trackId",
                canonicalArtist = "artist $trackId",
                source = MusicSource.YOUTUBE,
                isDownloaded = false,
            )
        )
    }

    private suspend fun seedTrackInSyncEnabledPlaylist(trackId: Long, source: MusicSource) {
        trackDao.insert(
            TrackEntity(
                id = trackId,
                title = "Track $trackId",
                artist = "Artist $trackId",
                canonicalTitle = "track $trackId",
                canonicalArtist = "artist $trackId",
                source = source,
                isDownloaded = false,
            )
        )
        val playlistId = playlistDao.insert(
            PlaylistEntity(
                name = "Test playlist",
                source = MusicSource.BOTH,
                sourceId = "test_playlist_$trackId",
                type = PlaylistType.STASH_MIX,
                trackCount = 0,
                syncEnabled = true,
                isActive = true,
            )
        )
        playlistDao.insertCrossRef(
            PlaylistTrackCrossRef(
                playlistId = playlistId,
                trackId = trackId,
                position = 0,
                addedAt = Instant.EPOCH,
            )
        )
    }

    private fun pendingRow(id: Long, trackId: Long, syncId: Long?) = DownloadQueueEntity(
        id = id,
        trackId = trackId,
        status = DownloadStatus.PENDING,
        syncId = syncId,
        searchQuery = "artist - title",
    )

    private fun failedRow(id: Long, trackId: Long, syncId: Long?, retryCount: Int) = DownloadQueueEntity(
        id = id,
        trackId = trackId,
        status = DownloadStatus.FAILED,
        syncId = syncId,
        searchQuery = "artist - title",
        retryCount = retryCount,
    )
}
```

### Step 3: Run tests — expect compile error then partial fail

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.dao.DownloadQueueDaoPartitionTest"
```

Expected: compile error (no `pendingDiscoveryDownloads` method); after Step 4, the two partition tests fail because the predicate isn't added yet.

### Step 4: Implement the production changes

In `DownloadQueueDao.kt`, modify `getAllPendingBySources` (around lines 76-95). The current WHERE clause is:

```kotlin
WHERE dq.status = 'PENDING'
  AND t.source IN (:sources)
  AND bl.canonical_key IS NULL
  AND EXISTS (...)
```

Add the predicate:

```kotlin
WHERE dq.status = 'PENDING'
  AND dq.sync_id IS NOT NULL
  AND t.source IN (:sources)
  AND bl.canonical_key IS NULL
  AND EXISTS (...)
```

Same for `getRetryableBySources` (around lines 105-124). The current WHERE clause:

```kotlin
WHERE dq.status = 'FAILED' AND dq.retry_count < 3
  AND t.source IN (:sources)
  ...
```

Add the predicate:

```kotlin
WHERE dq.status = 'FAILED' AND dq.retry_count < 3
  AND dq.sync_id IS NOT NULL
  AND t.source IN (:sources)
  ...
```

Append the new query at the end of the existing query block (find a sensible spot — alongside the other status-based queries):

```kotlin
/**
 * Discovery rows queued for download — `sync_id IS NULL` partitions
 * them away from sync-chain [TrackDownloadWorker] (which after the
 * v0.9.20 predicate update only touches rows with non-null sync_id).
 *
 * Includes FAILED rows with retry_count < 3 so a transient network
 * blip doesn't permanently sideline a discovery candidate — matches
 * the retry posture of [getRetryableBySources].
 *
 * Filtered to exclude WAITING_FOR_LOSSLESS (owned by LosslessRetryWorker)
 * and IN_PROGRESS / COMPLETED (already running or done).
 */
@Query(
    """
    SELECT * FROM download_queue
    WHERE sync_id IS NULL
      AND (status = 'PENDING' OR (status = 'FAILED' AND retry_count < 3))
    ORDER BY created_at ASC
    """
)
suspend fun pendingDiscoveryDownloads(): List<DownloadQueueEntity>
```

### Step 5: Run tests — expect 4/4 pass

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.dao.DownloadQueueDaoPartitionTest"
```

Expected: 4/4 pass.

### Step 6: Run the full :core:data module test sweep

```bash
./gradlew :core:data:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. **Watch for regressions**: existing tests that exercise `getAllPendingBySources` / `getRetryableBySources` may have relied on the pre-PR-4 behavior. If any fail, investigate — likely fix is updating the test fixture to seed `syncId != null`.

### Step 7: Commit

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/DownloadQueueDao.kt \
        core/data/src/test/kotlin/com/stash/core/data/db/dao/DownloadQueueDaoPartitionTest.kt
git commit -m "feat(download): partition sync vs discovery download_queue rows

TrackDownloadWorker's feeders (getAllPendingBySources,
getRetryableBySources) gain AND dq.sync_id IS NOT NULL. Adds new
pendingDiscoveryDownloads query (sync_id IS NULL, PENDING + retryable
FAILED, ORDER BY created_at ASC) for the upcoming
DiscoveryDownloadWorker. Without this partition, the sync chain
silently drained discovery rows when the user happened to sync —
which is why the user has been seeing some-but-not-all discovery
tracks downloaded.

Sync behavior on its own rows is unchanged.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: `DiscoveryDownloadWorker`

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/DiscoveryDownloadWorker.kt`
- Create: `data/download/src/test/kotlin/com/stash/data/download/DiscoveryDownloadWorkerTest.kt`

### Step 1: Verify infrastructure exists

Confirm the following symbols are reachable from `:data:download`:
- `TrackDownloader` interface at `core/data/.../sync/TrackDownloader.kt` (already used by `TrackDownloadWorker`). Verified signature: `suspend fun downloadTrack(track: Track, preResolvedUrl: String? = null): TrackDownloadOutcome`.
- `TrackDownloadOutcome` sealed class at `core/data/.../sync/` with `Success(filePath)`, `Unmatched(rejectedVideoId)`, `Failed(error)`, `Deferred` variants.
- `SyncNotificationManager` class at `core/data/.../sync/` — confirmed by source read: has `NOTIFICATION_ID_PROGRESS = 9001` (line 33) and `buildProgressNotification(title, text, progress, cancelIntent)` (line 141, `cancelIntent` defaults to null).
- `BlocklistGuard.isBlocked` — confirmed signature: `suspend fun isBlocked(artist: String, title: String, spotifyUri: String?, youtubeId: String?): Boolean`. 4-arg matches the test stub.
- `AudioDurationExtractor`, `DownloadQueueDao`, `TrackDao` — all in `:core:data`.
- `StashMixRefreshWorker.enqueueOneTime(context)` (no-arg overload) is public (verified — PR 3's spec didn't touch this).

Read `LosslessRetryWorker.kt` for the `@HiltWorker @AssistedInject` precedent — `DiscoveryDownloadWorker` follows the same pattern.

### Step 2: Write the failing worker test

Create `data/download/src/test/kotlin/com/stash/data/download/DiscoveryDownloadWorkerTest.kt`:

```kotlin
package com.stash.data.download

import android.content.Context
import androidx.work.WorkerParameters
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.core.data.sync.TrackDownloadOutcome
import com.stash.core.data.sync.TrackDownloader
import com.stash.core.model.DownloadStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MockK tests for [DiscoveryDownloadWorker] — the v0.9.20 worker that
 * drains discovery_queue PENDING/retryable-FAILED rows (sync_id IS NULL)
 * via the existing [TrackDownloader] abstraction. Mirrors the
 * [com.stash.data.download.lossless.LosslessRetryWorkerTest] MockK pattern.
 */
class DiscoveryDownloadWorkerTest {

    private val appContext: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val downloadQueueDao: DownloadQueueDao = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val trackDownloader: TrackDownloader = mockk()
    private val audioDurationExtractor: AudioDurationExtractor = mockk(relaxed = true)
    private val blocklistGuard: BlocklistGuard = mockk {
        coEvery { isBlocked(any(), any(), any(), any()) } returns false
    }
    private val syncNotificationManager: SyncNotificationManager = mockk(relaxed = true)

    private fun newWorker() = DiscoveryDownloadWorker(
        appContext, workerParams,
        downloadQueueDao, trackDao, trackDownloader,
        audioDurationExtractor, blocklistGuard, syncNotificationManager,
    )

    @Test fun `empty queue returns success and does not invoke TrackDownloader`() = runTest {
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns emptyList()

        val result = newWorker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { trackDownloader.downloadTrack(any(), any()) }
    }

    @Test fun `successful download marks COMPLETED and writes isDownloaded`() = runTest {
        val entry = entry(id = 100L, trackId = 1L)
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns listOf(entry)
        coEvery { trackDao.getById(1L) } returns stubTrack(1L)
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Success(
            filePath = "/tmp/file.flac",
        )
        coEvery { audioDurationExtractor.extract(any()) } returns null

        newWorker().doWork()

        coVerify(exactly = 1) {
            trackDao.markAsDownloaded(
                trackId = 1L,
                filePath = "/tmp/file.flac",
                fileSizeBytes = any(),
                sampleRateHz = null,
                bitsPerSample = null,
            )
        }
        coVerify(exactly = 1) {
            downloadQueueDao.updateStatus(
                id = 100L,
                status = DownloadStatus.COMPLETED,
                completedAt = any(),
            )
        }
    }

    @Test fun `unmatched outcome marks FAILED with NO_MATCH and increments retry count`() = runTest {
        val entry = entry(id = 100L, trackId = 1L)
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns listOf(entry)
        coEvery { trackDao.getById(1L) } returns stubTrack(1L)
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Unmatched(
            rejectedVideoId = "abc123",
        )

        newWorker().doWork()

        coVerify(exactly = 1) { downloadQueueDao.incrementRetryCount(100L) }
        coVerify(exactly = 1) {
            downloadQueueDao.updateStatus(
                id = 100L,
                status = DownloadStatus.FAILED,
                failureType = any(),
                errorMessage = any(),
                rejectedVideoId = "abc123",
            )
        }
    }

    @Test fun `failed outcome marks FAILED with DOWNLOAD_ERROR`() = runTest {
        val entry = entry(id = 100L, trackId = 1L)
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns listOf(entry)
        coEvery { trackDao.getById(1L) } returns stubTrack(1L)
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Failed(
            error = "yt-dlp timeout",
        )

        newWorker().doWork()

        coVerify(exactly = 1) { downloadQueueDao.incrementRetryCount(100L) }
        coVerify(exactly = 1) {
            downloadQueueDao.updateStatus(
                id = 100L,
                status = DownloadStatus.FAILED,
                failureType = any(),
                errorMessage = match { it.contains("yt-dlp timeout") },
            )
        }
    }

    @Test fun `deferred outcome does NOT touch the queue row`() = runTest {
        val entry = entry(id = 100L, trackId = 1L)
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns listOf(entry)
        coEvery { trackDao.getById(1L) } returns stubTrack(1L)
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Deferred

        newWorker().doWork()

        coVerify(exactly = 0) { downloadQueueDao.updateStatus(id = 100L, status = DownloadStatus.FAILED, any(), any(), any(), any()) }
        coVerify(exactly = 0) { downloadQueueDao.updateStatus(id = 100L, status = DownloadStatus.COMPLETED, completedAt = any()) }
        coVerify(exactly = 0) { downloadQueueDao.incrementRetryCount(any()) }
    }

    @Test fun `blocked track deletes the queue row and does NOT invoke TrackDownloader`() = runTest {
        val entry = entry(id = 100L, trackId = 1L)
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns listOf(entry)
        coEvery { trackDao.getById(1L) } returns stubTrack(1L)
        coEvery { blocklistGuard.isBlocked(any(), any(), any(), any()) } returns true

        newWorker().doWork()

        coVerify(exactly = 1) { downloadQueueDao.deleteByTrackId(1L) }
        coVerify(exactly = 0) { trackDownloader.downloadTrack(any(), any()) }
    }

    @Test fun `already-downloaded track is idempotently marked COMPLETED without re-downloading`() = runTest {
        val entry = entry(id = 100L, trackId = 1L)
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns listOf(entry)
        coEvery { trackDao.getById(1L) } returns stubTrack(1L, isDownloaded = true, filePath = "/already/here.flac")

        newWorker().doWork()

        coVerify(exactly = 1) {
            downloadQueueDao.updateStatus(id = 100L, status = DownloadStatus.COMPLETED, completedAt = any())
        }
        coVerify(exactly = 0) { trackDownloader.downloadTrack(any(), any()) }
    }

    @Test fun `IN_PROGRESS stamp is written BEFORE invoking TrackDownloader`() = runTest {
        val entry = entry(id = 100L, trackId = 1L)
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns listOf(entry)
        coEvery { trackDao.getById(1L) } returns stubTrack(1L)
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Failed("fail")

        newWorker().doWork()

        // The order matters — IN_PROGRESS must land before the downloader runs.
        // coVerifyOrder enforces sequence; plain coVerify only checks existence.
        coVerifyOrder {
            downloadQueueDao.updateStatus(id = 100L, status = DownloadStatus.IN_PROGRESS)
            trackDownloader.downloadTrack(any(), any())
        }
    }

    private fun entry(id: Long, trackId: Long) = DownloadQueueEntity(
        id = id,
        trackId = trackId,
        status = DownloadStatus.PENDING,
        syncId = null,
        searchQuery = "artist - title",
    )

    private fun stubTrack(
        id: Long,
        isDownloaded: Boolean = false,
        filePath: String? = null,
    ) = TrackEntity(
        id = id,
        title = "Track $id",
        artist = "Artist $id",
        canonicalTitle = "track $id",
        canonicalArtist = "artist $id",
        isDownloaded = isDownloaded,
        filePath = filePath,
    )
}
```

### Step 3: Run tests — expect compile error

```bash
./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.DiscoveryDownloadWorkerTest"
```

Expected: compile error — `DiscoveryDownloadWorker` doesn't exist yet.

### Step 4: Implement `DiscoveryDownloadWorker`

Create `data/download/src/main/kotlin/com/stash/data/download/DiscoveryDownloadWorker.kt`:

```kotlin
package com.stash.data.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.core.data.sync.TrackDownloadOutcome
import com.stash.core.data.sync.TrackDownloader
import com.stash.core.data.sync.workers.StashMixRefreshWorker
import com.stash.core.model.DownloadFailureType
import com.stash.core.model.DownloadStatus
import com.stash.core.model.Track
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

/**
 * Drains `download_queue` rows produced by [StashDiscoveryWorker]
 * (`sync_id IS NULL`). Parallels [TrackDownloadWorker]'s per-track flow
 * (blocklist guard → [TrackDownloader.downloadTrack] → mark COMPLETED
 * with isDownloaded=true + filePath, or FAILED with retry accounting)
 * without the sync-history coupling that worker requires.
 *
 * Chained from the tail of [StashDiscoveryWorker.doWork] (REPLACE policy
 * on the unique work name so a rapid discovery + drain cycle doesn't
 * double-run). At the end of the drain, enqueues a one-shot
 * [StashMixRefreshWorker] so mixes re-materialize and the user sees the
 * newly-downloaded survivors without manual refresh.
 *
 * Foreground-service promotion via [getForegroundInfo] is the same
 * pattern TrackDownloadWorker uses — required for long batches that
 * outlive normal background limits.
 */
@HiltWorker
class DiscoveryDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadQueueDao: DownloadQueueDao,
    private val trackDao: TrackDao,
    private val trackDownloader: TrackDownloader,
    private val audioDurationExtractor: AudioDurationExtractor,
    private val blocklistGuard: BlocklistGuard,
    private val syncNotificationManager: SyncNotificationManager,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "discovery_download"
        private const val TAG = "DiscoveryDownload"

        fun enqueueOneTime(context: Context, constraints: Constraints) {
            val work = OneTimeWorkRequestBuilder<DiscoveryDownloadWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                work,
            )
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return buildForegroundInfo("Downloading discoveries", "Preparing\u2026", progress = -1f)
    }

    override suspend fun doWork(): Result {
        setForeground(getForegroundInfo())

        val pending = downloadQueueDao.pendingDiscoveryDownloads()
        if (pending.isEmpty()) {
            chainRefresh()
            return Result.success()
        }

        Log.i(TAG, "draining ${pending.size} discovery download(s)")

        for ((index, queueItem) in pending.withIndex()) {
            setForeground(
                buildForegroundInfo(
                    title = "Downloading discoveries",
                    text = "${index + 1} of ${pending.size}",
                    progress = (index.toFloat() / pending.size),
                )
            )

            // v0.9.20: stamp IN_PROGRESS BEFORE invoking the downloader.
            // REPLACE policy on UNIQUE_WORK_NAME prevents concurrent instances
            // of THIS worker; the sync-side partition predicate prevents
            // TrackDownloadWorker from touching the same row. Defense-in-depth:
            // a stale IN_PROGRESS row left over from a crashed run gets reset
            // to PENDING on the next sync (the existing `resetStaleInProgress`
            // sweep in TrackDownloadWorker's startup) — that path is unchanged.
            downloadQueueDao.updateStatus(
                id = queueItem.id,
                status = DownloadStatus.IN_PROGRESS,
            )

            val trackEntity = trackDao.getById(queueItem.trackId) ?: continue

            if (blocklistGuard.isBlocked(
                    artist = trackEntity.artist,
                    title = trackEntity.title,
                    spotifyUri = null,
                    youtubeId = null,
                )) {
                Log.d(TAG, "Skipping blocked track ${trackEntity.id}")
                downloadQueueDao.deleteByTrackId(trackEntity.id)
                continue
            }

            if (trackEntity.isDownloaded && trackEntity.filePath != null) {
                downloadQueueDao.updateStatus(
                    id = queueItem.id,
                    status = DownloadStatus.COMPLETED,
                    completedAt = System.currentTimeMillis(),
                )
                continue
            }

            val track = trackEntity.toDomain()
            val outcome = runCatching {
                trackDownloader.downloadTrack(track = track, preResolvedUrl = queueItem.youtubeUrl)
            }.getOrElse {
                Log.e(TAG, "downloadTrack threw for ${track.artist} - ${track.title}", it)
                TrackDownloadOutcome.Failed(error = it.message.orEmpty())
            }

            when (outcome) {
                is TrackDownloadOutcome.Success -> handleSuccess(queueItem, trackEntity, outcome)
                is TrackDownloadOutcome.Unmatched -> handleUnmatched(queueItem, track, outcome)
                is TrackDownloadOutcome.Failed -> handleFailed(queueItem, track, outcome)
                is TrackDownloadOutcome.Deferred -> {
                    // Lossless deferred — TrackDownloaderImpl already moved the row
                    // to WAITING_FOR_LOSSLESS. LosslessRetryWorker owns the
                    // re-attempt. No-op here.
                    Log.i(TAG, "Deferred (waiting for lossless): ${track.artist} - ${track.title}")
                }
            }
        }

        chainRefresh()
        return Result.success()
    }

    private suspend fun handleSuccess(
        queueItem: DownloadQueueEntity,
        trackEntity: TrackEntity,
        outcome: TrackDownloadOutcome.Success,
    ) {
        val fileSize = try { File(outcome.filePath).length() } catch (_: Exception) { 0L }
        val meta = audioDurationExtractor.extract(outcome.filePath)

        trackDao.markAsDownloaded(
            trackId = trackEntity.id,
            filePath = outcome.filePath,
            fileSizeBytes = fileSize,
            sampleRateHz = meta?.sampleRateHz,
            bitsPerSample = meta?.bitsPerSample,
        )

        if (meta != null && meta.format != "unknown") {
            runCatching {
                trackDao.setFormatAndQuality(
                    trackId = trackEntity.id,
                    fileFormat = meta.format,
                    qualityKbps = meta.bitrateKbps,
                )
            }
        }

        downloadQueueDao.updateStatus(
            id = queueItem.id,
            status = DownloadStatus.COMPLETED,
            completedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun handleUnmatched(
        queueItem: DownloadQueueEntity,
        track: Track,
        outcome: TrackDownloadOutcome.Unmatched,
    ) {
        val err = "No YouTube match for: ${track.artist} - ${track.title}"
        Log.w(TAG, err)
        downloadQueueDao.incrementRetryCount(queueItem.id)
        downloadQueueDao.updateStatus(
            id = queueItem.id,
            status = DownloadStatus.FAILED,
            failureType = DownloadFailureType.NO_MATCH,
            errorMessage = err,
            rejectedVideoId = outcome.rejectedVideoId,
        )
    }

    private suspend fun handleFailed(
        queueItem: DownloadQueueEntity,
        track: Track,
        outcome: TrackDownloadOutcome.Failed,
    ) {
        Log.e(TAG, "Download failed for ${track.artist} - ${track.title}: ${outcome.error}")
        downloadQueueDao.incrementRetryCount(queueItem.id)
        downloadQueueDao.updateStatus(
            id = queueItem.id,
            status = DownloadStatus.FAILED,
            failureType = DownloadFailureType.DOWNLOAD_ERROR,
            errorMessage = outcome.error.take(500),
        )
    }

    /**
     * Always chain — even on all-failure batches. The mix-refresh worker
     * is a no-op when nothing new is on disk; simpler to unconditionally
     * fire than to branch.
     */
    private fun chainRefresh() {
        StashMixRefreshWorker.enqueueOneTime(applicationContext)
    }

    /**
     * Inline mirror of [TrackDownloadWorker]'s private `createForegroundInfo`
     * helper. Duplicated rather than promoted to a public method on
     * [SyncNotificationManager] because the WorkManager cancel intent is
     * per-worker-instance and SyncNotificationManager is a singleton.
     */
    private fun buildForegroundInfo(
        title: String,
        text: String,
        progress: Float,
    ): ForegroundInfo {
        val notification = syncNotificationManager.buildProgressNotification(
            title = title,
            text = text,
            progress = progress,
            cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        )
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                SyncNotificationManager.NOTIFICATION_ID_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(SyncNotificationManager.NOTIFICATION_ID_PROGRESS, notification)
        }
    }
}
```

**Verification points the implementer should sanity-check before committing:**

1. `SyncNotificationManager.buildProgressNotification` signature — the spec assumes `(title, text, progress, cancelIntent)`. If the actual signature differs, adapt. Grep the source.
2. `trackDao.markAsDownloaded` parameter shape — copy verbatim from `TrackDownloadWorker.kt:274-280`.
3. `trackDao.setFormatAndQuality` parameter shape — copy from `TrackDownloadWorker.kt:296-301`.
4. `downloadQueueDao.updateStatus` overloads — there are multiple. Use the right one per call site (COMPLETED takes completedAt; FAILED takes failureType + errorMessage + optionally rejectedVideoId; IN_PROGRESS takes just id + status).

### Step 5: Run tests — expect 7/7 pass

```bash
./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.DiscoveryDownloadWorkerTest"
```

Expected: 7/7 pass. If any tests fail, the most likely culprit is a `coVerify` parameter signature mismatch — match the actual method signature of `downloadQueueDao.updateStatus` overloads.

### Step 6: Run the full :data:download module test sweep

```bash
./gradlew :data:download:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. Known pre-existing failure may exist for `YtLibraryCanonicalizerTest` (unrelated, from prior PR 1) — note but don't fix.

### Step 7: Commit

```bash
git add data/download/src/main/kotlin/com/stash/data/download/DiscoveryDownloadWorker.kt \
        data/download/src/test/kotlin/com/stash/data/download/DiscoveryDownloadWorkerTest.kt
git commit -m "feat(download): new DiscoveryDownloadWorker drains sync_id IS NULL queue

Parallels TrackDownloadWorker's per-track flow (blocklist guard →
TrackDownloader.downloadTrack → outcome handling) without the
sync-history coupling. Foreground service for long batches with
per-track progress notification. Stamps IN_PROGRESS before each
invocation as defense-in-depth.

At the end of the drain, always chains
StashMixRefreshWorker.enqueueOneTime so mixes re-materialize with
the newly-downloaded survivors without manual refresh.

Code-duplication note: handleSuccess / handleUnmatched / handleFailed
mirror TrackDownloadWorker.kt:262-380. Extraction would touch the
sync chain's parallel-execution context (atomic counters, sync-state
callbacks) — duplication is bounded and the TrackDownloader contract
is stable.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Chain `DiscoveryDownloadWorker` from `StashDiscoveryWorker`

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashDiscoveryWorker.kt`

No unit test for the chain itself — testing `WorkManager.enqueueUniqueWork` invocation via MockK is fragile; verified by manual on-device test in Task 6.

### Step 1: Read the existing `doWork` end

Open `StashDiscoveryWorker.kt`. Find the bottom of `doWork()` — the last statement before the return.

### Step 2: Add the chain

At the end of `doWork()`, after the existing recipe-iteration loop and any final logging:

```kotlin
// v0.9.20: after queueing/processing discoveries, kick the downloader
// so the new tracks become playable in this charging+WiFi window.
// Mirror this worker's own constraints (charging + batteryNotLow +
// NetworkType.UNMETERED) — discovery downloads should respect the same
// posture that gated the discovery itself.
val downloadConstraints = Constraints.Builder()
    .setRequiresCharging(true)
    .setRequiresBatteryNotLow(true)
    .setRequiredNetworkType(NetworkType.UNMETERED)
    .build()
DiscoveryDownloadWorker.enqueueOneTime(applicationContext, downloadConstraints)
```

Required new imports (add only if not already present — grep first):

```kotlin
import androidx.work.Constraints
import androidx.work.NetworkType
import com.stash.data.download.DiscoveryDownloadWorker
```

### Step 3: Build to verify

```bash
./gradlew :core:data:assembleDebug
```

Expected: BUILD SUCCESSFUL.

### Step 4: Run the :core:data module test sweep

```bash
./gradlew :core:data:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

### Step 5: Commit

```bash
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashDiscoveryWorker.kt
git commit -m "feat(mix): chain DiscoveryDownloadWorker from StashDiscoveryWorker tail

Discovery downloads now happen in the same charging+WiFi window that
gated the discovery itself. Constraints mirror StashDiscoveryWorker's
(charging + batteryNotLow + UNMETERED). REPLACE policy on the unique
work name means a back-to-back discovery + drain cycle doesn't double-
run.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: `is_downloaded = 1` filter (Path B, defense-in-depth)

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/DiscoveryQueueDao.kt`
- Modify: `core/data/src/test/kotlin/com/stash/core/data/db/dao/DiscoveryQueueDaoCapTest.kt`

### Step 1: Read the current state of both files

Open `DiscoveryQueueDao.kt`. Find the existing `getDoneTrackIdsForRecipe` query by name — its `@Query` body was updated by PR 1 Task 8 (`f29f6da`) to add `INNER JOIN tracks t ON t.id = dq.track_id`. The current WHERE clause filters on `dq.recipe_id`, `dq.status = 'DONE'`, and `dq.track_id IS NOT NULL`.

Open `DiscoveryQueueDaoCapTest.kt`. Find the existing `trackEntity(id: Long)` helper near the bottom of the file. PR 1 Task 8 added 1 test (`excludes rows whose track_id points to a deleted track`) bringing the count to 6. The helper currently uses TrackEntity defaults — `isDownloaded` defaults to `false`.

### Step 2: Update the test helper to default `isDownloaded = true`

In `DiscoveryQueueDaoCapTest.kt`, find the existing helper:

```kotlin
private fun trackEntity(id: Long) = TrackEntity(
    id = id,
    title = "Track $id",
    artist = "Artist $id",
    canonicalTitle = "track $id",
    canonicalArtist = "artist $id",
)
```

Replace with:

```kotlin
private fun trackEntity(id: Long, isDownloaded: Boolean = true) = TrackEntity(
    id = id,
    title = "Track $id",
    artist = "Artist $id",
    canonicalTitle = "track $id",
    canonicalArtist = "artist $id",
    isDownloaded = isDownloaded,
)
```

### Step 3: Add the new failing test

After the existing 6 tests, add:

```kotlin
@Test fun `excludes rows whose track is not yet downloaded`() = runTest {
    db.trackDao().insert(trackEntity(id = 1L, isDownloaded = true))
    db.trackDao().insert(trackEntity(id = 2L, isDownloaded = false))  // stub
    dao.insertIfNew(doneRow(recipeId, trackId = 1L, completedAt = 1000L))
    dao.insertIfNew(doneRow(recipeId, trackId = 2L, completedAt = 2000L))

    val result = dao.getDoneTrackIdsForRecipe(recipeId, limit = 99)

    assertEquals(listOf(1L), result)
}
```

### Step 4: Run tests — expect the new test to fail

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.dao.DiscoveryQueueDaoCapTest"
```

Expected: 6/7 pass (existing 6 still pass because the helper default change keeps them seeding `isDownloaded = true`); the new test fails because the DAO doesn't filter on `is_downloaded = 1` yet.

### Step 5: Implement the DAO change

In `DiscoveryQueueDao.kt`, the existing query at lines 92-102:

```kotlin
@Query(
    """
    SELECT dq.track_id FROM discovery_queue dq
    INNER JOIN tracks t ON t.id = dq.track_id
    WHERE dq.recipe_id = :recipeId
      AND dq.status = 'DONE'
      AND dq.track_id IS NOT NULL
    ORDER BY dq.completed_at DESC
    LIMIT :limit
    """
)
suspend fun getDoneTrackIdsForRecipe(recipeId: Long, limit: Int): List<Long>
```

Add `AND t.is_downloaded = 1` and update the KDoc:

```kotlin
/**
 * ... existing KDoc paragraphs preserved ...
 *
 * v0.9.20 follow-up: AND t.is_downloaded = 1 ensures the mix never
 * surfaces a stub TrackEntity (a discovery row that was marked DONE
 * by StashDiscoveryWorker but whose file hasn't landed on disk yet).
 * Without this filter, a transient window between StashDiscoveryWorker
 * completion and DiscoveryDownloadWorker completion would put unplayable
 * tracks in the playlist. DiscoveryDownloadWorker fixes the underlying
 * issue (downloads run promptly); this filter is defense-in-depth.
 */
@Query(
    """
    SELECT dq.track_id FROM discovery_queue dq
    INNER JOIN tracks t ON t.id = dq.track_id
    WHERE dq.recipe_id = :recipeId
      AND dq.status = 'DONE'
      AND dq.track_id IS NOT NULL
      AND t.is_downloaded = 1
    ORDER BY dq.completed_at DESC
    LIMIT :limit
    """
)
suspend fun getDoneTrackIdsForRecipe(recipeId: Long, limit: Int): List<Long>
```

### Step 6: Run tests — expect 7/7 pass

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.dao.DiscoveryQueueDaoCapTest"
```

Expected: 7/7 pass.

### Step 7: Run the full :core:data module test sweep

```bash
./gradlew :core:data:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

### Step 8: Commit

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/DiscoveryQueueDao.kt \
        core/data/src/test/kotlin/com/stash/core/data/db/dao/DiscoveryQueueDaoCapTest.kt
git commit -m "fix(mix): filter discovery survivors by is_downloaded = 1

Defense-in-depth: even if there's a transient window where
StashDiscoveryWorker has marked DONE but DiscoveryDownloadWorker
hasn't finished, the mix won't surface the stub. The real fix is
the new worker (downloads happen promptly); this filter guarantees
the mix UI never shows unplayable tracks.

Existing test helper updated to default isDownloaded = true so
prior tests don't suddenly return empty; new test asserts the
filter excludes stubs.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Build APK + install + on-device verification

**Files:** none — verification task.

### Step 1: Full release build

```bash
./gradlew :app:assembleRelease
```

Expected: BUILD SUCCESSFUL. If `packageRelease` fails transiently (observed during PR 1 + PR 3), re-run `./gradlew :app:packageRelease`.

### Step 2: Install over the existing release app on Pixel

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Expected: `Success`.

### Step 3: Cold-start the app

```bash
adb shell monkey -p com.stash.app -c android.intent.category.LAUNCHER 1
```

Wait ~5 seconds.

### Step 4: Trigger per-recipe refresh on each mix, verify zero overlap

On the Pixel:
1. Long-press Daily Discover → "Refresh this mix". Wait for the snackbar to confirm `Refreshed Daily Discover`. Note the first ~5 tracks.
2. Long-press Deep Cuts → "Refresh this mix". Note its first ~5 tracks. They should be DIFFERENT from Daily Discover's.
3. Long-press First Listen → "Refresh this mix". Note its first ~5 tracks. Different from both above.
4. **Acceptance**: zero track-title overlap across the three top-5 samples. If any track appears in two mixes, dig in — likely a stub-track race that Task 5's filter should have prevented.

### Step 5: Plug in to charge + connect WiFi, watch the discovery downloader

The `StashDiscoveryWorker` is constrained on charging + batteryNotLow + WiFi. Once those are satisfied, JobScheduler will fire it (within a few minutes; can take longer if Doze is active).

Tail logcat for the chain:

```bash
adb logcat -t 200 | grep -E "StashDiscovery|DiscoveryDownload|StashMixRefresh"
```

Expected sequence:
1. `StashDiscoveryWorker` starts, processes its batch.
2. `DiscoveryDownload: draining N discovery download(s)` log line.
3. Per-track progress notification visible in the system shade.
4. Each track logged on completion (success or failure).
5. After the drain, `StashMixRefresh` fires (chained refresh).

### Step 6: Verify mixes are now playable

After the drain completes:
1. Open each mix on the Pixel.
2. Track counts should reflect playable tracks only (no phantom rows).
3. Tap several tracks per mix — every one should play immediately.

### Step 7: Reporting

When all manual checks pass, summarize:
- Commits that landed (likely 5: per-recipe dedup + partition + new worker + chain + filter)
- APK installed on the Pixel
- Migration log line observed
- Manual checks that passed
- Any deferred items

Do NOT push, do NOT tag.

---

## What's intentionally not in this plan

YAGNI:

- **A new unit test for the StashDiscoveryWorker chain.** WorkManager.enqueueUniqueWork is hard to assert on via MockK without significant test infrastructure. Manual on-device verification is more reliable.
- **Promoting `createForegroundInfo` to public on SyncNotificationManager.** Duplication inside DiscoveryDownloadWorker is bounded; future divergence between sync and discovery foreground notifications is easier to reason about with two clear sites.
- **Modifying `TrackDownloadWorker` to accept `syncId = null`.** Explicitly rejected during brainstorming — partition predicate is the cleaner answer.
- **A "discovery downloads pending" UI surface.** The foreground service notification suffices.
- **Inline pre-seeding of discovery_queue on migration.** Still deferred from PR 3.
