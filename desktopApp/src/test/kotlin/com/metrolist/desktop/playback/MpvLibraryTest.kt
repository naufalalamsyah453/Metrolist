package com.metrolist.desktop.playback

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.shared.StreamResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives libmpv through JNA in CI: resolve a real stream, play five seconds
 * headless (null ao/vo) and require a clean end-of-file event.
 */
class MpvLibraryTest {
    @Test
    fun libmpvPlaysResolvedStream() =
        runBlocking {
            YouTube.locale = YouTubeLocale(gl = "US", hl = "en")
            val stream = StreamResolver.resolve(VIDEO_ID)
            println("DIAG libmpv client=${stream.clientName} itag=${stream.itag} mime=${stream.mimeType}")

            val player =
                MpvPlayer.create(options = mapOf("ao" to "null", "vo" to "null", "length" to "5"))
            try {
                val ended = CountDownLatch(1)
                val result = AtomicReference<Pair<Int, Int>?>()
                player.onEvent = { event ->
                    player.endFileOf(event)?.let {
                        result.set(it)
                        ended.countDown()
                    }
                }
                player.load(stream.url)

                assertTrue("expected MPV_EVENT_END_FILE within 60s", ended.await(60, TimeUnit.SECONDS))
                val (reason, mpvError) = requireNotNull(result.get())
                println("DIAG libmpv end_file reason=$reason error=$mpvError (${player.errorString(mpvError)})")
                assertTrue(
                    "playback aborted with mpv error $mpvError: ${player.errorString(mpvError)}",
                    reason != MpvLibrary.END_FILE_REASON_ERROR,
                )
            } finally {
                player.close()
            }
        }

    private companion object {
        const val VIDEO_ID = "dQw4w9WgXcQ"
    }
}
