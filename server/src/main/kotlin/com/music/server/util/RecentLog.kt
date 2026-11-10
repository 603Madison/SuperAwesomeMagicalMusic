package com.music.server.util

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The server's latest warnings and errors, kept in memory (logback.xml attaches it), so they can
 * be read without the container's log: the Claude connector's recent_warnings tool shows them.
 */
class RecentLog : AppenderBase<ILoggingEvent>() {
    override fun append(event: ILoggingEvent) {
        if (!event.level.isGreaterOrEqual(Level.WARN)) return
        val cause = event.throwableProxy?.let { " (${it.className}: ${it.message})" }.orEmpty()
        val line = "${TIME.format(Instant.ofEpochMilli(event.timeStamp))} ${event.level} ${event.loggerName.substringAfterLast('.')}: ${event.formattedMessage}$cause"
        synchronized(lines) {
            lines.addLast(line.take(2_000))
            while (lines.size > MAX) lines.removeFirst()
        }
    }

    companion object {
        private const val MAX = 300
        private val lines = ArrayDeque<String>()
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)

        /** The latest lines, oldest first. */
        fun latest(): List<String> = synchronized(lines) { lines.toList() }
    }
}
