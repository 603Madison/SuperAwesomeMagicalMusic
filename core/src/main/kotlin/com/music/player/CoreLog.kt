package com.music.player

/**
 * Logging for shared code, which runs both on Android and on the server: each app points
 * [sink] at its own log (Android's Log, the server's logger). By default it prints.
 */
object CoreLog {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    @Volatile
    var sink: (level: Level, tag: String, message: String, error: Throwable?) -> Unit = { level, tag, message, error ->
        System.err.println("$level $tag: $message" + (error?.let { " ($it)" } ?: ""))
    }

    fun d(tag: String, message: String, error: Throwable? = null) = sink(Level.DEBUG, tag, message, error)
    fun i(tag: String, message: String, error: Throwable? = null) = sink(Level.INFO, tag, message, error)
    fun w(tag: String, message: String, error: Throwable? = null) = sink(Level.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = sink(Level.ERROR, tag, message, error)
}

/** Who's talking to services: set by each app at startup (for user agents and similar). */
object AppInfo {
    @Volatile
    var name: String = "SuperAwesomeMagicalMusic"

    @Volatile
    var version: String = "dev"

    /** e.g. "Android" or "Server". */
    @Volatile
    var platform: String = "Android"

    /** A name for this device, as some services show it (Jellyfin's dashboard). */
    @Volatile
    var deviceName: String = "Device"
}
