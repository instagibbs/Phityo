package com.gsanders.phityo.ble

data class TreadmillData(
    val speedKmh: Double? = null,
    val distanceM: Int? = null,
    val inclinePct: Double? = null,
    val totalKcal: Int? = null,
    val elapsedSec: Int? = null,
    val heartRateBpm: Int? = null,
)

/** Opcode from FTMS Training Status characteristic (0x2AD3). */
enum class TrainingStatus(val opcode: Int) {
    Other(0x00),
    Idle(0x01),
    WarmingUp(0x02),
    LowIntensity(0x03),
    HighIntensity(0x04),
    Recovery(0x05),
    Isometric(0x06),
    HeartRateControl(0x07),
    FitnessTest(0x08),
    LowSpeed(0x09),
    HighSpeed(0x0A),
    CoolDown(0x0B),
    WattControl(0x0C),
    ManualMode(0x0D),
    PreWorkout(0x0E),
    PostWorkout(0x0F),
    Unknown(-1);

    companion object {
        fun fromOpcode(op: Int) = entries.firstOrNull { it.opcode == op } ?: Unknown
    }
}

object FtmsParser {
    private const val FLAG_MORE_DATA       = 1 shl 0
    private const val FLAG_AVG_SPEED       = 1 shl 1
    private const val FLAG_TOTAL_DISTANCE  = 1 shl 2
    private const val FLAG_INCLINATION     = 1 shl 3
    private const val FLAG_ELEVATION_GAIN  = 1 shl 4
    private const val FLAG_INST_PACE       = 1 shl 5
    private const val FLAG_AVG_PACE        = 1 shl 6
    private const val FLAG_ENERGY          = 1 shl 7
    private const val FLAG_HEART_RATE      = 1 shl 8
    private const val FLAG_MET_EQUIV       = 1 shl 9
    private const val FLAG_ELAPSED_TIME    = 1 shl 10
    private const val FLAG_REMAINING_TIME  = 1 shl 11
    private const val FLAG_FORCE_POWER     = 1 shl 12

    fun parseTreadmillData(bytes: ByteArray): TreadmillData? {
        if (bytes.size < 2) return null
        val flags = u16(bytes, 0)

        if ((flags and FLAG_MORE_DATA) != 0) return null

        var off = 2
        var data = TreadmillData()

        if (off + 2 > bytes.size) return data
        data = data.copy(speedKmh = u16(bytes, off) / 100.0)
        off += 2

        if ((flags and FLAG_AVG_SPEED) != 0) off += 2

        if ((flags and FLAG_TOTAL_DISTANCE) != 0) {
            if (off + 3 > bytes.size) return data
            data = data.copy(distanceM = u24(bytes, off))
            off += 3
        }

        if ((flags and FLAG_INCLINATION) != 0) {
            if (off + 4 > bytes.size) return data
            data = data.copy(inclinePct = s16(bytes, off) / 10.0)
            off += 4
        }

        if ((flags and FLAG_ELEVATION_GAIN) != 0) off += 4
        if ((flags and FLAG_INST_PACE) != 0) off += 2
        if ((flags and FLAG_AVG_PACE) != 0) off += 2

        if ((flags and FLAG_ENERGY) != 0) {
            if (off + 5 > bytes.size) return data
            val total = u16(bytes, off)
            data = data.copy(totalKcal = if (total == 0xFFFF) null else total)
            off += 5
        }

        if ((flags and FLAG_HEART_RATE) != 0) {
            if (off + 1 > bytes.size) return data
            val hr = bytes[off].toInt() and 0xFF
            data = data.copy(heartRateBpm = if (hr == 0) null else hr)
            off += 1
        }

        if ((flags and FLAG_MET_EQUIV) != 0) off += 1

        if ((flags and FLAG_ELAPSED_TIME) != 0) {
            if (off + 2 > bytes.size) return data
            data = data.copy(elapsedSec = u16(bytes, off))
            off += 2
        }

        return data
    }

    /** Training Status (0x2AD3): byte 0 = flags, byte 1 = training status opcode. */
    fun parseTrainingStatus(bytes: ByteArray): TrainingStatus? {
        if (bytes.size < 2) return null
        return TrainingStatus.fromOpcode(bytes[1].toInt() and 0xFF)
    }

    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun s16(b: ByteArray, o: Int): Int {
        val v = u16(b, o)
        return if (v and 0x8000 != 0) v - 0x10000 else v
    }

    private fun u24(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16)
}
