// StashRenderersFactory.kt
package com.stash.core.media.equalizer

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.stash.core.media.equalizer.dsp.BassShelfProcessor
import com.stash.core.media.equalizer.dsp.EqProcessor
import com.stash.core.media.equalizer.dsp.LoudnessGainProcessor
import com.stash.core.media.equalizer.dsp.PreampProcessor
import com.stash.core.media.equalizer.dsp.SoftClipLimiterProcessor

/**
 * Custom RenderersFactory that builds an audio sink with our EQ + loudness chain.
 *
 * The chain is built ONCE per ExoPlayer instance. Toggling EQ or loudness
 * enabled is a flag flip read by each processor on every buffer — never a
 * topology change. This is what makes "stacking on re-enable" structurally
 * impossible.
 *
 * Chain order (see spec §3.2):
 *   PreampProcessor → EqProcessor → BassShelfProcessor →
 *   LoudnessGainProcessor → SoftClipLimiterProcessor
 *
 * Loudness sits AFTER the EQ stages so the per-track gain operates on the
 * fully-shaped signal, and the soft-clip limiter sits LAST so any positive-
 * gain transient that survives true-peak headroom is clamped before the
 * mixer ever sees it.
 */
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
        return DefaultAudioSink.Builder(context)
            // setEnableFloatOutput(true) routes high-resolution PCM through
            // a separate Media3 branch that BYPASSES user-supplied processors —
            // verified in DefaultAudioSink source. Keep it false so 24-bit
            // FLAC is downsampled to 16-bit and our chain still applies.
            .setEnableFloatOutput(false)
            .setAudioProcessors(buildAudioProcessors())
            .build()
    }

    /**
     * Single source of truth for the processor chain. Called once by
     * [buildAudioSink] for production and once by [audioProcessorsForTest] from
     * the unit test that asserts ordering. Keep this private; expose only the
     * `internal` accessor below so tests can verify the wiring without
     * cracking open the sink.
     */
    private fun buildAudioProcessors(): Array<AudioProcessor> {
        return if (ENABLE_LOUDNESS) {
            arrayOf(
                PreampProcessor(eqController),
                EqProcessor(eqController),
                BassShelfProcessor(eqController),
                LoudnessGainProcessor(loudnessController),
                SoftClipLimiterProcessor(),
            )
        } else {
            arrayOf(
                PreampProcessor(eqController),
                EqProcessor(eqController),
                BassShelfProcessor(eqController),
            )
        }
    }

    /**
     * Test-only accessor that returns the exact processor array the factory
     * would hand to [DefaultAudioSink]. Used by `StashRenderersFactoryTest` to
     * verify chain ordering without constructing a real audio sink (which
     * requires Android hardware mocking that isn't worth the friction).
     */
    internal fun audioProcessorsForTest(): Array<AudioProcessor> = buildAudioProcessors()

    private companion object {
        /**
         * Kill-switch for the loudness stage. Flip to `false` to ship without
         * loudness if a regression surfaces; the EQ chain continues to work
         * exactly as before since [LoudnessGainProcessor] and
         * [SoftClipLimiterProcessor] are conditionally appended only when this
         * flag is true.
         */
        const val ENABLE_LOUDNESS = true
    }
}
