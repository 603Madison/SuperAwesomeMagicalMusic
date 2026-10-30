package com.music.player.ui.components

/** "30 s", "5 min", "1 h 30 min". */
fun formatBufferLength(seconds: Int): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> if (seconds % 60 == 0) "${seconds / 60} min" else "${seconds / 60} min ${seconds % 60} s"
    seconds % 3600 == 0 -> "${seconds / 3600} h"
    else -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
}

/** "3:07" or "1:02:45". */
fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    return if (hours > 0) "%d:%02d:%02d".format(hours, totalSeconds % 3600 / 60, totalSeconds % 60)
    else "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** "4.2 MB". */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
