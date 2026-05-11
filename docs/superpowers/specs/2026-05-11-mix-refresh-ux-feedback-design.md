# Mix Refresh + Lossless Sweep UX Feedback Design

> **Status:** Design — pending implementation plan.
> **Scope:** PR 1 of a 4-PR series. UX feedback gaps only (Symptoms 1 + 4).
> **Related design docs:** none new; touches code from `2026-05-08-flac-only-mode-design.md` and `2026-05-08-find-in-flac-design.md`.

---

## Goal

Make two fire-and-forget user actions on the Home screen produce visible acknowledgment, so the user can tell the difference between "I tapped nothing" and "the system did its job."

The two actions are:

1. **Long-press a Stash Mix card → tap "Refresh this mix."** Today the bottom sheet just closes; whether the worker succeeded, failed, or was never enqueued at all, the user sees nothing.
2. **Tap the "N tracks waiting for lossless" banner.** Today the `LosslessRetryWorker` runs (or doesn't), the DB count flow updates (or doesn't), and the user has no signal that anything happened.

Both flows already have a working `_userMessages` channel and a Toast collector in the UI layer. We just need to wire enqueue-time + completion-time messages and surface output data from the lossless retry worker.

A secondary goal: address one credible cause of "the periodic refresh hasn't fired in 3 days" — `ExistingPeriodicWorkPolicy.KEEP` ignores changes to constraints + worker spec across upgrades. Flipping to `UPDATE` ensures every cold start re-applies the current spec.

## Non-goals

Things explicitly **out of scope** for this PR (they belong to follow-up PRs):

- Match-quality fix for `QobuzSource.normalize` (Symptom 4a — separate PR 2).
- Inter-mix dedup logic (Symptom 3 — separate PR 3).
- Non-sync downloader for discovery rows (Symptom 2 — separate PR 4).
- Recipe parameter retuning + shortfall-fill gate (Symptom 5 — bundled with PR 3).
- A debug "Mixes diagnostics" screen — defer until PR 1 ships and we can observe whether the periodic + manual flows now look healthy.

## Architecture

Three small, additive changes in three separate modules — no schema migration, no new entities, no new workers.

```
┌────────────────────────────────────────────────────────────────────────┐
│ feature:home                                                           │
│                                                                        │
│   HomeViewModel.refreshMix(playlistId)                                 │
│     ├── emit "Refreshing $name…"                                       │
│     ├── recipe = recipeDao.findByPlaylistId(playlistId)                │
│     │     ├── null branch → Log.w + emit "Couldn't refresh — …"        │
│     │     └── found branch → enqueueOneTime(context, recipe.id)        │
│     └── observe WorkInfo for "${ONE_SHOT_WORK_NAME}_${recipe.id}"      │
│           └── on SUCCEEDED → emit "Refreshed $name"                    │
│                                                                        │
│   HomeViewModel.onRetryDeferredRequested()                             │
│     ├── snapshot waitingForLosslessCount                               │
│     ├── emit "Looking for FLAC versions of N tracks…"                  │
│     ├── enqueueUniqueWork(LosslessRetryWorker.UNIQUE_WORK_NAME, KEEP)  │
│     └── observe WorkInfo for UNIQUE_WORK_NAME                          │
│           └── on SUCCEEDED → read output data (resolved, total)        │
│                 ├── resolved == 0 → "None resolved this time —         │
│                 │                    we'll keep trying."               │
│                 └── resolved > 0  → "Resolved $resolved/$total.        │
│                                       $remaining still waiting."       │
└────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────┐
│ data:download                                                          │
│                                                                        │
│   LosslessRetryWorker.doWork()                                         │
│     ├── deferred = downloadQueueDao.waitingForLosslessTracks()         │
│     ├── for each → resolve via registry; if match → flip to PENDING    │
│     ├── resolvedCount = count of flips                                 │
│     └── Result.success(workDataOf(                                     │
│           KEY_RESOLVED to resolvedCount,                               │
│           KEY_TOTAL to deferred.size,                                  │
│         ))                                                             │
└────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────┐
│ core:data                                                              │
│                                                                        │
│   StashMixRefreshWorker.schedulePeriodic(context)                      │
│     ExistingPeriodicWorkPolicy.KEEP → ExistingPeriodicWorkPolicy.UPDATE│
└────────────────────────────────────────────────────────────────────────┘
```

## Detailed component design

### Component 1 — `HomeViewModel.refreshMix` snackbar lifecycle

**Responsibility:** Surface enqueue-time + completion-time messages for the single-recipe refresh action.

**Current state (`HomeViewModel.kt:554-559`):**

```kotlin
fun refreshMix(playlistId: Long) {
    viewModelScope.launch {
        val recipe = recipeDao.findByPlaylistId(playlistId) ?: return@launch
        StashMixRefreshWorker.enqueueOneTime(context, recipe.id)
    }
}
```

**New behavior:**

1. Look up the recipe via `recipeDao.findByPlaylistId(playlistId)`.
2. If recipe is **null** (data-integrity bug — `playlist.type == STASH_MIX` but no back-linked recipe):
   - `Log.w(TAG, "refreshMix: no recipe back-links playlistId=$playlistId")`
   - Emit `"Couldn't refresh — this mix isn't linked to a recipe"` to `_userMessages`.
   - Return.
3. If recipe is **found**:
   - Emit `"Refreshing ${recipe.name}…"` to `_userMessages`.
   - Enqueue the worker via `StashMixRefreshWorker.enqueueOneTime(context, recipe.id)`.
   - Subscribe to `WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("${StashMixRefreshWorker.ONE_SHOT_WORK_NAME}_${recipe.id}")` inside `viewModelScope`.
   - When the WorkInfo list contains a terminal-state entry:
     - `WorkInfo.State.SUCCEEDED` → emit `"Refreshed ${recipe.name}"`.
     - `WorkInfo.State.FAILED` or `CANCELLED` → emit `"Refresh failed — try again later"`. (Worker today returns `Result.success()` on every path, but cancellation is still possible via REPLACE-on-rapid-tap.)
     - `WorkInfo.State.RUNNING` → no-op (we already emitted the start message).
   - Cancel the subscription once a terminal state is observed, to keep the viewModelScope clean across many taps.

**Why observe a Flow rather than fire-and-forget:** the worker may take several seconds when discovery seeding hits Last.fm; the user deserves a "done" signal, not just a "started" signal. The `getWorkInfosForUniqueWorkFlow` API was introduced in WorkManager 2.8 and is already in use elsewhere in the codebase (verify on read).

**Concurrency note:** the worker uses `ExistingWorkPolicy.REPLACE`, so a rapid double-tap on the same mix coalesces into one job. The Flow returns a list, not a single value; take the most recent entry by `id` to compare against terminal states. If two distinct refresh actions are in flight for different recipes, each has its own unique work name (`"${ONE_SHOT_WORK_NAME}_$recipeId"`), so their flows are independent.

### Component 2 — `LosslessRetryWorker` output data

**Responsibility:** Return enough information for the calling UI to render a result snackbar.

**Current state (`LosslessRetryWorker.kt:44-73`):**

The worker iterates `WAITING_FOR_LOSSLESS` rows, flips each successful match to `PENDING`, and returns `Result.success()` with no output data.

**New behavior:**

- Add `companion object` constants:

  ```kotlin
  const val KEY_RESOLVED = "lossless_retry_resolved"
  const val KEY_TOTAL = "lossless_retry_total"
  ```

- Track `resolvedCount` locally inside the loop:

  ```kotlin
  var resolvedCount = 0
  for (entry in deferred) {
      // existing resolve logic
      if (match != null) {
          downloadQueueDao.updateStatus(id = entry.id, status = DownloadStatus.PENDING)
          resolvedCount++
      }
  }
  ```

- Return with output data:

  ```kotlin
  return Result.success(
      workDataOf(
          KEY_RESOLVED to resolvedCount,
          KEY_TOTAL to deferred.size,
      ),
  )
  ```

- The empty-input early return (line 46) also needs `Result.success(workDataOf(KEY_RESOLVED to 0, KEY_TOTAL to 0))` so the UI consumer doesn't get null output data when called against an empty queue.

### Component 3 — `HomeViewModel.onRetryDeferredRequested` snackbar lifecycle

**Responsibility:** Surface enqueue-time + completion-time messages for the banner-tap retry sweep.

**Current state (`HomeViewModel.kt:404-411`):**

```kotlin
fun onRetryDeferredRequested() {
    val request = OneTimeWorkRequestBuilder<LosslessRetryWorker>().build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        LosslessRetryWorker.UNIQUE_WORK_NAME,
        ExistingWorkPolicy.KEEP,
        request,
    )
}
```

**New behavior:**

1. Snapshot the current waiting count by collecting one value from whatever flow drives `waitingForLosslessBanner.count` (likely a DAO Flow — verify on read).
2. Emit `"Looking for FLAC versions of $count tracks…"` to `_userMessages`. If `count == 0`, skip the start message and the enqueue (early return — there's nothing to sweep, and the banner shouldn't be visible anyway, but this is defensive).
3. Enqueue the worker (existing code path).
4. Subscribe to `getWorkInfosForUniqueWorkFlow(LosslessRetryWorker.UNIQUE_WORK_NAME)` in `viewModelScope`.
5. On the first `WorkInfo.State.SUCCEEDED` after enqueue:
   - Read `KEY_RESOLVED` and `KEY_TOTAL` from `outputData`. Defensively default to `0`.
   - `resolved == 0` → emit `"None resolved this time — we'll keep trying."`
   - `resolved > 0` → compute `remaining = total - resolved`; emit `"Resolved $resolved/$total. $remaining still waiting."`
6. On `FAILED` or `CANCELLED`: emit `"Sweep failed — try again later"`.
7. Cancel the subscription after the terminal state.

**Subtle concurrency case:** because of `ExistingWorkPolicy.KEEP`, a rapid double-tap returns the *same* WorkInfo. The subscription started by the second tap may observe a `SUCCEEDED` state for work that was already complete *before* the second tap fired. That would emit a duplicate "Resolved …" message. To handle: filter the Flow on enqueue-time `id` — observe only the WorkInfo whose `id` matches the `WorkContinuation`/`Operation` result returned by `enqueueUniqueWork`.

### Component 4 — Periodic refresh policy flip

**Responsibility:** Ensure every cold start re-applies the current periodic worker spec.

**Current state (`StashMixRefreshWorker.kt:119-123`):**

```kotlin
WorkManager.getInstance(context).enqueueUniquePeriodicWork(
    WORK_NAME,
    ExistingPeriodicWorkPolicy.KEEP,
    work,
)
```

**Change:**

```kotlin
WorkManager.getInstance(context).enqueueUniquePeriodicWork(
    WORK_NAME,
    ExistingPeriodicWorkPolicy.UPDATE,
    work,
)
```

**Side effect:** existing installs that already have a periodic spec will have it overwritten on next cold start. WorkManager preserves the *next-run* clock when the policy is `UPDATE` and the constraints + initial delay haven't moved, so this should not delay the next firing. (Verify against WorkManager 2.9+ semantics during implementation — Android docs are explicit about this for `UPDATE` since 2.8.)

**No data migration needed.** This is a one-line change that takes effect on the next cold start after the user installs PR 1's APK.

## User-facing copy

All strings live in `feature/home/.../strings.xml` (or wherever the existing `_userMessages` strings are sourced — verify during implementation). Proposed keys + values:

| Key                                            | Value                                                         |
| ---------------------------------------------- | ------------------------------------------------------------- |
| `home_mix_refresh_starting`                    | `Refreshing %1$s…`                                            |
| `home_mix_refresh_done`                        | `Refreshed %1$s`                                              |
| `home_mix_refresh_failed`                      | `Refresh failed — try again later`                            |
| `home_mix_refresh_no_recipe`                   | `Couldn't refresh — this mix isn't linked to a recipe`        |
| `home_lossless_retry_starting`                 | `Looking for FLAC versions of %1$d tracks…`                   |
| `home_lossless_retry_done_partial`             | `Resolved %1$d/%2$d. %3$d still waiting.`                     |
| `home_lossless_retry_done_zero`                | `None resolved this time — we'll keep trying.`                |
| `home_lossless_retry_failed`                   | `Sweep failed — try again later`                              |

Use the same Material 3 Snackbar surface that the existing `_userMessages` Toast collector renders — no new UI primitive.

## Failure modes + edge cases

1. **`findByPlaylistId` returns null on a `STASH_MIX` playlist.** Data-integrity bug (recipe → playlist linkage lost via FK SET_NULL on a deleted-and-replayed playlist). User sees `"Couldn't refresh — this mix isn't linked to a recipe"`. Diagnostic log captures `playlistId`. Follow-up: a separate PR can add a self-heal that calls `enqueueOneTime(context)` (the no-arg full-set refresh) which will create-or-relink the recipe's playlist on its next pass.

2. **WorkManager unique work name collision.** The single-recipe refresh uses `"${ONE_SHOT_WORK_NAME}_$recipeId"`. The full-set refresh uses `ONE_SHOT_WORK_NAME` (no suffix). They are distinct, so observing one Flow doesn't pick up the other. The periodic uses `WORK_NAME` (also distinct).

3. **User taps "Refresh" then closes the app before work completes.** The viewModelScope subscription dies with the ViewModel. No snackbar fires. Acceptable — the worker still finishes and updates the DB; on next app launch the new track list is visible. The lost "Refreshed" message is not worth persistent state.

4. **User taps "Refresh" twice in 1 second.** REPLACE policy means the second enqueue cancels the first. The first subscription observes `CANCELLED`, emits the failure snackbar, and exits. The second subscription observes `SUCCEEDED` on completion and emits the success snackbar. Net UX: a brief "failed → succeeded" flicker. Mitigation deferred — not a real-world pattern.

5. **Lossless sweep finds zero matches against an empty queue.** Worker early returns `Result.success()` with output data `(resolved=0, total=0)`. The start message has already been emitted with `count=0`, which is awkward (`"Looking for FLAC versions of 0 tracks…"`). Defense: skip the start message and the enqueue entirely when the snapshot count is 0.

6. **`getWorkInfosForUniqueWorkFlow` returns historical entries.** The Flow includes work that has already completed before the subscription started. Filter by enqueue-time `WorkInfo.id` to avoid emitting stale "Refreshed" / "Resolved" messages from a previous session.

## Testing strategy

### Unit tests

- **`LosslessRetryWorkerTest`** — extend existing tests to assert output data emission:
  - Empty queue → `Result.success` with `(resolved=0, total=0)`.
  - 3 tracks, 0 matches → `(resolved=0, total=3)`.
  - 3 tracks, 2 matches → `(resolved=2, total=3)`.
- **`StashMixRefreshWorkerTest`** — no new tests; the policy flip is configuration only.
- **`HomeViewModelTest`** — add unit tests for `refreshMix` and `onRetryDeferredRequested` exercising:
  - Null recipe → no-recipe snackbar fires, no enqueue happens.
  - Found recipe → "starting" snackbar fires immediately.
  - Use a fake `WorkManager` (Robolectric or hand-rolled) to drive WorkInfo states and assert the terminal snackbars.

### Manual test plan on Pixel after install

1. Reboot device → wait for app to cold-start. Verify no crash on `enqueueUniquePeriodicWork(UPDATE)`.
2. Long-press Daily Discover → tap "Refresh this mix." Expect:
   - Bottom sheet dismisses.
   - Snackbar: `"Refreshing Daily Discover…"` within ~100ms.
   - Snackbar: `"Refreshed Daily Discover"` when the worker finishes (typically a few seconds).
3. Repeat for Deep Cuts and First Listen.
4. Tap the "N tracks waiting for lossless" banner. Expect:
   - Snackbar: `"Looking for FLAC versions of N tracks…"`.
   - Snackbar after the sweep: `"None resolved this time — we'll keep trying."` (because Symptom 4a's match-quality bug isn't fixed in this PR).
5. Force-stop the app → reopen. Verify the periodic worker is now showing as scheduled in `dumpsys jobscheduler` (search for the StashMixRefreshWorker class).

### Regression checks

- Existing `WaitingForLosslessBannerTest` must still pass — the banner state-machine is untouched.
- Existing `LosslessRetryWorkerTest` cases for "no work → success" must still pass with the new output-data shape.

## Open questions for implementation phase

None blocking the spec. During the writing-plans phase, the implementer should:

1. Verify the exact name of the user-messages flow used by the Toast collector (likely `_userMessages` per memory of prior work, but confirm the buffer capacity is ≥ 8 so messages don't get dropped — already raised to 8 in v0.9.18 per session memory).
2. Verify the exact DAO method that powers the banner's waiting count, and ensure it's a Flow we can collect a single snapshot from without subscribing forever.
3. Confirm `WorkManager.getWorkInfosForUniqueWorkFlow` is available in the project's WorkManager version. If not, fall back to `getWorkInfosForUniqueWorkLiveData().asFlow()`.

## Rollout

Single APK install on the Pixel, manual verification of the test plan above. No flag, no staged rollout. If verification passes, push the branch and tag as v0.9.20.
