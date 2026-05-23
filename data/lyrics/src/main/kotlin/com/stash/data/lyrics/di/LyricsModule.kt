package com.stash.data.lyrics.di

import com.stash.core.common.Clock
import com.stash.core.common.SystemClock
import com.stash.data.lyrics.sidecar.LyricsSidecarWriter
import com.stash.data.lyrics.sidecar.NoOpLyricsSidecarWriter
import com.stash.data.lyrics.source.InnerTubeLyricsGateway
import com.stash.data.lyrics.source.InnerTubeLyricsGatewayImpl
import com.stash.data.lyrics.source.LrclibLyricsSource
import com.stash.data.lyrics.source.LyricsSource
import com.stash.data.lyrics.source.YtMusicLyricsSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt wiring for the `:data:lyrics` module.
 *
 * Three responsibilities:
 * 1. Binds the production [InnerTubeLyricsGateway] implementation.
 * 2. Provides an ordered `List<LyricsSource>` (LRCLIB first, then InnerTube)
 *    consumed by [com.stash.data.lyrics.LyricsRepository]. Ordering is
 *    semantic — LRCLIB wins because it can return synced LRC, InnerTube
 *    is plain-text fallback — so a multibinding `Set` is deliberately
 *    NOT used (sets do not preserve insertion order).
 * 3. Binds [Clock] -> [SystemClock] for the repository's epoch-millis stamps.
 *    Lives here because `:core:common` is currently Hilt-free; if other
 *    modules later need [Clock], lift this binding to a shared module.
 * 4. Binds a no-op default [LyricsSidecarWriter]. Task 7 replaces it
 *    with the real internal-storage + SAF impl behind the same interface.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LyricsModule {

    @Binds
    @Singleton
    abstract fun bindInnerTubeLyricsGateway(
        impl: InnerTubeLyricsGatewayImpl,
    ): InnerTubeLyricsGateway

    @Binds
    @Singleton
    abstract fun bindLyricsSidecarWriter(
        impl: NoOpLyricsSidecarWriter,
    ): LyricsSidecarWriter

    companion object {

        /**
         * Ordered source chain consumed by `LyricsRepository`. The order
         * defines fallback priority: LRCLIB first (can return synced LRC),
         * InnerTube second (plain text only).
         */
        @Provides
        @Singleton
        fun provideLyricsSources(
            lrclib: LrclibLyricsSource,
            ytmusic: YtMusicLyricsSource,
        ): List<@JvmSuppressWildcards LyricsSource> = listOf(lrclib, ytmusic)

        @Provides
        @Singleton
        fun provideClock(): Clock = SystemClock()
    }
}
