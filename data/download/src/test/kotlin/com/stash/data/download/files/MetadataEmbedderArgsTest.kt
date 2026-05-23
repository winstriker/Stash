package com.stash.data.download.files

import com.stash.core.common.AppVersionProvider
import com.stash.core.model.Track
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MetadataEmbedderArgsTest {

    private val versionProvider = object : AppVersionProvider {
        override val versionName: String = "0.9.35"
        override val versionCode: Int = 71
    }

    @Test fun `writes ALBUMARTIST + album_artist when track has albumArtist`() {
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.opus"),
            outputFile = File("/tmp/out.opus"),
            track = Track(
                id = 1, title = "T", artist = "Drake, 21 Savage",
                albumArtist = "Drake",
            ),
            albumArtFile = null,
            appVersion = versionProvider,
        )
        assertTrue("ALBUMARTIST=Drake" in args.zipMetadataValues())
        assertTrue("album_artist=Drake" in args.zipMetadataValues())
    }

    @Test fun `falls back to artist when albumArtist is blank`() {
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.opus"),
            outputFile = File("/tmp/out.opus"),
            track = Track(
                id = 1, title = "T", artist = "Drake",
                albumArtist = "",
            ),
            albumArtFile = null,
            appVersion = versionProvider,
        )
        assertTrue("ALBUMARTIST=Drake" in args.zipMetadataValues())
    }

    @Test fun `writes ISRC when present`() {
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.opus"),
            outputFile = File("/tmp/out.opus"),
            track = Track(id = 1, title = "T", artist = "A", isrc = "USRC17607839"),
            albumArtFile = null,
            appVersion = versionProvider,
        )
        assertTrue("ISRC=USRC17607839" in args.zipMetadataValues())
    }

    @Test fun `omits ISRC when blank or null`() {
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.opus"),
            outputFile = File("/tmp/out.opus"),
            track = Track(id = 1, title = "T", artist = "A", isrc = null),
            albumArtFile = null,
            appVersion = versionProvider,
        )
        assertFalse(args.zipMetadataValues().any { it.startsWith("ISRC=") })
    }

    @Test fun `writes ENCODER with versionName`() {
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.opus"),
            outputFile = File("/tmp/out.opus"),
            track = Track(id = 1, title = "T", artist = "A"),
            albumArtFile = null,
            appVersion = versionProvider,
        )
        assertTrue("ENCODER=Stash 0.9.35" in args.zipMetadataValues())
    }

    @Test fun `attaches art when albumArtFile is non-null and exists`() {
        val art = File.createTempFile("art", ".jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) }
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.opus"),
            outputFile = File("/tmp/out.opus"),
            track = Track(id = 1, title = "T", artist = "A"),
            albumArtFile = art,
            appVersion = versionProvider,
        )
        assertTrue(args.contains("-disposition:v:0"))
        assertTrue(args.contains("attached_pic"))
        assertTrue(args.contains(art.absolutePath))
        art.delete()
    }

    @Test fun `sanitises control characters from values`() {
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.opus"),
            outputFile = File("/tmp/out.opus"),
            track = Track(id = 1, title = "Hello\u0000World", artist = "Evil\u0001\u001F"),
            albumArtFile = null,
            appVersion = versionProvider,
        )
        assertTrue("title=HelloWorld" in args.zipMetadataValues())
        assertTrue("artist=Evil" in args.zipMetadataValues())
    }

    // Pairs every `-metadata` flag with the value that follows it.
    private fun List<String>.zipMetadataValues(): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < size - 1) {
            if (this[i] == "-metadata") result.add(this[i + 1])
            i++
        }
        return result
    }
}
