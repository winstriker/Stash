package com.stash.data.lyrics

import android.util.Log
import com.stash.core.common.Clock
import com.stash.core.data.db.dao.LyricsDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.LyricsEntity
import com.stash.data.lyrics.sidecar.LyricsSidecarWriter
import com.stash.data.lyrics.source.LyricsQuery
import com.stash.data.lyrics.source.LyricsSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sole entrypoint for the lyrics subsystem. Both UI (Now Playing sheet)
 * and workers (post-download + backfill) go through this class.
 *
 * `resolveAndStore` walks the [sources] chain in priority order
 * (LRCLIB -> InnerTube; ordering enforced in `LyricsModule.provideLyricsSources`),
 * persists the first non-null result to the `lyrics` table, stamps
 * `tracks.lyrics_fetched_at` with the success epoch-millis, and
 * triggers a sidecar `.lrc` write for the non-instrumental case.
 *
 * Sentinel rules (see `LyricsDao` docs):
 * - Successful fetch -> upsert lyrics row + stamp `lyrics_fetched_at = clock.now()`.
 * - Complete miss across all sources -> no row + stamp `lyrics_fetched_at = 0L`.
 *   The 0L sentinel keeps the backfill worker's `WHERE lyrics_fetched_at IS NULL`
 *   predicate honest so it terminates.
 *
 * Sidecar-write failure is logged but does NOT unwind the Room state.
 * The Room row + stamp are the source of truth; the sidecar is a best-effort
 * courtesy for external players.
 */
@Singleton
class LyricsRepository @Inject constructor(
    private val sources: List<@JvmSuppressWildcards LyricsSource>,
    private val lyricsDao: LyricsDao,
    private val trackDao: TrackDao,
    private val sidecarWriter: LyricsSidecarWriter,
    private val clock: Clock,
) {

    /** Observe the lyrics row for [trackId]. Emits null when no row exists yet. */
    fun observe(trackId: Long): Flow<LyricsEntity?> = lyricsDao.observe(trackId)

    /** One-shot read of the lyrics row for [trackId], or null when absent. */
    suspend fun get(trackId: Long): LyricsEntity? = lyricsDao.get(trackId)

    /**
     * Walks [sources] in order, returns the first non-null
     * [com.stash.data.lyrics.source.LyricsResult], persists it to Room,
     * stamps `tracks.lyrics_fetched_at`, and (for non-instrumental hits)
     * triggers a sidecar `.lrc` write.
     *
     * On a complete miss, stamps `tracks.lyrics_fetched_at = 0L` and
     * returns null without writing a row.
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
                .onFailure { e ->
                    // Non-fatal: Room row + stamp are the source of truth.
                    Log.w(TAG, "Sidecar write failed for trackId=${query.trackId}", e)
                }
        }
        return entity
    }

    private companion object {
        private const val TAG = "LyricsRepository"
    }
}
