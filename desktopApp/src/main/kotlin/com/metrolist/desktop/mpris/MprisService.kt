package com.metrolist.desktop.mpris

import com.metrolist.desktop.playback.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.freedesktop.dbus.annotations.DBusBoundProperty
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant

/** MPRIS root interface: identity, capabilities, raise/quit. */
@DBusInterfaceName("org.mpris.MediaPlayer2")
interface MprisRoot : DBusInterface {
    @DBusMemberName("Raise")
    fun raise()

    @DBusMemberName("Quit")
    fun quit()

    @DBusBoundProperty
    fun isCanQuit(): Boolean

    @DBusBoundProperty
    fun isCanRaise(): Boolean

    @DBusBoundProperty
    fun isHasTrackList(): Boolean

    @DBusBoundProperty
    fun getIdentity(): String

    @DBusBoundProperty
    fun getSupportedUriSchemes(): List<String>

    @DBusBoundProperty
    fun getSupportedMimeTypes(): List<String>
}

/** MPRIS player interface: transport controls and playback state. */
@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MprisPlayer : DBusInterface {
    @DBusMemberName("Next")
    fun next()

    @DBusMemberName("Pause")
    fun pause()

    @DBusMemberName("Play")
    fun play()

    @DBusMemberName("PlayPause")
    fun playPause()

    @DBusMemberName("Previous")
    fun previous()

    @DBusMemberName("Stop")
    fun stop()

    @DBusBoundProperty
    fun getPlaybackStatus(): String

    @DBusBoundProperty
    fun getMetadata(): Map<String, Variant<*>>

    @DBusBoundProperty
    fun getPosition(): Long

    @DBusBoundProperty
    fun getRate(): Double

    @DBusBoundProperty
    fun getMinimumRate(): Double

    @DBusBoundProperty
    fun getMaximumRate(): Double

    @DBusBoundProperty
    fun getVolume(): Double

    @DBusBoundProperty
    fun isCanGoNext(): Boolean

    @DBusBoundProperty
    fun isCanGoPrevious(): Boolean

    @DBusBoundProperty
    fun isCanPlay(): Boolean

    @DBusBoundProperty
    fun isCanPause(): Boolean

    @DBusBoundProperty
    fun isCanSeek(): Boolean

    @DBusBoundProperty
    fun isCanControl(): Boolean
}

/** One exported object serving both MPRIS interfaces at a single object path. */
private class MprisObject(private val controller: PlayerController) : MprisRoot, MprisPlayer {
    override fun getObjectPath(): String = MprisService.PATH

    // Window management is out of scope for the desktop MVP.
    override fun raise() {}
    override fun quit() {}
    override fun isCanQuit() = false
    override fun isCanRaise() = false
    override fun isHasTrackList() = false
    override fun getIdentity() = "Metrolist"
    override fun getSupportedUriSchemes(): List<String> = emptyList()
    override fun getSupportedMimeTypes(): List<String> = emptyList()

    // Transport: controller methods are no-ops while nothing is loaded.
    override fun play() {
        if (!controller.isPlaying.value) controller.togglePlayPause()
    }

    override fun pause() {
        if (controller.isPlaying.value) controller.togglePlayPause()
    }

    override fun playPause() = controller.togglePlayPause()
    override fun stop() = pause()
    override fun next() = controller.next()
    override fun previous() = controller.previous()

    override fun getPlaybackStatus(): String =
        when {
            controller.isPlaying.value -> "Playing"
            controller.queue.value.isEmpty() -> "Stopped"
            else -> "Paused"
        }

    override fun getMetadata(): Map<String, Variant<*>> {
        val track = controller.queue.value.getOrNull(controller.currentIndex.value) ?: return emptyMap()
        return mapOf(
            // Object paths only allow [A-Za-z0-9_]; video ids contain '-'.
            "mpris:trackid" to Variant(trackId(track.id), "o"),
            "mpris:length" to Variant((track.duration ?: 0) * 1_000_000L),
            "xesam:title" to Variant(track.title),
        )
    }

    // Position tracking arrives with the player UI; clients read 0 until then.
    override fun getPosition() = 0L
    override fun getRate() = 1.0
    override fun getMinimumRate() = 1.0
    override fun getMaximumRate() = 1.0
    override fun getVolume() = 1.0
    override fun isCanGoNext() = controller.currentIndex.value < controller.queue.value.lastIndex
    override fun isCanGoPrevious() = controller.currentIndex.value > 0
    override fun isCanPlay() = true
    override fun isCanPause() = true
    override fun isCanSeek() = false
    override fun isCanControl() = true

    private fun trackId(videoId: String): String =
        "/org/mpris/MediaPlayer2/Track/" + videoId.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
}

/**
 * Publishes the player on the session bus as `org.mpris.MediaPlayer2.Metrolist`
 * so desktop shells, media keys and playerctl can control playback. State
 * mirrors [PlayerController]; changes are pushed as PropertiesChanged signals.
 */
class MprisService internal constructor(
    private val conn: DBusConnection,
    private val controller: PlayerController,
) : AutoCloseable {
    private val obj = MprisObject(controller)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        conn.exportObject(PATH, obj)
        scope.launch {
            combine(controller.isPlaying, controller.queue, controller.currentIndex) { playing, queue, index ->
                Triple(playing, queue.getOrNull(index)?.id, index to queue.size)
            }.distinctUntilChanged().collect { emitState() }
        }
    }

    /** Push current playback state to MPRIS clients. */
    private fun emitState() {
        runCatching {
            val changed =
                mapOf(
                    "PlaybackStatus" to Variant(obj.getPlaybackStatus()),
                    "Metadata" to Variant(obj.getMetadata(), "a{sv}"),
                    "CanGoNext" to Variant(obj.isCanGoNext()),
                    "CanGoPrevious" to Variant(obj.isCanGoPrevious()),
                )
            conn.sendMessage(Properties.PropertiesChanged(PATH, PLAYER_IFACE, changed, emptyList()))
        }.onFailure { System.err.println("MPRIS PropertiesChanged failed: $it") }
    }

    override fun close() {
        scope.cancel()
        runCatching { conn.close() }
    }

    companion object {
        const val PATH = "/org/mpris/MediaPlayer2"
        const val BUS_NAME = "org.mpris.MediaPlayer2.Metrolist"
        private const val PLAYER_IFACE = "org.mpris.MediaPlayer2.Player"

        /**
         * Connect to the session bus and export the player. Returns null
         * (logged) when no bus exists — headless CI runs must not crash.
         */
        fun start(controller: PlayerController): MprisService? =
            runCatching {
                val conn = DBusConnectionBuilder.forSessionBus().build()
                conn.requestBusName(BUS_NAME)
                MprisService(conn, controller)
            }.onFailure {
                System.err.println("MPRIS unavailable: $it")
            }.getOrNull()
    }
}
