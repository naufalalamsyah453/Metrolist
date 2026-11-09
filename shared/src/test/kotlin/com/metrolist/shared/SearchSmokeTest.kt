package com.metrolist.shared

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeLocale
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * End-to-end check that the InnerTube facade works on a plain JVM: real
 * network, real YouTube Music search. Runs on the GitHub Actions runner, so
 * it also proves the runner IP is not blocked by YouTube.
 *
 * The runner's default locale can have an empty country, which turns into an
 * empty `gl` in the InnerTube context (HTTP 400), so the test pins an
 * explicit locale the way a desktop user's settings will. On failure it dumps
 * YouTube's raw error body via a hand-rolled probe for diagnosis.
 */
class SearchSmokeTest {
    @Test
    fun searchReturnsSongResults() =
        runBlocking {
            val systemLocale = Locale.getDefault()
            println(
                "DIAG system locale=$systemLocale country='${systemLocale.country}' tag='${systemLocale.toLanguageTag()}'",
            )

            YouTube.locale = YouTubeLocale(gl = "US", hl = "en")

            val result = YouTube.search("daft punk", YouTube.SearchFilter.FILTER_SONG)
            result.fold(
                onSuccess = { search ->
                    assertTrue(
                        "expected at least one song, got ${search.items.size}",
                        search.items.isNotEmpty(),
                    )
                    println("smoke: got ${search.items.size} items, first=${search.items.first()}")
                },
                onFailure = { e ->
                    println("DIAG search failed: $e")
                    rawSearchProbe()
                    throw AssertionError("YouTube Music search failed", e)
                },
            )
        }

    /** Minimal hand-built search request that prints YouTube's error body verbatim. */
    private suspend fun rawSearchProbe() {
        val client = HttpClient(OkHttp)
        try {
            val body =
                buildJsonObject {
                        put("context", buildJsonObject {
                            put("client", buildJsonObject {
                                put("clientName", "WEB_REMIX")
                                put("clientVersion", YouTubeClient.WEB_REMIX.clientVersion)
                                put("hl", "en")
                                put("gl", "US")
                            })
                        })
                        put("query", "daft punk")
                    }
                    .toString()
            val resp =
                client.post("https://music.youtube.com/youtubei/v1/search?prettyPrint=false") {
                    contentType(ContentType.Application.Json)
                    header("User-Agent", YouTubeClient.WEB_REMIX.userAgent)
                    header("X-YouTube-Client-Version", YouTubeClient.WEB_REMIX.clientVersion)
                    header("X-Goog-Api-Format-Version", "1")
                    setBody(body)
                }
            println("DIAG probe status=${resp.status}")
            println("DIAG probe body=${resp.bodyAsText().take(1000)}")
        } catch (t: Throwable) {
            println("DIAG probe threw: $t")
        } finally {
            client.close()
        }
    }
}
