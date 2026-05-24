package com.stash.data.lyrics.backfill

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore for the v0.9.36 lyrics-backfill pipeline.
 *
 * Top-level extension — the property delegate enforces a single
 * [DataStore] instance per file path per process. `internal` so other
 * classes in this module (and the test class) can resolve the same
 * singleton without going through the public API.
 *
 * Lives in `lyrics_backfill_state.preferences_pb`, deliberately separate
 * from `metadata_backfill_state.preferences_pb` per spec §6.3: the two
 * backfills run independently and a wipe of one shouldn't accidentally
 * reset the other.
 */
internal val Context.lyricsBackfillDataStore by preferencesDataStore(name = "lyrics_backfill_state")

/**
 * Banner state machine for the lyrics-backfill worker.
 *
 *  - [IDLE] — no backfill in flight, banner hidden.
 *  - [RUNNING] — worker is iterating; banner shows progress.
 *  - [FINISHED] — worker drained the queue; banner shows the completion
 *    summary until the user dismisses it via
 *    [LyricsBackfillState.markFinishedAcknowledged].
 *
 * Intentionally NOT shared with the v0.9.35 metadata backfill's enum
 * (spec §6.3): the two pipelines progress on independent timelines and
 * the Home banner reads each snapshot separately, so a shared enum would
 * only couple them artificially.
 */
enum class State { IDLE, RUNNING, FINISHED }

/**
 * Snapshot of [LyricsBackfillState] consumed by the Home banner
 * (`LyricsBackfillBannerState`, Task 11). [finishedAt] is `null` while
 * the worker is running and stamped with `System.currentTimeMillis()`
 * inside [LyricsBackfillState.markFinished]; the banner uses it to drive
 * the "Lyrics fetched · 2m ago" subtitle.
 */
data class LyricsBackfillSnapshot(
    val state: State,
    val processed: Int,
    val total: Int,
    val finishedAt: Long?,
)

/**
 * Persists the observable state of the lyrics-backfill worker so the
 * Home banner (Task 11) can render progress without polling the worker
 * directly.
 *
 * No `safSkipped` counter here — unlike v0.9.35 metadata embedding
 * (which couldn't touch SAF content URIs in place), lyrics sidecars are
 * written by [com.stash.data.lyrics.sidecar.LyricsSidecarWriter] which
 * already handles the SAF tree path via DocumentFile. The backfill loop
 * itself has nothing SAF-specific to skip, so there's no equivalent
 * counter to surface on the Home banner.
 */
@Singleton
class LyricsBackfillState @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store: DataStore<Preferences> get() = context.lyricsBackfillDataStore

    val snapshot: Flow<LyricsBackfillSnapshot> = store.data.map { prefs ->
        val stateOrdinal = prefs[KEY_STATE] ?: State.IDLE.ordinal
        LyricsBackfillSnapshot(
            state = State.entries[stateOrdinal],
            processed = prefs[KEY_PROCESSED] ?: 0,
            total = prefs[KEY_TOTAL] ?: 0,
            finishedAt = prefs[KEY_FINISHED_AT],
        )
    }

    suspend fun markStarted(total: Int) {
        store.edit {
            it[KEY_STATE] = State.RUNNING.ordinal
            it[KEY_PROCESSED] = 0
            it[KEY_TOTAL] = total
            it.remove(KEY_FINISHED_AT)
        }
    }

    suspend fun publishProgress(processed: Int, total: Int) {
        store.edit {
            it[KEY_STATE] = State.RUNNING.ordinal
            it[KEY_PROCESSED] = processed
            it[KEY_TOTAL] = total
        }
    }

    suspend fun markFinished() {
        store.edit {
            it[KEY_STATE] = State.FINISHED.ordinal
            it[KEY_FINISHED_AT] = System.currentTimeMillis()
        }
    }

    suspend fun markFinishedAcknowledged() {
        store.edit {
            it[KEY_STATE] = State.IDLE.ordinal
        }
    }

    private companion object {
        val KEY_STATE = intPreferencesKey("state")
        val KEY_PROCESSED = intPreferencesKey("processed")
        val KEY_TOTAL = intPreferencesKey("total")
        val KEY_FINISHED_AT = longPreferencesKey("finished_at")
    }
}
