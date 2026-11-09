package com.metrolist.shared

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeLocale
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resolves a real audio stream URL through innertubex and then fetches the
 * first bytes of it — the exact data path mpv will use in Phase 3. Runs in CI.
 */
class StreamResolutionTest {
    @Test
    fun resolvesPlayableAudioStream() =
        runBlocking {
            YouTube.locale = YouTubeLocale(gl = "US", hl = "en")

            val stream = StreamResolver.resolve(VIDEO_ID)
            println(
                "DIAG stream client=${stream.clientName} itag=${stream.itag} mime=${stream.mimeType} " +
                    "codecs=${stream.codecs} bitrate=${stream.bitrate} duration=${stream.durationSeconds}s " +
                    "title=${stream.title}",
            )
            println("DIAG url=${stream.url.take(180)}")
            assertTrue("stream URL must be https", stream.url.startsWith("https://"))

            val client = HttpClient(OkHttp)
            try {
                val resp =
                    client.get(stream.url) {
                        stream.headers.forEach { (key, value) -> header(key, value) }
                        header(HttpHeaders.Range, "bytes=0-2047")
                    }
                val total = resp.headers[HttpHeaders.ContentRange] ?: resp.headers[HttpHeaders.ContentLength]
                println("DIAG fetch status=${resp.status} lengthHint=$total")
                assertTrue(
                    "stream fetch expected 200/206, got ${resp.status.value}",
                    resp.status.value in 200..206,
                )
                assertTrue("expected a content length/range header", total != null)
            } finally {
                client.close()
            }
        }

    private companion object {
        // Rick Astley — stable, also innertubex's own prewarm video.
        const val VIDEO_ID = "dQw4w9WgXcQ"
    }
}
