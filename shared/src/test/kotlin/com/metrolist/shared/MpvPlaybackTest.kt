package com.metrolist.shared

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeLocale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Phase 3a decode spike: resolve a real audio stream and play five seconds of
 * it through mpv with null audio/video outputs — the full network → demux →
 * decode path without needing a display or sound card on the CI runner.
 *
 * The workflow installs mpv via apt before the shared test step.
 */
class MpvPlaybackTest {
    @Test
    fun mpvDecodesFiveSecondsOfStream() =
        runBlocking {
            requireMpv()

            YouTube.locale = YouTubeLocale(gl = "US", hl = "en")
            val stream = StreamResolver.resolve(VIDEO_ID)
            println(
                "DIAG mpv spike client=${stream.clientName} itag=${stream.itag} mime=${stream.mimeType} " +
                    "headers=${stream.headers}",
            )

            val headerFields = stream.headers.entries.joinToString(",") { "${it.key}: ${it.value}" }
            val command =
                buildList {
                    add("mpv")
                    add("--no-config") // ignore runner/user config
                    add("--ao=null") // decode audio into a null sink (no sound card)
                    add("--vo=null") // no display on the runner
                    add("--length=5") // five seconds proves the decode path
                    if (headerFields.isNotEmpty()) add("--http-header-fields=$headerFields")
                    add(stream.url)
                }
            println("DIAG mpv cmd=${command.dropLast(1).joinToString(" ")} <stream-url>")

            val log = File.createTempFile("mpv-spike", ".log")
            try {
                val process =
                    ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(log)
                        .start()
                val finished = process.waitFor(90, TimeUnit.SECONDS)
                val output = log.readText()
                output.lineSequence().forEach { println("DIAG mpv| $it") }

                if (!finished) {
                    process.destroyForcibly()
                    fail("mpv did not exit within 90s\n$output")
                }
                assertEquals("mpv should decode 5s and exit cleanly\n$output", 0, process.exitValue())
            } finally {
                log.delete()
            }
        }

    private fun requireMpv() {
        val present =
            try {
                ProcessBuilder("mpv", "--version").start().waitFor() == 0
            } catch (_: IOException) {
                false
            }
        assertTrue("mpv missing from PATH — the workflow installs it with apt", present)
    }

    private companion object {
        // Same stable video as StreamResolutionTest (also innertubex's prewarm).
        const val VIDEO_ID = "dQw4w9WgXcQ"
    }
}
