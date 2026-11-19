package com.music.tuner.scan

import com.music.tuner.Region
import kotlin.math.abs

/** One bin of a sweep: its frequency (Hz) and power (dB). */
data class PowerBin(val hz: Double, val db: Double)

/** A frequency that stands out of the noise: a station, maybe. */
data class Carrier(val khz: Int, val db: Double, val aboveFloorDb: Double)

/**
 * rtl_power's CSV (rtl-sdr's rtl_power): each line is one hop of the sweep,
 * `date, time, Hz low, Hz high, Hz step, samples, dB, dB, …`.
 */
object RtlPower {
    fun parse(lines: Sequence<String>): List<PowerBin> {
        // Hops overlap and repeat across sweeps: average what lands in the same place.
        val sums = HashMap<Long, DoubleArray>()
        for (line in lines) {
            val fields = line.split(',').map { it.trim() }
            if (fields.size < 7) continue
            val low = fields[2].toDoubleOrNull() ?: continue
            val step = fields[4].toDoubleOrNull()?.takeIf { it > 0 } ?: continue
            for (i in 6 until fields.size) {
                val db = fields[i].toDoubleOrNull()?.takeIf { it.isFinite() } ?: continue
                val hz = low + (i - 6) * step
                val key = Math.round(hz)
                val sum = sums.getOrPut(key) { DoubleArray(2) }
                sum[0] += db
                sum[1] += 1.0
            }
        }
        return sums.entries.map { PowerBin(it.key.toDouble(), it.value[0] / it.value[1]) }.sortedBy { it.hz }
    }

    /**
     * The channels of [region] whose power is at least [marginDb] over the noise floor (the
     * quietest fifth of the band), strongest first. A strong station spills into the channels
     * beside it (and HD Radio's digital sidebands sit there), so only a channel louder than its
     * neighbours counts.
     */
    fun carriers(bins: List<PowerBin>, region: Region, marginDb: Double = 8.0): List<Carrier> {
        if (bins.isEmpty()) return emptyList()
        // The middle of an FM signal: ±50 kHz around the channel.
        val power = region.channelsKhz.associateWith { khz ->
            val center = khz * 1000.0
            bins.filter { abs(it.hz - center) <= 50_000 }.map { it.db }.takeIf { it.isNotEmpty() }?.average()
        }.filterValues { it != null }.mapValues { it.value!! }
        if (power.size < 5) return emptyList()
        val sorted = power.values.sorted()
        val floor = sorted[sorted.size / 5]
        val reach = maxOf(200, region.spacingKhz)
        return power.filter { (khz, db) ->
            db - floor >= marginDb && power.none { (other, otherDb) -> other != khz && abs(other - khz) <= reach && otherDb > db }
        }.map { (khz, db) -> Carrier(khz, db, db - floor) }.sortedByDescending { it.db }
    }
}
