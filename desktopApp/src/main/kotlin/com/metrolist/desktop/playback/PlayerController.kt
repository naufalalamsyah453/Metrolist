package com.metrolist.desktop.playback

import com.metrolist.innertube.models.SongItem
import com.metrolist.shared.StreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Queue-driven playback on top of [MpvPlayer]: mpv holds one track at a time,
 * the queue lives here. A natural end-of-file advances the queue; STOP/QUIT
 * END_FILEs come from our own track switches and are ignored.
 */
class PlayerController(
    private val playerOptions: Map<String, String> = mapOf("vo" to "null"),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val queue = MutableStateFlow<List<SongItem>>(emptyList())
    val currentIndex = MutableStateFlow(-1)
    val isPlaying = MutableStateFlow(false)

    private var player: MpvPlayer? = null
    private var resolveJob: Job? = null

    fun play(queue: List<SongItem>, index: Int) {
        this.queue.value = queue
        playAt(index)
    }

    fun togglePlayPause() {
        val p = player ?: return
        val wasPlaying = isPlaying.value
        p.setPause(wasPlaying)
        isPlaying.value = !wasPlaying
    }

    fun next() = playAt(currentIndex.value + 1)

    fun previous() = playAt(currentIndex.value - 1)

    fun close() {
        resolveJob?.cancel()
        scope.cancel()
        player?.close()
        player = null
    }

    private fun playAt(index: Int) {
        val track = queue.value.getOrNull(index)
        if (track == null) {
            isPlaying.value = false
            return
        }
        currentIndex.value = index
        isPlaying.value = false
        resolveJob?.cancel()
        resolveJob =
            scope.launch {
                try {
                    val stream = StreamResolver.resolve(track.id)
                    currentPlayer().load(stream.url)
                    isPlaying.value = true
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    isPlaying.value = false
                    System.err.println("playback failed for ${track.id}: $t")
                }
            }
    }

    private fun currentPlayer(): MpvPlayer =
        player ?: run {
            MpvPlayer.create(playerOptions).also { created ->
                created.onEvent = { event -> onMpvEvent(created, event) }
                player = created
            }
        }

    private fun onMpvEvent(
        source: MpvPlayer,
        event: MpvEvent,
    ) {
        val (reason, error) = source.endFileOf(event) ?: return
        when (reason) {
            MpvLibrary.END_FILE_REASON_EOF -> {
                isPlaying.value = false
                next()
            }
            MpvLibrary.END_FILE_REASON_ERROR -> {
                isPlaying.value = false
                System.err.println("mpv ended with error $error: ${source.errorString(error)}")
            }
        }
    }
}
