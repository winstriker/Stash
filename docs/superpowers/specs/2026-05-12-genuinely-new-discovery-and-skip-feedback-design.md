# Phase 1: Genuinely-New Discovery + Skip-Feedback Loop Design

> **Status:** Design — pending implementation plan.
> **Scope:** PR 6 of the post-v0.9.19 audit. After 5 PRs of plumbing, the Stash Mix experience still feels like a library re-shuffle. Phase 1 addresses the actual algorithmic root cause: Last.fm candidates that already exist in the user's library get canonical-deduplicated into "rediscovery" hits instead of generating new content; and the system has no negative-feedback signal to learn from skips. This PR (a) makes manual triggers fire on any network, (b) hard-filters library-overlapping candidates at the seed-generator boundary, and (c) bans tracks with ≥3 early skips in 90 days from future discovery.
> **Related:** continues from PR 5 (manual refresh kicks pipeline). Sets foundation for Phases 2-4 (seed diversity, tag-graph adjacency, feedback UI, embeddings).

---

## Goal

Three changes, in order of user-visible impact:

1. **Manual-trigger network constraint relaxes to `CONNECTED`.** When the user explicitly taps "Refresh this mix," the pipeline fires on whatever network is available. Background periodic discovery still respects `DownloadNetworkMode` strictly. Closes the "I tap refresh and nothing happens because I'm not on WiFi" trap that the PR 5 design walked into and that the corruption hotfix's pref-reset made unavoidable for the user.

2. **Last.fm candidates are hard-filtered against the user's library at the seed-generator boundary.** Today, `StashDiscoveryWorker.handle()` does canonical-match dedup AFTER fetching — turning library-overlap candidates into "rediscovery" hits that re-link existing tracks into the playlist. The user observed mixes filled with library content because the seeds for ARTIST_SIMILAR / TRACK_SIMILAR queries hit artists they already have many tracks from. We move the filter EARLIER: candidates whose canonical artist+title key matches the library get dropped before they ever enter `discovery_queue`. Every queued candidate is genuinely new.

3. **Tracks the user has skipped ≥3 times in the first 30 seconds within the last 90 days are banned from discovery candidates.** Negative-feedback signal that propagates from the user's behavior into seed selection. The existing `track_skip_events` table (with `positionMs` column, verified) gives us the data; we just need to use it.

## Non-goals

- **No new UI for thumbs-up/down feedback.** Phase 3 work. Phase 1 uses only existing skip signals.
- **No multi-strategy fan-out per recipe** (Phase 2).
- **No tag-graph adjacency exploration beyond top-10 tags** (Phase 2).
- **No audio-feature embeddings or vector similarity** (Phase 4 — the "world-class" pivot).
- **No change to the existing `MixGenerator` library-scorer.** The library slice of each mix still comes from there. Phase 1 only changes the discovery pipeline's content quality.
- **No retroactive cleanup of existing `discovery_queue` PENDING/DONE rows.** Pre-PR-6 rows that happen to be library-overlapping will continue to flow through. Acceptable — new rows added after PR 6 are clean, and the materialize step will naturally trim to capacity. The user's library can re-emerge in mixes as the affinity-scored library slice, not as discovery survivors.
- **No tunable thresholds in app settings.** The 3-skip / 90-day / 30s-position numbers are hardcoded for now. Can extract to settings later if signal proves to be off.

## Architecture

```
┌────────────────────────────────────────────────────────────────────────────┐
│ User taps "Refresh this mix"                                               │
│   ↓                                                                        │
│ HomeViewModel.refreshMix                                                   │
│   ↓                                                                        │
│ StashMixRefreshWorker.doWork (or manual one-shot from PR 5)                │
│   ↓                                                                        │
│ queueDiscoveryForRecipe(recipe, personas):                                 │
│   1. MixSeedGenerator.generate(strategy, seedArtists, topTags, seedTracks, │
│      personas) → List<DiscoveryCandidate>      (unchanged)                 │
│   2. NEW: load libraryKeys = trackDao.getLibraryCanonicalKeys()            │
│   3. NEW: load skipBannedKeys = trackSkipEventDao                          │
│           .getEarlySkipBannedCanonicalKeys(                                │
│             minSkips = 3,                                                  │
│             sinceMs = now - 90.days,                                       │
│             maxPositionMs = 30_000,                                        │
│           )                                                                │
│   4. NEW: filteredCandidates = candidates.filter { c ->                    │
│        val key = "${canonicalArtist(c.artist)}|${canonicalTitle(c.title)}" │
│        key !in libraryKeys && key !in skipBannedKeys                       │
│      }                                                                     │
│   5. NEW: if filteredCandidates.size < MIN_POOL_AFTER_FILTER:              │
│        // Fall back: append TAG_GRAPH candidates from adjacent tags        │
│        val fallback = seedGenerator.generate(TAG_GRAPH, ...)               │
│            .filter { ... same library + skip filters ... }                 │
│        filteredCandidates += fallback                                      │
│   6. mixGenerator.queueDiscoveryCandidates(recipe, filteredCandidates)     │
│                                                                            │
│ Result: discovery_queue PENDING rows for THIS recipe are guaranteed        │
│ to be (a) not in the user's library, (b) not heavily-skipped, (c) at      │
│ least MIN_POOL_AFTER_FILTER in number when seed signal is sparse.          │
└────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────────┐
│ Network constraint relaxation (Decision 1)                                 │
│                                                                            │
│ DownloadConstraints.kt:                                                    │
│   constraintsForManualTrigger(mode) becomes mode-INDEPENDENT:              │
│     Network: CONNECTED (any internet)                                      │
│     BatteryNotLow: true                                                    │
│     Charging: false                                                        │
│                                                                            │
│   constraintsFor(mode) (periodic background) stays unchanged —             │
│   respects DownloadNetworkMode strictly.                                   │
└────────────────────────────────────────────────────────────────────────────┘
```

No schema migration. Room schema v22 stays. All new data flows through existing tables.

## Component 1 — Manual-trigger constraints become network-mode-independent

`core/data/src/main/kotlin/com/stash/core/data/sync/workers/DownloadConstraints.kt`.

Replace the body of `constraintsForManualTrigger(mode)`:

```kotlin
/**
 * Constraints for **manual user-initiated triggers** — the user explicitly
 * tapped "Refresh this mix" or the app booted with orphan downloads to
 * drain. They're asking for content right now; the system honors that on
 * whatever network is available.
 *
 * Differs from [constraintsFor]:
 *  - Charging requirement DROPPED (user is actively using the app).
 *  - Network requirement RELAXED from UNMETERED to CONNECTED — manual
 *    triggers don't gate on cellular preference. The user's
 *    DownloadNetworkMode pref still governs the periodic background
 *    cycle (via [constraintsFor]); it doesn't govern foreground intent.
 *
 * `setRequiresBatteryNotLow(true)` stays on — a 5% battery + manual
 * refresh is still a bad combination.
 *
 * v0.9.20 history: shipped in PR 5 respecting DownloadNetworkMode for
 * cellular gating. After a corruption-induced pref reset left the user
 * stuck (default mode = WIFI_AND_CHARGING → manual triggers required
 * unmetered → no firing on cellular), we relaxed to CONNECTED to match
 * how every major music app handles user-initiated downloads.
 */
@Suppress("UNUSED_PARAMETER")
fun constraintsForManualTrigger(mode: DownloadNetworkMode): Constraints = Constraints.Builder()
    .setRequiresBatteryNotLow(true)
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()
```

The `mode` parameter is preserved in the signature (suppressed unused warning) so callers don't need to change, AND so a future iteration could re-thread it (e.g., if we re-add user-pref respect for a "strict WiFi-only even for manual" toggle later).

**Callers unchanged:**
- `StashDiscoveryWorker.enqueueOneTime(context, mode)` still passes mode through; constraint just stops looking at it.
- `StashDiscoveryWorker.doWork`'s chain to `DiscoveryDownloadWorker` still passes mode through.
- `HomeViewModel.refreshMix` still reads pref + passes through.
- `StashApplication.onCreate` startup drain still passes through.

Each caller's `downloadNetworkPreference.current()` call becomes dead-weight reading, but harmless. Cleaning it up isn't necessary for this PR; can be tidied in a follow-up.

**Tests:** the existing `DownloadConstraintsTest` (PR 5 Task 1) had three tests asserting per-mode behavior. Two of them now assert the SAME outcome (`UNMETERED` → no longer required). Rewrite to three tests asserting mode-independence:

```kotlin
@Test fun `constraintsForManualTrigger ignores mode — always CONNECTED + battery_not_low + no charging`() {
    for (mode in DownloadNetworkMode.values()) {
        val constraints = constraintsForManualTrigger(mode)
        assertEquals("mode=$mode", NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertFalse("mode=$mode", constraints.requiresCharging())
        assertTrue("mode=$mode", constraints.requiresBatteryNotLow())
    }
}
```

Or three explicit per-mode tests if `assertEquals` in a loop is awkward. Either works.

## Component 2 — Pre-filter Last.fm candidates against library

### 2a. `TrackDao.getLibraryCanonicalKeys`

`core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt`. Add:

```kotlin
/**
 * Returns the canonical-key set ("$canonicalArtist|$canonicalTitle")
 * for every downloaded track. Used by [MixSeedGenerator]'s pre-filter
 * to drop Last.fm candidates that would dedup to library content
 * downstream — keeping `discovery_queue` PENDING rows representative
 * of genuinely-new music instead of "rediscovery" hits.
 *
 * `is_downloaded = 1` filter restricts to playable content. Stub
 * TrackEntities (created by StashDiscoveryWorker before their files
 * land) are excluded so we don't double-filter against in-flight
 * discoveries.
 */
@Query(
    """
    SELECT DISTINCT (canonical_artist || '|' || canonical_title) AS k
    FROM tracks
    WHERE is_downloaded = 1
    """
)
suspend fun getLibraryCanonicalKeys(): List<String>
```

`String` not `Set<String>` because Room doesn't return Sets natively; the caller converts to a `Set` once for O(1) membership lookups.

### 2b. `MixSeedGenerator.generate` filters before returning (OR call-site filters)

**Decision: filter at the call site in `StashMixRefreshWorker.queueDiscoveryForRecipe`.**

Rationale: `MixSeedGenerator` is a pure Last.fm-API wrapper. Injecting `TrackDao` + `TrackSkipEventDao` + `TrackMatcher` into it pollutes its role. Cleaner: keep it pure, filter at the boundary in the worker.

In `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt`, modify `queueDiscoveryForRecipe(recipe, personas)`:

```kotlin
private suspend fun queueDiscoveryForRecipe(
    recipe: StashMixRecipeEntity,
    personas: LastFmPersonas,
) {
    val strategy = MixSeedStrategy.fromStored(recipe.seedStrategy)
    if (strategy == MixSeedStrategy.NONE) return

    // ... existing seed-artist + seed-track + topTags assembly unchanged ...

    val candidates = seedGenerator.generate(
        strategy = strategy,
        seedArtists = seedArtists,
        topTags = topTags,
        seedTracks = seedTracks,
        personas = personas,
    )
    if (candidates.isEmpty()) return

    // NEW: pre-filter against library + skip-ban
    val libraryKeys = trackDao.getLibraryCanonicalKeys().toHashSet()
    val skipBannedKeys = trackSkipEventDao
        .getEarlySkipBannedCanonicalKeys(
            minSkips = DISCOVERY_SKIP_BAN_MIN_COUNT,
            sinceMs = System.currentTimeMillis() - DISCOVERY_SKIP_BAN_WINDOW_MS,
            maxPositionMs = DISCOVERY_SKIP_BAN_MAX_POSITION_MS,
        )
        .toHashSet()

    val filtered = candidates.filter { candidate ->
        val key = canonicalKey(candidate.artist, candidate.title)
        key !in libraryKeys && key !in skipBannedKeys
    }

    val final = if (filtered.size >= MIN_DISCOVERY_POOL_AFTER_FILTER) {
        filtered
    } else {
        // Fallback: top off with TAG_GRAPH candidates (different strategy
        // typically yields different artists, so library overlap is lower).
        // Same filters applied so we still don't surface library/banned.
        //
        // withTimeout protects WorkManager's 10-minute budget: the first
        // seedGenerator.generate() can take up to 30s on a slow connection,
        // and this fallback runs sequentially. 30s cap matches the persona-
        // fetch timeout already in doWork. On timeout we fall through to
        // whatever `filtered` had (could be empty — handled below).
        val tagFallback = runCatching {
            withTimeout(SEED_FALLBACK_TIMEOUT_MS) {
                seedGenerator.generate(
                    strategy = MixSeedStrategy.TAG_GRAPH,
                    seedArtists = emptyList(),
                    topTags = topTags,
                    seedTracks = emptyList(),
                    personas = personas,
                )
            }
        }.getOrElse {
            Log.w(TAG, "'${recipe.name}': TAG_GRAPH fallback timed out / failed", it)
            emptyList()
        }.filter { candidate ->
            val key = canonicalKey(candidate.artist, candidate.title)
            key !in libraryKeys && key !in skipBannedKeys
        }
        Log.i(
            TAG,
            "'${recipe.name}': filtered pool (${filtered.size}) below floor; " +
                "appending ${tagFallback.size} TAG_GRAPH fallback candidates",
        )
        filtered + tagFallback
    }

    if (final.isEmpty()) {
        Log.w(TAG, "'${recipe.name}': all candidates filtered out (library + skips); skipping queue")
        return
    }

    Log.i(
        TAG,
        "'${recipe.name}': ${final.size} candidates via $strategy " +
            "(${candidates.size - filtered.size} filtered as library/banned)",
    )
    mixGenerator.queueDiscoveryCandidates(recipe, final)
}

private fun canonicalKey(artist: String, title: String): String {
    return "${trackMatcher.canonicalArtist(artist)}|${trackMatcher.canonicalTitle(title)}"
}
```

New constants on the worker:

```kotlin
companion object {
    // existing ...
    private const val MIN_DISCOVERY_POOL_AFTER_FILTER = 20
    private const val DISCOVERY_SKIP_BAN_MIN_COUNT = 3
    private val DISCOVERY_SKIP_BAN_WINDOW_MS = TimeUnit.DAYS.toMillis(90)
    private const val DISCOVERY_SKIP_BAN_MAX_POSITION_MS = 30_000L
    private const val SEED_FALLBACK_TIMEOUT_MS = 30_000L
}
```

**New constructor dependencies on `StashMixRefreshWorker`** (verified against the current source — `trackDao` and `mixGenerator` are already injected; the other two are not):

```kotlin
private val trackSkipEventDao: TrackSkipEventDao,
private val trackMatcher: TrackMatcher,
```

Both are Hilt-resolvable: `TrackSkipEventDao` is exposed via the existing Room database module; `TrackMatcher` is `@Singleton class TrackMatcher @Inject constructor()` so Hilt resolves it directly.

## Component 3 — Skip-event ban-list query

`core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackSkipEventDao.kt`.

Add:

```kotlin
/**
 * Returns canonical-key set ("$canonicalArtist|$canonicalTitle") for
 * tracks that have been "early-skipped" at least [minSkips] times since
 * [sinceMs], where "early" means within the first [maxPositionMs]
 * milliseconds of playback. Used by the discovery pre-filter to ban
 * candidates the user has repeatedly rejected.
 *
 * Position-aware: a skip 90% of the way through a song is "finished
 * listening, moving on," not a verdict. Only skips in the first
 * [maxPositionMs] count.
 *
 * Joins to `tracks` to look up the canonical key. Tracks deleted from
 * the library are naturally excluded (INNER JOIN). Acceptable — if a
 * track is gone we don't need to ban it from future re-discovery.
 */
@Query(
    """
    SELECT (t.canonical_artist || '|' || t.canonical_title) AS k
    FROM track_skip_events s
    INNER JOIN tracks t ON t.id = s.track_id
    WHERE s.skipped_at >= :sinceMs
      AND s.position_ms <= :maxPositionMs
    GROUP BY k
    HAVING COUNT(*) >= :minSkips
    """
)
suspend fun getEarlySkipBannedCanonicalKeys(
    minSkips: Int,
    sinceMs: Long,
    maxPositionMs: Long,
): List<String>
```

Index check: `track_skip_events` already has index on `skipped_at` (per `StashDatabase.kt:623`). The join uses `track_id` index. The position filter is in-row, no index needed. Query should be fast for typical histories.

## Component 4 — TrackMatcher injection into StashMixRefreshWorker

`TrackMatcher` is the existing helper used by `StashDiscoveryWorker.handle()` for canonical-title / canonical-artist normalization. We need the same canonical-key construction in `StashMixRefreshWorker.queueDiscoveryForRecipe` to match against `getLibraryCanonicalKeys` / `getEarlySkipBannedCanonicalKeys`.

**Verified import path:** `com.stash.core.data.sync.TrackMatcher`.

Both `TrackMatcher.canonicalArtist(artist)` and `TrackMatcher.canonicalTitle(title)` are non-suspend, deterministic, lowercase + trim — same normalization that the sync writer uses to populate `tracks.canonical_artist` and `tracks.canonical_title`. No normalization drift risk between filter keys and library keys.

Injection (already detailed in Component 2 above; restating for clarity): add `trackMatcher: TrackMatcher` and `trackSkipEventDao: TrackSkipEventDao` to the worker constructor. Hilt resolves both.

## Tests

### MockK unit tests

- **Extend `MixSeedStrategyTest.kt`**: no change — MixSeedGenerator itself is untouched.
- **`StashMixRefreshWorkerSeedFilterTest`** (new): MockK worker test that:
  - Stubs `seedGenerator.generate` to return a known candidate list (e.g., 50 candidates).
  - Stubs `trackDao.getLibraryCanonicalKeys` to return keys matching 20 of those candidates.
  - Stubs `trackSkipEventDao.getEarlySkipBannedCanonicalKeys` to return keys matching 5 different candidates.
  - Asserts `mixGenerator.queueDiscoveryCandidates` is called with exactly the 25 remaining (50 - 20 - 5).
  - Edge case: when filtered pool falls below `MIN_DISCOVERY_POOL_AFTER_FILTER`, verify the TAG_GRAPH fallback fires (stub seedGenerator.generate for both ARTIST_SIMILAR and TAG_GRAPH and assert both are called).
  - Edge case: when all candidates filter out, verify `queueDiscoveryCandidates` is NOT called and the log line fires.
- **Update `DownloadConstraintsTest.kt`**: three existing tests assert mode-dependent behavior; rewrite as mode-independent (all three modes produce the same constraints now).

### Robolectric + Room in-memory tests

- **`TrackDaoCanonicalKeysTest`** (new):
  - Seed 3 downloaded tracks + 2 undownloaded.
  - Assert `getLibraryCanonicalKeys()` returns exactly the 3 keys for downloaded tracks.
  - Verify DISTINCT — two downloaded tracks with identical canonical key return ONE row.
- **`TrackSkipEventDaoBanListTest`** (new):
  - Seed 3 tracks: A, B, C.
  - Seed skip events:
    - A: 4 events at position 1000ms (within window) → SHOULD ban
    - B: 4 events at position 60000ms (outside position cutoff) → should NOT ban
    - C: 2 events at position 1000ms (below count threshold) → should NOT ban
  - Assert `getEarlySkipBannedCanonicalKeys(3, sinceMs = far past, maxPositionMs = 30_000)` returns only A's canonical key.
  - Edge: time window — seed an event from 100 days ago for A; verify it's excluded from a 90-day query.

### Manual on-device verification

1. Cold-start the app. Watch logcat: `StashMigration` fires; `DiscoveryDownloadWorker` orphan drain queues (PR 5).
2. Plug in to charge + WiFi if not already; OR stay on cellular (PR 6 change means manual trigger fires on cellular too).
3. Long-press Daily Discover → Refresh. Snackbar "Refreshing Daily Discover…" appears.
4. Tail logcat:
   ```
   adb logcat -t 500 | grep -iE "stashmixrefresh|stashdiscovery|discoverydownload"
   ```
   Expected line: `StashMixRefresh: 'Daily Discover': N candidates via ARTIST_SIMILAR (M filtered as library/banned)`. M should be substantial (likely >50% of N for a large library).
5. Wait for `StashDiscoveryWorker` and `DiscoveryDownloadWorker` to fire and complete (foreground notification visible).
6. Re-open Daily Discover. Track count should grow. Sample 5-10 random tracks — none should be ones you recognize from your library. Tap several to verify they play.
7. Repeat for Deep Cuts and First Listen.
8. Spot-check: tap a recommended track in Daily Discover, immediately skip it (within 5 seconds). Repeat with the same track 3 times across the next refresh cycles. After the 3rd skip, refresh Daily Discover again. The skipped track should NOT reappear in the mix.

## Failure modes + edge cases

1. **Library is enormous, every Last.fm candidate dedups to library.** `filtered.size` is 0; TAG_GRAPH fallback fires. If that ALSO returns 0 (extremely unlikely for any active Last.fm user), the worker logs `all candidates filtered out` and skips the queue insert. The mix retains its existing state. Next refresh tries again — Last.fm's `artist.getSimilar` returns slightly different candidates per call (rate-limit jitter), so a few cycles may yield candidates.

2. **Library is empty (fresh install).** `getLibraryCanonicalKeys` returns `[]`. Filter is a no-op. Pipeline behaves as if PR 6 wasn't applied — appropriate for a new user with nothing to compare against.

3. **A track in the user's library AND in the skip-ban set.** Filter checks both sets independently; tracks fail both. Net effect: skipped library tracks are excluded from discovery candidates (which they would be anyway via the library filter). Fine.

4. **A canonical-key collision between two distinct tracks** (e.g., two different songs both canonicalizing to `"the_beatles|yesterday"`). Filter treats them as same. Affects ALL canonical-dedup logic in the codebase — not introduced by this PR; consistent with how `StashDiscoveryWorker.handle()` already operates.

5. **User skipped a track 3 times but loves it now.** Currently no "un-ban" affordance. After 90 days the skip events age out of the window naturally. If the user wants to re-discover something they skipped recently, they can add it via Search. Phase 3 (feedback UI) will add explicit un-ban via thumbs-up.

6. **Manual-trigger network relaxation surprises a user on a limited data plan.** They explicitly tapped refresh — they're aware. Their `DownloadNetworkMode` pref STILL governs the periodic background cycle (most data usage by far). The manual trigger downloads at most ~30-40 tracks worth of yt-dlp downloads on cellular — measurable but not catastrophic.

7. **`TrackMatcher` is the right canonicalizer.** Verify during implementation that the canonical-key produced by `TrackMatcher.canonicalArtist(c.artist) + "|" + TrackMatcher.canonicalTitle(c.title)` matches the format stored in `tracks.canonical_artist || '|' || tracks.canonical_title`. If there's any normalization drift (e.g., the DAO stores lowercased but TrackMatcher returns title-case), the filter silently fails. Add a smoke-test if uncertain.

## Rollout

Single APK install on the Pixel, manual verification of the test plan above. No flag, no staged rollout.

## Open questions

None. All three design decisions made (delegated to my judgment, justified above):
- Manual-trigger constraint: relaxed to `CONNECTED`, mode-independent.
- Library pre-filter: HARD, at the seed-generator-call boundary in the worker.
- Skip ban: 3 skips in 90 days, position-aware (first 30 seconds only).
