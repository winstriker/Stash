# Loudness Normalization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add per-track loudness normalization to Stash so every track plays at a consistent perceived volume, matching Spotify/Tidal/YT Music behavior at the −14 LUFS target.

**Architecture:** Two new Media3 `BaseAudioProcessor`s (`LoudnessGainProcessor`, `SoftClipLimiterProcessor`) appended to the existing DSP chain in `StashRenderersFactory`. A new `LoudnessController` singleton (mirroring the established `EqController` pattern) holds state and is updated on each `Player.Listener.onMediaItemTransition`. Per-track loudness data is produced by an FFmpeg `ebur128` invocation at the download path (via `TrackFinalizer`) and by a `WorkManager` periodic worker that backfills the pre-existing library while the device is charging + idle. Results live in three new `tracks` columns (`loudness_lufs`, `true_peak_dbfs`, `loudness_measured_at`).

**Tech Stack:** Kotlin / Compose / Hilt / Media3 ExoPlayer / Room / WorkManager / Preferences DataStore / `com.yausername.ffmpeg.FFmpeg`. JVM unit tests via JUnit + Truth.

**Spec:** `docs/superpowers/specs/2026-05-14-loudness-normalization-design.md`

---

## File Structure (decomposition locked in here)

New files (Kotlin):

| Path | Responsibility |
|---|---|
| `core/data/src/main/kotlin/com/stash/core/data/audio/FFmpegBridge.kt` | Thin adapter around `com.yausername.ffmpeg.FFmpeg` exposing `suspend fun runWithStderrCapture(args): String`. Sole purpose: testability — the parser can run against canned stderr fixtures. |
| `core/data/src/main/kotlin/com/stash/core/data/audio/LoudnessMeasurer.kt` | Invokes FFmpeg ebur128, parses stderr, returns `Result.Success(lufs, peak)` or `Result.Failed(reason)`. App-wide `Mutex` for single-flight. |
| `core/data/src/main/kotlin/com/stash/core/data/audio/LoudnessProgressStore.kt` | Tiny Preferences DataStore: `totalRemaining: Int` + `lastCompletedAt: Long`. Read by EQ-screen ViewModel as a `Flow`. |
| `core/data/src/main/kotlin/com/stash/core/data/sync/workers/LoudnessBackfillWorker.kt` | `@HiltWorker`. Pulls 20 un-measured tracks, measures, writes back, updates progress store. Constraints: charging + deviceIdle + batteryNotLow. |
| `core/media/src/main/kotlin/com/stash/core/media/equalizer/LoudnessState.kt` | `data class LoudnessState` (in-memory state). Top-level `fun computeGain(...)`. |
| `core/media/src/main/kotlin/com/stash/core/media/equalizer/LoudnessStore.kt` | Preferences DataStore wrapper for `LoudnessState`. Mirrors `EqStore` shape. |
| `core/media/src/main/kotlin/com/stash/core/media/equalizer/LoudnessController.kt` | Singleton state owner. `runBlocking { store.read() }` in `init`. `setEnabled(Boolean)`. `setCurrentTrackGain(Float)`. |
| `core/media/src/main/kotlin/com/stash/core/media/equalizer/dsp/LoudnessGainProcessor.kt` | `BaseAudioProcessor`. Reads `controller.state.value` snapshot per buffer; applies linear gain with 15 ms ramp at gain changes. |
| `core/media/src/main/kotlin/com/stash/core/media/equalizer/dsp/SoftClipLimiterProcessor.kt` | `BaseAudioProcessor`. Lookahead sample-peak limiter: −1 dBFS threshold, 1 ms attack, 50 ms release, 2 ms lookahead. |

New test files mirror the above, one-to-one, in each module's `src/test/`.

UI composable additions stay inside `feature/settings/.../equalizer/EqualizerScreen.kt` (no new file).

Files modified:

- `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt` — bump `version = 23 → 24`, add `MIGRATION_23_24`, register in `Companion`.
- `core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt` — three nullable columns.
- `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt` — three new methods: `tracksNeedingLoudness`, `updateLoudness`, `markLoudnessFailed`.
- `data/download/src/main/kotlin/com/stash/data/download/shared/TrackFinalizer.kt` — extend `FinalizeResult.Success` with `loudness: LoudnessMeasurer.Result.Success?` field; add measurement step after probe.
- All `TrackFinalizer` callers (`DownloadManager`, `SearchDownloadCoordinator`) — pull loudness off the result, pass to existing DB-write path.
- `core/media/src/main/kotlin/com/stash/core/media/equalizer/StashRenderersFactory.kt` — append `LoudnessGainProcessor` and `SoftClipLimiterProcessor` to the processor array, gated by build-time const `ENABLE_LOUDNESS`.
- `core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt` (or wherever the `Player.Listener` is wired) — register `onMediaItemTransition` callback that calls `loudnessController.setCurrentTrackGain(...)`.
- `app/src/main/kotlin/com/stash/app/StashApplication.kt` — register `LoudnessBackfillWorker.schedulePeriodic(this)` alongside other workers.
- `feature/settings/src/main/kotlin/com/stash/feature/settings/equalizer/EqualizerScreen.kt` — new `LoudnessCard` composable + Snackbar for first-run notice.
- `feature/settings/src/main/kotlin/com/stash/feature/settings/equalizer/EqualizerViewModel.kt` — combine `loudnessController.state` + `loudnessProgressStore.flow` into a `loudnessUiState` flow.
- `app/build.gradle.kts` — bump `versionCode 61 → 62`, `versionName 0.9.24 → 0.9.25`.

---

## Sequencing rationale

Bottom-up so each task's outputs can be unit-tested before higher layers depend on them:

1. DB schema (no callers yet).
2. Pure data + math (`LoudnessState`, `computeGain`).
3. DSP processors (depend only on a controller stub — controller comes later).
4. Persistence (`LoudnessStore`, `LoudnessProgressStore`).
5. Controller (depends on Store).
6. Measurement (`FFmpegBridge`, `LoudnessMeasurer`).
7. Worker (depends on Measurer + DAO).
8. Wiring (`TrackFinalizer`, callers, `StashRenderersFactory`, `Player.Listener`, `StashApplication`).
9. UI (`EqualizerViewModel`, `EqualizerScreen`, first-run notice).
10. Version bump + manual on-device validation.

---

## Conventions

- **Commit after each task passes its tests.** Each task lists the exact `git add` paths + a one-line commit message. Frequent commits = easy bisect later.
- **Gradle invocations.** Use `./gradlew` on Linux/macOS or `.\gradlew.bat` on Windows. This project's existing memory notes (`feedback_install_after_fix.md`) require `:app:installDebug` after every fix — so the final task includes that.
- **Tests live next to code.** A processor in `core/media/.../dsp/Foo.kt` has its test in `core/media/.../dsp/FooTest.kt` (path mirrored under `src/test/`).
- **No emojis, no fluff comments.** Match the existing style (see `PreampProcessor.kt` for the canonical example).
- **Don't write to ALL_CAPS_KEYS in DataStore.** This project uses lowercase snake_case keys (`eq_state_v1_json`). Follow suit.

---

### Task 1: DB schema delta — Room migration + entity columns

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt` (bump version; add migration)
- Test: `core/data/src/test/kotlin/com/stash/core/data/db/MigrationTest.kt` (add or extend test for 23 → 24)

- [ ] **Step 1: Add the failing migration test**

In `core/data/src/test/kotlin/com/stash/core/data/db/MigrationTest.kt` (create if absent; follow `MigrationTestHelper` boilerplate from Room docs). Test asserts:

```kotlin
@Test
fun migrate23To24_addsLoudnessColumns() {
    helper.createDatabase(TEST_DB, 23).apply {
        execSQL("INSERT INTO tracks (id, title, artist, album, duration_ms, file_path, file_format, quality_kbps, file_size_bytes, source, date_added, play_count, is_downloaded, canonical_title, canonical_artist, match_confidence, match_dismissed, match_flagged, lastfm_user_loved) VALUES (1, 't', 'a', '', 0, '/x', 'mp3', 0, 0, 'YOUTUBE', 0, 0, 1, 't', 'a', 0.0, 0, 0, 0)")
        close()
    }
    val db = helper.runMigrationsAndValidate(TEST_DB, 24, true, StashDatabase.MIGRATION_23_24)
    db.query("SELECT loudness_lufs, true_peak_dbfs, loudness_measured_at FROM tracks WHERE id = 1").use { c ->
        assertThat(c.moveToFirst()).isTrue()
        assertThat(c.isNull(0)).isTrue()
        assertThat(c.isNull(1)).isTrue()
        assertThat(c.isNull(2)).isTrue()
    }
}
```

- [ ] **Step 2: Run and verify fail**

Run: `./gradlew :core:data:testDebug --tests "*MigrationTest.migrate23To24*"`
Expected: FAIL with "MIGRATION_23_24 is not defined" or similar reference error.

- [ ] **Step 3: Implement the migration + entity columns**

In `StashDatabase.kt`:
- Bump `version = 23` to `version = 24`.
- Add inside the `companion object`:

```kotlin
/**
 * v23 → v24: per-track loudness metadata for BS.1770 normalization.
 *
 * Three nullable columns: `loudness_lufs` (integrated LUFS, null = not measured,
 * NaN = measurement attempted-and-failed sentinel), `true_peak_dbfs` (sample-peak
 * in dBFS, negative), `loudness_measured_at` (epoch-ms timestamp of the
 * measurement attempt; usable for stale-measurement detection if the algorithm
 * ever changes and for the weekly NaN-resurrection query).
 */
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN loudness_lufs REAL")
        db.execSQL("ALTER TABLE tracks ADD COLUMN true_peak_dbfs REAL")
        db.execSQL("ALTER TABLE tracks ADD COLUMN loudness_measured_at INTEGER")
    }
}
```

Register `MIGRATION_23_24` in whichever Hilt module currently lists migrations (search for `MIGRATION_22_23` to find it).

In `TrackEntity.kt`, add the three matching `@ColumnInfo` fields. Use `Float?` for the REAL columns and `Long?` for the INTEGER column. Default all three to `null`.

- [ ] **Step 4: Run and verify pass**

Run: `./gradlew :core:data:testDebug --tests "*MigrationTest.migrate23To24*"`
Expected: PASS.

Then run: `./gradlew :core:data:compileDebugKotlin` to confirm entity compiles cleanly.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt \
        core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt \
        core/data/src/test/kotlin/com/stash/core/data/db/MigrationTest.kt \
        core/data/schemas/com.stash.core.data.db.StashDatabase/24.json
git commit -m "feat(db): v24 migration adds loudness_lufs, true_peak_dbfs, loudness_measured_at columns"
```

(Room will auto-generate `24.json` on the next build because `exportSchema = true`. If the build fails for missing schema, run `./gradlew :core:data:compileDebugKotlin` to generate it, then add it to the commit.)

---

### Task 2: TrackDao methods — query unmeasured, write loudness, mark failed

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/db/dao/TrackDaoLoudnessTest.kt` (create)

- [ ] **Step 1: Add the failing DAO test**

Create `TrackDaoLoudnessTest.kt`. Use the existing in-memory-DB test scaffolding pattern (search for any `*DaoTest.kt` in `core/data/src/test/` to match it). Tests:

```kotlin
@Test fun tracksNeedingLoudness_returnsRowsWithNullMeasuredAt() { ... }
@Test fun tracksNeedingLoudness_excludesMeasuredRows() { ... }
@Test fun tracksNeedingLoudness_excludesFailedSentinelRows() { ... }
@Test fun updateLoudness_writesAllThreeColumns() { ... }
@Test fun markLoudnessFailed_writesNanLufsAndTimestamp() { ... }
```

For each, insert a few `TrackEntity`s with combinations of null / non-null / NaN values in the new columns and assert the expected query results.

- [ ] **Step 2: Run and verify fail**

Run: `./gradlew :core:data:testDebug --tests "*TrackDaoLoudnessTest*"`
Expected: FAIL with "Unresolved reference: tracksNeedingLoudness".

- [ ] **Step 3: Implement the DAO methods**

In `TrackDao.kt`, add:

```kotlin
@Query("""
    SELECT * FROM tracks
    WHERE loudness_measured_at IS NULL
      AND file_path IS NOT NULL
    LIMIT :limit
""")
suspend fun tracksNeedingLoudness(limit: Int): List<TrackEntity>

@Query("""
    UPDATE tracks
    SET loudness_lufs = :lufs,
        true_peak_dbfs = :peak,
        loudness_measured_at = :now
    WHERE id = :id
""")
suspend fun updateLoudness(id: Long, lufs: Float, peak: Float, now: Long)

/**
 * Marks a track's measurement as attempted-and-failed.
 * Writes `loudness_lufs = Float.NaN` (sentinel) + the wall-clock timestamp.
 * `tracksNeedingLoudness` skips these rows; a weekly resurrection query
 * picks them up after 7 days for a single retry attempt.
 */
@Query("""
    UPDATE tracks
    SET loudness_lufs = :nanSentinel,
        loudness_measured_at = :now
    WHERE id = :id
""")
suspend fun markLoudnessFailed(id: Long, now: Long, nanSentinel: Float = Float.NaN)
```

Note: SQLite stores NaN as a REAL that won't equal itself. The `IS NULL` in `tracksNeedingLoudness` skips both un-measured AND failed-sentinel rows automatically because `loudness_measured_at` is set when failure is recorded.

- [ ] **Step 4: Run and verify pass**

Run: `./gradlew :core:data:testDebug --tests "*TrackDaoLoudnessTest*"`
Expected: all 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt \
        core/data/src/test/kotlin/com/stash/core/data/db/dao/TrackDaoLoudnessTest.kt
git commit -m "feat(db): TrackDao methods for loudness query + write + failed-sentinel"
```

---

### Task 3: LoudnessState data class + computeGain pure function

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/equalizer/LoudnessState.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/equalizer/LoudnessStateTest.kt`

- [ ] **Step 1: Add the failing test**

```kotlin
package com.stash.core.media.equalizer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LoudnessStateTest {
    @Test fun computeGain_nullLufs_returnsZero() {
        assertThat(computeGain(trackLufs = null, trackPeakDbfs = null)).isEqualTo(0f)
    }

    @Test fun computeGain_nanLufs_returnsZero() {
        assertThat(computeGain(trackLufs = Float.NaN, trackPeakDbfs = null)).isEqualTo(0f)
    }

    @Test fun computeGain_quietTrack_boostsByDifference() {
        // target = -14, track = -20  →  +6 dB
        assertThat(computeGain(-20f, -3f)).isWithin(0.001f).of(6f)
    }

    @Test fun computeGain_loudTrack_attenuatesByDifference() {
        // target = -14, track = -8  →  -6 dB
        assertThat(computeGain(-8f, -1f)).isWithin(0.001f).of(-6f)
    }

    @Test fun computeGain_clampsToPositive12dbMax() {
        // track = -40, raw would be +26 dB, capped at +12
        assertThat(computeGain(-40f, -10f)).isWithin(0.001f).of(12f)
    }

    @Test fun computeGain_clampsToNegative15dbMin() {
        // track = +5, raw would be -19 dB, capped at -15
        assertThat(computeGain(+5f, 0f)).isWithin(0.001f).of(-15f)
    }

    @Test fun computeGain_peakAwareCapPreventsClip() {
        // track = -20 (raw +6 dB), but peak is -0.5 dBFS so peakRoom = -0.5
        // Result: min(+6, -0.5) = -0.5
        assertThat(computeGain(-20f, -0.5f)).isWithin(0.001f).of(-0.5f)
    }

    @Test fun computeGain_nullPeak_skipsPeakCap() {
        // No peak data → use full computed gain
        assertThat(computeGain(-20f, null)).isWithin(0.001f).of(6f)
    }
}
```

- [ ] **Step 2: Run and verify fail**

Run: `./gradlew :core:media:testDebug --tests "*LoudnessStateTest*"`
Expected: FAIL with "Unresolved reference: computeGain".

- [ ] **Step 3: Implement**

Create `LoudnessState.kt`:

```kotlin
package com.stash.core.media.equalizer

import kotlinx.serialization.Serializable

/**
 * In-memory loudness state held by [LoudnessController].
 *
 * `currentTrackGainDb` is the gain to apply *right now* (after the smoothing
 * ramp has run to completion); `currentTargetGainDb` is what we're ramping
 * toward when a track transition fires.
 */
@Serializable
data class LoudnessState(
    val enabled: Boolean = true,
    val targetLufs: Float = -14f,
    val currentTrackGainDb: Float = 0f,
    val currentTargetGainDb: Float = 0f,
)

/**
 * Compute the per-track gain in dB to reach [target] LUFS, with two safety belts:
 *
 *  1. Hard caps at −15 / +12 dB so a podcast at −40 LUFS doesn't get +26 dB boost.
 *  2. Peak-aware cap so the gain never lifts the track's peak above −1 dBFS; the
 *     [SoftClipLimiterProcessor] catches residual peaks from EQ/Bass stages.
 *
 * Returns 0 dB (bypass) when [trackLufs] is null or NaN (un-measured or
 * measurement-failed track).
 */
fun computeGain(
    trackLufs: Float?,
    trackPeakDbfs: Float?,
    target: Float = -14f,
): Float {
    if (trackLufs == null || trackLufs.isNaN()) return 0f
    val raw = target - trackLufs
    val capped = raw.coerceIn(-15f, +12f)
    val peakRoom = if (trackPeakDbfs != null) (-1f) - trackPeakDbfs else Float.MAX_VALUE
    return minOf(capped, peakRoom)
}
```

- [ ] **Step 4: Run and verify pass**

Run: `./gradlew :core:media:testDebug --tests "*LoudnessStateTest*"`
Expected: all 8 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/equalizer/LoudnessState.kt \
        core/media/src/test/kotlin/com/stash/core/media/equalizer/LoudnessStateTest.kt
git commit -m "feat(loudness): LoudnessState + computeGain pure function"
```

---

### Task 4: LoudnessStore — Preferences DataStore wrapper

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/equalizer/LoudnessStore.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/equalizer/LoudnessStoreTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
class LoudnessStoreTest {
    // Use TestPreferenceDataStore or similar — match existing EqStoreTest scaffolding.

    @Test fun read_missingKey_returnsDefaultEnabledTrue() {
        // …default LoudnessState has enabled=true, target=-14
    }

    @Test fun write_then_read_roundTrips() {
        // …write a state with enabled=false, target=-11, read back
    }

    @Test fun read_corruptJson_returnsDefault() {
        // …writeRaw "{bad}" then read returns LoudnessState()
    }
}
```

(Copy the structure of `EqStoreTest.kt` if present, or write the harness fresh.)

- [ ] **Step 2: Run and verify fail**

Run: `./gradlew :core:media:testDebug --tests "*LoudnessStoreTest*"`
Expected: FAIL with "Unresolved reference: LoudnessStore".

- [ ] **Step 3: Implement**

Create `LoudnessStore.kt` mirroring `EqStore.kt` byte-for-byte where possible. Key constant: `stringPreferencesKey("loudness_state_v1_json")`. Use the same JSON config (`ignoreUnknownKeys = true`, `encodeDefaults = true`).

- [ ] **Step 4: Pass**

Run: `./gradlew :core:media:testDebug --tests "*LoudnessStoreTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/equalizer/LoudnessStore.kt \
        core/media/src/test/kotlin/com/stash/core/media/equalizer/LoudnessStoreTest.kt
git commit -m "feat(loudness): LoudnessStore — Preferences DataStore wrapper for LoudnessState"
```

---

### Task 5: LoudnessController — singleton state owner

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/equalizer/LoudnessController.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/equalizer/LoudnessControllerTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
class LoudnessControllerTest {
    @Test fun init_restoresStateFromStoreSynchronously() { /* runBlocking guarantee */ }
    @Test fun setEnabled_updatesStateFlow() { ... }
    @Test fun setCurrentTrackGain_updatesStateFlow() { ... }
    @Test fun setEnabled_persistsAfterDebounce() { /* 250ms delay then verify store.write */ }
}
```

Use a fake `LoudnessStore` that records calls.

- [ ] **Step 2: Fail**

Run: `./gradlew :core:media:testDebug --tests "*LoudnessControllerTest*"`
Expected: FAIL "Unresolved reference: LoudnessController".

- [ ] **Step 3: Implement**

Create `LoudnessController.kt`. Closely mirror `EqController.kt:30-105`:

```kotlin
@Singleton
class LoudnessController @Inject constructor(
    private val store: LoudnessStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(LoudnessState())
    val state: StateFlow<LoudnessState> = _state.asStateFlow()
    private var pendingWrite: Job? = null

    init {
        runBlocking { _state.value = store.read() }
    }

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    /**
     * Called by the player listener on each track transition. The DSP
     * processor reads `currentTrackGainDb` per buffer; setting the target
     * here triggers the ramp inside the processor.
     */
    fun setCurrentTrackGain(gainDb: Float) = update {
        it.copy(currentTrackGainDb = gainDb, currentTargetGainDb = gainDb)
    }

    suspend fun flush() {
        pendingWrite?.cancel()
        store.write(_state.value)
    }

    private fun update(transform: (LoudnessState) -> LoudnessState) {
        _state.value = transform(_state.value)
        pendingWrite?.cancel()
        pendingWrite = scope.launch {
            delay(DEBOUNCE_MS)
            store.write(_state.value)
        }
    }

    companion object { private const val DEBOUNCE_MS = 200L }
}
```

Note: unlike `EqController`, this controller doesn't have a migration step — there's no legacy persisted state to migrate.

- [ ] **Step 4: Pass**

Run: `./gradlew :core:media:testDebug --tests "*LoudnessControllerTest*"`
Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/equalizer/LoudnessController.kt \
        core/media/src/test/kotlin/com/stash/core/media/equalizer/LoudnessControllerTest.kt
git commit -m "feat(loudness): LoudnessController singleton, mirrors EqController shape"
```

---

### Task 6: LoudnessGainProcessor — DSP gain stage with ramp

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/equalizer/dsp/LoudnessGainProcessor.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/equalizer/dsp/LoudnessGainProcessorTest.kt`

- [ ] **Step 1: Failing test**

Pattern matches `PreampProcessorTest.kt` (look it up first). Tests:

```kotlin
class LoudnessGainProcessorTest {
    @Test fun nonPcm16Input_throwsUnhandledFormat() {
        val p = LoudnessGainProcessor(fakeController(state = LoudnessState()))
        assertThrows<UnhandledAudioFormatException> {
            p.configure(AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT))
        }
    }

    @Test fun disabled_passesSamplesThroughUnchanged() {
        val controller = fakeController(state = LoudnessState(enabled = false, currentTrackGainDb = 6f))
        val out = runProcessor(LoudnessGainProcessor(controller), inputPcm16 = sineWave(...))
        assertSampleEquivalence(out, inputPcm16)
    }

    @Test fun positiveGain_amplifiesSamples() {
        val controller = fakeController(state = LoudnessState(enabled = true, currentTrackGainDb = 6f))
        // 6 dB ≈ 2× linear. Run a stable signal past the ramp; verify amplification.
    }

    @Test fun negativeGain_attenuatesSamples() {
        // -6 dB ≈ 0.5× linear.
    }

    @Test fun gainChange_rampsOverConfiguredSamples() {
        // Start with currentTrackGainDb=0, queue some samples, change to +12 dB
        // mid-stream, verify the gain is linearly ramping during the first 15ms
        // of subsequent samples and converges at the target.
    }

    @Test fun extremePositiveGain_doesNotOverflow() {
        // +24 dB on a near-full-scale signal — verify output is clamped to Short.MAX_VALUE.
    }
}
```

Helpers: `fakeController` returns an object with a `MutableStateFlow<LoudnessState>` you can mutate from the test. `runProcessor` wires `configure` → `queueInput` → `getOutput` and returns a `ShortArray`.

- [ ] **Step 2: Fail**

Run: `./gradlew :core:media:testDebug --tests "*LoudnessGainProcessorTest*"`
Expected: FAIL.

- [ ] **Step 3: Implement**

Create `LoudnessGainProcessor.kt`:

```kotlin
package com.stash.core.media.equalizer.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.stash.core.media.equalizer.LoudnessController
import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * Per-track linear-gain stage for loudness normalization.
 *
 * Reads [LoudnessController.state] snapshot per buffer. When the controller's
 * gain changes (new track transition), linearly ramps from the previous gain
 * to the new gain over [RAMP_MS] milliseconds — prevents the audible click
 * a hard gain step would produce at track boundaries.
 *
 * Operates on `PCM_16BIT` only (Media3's user-processor branch — see
 * [com.stash.core.media.equalizer.PreampProcessor]'s KDoc for the float-output
 * bypass note).
 */
@OptIn(UnstableApi::class)
class LoudnessGainProcessor(
    private val controller: LoudnessController,
) : BaseAudioProcessor() {

    private var currentLinearGain = 1.0f
    private var targetLinearGain = 1.0f
    private var rampSamplesRemaining = 0
    private var sampleRateHz = 44100

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)
            throw UnhandledAudioFormatException(inputAudioFormat)
        sampleRateHz = inputAudioFormat.sampleRate
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val state = controller.state.value
        val desiredLinear =
            if (state.enabled) 10f.pow(state.currentTrackGainDb / 20f) else 1f

        if (desiredLinear != targetLinearGain) {
            targetLinearGain = desiredLinear
            rampSamplesRemaining =
                (sampleRateHz * RAMP_MS / 1000).coerceAtLeast(1)
        }

        val step = if (rampSamplesRemaining > 0)
            (targetLinearGain - currentLinearGain) / rampSamplesRemaining else 0f

        val out = replaceOutputBuffer(inputBuffer.remaining())
        while (inputBuffer.remaining() >= 2) {
            val s = inputBuffer.short.toInt()
            val gained = (s * currentLinearGain).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out.putShort(gained.toShort())
            if (rampSamplesRemaining > 0) {
                currentLinearGain += step
                rampSamplesRemaining--
                if (rampSamplesRemaining == 0) currentLinearGain = targetLinearGain
            }
        }
        out.flip()
    }

    private companion object { const val RAMP_MS = 15 }
}
```

- [ ] **Step 4: Pass**

Run: `./gradlew :core:media:testDebug --tests "*LoudnessGainProcessorTest*"`
Expected: all 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/equalizer/dsp/LoudnessGainProcessor.kt \
        core/media/src/test/kotlin/com/stash/core/media/equalizer/dsp/LoudnessGainProcessorTest.kt
git commit -m "feat(loudness): LoudnessGainProcessor — per-buffer gain with 15ms ramp"
```

---

### Task 7: SoftClipLimiterProcessor — lookahead sample-peak limiter

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/equalizer/dsp/SoftClipLimiterProcessor.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/equalizer/dsp/SoftClipLimiterProcessorTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
class SoftClipLimiterProcessorTest {
    @Test fun nonPcm16Input_throws() { ... }
    @Test fun subThresholdSignal_passesUnchanged() {
        // ±0.5 (50% scale) sine wave goes in and out within ±1 LSB.
    }
    @Test fun aboveThresholdSignal_clampsToThreshold() {
        // Full-scale sine wave; max output magnitude ≤ 0.891 * 32767 + a few samples of attack
    }
    @Test fun releaseFollowsImpulseWithinExpectedTime() {
        // Single impulse at full scale, then silence; gain returns to 1.0 within 50 ms ± tolerance.
    }
    @Test fun lookaheadDelaysOutputByExpectedSamples() {
        // First N samples of output should be the "pre-fill" period before any real input emerges.
    }
}
```

- [ ] **Step 2: Fail**

Run: `./gradlew :core:media:testDebug --tests "*SoftClipLimiterProcessorTest*"`
Expected: FAIL.

- [ ] **Step 3: Implement**

```kotlin
package com.stash.core.media.equalizer.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp

/**
 * Final-stage lookahead peak limiter that catches summed peaks from upstream
 * gain stages (EQ + BassShelf + LoudnessGain). Threshold is −1 dBFS, attack
 * 1 ms, release 50 ms, lookahead 2 ms (sample peak — true-peak oversampling
 * is overkill for a phone DAC chain; see the design spec).
 */
@OptIn(UnstableApi::class)
class SoftClipLimiterProcessor : BaseAudioProcessor() {

    private lateinit var ringBuffer: ShortArray
    private var ringWrite = 0
    private var ringRead = 0
    private var currentGain = 1.0f
    private var attackCoeff = 0f
    private var releaseCoeff = 0f
    private var prefillSamples = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)
            throw UnhandledAudioFormatException(inputAudioFormat)
        val frames = (inputAudioFormat.sampleRate * LOOKAHEAD_MS / 1000f).toInt().coerceAtLeast(1)
        ringBuffer = ShortArray(frames * inputAudioFormat.channelCount)
        attackCoeff = expCoeff(ATTACK_MS, inputAudioFormat.sampleRate)
        releaseCoeff = expCoeff(RELEASE_MS, inputAudioFormat.sampleRate)
        ringWrite = 0; ringRead = 0; currentGain = 1f
        prefillSamples = ringBuffer.size
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val out = replaceOutputBuffer(inputBuffer.remaining())
        while (inputBuffer.remaining() >= 2) {
            val sample = inputBuffer.short
            ringBuffer[ringWrite] = sample
            ringWrite = (ringWrite + 1) % ringBuffer.size

            val peakAbs = lookaheadPeakAbs() / 32768f
            val targetGain = if (peakAbs > THRESHOLD) THRESHOLD / peakAbs else 1f
            val coeff = if (targetGain < currentGain) attackCoeff else releaseCoeff
            currentGain += (targetGain - currentGain) * coeff

            if (prefillSamples > 0) {
                prefillSamples--
                out.putShort(0)   // silence during pre-fill
            } else {
                val delayed = ringBuffer[ringRead]
                ringRead = (ringRead + 1) % ringBuffer.size
                val limited = (delayed * currentGain).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out.putShort(limited.toShort())
            }
        }
        out.flip()
    }

    private fun lookaheadPeakAbs(): Int {
        var max = 0
        for (s in ringBuffer) {
            val a = abs(s.toInt()); if (a > max) max = a
        }
        return max
    }

    private fun expCoeff(ms: Float, sampleRate: Int): Float =
        1f - exp(-1f / (sampleRate * ms / 1000f))

    private companion object {
        const val THRESHOLD = 0.891f   // -1 dBFS
        const val ATTACK_MS = 1f
        const val RELEASE_MS = 50f
        const val LOOKAHEAD_MS = 2f
    }
}
```

- [ ] **Step 4: Pass**

Run: `./gradlew :core:media:testDebug --tests "*SoftClipLimiterProcessorTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/equalizer/dsp/SoftClipLimiterProcessor.kt \
        core/media/src/test/kotlin/com/stash/core/media/equalizer/dsp/SoftClipLimiterProcessorTest.kt
git commit -m "feat(loudness): SoftClipLimiterProcessor — lookahead sample-peak limiter at -1 dBFS"
```

---

### Task 8: FFmpegBridge — thin adapter for testability

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/audio/FFmpegBridge.kt`
- (No test in this task — the bridge is a one-line wrapper around an external library; testing it requires the FFmpeg native lib which is integration-only. We unit-test `LoudnessMeasurer` against a fake bridge in Task 9.)

- [ ] **Step 1: Implement**

```kotlin
package com.stash.core.data.audio

import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin adapter around [com.yausername.ffmpeg.FFmpeg] that exposes a single
 * suspend entry point returning captured stderr as a String. Sole purpose:
 * lets [LoudnessMeasurer] be unit-tested against canned stderr fixtures
 * without spawning a real ffmpeg process.
 *
 * Always invoked off the main thread.
 */
interface FFmpegBridge {
    suspend fun runWithStderrCapture(args: List<String>): String
}

@Singleton
class FFmpegBridgeImpl @Inject constructor() : FFmpegBridge {
    override suspend fun runWithStderrCapture(args: List<String>): String =
        withContext(Dispatchers.IO) {
            val request = YoutubeDLRequest(emptyList()).apply {
                args.forEach { addOption(it) }
            }
            val response = FFmpeg.getInstance().execute(request) { _, _, _ -> /* progress */ }
            // Mobile-FFmpeg captures stderr in `out` for the ebur128 case
            // because we pipe `-f null -`; verify on first integration test.
            response.out
        }
}
```

> Note for the implementer: the exact API of `com.yausername.ffmpeg.FFmpeg.execute(...)` should be verified against the existing call site in `YtDlpManager.kt:67-68`. If the return type or progress-callback shape differs from what's sketched here, adapt — the *interface* (`suspend fun runWithStderrCapture`) is the contract that matters.

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :core:data:compileDebugKotlin`
Expected: PASS.

- [ ] **Step 3: Register in Hilt module**

Find the existing Hilt module in `core/data` that binds DAOs/services (search for `@Module` files in `core/data/src/main/kotlin`). Add a `@Binds` line:

```kotlin
@Binds
abstract fun bindFFmpegBridge(impl: FFmpegBridgeImpl): FFmpegBridge
```

- [ ] **Step 4: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/audio/FFmpegBridge.kt \
        core/data/src/main/kotlin/com/stash/core/data/<your-module>.kt
git commit -m "feat(audio): FFmpegBridge interface + impl for ffmpeg invocation"
```

---

### Task 9: LoudnessMeasurer — ffmpeg ebur128 invocation + stderr parser

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/audio/LoudnessMeasurer.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/audio/LoudnessMeasurerTest.kt`
- Test fixtures: `core/data/src/test/resources/ffmpeg_output/*.txt`

- [ ] **Step 1: Create fixtures + failing test**

Place three fixture files in `core/data/src/test/resources/ffmpeg_output/`:

`normal.txt` (verbatim from a real ffmpeg ebur128 run — generate by running `ffmpeg -i sample.mp3 -af ebur128=peak=true -f null -` on a desktop and saving the stderr):

```
... lots of frame lines ...
[Parsed_ebur128_0 @ 0x55a7c8df9b40] Summary:

  Integrated loudness:
    I:         -14.2 LUFS
    Threshold: -24.3 LUFS

  Loudness range:
    LRA:         5.6 LU
    Threshold: -34.3 LUFS
    LRA low:   -16.8 LUFS
    LRA high:  -11.2 LUFS

  True peak:
    Peak:       -0.3 dBFS
```

`short_clip.txt` — same but with `I: -inf LUFS`.

`no_summary.txt` — just frame lines, no Summary block.

Test:

```kotlin
class LoudnessMeasurerTest {
    private val fakeBridge = FakeFFmpegBridge()
    private val measurer = LoudnessMeasurer(fakeBridge)

    @Test fun parsesNormalOutput() {
        fakeBridge.nextOutput = resource("ffmpeg_output/normal.txt")
        val r = runBlocking { measurer.measure(tempFile("dummy.mp3")) }
        assertThat(r).isInstanceOf(LoudnessMeasurer.Result.Success::class.java)
        r as LoudnessMeasurer.Result.Success
        assertThat(r.lufs).isWithin(0.01f).of(-14.2f)
        assertThat(r.truePeakDbfs).isWithin(0.01f).of(-0.3f)
    }

    @Test fun shortClip_returnsFailed() {
        fakeBridge.nextOutput = resource("ffmpeg_output/short_clip.txt")
        val r = runBlocking { measurer.measure(tempFile("dummy.mp3")) }
        assertThat(r).isInstanceOf(LoudnessMeasurer.Result.Failed::class.java)
    }

    @Test fun noSummary_returnsFailed() {
        fakeBridge.nextOutput = resource("ffmpeg_output/no_summary.txt")
        val r = runBlocking { measurer.measure(tempFile("dummy.mp3")) }
        assertThat(r).isInstanceOf(LoudnessMeasurer.Result.Failed::class.java)
    }

    @Test fun concurrentCalls_serializeBehindMutex() {
        // Launch two parallel `measure()` calls; verify the fake bridge sees
        // them serially (callCount goes 0 → 1 → 0 → 1 between them).
    }
}
```

- [ ] **Step 2: Fail**

Run: `./gradlew :core:data:testDebug --tests "*LoudnessMeasurerTest*"`
Expected: FAIL with "Unresolved reference: LoudnessMeasurer".

- [ ] **Step 3: Implement**

```kotlin
package com.stash.core.data.audio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LoudnessMeasurer @Inject constructor(
    private val bridge: FFmpegBridge,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    suspend fun measure(file: File): Result = mutex.withLock {
        withContext(dispatcher) {
            val args = listOf(
                "-nostats", "-hide_banner",
                "-i", file.absolutePath,
                "-af", "ebur128=peak=true",
                "-f", "null", "-",
            )
            val stderr = runCatching { bridge.runWithStderrCapture(args) }
                .getOrElse { return@withContext Result.Failed("ffmpeg invocation failed: ${it.message}") }
            parseSummary(stderr)
        }
    }

    /** Visible for tests. */
    internal fun parseSummary(stderr: String): Result {
        if (!stderr.contains("Summary:")) return Result.Failed("no summary block")
        val lufs = LUFS_REGEX.find(stderr)?.groupValues?.get(1)?.toFloatOrNull()
            ?: return Result.Failed("could not parse integrated loudness")
        if (lufs.isInfinite() || lufs.isNaN())
            return Result.Failed("integrated loudness is inf/NaN (track too short?)")
        val peak = PEAK_REGEX.find(stderr)?.groupValues?.get(1)?.toFloatOrNull()
            ?: return Result.Failed("could not parse true peak")
        return Result.Success(lufs = lufs, truePeakDbfs = peak)
    }

    sealed class Result {
        data class Success(val lufs: Float, val truePeakDbfs: Float) : Result()
        data class Failed(val reason: String) : Result()
    }

    private companion object {
        // matches "    I:         -14.2 LUFS" (also "-inf" / "+inf")
        val LUFS_REGEX = Regex("""\bI:\s+(-?[\d.]+|-?inf)\s+LUFS""")
        val PEAK_REGEX = Regex("""\bPeak:\s+(-?[\d.]+)\s+dBFS""")
    }
}
```

- [ ] **Step 4: Pass**

Run: `./gradlew :core:data:testDebug --tests "*LoudnessMeasurerTest*"`
Expected: all 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/audio/LoudnessMeasurer.kt \
        core/data/src/test/kotlin/com/stash/core/data/audio/LoudnessMeasurerTest.kt \
        core/data/src/test/resources/ffmpeg_output/
git commit -m "feat(audio): LoudnessMeasurer wraps ffmpeg ebur128, parses LUFS + true peak"
```

---

### Task 10: LoudnessProgressStore — Preferences DataStore for backfill progress

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/audio/LoudnessProgressStore.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/audio/LoudnessProgressStoreTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
class LoudnessProgressStoreTest {
    @Test fun flow_emitsZeroByDefault() { ... }
    @Test fun recordBatchComplete_updatesRemainingAndTimestamp() { ... }
    @Test fun setTotal_updatesTotal() { ... }
}
```

Use `TestPreferenceDataStore` or stub.

- [ ] **Step 2: Fail**

- [ ] **Step 3: Implement**

```kotlin
@Singleton
class LoudnessProgressStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    data class Snapshot(val remaining: Int = 0, val total: Int = 0, val lastCompletedAt: Long = 0L)

    val flow: Flow<Snapshot> = dataStore.data.map { p ->
        Snapshot(
            remaining = p[KEY_REMAINING] ?: 0,
            total = p[KEY_TOTAL] ?: 0,
            lastCompletedAt = p[KEY_LAST] ?: 0L,
        )
    }

    suspend fun setTotal(total: Int) = dataStore.edit { it[KEY_TOTAL] = total }

    suspend fun setRemaining(remaining: Int) = dataStore.edit { it[KEY_REMAINING] = remaining }

    suspend fun recordBatchComplete(completed: Int, at: Long) = dataStore.edit { p ->
        val rem = (p[KEY_REMAINING] ?: 0) - completed
        p[KEY_REMAINING] = rem.coerceAtLeast(0)
        p[KEY_LAST] = at
    }

    private companion object {
        val KEY_REMAINING = intPreferencesKey("loudness_backfill_remaining")
        val KEY_TOTAL = intPreferencesKey("loudness_backfill_total")
        val KEY_LAST = longPreferencesKey("loudness_backfill_last_completed_at")
    }
}
```

- [ ] **Step 4: Pass**

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/audio/LoudnessProgressStore.kt \
        core/data/src/test/kotlin/com/stash/core/data/audio/LoudnessProgressStoreTest.kt
git commit -m "feat(audio): LoudnessProgressStore — backfill remaining/total/lastCompletedAt"
```

---

### Task 11: LoudnessBackfillWorker

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/LoudnessBackfillWorker.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/sync/workers/LoudnessBackfillWorkerTest.kt`

- [ ] **Step 1: Failing test**

Test scenarios:
- Empty queue (no rows with `loudness_measured_at IS NULL`) → `Result.success()` with `KEY_DONE = true`.
- Batch of 3 rows, all succeed → 3 DAO `updateLoudness` calls, progress store updated, `Result.success()`.
- Batch with one failing measurement → 2 updateLoudness + 1 markLoudnessFailed; success.
- Mid-batch cancellation (`isStopped == true` after first track) → only one update, idempotent.

Use a fake `WorkerParameters` (search for "AssistedInject" pattern in existing worker tests; pattern is well-established here).

- [ ] **Step 2: Fail**

- [ ] **Step 3: Implement**

```kotlin
@HiltWorker
class LoudnessBackfillWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
    private val measurer: LoudnessMeasurer,
    private val progressStore: LoudnessProgressStore,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val deadline = System.currentTimeMillis() + MAX_RUN_MS
        val batch = trackDao.tracksNeedingLoudness(limit = BATCH_SIZE)
        if (batch.isEmpty()) {
            progressStore.setRemaining(0)
            return Result.success(workDataOf(KEY_DONE to true))
        }
        var completed = 0
        for (track in batch) {
            if (isStopped || System.currentTimeMillis() > deadline) break
            val path = track.filePath ?: continue
            val file = File(path).takeIf { it.exists() } ?: continue
            when (val r = measurer.measure(file)) {
                is LoudnessMeasurer.Result.Success ->
                    trackDao.updateLoudness(track.id, r.lufs, r.truePeakDbfs, System.currentTimeMillis())
                is LoudnessMeasurer.Result.Failed ->
                    trackDao.markLoudnessFailed(track.id, System.currentTimeMillis())
            }
            completed++
        }
        progressStore.recordBatchComplete(completed, System.currentTimeMillis())
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "loudness-backfill"
        const val KEY_DONE = "done"
        private const val BATCH_SIZE = 20
        private const val MAX_RUN_MS = 10L * 60 * 1000

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<LoudnessBackfillWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresDeviceIdle(true)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }
    }
}
```

- [ ] **Step 4: Pass**

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/LoudnessBackfillWorker.kt \
        core/data/src/test/kotlin/com/stash/core/data/sync/workers/LoudnessBackfillWorkerTest.kt
git commit -m "feat(workers): LoudnessBackfillWorker — periodic, charging+idle, batches of 20"
```

---

### Task 12: TrackFinalizer integration — measure-on-download

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/shared/TrackFinalizer.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/shared/TrackFinalizerLoudnessTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
class TrackFinalizerLoudnessTest {
    @Test fun successfulFinalize_invokesMeasurerAndReturnsLufs() { ... }
    @Test fun measurementFailed_finalizeStillReturnsSuccess() {
        // Non-fatal failure: file is playable, just no loudness data.
    }
}
```

- [ ] **Step 2: Fail**

- [ ] **Step 3: Implement**

Modify `TrackFinalizer.kt`:

1. Inject `LoudnessMeasurer` (constructor param).
2. Extend `FinalizeResult.Success` data class:
   ```kotlin
   data class Success(
       val committed: CommittedTrack,
       val meta: AudioMetadata?,
       val loudness: LoudnessMeasurer.Result.Success? = null,
   ) : FinalizeResult
   ```
3. After the existing `audioExtractor.extract(...)` call, add:
   ```kotlin
   val loudness = when (val r = loudnessMeasurer.measure(File(committed.filePath))) {
       is LoudnessMeasurer.Result.Success -> r
       is LoudnessMeasurer.Result.Failed -> {
           Log.w(TAG, "loudness measurement failed for ${committed.filePath}: ${r.reason}")
           null
       }
   }
   FinalizeResult.Success(committed, meta, loudness)
   ```

- [ ] **Step 4: Pass**

Run: `./gradlew :data:download:testDebug --tests "*TrackFinalizerLoudnessTest*"`
Expected: PASS.

Also run the full `:data:download:test` to confirm existing `TrackFinalizer` callers' tests still pass.

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/shared/TrackFinalizer.kt \
        data/download/src/test/kotlin/com/stash/data/download/shared/TrackFinalizerLoudnessTest.kt
git commit -m "feat(download): TrackFinalizer measures loudness after probe, returns in Success"
```

---

### Task 13: TrackFinalizer callers — write loudness to DB

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/DownloadManager.kt` (or wherever it consumes `FinalizeResult`)
- Modify: `data/download/src/main/kotlin/com/stash/data/download/search/SearchDownloadCoordinator.kt`
- Test: extend whichever test fixture covers the existing finalize-then-write path; add an assertion that loudness columns are populated when present.

- [ ] **Step 1: Map call sites**

Grep both callers for where they consume `FinalizeResult.Success.meta` to write quality columns. The new `loudness` field goes next to that write. (For sync: a `TrackDao.updateXxx` call. For search: same.)

- [ ] **Step 2: Write the failing test**

In whichever test class covers the existing `quality_kbps`/`bits_per_sample` write, add:

```kotlin
@Test fun afterDownload_writesLoudnessLufsAndPeak() {
    // Stub the finalizer to return a Success with loudness = (lufs=-14.2, peak=-0.3)
    // Run the download flow.
    // Assert trackDao.updateLoudness was called with those values.
}
```

- [ ] **Step 3: Fail, then implement**

In each caller's success path, add immediately after the existing DAO write:

```kotlin
result.loudness?.let { l ->
    trackDao.updateLoudness(trackId, l.lufs, l.truePeakDbfs, System.currentTimeMillis())
}
```

- [ ] **Step 4: Pass**

Run: `./gradlew :data:download:testDebug`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/DownloadManager.kt \
        data/download/src/main/kotlin/com/stash/data/download/search/SearchDownloadCoordinator.kt \
        <whichever test files you touched>
git commit -m "feat(download): persist loudness from finalize in sync + search download paths"
```

---

### Task 14: StashRenderersFactory wiring

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/equalizer/StashRenderersFactory.kt`
- Test: extend `core/media/src/test/kotlin/com/stash/core/media/equalizer/StashRenderersFactoryTest.kt` (create if absent) to assert the chain ordering.

- [ ] **Step 1: Test**

```kotlin
@Test fun chainOrder_isPreampEqBassLoudnessLimiter() {
    val factory = StashRenderersFactory(context, eqController, loudnessController)
    val processors = factory.buildAudioProcessorsForTest()
    assertThat(processors.map { it::class.simpleName }).containsExactly(
        "PreampProcessor", "EqProcessor", "BassShelfProcessor",
        "LoudnessGainProcessor", "SoftClipLimiterProcessor",
    ).inOrder()
}
```

(You'll need to expose a test-only accessor for the processor array, or break out the array build into a `protected` method you can spy on. Smallest invasive: add `internal fun audioProcessorsForTest(): Array<AudioProcessor>` returning the same list.)

- [ ] **Step 2: Fail**

- [ ] **Step 3: Implement**

Modify `StashRenderersFactory.kt`:

```kotlin
@OptIn(UnstableApi::class)
class StashRenderersFactory(
    context: Context,
    private val eqController: EqController,
    private val loudnessController: LoudnessController,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val processors: Array<AudioProcessor> = if (ENABLE_LOUDNESS) {
            arrayOf(
                PreampProcessor(eqController),
                EqProcessor(eqController),
                BassShelfProcessor(eqController),
                LoudnessGainProcessor(loudnessController),
                SoftClipLimiterProcessor(),
            )
        } else {
            // Fast hotfix path: drop loudness stages entirely.
            arrayOf(
                PreampProcessor(eqController),
                EqProcessor(eqController),
                BassShelfProcessor(eqController),
            )
        }
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setAudioProcessors(processors)
            .build()
    }

    private companion object { const val ENABLE_LOUDNESS = true }
}
```

Wherever `StashRenderersFactory` is constructed (search for `StashRenderersFactory(`), update the call site to pass the new `LoudnessController` (it's a Hilt singleton — should resolve automatically wherever the factory itself is provided).

- [ ] **Step 4: Pass**

Run: `./gradlew :core:media:testDebug`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/equalizer/StashRenderersFactory.kt \
        <call-site files>
git commit -m "feat(media): wire LoudnessGain + SoftClipLimiter into StashRenderersFactory chain"
```

---

### Task 15: Player.Listener — update controller on track transition

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt` (or `PlayerRepositoryImpl.kt` — whichever holds the canonical `Player.Listener`)
- Test: extend the existing service/repository test to verify the listener pulls loudness on transition.

- [ ] **Step 1: Locate the listener**

Search for `addListener` or `Player.Listener` in `core/media`. The relevant call site is where the app already responds to `onMediaItemTransition`. The new listener block needs the active track's `loudnessLufs` + `truePeakDbfs` from the DB.

- [ ] **Step 2: Test**

```kotlin
@Test fun onTrackTransition_callsControllerWithComputedGain() {
    val track = trackEntity(lufs = -20f, peak = -3f)
    trackDao.stubGetByMediaId(track.mediaId, track)
    service.simulateTransition(track.mediaId)
    verify(loudnessController).setCurrentTrackGain(eq(6f, tol = 0.01f))
}
```

- [ ] **Step 3: Implement**

Inject `LoudnessController` into the service/repo. In the `Player.Listener`:

```kotlin
override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
    super.onMediaItemTransition(mediaItem, reason)
    val mediaId = mediaItem?.mediaId ?: return
    serviceScope.launch {
        val track = trackDao.getById(mediaId.toLongOrNull() ?: return@launch) ?: return@launch
        val gainDb = computeGain(track.loudnessLufs, track.truePeakDbfs)
        loudnessController.setCurrentTrackGain(gainDb)
    }
}
```

(Use whatever `trackDao.getById` signature exists — search for an existing call.)

- [ ] **Step 4: Pass**

Run: `./gradlew :core:media:testDebug`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add <files touched>
git commit -m "feat(media): Player.Listener pushes per-track gain to LoudnessController"
```

---

### Task 16: StashApplication — enqueue periodic backfill worker

**Files:**
- Modify: `app/src/main/kotlin/com/stash/app/StashApplication.kt`

- [ ] **Step 1: Add the scheduling call**

Inside `onCreate()`, alongside the existing `StashMixRefreshWorker.schedulePeriodic(this)` line:

```kotlin
LoudnessBackfillWorker.schedulePeriodic(this)
```

Import `com.stash.core.data.sync.workers.LoudnessBackfillWorker`.

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/stash/app/StashApplication.kt
git commit -m "feat(app): schedule LoudnessBackfillWorker at app start"
```

---

### Task 17: EqualizerViewModel — surface loudness state to UI

**Files:**
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/equalizer/EqualizerViewModel.kt`

- [ ] **Step 1: Implement**

Inject `LoudnessController` + `LoudnessProgressStore`. Add a `loudnessUiState: StateFlow<LoudnessUiState>` that combines both:

```kotlin
data class LoudnessUiState(
    val enabled: Boolean = true,
    val backfillRemaining: Int = 0,
    val backfillTotal: Int = 0,
)

val loudnessUiState: StateFlow<LoudnessUiState> = combine(
    loudnessController.state,
    loudnessProgressStore.flow,
) { settings, progress ->
    LoudnessUiState(
        enabled = settings.enabled,
        backfillRemaining = progress.remaining,
        backfillTotal = progress.total,
    )
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoudnessUiState())

fun onLoudnessToggle(enabled: Boolean) = loudnessController.setEnabled(enabled)
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :feature:settings:compileDebugKotlin`

- [ ] **Step 3: Commit**

```bash
git add feature/settings/src/main/kotlin/com/stash/feature/settings/equalizer/EqualizerViewModel.kt
git commit -m "feat(settings): EqualizerViewModel exposes loudnessUiState + toggle"
```

---

### Task 18: EqualizerScreen — LoudnessCard composable

**Files:**
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/equalizer/EqualizerScreen.kt`

- [ ] **Step 1: Implement**

Read the existing card structure (lines 139–170 — Bass Boost card). Insert a new `GlassCard` between the Bass Boost card and the Pre-amp card. Pattern:

```kotlin
// Inside the main Column, between Bass Boost card and Pre-amp card:

val loudnessState by viewModel.loudnessUiState.collectAsStateWithLifecycle()
Spacer(Modifier.height(12.dp))
GlassCard {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Loudness Normalization")
            Spacer(Modifier.weight(1f))
            Switch(
                checked = loudnessState.enabled,
                onCheckedChange = viewModel::onLoudnessToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Plays every track at a consistent volume.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (loudnessState.backfillRemaining > 0) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Spacer(Modifier.height(10.dp))
            LoudnessBackfillBlock(
                remaining = loudnessState.backfillRemaining,
                total = loudnessState.backfillTotal,
            )
        }
    }
}
```

Add a `private fun LoudnessBackfillBlock(...)` that renders the "X of Y tracks" line + a `LinearProgressIndicator` + a subtitle.

**Critical**: do **not** wrap the new card's `Column` in `.alpha(if (state.enabled) 0.5f else 1f)` — loudness operates independently from the EQ master toggle.

- [ ] **Step 2: Manual visual check**

Run: `./gradlew :app:installDebug`
Open Settings → Equalizer. Verify:
- New Loudness card appears between Bass Boost and Pre-amp.
- Toggle works (gain stops/starts on screen).
- Card layout matches the existing visual language (GlassCard, uppercase SectionLabel, switch on the right).

- [ ] **Step 3: Commit**

```bash
git add feature/settings/src/main/kotlin/com/stash/feature/settings/equalizer/EqualizerScreen.kt
git commit -m "feat(settings): LoudnessCard composable in EQ screen"
```

---

### Task 19: First-run Snackbar notice

**Files:**
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/equalizer/EqualizerScreen.kt` (Snackbar host + state)
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/equalizer/EqualizerViewModel.kt` (read/clear the first-run flag)
- Create: `feature/settings/src/main/kotlin/com/stash/feature/settings/equalizer/LoudnessFirstRunStore.kt` — tiny DataStore wrapper

- [ ] **Step 1: Implement the store**

```kotlin
@Singleton
class LoudnessFirstRunStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val noticeShownFlow: Flow<Boolean> = dataStore.data.map { it[KEY] ?: false }
    suspend fun markShown() = dataStore.edit { it[KEY] = true }
    private companion object { val KEY = booleanPreferencesKey("loudness_first_run_notice_shown") }
}
```

- [ ] **Step 2: Wire to ViewModel**

In `EqualizerViewModel`:

```kotlin
val showFirstRunNotice: StateFlow<Boolean> = loudnessFirstRunStore.noticeShownFlow
    .map { !it }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

fun onFirstRunNoticeDismissed() {
    viewModelScope.launch { loudnessFirstRunStore.markShown() }
}
```

- [ ] **Step 3: Show Snackbar**

In `EqualizerScreen.kt`, add a `SnackbarHostState` and a `LaunchedEffect(Unit)` that, when `showFirstRunNotice == true`, shows a Snackbar:

```kotlin
val snackbarHostState = remember { SnackbarHostState() }
val showNotice by viewModel.showFirstRunNotice.collectAsStateWithLifecycle()
LaunchedEffect(showNotice) {
    if (showNotice) {
        val result = snackbarHostState.showSnackbar(
            message = "Loudness normalization is on. Tracks will sound more consistent as your library is measured in the background.",
            actionLabel = "Dismiss",
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed || result == SnackbarResult.Dismissed) {
            viewModel.onFirstRunNoticeDismissed()
        }
    }
}
```

Also dismiss on first toggle interaction:

```kotlin
// in the Switch onCheckedChange:
onCheckedChange = { enabled ->
    viewModel.onLoudnessToggle(enabled)
    viewModel.onFirstRunNoticeDismissed()
},
```

You'll need a `Scaffold` around the screen content to host the `SnackbarHost`. If the screen currently uses `Column` directly (lines 87-211), wrap it in a `Scaffold`.

- [ ] **Step 4: Verify on-device**

Run: `./gradlew :app:installDebug`
Open EQ screen for the first time → Snackbar should appear. Dismiss → reopen → it should NOT reappear.

- [ ] **Step 5: Commit**

```bash
git add feature/settings/src/main/kotlin/com/stash/feature/settings/equalizer/LoudnessFirstRunStore.kt \
        feature/settings/src/main/kotlin/com/stash/feature/settings/equalizer/EqualizerViewModel.kt \
        feature/settings/src/main/kotlin/com/stash/feature/settings/equalizer/EqualizerScreen.kt
git commit -m "feat(settings): one-time first-run Snackbar for loudness normalization"
```

---

### Task 20: Version bump + end-to-end manual validation

**Files:**
- Modify: `app/build.gradle.kts` — bump `versionCode 61 → 62`, `versionName "0.9.24" → "0.9.25"`

- [ ] **Step 1: Bump version**

In `app/build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        versionCode = 62
        versionName = "0.9.25"
    }
}
```

- [ ] **Step 2: Full test sweep**

Run the whole test suite to confirm nothing regressed:

```bash
./gradlew test
```

Expected: PASS across all modules.

- [ ] **Step 3: Install + manual validation**

```bash
./gradlew :app:installDebug
```

On device:

1. **Existing library scan**: open Settings → Equalizer. Loudness card visible. Switch is ON by default. Snackbar appears. Dismiss it.
2. **Backfill kicks in**: plug the phone in, lock screen. Within an hour the worker should run (system idle + charging gate). Re-open EQ screen → progress shrinks ("Library measured: X of Y").
3. **Per-track gain**: play two tracks of obviously different mastered loudness — e.g. one acoustic / one loud pop. Verify with logcat (`adb logcat -s LoudnessController`) that `setCurrentTrackGain` fires with different values, and that they sound closer in volume.
4. **Toggle off**: flip the loudness switch off. The two tracks should now sound at their original (different) volumes.
5. **First-run notice not repeating**: kill app, relaunch, open EQ → no Snackbar.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts
git commit -m "chore: bump versionCode 61->62, versionName 0.9.24->0.9.25 for loudness normalization"
```

- [ ] **Step 5: Final commit on the feature branch**

If there are any leftover cleanups (formatting, imports), commit them too. The branch is ready for review/merge.

---

## Validation gates (gates before merging)

1. `./gradlew test` — all modules pass.
2. `./gradlew :app:installDebug` — installs cleanly.
3. Manual on-device sweep (Task 20 Step 3) — all five items verified.
4. ffmpeg ebur128 measures known reference tracks within ±0.3 LUFS — sanity-check 5 hand-measured tracks against ffmpeg desktop output.
5. No regression in existing EQ tests (`:core:media:testDebug` and `:feature:settings:testDebug`).

## Out of scope for this plan (deferred to v2 or never)

See spec § Non-Goals — but to summarize the most-likely-to-be-asked-for:

- **Album gain.** Track gain only in v1. Adding album gain requires a "playback parent" signal flow through the queue manager that doesn't exist yet — separate plan.
- **Quiet/Loud mode selector.** Single −14 LUFS target only. The `LoudnessState.targetLufs` setting hook is in place but no UI exposes it.
- **Writing RG tags back to source files.** Stash DB only.
- **Per-playlist normalization profiles.** Single global toggle.
- **True-peak limiter with 4× oversampling.** Sample-peak only — cheaper, fine for phone DAC.
- **Opportunistic measurement during playback.** Backfill worker only.

---

## Skills referenced

- @superpowers:test-driven-development — followed throughout (red → green → commit).
- @superpowers:verification-before-completion — Task 20 manual sweep is the verification gate before claiming done.
- @superpowers:requesting-code-review — invoke after Task 20 completes, before merging.
