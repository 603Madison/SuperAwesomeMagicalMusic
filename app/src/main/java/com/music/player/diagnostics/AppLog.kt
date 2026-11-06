package com.music.player.diagnostics

import android.util.Log
import java.io.File
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * A short log of what the app was asked to do and how it answered (Android Auto's requests in
 * particular), kept on disk so it survives the app being stopped. Copied from Settings for
 * troubleshooting; it holds no passwords or tokens.
 *
 * Lines are appended to the file, which is rewritten with just the latest lines once it has
 * grown to twice that; writing happens off the calling thread (often the main thread).
 */
class AppLog(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val writer: Executor = Executors.newSingleThreadExecutor { Thread(it, "AppLog").apply { isDaemon = true } },
) {
    private val lines = ArrayDeque<String>()

    /** Lines in the file, which is compacted when it reaches twice [MAX_LINES]. */
    private var linesInFile = 0

    init {
        runCatching {
            if (file.exists()) {
                val saved = file.readLines()
                linesInFile = saved.size
                saved.takeLast(MAX_LINES).forEach(lines::addLast)
            }
        }
    }

    @Synchronized
    fun log(tag: String, message: String) {
        val line = "${Instant.ofEpochMilli(clock())} [$tag] ${message.replace('\n', ' ').take(MAX_LINE_LENGTH)}"
        Log.i(TAG, line)
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        linesInFile++
        val rewrite = if (linesInFile >= MAX_LINES * 2) {
            linesInFile = lines.size
            lines.joinToString("\n", postfix = "\n")
        } else {
            null
        }
        writer.execute {
            runCatching {
                if (rewrite != null) file.writeText(rewrite) else file.appendText(line + "\n")
            }
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
