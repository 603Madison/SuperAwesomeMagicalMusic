package com.music.tuner.hd

import java.util.Locale
import kotlin.math.log10

/**
 * An HD Radio signal's quality, 0 to 1: digital audio is either there or not, so what matters
 * is how far it is from dropping out. nrsc5 reports the modulation error ratio (MER: below
 * about 3 dB nothing decodes, 12 dB and up is solid), the bit error rate before error
 * correction, and how many audio packets failed their check.
 */
object HdQuality {
    fun score(info: HdInfo): Double? {
        if (!info.synced) return if (info.mer == null && info.ber == null) null else 0.0
        val fromMer = info.mer?.let { ((it - 3.0) / 9.0).coerceIn(0.0, 1.0) }
        // 1 in 10,000 bits wrong or fewer is clean; 1 in 20 is about where audio gives out.
        val fromBer = info.ber?.let { ber -> if (ber <= 1e-4) 1.0 else (1 - log10(ber / 1e-4) / log10(500.0)).coerceIn(0.0, 1.0) }
        val measured = listOfNotNull(fromMer, fromBer)
        val base = if (measured.isEmpty()) 0.6 else measured.average()
        // Failed audio packets are dropouts you hear.
        val fromAudio = 1 - info.audioErrors / 32.0 * 3
        return minOf(base, fromAudio.coerceIn(0.0, 1.0))
    }

    fun detail(info: HdInfo): String {
        if (!info.synced) return if (info.mer == null) "Looking for the digital signal" else "Digital signal lost"
        val parts = mutableListOf("HD Radio")
        info.mer?.let { parts += String.format(Locale.ROOT, "MER %.1f dB", it) }
        info.ber?.let { parts += String.format(Locale.ROOT, "BER %.5f", it) }
        if (info.audioErrors > 0) parts += "${info.audioErrors} of 32 audio packets lost"
        info.bitrateKbps?.let { parts += String.format(Locale.ROOT, "%.0f kbps", it) }
        return parts.joinToString(", ")
    }
}
