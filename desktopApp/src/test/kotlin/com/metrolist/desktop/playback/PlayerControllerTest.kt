package com.metrolist.desktop.playback

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YouTubeLocale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * End-to-end controller test on CI's libmpv: real stream resolution, a 3s
 * track, pause/resume and automatic queue advance on end-of-file.
 */
class PlayerControllerTest {
    @Test
    fun playsQueueAdvancesAndToggles() =
        runBlocking {
            YouTube.locale = YouTubeLocale(gl = "US", hl = "en")

            val controller =
                PlayerController(
                    playerOptions = mapOf("ao" to "null", "vo" to "null", "length" to "3"),
                )
            try {
                controller.play(
                    listOf(
                        SongItem(id = "dQw4w9WgXcQ", title = "Never Gonna Give You Up", artists = emptyList(), thumbnail = ""),
                        SongItem(id = "9bZkp7q19f0", title = "Gangnam Style", artists = emptyList(), thumbnail = ""),
                    ),
                    0,
                )

                withTimeout(60_000) { controller.isPlaying.first { it } }
                controller.togglePlayPause()
                withTimeout(10_000) { controller.isPlaying.first { !it } }
                controller.togglePlayPause()

                // First track hits `length=3` → EOF → controller advances.
                withTimeout(90_000) { controller.currentIndex.first { it == 1 } }
                withTimeout(90_000) { controller.isPlaying.first { it } }
                assertEquals(1, controller.currentIndex.value)
            } finally {
                controller.close()
            }
        }
}
