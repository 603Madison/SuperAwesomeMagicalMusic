package com.music.player.diagnostics

import android.util.Log
import java.io.File
import java.time.Instant

/**
 * A short log of what the app was asked to do and how it answered (Android Auto's requests in
 * particular), kept on disk so it survives the app being stopped. Copied from Settings for
 * troubleshooting; it holds no passwords or tokens.
 */
class AppLog(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val lines = ArrayDeque<String>()

    init {
        runCatching { if (file.exists()) file.readLines().takeLast(MAX_LINES).forEach(lines::addLast) }
    }

    @Synchronized
    fun log(tag: String, message: String) {
        val line = "${Instant.ofEpochMilli(clock())} [$tag] ${message.replace('\n', ' ').take(MAX_LINE_LENGTH)}"
        Log.i(TAG, line)
        lines.addLast(line)
        val trimmed = lines.size > MAX_LINES
        while (lines.size > MAX_LINES) lines.removeFirst()
        runCatching {
            // Rewriting a few hundred short lines is cheap; appending keeps most calls cheaper.
            if (trimmed && lines.size % 50 == 0) file.writeText(lines.joinToString("\n", postfix = "\n"))
            else file.appendText(line + "\n")
        }
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    private companion object {
        const val TAG = "AppLog"
        const val MAX_LINES = 400
        const val MAX_LINE_LENGTH = 600
    }
}
