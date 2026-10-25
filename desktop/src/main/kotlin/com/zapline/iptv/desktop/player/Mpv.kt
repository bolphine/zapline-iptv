package com.zapline.iptv.desktop.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File

/**
 * The parts of libmpv's C API Zapline uses (https://github.com/mpv-player/mpv/blob/master/include/mpv/client.h).
 * On Windows the library is libmpv-2.dll, shipped next to Zapline.exe.
 */
@Suppress("FunctionName")
interface MpvLib : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_free(data: Pointer)
    /** args must end with null. */
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_command_string(ctx: Pointer, args: String): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(ctx: Pointer)
    fun mpv_error_string(error: Int): String
    fun mpv_request_log_messages(ctx: Pointer, minLevel: String): Int
    fun mpv_client_api_version(): Long

    companion object {
        const val EVENT_NONE = 0
        const val EVENT_SHUTDOWN = 1
        const val EVENT_LOG_MESSAGE = 2
        const val EVENT_START_FILE = 6
        const val EVENT_END_FILE = 7
        const val EVENT_FILE_LOADED = 8
        const val EVENT_CLIENT_MESSAGE = 16
        const val EVENT_VIDEO_RECONFIG = 17
        const val EVENT_PLAYBACK_RESTART = 21

        const val END_EOF = 0
        const val END_STOP = 2
        const val END_ERROR = 4
    }
}

object Mpv {
    /** Why libmpv couldn't be loaded, if it couldn't. */
    @Volatile var loadError: String? = null; private set

    val lib: MpvLib? by lazy {
        // Zapline.exe ships libmpv-2.dll in its resources folder; also look next to the app and on PATH.
        System.getProperty("compose.application.resources.dir")?.let { dir ->
            val old = System.getProperty("jna.library.path")
            System.setProperty("jna.library.path", listOfNotNull(dir, old).joinToString(File.pathSeparator))
        }
        val names = listOf("libmpv-2", "mpv-2", "mpv", "libmpv")
        var last: Throwable? = null
        for (n in names) {
            try {
                return@lazy Native.load(n, MpvLib::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
            } catch (t: Throwable) { last = t }
        }
        loadError = last?.message ?: "libmpv not found"
        null
    }

    val available: Boolean get() = lib != null
}
