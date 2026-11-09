package com.metrolist.desktop.playback

import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File

/** Snapshot of one mpv_event; see [MpvLibrary] for struct layouts. */
data class MpvEvent(val id: Int, val error: Int, val data: Pointer?)

/**
 * One libmpv instance with a background event loop. Queue and track metadata
 * stay in the controller — mpv only ever plays a single file at a time.
 */
class MpvPlayer private constructor(
    private val lib: MpvLibrary,
    private val handle: Pointer,
) : AutoCloseable {
    @Volatile
    var onEvent: ((MpvEvent) -> Unit)? = null

    private var closed = false
    private val eventThread =
        Thread(
            {
                while (!closed) {
                    val ptr = lib.mpv_wait_event(handle, 0.5) ?: continue
                    val id = ptr.getInt(0L)
                    if (id == MpvLibrary.EVENT_NONE) continue
                    onEvent?.invoke(MpvEvent(id, ptr.getInt(4L), ptr.getPointer(16L)))
                }
            },
            "mpv-event-loop",
        ).apply {
            isDaemon = true
            start()
        }

    // shortcut: command_string splits on spaces; YouTube URLs are percent-encoded
    fun load(url: String) = command("loadfile $url replace")

    fun setPause(pause: Boolean) = command("set pause ${if (pause) "yes" else "no"}")

    fun command(command: String) {
        val err = lib.mpv_command_string(handle, command)
        if (err < 0) error("mpv command failed ($command): ${lib.mpv_error_string(err)}")
    }

    fun errorString(code: Int): String = lib.mpv_error_string(code) ?: "unknown"

    /** reason/error pair for an EVENT_END_FILE, null for any other event. */
    fun endFileOf(event: MpvEvent): Pair<Int, Int>? =
        event.data
            ?.takeIf { event.id == MpvLibrary.EVENT_END_FILE }
            ?.let { it.getInt(0L) to it.getInt(4L) }

    override fun close() {
        if (closed) return
        closed = true
        // quit wakes the event loop so join() finishes before destroy races it
        runCatching { command("quit") }
        eventThread.join(3_000)
        lib.mpv_terminate_destroy(handle)
    }

    companion object {
        private val LIBRARY_CANDIDATES =
            listOf(
                "/usr/lib/x86_64-linux-gnu/libmpv.so.2",
                "/usr/lib/libmpv.so.2",
                "/lib/x86_64-linux-gnu/libmpv.so.2",
            )

        /**
         * @param options mpv options set before mpv_initialize (ao/vo for
         *   headless runs); the AppImage passes -Dmetrolist.mpv.library.
         */
        fun create(
            options: Map<String, String> = emptyMap(),
            libraryPath: String? = null,
        ): MpvPlayer {
            val path =
                libraryPath
                    ?: System.getProperty("metrolist.mpv.library")
                    ?: LIBRARY_CANDIDATES.firstOrNull { File(it).exists() }
                    ?: error(
                        "libmpv.so.2 not found (tried $LIBRARY_CANDIDATES); " +
                            "set -Dmetrolist.mpv.library=<path>",
                    )
            val lib = Native.load(path, MpvLibrary::class.java)
            val handle = lib.mpv_create() ?: error("mpv_create returned NULL")
            try {
                options.forEach { (name, value) ->
                    val err = lib.mpv_set_option_string(handle, name, value)
                    if (err < 0) error("mpv option $name failed: ${lib.mpv_error_string(err)}")
                }
                val err = lib.mpv_initialize(handle)
                if (err < 0) error("mpv_initialize failed: ${lib.mpv_error_string(err)}")
            } catch (t: Throwable) {
                lib.mpv_terminate_destroy(handle)
                throw t
            }
            return MpvPlayer(lib, handle)
        }
    }
}
