package com.music.mini

import android.content.Context
import com.music.mini.net.User

/** Mini's few settings and its sign-in, kept on the phone (never backed up). */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("mini", Context.MODE_PRIVATE)

    /** The server's address, like https://music.example.com; empty until signed in. */
    val server: String get() = prefs.getString(SERVER, null).orEmpty()
    val token: String? get() = prefs.getString(TOKEN, null)
    val signedIn: Boolean get() = token != null && server.isNotEmpty()
    val serverName: String get() = prefs.getString(SERVER_NAME, null) ?: "SuperAwesomeMagicalMusic"
    val userName: String get() = prefs.getString(USER_NAME, null).orEmpty()

    /** The address typed last time, to start from when signing in again. */
    val lastServer: String get() = prefs.getString(LAST_SERVER, null).orEmpty()

    /** The bitrate cap for streams (0 for the original quality): low by default, to save data. */
    var kbps: Int
        get() = prefs.getInt(KBPS, DEFAULT_KBPS)
        set(value) = prefs.edit().putInt(KBPS, value).apply()

    fun rememberServer(server: String) = prefs.edit().putString(LAST_SERVER, server).apply()

    fun signIn(server: String, token: String, user: User, serverName: String) {
        prefs.edit()
            .putString(SERVER, server)
            .putString(LAST_SERVER, server)
            .putString(TOKEN, token)
            .putString(USER_NAME, user.displayName.ifBlank { user.username })
            .putString(SERVER_NAME, serverName)
            .apply()
    }

    fun signOut() {
        prefs.edit().remove(TOKEN).remove(SERVER).remove(USER_NAME).apply()
    }

    companion object {
        private const val SERVER = "server"
        private const val TOKEN = "token"
        private const val SERVER_NAME = "server_name"
        private const val USER_NAME = "user_name"
        private const val LAST_SERVER = "last_server"
        private const val KBPS = "kbps"
        const val DEFAULT_KBPS = 96

        /** The choices offered, with what they're good for. */
        val QUALITIES = listOf(
            64 to "64 kbps: least data",
            96 to "96 kbps: data saver",
            128 to "128 kbps",
            192 to "192 kbps",
            320 to "320 kbps: high",
            0 to "Original quality",
        )

        fun qualityLabel(kbps: Int): String = QUALITIES.firstOrNull { it.first == kbps }?.second ?: if (kbps == 0) "Original quality" else "$kbps kbps"
    }
}
