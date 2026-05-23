# v0.9.36 Lyrics Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Every subagent dispatch must use `model: "opus"`** (project convention from `feedback_subagent_model.md`).

**Goal:** Add lyrics integration to Stash. LRCLIB-primary + InnerTube fallback fetches `synced` and `plain` lyrics, persists to a new Room `lyrics` table, writes a sidecar `.lrc` next to every successfully fetched audio file, surfaces a Now Playing `ModalBottomSheet` with auto-follow + tap-to-seek, and one-shot backfills the existing library on first launch after upgrade.

**Architecture:** New `:data:lyrics` Gradle module hosts `LyricsRepository` and the source chain (`LrclibLyricsSource` → `YtMusicLyricsSource`). Room v27 → v28 adds `tracks.lyrics_fetched_at` (NULL / 0L / epoch-millis sentinel mirroring v0.9.35) plus a new `lyrics` table with FK cascade. `LyricsFetchWorker` enqueues per-track on download success (via a new `LyricsFetchTrigger` interface in `:data:download`) and on user open of the sheet (priority). `LyricsBackfillWorker` mirrors `MetadataBackfillWorker` exactly (OFFSET=0 + stamp-removes-from-result-set). `BackfillVersionTracker` is generalised to take a key parameter so both metadata and lyrics keys can live in the existing DataStore file. `LyricsBottomSheet` in `:feature:nowplaying` (parallels `QueueBottomSheet`) renders synced LRC with auto-scroll + tap-to-seek; loading / instrumental / none / retry states all built-in. A new Home banner field on `HomeUiState` surfaces backfill progress, parallel to `metadataBackfillBanner`.

**Tech Stack:** Kotlin / Hilt / Room / WorkManager / OkHttp / DataStore (Preferences) / Jetpack Compose / Media3 / kotlinx-serialization. Tests use JUnit4 + mockk + kotlinx-coroutines-test + Robolectric (DataStore + Worker tests) + MockWebServer (LRCLIB).

**Spec:** [docs/superpowers/specs/2026-05-23-lyrics-integration-design.md](../specs/2026-05-23-lyrics-integration-design.md)

---

## File Map

### Created

**Module `:data:lyrics`** (new module — sibling of `:data:download`):
- `data/lyrics/build.gradle.kts` — module Gradle config
- `data/lyrics/src/main/AndroidManifest.xml` — empty manifest with package
- `data/lyrics/src/main/kotlin/com/stash/data/lyrics/source/LyricsSource.kt` — interface + `LyricsQuery` + `LyricsResult` data classes
- `data/lyrics/src/main/kotlin/com/stash/data/lyrics/source/LrclibLyricsSource.kt`
- `data/lyrics/src/main/kotlin/com/stash/data/lyrics/source/YtMusicLyricsSource.kt`
- `data/lyrics/src/main/kotlin/com/stash/data/lyrics/source/LrclibApi.kt` — Retrofit-style internal API surface (or direct OkHttp; implementer's choice — see Task 4)
- `data/lyrics/src/main/kotlin/com/stash/data/lyrics/parser/LrcParser.kt` + `LrcLine`
- `data/lyrics/src/main/kotlin/com/stash/data/lyrics/LyricsRepository.kt`
- `data/lyrics/src/main/kotlin/com/stash/data/lyrics/sidecar/LyricsSidecarWriter.kt`
- `data/lyrics/src/main/kotlin/com/stash/data/lyrics/worker/LyricsFetchWorker.kt`
- `data/lyrics/src/main/kotlin/com/stash/data/lyrics/worker/LyricsBackfillWorker.kt`
- `data/lyrics/src/main/kotlin/com/stash/data/lyrics/backfill/LyricsBackfillState.kt` (+ inline `enum class State`)
- `data/lyrics/src/main/kotlin/com/stash/data/lyrics/backfill/LyricsBackfillScheduler.kt`
- `data/lyrics/src/main/kotlin/com/stash/data/lyrics/di/LyricsModule.kt` — Hilt module: sources multibinding, DataStore, repository
- `data/lyrics/src/test/kotlin/com/stash/data/lyrics/parser/LrcParserTest.kt`
- `data/lyrics/src/test/kotlin/com/stash/data/lyrics/source/LrclibLyricsSourceTest.kt`
- `data/lyrics/src/test/kotlin/com/stash/data/lyrics/source/YtMusicLyricsSourceTest.kt`
- `data/lyrics/src/test/kotlin/com/stash/data/lyrics/LyricsRepositoryTest.kt`
- `data/lyrics/src/test/kotlin/com/stash/data/lyrics/sidecar/LyricsSidecarWriterTest.kt`
- `data/lyrics/src/test/kotlin/com/stash/data/lyrics/worker/LyricsFetchWorkerTest.kt`
- `data/lyrics/src/test/kotlin/com/stash/data/lyrics/worker/LyricsBackfillWorkerTest.kt`
- `data/lyrics/src/test/kotlin/com/stash/data/lyrics/backfill/LyricsBackfillStateTest.kt`

**Module `:core:data`:**
- `core/data/src/main/kotlin/com/stash/core/data/db/entity/LyricsEntity.kt`
- `core/data/src/main/kotlin/com/stash/core/data/db/dao/LyricsDao.kt`
- `core/data/schemas/com.stash.core.data.db.StashDatabase/28.json` — auto-generated after Room compile

**Module `:data:download`:**
- `data/download/src/main/kotlin/com/stash/data/download/lyrics/LyricsFetchTrigger.kt` — empty interface, no impl in this module

**Module `:feature:home`:**
- `feature/home/src/main/kotlin/com/stash/feature/home/banner/LyricsBackfillBannerState.kt`
- `feature/home/src/main/kotlin/com/stash/feature/home/banner/LyricsBackfillBanner.kt`
- `feature/home/src/test/kotlin/com/stash/feature/home/banner/LyricsBackfillBannerStateTest.kt`

**Module `:feature:nowplaying`:**
- `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/ui/LyricsBottomSheet.kt`
- `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/ui/LyricsView.kt`
- `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/ui/LyricsViewState.kt`
- `feature/nowplaying/src/test/kotlin/com/stash/feature/nowplaying/ui/LyricsViewStateTest.kt`

**Module `:app`:**
- `app/src/main/kotlin/com/stash/app/di/LyricsFetchTriggerModule.kt` — Hilt binding for `LyricsFetchTrigger` → `LyricsFetchWorker.enqueue`

### Modified

- `settings.gradle.kts` — `include(":data:lyrics")`
- `core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt` — add `lyricsFetchedAt: Long? = null`
- `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt` — bump `version = 27` → `28`, add `MIGRATION_27_28`, register entity
- `core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt` — register `MIGRATION_27_28` in `addMigrations(...)`
- `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt` — add `setLyricsFetchedAt`, `getTracksNeedingLyrics`, `observeTracksNeedingLyricsCount`
- `core/model/src/main/kotlin/com/stash/core/model/Track.kt` — add `lyricsFetchedAt: Long? = null` field
- `core/data/src/main/kotlin/com/stash/core/data/mapper/TrackMapper.kt` — propagate `lyricsFetchedAt` in both directions
- `data/download/src/main/kotlin/com/stash/data/download/backfill/BackfillVersionTracker.kt` — generalise: `shouldRunForCurrentVersion(key: String)`, `markEnqueuedForCurrentVersion(key: String)`. Existing key constant stays for metadata.
- `data/download/src/main/kotlin/com/stash/data/download/backfill/MetadataBackfillScheduler.kt` — pass the metadata key explicitly into the now-keyed tracker calls
- `data/download/src/main/kotlin/com/stash/data/download/DownloadManager.kt` — `lyricsFetchTrigger.enqueueFor(track.id)` after both `setMetadataEmbeddedAt` stamp sites (lossless completion + alternate lossless completion)
- `data/download/src/main/kotlin/com/stash/data/download/search/SearchDownloadCoordinator.kt` — `lyricsFetchTrigger.enqueueFor(track.id)` after `stampEmbeddedAt` (refactor `stampEmbeddedAt` to return trackId so the lookup isn't duplicated)
- `data/download/src/main/kotlin/com/stash/data/download/files/FileOrganizer.kt` — expose `internal object FileOrganizerSlugs` (or `internal fun slugify(...)`) so `LyricsSidecarWriter` can derive SAF paths
- `data/download/build.gradle.kts` — add `:core:network` dependency only if not already present (verify)
- `app/src/main/kotlin/com/stash/app/StashApplication.kt` — call `lyricsBackfillScheduler.scheduleIfNeeded()` from `onCreate` alongside the metadata scheduler call
- `feature/home/src/main/kotlin/com/stash/feature/home/HomeUiState.kt` — add `lyricsBackfillBanner: LyricsBackfillBannerState = Hidden`
- `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt` — combine `lyricsBackfillState.snapshot` into the assembly
- `feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt` — render `LyricsBackfillBanner` under the existing metadata banner
- `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt` — add Lyrics IconButton in TopBar between Save and Queue; render `LyricsBottomSheet` when the new sheet flag is true
- `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingViewModel.kt` — add `onShowLyrics`, `onDismissLyrics`, `onLyricsRetry`, `onLyricsLineSeek(timestampMs)`, expose `lyricsViewState: StateFlow<LyricsViewState>` + `currentPositionMs: StateFlow<Long>`
- `app/build.gradle.kts` — bump `versionCode` and `versionName` to `0.9.36` (after all tasks pass)

---

## Conventions

- **TDD throughout.** Failing test → run it → minimal implementation → run it → commit. Watch the failure for the right reason; don't skip the failing-test step.
- **One commit per task** unless the task explicitly says otherwise. Commit messages follow `type(scope): summary` and include the trailer:
  ```
  Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
  ```
- **Every subagent dispatch uses `model: "opus"`.** Per `feedback_subagent_model`.
- **`:app:installDebug` after every UI-touching task** (Tasks 11, 12, 13). Per `feedback_install_after_fix`, compile-pass alone is not enough on this project.
- **"Ship" = tag + push, not local install.** Per `feedback_ship_terminology`. This plan stops at the on-device validation step (Task 14). The user gates the final tag.
- **Worktree-local `local.properties`.** `git worktree add` does not copy `local.properties`; copy manually before first Gradle build (per `feedback_worktree_local_properties.md`) or Last.fm shows "Not configured" in debug builds.
- **No scope creep.** This plan does NOT add: manual lyric search/edit, sidecar **read**, KuGou source, word-level highlighting, AI translation, karaoke mode, USLT/SYLT embedding, lyrics share/export. All listed in spec §"Out of scope (Phase 2 and beyond)".
- **Preserve existing design** (per `feedback_preserve_existing_design.md`). Only TopBar change is the addition of one IconButton between Save and Queue. No existing icon moves; no restructure.
- **Pre-existing master test failures** noted in prior plans still apply — ignore them in the test sweep at Task 14.

---

## Worktree setup (do this once, before Task 1)

- [ ] **Step 1: Confirm spec is on master**

```bash
cd /c/Users/theno/Projects/MP3APK
git log --oneline -3 master
```

Expected: top commit is the advisory-note patch `3f077c5 docs(lyrics): address spec-reviewer advisory notes` (or the brainstorming-session commit that supersedes it).

- [ ] **Step 2: Survey existing worktrees**

Per `feedback_check_worktrees_before_release.md`, in-flight WIP can live in `.worktrees/<name>`. Confirm there's no `lyrics-*` worktree already in flight:

```bash
git worktree list
```

Expected: at most `metadata-embedding` (the already-merged v0.9.35 worktree) and possibly other unrelated stale ones. No `lyrics-integration`.

- [ ] **Step 3: Create the worktree from current master**

```bash
git fetch origin
git worktree add .worktrees/lyrics-integration -b feat/lyrics-integration origin/master
```

- [ ] **Step 4: Copy `local.properties` into the worktree**

```bash
cp local.properties .worktrees/lyrics-integration/local.properties
```

- [ ] **Step 5: cd into the worktree**

All subsequent paths in this plan are relative to the worktree root.

```bash
cd .worktrees/lyrics-integration
```

- [ ] **Step 6: Sanity build**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. If it fails on this clean baseline, surface to the user before starting Task 1.

---

# Phase 1 — Foundation (Room schema + module skeleton + tracker generalisation)

Phase end state: Room is at v28 with the new column + table + DAO, the empty `:data:lyrics` module compiles, and `BackfillVersionTracker` accepts a key parameter so both metadata and lyrics can share the file. No new behaviour yet — Phase 2-6 wires it up.

Tasks 1, 2, and 3 are independent and parallel-safe.

---

## Task 1: Room v27 → v28 — `tracks.lyrics_fetched_at` column + `lyrics` table + DAO

**Why this is small:** One migration, one new entity, one new DAO, three new TrackDao queries. Largest source of accidental complexity is the migration test — write it FIRST.

**Files:**
- Modify: `core/model/src/main/kotlin/com/stash/core/model/Track.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/mapper/TrackMapper.kt`
- Create: `core/data/src/main/kotlin/com/stash/core/data/db/entity/LyricsEntity.kt`
- Create: `core/data/src/main/kotlin/com/stash/core/data/db/dao/LyricsDao.kt`
- Test: `core/data/src/androidTest/kotlin/com/stash/core/data/db/Migration27To28Test.kt` (or wherever the existing migration tests live; mirror)
- Auto-generated: `core/data/schemas/com.stash.core.data.db.StashDatabase/28.json`

- [ ] **Step 1: Locate the existing migration-test pattern**

```bash
grep -rn "MigrationTestHelper" core/data/src --include="*.kt" | head
```

Expected: an existing `MigrationXToYTest.kt` next to the v0.9.35 work (e.g. `Migration26To27Test.kt`). Open it; the new test mirrors that exactly — same package, same helper, same setUp.

- [ ] **Step 2: Write the failing migration test**

`core/data/src/androidTest/kotlin/com/stash/core/data/db/Migration27To28Test.kt` (mirror the existing v26→v27 test, adjusting versions):

```kotlin
package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration27To28Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate27To28_addsLyricsFetchedAtColumn_andCreatesLyricsTable() {
        // create v27 schema (empty), close, run migration, open v28, assert
        var db = helper.createDatabase(TEST_DB, 27).apply {
            execSQL("INSERT INTO tracks (id, title, artist, album, album_artist, metadata_embedded_at) " +
                    "VALUES ('t1', 'T', 'A', 'AL', 'AA', NULL)")
            close()
        }
        db = helper.runMigrationsAndValidate(TEST_DB, 28, true, StashDatabase.MIGRATION_27_28)
        // verify lyrics_fetched_at column exists and is NULL for the preserved row
        db.query("SELECT lyrics_fetched_at FROM tracks WHERE id = 't1'").use { c ->
            assert(c.moveToFirst())
            assert(c.isNull(0))
        }
        // verify lyrics table exists and is empty
        db.query("SELECT COUNT(*) FROM lyrics").use { c ->
            assert(c.moveToFirst())
            assert(c.getInt(0) == 0)
        }
        db.close()
    }

    private companion object { const val TEST_DB = "migration-test-27-28" }
}
```

- [ ] **Step 3: Run the test — verify it fails**

```bash
./gradlew :core:data:connectedAndroidTest --tests "*Migration27To28Test*"
```

Expected: FAIL with `Unresolved reference: MIGRATION_27_28`. (If the project doesn't easily run instrumented tests in CI, the implementer can swap to a JVM-based Room `MigrationTestHelper` pattern; mirror whatever `Migration26To27Test.kt` does.)

- [ ] **Step 4: Add `LyricsEntity`**

`core/data/src/main/kotlin/com/stash/core/data/db/entity/LyricsEntity.kt`:

```kotlin
package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
    @PrimaryKey @ColumnInfo("track_id")       val trackId: String,
    @ColumnInfo("plain_text")                  val plainText: String?,
    @ColumnInfo("synced_lrc")                  val syncedLrc: String?,
    @ColumnInfo("instrumental")                val instrumental: Boolean,
    @ColumnInfo("language")                    val language: String?,
    @ColumnInfo("source")                      val source: String,
    @ColumnInfo("source_lyrics_id")            val sourceLyricsId: String?,
    @ColumnInfo("fetched_at")                  val fetchedAt: Long,
)
```

- [ ] **Step 5: Add `lyricsFetchedAt` to `TrackEntity`**

In `core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt`, add (alongside `metadataEmbeddedAt`):

```kotlin
@ColumnInfo("lyrics_fetched_at")
val lyricsFetchedAt: Long? = null,
```

- [ ] **Step 6: Add `lyricsFetchedAt` to `core/model/Track.kt`**

Append to the constructor parameter list:

```kotlin
/**
 * Epoch-millis of the most recent lyrics fetch attempt that produced a result.
 * NULL = never tried; 0L = tried, none available; non-zero = success epoch-millis.
 * Mirrors `tracks.lyrics_fetched_at` (v27 → v28 migration).
 */
val lyricsFetchedAt: Long? = null,
```

- [ ] **Step 7: Propagate through `TrackMapper`**

In `core/data/src/main/kotlin/com/stash/core/data/mapper/TrackMapper.kt`, add `lyricsFetchedAt = lyricsFetchedAt` in both directions (entity → domain and domain → entity).

- [ ] **Step 8: Add `MIGRATION_27_28` and bump version**

In `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt`:

- Bump `version = 27` to `version = 28`.
- Append `LyricsEntity::class` to the `entities = [...]` array on `@Database`.
- Add inside the companion object (next to `MIGRATION_26_27`):

```kotlin
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN lyrics_fetched_at INTEGER")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS lyrics (
              track_id TEXT NOT NULL PRIMARY KEY,
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

- [ ] **Step 9: Register the migration in `DatabaseModule`**

In `core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt`, find the `Room.databaseBuilder(...).addMigrations(MIGRATION_26_27)` call and add `, MIGRATION_27_28`.

- [ ] **Step 10: Add `LyricsDao`**

`core/data/src/main/kotlin/com/stash/core/data/db/dao/LyricsDao.kt`:

```kotlin
package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stash.core.data.db.entity.LyricsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE track_id = :trackId")
    suspend fun get(trackId: String): LyricsEntity?

    @Query("SELECT * FROM lyrics WHERE track_id = :trackId")
    fun observe(trackId: String): Flow<LyricsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LyricsEntity)

    @Query("DELETE FROM lyrics WHERE track_id = :trackId")
    suspend fun delete(trackId: String)
}
```

Wire `LyricsDao` into `StashDatabase` via `abstract fun lyricsDao(): LyricsDao`.

- [ ] **Step 11: Add new `TrackDao` queries**

In `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt`, mirror the metadata trio (which is in the same file):

```kotlin
@Query("UPDATE tracks SET lyrics_fetched_at = :ts WHERE id = :trackId")
suspend fun setLyricsFetchedAt(trackId: String, ts: Long)

@Query("SELECT * FROM tracks WHERE lyrics_fetched_at IS NULL ORDER BY id LIMIT :limit")
suspend fun getTracksNeedingLyrics(limit: Int): List<TrackEntity>

@Query("SELECT COUNT(*) FROM tracks WHERE lyrics_fetched_at IS NULL")
fun observeTracksNeedingLyricsCount(): Flow<Int>
```

- [ ] **Step 12: Run KSP — schema JSON should auto-generate**

```bash
./gradlew :core:data:assembleDebug
```

Expected: BUILD SUCCESSFUL. The file `core/data/schemas/com.stash.core.data.db.StashDatabase/28.json` should now exist.

- [ ] **Step 13: Run the migration test — verify it passes**

```bash
./gradlew :core:data:connectedAndroidTest --tests "*Migration27To28Test*"
```

Expected: PASS.

- [ ] **Step 14: Confirm all `:core:data` tests still pass**

```bash
./gradlew :core:data:test :core:data:connectedAndroidTest
```

Expected: PASS (or fail only on pre-existing failures).

- [ ] **Step 15: Commit**

```bash
git add core/model/src/main/kotlin/com/stash/core/model/Track.kt \
        core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt \
        core/data/src/main/kotlin/com/stash/core/data/db/entity/LyricsEntity.kt \
        core/data/src/main/kotlin/com/stash/core/data/db/dao/LyricsDao.kt \
        core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt \
        core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt \
        core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt \
        core/data/src/main/kotlin/com/stash/core/data/mapper/TrackMapper.kt \
        core/data/schemas/com.stash.core.data.db.StashDatabase/28.json \
        core/data/src/androidTest/kotlin/com/stash/core/data/db/Migration27To28Test.kt
git commit -m "$(cat <<'EOF'
feat(data): room v27 → v28 — lyrics_fetched_at column + lyrics table

Adds the v0.9.36 lyrics integration's data layer:
- tracks.lyrics_fetched_at: NULL/0L/epoch-millis sentinel mirroring
  metadata_embedded_at semantics (v0.9.35).
- new lyrics table keyed by track_id with FK cascade.
- LyricsDao + TrackDao additions for the backfill drain pattern.
- Track domain field + mapper propagation.
- migration test validates row preservation + table creation.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `:data:lyrics` module skeleton + `LrcParser`

**Why this is small:** New Gradle module with the bare minimum to compile, plus the one pure utility (LRC parser) that has no dependencies. Parser is a great TDD target — many edge cases.

**Files:**
- Modify: `settings.gradle.kts`
- Create: `data/lyrics/build.gradle.kts`
- Create: `data/lyrics/src/main/AndroidManifest.xml`
- Create: `data/lyrics/src/main/kotlin/com/stash/data/lyrics/source/LyricsSource.kt` (interface stub + data classes)
- Create: `data/lyrics/src/main/kotlin/com/stash/data/lyrics/parser/LrcParser.kt`
- Test: `data/lyrics/src/test/kotlin/com/stash/data/lyrics/parser/LrcParserTest.kt`

- [ ] **Step 1: Inspect the sibling `:data:download` Gradle config as a template**

```bash
cat data/download/build.gradle.kts
```

Note its `plugins { ... }` block, `android { namespace = ... }`, and `dependencies { ... }`. The new `:data:lyrics` mirrors this structure.

- [ ] **Step 2: Add the module to `settings.gradle.kts`**

Add `include(":data:lyrics")` alongside the existing `include(":data:download")`.

- [ ] **Step 3: Create `data/lyrics/build.gradle.kts`**

Copy the `:data:download` Gradle file as a starting point. Strip dependencies that aren't needed and add the ones that are. Minimum dependencies for this module:

```kotlin
plugins {
    alias(libs.plugins.stash.android.library)
    alias(libs.plugins.stash.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.stash.data.lyrics"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":data:download"))   // FileOrganizerSlugs + LyricsFetchTrigger interface
    implementation(project(":data:innertube"))  // for YtMusicLyricsSource (Task 5)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.work.testing)
}
```

(Adjust to whatever the actual convention-plugin names are in `libs.versions.toml`. Mirror `:data:download` exactly when in doubt.)

- [ ] **Step 4: Create the manifest**

`data/lyrics/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 5: Stub `LyricsSource` + `LyricsQuery` + `LyricsResult`**

`data/lyrics/src/main/kotlin/com/stash/data/lyrics/source/LyricsSource.kt`:

```kotlin
package com.stash.data.lyrics.source

interface LyricsSource {
    val id: String
    val displayName: String
    suspend fun resolve(query: LyricsQuery): LyricsResult?
}

data class LyricsQuery(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val albumArtist: String?,
    val durationMs: Long?,
    val youtubeVideoId: String?,
)

data class LyricsResult(
    val sourceId: String,
    val plainText: String?,
    val syncedLrc: String?,
    val instrumental: Boolean,
    val language: String?,
    val sourceLyricsId: String?,
)
```

- [ ] **Step 6: Write the failing `LrcParser` test**

`data/lyrics/src/test/kotlin/com/stash/data/lyrics/parser/LrcParserTest.kt`:

```kotlin
package com.stash.data.lyrics.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test fun `parses single line with hundredths`() {
        val body = "[00:12.34]hello world"
        val lines = LrcParser.parse(body)
        assertEquals(1, lines.size)
        assertEquals(12_340L, lines[0].timestampMs)
        assertEquals("hello world", lines[0].text)
    }

    @Test fun `parses single line with milliseconds`() {
        val body = "[00:12.345]hello world"
        val lines = LrcParser.parse(body)
        assertEquals(1, lines.size)
        assertEquals(12_345L, lines[0].timestampMs)
    }

    @Test fun `sorts lines by timestamp`() {
        val body = "[01:00.00]b\n[00:00.00]a\n[00:30.00]middle"
        val lines = LrcParser.parse(body)
        assertEquals(listOf("a", "middle", "b"), lines.map { it.text })
    }

    @Test fun `expands multi-timestamp lines`() {
        val body = "[00:10.00][00:30.00]chorus"
        val lines = LrcParser.parse(body)
        assertEquals(2, lines.size)
        assertEquals(10_000L, lines[0].timestampMs)
        assertEquals(30_000L, lines[1].timestampMs)
        assertTrue(lines.all { it.text == "chorus" })
    }

    @Test fun `strips standard metadata tags`() {
        val body = "[ti:Title]\n[ar:Artist]\n[al:Album]\n[length:03:45]\n[by:Author]\n[offset:+0]\n[00:10.00]line"
        val lines = LrcParser.parse(body)
        assertEquals(1, lines.size)
        assertEquals("line", lines[0].text)
    }

    @Test fun `skips malformed lines without throwing`() {
        val body = "garbage\n[00:10.00]ok\nalso garbage\n[bad timestamp]nope\n[00:20.00]ok2"
        val lines = LrcParser.parse(body)
        assertEquals(listOf("ok", "ok2"), lines.map { it.text })
    }

    @Test fun `returns empty list for completely malformed input`() {
        assertTrue(LrcParser.parse("not lyrics at all").isEmpty())
        assertTrue(LrcParser.parse("").isEmpty())
    }

    @Test fun `trims trailing whitespace but preserves internal spacing`() {
        val body = "[00:10.00]  hello  world   "
        val lines = LrcParser.parse(body)
        assertEquals("  hello  world", lines[0].text)
    }
}
```

- [ ] **Step 7: Run the test — verify it fails**

```bash
./gradlew :data:lyrics:test --tests "*LrcParserTest*"
```

Expected: FAIL with `Unresolved reference: LrcParser`.

- [ ] **Step 8: Implement `LrcParser`**

`data/lyrics/src/main/kotlin/com/stash/data/lyrics/parser/LrcParser.kt`:

```kotlin
package com.stash.data.lyrics.parser

data class LrcLine(val timestampMs: Long, val text: String)

object LrcParser {

    /**
     * Parses an LRC body into a sorted, timestamp-tagged line list.
     *
     * Recognises:
     *   - `[mm:ss.xx]text` and `[mm:ss.xxx]text`
     *   - multiple timestamps per line: `[00:10.00][00:30.00]text` → two LrcLine entries
     *
     * Strips well-known metadata tags: `[ti:...] [ar:...] [al:...] [length:...] [by:...] [offset:...]`.
     * Skips malformed lines (logs nothing — they're expected from real LRCLIB bodies).
     */
    fun parse(body: String): List<LrcLine> {
        if (body.isBlank()) return emptyList()
        val out = mutableListOf<LrcLine>()
        for (rawLine in body.lineSequence()) {
            val line = rawLine.trimEnd()
            if (line.isBlank()) continue
            if (META_TAG.matches(line)) continue

            // Collect every leading [mm:ss.xx] timestamp; the text is whatever follows the last one.
            val timestamps = mutableListOf<Long>()
            var idx = 0
            while (idx < line.length && line[idx] == '[') {
                val close = line.indexOf(']', idx)
                if (close == -1) break
                val token = line.substring(idx + 1, close)
                val ms = parseTimestampMs(token) ?: break
                timestamps += ms
                idx = close + 1
            }
            if (timestamps.isEmpty()) continue
            val text = line.substring(idx).trimEnd()
            if (text.isBlank()) continue
            timestamps.forEach { ms -> out += LrcLine(ms, text) }
        }
        return out.sortedBy { it.timestampMs }
    }

    private val META_TAG = Regex("""^\[(ti|ar|al|length|by|offset|au|re|ve):.*]\s*$""", RegexOption.IGNORE_CASE)
    private val TIMESTAMP = Regex("""^(\d{1,2}):(\d{2})\.(\d{2,3})$""")

    private fun parseTimestampMs(token: String): Long? {
        val match = TIMESTAMP.matchEntire(token) ?: return null
        val minutes = match.groupValues[1].toLong()
        val seconds = match.groupValues[2].toLong()
        val frac = match.groupValues[3]
        val fracMs = when (frac.length) {
            2 -> frac.toLong() * 10
            3 -> frac.toLong()
            else -> return null
        }
        return (minutes * 60_000) + (seconds * 1_000) + fracMs
    }
}
```

- [ ] **Step 9: Run the parser test — verify it passes**

```bash
./gradlew :data:lyrics:test --tests "*LrcParserTest*"
```

Expected: PASS (all 8 tests).

- [ ] **Step 10: Build the full module to confirm it compiles**

```bash
./gradlew :data:lyrics:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 11: Run the broader test sweep — verify no other module broke**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 12: Commit**

```bash
git add settings.gradle.kts \
        data/lyrics/
git commit -m "$(cat <<'EOF'
feat(lyrics): :data:lyrics module skeleton + LrcParser

New Gradle module for v0.9.36 lyrics integration. Includes:
- LyricsSource interface + LyricsQuery + LyricsResult data classes
- LrcParser for [mm:ss.xx]text — multi-timestamp expansion,
  metadata-tag stripping, malformed-line skipping, sorted output
- 8 parser unit tests covering hundredths/millis precision,
  multi-timestamps, metadata tags, garbage tolerance

Source implementations come in Tasks 4-5; this task is just the
shape + the one pure utility.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Generalise `BackfillVersionTracker` with a key parameter

**Why this is small:** Two method-signature changes, plus update the one existing caller (`MetadataBackfillScheduler`). Pure mechanical refactor.

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/backfill/BackfillVersionTracker.kt`
- Modify: `data/download/src/main/kotlin/com/stash/data/download/backfill/MetadataBackfillScheduler.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/backfill/BackfillVersionTrackerTest.kt` (extend existing tests with a key-isolation case)

- [ ] **Step 1: Read the current `BackfillVersionTracker`**

```bash
cat data/download/src/main/kotlin/com/stash/data/download/backfill/BackfillVersionTracker.kt
```

Expected: methods `shouldRunForCurrentVersion()` and `markEnqueuedForCurrentVersion()` with a hard-coded key constant.

- [ ] **Step 2: Read the existing test file**

```bash
cat data/download/src/test/kotlin/com/stash/data/download/backfill/BackfillVersionTrackerTest.kt
```

Note the test pattern (Robolectric + DataStore isolation in `@Before` / `@After`).

- [ ] **Step 3: Write a new failing test — two keys are isolated**

Add to `BackfillVersionTrackerTest.kt`:

```kotlin
@Test
fun `two distinct keys do not interfere`() = runTest {
    val tracker = BackfillVersionTracker(context, fakeAppVersion(versionCode = 100))

    // Mark metadata enqueued for current version
    tracker.markEnqueuedForCurrentVersion("backfill_enqueued_for_version")
    assertFalse(tracker.shouldRunForCurrentVersion("backfill_enqueued_for_version"))

    // Lyrics key should still report should-run
    assertTrue(tracker.shouldRunForCurrentVersion("lyrics_backfill_enqueued_for_version"))

    // Mark lyrics; metadata stays marked
    tracker.markEnqueuedForCurrentVersion("lyrics_backfill_enqueued_for_version")
    assertFalse(tracker.shouldRunForCurrentVersion("backfill_enqueued_for_version"))
    assertFalse(tracker.shouldRunForCurrentVersion("lyrics_backfill_enqueued_for_version"))
}
```

- [ ] **Step 4: Run the test — verify it fails to compile**

```bash
./gradlew :data:download:test --tests "*BackfillVersionTrackerTest*"
```

Expected: FAIL with `Too many arguments for ...` on `shouldRunForCurrentVersion(String)` / `markEnqueuedForCurrentVersion(String)`.

- [ ] **Step 5: Generalise `BackfillVersionTracker`**

Edit `BackfillVersionTracker.kt`. Replace the no-arg method bodies with `key: String` parameters:

```kotlin
suspend fun shouldRunForCurrentVersion(key: String): Boolean {
    val stored = context.backfillDataStore.data.first()[intPreferencesKey(key)] ?: -1
    return stored < appVersion.versionCode
}

suspend fun markEnqueuedForCurrentVersion(key: String) {
    context.backfillDataStore.edit { it[intPreferencesKey(key)] = appVersion.versionCode }
}
```

Remove the previously-hard-coded `BACKFILL_KEY` constant (its value `"backfill_enqueued_for_version"` now lives in `MetadataBackfillScheduler`).

- [ ] **Step 6: Update `MetadataBackfillScheduler` to pass its key explicitly**

In `MetadataBackfillScheduler.kt`:

```kotlin
private companion object {
    const val METADATA_BACKFILL_KEY = "backfill_enqueued_for_version"
}

suspend fun scheduleIfNeeded() {
    if (versionTracker.shouldRunForCurrentVersion(METADATA_BACKFILL_KEY)) {
        workManager.enqueueUniqueWork(/* unchanged */)
        versionTracker.markEnqueuedForCurrentVersion(METADATA_BACKFILL_KEY)
    }
}
```

The constant value MUST remain `"backfill_enqueued_for_version"` for upgrade compatibility — users who already ran v0.9.35 must not re-run metadata backfill.

- [ ] **Step 7: Update any other existing tests that called the no-arg versions**

```bash
grep -rn "shouldRunForCurrentVersion\|markEnqueuedForCurrentVersion" data/download/src --include="*.kt"
```

For each caller, pass the metadata key string `"backfill_enqueued_for_version"`.

- [ ] **Step 8: Run tests — verify all pass**

```bash
./gradlew :data:download:test --tests "*BackfillVersionTrackerTest*" --tests "*MetadataBackfillSchedulerTest*"
```

Expected: PASS.

- [ ] **Step 9: Full module test sweep**

```bash
./gradlew :data:download:test
```

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/backfill/BackfillVersionTracker.kt \
        data/download/src/main/kotlin/com/stash/data/download/backfill/MetadataBackfillScheduler.kt \
        data/download/src/test/kotlin/com/stash/data/download/backfill/BackfillVersionTrackerTest.kt
git commit -m "$(cat <<'EOF'
refactor(download): parameterise BackfillVersionTracker by key

Generalises the version-gate tracker so v0.9.36 lyrics backfill
can share the existing metadata_backfill_state.preferences_pb
DataStore file under a separate key. Metadata caller passes its
key constant explicitly; the existing key string is preserved
for upgrade compatibility (users who ran v0.9.35 must not
re-trigger metadata backfill).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 2 — Sources (LRCLIB + InnerTube)

Phase end state: both `LyricsSource` implementations exist, each is unit-tested, and they can be wired into the repository in Phase 3. Tasks 4 and 5 are independent and parallel-safe.

---

## Task 4: `LrclibLyricsSource` — duration-tolerance ladder + search fallback

**Files:**
- Create: `data/lyrics/src/main/kotlin/com/stash/data/lyrics/source/LrclibApi.kt`
- Create: `data/lyrics/src/main/kotlin/com/stash/data/lyrics/source/LrclibLyricsSource.kt`
- Test: `data/lyrics/src/test/kotlin/com/stash/data/lyrics/source/LrclibLyricsSourceTest.kt`

- [ ] **Step 1: Write the failing happy-path test**

`data/lyrics/src/test/kotlin/com/stash/data/lyrics/source/LrclibLyricsSourceTest.kt`:

```kotlin
package com.stash.data.lyrics.source

import com.stash.core.common.AppVersionProvider
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LrclibLyricsSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: LrclibLyricsSource
    private val appVersion = object : AppVersionProvider {
        override val versionName = "0.9.36"
        override val versionCode = 9036
    }

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        source = LrclibLyricsSource(
            okHttpClient = OkHttpClient(),
            appVersion = appVersion,
            baseUrl = server.url("/").toString(),
        )
    }
    @After  fun tearDown() { server.shutdown() }

    @Test fun `exact-match get returns synced and plain`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"id": 42, "trackName": "Off The Grid", "artistName": "Kanye West",
             "albumName": "DONDA", "duration": 279, "instrumental": false,
             "plainLyrics": "I been off the grid",
             "syncedLyrics": "[00:01.00]I been off the grid"}
        """.trimIndent()))
        val result = source.resolve(query(durationMs = 279_000))
        assertNotNull(result)
        assertEquals("lrclib", result!!.sourceId)
        assertEquals("[00:01.00]I been off the grid", result.syncedLrc)
        assertEquals("I been off the grid", result.plainText)
        assertEquals(false, result.instrumental)
        assertEquals("42", result.sourceLyricsId)
        // Verify User-Agent
        val request = server.takeRequest()
        val ua = request.getHeader("User-Agent")
        assertTrue("User-Agent header should mention Stash + version", ua!!.contains("Stash/0.9.36"))
        // Verify exact endpoint
        assertTrue(request.path!!.startsWith("/api/get"))
    }

    @Test fun `instrumental flag preserved`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"id": 7, "trackName": "Linus and Lucy", "artistName": "Vince Guaraldi Trio",
             "albumName": "A Charlie Brown Christmas", "duration": 180, "instrumental": true,
             "plainLyrics": null, "syncedLyrics": null}
        """.trimIndent()))
        val result = source.resolve(query(durationMs = 180_000))
        assertNotNull(result)
        assertTrue(result!!.instrumental)
        assertNull(result.plainText)
        assertNull(result.syncedLrc)
    }

    @Test fun `duration ladder — exact misses, minus-one hits`() = runTest {
        // Exact fails (404), -1 succeeds, no further requests
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("""{"id": 1, "trackName": "T", "artistName": "A",
            "albumName": "AL", "duration": 233, "instrumental": false, "plainLyrics": "x",
            "syncedLyrics": null}"""))
        val result = source.resolve(query(durationMs = 234_000))
        assertNotNull(result)
        assertEquals(2, server.requestCount)
        val first = server.takeRequest().path
        val second = server.takeRequest().path
        assertTrue("exact duration first", first!!.contains("duration=234"))
        assertTrue("-1 second", second!!.contains("duration=233"))
    }

    @Test fun `all rungs miss — search fallback used`() = runTest {
        // Exact + ±2 + ±5 all 404; then /api/search returns a hit
        repeat(11) { server.enqueue(MockResponse().setResponseCode(404)) }
        server.enqueue(MockResponse().setBody("""
            [{"id": 99, "trackName": "Random", "artistName": "Random",
              "albumName": "?", "duration": 234, "instrumental": false,
              "plainLyrics": "fallback", "syncedLyrics": null}]
        """.trimIndent()))
        val result = source.resolve(query(durationMs = 234_000))
        // Lyrics returned only if similarity + duration within ±5s
        // For this stub artist/title equal query, similarity passes; duration 234 vs 234 passes
        assertNotNull(result)
    }

    @Test fun `complete miss returns null`() = runTest {
        repeat(12) { server.enqueue(MockResponse().setResponseCode(404)) }
        // /api/search returns empty list
        server.enqueue(MockResponse().setBody("[]"))
        assertNull(source.resolve(query(durationMs = 234_000)))
    }

    @Test fun `network exception returns null`() = runTest {
        server.shutdown()
        assertNull(source.resolve(query(durationMs = 234_000)))
    }

    @Test fun `null duration skips ladder, goes straight to search`() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        assertNull(source.resolve(query(durationMs = null)))
        // Only one request (search), not 12
        assertEquals(1, server.requestCount)
    }

    private fun query(durationMs: Long?) = LyricsQuery(
        trackId = "t1",
        title = "Random",
        artist = "Random",
        album = "?",
        albumArtist = null,
        durationMs = durationMs,
        youtubeVideoId = null,
    )
}
```

- [ ] **Step 2: Run the test — verify it fails**

```bash
./gradlew :data:lyrics:test --tests "*LrclibLyricsSourceTest*"
```

Expected: FAIL with `Unresolved reference: LrclibLyricsSource`.

- [ ] **Step 3: Implement `LrclibApi` (DTOs + endpoint URL helpers)**

`data/lyrics/src/main/kotlin/com/stash/data/lyrics/source/LrclibApi.kt`:

```kotlin
package com.stash.data.lyrics.source

import kotlinx.serialization.Serializable

@Serializable
internal data class LrclibGetResponse(
    val id: Long,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Int? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)
```

- [ ] **Step 4: Implement `LrclibLyricsSource`**

`data/lyrics/src/main/kotlin/com/stash/data/lyrics/source/LrclibLyricsSource.kt`:

```kotlin
package com.stash.data.lyrics.source

import com.stash.core.common.AppVersionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LrclibLyricsSource @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val appVersion: AppVersionProvider,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : LyricsSource {

    override val id: String = "lrclib"
    override val displayName: String = "LRCLIB"

    override suspend fun resolve(query: LyricsQuery): LyricsResult? = withContext(Dispatchers.IO) {
        // 1. Duration ladder
        query.durationMs?.let { ms ->
            val baseSec = (ms / 1000).toInt()
            for (delta in DURATION_LADDER) {
                val sec = baseSec + delta
                if (sec <= 0) continue
                tryGet(query, sec)?.let { return@withContext it }
            }
        }
        // 2. Search fallback
        return@withContext trySearch(query)
    }

    private fun tryGet(query: LyricsQuery, durationSec: Int): LyricsResult? {
        val url = "${baseUrl.trimEnd('/')}/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", query.title)
            .addQueryParameter("artist_name", query.artist)
            .also { if (!query.album.isNullOrBlank()) it.addQueryParameter("album_name", query.album) }
            .addQueryParameter("duration", durationSec.toString())
            .build()
        val req = Request.Builder().url(url).header("User-Agent", userAgent()).get().build()
        return runCatching {
            okHttpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.string() ?: return@runCatching null
                val dto = JSON.decodeFromString<LrclibGetResponse>(body)
                LyricsResult(
                    sourceId = id,
                    plainText = dto.plainLyrics,
                    syncedLrc = dto.syncedLyrics,
                    instrumental = dto.instrumental,
                    language = null,
                    sourceLyricsId = dto.id.toString(),
                )
            }
        }.getOrNull()
    }

    private fun trySearch(query: LyricsQuery): LyricsResult? {
        val url = "${baseUrl.trimEnd('/')}/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", "${query.artist} ${query.title}")
            .build()
        val req = Request.Builder().url(url).header("User-Agent", userAgent()).get().build()
        return runCatching {
            okHttpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.string() ?: return@runCatching null
                val list = JSON.decodeFromString<List<LrclibGetResponse>>(body)
                if (list.isEmpty()) return@runCatching null
                pickBestSearchHit(query, list)?.let { dto ->
                    LyricsResult(
                        sourceId = id,
                        plainText = dto.plainLyrics,
                        syncedLrc = dto.syncedLyrics,
                        instrumental = dto.instrumental,
                        language = null,
                        sourceLyricsId = dto.id.toString(),
                    )
                }
            }
        }.getOrNull()
    }

    private fun pickBestSearchHit(query: LyricsQuery, hits: List<LrclibGetResponse>): LrclibGetResponse? {
        val target = "${query.artist} ${query.title}".lowercase()
        val baseSec = query.durationMs?.let { (it / 1000).toInt() }
        return hits
            .filter { hit ->
                if (baseSec == null) true
                else hit.duration?.let { kotlin.math.abs(it - baseSec) <= 5 } ?: true
            }
            .maxByOrNull { hit ->
                val candidate = "${hit.artistName.orEmpty()} ${hit.trackName.orEmpty()}".lowercase()
                jaroWinkler(target, candidate)
            }
            ?.takeIf { hit ->
                val candidate = "${hit.artistName.orEmpty()} ${hit.trackName.orEmpty()}".lowercase()
                jaroWinkler(target, candidate) >= 0.85
            }
    }

    private fun userAgent(): String =
        "Stash/${appVersion.versionName} (https://github.com/rawnaldclark/Stash)"

    companion object {
        const val DEFAULT_BASE_URL = "https://lrclib.net/"

        // Closer-to-exact first. Excludes 0 (the exact rung is implicit at index 0).
        private val DURATION_LADDER: IntArray = intArrayOf(0, -1, +1, -2, +2, -3, +3, -4, +4, -5, +5)

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
```

- [ ] **Step 5: Implement `jaroWinkler` helper**

Add a private file-scope function at the bottom of `LrclibLyricsSource.kt` (or a separate file `data/lyrics/src/main/kotlin/com/stash/data/lyrics/source/Similarity.kt`):

```kotlin
internal fun jaroWinkler(s1: String, s2: String): Double {
    if (s1 == s2) return 1.0
    if (s1.isEmpty() || s2.isEmpty()) return 0.0
    val matchDistance = (maxOf(s1.length, s2.length) / 2) - 1
    val s1Matches = BooleanArray(s1.length)
    val s2Matches = BooleanArray(s2.length)
    var matches = 0
    for (i in s1.indices) {
        val start = maxOf(0, i - matchDistance)
        val end = minOf(i + matchDistance + 1, s2.length)
        for (j in start until end) {
            if (s2Matches[j]) continue
            if (s1[i] != s2[j]) continue
            s1Matches[i] = true
            s2Matches[j] = true
            matches++
            break
        }
    }
    if (matches == 0) return 0.0
    var transpositions = 0
    var k = 0
    for (i in s1.indices) {
        if (!s1Matches[i]) continue
        while (!s2Matches[k]) k++
        if (s1[i] != s2[k]) transpositions++
        k++
    }
    val m = matches.toDouble()
    val jaro = (m / s1.length + m / s2.length + (m - transpositions / 2.0) / m) / 3.0
    // Winkler boost
    var prefix = 0
    while (prefix < 4 && prefix < s1.length && prefix < s2.length && s1[prefix] == s2[prefix]) prefix++
    return jaro + prefix * 0.1 * (1 - jaro)
}
```

- [ ] **Step 6: Run the test — verify it passes**

```bash
./gradlew :data:lyrics:test --tests "*LrclibLyricsSourceTest*"
```

Expected: PASS (all tests).

- [ ] **Step 7: Run the full module test sweep**

```bash
./gradlew :data:lyrics:test
```

Expected: PASS (LrcParser + LrclibLyricsSource).

- [ ] **Step 8: Commit**

```bash
git add data/lyrics/src/main/kotlin/com/stash/data/lyrics/source/LrclibApi.kt \
        data/lyrics/src/main/kotlin/com/stash/data/lyrics/source/LrclibLyricsSource.kt \
        data/lyrics/src/test/kotlin/com/stash/data/lyrics/source/LrclibLyricsSourceTest.kt
git commit -m "$(cat <<'EOF'
feat(lyrics): LrclibLyricsSource with duration-tolerance ladder

Primary lyrics source for v0.9.36. Walks /api/get with
closer-to-exact-first duration ladder (-1, +1, -2, +2, -3, +3,
-4, +4, -5, +5), then falls back to /api/search with
Jaro-Winkler similarity ≥ 0.85 + duration tolerance ±5s.

User-Agent identifies Stash + version per LRCLIB's request.
Network errors return null (caller decides retry policy).
Instrumental flag preserved; null durationMs short-circuits
to search.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: `YtMusicLyricsSource` — InnerTube fallback for plain-text lyrics

**Files:**
- Create: `data/lyrics/src/main/kotlin/com/stash/data/lyrics/source/YtMusicLyricsSource.kt`
- Test: `data/lyrics/src/test/kotlin/com/stash/data/lyrics/source/YtMusicLyricsSourceTest.kt`

- [ ] **Step 1: Locate InnerTube client integration points**

```bash
grep -rn "get_watch_playlist\|getWatchPlaylist\|class InnerTubeClient" data/innertube/src/main --include="*.kt" | head -10
```

Take note of the existing public API for `get_watch_playlist(videoId)` and `browse(browseId)`. The InnerTube client's exact class/method names vary — use what the project actually exposes.

- [ ] **Step 2: Write the failing test**

`data/lyrics/src/test/kotlin/com/stash/data/lyrics/source/YtMusicLyricsSourceTest.kt`:

```kotlin
package com.stash.data.lyrics.source

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class YtMusicLyricsSourceTest {

    @Test fun `null youtubeVideoId returns null without InnerTube call`() = runTest {
        val innerTube = mockk<InnerTubeLyricsGateway>(relaxed = true)
        val source = YtMusicLyricsSource(innerTube)
        assertNull(source.resolve(query(videoId = null)))
    }

    @Test fun `videoId with no browseId returns null`() = runTest {
        val innerTube = mockk<InnerTubeLyricsGateway>()
        coEvery { innerTube.lyricsBrowseId(any()) } returns null
        val source = YtMusicLyricsSource(innerTube)
        assertNull(source.resolve(query(videoId = "abc")))
    }

    @Test fun `videoId with browseId returns plain text result`() = runTest {
        val innerTube = mockk<InnerTubeLyricsGateway>()
        coEvery { innerTube.lyricsBrowseId("abc") } returns "MPLYt_abc"
        coEvery { innerTube.fetchLyricsByBrowseId("MPLYt_abc") } returns "yesterday all my troubles"
        val source = YtMusicLyricsSource(innerTube)
        val result = source.resolve(query(videoId = "abc"))
        assertNotNull(result)
        assertEquals("innertube", result!!.sourceId)
        assertEquals("yesterday all my troubles", result.plainText)
        assertNull(result.syncedLrc)
        assertEquals(false, result.instrumental)
    }

    private fun query(videoId: String?) = LyricsQuery(
        trackId = "t1",
        title = "T", artist = "A", album = null, albumArtist = null,
        durationMs = null, youtubeVideoId = videoId,
    )
}
```

- [ ] **Step 3: Define the `InnerTubeLyricsGateway` interface**

`data/lyrics/src/main/kotlin/com/stash/data/lyrics/source/InnerTubeLyricsGateway.kt`:

```kotlin
package com.stash.data.lyrics.source

/**
 * Adapter interface around the InnerTube client. Lets YtMusicLyricsSource
 * unit-test cleanly without instantiating the real client; the production
 * binding (in `:app` Hilt module) wraps the existing `:data:innertube` client.
 */
interface InnerTubeLyricsGateway {
    /** Returns the lyrics browseId (MPLY...) from get_watch_playlist, or null if not surfaced. */
    suspend fun lyricsBrowseId(videoId: String): String?

    /** Fetches plain-text lyrics for the given browseId, or null on miss. */
    suspend fun fetchLyricsByBrowseId(browseId: String): String?
}
```

- [ ] **Step 4: Run the test — verify it fails**

```bash
./gradlew :data:lyrics:test --tests "*YtMusicLyricsSourceTest*"
```

Expected: FAIL with `Unresolved reference: YtMusicLyricsSource`.

- [ ] **Step 5: Implement `YtMusicLyricsSource`**

`data/lyrics/src/main/kotlin/com/stash/data/lyrics/source/YtMusicLyricsSource.kt`:

```kotlin
package com.stash.data.lyrics.source

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YtMusicLyricsSource @Inject constructor(
    private val innerTube: InnerTubeLyricsGateway,
) : LyricsSource {

    override val id: String = "innertube"
    override val displayName: String = "YouTube Music"

    override suspend fun resolve(query: LyricsQuery): LyricsResult? {
        val videoId = query.youtubeVideoId ?: return null
        val browseId = runCatching { innerTube.lyricsBrowseId(videoId) }.getOrNull() ?: return null
        val text = runCatching { innerTube.fetchLyricsByBrowseId(browseId) }.getOrNull() ?: return null
        if (text.isBlank()) return null
        return LyricsResult(
            sourceId = id,
            plainText = text.trim(),
            syncedLrc = null,
            instrumental = false,
            language = null,
            sourceLyricsId = null,
        )
    }
}
```

- [ ] **Step 6: Implement the real `InnerTubeLyricsGateway` binding**

`data/lyrics/src/main/kotlin/com/stash/data/lyrics/source/InnerTubeLyricsGatewayImpl.kt` (wraps the actual `:data:innertube` client — use the API discovered in Step 1):

```kotlin
package com.stash.data.lyrics.source

import com.stash.data.innertube.InnerTubeClient   // adjust to actual package
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InnerTubeLyricsGatewayImpl @Inject constructor(
    private val client: InnerTubeClient,
) : InnerTubeLyricsGateway {

    override suspend fun lyricsBrowseId(videoId: String): String? {
        // get_watch_playlist returns a payload that may include a lyrics tab browseId
        val response = client.getWatchPlaylist(videoId)
        return response.tabs
            .firstOrNull { it.title.equals("lyrics", ignoreCase = true) }
            ?.browseId
            ?.takeIf { it.startsWith("MPLY") }
    }

    override suspend fun fetchLyricsByBrowseId(browseId: String): String? {
        val response = client.browse(browseId)
        return response.contents
            ?.sectionListRenderer
            ?.contents
            ?.firstNotNullOfOrNull { it.musicDescriptionShelfRenderer?.description?.runs?.joinToString("") { run -> run.text } }
    }
}
```

If the project's InnerTube client API differs from these method names (likely — these are illustrative), adapt to the actual surface. Locate it via:

```bash
grep -rn "fun getWatchPlaylist\|fun browse\b" data/innertube/src/main --include="*.kt"
```

If the response models differ, the implementer fills in the projection. The interface contract is fixed; the impl adapts.

- [ ] **Step 7: Run the test — verify it passes**

```bash
./gradlew :data:lyrics:test --tests "*YtMusicLyricsSourceTest*"
```

Expected: PASS.

- [ ] **Step 8: Build the impl to verify it compiles against the real InnerTube client**

```bash
./gradlew :data:lyrics:assembleDebug
```

Expected: BUILD SUCCESSFUL. If the implementer guessed the InnerTube API shape wrong in Step 6, the failure here is the signal to look at the real surface and adjust.

- [ ] **Step 9: Commit**

```bash
git add data/lyrics/src/main/kotlin/com/stash/data/lyrics/source/YtMusicLyricsSource.kt \
        data/lyrics/src/main/kotlin/com/stash/data/lyrics/source/InnerTubeLyricsGateway.kt \
        data/lyrics/src/main/kotlin/com/stash/data/lyrics/source/InnerTubeLyricsGatewayImpl.kt \
        data/lyrics/src/test/kotlin/com/stash/data/lyrics/source/YtMusicLyricsSourceTest.kt
git commit -m "$(cat <<'EOF'
feat(lyrics): YtMusicLyricsSource — InnerTube plain-text fallback

Secondary lyrics source. Reuses :data:innertube wiring via a small
adapter interface (InnerTubeLyricsGateway) — keeps the source easily
unit-testable without instantiating the real client. Production
binding lives in InnerTubeLyricsGatewayImpl which calls
get_watch_playlist → extracts the MPLY... browseId → browse.

Returns only plain text (InnerTube does not provide synced LRC).
Null youtubeVideoId short-circuits to null without HTTP.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 3 — Repository + sidecar writer

Phase end state: `LyricsRepository.resolveAndStore(query)` orchestrates the source chain, writes Room rows, stamps, and triggers sidecar writes. `LyricsSidecarWriter` handles both internal storage and SAF tree.

Tasks 6 and 7 are independent and parallel-safe (6 mocks the sidecar writer).

---

## Task 6: `LyricsRepository` — orchestration + idempotent upsert

**Files:**
- Modify: `core/common/src/main/kotlin/com/stash/core/common/Clock.kt` — verify exists; if not, add (a `Clock` interface for testable time)
- Create: `data/lyrics/src/main/kotlin/com/stash/data/lyrics/LyricsRepository.kt`
- Test: `data/lyrics/src/test/kotlin/com/stash/data/lyrics/LyricsRepositoryTest.kt`

- [ ] **Step 1: Verify a `Clock` abstraction exists**

```bash
grep -rn "interface Clock\b\|fun now()" core/common/src/main --include="*.kt" | head
```

If present (likely — v0.9.35 used one), reuse it. If not, add a minimal one:

```kotlin
// core/common/src/main/kotlin/com/stash/core/common/Clock.kt
package com.stash.core.common
interface Clock { fun now(): Long }
class SystemClock : Clock { override fun now() = System.currentTimeMillis() }
```

And add the Hilt binding in `core/common`'s module.

- [ ] **Step 2: Write the failing test**

`data/lyrics/src/test/kotlin/com/stash/data/lyrics/LyricsRepositoryTest.kt`:

```kotlin
package com.stash.data.lyrics

import com.stash.core.common.Clock
import com.stash.core.data.db.dao.LyricsDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.LyricsEntity
import com.stash.data.lyrics.sidecar.LyricsSidecarWriter
import com.stash.data.lyrics.source.LyricsQuery
import com.stash.data.lyrics.source.LyricsResult
import com.stash.data.lyrics.source.LyricsSource
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsRepositoryTest {

    private val clock = object : Clock { override fun now() = 1_700_000_000_000L }

    @Test fun `success path — writes row, stamps, invokes sidecar`() = runTest {
        val lrclib = fakeSource("lrclib", LyricsResult("lrclib", "plain", "[00:01.00]plain", false, null, "42"))
        val ytm = fakeSource("innertube", null)
        val lyricsDao = mockk<LyricsDao>(); val trackDao = mockk<TrackDao>(); val sidecar = mockk<LyricsSidecarWriter>()
        coEvery { lyricsDao.upsert(any()) } just Runs
        coEvery { trackDao.setLyricsFetchedAt(any(), any()) } just Runs
        coEvery { sidecar.write(any(), any()) } just Runs

        val repo = LyricsRepository(listOf(lrclib, ytm), lyricsDao, trackDao, sidecar, clock)
        val result = repo.resolveAndStore(query("t1"))

        assertNotNull(result)
        val capture = slot<LyricsEntity>()
        coVerify { lyricsDao.upsert(capture(capture)) }
        assertEquals("t1", capture.captured.trackId)
        assertEquals("lrclib", capture.captured.source)
        coVerify { trackDao.setLyricsFetchedAt("t1", 1_700_000_000_000L) }
        coVerify { sidecar.write("t1", any()) }
    }

    @Test fun `instrumental path — writes row, stamps, does NOT invoke sidecar`() = runTest {
        val lrclib = fakeSource("lrclib", LyricsResult("lrclib", null, null, true, null, "42"))
        val lyricsDao = mockk<LyricsDao>(relaxed = true); val trackDao = mockk<TrackDao>(relaxed = true)
        val sidecar = mockk<LyricsSidecarWriter>()
        val repo = LyricsRepository(listOf(lrclib), lyricsDao, trackDao, sidecar, clock)
        repo.resolveAndStore(query("t1"))
        coVerify(exactly = 0) { sidecar.write(any(), any()) }
        coVerify { trackDao.setLyricsFetchedAt("t1", 1_700_000_000_000L) }
    }

    @Test fun `complete miss — stamps 0L, no row, no sidecar`() = runTest {
        val a = fakeSource("lrclib", null); val b = fakeSource("innertube", null)
        val lyricsDao = mockk<LyricsDao>(relaxed = true); val trackDao = mockk<TrackDao>(relaxed = true)
        val sidecar = mockk<LyricsSidecarWriter>()
        val repo = LyricsRepository(listOf(a, b), lyricsDao, trackDao, sidecar, clock)
        assertNull(repo.resolveAndStore(query("t1")))
        coVerify(exactly = 0) { lyricsDao.upsert(any()) }
        coVerify(exactly = 0) { sidecar.write(any(), any()) }
        coVerify { trackDao.setLyricsFetchedAt("t1", 0L) }
    }

    @Test fun `source-chain order — first non-null wins`() = runTest {
        val a = fakeSource("lrclib", LyricsResult("lrclib", "p", null, false, null, "1"))
        val b = mockk<LyricsSource>(relaxed = true)
        val lyricsDao = mockk<LyricsDao>(relaxed = true); val trackDao = mockk<TrackDao>(relaxed = true)
        val sidecar = mockk<LyricsSidecarWriter>(relaxed = true)
        val repo = LyricsRepository(listOf(a, b), lyricsDao, trackDao, sidecar, clock)
        repo.resolveAndStore(query("t1"))
        coVerify(exactly = 0) { b.resolve(any()) }
    }

    @Test fun `sidecar failure does not unwind Room write`() = runTest {
        val lrclib = fakeSource("lrclib", LyricsResult("lrclib", "p", null, false, null, "1"))
        val lyricsDao = mockk<LyricsDao>(relaxed = true); val trackDao = mockk<TrackDao>(relaxed = true)
        val sidecar = mockk<LyricsSidecarWriter>()
        coEvery { sidecar.write(any(), any()) } throws RuntimeException("disk full")
        val repo = LyricsRepository(listOf(lrclib), lyricsDao, trackDao, sidecar, clock)
        // Should NOT throw
        repo.resolveAndStore(query("t1"))
        coVerify { lyricsDao.upsert(any()) }
        coVerify { trackDao.setLyricsFetchedAt("t1", 1_700_000_000_000L) }
    }

    private fun fakeSource(sourceId: String, result: LyricsResult?): LyricsSource = object : LyricsSource {
        override val id = sourceId
        override val displayName = sourceId
        override suspend fun resolve(query: LyricsQuery): LyricsResult? = result
    }

    private fun query(id: String) = LyricsQuery(
        trackId = id, title = "T", artist = "A", album = null, albumArtist = null,
        durationMs = 200_000, youtubeVideoId = null,
    )
}
```

- [ ] **Step 3: Run the test — verify it fails**

```bash
./gradlew :data:lyrics:test --tests "*LyricsRepositoryTest*"
```

Expected: FAIL with `Unresolved reference: LyricsRepository`.

- [ ] **Step 4: Implement `LyricsRepository`**

`data/lyrics/src/main/kotlin/com/stash/data/lyrics/LyricsRepository.kt`:

```kotlin
package com.stash.data.lyrics

import com.stash.core.common.Clock
import com.stash.core.data.db.dao.LyricsDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.LyricsEntity
import com.stash.data.lyrics.sidecar.LyricsSidecarWriter
import com.stash.data.lyrics.source.LyricsQuery
import com.stash.data.lyrics.source.LyricsResult
import com.stash.data.lyrics.source.LyricsSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LyricsRepository @Inject constructor(
    private val sources: List<@JvmSuppressWildcards LyricsSource>,
    private val lyricsDao: LyricsDao,
    private val trackDao: TrackDao,
    private val sidecarWriter: LyricsSidecarWriter,
    private val clock: Clock,
) {

    fun observe(trackId: String): Flow<LyricsEntity?> = lyricsDao.observe(trackId)

    suspend fun get(trackId: String): LyricsEntity? = lyricsDao.get(trackId)

    /**
     * Walks the source chain in order, returns the first non-null LyricsResult,
     * persists it to Room, stamps tracks.lyrics_fetched_at, and triggers a
     * sidecar .lrc write (skipped for instrumental).
     *
     * On complete miss, stamps tracks.lyrics_fetched_at = 0L.
     * Sidecar failure is logged but does not unwind Room state.
     */
    suspend fun resolveAndStore(query: LyricsQuery): LyricsEntity? {
        val result = sources.firstNotNullOfOrNull { it.resolve(query) }
        if (result == null) {
            trackDao.setLyricsFetchedAt(query.trackId, 0L)
            return null
        }
        val now = clock.now()
        val entity = LyricsEntity(
            trackId = query.trackId,
            plainText = result.plainText,
            syncedLrc = result.syncedLrc,
            instrumental = result.instrumental,
            language = result.language,
            source = result.sourceId,
            sourceLyricsId = result.sourceLyricsId,
            fetchedAt = now,
        )
        lyricsDao.upsert(entity)
        trackDao.setLyricsFetchedAt(query.trackId, now)
        if (!result.instrumental) {
            runCatching { sidecarWriter.write(query.trackId, entity) }
                .onFailure { /* sidecar failure is non-fatal; log via Timber in prod, swallow in tests */ }
        }
        return entity
    }
}
```

- [ ] **Step 5: Run the test — verify it passes**

```bash
./gradlew :data:lyrics:test --tests "*LyricsRepositoryTest*"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add data/lyrics/src/main/kotlin/com/stash/data/lyrics/LyricsRepository.kt \
        data/lyrics/src/test/kotlin/com/stash/data/lyrics/LyricsRepositoryTest.kt
# include Clock additions if any
git commit -m "$(cat <<'EOF'
feat(lyrics): LyricsRepository — source-chain orchestration

Sole entrypoint for both UI and workers. Walks the LyricsSource
chain in priority order (LRCLIB → InnerTube), persists the first
non-null result to the lyrics table, stamps tracks.lyrics_fetched_at
with the success timestamp, and triggers a sidecar .lrc write
(skipped for LRCLIB-confirmed instrumental tracks).

Complete-miss path stamps 0L; sidecar-write failure is non-fatal
and does not unwind the Room state.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: `LyricsSidecarWriter` — internal storage + SAF tree

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/files/FileOrganizer.kt` — expose slug helpers
- Create: `data/lyrics/src/main/kotlin/com/stash/data/lyrics/sidecar/LyricsSidecarWriter.kt`
- Test: `data/lyrics/src/test/kotlin/com/stash/data/lyrics/sidecar/LyricsSidecarWriterTest.kt`

- [ ] **Step 1: Expose slug helpers from `FileOrganizer`**

The spec recommends `internal object FileOrganizerSlugs` in `:data:download`. Implementation:

In `FileOrganizer.kt`, find the existing `private fun slugify(...)` (per recon, around line 244). Rename to `internal` and move into a top-level `internal object FileOrganizerSlugs` in the same file (or a sibling file `FileOrganizerSlugs.kt`). Update internal callers in `FileOrganizer.kt` to call `FileOrganizerSlugs.slugify(...)`.

```kotlin
// data/download/src/main/kotlin/com/stash/data/download/files/FileOrganizerSlugs.kt
package com.stash.data.download.files

internal object FileOrganizerSlugs {
    /** Returns a filesystem-safe slug for the given path component. */
    fun slugify(input: String): String {
        // (Body copied verbatim from the existing FileOrganizer.slugify)
        ...
    }
}
```

(Implementer: copy the existing slugify body, do NOT rewrite it. The behaviour must match exactly.)

- [ ] **Step 2: Confirm no behaviour change to FileOrganizer**

```bash
./gradlew :data:download:test
```

Expected: PASS (existing FileOrganizer tests should not regress; if there are slugify-specific tests, they should still pass because the function body is identical).

- [ ] **Step 3: Write the failing sidecar test**

`data/lyrics/src/test/kotlin/com/stash/data/lyrics/sidecar/LyricsSidecarWriterTest.kt`:

```kotlin
package com.stash.data.lyrics.sidecar

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.LyricsEntity
import com.stash.core.data.db.entity.TrackEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LyricsSidecarWriterTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `internal storage path writes basename dot lrc next to audio`() = runTest {
        val audio = tmp.newFile("Off The Grid.opus")
        val track = stubTrack(filePath = audio.absolutePath, title = "Off The Grid", artist = "Kanye West", album = "DONDA")
        val writer = makeWriter(track)
        writer.write("t1", lyricsEntity(syncedLrc = "[00:01.00]hello", plainText = "hello"))
        val sidecar = File(audio.parent, "Off The Grid.lrc")
        assertTrue("sidecar must exist", sidecar.exists())
        val body = sidecar.readText()
        assertTrue("LRC header tags written", body.contains("[ti:Off The Grid]"))
        assertTrue("LRC header includes artist", body.contains("[ar:Kanye West]"))
        assertTrue("synced body present", body.contains("[00:01.00]hello"))
    }

    @Test fun `prefers syncedLrc, falls back to plain when synced is null`() = runTest {
        val audio = tmp.newFile("track.flac")
        val track = stubTrack(filePath = audio.absolutePath)
        val writer = makeWriter(track)
        writer.write("t1", lyricsEntity(syncedLrc = null, plainText = "plain text only"))
        val sidecar = File(audio.parent, "track.lrc")
        val body = sidecar.readText()
        assertTrue("plain text written", body.contains("plain text only"))
    }

    @Test fun `does nothing when both syncedLrc and plainText are null (instrumental)`() = runTest {
        val audio = tmp.newFile("inst.opus")
        val track = stubTrack(filePath = audio.absolutePath)
        val writer = makeWriter(track)
        writer.write("t1", lyricsEntity(syncedLrc = null, plainText = null, instrumental = true))
        val sidecar = File(audio.parent, "inst.lrc")
        assertEquals(false, sidecar.exists())
    }

    @Test fun `missing track returns silently without writing`() = runTest {
        val trackDao = mockk<TrackDao>()
        coEvery { trackDao.get("missing") } returns null
        val writer = LyricsSidecarWriter(trackDao = trackDao, /* context = */ stubContext())
        writer.write("missing", lyricsEntity())
        // no exception, no file
    }

    private fun makeWriter(track: TrackEntity): LyricsSidecarWriter {
        val trackDao = mockk<TrackDao>()
        coEvery { trackDao.get(track.id) } returns track
        return LyricsSidecarWriter(trackDao = trackDao, context = stubContext())
    }

    private fun stubContext(): android.content.Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()

    private fun stubTrack(filePath: String, title: String = "T", artist: String = "A", album: String = "AL") =
        TrackEntity(
            id = "t1", title = title, artist = artist, album = album,
            albumArtist = "", filePath = filePath, durationMs = 0L,
            /* remaining required fields with defaults; mirror the entity's actual ctor */
        )

    private fun lyricsEntity(syncedLrc: String? = null, plainText: String? = null, instrumental: Boolean = false) =
        LyricsEntity("t1", plainText, syncedLrc, instrumental, null, "lrclib", "42", 1_700_000_000_000L)
}
```

(The TrackEntity stub will need to match the actual entity's required fields; the implementer copies the canonical builder from existing tests in `:core:data`.)

This test uses Robolectric for `ApplicationProvider.getApplicationContext()` — annotate the class with `@RunWith(RobolectricTestRunner::class)`.

- [ ] **Step 4: Run the test — verify it fails**

```bash
./gradlew :data:lyrics:test --tests "*LyricsSidecarWriterTest*"
```

Expected: FAIL with `Unresolved reference: LyricsSidecarWriter`.

- [ ] **Step 5: Implement `LyricsSidecarWriter`**

`data/lyrics/src/main/kotlin/com/stash/data/lyrics/sidecar/LyricsSidecarWriter.kt`:

```kotlin
package com.stash.data.lyrics.sidecar

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.LyricsEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.files.FileOrganizerSlugs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LyricsSidecarWriter @Inject constructor(
    private val trackDao: TrackDao,
    @ApplicationContext private val context: Context,
) {

    suspend fun write(trackId: String, lyrics: LyricsEntity) {
        if (lyrics.syncedLrc.isNullOrBlank() && lyrics.plainText.isNullOrBlank()) return
        val track = trackDao.get(trackId) ?: return
        val body = buildLrcBody(track, lyrics)
        val path = track.filePath
        if (path.startsWith("content://")) writeSafSidecar(path, track, body)
        else writeFilesystemSidecar(path, body)
    }

    private fun writeFilesystemSidecar(audioPath: String, body: String) {
        val audio = File(audioPath)
        val sidecar = File(audio.parentFile, "${audio.nameWithoutExtension}.lrc")
        sidecar.writeText(body, Charsets.UTF_8)
    }

    private fun writeSafSidecar(audioUri: String, track: TrackEntity, body: String) {
        val tree = DocumentFile.fromTreeUri(context, android.net.Uri.parse(audioUri)) ?: return
        val artistDir = findOrCreateDir(tree, FileOrganizerSlugs.slugify(track.albumArtist.ifBlank { track.artist }))
        val albumDir = findOrCreateDir(artistDir, FileOrganizerSlugs.slugify(track.album))
        val name = "${FileOrganizerSlugs.slugify(track.title)}.lrc"
        val existing = albumDir.findFile(name)
        val target = existing ?: albumDir.createFile("application/x-lrc", name) ?: return
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    }

    private fun findOrCreateDir(parent: DocumentFile, name: String): DocumentFile =
        parent.findFile(name)?.takeIf { it.isDirectory } ?: parent.createDirectory(name)!!

    private fun buildLrcBody(track: TrackEntity, lyrics: LyricsEntity): String = buildString {
        appendLine("[ti:${track.title}]")
        appendLine("[ar:${track.albumArtist.ifBlank { track.artist }}]")
        if (track.album.isNotBlank()) appendLine("[al:${track.album}]")
        if (track.durationMs > 0) {
            val sec = (track.durationMs / 1000).toInt()
            appendLine("[length:${sec / 60}:%02d]".format(sec % 60))
        }
        appendLine("[by:Stash]")
        append(lyrics.syncedLrc ?: lyrics.plainText.orEmpty())
    }
}
```

(The SAF path may need refinement based on the actual `FileOrganizer.writeToSafTree` helper structure — see spec §5. If the existing FileOrganizer already has a helper for "open tree → walk down by slug" reuse that.)

- [ ] **Step 6: Run the test — verify it passes**

```bash
./gradlew :data:lyrics:test --tests "*LyricsSidecarWriterTest*"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add data/lyrics/src/main/kotlin/com/stash/data/lyrics/sidecar/LyricsSidecarWriter.kt \
        data/lyrics/src/test/kotlin/com/stash/data/lyrics/sidecar/LyricsSidecarWriterTest.kt \
        data/download/src/main/kotlin/com/stash/data/download/files/FileOrganizerSlugs.kt \
        data/download/src/main/kotlin/com/stash/data/download/files/FileOrganizer.kt
git commit -m "$(cat <<'EOF'
feat(lyrics): LyricsSidecarWriter — internal + SAF .lrc writes

Writes <basename>.lrc next to the audio file on every successful
fetch. Handles both internal storage (java.io.File) and SAF tree
(DocumentFile + ContentResolver). LRC body includes canonical
header tags [ti:][ar:][al:][length:][by:Stash] then the synced
body (preferred) or plain text fallback.

Skips when both synced and plain are null (instrumental).
FileOrganizer.slugify lifted to internal FileOrganizerSlugs object
so the SAF path can be derived without duplicating slug semantics.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 4 — Workers

Phase end state: both workers exist and pass Robolectric tests; the backfill scheduler is wired into `StashApplication.onCreate`.

Tasks 8 and 9 are independent and parallel-safe.

---

## Task 8: `LyricsFetchWorker` — post-download + priority on-open

**Files:**
- Create: `data/lyrics/src/main/kotlin/com/stash/data/lyrics/worker/LyricsFetchWorker.kt`
- Test: `data/lyrics/src/test/kotlin/com/stash/data/lyrics/worker/LyricsFetchWorkerTest.kt`

- [ ] **Step 1: Write the failing test**

`data/lyrics/src/test/kotlin/com/stash/data/lyrics/worker/LyricsFetchWorkerTest.kt`:

Mirror the existing `MetadataBackfillWorkerTest` setup pattern (Robolectric + `ListenableWorker.startWork()` + `TestListenableWorkerBuilder`).

```kotlin
package com.stash.data.lyrics.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.lyrics.LyricsRepository
import com.stash.data.lyrics.source.LyricsQuery
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsFetchWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `missing trackId fails`() {
        val worker = buildWorker(workDataOf(), mockk(), mockk())
        val result = runBlocking { worker.doWork() }
        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test fun `track deleted mid-flight succeeds without fetch`() {
        val trackDao = mockk<TrackDao>(); val repo = mockk<LyricsRepository>()
        coEvery { trackDao.get("t1") } returns null
        val worker = buildWorker(workDataOf(LyricsFetchWorker.KEY_TRACK_ID to "t1"), trackDao, repo)
        val result = runBlocking { worker.doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { repo.resolveAndStore(any()) }
    }

    @Test fun `happy path — fetches and succeeds`() {
        val trackDao = mockk<TrackDao>(); val repo = mockk<LyricsRepository>()
        coEvery { trackDao.get("t1") } returns sampleTrack("t1")
        coEvery { repo.resolveAndStore(any()) } returns null   // returns null on miss — doesn't matter; we just verify the call
        val worker = buildWorker(workDataOf(LyricsFetchWorker.KEY_TRACK_ID to "t1"), trackDao, repo)
        val result = runBlocking { worker.doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { repo.resolveAndStore(any<LyricsQuery>()) }
    }

    @Test fun `transient failure retries until exhausted`() {
        val trackDao = mockk<TrackDao>(); val repo = mockk<LyricsRepository>()
        coEvery { trackDao.get("t1") } returns sampleTrack("t1")
        coEvery { repo.resolveAndStore(any()) } throws RuntimeException("network")
        val worker = buildWorker(
            workDataOf(LyricsFetchWorker.KEY_TRACK_ID to "t1"), trackDao, repo, runAttemptCount = 0,
        )
        val result = runBlocking { worker.doWork() }
        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test fun `transient failure after MAX_ATTEMPTS returns success (leaves NULL)`() {
        val trackDao = mockk<TrackDao>(); val repo = mockk<LyricsRepository>()
        coEvery { trackDao.get("t1") } returns sampleTrack("t1")
        coEvery { repo.resolveAndStore(any()) } throws RuntimeException("network")
        val worker = buildWorker(
            workDataOf(LyricsFetchWorker.KEY_TRACK_ID to "t1"), trackDao, repo, runAttemptCount = 5,
        )
        val result = runBlocking { worker.doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
    }

    private fun buildWorker(
        inputData: androidx.work.Data,
        trackDao: TrackDao,
        repo: LyricsRepository,
        runAttemptCount: Int = 0,
    ): LyricsFetchWorker = TestListenableWorkerBuilder<LyricsFetchWorker>(context)
        .setInputData(inputData)
        .setRunAttemptCount(runAttemptCount)
        .setWorkerFactory(object : androidx.work.WorkerFactory() {
            override fun createWorker(c: Context, workerClassName: String, params: androidx.work.WorkerParameters) =
                LyricsFetchWorker(c, params, repo, trackDao)
        })
        .build()

    private fun sampleTrack(id: String) = TrackEntity(
        id = id, title = "T", artist = "A", album = "AL", albumArtist = "",
        filePath = "/x/y.opus", durationMs = 200_000,
        /* remaining required fields with defaults */
    )
}
```

- [ ] **Step 2: Run the test — verify it fails**

```bash
./gradlew :data:lyrics:test --tests "*LyricsFetchWorkerTest*"
```

Expected: FAIL with `Unresolved reference: LyricsFetchWorker`.

- [ ] **Step 3: Implement `LyricsFetchWorker`**

`data/lyrics/src/main/kotlin/com/stash/data/lyrics/worker/LyricsFetchWorker.kt`:

```kotlin
package com.stash.data.lyrics.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.TrackDao
import com.stash.data.lyrics.LyricsRepository
import com.stash.data.lyrics.source.LyricsQuery
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class LyricsFetchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val lyricsRepository: LyricsRepository,
    private val trackDao: TrackDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val trackId = inputData.getString(KEY_TRACK_ID) ?: return Result.failure()
        val track = trackDao.get(trackId) ?: return Result.success()
        val query = LyricsQuery(
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album.ifBlank { null },
            albumArtist = track.albumArtist.ifBlank { null },
            durationMs = track.durationMs.takeIf { it > 0 },
            youtubeVideoId = extractYoutubeVideoId(track),
        )
        return runCatching { lyricsRepository.resolveAndStore(query) }
            .fold(
                onSuccess = { Result.success() },
                onFailure = { if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success() },
            )
    }

    // The Track domain may expose youtubeVideoId via a dedicated column or via the
    // existing TrackEntity.videoId field — check the schema at impl time.
    private fun extractYoutubeVideoId(track: com.stash.core.data.db.entity.TrackEntity): String? =
        track.videoId.takeIf { it.isNotBlank() }   // adjust to actual field name

    companion object {
        const val KEY_TRACK_ID = "track_id"
        private const val MAX_ATTEMPTS = 3
        const val UNIQUE_PREFIX_POST_DOWNLOAD = "lyrics_post_download_"
        const val UNIQUE_PREFIX_PRIORITY = "lyrics_priority_"
    }
}
```

- [ ] **Step 4: Run the test — verify it passes**

```bash
./gradlew :data:lyrics:test --tests "*LyricsFetchWorkerTest*"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add data/lyrics/src/main/kotlin/com/stash/data/lyrics/worker/LyricsFetchWorker.kt \
        data/lyrics/src/test/kotlin/com/stash/data/lyrics/worker/LyricsFetchWorkerTest.kt
git commit -m "$(cat <<'EOF'
feat(lyrics): LyricsFetchWorker (post-download + priority on-open)

Per-track WorkManager worker. Two enqueue origins: post-download
hook (background, non-expedited) and user-priority-on-open
(expedited, REPLACE policy). Retries transient failures up to
MAX_ATTEMPTS=3, then returns success leaving the row NULL so
the once-per-version backfill picks it up later.

Track-deleted-mid-flight is a no-op success.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: `LyricsBackfillWorker` + scheduler + state + DataStore + wiring

**Files:**
- Create: `data/lyrics/src/main/kotlin/com/stash/data/lyrics/backfill/LyricsBackfillState.kt`
- Create: `data/lyrics/src/main/kotlin/com/stash/data/lyrics/backfill/LyricsBackfillScheduler.kt`
- Create: `data/lyrics/src/main/kotlin/com/stash/data/lyrics/worker/LyricsBackfillWorker.kt`
- Test: `data/lyrics/src/test/kotlin/com/stash/data/lyrics/worker/LyricsBackfillWorkerTest.kt`
- Test: `data/lyrics/src/test/kotlin/com/stash/data/lyrics/backfill/LyricsBackfillStateTest.kt`
- Modify: `app/src/main/kotlin/com/stash/app/StashApplication.kt`
- Modify: `data/lyrics/src/main/kotlin/com/stash/data/lyrics/di/LyricsModule.kt`

- [ ] **Step 1: Mirror `MetadataBackfillState` for the lyrics version**

`data/lyrics/src/main/kotlin/com/stash/data/lyrics/backfill/LyricsBackfillState.kt`:

```kotlin
package com.stash.data.lyrics.backfill

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class State { IDLE, RUNNING, FINISHED }

data class LyricsBackfillSnapshot(
    val state: State,
    val processed: Int,
    val total: Int,
    val finishedAt: Long?,
)

internal val Context.lyricsBackfillDataStore by preferencesDataStore(name = "lyrics_backfill_state")

@Singleton
class LyricsBackfillState @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val snapshot: Flow<LyricsBackfillSnapshot> = context.lyricsBackfillDataStore.data.map { prefs ->
        val stateOrdinal = prefs[KEY_STATE] ?: State.IDLE.ordinal
        LyricsBackfillSnapshot(
            state = State.entries[stateOrdinal],
            processed = prefs[KEY_PROCESSED] ?: 0,
            total = prefs[KEY_TOTAL] ?: 0,
            finishedAt = prefs[KEY_FINISHED_AT],
        )
    }

    suspend fun markStarted(total: Int) = context.lyricsBackfillDataStore.edit {
        it[KEY_STATE] = State.RUNNING.ordinal
        it[KEY_PROCESSED] = 0
        it[KEY_TOTAL] = total
        it.remove(KEY_FINISHED_AT)
    }

    suspend fun publishProgress(processed: Int, total: Int) = context.lyricsBackfillDataStore.edit {
        it[KEY_STATE] = State.RUNNING.ordinal
        it[KEY_PROCESSED] = processed
        it[KEY_TOTAL] = total
    }

    suspend fun markFinished() = context.lyricsBackfillDataStore.edit {
        it[KEY_STATE] = State.FINISHED.ordinal
        it[KEY_FINISHED_AT] = System.currentTimeMillis()
    }

    suspend fun markFinishedAcknowledged() = context.lyricsBackfillDataStore.edit {
        it[KEY_STATE] = State.IDLE.ordinal
    }

    private companion object {
        val KEY_STATE      = intPreferencesKey("state")
        val KEY_PROCESSED  = intPreferencesKey("processed")
        val KEY_TOTAL      = intPreferencesKey("total")
        val KEY_FINISHED_AT = longPreferencesKey("finished_at")
    }
}
```

- [ ] **Step 2: Write `LyricsBackfillStateTest`** (mirror the v0.9.35 metadata test exactly)

```kotlin
// data/lyrics/src/test/kotlin/com/stash/data/lyrics/backfill/LyricsBackfillStateTest.kt
@RunWith(RobolectricTestRunner::class)
class LyricsBackfillStateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val state = LyricsBackfillState(context)

    @Before fun clear() = runBlocking { context.lyricsBackfillDataStore.edit { it.clear() } }
    @After  fun reset() = runBlocking { context.lyricsBackfillDataStore.edit { it.clear() } }

    @Test fun `initial state is IDLE`() = runTest {
        val snap = state.snapshot.first()
        assertEquals(State.IDLE, snap.state)
    }

    @Test fun `markStarted publishes RUNNING with total`() = runTest {
        state.markStarted(50)
        val snap = state.snapshot.first()
        assertEquals(State.RUNNING, snap.state); assertEquals(50, snap.total)
    }

    @Test fun `markFinished publishes FINISHED`() = runTest {
        state.markStarted(10)
        state.markFinished()
        val snap = state.snapshot.first()
        assertEquals(State.FINISHED, snap.state)
    }

    @Test fun `markFinishedAcknowledged returns to IDLE`() = runTest {
        state.markStarted(10); state.markFinished(); state.markFinishedAcknowledged()
        assertEquals(State.IDLE, state.snapshot.first().state)
    }
}
```

- [ ] **Step 3: Write the failing backfill worker test**

`data/lyrics/src/test/kotlin/com/stash/data/lyrics/worker/LyricsBackfillWorkerTest.kt`:

Mirror `MetadataBackfillWorkerTest` exactly — Robolectric, in-memory Room, DataStore clear in `@Before` / `@After`, fake `LyricsRepository`. Cases:
1. Empty library — returns success, no resolveAndStore calls
2. Three tracks — drains all three, banner snapshot transitions IDLE → RUNNING → FINISHED
3. resolveAndStore throws — that track is stamped 0L (via setLyricsFetchedAt mock), loop continues

(Full code block omitted for brevity but mirror `MetadataBackfillWorkerTest`. The implementing subagent reads the metadata test as the template.)

- [ ] **Step 4: Run the failing test**

```bash
./gradlew :data:lyrics:test --tests "*LyricsBackfillWorkerTest*"
```

Expected: FAIL with `Unresolved reference: LyricsBackfillWorker`.

- [ ] **Step 5: Implement `LyricsBackfillWorker`**

`data/lyrics/src/main/kotlin/com/stash/data/lyrics/worker/LyricsBackfillWorker.kt`:

```kotlin
package com.stash.data.lyrics.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.TrackDao
import com.stash.data.lyrics.LyricsRepository
import com.stash.data.lyrics.backfill.LyricsBackfillState
import com.stash.data.lyrics.source.LyricsQuery
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

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
        if (total == 0) {
            backfillState.markFinished()
            return Result.success()
        }
        backfillState.markStarted(total)
        var processed = 0
        while (true) {
            val batch = trackDao.getTracksNeedingLyrics(BATCH_SIZE)
            if (batch.isEmpty()) break
            for (track in batch) {
                val query = LyricsQuery(
                    trackId = track.id,
                    title = track.title,
                    artist = track.artist,
                    album = track.album.ifBlank { null },
                    albumArtist = track.albumArtist.ifBlank { null },
                    durationMs = track.durationMs.takeIf { it > 0 },
                    youtubeVideoId = track.videoId.takeIf { it.isNotBlank() },
                )
                runCatching { lyricsRepository.resolveAndStore(query) }
                    .onFailure { trackDao.setLyricsFetchedAt(track.id, 0L) }
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

- [ ] **Step 6: Implement `LyricsBackfillScheduler`**

`data/lyrics/src/main/kotlin/com/stash/data/lyrics/backfill/LyricsBackfillScheduler.kt`:

```kotlin
package com.stash.data.lyrics.backfill

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.stash.data.download.backfill.BackfillVersionTracker
import com.stash.data.lyrics.worker.LyricsBackfillWorker
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LyricsBackfillScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val versionTracker: BackfillVersionTracker,
) {

    suspend fun scheduleIfNeeded() {
        if (!versionTracker.shouldRunForCurrentVersion(LYRICS_BACKFILL_KEY)) return
        workManager.enqueueUniqueWork(
            LyricsBackfillWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LyricsBackfillWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
        versionTracker.markEnqueuedForCurrentVersion(LYRICS_BACKFILL_KEY)
    }

    private companion object {
        const val LYRICS_BACKFILL_KEY = "lyrics_backfill_enqueued_for_version"
    }
}
```

- [ ] **Step 7: Wire `scheduleIfNeeded()` into `StashApplication`**

In `app/src/main/kotlin/com/stash/app/StashApplication.kt`, find the existing `metadataBackfillScheduler.scheduleIfNeeded()` call inside `onCreate`. Add the lyrics call right after, inside the same `applicationScope.launch { ... }`:

```kotlin
applicationScope.launch {
    metadataBackfillScheduler.scheduleIfNeeded()
    lyricsBackfillScheduler.scheduleIfNeeded()
}
```

Inject `lyricsBackfillScheduler: LyricsBackfillScheduler` alongside the metadata one.

- [ ] **Step 8: Run all worker / state tests**

```bash
./gradlew :data:lyrics:test
```

Expected: PASS.

- [ ] **Step 9: Build the app**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add data/lyrics/src/main/kotlin/com/stash/data/lyrics/backfill/ \
        data/lyrics/src/main/kotlin/com/stash/data/lyrics/worker/LyricsBackfillWorker.kt \
        data/lyrics/src/test/kotlin/com/stash/data/lyrics/ \
        app/src/main/kotlin/com/stash/app/StashApplication.kt
git commit -m "$(cat <<'EOF'
feat(lyrics): backfill worker + scheduler + state + wiring

Once-per-version drain of tracks.lyrics_fetched_at IS NULL,
mirroring v0.9.35's MetadataBackfillWorker exactly:
OFFSET=0 + stamp-removes-from-result-set, BATCH_SIZE=50,
NetworkType.CONNECTED + non-expedited expedited policy.

LyricsBackfillState DataStore lives in a separate
lyrics_backfill_state.preferences_pb file so it never collides
with metadata. LyricsBackfillScheduler uses the now-keyed
BackfillVersionTracker with key "lyrics_backfill_enqueued_for_version".

StashApplication.onCreate dispatches both metadata and lyrics
schedulers; they run concurrently (see spec §9.3).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 5 — Hook into download success

Phase end state: every successful download enqueues a post-download `LyricsFetchWorker`.

---

## Task 10: `LyricsFetchTrigger` + hooks in `DownloadManager` + `SearchDownloadCoordinator`

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lyrics/LyricsFetchTrigger.kt`
- Create: `app/src/main/kotlin/com/stash/app/di/LyricsFetchTriggerModule.kt`
- Modify: `data/download/src/main/kotlin/com/stash/data/download/DownloadManager.kt`
- Modify: `data/download/src/main/kotlin/com/stash/data/download/search/SearchDownloadCoordinator.kt`

- [ ] **Step 1: Add the interface in `:data:download`**

```kotlin
// data/download/src/main/kotlin/com/stash/data/download/lyrics/LyricsFetchTrigger.kt
package com.stash.data.download.lyrics

/** Indirection that lets :data:download enqueue a lyrics fetch without depending on :data:lyrics. */
interface LyricsFetchTrigger {
    fun enqueueFor(trackId: String)
}
```

- [ ] **Step 2: Add a no-op default for test builds inside `:data:download`**

```kotlin
// data/download/src/main/kotlin/com/stash/data/download/lyrics/NoOpLyricsFetchTrigger.kt
internal class NoOpLyricsFetchTrigger : LyricsFetchTrigger {
    override fun enqueueFor(trackId: String) = Unit
}
```

(Bind this in the test Hilt module for `:data:download` if needed — otherwise rely on mockk.)

- [ ] **Step 3: Add the production binding in `:app`**

```kotlin
// app/src/main/kotlin/com/stash/app/di/LyricsFetchTriggerModule.kt
package com.stash.app.di

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.Constraints
import com.stash.data.download.lyrics.LyricsFetchTrigger
import com.stash.data.lyrics.worker.LyricsFetchWorker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LyricsFetchTriggerModule {

    @Provides @Singleton
    fun provideLyricsFetchTrigger(workManager: WorkManager): LyricsFetchTrigger = object : LyricsFetchTrigger {
        override fun enqueueFor(trackId: String) {
            workManager.enqueueUniqueWork(
                "${LyricsFetchWorker.UNIQUE_PREFIX_POST_DOWNLOAD}$trackId",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LyricsFetchWorker>()
                    .setInputData(workDataOf(LyricsFetchWorker.KEY_TRACK_ID to trackId))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}
```

- [ ] **Step 4: Hook into `DownloadManager` (lossless path)**

In `DownloadManager.kt`, locate both `trackDao.setMetadataEmbeddedAt(track.id, System.currentTimeMillis())` call sites (around `:288-290` and `:429-431` at authoring; locate by symbol, not line). Add immediately after each:

```kotlin
lyricsFetchTrigger.enqueueFor(track.id)
```

Inject `private val lyricsFetchTrigger: LyricsFetchTrigger` into `DownloadManager`'s constructor (alongside the existing dependencies).

- [ ] **Step 5: Hook into `SearchDownloadCoordinator` (yt-dlp path)**

In `SearchDownloadCoordinator.kt`:

1. Refactor `private fun stampEmbeddedAt(videoId: String)` to return the resolved trackId: `private suspend fun stampEmbeddedAt(videoId: String): String?` — returns the trackId on a successful stamp, null otherwise.
2. At the call site (around `:327`), after the stamp:

```kotlin
val stampedTrackId = stampEmbeddedAt(track.videoId)
stampedTrackId?.let { lyricsFetchTrigger.enqueueFor(it) }
```

3. Inject `private val lyricsFetchTrigger: LyricsFetchTrigger` into `SearchDownloadCoordinator`'s constructor.

- [ ] **Step 6: Build the app**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Run the download module tests**

```bash
./gradlew :data:download:test
```

Expected: PASS. If tests fail because the existing constructor-arg list changed, update the test fixtures (mockk + relaxed=true everywhere; trivial).

- [ ] **Step 8: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lyrics/ \
        data/download/src/main/kotlin/com/stash/data/download/DownloadManager.kt \
        data/download/src/main/kotlin/com/stash/data/download/search/SearchDownloadCoordinator.kt \
        app/src/main/kotlin/com/stash/app/di/LyricsFetchTriggerModule.kt
git commit -m "$(cat <<'EOF'
feat(download): hook lyrics fetch into download success branches

LyricsFetchTrigger interface in :data:download lets DownloadManager
and SearchDownloadCoordinator enqueue a per-track lyrics fetch
without depending on :data:lyrics. Production binding lives in :app
and points at LyricsFetchWorker with the post-download unique-name
prefix.

Hooks land immediately after the existing metadata_embedded_at
stamp at both lossless completion sites (DownloadManager) and the
yt-dlp completion site (SearchDownloadCoordinator). stampEmbeddedAt
refactored to return the trackId so the lookup isn't duplicated.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 6 — UI surfaces

Phase end state: a new Lyrics IconButton on Now Playing opens a `ModalBottomSheet` rendering synced LRC with auto-scroll + tap-to-seek; a Home banner mirrors the metadata one for progress visibility.

Tasks 11 and 12 are mostly independent; Task 13 depends on Task 12.

---

## Task 11: Home `LyricsBackfillBanner` — state, mapper, composable, wiring

**Files:**
- Create: `feature/home/src/main/kotlin/com/stash/feature/home/banner/LyricsBackfillBannerState.kt`
- Create: `feature/home/src/main/kotlin/com/stash/feature/home/banner/LyricsBackfillBanner.kt`
- Test: `feature/home/src/test/kotlin/com/stash/feature/home/banner/LyricsBackfillBannerStateTest.kt`
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeUiState.kt`
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt`
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt`

- [ ] **Step 1: Read the metadata banner files as reference**

```bash
cat feature/home/src/main/kotlin/com/stash/feature/home/banner/MetadataBackfillBannerState.kt
cat feature/home/src/main/kotlin/com/stash/feature/home/banner/MetadataBackfillBanner.kt
cat feature/home/src/test/kotlin/com/stash/feature/home/banner/MetadataBackfillBannerStateTest.kt
```

The lyrics versions mirror these line for line. Copy + adjust names + copy.

- [ ] **Step 2: Write the failing mapper test**

`feature/home/src/test/kotlin/com/stash/feature/home/banner/LyricsBackfillBannerStateTest.kt`:

```kotlin
package com.stash.feature.home.banner

import com.stash.data.lyrics.backfill.LyricsBackfillSnapshot
import com.stash.data.lyrics.backfill.State
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsBackfillBannerStateTest {

    @Test fun `IDLE maps to Hidden`() {
        val snap = LyricsBackfillSnapshot(State.IDLE, 0, 0, null)
        assertEquals(LyricsBackfillBannerState.Hidden, lyricsBackfillBannerStateFor(snap))
    }

    @Test fun `RUNNING with total maps to Running`() {
        val snap = LyricsBackfillSnapshot(State.RUNNING, 10, 100, null)
        assertEquals(LyricsBackfillBannerState.Running(10, 100), lyricsBackfillBannerStateFor(snap))
    }

    @Test fun `RUNNING with zero total maps to Hidden`() {
        val snap = LyricsBackfillSnapshot(State.RUNNING, 0, 0, null)
        assertEquals(LyricsBackfillBannerState.Hidden, lyricsBackfillBannerStateFor(snap))
    }

    @Test fun `FINISHED with total maps to Finished`() {
        val snap = LyricsBackfillSnapshot(State.FINISHED, 100, 100, 1L)
        assertEquals(LyricsBackfillBannerState.Finished(100), lyricsBackfillBannerStateFor(snap))
    }
}
```

- [ ] **Step 3: Run the failing test**

```bash
./gradlew :feature:home:test --tests "*LyricsBackfillBannerStateTest*"
```

Expected: FAIL with `Unresolved reference: LyricsBackfillBannerState`.

- [ ] **Step 4: Implement the sealed type + mapper**

```kotlin
// feature/home/src/main/kotlin/com/stash/feature/home/banner/LyricsBackfillBannerState.kt
package com.stash.feature.home.banner

import com.stash.data.lyrics.backfill.LyricsBackfillSnapshot
import com.stash.data.lyrics.backfill.State

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

- [ ] **Step 5: Run the test — verify it passes**

```bash
./gradlew :feature:home:test --tests "*LyricsBackfillBannerStateTest*"
```

Expected: PASS.

- [ ] **Step 6: Implement the composable**

`feature/home/src/main/kotlin/com/stash/feature/home/banner/LyricsBackfillBanner.kt`:

Copy `MetadataBackfillBanner.kt` verbatim. Replace:
- Type name `MetadataBackfillBannerState` → `LyricsBackfillBannerState`
- Running copy: "Re-tagging library… (X / Y)" → "Fetching lyrics… (X / Y)"
- Finished copy: "X tracks re-tagged" → "Lyrics fetched for X tracks"
- The "Done" pulse dismissal callback should call `lyricsBackfillState.markFinishedAcknowledged()` (passed through ViewModel like the metadata version).

(The implementer should match exact visual style verbatim — same GlassCard wrapper, same icon family if differentiated.)

- [ ] **Step 7: Add the field to `HomeUiState`**

In `feature/home/src/main/kotlin/com/stash/feature/home/HomeUiState.kt`, add alongside `metadataBackfillBanner`:

```kotlin
val lyricsBackfillBanner: LyricsBackfillBannerState = LyricsBackfillBannerState.Hidden,
```

- [ ] **Step 8: Wire `HomeViewModel`**

Inject `lyricsBackfillState: LyricsBackfillState` alongside `metadataBackfillState`. Add to the existing `combine(...)` assembly:

```kotlin
combine(
    /* ...existing flows... */,
    metadataBackfillState.snapshot,
    lyricsBackfillState.snapshot,
) { /* ... */, metaSnap, lyrSnap ->
    /* ... existing assembly ... */
    .copy(
        metadataBackfillBanner = metadataBackfillBannerStateFor(metaSnap),
        lyricsBackfillBanner = lyricsBackfillBannerStateFor(lyrSnap),
    )
}
```

Add `onLyricsBackfillFinishedAcknowledged()` ViewModel action that calls `lyricsBackfillState.markFinishedAcknowledged()`.

- [ ] **Step 9: Render in `HomeScreen.kt`**

Find the existing `MetadataBackfillBanner` call in HomeScreen. Add `LyricsBackfillBanner` directly under it (or directly above, depending on Stash's visual priority — both are short-lived; same vertical ordering as the existing banner). Pass the state field + the ack callback.

- [ ] **Step 10: Compile + install**

```bash
./gradlew :app:installDebug
```

Expected: BUILD SUCCESSFUL. Install on the device.

- [ ] **Step 11: Visual sanity check**

Manually trigger backfill by force-clearing app data on the device and re-launching. Both banners should appear. Verify the lyrics one renders with proper Stash design system (GlassCard + extended Material3 theme).

- [ ] **Step 12: Commit**

```bash
git add feature/home/src/main/kotlin/com/stash/feature/home/banner/LyricsBackfillBannerState.kt \
        feature/home/src/main/kotlin/com/stash/feature/home/banner/LyricsBackfillBanner.kt \
        feature/home/src/test/kotlin/com/stash/feature/home/banner/LyricsBackfillBannerStateTest.kt \
        feature/home/src/main/kotlin/com/stash/feature/home/HomeUiState.kt \
        feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt \
        feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt
git commit -m "$(cat <<'EOF'
feat(home): LyricsBackfillBanner — progress visibility on Home

Mirrors the v0.9.35 MetadataBackfillBanner exactly:
- Sealed Hidden/Running/Finished state with pure mapper
- New independent field on HomeUiState
- GlassCard wrapper, two-second Done pulse, ack-dismiss
- Copy: "Fetching lyrics… (X / Y)" → "Lyrics fetched for X tracks"

Renders under the existing metadata banner; both are short-lived.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: `LyricsBottomSheet` + `LyricsView` + Now Playing wiring

**Files:**
- Create: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/ui/LyricsViewState.kt`
- Create: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/ui/LyricsView.kt`
- Create: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/ui/LyricsBottomSheet.kt`
- Test: `feature/nowplaying/src/test/kotlin/com/stash/feature/nowplaying/ui/LyricsViewStateTest.kt`
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingViewModel.kt`

- [ ] **Step 1: Define `LyricsViewState`**

```kotlin
// feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/ui/LyricsViewState.kt
package com.stash.feature.nowplaying.ui

import com.stash.data.lyrics.parser.LrcLine

sealed interface LyricsViewState {
    object Loading : LyricsViewState
    data class Synced(val lines: List<LrcLine>, val plainFallback: String) : LyricsViewState
    data class Plain(val text: String) : LyricsViewState
    object Instrumental : LyricsViewState
    object None : LyricsViewState
    data class Error(val retryable: Boolean) : LyricsViewState
}
```

- [ ] **Step 2: Write a state-derivation unit test**

`feature/nowplaying/src/test/kotlin/com/stash/feature/nowplaying/ui/LyricsViewStateTest.kt`:

```kotlin
package com.stash.feature.nowplaying.ui

import com.stash.core.data.db.entity.LyricsEntity
import com.stash.core.data.db.entity.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsViewStateTest {

    @Test fun `track NULL stamp with no row maps to Loading`() {
        val state = lyricsViewStateFor(track = trackWithStamp(null), row = null)
        assertEquals(LyricsViewState.Loading, state)
    }

    @Test fun `track 0L stamp with no row maps to None`() {
        val state = lyricsViewStateFor(track = trackWithStamp(0L), row = null)
        assertEquals(LyricsViewState.None, state)
    }

    @Test fun `instrumental row maps to Instrumental`() {
        val row = sampleRow(instrumental = true)
        val state = lyricsViewStateFor(track = trackWithStamp(1L), row = row)
        assertEquals(LyricsViewState.Instrumental, state)
    }

    @Test fun `synced lyrics map to Synced with parsed lines`() {
        val row = sampleRow(syncedLrc = "[00:01.00]hello\n[00:02.50]world", plainText = "hello world")
        val state = lyricsViewStateFor(track = trackWithStamp(1L), row = row)
        assertTrue(state is LyricsViewState.Synced)
        val synced = state as LyricsViewState.Synced
        assertEquals(2, synced.lines.size)
        assertEquals("hello world", synced.plainFallback)
    }

    @Test fun `plain-only row maps to Plain`() {
        val row = sampleRow(syncedLrc = null, plainText = "lyrics")
        val state = lyricsViewStateFor(track = trackWithStamp(1L), row = row)
        assertEquals(LyricsViewState.Plain("lyrics"), state)
    }

    @Test fun `synced parse failing with plain fallback maps to Plain`() {
        val row = sampleRow(syncedLrc = "junk that wont parse", plainText = "fallback")
        val state = lyricsViewStateFor(track = trackWithStamp(1L), row = row)
        assertEquals(LyricsViewState.Plain("fallback"), state)
    }

    @Test fun `synced parse failing with no plain falls back to None`() {
        val row = sampleRow(syncedLrc = "junk", plainText = null)
        val state = lyricsViewStateFor(track = trackWithStamp(1L), row = row)
        assertEquals(LyricsViewState.None, state)
    }

    private fun trackWithStamp(stamp: Long?): TrackEntity = /* same stubTrack helper */ TODO()
    private fun sampleRow(
        instrumental: Boolean = false,
        syncedLrc: String? = null,
        plainText: String? = null,
    ): LyricsEntity = LyricsEntity("t1", plainText, syncedLrc, instrumental, null, "lrclib", "1", 1L)
}
```

- [ ] **Step 3: Run the failing test**

```bash
./gradlew :feature:nowplaying:test --tests "*LyricsViewStateTest*"
```

Expected: FAIL with `Unresolved reference: lyricsViewStateFor`.

- [ ] **Step 4: Implement the state mapper**

Add to `LyricsViewState.kt`:

```kotlin
internal fun lyricsViewStateFor(track: TrackEntity, row: LyricsEntity?): LyricsViewState = when {
    track.lyricsFetchedAt == null -> LyricsViewState.Loading
    track.lyricsFetchedAt == 0L -> LyricsViewState.None
    row?.instrumental == true -> LyricsViewState.Instrumental
    row?.syncedLrc != null -> {
        val lines = com.stash.data.lyrics.parser.LrcParser.parse(row.syncedLrc!!)
        if (lines.isNotEmpty()) LyricsViewState.Synced(lines, row.plainText.orEmpty())
        else if (!row.plainText.isNullOrBlank()) LyricsViewState.Plain(row.plainText!!)
        else LyricsViewState.None
    }
    !row?.plainText.isNullOrBlank() -> LyricsViewState.Plain(row!!.plainText!!)
    else -> LyricsViewState.None
}
```

- [ ] **Step 5: Run the test — verify it passes**

```bash
./gradlew :feature:nowplaying:test --tests "*LyricsViewStateTest*"
```

Expected: PASS.

- [ ] **Step 6: Implement `LyricsView`**

`feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/ui/LyricsView.kt`. This composable renders each `LyricsViewState`:

- `Loading`: centered `CircularProgressIndicator` + "Fetching lyrics…"
- `Synced(lines, _)`: `LazyColumn` of `LrcLine`s with auto-scroll, current-line emphasis, tap-to-seek
- `Plain(text)`: scrollable `Text(text)`
- `Instrumental`: centered "♪ Instrumental"
- `None`: centered "No lyrics found" + `OutlinedButton("Retry", onClick = onRetry)`
- `Error(retryable=true)`: same as None with Retry; `Error(retryable=false)` → "Error" + dismiss

The synced renderer:

```kotlin
@Composable
fun LyricsSyncedRenderer(
    lines: List<LrcLine>,
    currentPositionMs: Long,
    onLineTap: (Long) -> Unit,
) {
    val currentIndex by remember(lines) {
        derivedStateOf {
            lines.indexOfLast { it.timestampMs <= currentPositionMs }.coerceAtLeast(0)
        }
    }
    val listState = rememberLazyListState()
    var lastUserScrollMs by remember { mutableStateOf(0L) }

    // Detect user scroll
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) lastUserScrollMs = System.currentTimeMillis()
    }

    // Auto-scroll guard
    LaunchedEffect(currentIndex) {
        if (System.currentTimeMillis() - lastUserScrollMs > 5_000L) {
            listState.animateScrollToItem(currentIndex.coerceAtMost(lines.size - 1), scrollOffset = -200)
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(lines) { index, line ->
            val current = index == currentIndex
            Text(
                text = line.text,
                style = if (current) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.bodyLarge,
                color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onLineTap(line.timestampMs) }
                    .padding(vertical = 6.dp, horizontal = 24.dp),
            )
        }
    }
}
```

(The exact dim factor, padding, and centering offsets are styling polish — the implementer adjusts to match Stash's existing typography scale.)

- [ ] **Step 7: Implement `LyricsBottomSheet`**

`feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/ui/LyricsBottomSheet.kt`. Mirror `QueueBottomSheet.kt` structure:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsBottomSheet(
    state: LyricsViewState,
    currentPositionMs: Long,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Box(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
            when (state) {
                LyricsViewState.Loading -> CenteredSpinner("Fetching lyrics…")
                is LyricsViewState.Synced -> LyricsSyncedRenderer(state.lines, currentPositionMs, onSeek)
                is LyricsViewState.Plain -> LyricsPlainRenderer(state.text)
                LyricsViewState.Instrumental -> CenteredPlacard("♪ Instrumental")
                LyricsViewState.None -> CenteredPlacard("No lyrics found", action = "Retry", onAction = onRetry)
                is LyricsViewState.Error -> CenteredPlacard("Error", action = if (state.retryable) "Retry" else null, onAction = onRetry)
            }
        }
    }
}
```

- [ ] **Step 8: Wire `NowPlayingViewModel`**

Add fields:
- `private val lyricsRepository: LyricsRepository` injection
- `private val workManager: WorkManager` injection (for priority enqueue)
- `val lyricsViewState: StateFlow<LyricsViewState>` — `combine(track, lyricsRepository.observe(track.id)) { t, row -> lyricsViewStateFor(t, row) }`
- `val currentPositionMs: StateFlow<Long>` — already in the existing player wiring? Verify with `grep -n positionMs feature/nowplaying/src/main`
- `val lyricsSheetOpen: StateFlow<Boolean>` — local state
- `fun onShowLyrics()` — sets sheet open = true; if `lyricsFetchedAt == null`, enqueue priority worker
- `fun onDismissLyrics()` — sets sheet open = false
- `fun onLyricsRetry()` — re-enqueue priority worker (REPLACE policy)
- `fun onLyricsLineSeek(timestampMs: Long)` — calls the existing player controller's `seekTo(timestampMs)`

For the priority enqueue:

```kotlin
private fun enqueuePriorityFetch(trackId: String) {
    workManager.enqueueUniqueWork(
        "${LyricsFetchWorker.UNIQUE_PREFIX_PRIORITY}$trackId",
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<LyricsFetchWorker>()
            .setInputData(workDataOf(LyricsFetchWorker.KEY_TRACK_ID to trackId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build(),
    )
}
```

- [ ] **Step 9: Render `LyricsBottomSheet` in `NowPlayingScreen`**

Add a `val showLyrics by viewModel.lyricsSheetOpen.collectAsStateWithLifecycle()` at the top of the composable. Add at the bottom of the screen:

```kotlin
if (showLyrics) {
    val lyricsState by viewModel.lyricsViewState.collectAsStateWithLifecycle()
    val positionMs by viewModel.currentPositionMs.collectAsStateWithLifecycle()
    LyricsBottomSheet(
        state = lyricsState,
        currentPositionMs = positionMs,
        onSeek = viewModel::onLyricsLineSeek,
        onRetry = viewModel::onLyricsRetry,
        onDismiss = viewModel::onDismissLyrics,
    )
}
```

- [ ] **Step 10: Build + install**

```bash
./gradlew :app:installDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 11: Spot-check on device**

Open Now Playing on any track with already-fetched lyrics (or trigger backfill first). The Lyrics IconButton in Task 13 doesn't exist yet — for this task, temporarily invoke `viewModel.onShowLyrics()` from an existing button (e.g., long-press a control) to verify the sheet opens, renders, and dismisses. (This is throwaway — Task 13 adds the real button.)

- [ ] **Step 12: Commit**

```bash
git add feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/ui/LyricsView.kt \
        feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/ui/LyricsViewState.kt \
        feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/ui/LyricsBottomSheet.kt \
        feature/nowplaying/src/test/kotlin/com/stash/feature/nowplaying/ui/LyricsViewStateTest.kt \
        feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingViewModel.kt
git commit -m "$(cat <<'EOF'
feat(nowplaying): LyricsBottomSheet — synced rendering + tap-to-seek

ModalBottomSheet that mirrors QueueBottomSheet (skipPartiallyExpanded).
Auto-follows the playing track via Flow<LyricsEntity?>; on first open
of a NULL-stamped track, enqueues a priority LyricsFetchWorker
(expedited, REPLACE policy).

Synced renderer: LazyColumn with current line emphasized
(headlineMedium, full alpha) and surrounding lines dimmed
(bodyLarge, alpha 0.45). Auto-scroll centers the current line;
user-drag pauses auto-scroll for 5 seconds then resumes.
Tap any line → seekTo(timestampMs).

Loading / Instrumental / None / Error placards built-in.
Retry button on None re-enqueues the priority worker.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: Lyrics IconButton in Now Playing TopBar

**Files:**
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt`

- [ ] **Step 1: Locate the TopBar row**

```bash
grep -n "IconButton\|TopBar" feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt | head -20
```

Expected: a `Row` containing IconButtons (Dismiss / Flag / Like / Download / Save / Queue) around line 241.

- [ ] **Step 2: Add the Lyrics IconButton between Save and Queue**

Insert immediately after the Save IconButton, before the Queue IconButton:

```kotlin
IconButton(onClick = viewModel::onShowLyrics) {
    Icon(
        imageVector = Icons.Outlined.Lyrics,
        contentDescription = "Lyrics",
    )
}
```

Import `androidx.compose.material.icons.outlined.Lyrics`. If that symbol isn't resolved (icon extension set may not export it under that name), use `Icons.Outlined.Subject` as a fallback — verify by trying both.

- [ ] **Step 3: Build + install**

```bash
./gradlew :app:installDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: On-device check**

On the device, open Now Playing. Verify:
- A 7th IconButton appears between Save and Queue.
- Tap opens the lyrics sheet (verifying Task 12 wiring).
- TopBar layout doesn't overflow on the device (Pixel 6 Pro is comfortable; narrower devices should still fit since 7 24dp icons + 48dp tap targets fit on ≥360dp).

If overflow appears on a narrow device, do NOT propose a restructure (per `feedback_preserve_existing_design.md`) — instead, file a follow-up that addresses the rendering (e.g., compact icon spacing on narrow screens) and proceed with the existing PR.

- [ ] **Step 5: Remove the throwaway invocation from Task 12 Step 11**

If a temporary trigger was added to test the sheet during Task 12, remove it now.

- [ ] **Step 6: Commit**

```bash
git add feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt
git commit -m "$(cat <<'EOF'
feat(nowplaying): Lyrics IconButton in TopBar — invokes LyricsBottomSheet

Adds a 7th IconButton between Save and Queue: Dismiss · Flag · Like
· Download · Save · Lyrics · Queue. Tap opens the lyrics sheet
shipped in the previous commit. No other icon moves.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 7 — Validation

---

## Task 14: On-device validation pass

**Files:** none modified. This is a manual / scripted validation step.

- [ ] **Step 1: Bump versionName + versionCode**

In `app/build.gradle.kts`, bump to:
- `versionName = "0.9.36"`
- `versionCode = 9036` (or whatever the +1 from current is)

Commit:

```bash
git commit -am "$(cat <<'EOF'
chore(release): bump versionCode + versionName to 0.9.36

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 2: Run the full unit test sweep**

```bash
./gradlew test
```

Expected: PASS (or fail only on pre-existing failures called out in the conventions section).

- [ ] **Step 3: Run the assembly**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Install on Pixel 6 Pro**

```bash
./gradlew :app:installDebug
```

Expected: APK installs on the device.

- [ ] **Step 5: Pre-validation logcat tail**

```bash
adb logcat -c
adb logcat | grep -i -E "LyricsRepository|LyricsFetchWorker|LyricsBackfillWorker|LrcParser|LyricsSidecarWriter" > /tmp/lyrics-validation.log &
```

(Keep this running; ⌃C at end of validation.)

- [ ] **Step 6: Validate post-download fetch**

Sync the same 25-track Spotify playlist that validated v0.9.35. Within ~30 seconds of each track downloading, verify:
- `tracks.lyrics_fetched_at` is non-null for the track row (use `adb shell run-as com.stash.debug ls databases/`, pull the DB, sqlite3 inspect).
- For tracks LRCLIB has: a `lyrics` row exists with `synced_lrc IS NOT NULL`.
- The `.lrc` sidecar file is present next to the audio file. `adb shell ls -la /sdcard/Android/data/com.stash.debug/files/music/<artist>/<album>/` should show `<basename>.lrc`. `adb shell cat <path>` should show `[ti:...][ar:...][al:...][length:...][by:Stash]\n<body>`.

- [ ] **Step 7: Validate the lyrics sheet UX**

- Open Now Playing on **Kanye - Off The Grid**. Tap the Lyrics IconButton (slot 6 of 7 in TopBar). Verify:
  - Sheet pulls up to ~95% height (matches Queue / Save sheets).
  - Synced rendering: current line emphasized, surrounding lines dimmed, auto-scroll tracks playback.
  - Tap a line several seconds ahead: playback seeks to that timestamp.
  - User-drag the lyrics LazyColumn: auto-scroll suspends; after ~5 seconds idle it re-centers on current line.
- Open Now Playing on **Vince Guaraldi Trio - Linus And Lucy**. Tap Lyrics. Verify: "♪ Instrumental" placard.
- Pick a fresh-synced obscure track with no LRCLIB hit. Tap Lyrics. Verify: spinner → "No lyrics found" + Retry button. Tap Retry, verify spinner returns then settles back to None.

- [ ] **Step 8: Validate auto-follow on track change**

Open the sheet on track A. Let A finish. Verify the sheet's lyrics content swaps to track B without closing.

- [ ] **Step 9: Validate backfill resume**

Force-stop the app at ~30% through a backfill run (`adb shell am force-stop com.stash.debug`). Relaunch. Verify backfill resumes and completes. The Home banner should show progress.

- [ ] **Step 10: Validate SAF write (optional, if device has SAF storage configured)**

If the device's storage preference is set to a SAF tree, run a single download and verify `.lrc` lands in the SAF tree under the same `<artist>/<album>/` structure as the audio file.

- [ ] **Step 11: Logcat sweep**

Stop the background logcat tail (⌃C). Inspect `/tmp/lyrics-validation.log`. Verify zero `WARN` or `ERROR` lines from `LyricsRepository`, `LyricsFetchWorker`, `LyricsBackfillWorker`, `LrcParser`, `LyricsSidecarWriter`. Acceptable: `INFO` lines about successful fetches and miss stamps.

- [ ] **Step 12: File follow-up issues for anything surprising**

If any of the above produced unexpected behaviour (icon overflow on a small screen, parser misbehaving on an unusual LRC body, etc.), open a GitHub issue against `rawnaldclark/Stash` — do not amend the v0.9.36 PR. Issues should reference the validation step number.

- [ ] **Step 13: Push the branch + open PR**

**STOP HERE — user gates the PR opening and the eventual release tag.** Surface the branch state and the validation log to the user; await explicit go-ahead before `git push` or `gh pr create`.

If the user approves:

```bash
git push -u origin feat/lyrics-integration
gh pr create --title "v0.9.36 — lyrics integration (LRCLIB + sidecar + sheet)" --body "$(cat <<'EOF'
## Summary
- Adds LRCLIB-primary + InnerTube-fallback lyrics fetch on every successful download
- Persists synced + plain lyrics to a new Room v28 lyrics table
- Writes sidecar .lrc next to every audio file (internal + SAF)
- New Lyrics IconButton in Now Playing TopBar → ModalBottomSheet with auto-follow + tap-to-seek

## Spec
docs/superpowers/specs/2026-05-23-lyrics-integration-design.md

## Test plan
- [ ] Sync 25-track Spotify playlist; verify lyrics + sidecars
- [ ] Open Now Playing on Kanye - Off The Grid; verify synced render + tap-to-seek
- [ ] Open Now Playing on instrumental track (Linus And Lucy); verify Instrumental placard
- [ ] Verify Retry button on a known-miss track
- [ ] Verify auto-follow on track change
- [ ] Verify backfill resume across force-stop
- [ ] Logcat sweep — zero warnings/errors from lyrics modules

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

The implementer notes the PR URL for the user.

---

# Release prep (after user approves PR merge)

Per `feedback_ship_terminology.md`, "ship" = tag + push, not local install. The release tag and the GitHub Release are the user's call, not the implementer's. After PR merges to master:

- User reviews on-device on a fresh APK from the release CI workflow.
- User runs:
  ```bash
  git checkout master && git pull origin master
  git tag -a v0.9.36 -m "v0.9.36 — lyrics integration"
  git push origin v0.9.36
  ```
- `.github/workflows/release.yml` produces the signed APK; user finalises the GitHub Release body.

---

# Appendix — Pre-existing failures to ignore in test sweeps

Per the conventions section, the master test suite carries known pre-existing failures from prior plans. Do NOT block on these. Surface to the user only if a NEW test introduced in this plan fails.

# Appendix — Out-of-scope confirmation (do NOT do these in v0.9.36)

- Manual lyric search / per-track edit override
- Sidecar **read** (user-dropped .lrc files override Stash's fetch)
- KuGou source / CJK coverage gap
- Word-level highlighting
- AI translation
- Karaoke / big-text mode
- USLT / SYLT / LYRICS tag embedded in audio file
- Lyrics share / export
- LRCLIB community contribution UX

All listed in spec § "Out of scope (Phase 2 and beyond)". If any of these are tempting during implementation, write the temptation into a follow-up issue and do not include in this PR.
