# Stash Mix Recommendation Pivot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Shift Daily Discover and Deep Cuts from library-substrate to recommendation-substrate (85% discovery), with cross-mix track dedup and a shortfall-fill gate that stops library backfill on high-discovery recipes.

**Architecture:** Six small, sequential changes across `StashMixRecipeDao` (signature expansion), `MixGenerator` (excludeIds param + shortfall threshold), `StashMixRefreshWorker` (materializeMix returns picked discovery ids; doWork orchestrates dedup ordering), `StashMixDefaults` (retuned builtin entries), and `StashApplication` (new version-gated migration mirroring the existing `maybeRetuneStashDiscover` pattern).

**Tech Stack:** Kotlin, Room, WorkManager, Hilt, MockK + JUnit4 for ViewModel/Worker tests, Robolectric + Room in-memory for DAO behavior tests.

**Spec:** `docs/superpowers/specs/2026-05-11-stash-mix-recommendation-pivot-design.md` (committed at `7744b3a`).

---

## Pre-flight notes for the implementer

Worktree: `C:\Users\theno\Projects\MP3APK\.worktrees\first-listen-tag-fallback`. Branch: `feat/first-listen-tag-fallback`. PR 1 already shipped 11 commits on this branch — verify your work lands on top, don't rebase.

### Repository conventions you must follow

1. **TDD with bite-sized tasks.** Each task either follows the test-first cycle or is mechanical configuration. Build pass + module test sweep after every commit.
2. **MockK for unit tests over collaborators (MixGenerator, StashMixRefreshWorker); Robolectric + Room in-memory for DAO behavior tests.** This module already mixes the two patterns — match the precedent of `MixGeneratorComputeUserTopTagsTest` (MockK) and `DiscoveryQueueDaoCapTest` (Robolectric + Room).
3. **Inline string literals** for any user-facing messages, but this PR has none (purely backend).
4. **No `--no-verify`. No `--amend`. No push, no tag.** Per memory `feedback_ship_terminology.md`, implementation ends at "APK installed on Pixel + manual verification."
5. **Always `:app:installDebug` after a fix.** Per memory `feedback_install_after_fix.md`.
6. **No dev-time estimates** anywhere. Per memory `feedback_no_time_estimates.md`.

### Files you will touch

| Path | What changes |
|---|---|
| `core/data/src/main/kotlin/com/stash/core/data/db/dao/StashMixRecipeDao.kt` | Expand `retuneBuiltin` signature: add `affinityBias: Float, seedStrategy: String`. |
| `core/data/src/test/kotlin/com/stash/core/data/db/dao/StashMixRecipeDaoRetuneTest.kt` | **CREATE** — Robolectric + Room in-memory tests for the expanded signature. |
| `app/src/main/kotlin/com/stash/app/StashApplication.kt` | Fix the existing `maybeRetuneStashDiscover` call site to pass the new params. Add `maybeRetuneStashMixes` + `STASH_MIX_RECIPE_TUNING_VERSION` constant. Wire into startup sequence. |
| `core/data/src/main/kotlin/com/stash/core/data/mix/MixGenerator.kt` | Add `excludeIds: Set<Long> = emptySet()` to `generate`. Raise shortfall-fill threshold from `< 1.0f` to `< 0.5f`. |
| `core/data/src/test/kotlin/com/stash/core/data/mix/MixGeneratorExcludeIdsTest.kt` | **CREATE** — MockK tests for the excludeIds filter. |
| `core/data/src/test/kotlin/com/stash/core/data/mix/MixGeneratorShortfallFillTest.kt` | **CREATE** — MockK tests for the shortfall-fill gate. |
| `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt` | Refactor `materializeMix` to return `MaterializeResult(playlistId, discoveryIds)`, accept `excludeIds: Set<Long>`. Add private `recipeDedupPriority` helper. Rewrite `doWork` iteration to accumulate excludeIds across ordered recipes. Over-fetch discovery survivors so post-filter still fills the cap. |
| `core/data/src/test/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorkerDedupTest.kt` | **CREATE** — MockK end-to-end test of the dedup orchestration. |
| `core/data/src/main/kotlin/com/stash/core/data/mix/StashMixDefaults.kt` | Retune Daily Discover + Deep Cuts builtin rows. |

### Reference precedents

- **DAO Robolectric test pattern:** `core/data/src/test/kotlin/com/stash/core/data/db/dao/DiscoveryQueueDaoCapTest.kt`.
- **MixGenerator MockK test pattern:** `core/data/src/test/kotlin/com/stash/core/data/mix/MixGeneratorComputeUserTopTagsTest.kt`.
- **Worker MockK test pattern:** `data/download/src/test/kotlin/com/stash/data/download/lossless/LosslessRetryWorkerTest.kt`.
- **Existing version-gated migration:** `app/src/main/kotlin/com/stash/app/StashApplication.kt:335-355` (`maybeRetuneStashDiscover`). Mirror this exactly.

---

## Task 1: Expand `retuneBuiltin` signature + update existing call site

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/StashMixRecipeDao.kt`
- Modify: `app/src/main/kotlin/com/stash/app/StashApplication.kt:339-344` (the existing `maybeRetuneStashDiscover` call to `retuneBuiltin`)
- Create: `core/data/src/test/kotlin/com/stash/core/data/db/dao/StashMixRecipeDaoRetuneTest.kt`

The DAO currently has a 4-arg `retuneBuiltin`. We need 6 args (add `affinityBias`, `seedStrategy`). Expanding it compile-errors the existing call site in `StashApplication.maybeRetuneStashDiscover`, which we fix in the same commit.

- [ ] **Step 1: Read the current DAO definition**

Open `StashMixRecipeDao.kt`. The current method at lines 79-100 is the 4-arg version. Read it to confirm shape (KDoc + `@Query` + signature).

- [ ] **Step 2: Read the existing call site**

Open `StashApplication.kt`. Lines 335-355 are `maybeRetuneStashDiscover`. The call at lines 339-344 currently passes 4 args (name, discoveryRatio, freshnessWindowDays, targetLength). You'll update it in Step 6.

- [ ] **Step 3: Write the failing DAO test**

Create `core/data/src/test/kotlin/com/stash/core/data/db/dao/StashMixRecipeDaoRetuneTest.kt`:

```kotlin
package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.StashMixRecipeEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [StashMixRecipeDao.retuneBuiltin] — specifically the v0.9.20
 * expansion that adds affinityBias and seedStrategy to the SET clause so
 * the recipe-pivot migration can update all five tunable fields in one
 * UPDATE.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StashMixRecipeDaoRetuneTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: StashMixRecipeDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.stashMixRecipeDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `retuneBuiltin updates all 5 tunable fields including affinityBias and seedStrategy`() = runTest {
        dao.insert(
            StashMixRecipeEntity(
                name = "Daily Discover",
                discoveryRatio = 0.4f,
                freshnessWindowDays = 7,
                targetLength = 50,
                affinityBias = 0.3f,
                seedStrategy = "ARTIST_SIMILAR",
                isBuiltin = true,
            )
        )

        val updated = dao.retuneBuiltin(
            name = "Daily Discover",
            discoveryRatio = 0.85f,
            freshnessWindowDays = 7,
            targetLength = 40,
            affinityBias = 0.0f,
            seedStrategy = "ARTIST_SIMILAR",
        )

        assertEquals(1, updated)
        val after = dao.getActive().single()
        assertEquals(0.85f, after.discoveryRatio)
        assertEquals(40, after.targetLength)
        assertEquals(0.0f, after.affinityBias)
        assertEquals("ARTIST_SIMILAR", after.seedStrategy)
        assertEquals(7, after.freshnessWindowDays)
    }

    @Test fun `retuneBuiltin is idempotent — second call with same values returns 1 with no state change`() = runTest {
        dao.insert(
            StashMixRecipeEntity(
                name = "Deep Cuts",
                discoveryRatio = 0.85f,
                freshnessWindowDays = 90,
                targetLength = 40,
                affinityBias = 0.0f,
                seedStrategy = "TRACK_SIMILAR",
                isBuiltin = true,
            )
        )

        val first = dao.retuneBuiltin("Deep Cuts", 0.85f, 90, 40, 0.0f, "TRACK_SIMILAR")
        val second = dao.retuneBuiltin("Deep Cuts", 0.85f, 90, 40, 0.0f, "TRACK_SIMILAR")

        assertEquals(1, first)
        assertEquals(1, second) // SQLite UPDATE rowcount is rows-matched, not rows-changed
        val after = dao.getActive().single()
        assertEquals("TRACK_SIMILAR", after.seedStrategy)
    }

    @Test fun `retuneBuiltin does not touch non-builtin recipes with the same name`() = runTest {
        dao.insert(
            StashMixRecipeEntity(
                name = "Daily Discover",
                discoveryRatio = 0.4f,
                affinityBias = 0.3f,
                seedStrategy = "ARTIST_SIMILAR",
                isBuiltin = false, // user-created with same name
            )
        )

        val updated = dao.retuneBuiltin("Daily Discover", 0.85f, 7, 40, 0.0f, "ARTIST_SIMILAR")

        assertEquals(0, updated)
        val after = dao.getActive().single()
        assertEquals(0.4f, after.discoveryRatio) // untouched
        assertEquals(0.3f, after.affinityBias)
    }
}
```

- [ ] **Step 4: Run tests to verify they fail at compile time**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.dao.StashMixRecipeDaoRetuneTest"
```

Expected: compile error — `retuneBuiltin` doesn't accept `affinityBias` or `seedStrategy` parameters yet.

- [ ] **Step 5: Implement the DAO change**

In `StashMixRecipeDao.kt`, replace the existing `retuneBuiltin` `@Query` + signature (lines 79-100) with:

```kotlin
/**
 * Non-destructive tuning migration — updates an individual builtin
 * recipe's knobs without dropping its materialized playlist or
 * cascading its discovery_queue. Used when we ship a new default
 * (e.g. bumping discovery_ratio) and want existing installs to pick
 * it up without wiping the user's accumulated discovery state.
 *
 * v0.9.20: extended from 4 to 6 fields. The recipe-pivot migration
 * needs to change affinityBias + seedStrategy alongside the original
 * three knobs in a single atomic UPDATE.
 */
@Query(
    """
    UPDATE stash_mix_recipes
    SET discovery_ratio = :discoveryRatio,
        freshness_window_days = :freshnessWindowDays,
        target_length = :targetLength,
        affinity_bias = :affinityBias,
        seed_strategy = :seedStrategy
    WHERE is_builtin = 1 AND name = :name
    """
)
suspend fun retuneBuiltin(
    name: String,
    discoveryRatio: Float,
    freshnessWindowDays: Int,
    targetLength: Int,
    affinityBias: Float,
    seedStrategy: String,
): Int
```

- [ ] **Step 6: Update the existing v1 migration call site**

In `StashApplication.kt`, the existing `maybeRetuneStashDiscover` at lines 335-355 calls `stashMixRecipeDao.retuneBuiltin(...)` with 4 args. Update that call to pass the two new params with the Stash Discover values from that era — `affinityBias = 0.0f` and `seedStrategy = "TAG_GRAPH"`:

```kotlin
val updated = stashMixRecipeDao.retuneBuiltin(
    name = "Stash Discover",
    discoveryRatio = 1.0f,
    freshnessWindowDays = 14,
    targetLength = 50,
    affinityBias = 0.0f,
    seedStrategy = "TAG_GRAPH",
)
```

This preserves the v1 migration's intent for any user still on v0.9.x who hasn't run it yet.

- [ ] **Step 7: Run tests to verify all 3 pass**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.dao.StashMixRecipeDaoRetuneTest"
```

Expected: 3/3 pass.

- [ ] **Step 8: Build app module to verify the call-site update compiles**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Run the full :core:data module test sweep**

```bash
./gradlew :core:data:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. No regressions.

- [ ] **Step 10: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/StashMixRecipeDao.kt \
        core/data/src/test/kotlin/com/stash/core/data/db/dao/StashMixRecipeDaoRetuneTest.kt \
        app/src/main/kotlin/com/stash/app/StashApplication.kt
git commit -m "feat(mix): expand retuneBuiltin signature to cover affinityBias + seedStrategy

The recipe-pivot migration needs all 5 tunable fields in a single UPDATE.
Existing v1 Stash Discover migration call site updated to pass the new
params (TAG_GRAPH / 0.0f — the Stash Discover values from that era)
so it continues working for installs that haven't run it yet.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `MixGenerator.generate` accepts `excludeIds`

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/mix/MixGenerator.kt:113-201` (the `generate` function)
- Create: `core/data/src/test/kotlin/com/stash/core/data/mix/MixGeneratorExcludeIdsTest.kt`

- [ ] **Step 1: Read the existing MixGenerator MockK test for patterns**

Open `core/data/src/test/kotlin/com/stash/core/data/mix/MixGeneratorComputeUserTopTagsTest.kt`. Note how it constructs `MixGenerator` with mocked DAOs — copy that constructor pattern verbatim.

- [ ] **Step 2: Write failing tests**

Create `MixGeneratorExcludeIdsTest.kt`:

```kotlin
package com.stash.core.data.mix

import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.TrackSkipEventDao
import com.stash.core.data.db.dao.TrackTagDao
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.db.entity.TrackEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [MixGenerator.generate]'s v0.9.20 `excludeIds` parameter — the
 * cross-mix dedup primitive. excludeIds filters the library pool BEFORE
 * scoring + slot allocation, so excluded tracks can never appear in the
 * result.
 */
class MixGeneratorExcludeIdsTest {

    private val trackDao: TrackDao = mockk()
    private val trackTagDao: TrackTagDao = mockk(relaxed = true)
    private val listeningEventDao: ListeningEventDao = mockk(relaxed = true)
    private val discoveryQueueDao: DiscoveryQueueDao = mockk(relaxed = true)
    private val blocklistGuard: BlocklistGuard = mockk(relaxed = true)
    private val trackSkipEventDao: TrackSkipEventDao = mockk(relaxed = true)
    private lateinit var generator: MixGenerator

    @Before fun setUp() {
        generator = MixGenerator(
            trackDao,
            trackTagDao,
            listeningEventDao,
            discoveryQueueDao,
            blocklistGuard,
            trackSkipEventDao,
        )
    }

    @Test fun `excludeIds removes matching tracks from the pool before scoring`() = runTest {
        val tracks = (1L..5L).map { stubTrack(it) }
        coEvery { trackDao.getAllDownloaded() } returns tracks
        coEvery { listeningEventDao.getPlayCountsSinceWithLatest(any()) } returns emptyList()
        coEvery { listeningEventDao.getTrackIdsPlayedSince(any()) } returns emptyList()
        coEvery { trackTagDao.getByTrack(any()) } returns emptyList()

        val result = generator.generate(
            recipe = stubRecipe(discoveryRatio = 0.0f, targetLength = 10),
            excludeIds = setOf(2L, 4L),
        )

        val ids = result.map { it.id }.toSet()
        assertEquals(setOf(1L, 3L, 5L), ids)
        assertTrue("expected 2L excluded", 2L !in ids)
        assertTrue("expected 4L excluded", 4L !in ids)
    }

    @Test fun `empty excludeIds preserves the full library pool`() = runTest {
        val tracks = (1L..5L).map { stubTrack(it) }
        coEvery { trackDao.getAllDownloaded() } returns tracks
        coEvery { listeningEventDao.getPlayCountsSinceWithLatest(any()) } returns emptyList()
        coEvery { listeningEventDao.getTrackIdsPlayedSince(any()) } returns emptyList()
        coEvery { trackTagDao.getByTrack(any()) } returns emptyList()

        val result = generator.generate(stubRecipe(discoveryRatio = 0.0f, targetLength = 10))

        assertEquals(5, result.size)
    }

    private fun stubRecipe(discoveryRatio: Float, targetLength: Int) = StashMixRecipeEntity(
        id = 1L,
        name = "Test",
        discoveryRatio = discoveryRatio,
        targetLength = targetLength,
        freshnessWindowDays = 0,
    )

    private fun stubTrack(id: Long) = TrackEntity(
        id = id,
        title = "Track $id",
        artist = "Artist $id",
        canonicalTitle = "track $id",
        canonicalArtist = "artist $id",
        isDownloaded = true,
    )
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.mix.MixGeneratorExcludeIdsTest"
```

Expected: compile error — `generate` doesn't accept `excludeIds` parameter yet.

- [ ] **Step 4: Implement the production change**

In `MixGenerator.kt`, change the `generate` signature (around line 113) and add the filter right after `trackDao.getAllDownloaded()`:

```kotlin
/**
 * Produces the finalized track list for [recipe]. The worker calling
 * this replaces the playlist_tracks rows for [recipe.playlistId] with
 * this ordering.
 *
 * v0.9.20: [excludeIds] is the cross-mix dedup primitive — when the
 * refresh worker iterates multiple recipes back-to-back, it accumulates
 * track ids already claimed by earlier recipes and passes them here so
 * the current recipe can't re-pick them.
 */
suspend fun generate(
    recipe: StashMixRecipeEntity,
    excludeIds: Set<Long> = emptySet(),
): List<TrackEntity> {
    // Step 1: candidate pool — start from every downloaded,
    // non-blacklisted track in the library. v0.9.20: filter through
    // excludeIds first (cheap O(n) set lookup, before any other filtering).
    val rawPool = trackDao.getAllDownloaded()
    val pool0 = if (excludeIds.isEmpty()) rawPool else rawPool.filter { it.id !in excludeIds }

    // Step 2: era filter (cheap, done in-memory).
    var pool = filterByEra(pool0, recipe)

    // ... rest of existing pipeline unchanged ...
}
```

The rest of the function body stays as it is — only the first 3 lines after the KDoc change.

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.mix.MixGeneratorExcludeIdsTest"
```

Expected: 2/2 pass.

- [ ] **Step 6: Run the full :core:data module test sweep**

```bash
./gradlew :core:data:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/mix/MixGenerator.kt \
        core/data/src/test/kotlin/com/stash/core/data/mix/MixGeneratorExcludeIdsTest.kt
git commit -m "feat(mix): MixGenerator.generate accepts excludeIds for cross-mix dedup

Adds an optional Set<Long> parameter that filters the library pool
before any scoring or slot allocation. Default emptySet() preserves
all existing callers. Used by StashMixRefreshWorker to ensure tracks
picked by an earlier recipe in the iteration aren't re-picked by a
later one.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: `MixGenerator` shortfall-fill threshold

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/mix/MixGenerator.kt:192-198` (the shortfall-fill gate)
- Create: `core/data/src/test/kotlin/com/stash/core/data/mix/MixGeneratorShortfallFillTest.kt`

- [ ] **Step 1: Write failing tests**

Create `MixGeneratorShortfallFillTest.kt`:

```kotlin
package com.stash.core.data.mix

import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.TrackSkipEventDao
import com.stash.core.data.db.dao.TrackTagDao
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.db.entity.TrackEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [MixGenerator.generate]'s v0.9.20 shortfall-fill threshold.
 * The old gate was `discoveryRatio < 1.0f`; the new gate is
 * `discoveryRatio < 0.5f` — high-discovery recipes return a sparser
 * library slice rather than backfilling from the rest of the library.
 */
class MixGeneratorShortfallFillTest {

    private val trackDao: TrackDao = mockk()
    private val trackTagDao: TrackTagDao = mockk(relaxed = true)
    private val listeningEventDao: ListeningEventDao = mockk(relaxed = true)
    private val discoveryQueueDao: DiscoveryQueueDao = mockk(relaxed = true)
    private val blocklistGuard: BlocklistGuard = mockk(relaxed = true)
    private val trackSkipEventDao: TrackSkipEventDao = mockk(relaxed = true)
    private lateinit var generator: MixGenerator

    @Before fun setUp() {
        generator = MixGenerator(
            trackDao,
            trackTagDao,
            listeningEventDao,
            discoveryQueueDao,
            blocklistGuard,
            trackSkipEventDao,
        )
        coEvery { listeningEventDao.getPlayCountsSinceWithLatest(any()) } returns emptyList()
        coEvery { listeningEventDao.getTrackIdsPlayedSince(any()) } returns emptyList()
        coEvery { trackTagDao.getByTrack(any()) } returns emptyList()
    }

    @Test fun `shortfall fill triggers at discoveryRatio = 0_4`() = runTest {
        coEvery { trackDao.getAllDownloaded() } returns (1L..50L).map { stubTrack(it) }

        val result = generator.generate(
            stubRecipe(discoveryRatio = 0.4f, targetLength = 50),
        )

        // librarySlots = 50 * (1 - 0.4) = 30; pool = 50; shortfall fills to 50.
        assertEquals(50, result.size)
    }

    @Test fun `shortfall fill does NOT trigger at discoveryRatio = 0_5`() = runTest {
        coEvery { trackDao.getAllDownloaded() } returns (1L..50L).map { stubTrack(it) }

        val result = generator.generate(
            stubRecipe(discoveryRatio = 0.5f, targetLength = 50),
        )

        // librarySlots = 50 * (1 - 0.5) = 25; no shortfall fill.
        assertEquals(25, result.size)
    }

    @Test fun `shortfall fill does NOT trigger at discoveryRatio = 0_85`() = runTest {
        coEvery { trackDao.getAllDownloaded() } returns (1L..50L).map { stubTrack(it) }

        val result = generator.generate(
            stubRecipe(discoveryRatio = 0.85f, targetLength = 40),
        )

        // librarySlots = 40 * (1 - 0.85) = 6; no shortfall fill.
        assertEquals(6, result.size)
    }

    @Test fun `pure discovery recipe at ratio = 1_0 returns empty library slice`() = runTest {
        coEvery { trackDao.getAllDownloaded() } returns (1L..50L).map { stubTrack(it) }

        val result = generator.generate(
            stubRecipe(discoveryRatio = 1.0f, targetLength = 50),
        )

        // librarySlots = 0; no shortfall fill (already gated by < 0.5).
        assertTrue("expected empty, got ${result.size}", result.isEmpty())
    }

    private fun stubRecipe(discoveryRatio: Float, targetLength: Int) = StashMixRecipeEntity(
        id = 1L,
        name = "Test",
        discoveryRatio = discoveryRatio,
        targetLength = targetLength,
        freshnessWindowDays = 0,
    )

    private fun stubTrack(id: Long) = TrackEntity(
        id = id,
        title = "Track $id",
        artist = "Artist $id",
        canonicalTitle = "track $id",
        canonicalArtist = "artist $id",
        isDownloaded = true,
    )
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.mix.MixGeneratorShortfallFillTest"
```

Expected: tests fail. With the current gate at `< 1.0f`, recipes with discoveryRatio 0.5 and 0.85 *do* shortfall-fill, so `result.size` will be 50 + 40 respectively (target length, not librarySlots). The `0.4` and `1.0` cases will already pass — partial fail.

- [ ] **Step 3: Implement the production change**

In `MixGenerator.kt`, the existing block runs from line 185 (the comment `// Step 8: shortfall backfill...`) through line 198 (the closing brace of the `if` block). **Replace this entire block** — including the now-misleading commentary that references "Stash Discover (ratio = 1.0)" semantics no longer applicable — with the following:

```kotlin
// Step 8: shortfall backfill from the remaining library pool.
// v0.9.20: gate raised from `< 1.0f` to `< 0.5f`. Recipes that should
// be substantially discovery-driven (>= 50% by ratio) must not silently
// degrade to library when discovery is sparse — that's exactly what
// produced the "library-heavy mixes" symptom on existing installs.
// A sparser-but-honest mix is better than a deceptively library-filled
// one. Library-only recipes (ratio == 0) and lightly-discovery recipes
// (< 0.5) still get the original full-fill behavior.
if (recipe.discoveryRatio < 0.5f) {
    val shortfall = recipe.targetLength - picked.size
    if (shortfall > 0 && picked.size < pool.size) {
        val extra = ordered.filter { it !in picked }.take(shortfall)
        return picked + extra
    }
}
```

The `return picked` statement at line 200 (or wherever it currently lives — verify) stays as the final fall-through return.

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.mix.MixGeneratorShortfallFillTest"
```

Expected: 4/4 pass.

- [ ] **Step 5: Run the full :core:data module test sweep**

```bash
./gradlew :core:data:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. No regressions.

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/mix/MixGenerator.kt \
        core/data/src/test/kotlin/com/stash/core/data/mix/MixGeneratorShortfallFillTest.kt
git commit -m "feat(mix): gate shortfall-fill at discoveryRatio >= 0.5

Recipes that should be substantially discovery-driven (>= 50% by ratio)
no longer silently backfill the library slice up to targetLength when
discovery survivors are sparse. A sparser-but-honest mix is better than
a deceptively library-filled one. Library-only and lightly-discovery
recipes (< 0.5) keep the original behavior.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: `StashMixRefreshWorker` cross-mix dedup orchestration

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt` (private `materializeMix` + `doWork`'s iteration block + add `recipeDedupPriority` helper)
- Create: `core/data/src/test/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorkerDedupTest.kt`

This task is the largest single change in the PR. It bundles:

1. New `MaterializeResult` data class (private to the worker).
2. `materializeMix` signature change: accepts `excludeIds: Set<Long>`, returns `MaterializeResult`. Over-fetches discovery survivors via `discoveryCap + excludeIds.size` then post-filters and `.take(discoveryCap)`.
3. New private companion helper `recipeDedupPriority(StashMixRecipeEntity): Int`.
4. `doWork` iteration: sorts active recipes by priority, threads a mutable `excludeIds` set through `generate` + `materializeMix`, accumulates after each.

All of these compile together as one atomic change because `materializeMix` is private to the file.

- [ ] **Step 1: Read the existing worker thoroughly**

Open `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt`. Read:
- Constructor at lines 69-84 (note the injected DAOs — you'll need these for the MockK test).
- `companion object` at lines 86+ (note `ONE_SHOT_WORK_NAME` is already public from PR 1 Task 5).
- `doWork` at line 172 — specifically the persona-fetch block at lines 199-217 (preserved verbatim) and the iteration starting at line 219 (rewritten).
- `materializeMix` at line 254 (signature + body changes).

- [ ] **Step 2: Write the failing dedup test**

Create `core/data/src/test/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorkerDedupTest.kt`. Use MockK with hand-constructed `StashMixRefreshWorker`, mirroring the `LosslessRetryWorkerTest` pattern:

```kotlin
package com.stash.core.data.sync.workers

import android.content.Context
import androidx.work.WorkerParameters
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.db.entity.TrackEntity
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
 * End-to-end MockK test for [StashMixRefreshWorker]'s cross-mix dedup
 * orchestration. Verifies that when multiple builtin recipes run in
 * priority order, no track id appears in two playlists.
 */
class StashMixRefreshWorkerDedupTest {

    private val appContext: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val recipeDao: StashMixRecipeDao = mockk(relaxed = true)
    private val playlistDao: PlaylistDao = mockk(relaxed = true)
    private val discoveryQueueDao: DiscoveryQueueDao = mockk(relaxed = true)
    private val listeningEventDao: ListeningEventDao = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val mixGenerator: MixGenerator = mockk()
    private val seedGenerator: MixSeedGenerator = mockk(relaxed = true)
    private val lastFmApiClient: LastFmApiClient = mockk(relaxed = true)
    private val lastFmCredentials: LastFmCredentials = mockk {
        coEvery { isConfigured } returns false  // skip persona fetch + discovery seeding
    }
    private val sessionPreference: LastFmSessionPreference = mockk {
        coEvery { session } returns flowOf(null)
    }
    private val blocklistGuard: BlocklistGuard = mockk {
        coEvery { isBlockedByTrackId(any()) } returns false
    }

    private fun newWorker() = StashMixRefreshWorker(
        appContext, workerParams,
        recipeDao, playlistDao, discoveryQueueDao, listeningEventDao,
        trackDao, mixGenerator, seedGenerator, lastFmApiClient,
        lastFmCredentials, sessionPreference, blocklistGuard,
    )

    @Test fun `excludeIds accumulates across recipes — no track appears in two playlists`() = runTest {
        // Three active recipes in priority order: First Listen, Deep Cuts, Daily Discover.
        val firstListen = recipe(id = 1L, name = "First Listen", ratio = 1.0f)
        val deepCuts = recipe(id = 2L, name = "Deep Cuts", ratio = 0.85f)
        val dailyDiscover = recipe(id = 3L, name = "Daily Discover", ratio = 0.85f)
        coEvery { recipeDao.getActive() } returns listOf(dailyDiscover, deepCuts, firstListen) // unsorted

        // Capture excludeIds passed to each generate() call.
        val excludeCaptureFirstListen = slot<Set<Long>>()
        val excludeCaptureDeepCuts = slot<Set<Long>>()
        val excludeCaptureDailyDiscover = slot<Set<Long>>()
        coEvery { mixGenerator.generate(firstListen, capture(excludeCaptureFirstListen)) } returns emptyList()
        coEvery { mixGenerator.generate(deepCuts, capture(excludeCaptureDeepCuts)) } returns listOf(track(10L), track(11L))
        coEvery { mixGenerator.generate(dailyDiscover, capture(excludeCaptureDailyDiscover)) } returns listOf(track(20L), track(21L))

        // First Listen brings 5 discovery survivors; Deep Cuts brings 6; Daily Discover 7.
        coEvery { discoveryQueueDao.getDoneTrackIdsForRecipe(firstListen.id, any()) } returns listOf(1L, 2L, 3L, 4L, 5L)
        coEvery { discoveryQueueDao.getDoneTrackIdsForRecipe(deepCuts.id, any()) } returns listOf(6L, 7L, 8L, 9L, 10L, 11L) // 10, 11 are also library
        coEvery { discoveryQueueDao.getDoneTrackIdsForRecipe(dailyDiscover.id, any()) } returns listOf(1L, 12L, 13L, 14L, 15L, 16L, 17L) // 1L is already in First Listen

        coEvery { playlistDao.getById(any()) } returns null
        coEvery { playlistDao.insert(any()) } returnsMany listOf(100L, 200L, 300L)

        newWorker().doWork()

        // First Listen runs first (priority 1) — exclude set is empty.
        assertTrue("first listen excludeIds should start empty", excludeCaptureFirstListen.captured.isEmpty())

        // Deep Cuts runs second — exclude set has First Listen's 5 discovery ids.
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), excludeCaptureDeepCuts.captured)

        // Daily Discover runs third — exclude set has First Listen's 5 + Deep Cuts's 2 library + Deep Cuts's 4 NEW survivors (6,7,8,9 — 10 and 11 are in library so filtered by librarySet, not excludeIds).
        // Detailed: after First Listen, exclude = {1..5}. After Deep Cuts: + {10, 11} (library) + {6, 7, 8, 9} (discovery survivors, excluding 10/11 which are in library).
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L), excludeCaptureDailyDiscover.captured)
    }

    private fun recipe(id: Long, name: String, ratio: Float) = StashMixRecipeEntity(
        id = id, name = name,
        discoveryRatio = ratio, targetLength = 40,
        isBuiltin = true, isActive = true,
    )

    private fun track(id: Long) = TrackEntity(
        id = id, title = "Track $id", artist = "Artist $id",
        canonicalTitle = "track $id", canonicalArtist = "artist $id",
        isDownloaded = true,
    )
}
```

- [ ] **Step 3: Run tests to verify they fail at compile time**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.workers.StashMixRefreshWorkerDedupTest"
```

Expected: compile errors — `mixGenerator.generate` doesn't accept 2 args, or runtime mismatches if it does (Task 2 already shipped). Either way, the test should not pass against the current `doWork` (which doesn't accumulate excludeIds).

- [ ] **Step 4: Add the `MaterializeResult` data class and `recipeDedupPriority` helper**

At the bottom of `StashMixRefreshWorker.kt` (above the closing `}` of the class), or in a sensible spot near the existing private helpers:

```kotlin
    /**
     * Output of [materializeMix]. Carries the just-created/updated
     * playlist id (existing return) AND the discovery-survivor track ids
     * the materialization inserted, so [doWork]'s outer cross-mix dedup
     * loop can add them to its accumulating excludeIds set.
     */
    private data class MaterializeResult(
        val playlistId: Long,
        val discoveryIds: List<Long>,
    )

    /**
     * Cross-mix track-dedup priority. Most-restrictive recipes go first
     * so they claim their natural picks; most-permissive last so it
     * picks up the leftovers.
     *
     *  - First Listen (1.0 discovery, library-blind) — has no opinion on
     *    the library pool; claims TAG_GRAPH survivors first.
     *  - Deep Cuts (0.85 discovery) — claims TRACK_SIMILAR survivors and
     *    a sparse library slice next.
     *  - Daily Discover (0.85 discovery) — most permissive on the
     *    library slice; claims ARTIST_SIMILAR survivors last.
     *  - Non-builtins / unknown — last (99).
     *
     * Keyed by name rather than id because builtin id values aren't
     * stable across reseeds.
     */
    private fun recipeDedupPriority(recipe: StashMixRecipeEntity): Int = when (recipe.name) {
        "First Listen" -> 1
        "Deep Cuts" -> 2
        "Daily Discover" -> 3
        else -> 99
    }
```

- [ ] **Step 5: Refactor `materializeMix` to accept excludeIds and return MaterializeResult**

In `StashMixRefreshWorker.kt`, find `materializeMix` (around line 254). Update its signature + return + the discovery survivor query:

```kotlin
private suspend fun materializeMix(
    recipe: StashMixRecipeEntity,
    tracks: List<TrackEntity>,
    now: Long,
    excludeIds: Set<Long>,
): MaterializeResult {
    // ... existing find-or-create-playlist + library-track inserts unchanged ...

    // Discovery survivor re-link section (around lines 311-337).
    // v0.9.20: filter via excludeIds; over-fetch so post-filter still fills the cap.
    val discoveryCap = (recipe.targetLength * recipe.discoveryRatio)
        .roundToInt()
        .coerceAtLeast(0)
    val librarySet = tracks.mapTo(HashSet(tracks.size)) { it.id }
    val rawCandidateIds = discoveryQueueDao
        .getDoneTrackIdsForRecipe(recipe.id, limit = discoveryCap + excludeIds.size)
    val filteredCandidateIds = rawCandidateIds
        .filter { it !in librarySet && it !in excludeIds }
        .take(discoveryCap)
    val discoveryTrackIds = buildList {
        for (trackId in filteredCandidateIds) {
            if (!blocklistGuard.isBlockedByTrackId(trackId)) add(trackId)
        }
    }
    discoveryTrackIds.forEachIndexed { offset, trackId ->
        playlistDao.insertCrossRef(
            PlaylistTrackCrossRef(
                playlistId = playlistId,
                trackId = trackId,
                position = tracks.size + offset,
                addedAt = nowInstant,
            )
        )
    }
    val totalCount = tracks.size + discoveryTrackIds.size

    // ... existing cover-art update + updateTrackCount unchanged ...

    return MaterializeResult(playlistId, discoveryTrackIds)
}
```

Make sure to update the local variable name `candidateIds` everywhere it's referenced — the existing block uses `candidateIds`; we now have `rawCandidateIds` and `filteredCandidateIds`. The KDoc comment block about v0.9.15 blocklist filter / v0.9.19 cap (lines 305-316) should be preserved; add a paragraph noting the v0.9.20 over-fetch.

- [ ] **Step 6: Rewrite `doWork`'s recipe iteration block**

In `StashMixRefreshWorker.doWork`, the iteration currently at lines 219-242 (`for (recipe in active) { ... }`) needs to:
- Pre-sort by `recipeDedupPriority`.
- Thread a mutable `excludeIds` set through `generate` + `materializeMix`.
- Accumulate after each.

Replace the existing iteration with:

```kotlin
val orderedRecipes = active.sortedBy { recipeDedupPriority(it) }
val excludeIds = mutableSetOf<Long>()
for (recipe in orderedRecipes) {
    val tracks = mixGenerator.generate(recipe, excludeIds)

    // Empty-tracks skip is only safe for library-only recipes
    // (discoveryRatio == 0). Pure-discovery recipes like "Stash
    // Discover" (ratio = 1.0) produce an empty generator result
    // by design — the playlist gets its content from the
    // discovery re-link pass inside materializeMix + the async
    // StashDiscoveryWorker. Skipping them here would keep stale
    // pre-retune content in the playlist forever.
    if (tracks.isEmpty() && recipe.discoveryRatio == 0f) {
        Log.d(TAG, "'${recipe.name}' produced 0 tracks and has no discovery — skipping materialize")
        continue
    }

    val result = materializeMix(recipe, tracks, now, excludeIds)
    recipeDao.setPlaylistId(recipe.id, result.playlistId)
    recipeDao.setLastRefreshedAt(recipe.id, now)

    // v0.9.20: accumulate so the next recipe in the ordering doesn't re-pick these.
    excludeIds += tracks.map { it.id }
    excludeIds += result.discoveryIds

    // Discovery — opportunistic. Don't block refresh success on it.
    if (recipe.discoveryRatio > 0f && lastFmConfigured) {
        runCatching { queueDiscoveryForRecipe(recipe, personas) }
            .onFailure { Log.w(TAG, "discovery queueing failed for '${recipe.name}'", it) }
    }
}
```

Preserve the existing surrounding code — persona-fetch block above (lines 199-217), `return Result.success()` at line 243 below.

- [ ] **Step 7: Run the dedup test**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.workers.StashMixRefreshWorkerDedupTest"
```

Expected: 1/1 pass.

- [ ] **Step 8: Run the full :core:data module test sweep**

```bash
./gradlew :core:data:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. Any pre-existing failures noted in PR 1 (e.g., `YtLibraryCanonicalizerTest`) stay — they're not in this module anyway.

- [ ] **Step 9: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt \
        core/data/src/test/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorkerDedupTest.kt
git commit -m "feat(mix): cross-mix dedup orchestration in StashMixRefreshWorker

materializeMix now returns MaterializeResult(playlistId, discoveryIds)
and accepts excludeIds. doWork pre-sorts active recipes by dedup
priority (First Listen → Deep Cuts → Daily Discover → others), threads
a mutable Set<Long> through generate + materializeMix, and accumulates
both library and discovery-survivor ids after each iteration so the
next recipe can't re-pick them.

Discovery survivor query over-fetches discoveryCap + excludeIds.size
rows then post-filters and .take(discoveryCap) — guarantees the
discovery slot fills even when many ids are excluded.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Retune builtin recipe defaults

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/mix/StashMixDefaults.kt`

This is data-only — no new tests (the DAO + worker tests from Tasks 1 and 4 cover the behavior; Task 6's migration test exercises the migration that ships these values to existing installs).

- [ ] **Step 1: Replace the three builtin entries**

Open `StashMixDefaults.kt`. The current `ALL` list has three entries. Replace Daily Discover and Deep Cuts; keep First Listen unchanged. Result:

```kotlin
val ALL: List<StashMixRecipeEntity> = listOf(
    StashMixRecipeEntity(
        name = "Daily Discover",
        description = "Mostly fresh finds via similar-artists. A pinch of your library.",
        affinityBias = 0.0f,
        freshnessWindowDays = 7,
        discoveryRatio = 0.85f,
        targetLength = 40,
        seedStrategy = "ARTIST_SIMILAR",
        isBuiltin = true,
    ),
    StashMixRecipeEntity(
        name = "Deep Cuts",
        description = "Mostly fresh finds via similar-tracks. A pinch of your library.",
        affinityBias = 0.0f,
        freshnessWindowDays = 90,
        discoveryRatio = 0.85f,
        targetLength = 40,
        seedStrategy = "TRACK_SIMILAR",
        isBuiltin = true,
    ),
    StashMixRecipeEntity(
        name = "First Listen",
        description = "Tracks you've never heard. Wider net.",
        affinityBias = 0.0f,
        freshnessWindowDays = 14,
        discoveryRatio = 1.0f,
        targetLength = 50,
        seedStrategy = "TAG_GRAPH",
        isBuiltin = true,
    ),
)
```

Also update the file's top-level KDoc to reflect the new design intent (the existing one mentions ARTIST_SIMILAR / NONE / TAG_GRAPH and the "forgotten favorites" semantic for Deep Cuts — replace with the new recommendation-driven semantic):

```kotlin
/**
 * Ships Stash's built-in mix recipes. As of v0.9.20, all three builtins
 * are recommendation-substrate-with-library-seasoning — Daily Discover
 * and Deep Cuts at 85% discovery, First Listen at 100% discovery. Each
 * uses a distinct [com.stash.core.data.mix.MixSeedStrategy] so their
 * discovery-survivor pools are naturally differentiated:
 *
 *  - **Daily Discover** — ARTIST_SIMILAR; recommendations from artists
 *    similar to your top artists.
 *  - **Deep Cuts** — TRACK_SIMILAR; recommendations from tracks similar
 *    to your top tracks (deeper-dive flavor).
 *  - **First Listen** — TAG_GRAPH; top tracks across your taste tags
 *    (widest net).
 *
 * Only seeds when [StashMixRecipeDao.countBuiltins] is zero, so users
 * don't get defaults re-inserted every launch. Upgrades from earlier
 * installs that already have the previous builtin set go through
 * `StashApplication.maybeRetuneStashMixes` which non-destructively
 * updates the rows in place via [StashMixRecipeDao.retuneBuiltin].
 */
```

- [ ] **Step 2: Build to verify**

```bash
./gradlew :core:data:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the :core:data module test sweep**

```bash
./gradlew :core:data:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. Any test that hard-codes the OLD Daily Discover or Deep Cuts values would fail — flag if so; we'd need to update that test.

- [ ] **Step 4: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/mix/StashMixDefaults.kt
git commit -m "feat(mix): retune Daily Discover + Deep Cuts to 85% discovery

Daily Discover: ratio 0.4 -> 0.85, affinityBias 0.3 -> 0.0,
targetLength 50 -> 40. Stays ARTIST_SIMILAR.

Deep Cuts: ratio 0.0 -> 0.85, affinityBias 0.6 -> 0.0,
targetLength 50 -> 40. seedStrategy NONE -> TRACK_SIMILAR
(drops library-only rediscovery use case; user explicitly chose the
recommendations-first design).

First Listen unchanged. Fresh installs get the new defaults on first
seed; existing installs get them via the v0.9.20 retune migration
shipped in the next commit.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Version-gated migration hook in `StashApplication`

**Files:**
- Modify: `app/src/main/kotlin/com/stash/app/StashApplication.kt`

Mirror the existing `maybeRetuneStashDiscover` precedent verbatim. No new unit test — the DAO behavior is covered by Task 1's `StashMixRecipeDaoRetuneTest`; this hook is plumbing verified by the manual on-device test.

- [ ] **Step 1: Add the version constant**

In `StashApplication.kt`, the companion object holds existing version constants (around line 493). Add:

```kotlin
private const val STASH_MIX_RECIPE_TUNING_VERSION = 1
```

Next to `STASH_DISCOVER_TUNING_VERSION`.

- [ ] **Step 2: Add the migration method**

After `maybeRetuneStashDiscover` (at line 355), add:

```kotlin
/**
 * v0.9.20 pivot: Daily Discover + Deep Cuts move from library-substrate
 * to recommendation-substrate (85% discovery, 15% library). Deep Cuts
 * switches seed strategy from NONE to TRACK_SIMILAR. Gated by
 * [STASH_MIX_RECIPE_TUNING_VERSION] so the migration runs exactly once
 * per install. Fresh installs skip this because [StashMixDefaults]
 * already seeds with the new values.
 */
private suspend fun maybeRetuneStashMixes() {
    val prefs = getSharedPreferences("stash_migrations", MODE_PRIVATE)
    val stored = prefs.getInt("stash_mix_recipe_tuning_version", 0)
    if (stored >= STASH_MIX_RECIPE_TUNING_VERSION) return

    var totalUpdated = 0
    for (recipe in StashMixDefaults.ALL.filter { it.isBuiltin }) {
        val updated = stashMixRecipeDao.retuneBuiltin(
            name = recipe.name,
            discoveryRatio = recipe.discoveryRatio,
            freshnessWindowDays = recipe.freshnessWindowDays,
            targetLength = recipe.targetLength,
            affinityBias = recipe.affinityBias,
            seedStrategy = recipe.seedStrategy,
        )
        totalUpdated += updated
    }
    Log.i(
        "StashMigration",
        "Retuned $totalUpdated builtin mix recipe(s) to v$STASH_MIX_RECIPE_TUNING_VERSION",
    )
    prefs.edit()
        .putInt("stash_mix_recipe_tuning_version", STASH_MIX_RECIPE_TUNING_VERSION)
        .apply()
}
```

- [ ] **Step 3: Wire into the startup sequence**

In `StashApplication.kt`, the `applicationScope.launch { ... }` block at lines 168-176 contains the existing migrations. Add the new call between `maybeRetuneStashDiscover()` and the `StashMixRefreshWorker.enqueueOneTime` call:

```kotlin
applicationScope.launch {
    maybeReseedStashMixes()
    StashMixDefaults.seedIfNeeded(stashMixRecipeDao)
    maybeRetuneStashDiscover()
    maybeRetuneStashMixes()  // NEW
    StashMixRefreshWorker.enqueueOneTime(this@StashApplication)
}
```

Order matters: `seedIfNeeded` first (for fresh installs), then both retune migrations (for upgrades), then enqueue the one-shot refresh which will materialize against the (now-retuned) recipes.

- [ ] **Step 4: Build to verify**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the :app module test sweep (if any)**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL or UP-TO-DATE.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/stash/app/StashApplication.kt
git commit -m "feat(mix): version-gated retune migration for the recipe pivot

Mirrors the existing maybeRetuneStashDiscover precedent — separate
SharedPreferences key (stash_mix_recipe_tuning_version), idempotent,
runs once per upgrade. Iterates StashMixDefaults.ALL.filter { isBuiltin }
and applies retuneBuiltin to each. Fresh installs skip this because
seedIfNeeded already provides the new defaults.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: Build APK + install + manual on-device verification

**Files:** none — verification task.

- [ ] **Step 1: Full project build**

```bash
./gradlew :app:assembleRelease
```

Expected: BUILD SUCCESSFUL. (If `packageRelease` fails transiently with `IncrementalSplitterRunnable` — observed during PR 1's Task 7 — re-run `./gradlew :app:packageRelease`; second run usually succeeds.)

- [ ] **Step 2: Install over the existing release app on Pixel**

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Expected: `Success`. Library, settings, downloads preserved (same package id, `com.stash.app`).

- [ ] **Step 3: Cold-start the app to trigger `maybeRetuneStashMixes`**

```bash
adb shell monkey -p com.stash.app -c android.intent.category.LAUNCHER 1
```

Wait ~3 seconds.

- [ ] **Step 4: Verify migration ran**

```bash
adb logcat -d -t 200 | grep StashMigration
```

Expected: a log line like `Retuned 3 builtin mix recipe(s) to v1`.

- [ ] **Step 5: Trigger a refresh of each mix on-device**

On the Pixel:
1. Long-press each Stash Mix card (Daily Discover, Deep Cuts, First Listen) → tap "Refresh this mix" (the snackbar lifecycle from PR 1 will confirm).
2. Watch the Home screen — each mix should show updated track counts and covers.

- [ ] **Step 6: Verify the recommendation-first composition**

Tap each mix to view its track list. For Daily Discover and Deep Cuts:
- Most tracks (~85%) should be ones you don't recognize from your usual rotation — they're discovery survivors from the Last.fm pipeline.
- A small slice (~15%) should be your own library tracks.
- For the first refresh post-upgrade, Deep Cuts may be sparse (~6 tracks) because TRACK_SIMILAR discovery_queue rows haven't accumulated yet. This is expected (failure mode #1 in spec). Plug in to charge + WiFi and wait for `StashDiscoveryWorker` to fill the queue (next periodic run; can also be triggered by the app's existing nightly schedule). Refresh Deep Cuts again afterwards and verify it's now ~40 tracks.

- [ ] **Step 7: Verify cross-mix dedup**

Sample 10 track titles from Daily Discover and 10 from Deep Cuts. Expect zero overlap.

Optional: use ADB to query the DB directly for a hard verification:
```bash
adb shell run-as com.stash.app sqlite3 \
  /data/data/com.stash.app/databases/stash.db \
  "SELECT t.title, t.artist, p.name FROM tracks t \
   JOIN playlist_tracks pt ON pt.track_id = t.id \
   JOIN playlists p ON p.id = pt.playlist_id \
   WHERE p.type = 'STASH_MIX' \
   ORDER BY t.id, p.name;"
```
A row with the same `track_id` appearing under two playlist names = dedup failure. Otherwise pass.

- [ ] **Step 8: Report back**

When the manual checks pass, summarize:
- Commits that landed on the branch
- APK installed on the Pixel
- Migration log line observed
- Manual checks that passed (with caveat about Deep Cuts sparseness on first refresh)
- Any deferred/skipped items

Do not push or tag. Per memory `feedback_ship_terminology.md`, release happens separately after verification.

---

## What's intentionally not in this plan

Following YAGNI:

- **No pre-seeding `discovery_queue` inline on retune.** Failure mode #1 acknowledged — Deep Cuts is sparse for a day. The user explicitly chose this trade-off.
- **No new "Rediscovery" mix** to replace the dropped Deep Cuts semantic. Per brainstorm decision.
- **No edits to `MixSeedGenerator`** — TRACK_SIMILAR is already implemented and tested.
- **No "if a user edited the builtin, skip retune" logic** — the recipe-editing UI doesn't ship yet. When it does, that guard becomes urgent.
- **No new schema migration** — schema v22 stays; only row content + DAO query change.
- **No automated UI test for the snackbar lifecycle** — covered by manual on-device verification.
