package com.stash.data.lyrics.sidecar

import com.stash.core.data.db.entity.LyricsEntity
import javax.inject.Inject

/**
 * Writes the sidecar `.lrc` (or plain-text fallback) file next to the
 * downloaded audio file, both into internal storage and any user-chosen
 * SAF tree. Triggered by [com.stash.data.lyrics.LyricsRepository] after
 * a successful lyrics fetch; skipped entirely when the result is
 * instrumental.
 *
 * Implementations MUST treat write failure as non-fatal — the Room
 * row + `tracks.lyrics_fetched_at` stamp are the source of truth, and
 * the sidecar is a "if-we-can" courtesy for external players.
 *
 * The real implementation lands in Task 7. Until then, [NoOpLyricsSidecarWriter]
 * is bound as the default so [com.stash.data.lyrics.LyricsRepository]
 * compiles and is fully testable in T6.
 */
interface LyricsSidecarWriter {
    suspend fun write(trackId: Long, lyrics: LyricsEntity)
}

/**
 * Default no-op binding wired in [com.stash.data.lyrics.di.LyricsModule].
 * Task 7 replaces this with the real internal-storage + SAF impl behind
 * the same [LyricsSidecarWriter] interface.
 */
class NoOpLyricsSidecarWriter @Inject constructor() : LyricsSidecarWriter {
    override suspend fun write(trackId: Long, lyrics: LyricsEntity) = Unit
}
