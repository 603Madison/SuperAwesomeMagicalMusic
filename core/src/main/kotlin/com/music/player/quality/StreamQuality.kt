package com.music.player.quality

/** What the user asked for: the untouched original file, or a bitrate ceiling. */
sealed interface StreamQuality {
    data object Original : StreamQuality

    data class Capped(val kbps: Int) : StreamQuality {
        init {
            require(kbps in MIN_KBPS..MAX_KBPS) { "Bitrate $kbps kbps is outside $MIN_KBPS-$MAX_KBPS" }
        }
    }

    val label: String
        get() = when (this) {
            Original -> "Original quality"
            is Capped -> "$kbps kbps"
        }

    /** Whether this allows a higher bitrate than [other]. */
    fun isHigherThan(other: StreamQuality): Boolean = when {
        this == other -> false
        this == Original -> true
        other == Original -> false
        else -> (this as Capped).kbps > (other as Capped).kbps
    }

    /** Stored as an int in settings: 0 means original, anything else is the kbps cap. */
    fun toStored(): Int = when (this) {
        Original -> 0
        is Capped -> kbps
    }

    companion object {
        const val MIN_KBPS = 64
        const val MAX_KBPS = 320

        /** Standard MP3 bitrates between 64 and 320 kbps; these are the slider stops in settings. */
        val STEPS = listOf(64, 80, 96, 112, 128, 160, 192, 224, 256, 320)

        fun fromStored(value: Int): StreamQuality =
            if (value <= 0) Original else Capped(STEPS.lastOrNull { it <= value } ?: MIN_KBPS)
    }
}

enum class NetworkClass(val label: String) {
    WIFI("Wi-Fi"),
    CELLULAR("Cellular"),
    SATELLITE("Satellite"),
    NONE("Offline"),
}

/** One quality level a source can deliver, and whether it respects the requested cap. */
data class TierChoice<T>(val tier: T, val withinCap: Boolean)

object TierSelector {
    /**
     * Picks the tier to request from a source that only offers fixed quality levels.
     *
     * Original takes the best tier. A bitrate cap rounds *down* to the highest tier at or below
     * it. If every tier is above the cap (e.g. Qobuz, whose lowest tier is MP3 320), the lowest
     * tier is returned with [TierChoice.withinCap] = false so callers can prefer another source.
     */
    fun <T> choose(tiers: List<T>, quality: StreamQuality, kbpsOf: (T) -> Int): TierChoice<T>? {
        if (tiers.isEmpty()) return null
        val sorted = tiers.sortedBy(kbpsOf)
        return when (quality) {
            StreamQuality.Original -> TierChoice(sorted.last(), withinCap = true)
            is StreamQuality.Capped -> sorted.lastOrNull { kbpsOf(it) <= quality.kbps }
                ?.let { TierChoice(it, withinCap = true) }
                ?: TierChoice(sorted.first(), withinCap = false)
        }
    }
}
