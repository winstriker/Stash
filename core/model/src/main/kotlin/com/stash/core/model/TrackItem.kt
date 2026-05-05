package com.stash.core.model

/**
 * Lightweight representation of a track as seen from the search tab.
 *
 * Originally nested inside [com.stash.core.media.actions.TrackActionsDelegate],
 * moved to `:core:model` so that `:data:download` can reference it from
 * [com.stash.data.download.search.SearchDownloadCoordinator] without
 * creating a circular module dependency
 * (`:core:media` → `:data:download` → `:core:media`).
 *
 * Only carries the fields the coordinator and prefetcher need:
 * a stable [videoId] for dedup, [title]/[artist] for identity matching
 * and metadata embedding, [durationSeconds] for lossless-source duration
 * filtering, and [thumbnailUrl] for the cover-art fallback.
 */
data class TrackItem(
    val videoId: String,
    val title: String,
    val artist: String,
    /** Duration as a Double so sub-second precision is preserved. */
    val durationSeconds: Double,
    val thumbnailUrl: String?,
)
