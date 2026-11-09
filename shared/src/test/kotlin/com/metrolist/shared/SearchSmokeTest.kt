package com.metrolist.shared

import com.metrolist.innertube.YouTube
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end check that the InnerTube facade works on a plain JVM: real
 * network, real YouTube Music search. Runs on the GitHub Actions runner, so
 * it also proves the runner IP is not blocked by YouTube.
 */
class SearchSmokeTest {
    @Test
    fun searchReturnsSongResults() =
        runBlocking {
            val result = YouTube.search("daft punk", YouTube.SearchFilter.FILTER_SONG)
            val search =
                result.getOrElse {
                    throw AssertionError("YouTube Music search failed", it)
                }
            assertTrue(
                "expected at least one song, got ${search.items.size}",
                search.items.isNotEmpty(),
            )
            println("smoke: got ${search.items.size} items, first=${search.items.first()}")
        }
}
