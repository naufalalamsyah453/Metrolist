package com.metrolist.desktop.playback

import com.sun.jna.Library
import com.sun.jna.Pointer

/**
 * Subset of mpv/client.h used by the desktop player. Handles are opaque
 * `mpv_handle*` pointers; struct offsets used by [MpvPlayer]:
 *  - mpv_event: event_id@0, error@4, reply_userdata@8, data@16
 *  - mpv_event_end_file: reason@0, error@4
 */
interface MpvLibrary : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(handle: Pointer): Int
    fun mpv_set_option_string(handle: Pointer, name: String, value: String): Int
    fun mpv_command_string(handle: Pointer, command: String): Int
    fun mpv_set_property_string(handle: Pointer, name: String, value: String): Int
    fun mpv_wait_event(handle: Pointer, timeoutSeconds: Double): Pointer?
    fun mpv_error_string(error: Int): String?
    fun mpv_terminate_destroy(handle: Pointer)

    companion object {
        const val EVENT_NONE = 0
        const val EVENT_END_FILE = 7
        const val END_FILE_REASON_ERROR = 4
    }
}
