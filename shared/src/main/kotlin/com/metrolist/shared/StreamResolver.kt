package com.metrolist.shared

import com.metrolist.innertube.YouTube
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Desktop port of the Android app's `InnerTubeXPlayer`: builds the innertubex
 * extraction bundle once and resolves playable audio stream URLs.
 *
 * Differences from the Android implementation:
 *  - no WebView-based PoToken provider (tokenProvider = null; innertubex falls
 *    back to clients that do not require one)
 *  - no connectivity-aware quality selection (AUTO)
 *  - player-config cache is in-memory instead of SharedPreferences
 */
object StreamResolver {
    private const val PLAYER_CONFIG_URL =
        "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"

    data class ResolvedStream(
        val videoId: String,
        val url: String,
        val headers: Map<String, String>,
        val mimeType: String?,
        val codecs: String?,
        val bitrate: Int?,
        val itag: Int?,
        val clientName: String,
        val expiresAtEpochMs: Long?,
        val durationSeconds: Long?,
        val title: String?,
        val author: String?,
    )

    suspend fun resolve(
        videoId: String,
        audioQuality: AudioQuality = AudioQuality.AUTO,
        contentHints: ContentHints =
            ContentHints().withStreamCapabilities(
                allowHls = false,
                allowSabr = false,
                allowBoundedRange = true,
            ),
    ): ResolvedStream {
        val stream =
            bundle().extractor.extract(
                videoId = videoId,
                hints = contentHints,
                excludedClients = emptySet(),
                audioQuality = audioQuality,
                clientPlaybackNonce = generateClientPlaybackNonce(),
            ) ?: error("InnerTubeX returned no playable stream for $videoId")
        val metadata = stream.mediaMetadata
        return ResolvedStream(
            videoId = videoId,
            url = stream.audioUrl,
            headers = stream.headers,
            mimeType = stream.mimeType,
            codecs = stream.codecs,
            bitrate = stream.bitrate,
            itag = stream.itag,
            clientName = stream.clientName,
            expiresAtEpochMs = stream.expiresAt?.toEpochMilliseconds(),
            durationSeconds = metadata?.durationSeconds,
            title = metadata?.title,
            author = metadata?.author,
        )
    }

    private data class Bundle(
        val generation: Long,
        val extractor: InnerTubeExtractor,
    )

    private var cachedBundle: Bundle? = null
    private val bundleMutex = Mutex()

    private suspend fun bundle(): Bundle {
        val transport = YouTube.extractionTransport()
        cachedBundle?.takeIf { it.generation == transport.generation }?.let { return it }
        return bundleMutex.withLock {
            val latest = YouTube.extractionTransport()
            cachedBundle?.takeIf { it.generation == latest.generation }?.let { return@withLock it }
            val remoteStore = RemotePlayerConfigStore(latest.httpClient, playerConfigRepository, logger)
            val cipherService = YouTubeCipherService(latest.httpClient, remoteStore, logger)
            val extractor =
                InnerTubeExtractor(
                    configParser =
                        YtConfigParserImpl(latest.httpClient, latest.innerTube, remoteStore, logger)
                            .withEmbeddedConfigFallback(),
                    cipherService = cipherService,
                    innerTube = latest.innerTube,
                    tokenProvider = null,
                    logger = logger,
                )
            Bundle(latest.generation, extractor).also { cachedBundle = it }
        }
    }

    private val playerConfigRepository =
        object : PlayerConfigRepository {
            override val enabled: Boolean = true
            override val sourceUrl: String = PLAYER_CONFIG_URL
            override val defaultSourceUrl: String = PLAYER_CONFIG_URL
            override var cachedJson: String = ""
            override var cachedAtMs: Long = 0L
            override var cachedSourceUrl: String = ""
            override var cachedEtag: String = ""
        }

    private val logger =
        InnerTubeLogger { event ->
            if (event.level == InnerTubeLogLevel.WARN || event.level == InnerTubeLogLevel.ERROR) {
                println("innertubex/${event.level} ${event.tag}: ${event.message} ${event.details}")
            }
        }

    private fun YtConfigParser.withEmbeddedConfigFallback(): YtConfigParser =
        object : YtConfigParser by this {
            override suspend fun fetchConfig(
                videoId: String,
                useLoginCookies: Boolean,
            ) =
                try {
                    this@withEmbeddedConfigFallback.fetchConfig(videoId, useLoginCookies)
                } catch (_: IllegalStateException) {
                    this@withEmbeddedConfigFallback.fetchEmbeddedConfig(videoId, useLoginCookies = false)
                }
        }
}
