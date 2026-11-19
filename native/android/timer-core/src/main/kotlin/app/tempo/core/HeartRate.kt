package app.tempo.core

/** Heart-rate helpers shared by the phone native module and the Wear OS app. */
object HeartRate {
    const val MIN_VALID_BPM = 25
    const val MAX_VALID_BPM = 250

    fun isValid(bpm: Int): Boolean = bpm in MIN_VALID_BPM..MAX_VALID_BPM

    /** Zone 1–5 as a percentage of HR max (50/60/70/80/90 %). Returns 0 below zone 1. */
    fun zone(bpm: Int, hrMax: Int): Int {
        if (hrMax <= 0) return 0
        val pct = bpm.toDouble() / hrMax
        return when {
            pct >= 0.9 -> 5
            pct >= 0.8 -> 4
            pct >= 0.7 -> 3
            pct >= 0.6 -> 2
            pct >= 0.5 -> 1
            else -> 0
        }
    }

    enum class Sex { FEMALE, MALE, UNSPECIFIED }

    /**
     * Keytel et al. (2005) energy expenditure from average HR, in kcal.
     * For UNSPECIFIED sex the mean of both equations is used. Never returns a negative value.
     */
    fun keytelCalories(avgHr: Double, minutes: Double, weightKg: Double, age: Int, sex: Sex): Double {
        val male = (-55.0969 + 0.6309 * avgHr + 0.1988 * weightKg + 0.2017 * age) / 4.184
        val female = (-20.4022 + 0.4472 * avgHr - 0.1263 * weightKg + 0.074 * age) / 4.184
        val perMin = when (sex) {
            Sex.MALE -> male
            Sex.FEMALE -> female
            Sex.UNSPECIFIED -> (male + female) / 2
        }
        return maxOf(0.0, perMin * minutes)
    }

    /** MET-based fallback when no heart rate is available: kcal = MET × kg × hours. */
    fun metCalories(met: Double, weightKg: Double, minutes: Double): Double =
        maxOf(0.0, met * weightKg * minutes / 60.0)

    /**
     * Parses the Bluetooth Heart Rate Measurement characteristic (0x2A37).
     * Bit 0 of the flags byte selects UINT8 or UINT16 (little-endian) format.
     * Returns null for malformed packets or physiologically implausible values.
     */
    fun parseBleMeasurement(bytes: ByteArray?): Int? {
        if (bytes == null || bytes.isEmpty()) return null
        val flags = bytes[0].toInt() and 0xFF
        val bpm = if (flags and 0x01 == 0) {
            if (bytes.size < 2) return null
            bytes[1].toInt() and 0xFF
        } else {
            if (bytes.size < 3) return null
            (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
        }
        return bpm.takeIf { isValid(it) }
    }
}
