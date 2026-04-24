package com.gsanders.phityo.ble

/**
 * FTMS Fitness Machine Control Point (0x2AD9) request builders.
 * Every command starts with an opcode byte; some take parameters.
 * Must send RequestControl (0x00) first and receive a successful indication
 * before other commands are accepted.
 */
object FtmsControl {
    const val OP_REQUEST_CONTROL = 0x00
    const val OP_RESET           = 0x01
    const val OP_SET_TARGET_SPEED      = 0x02
    const val OP_SET_TARGET_INCLINE    = 0x03
    const val OP_START_OR_RESUME = 0x07
    const val OP_STOP_OR_PAUSE   = 0x08

    const val STOP_PARAM_STOP  = 0x01
    const val STOP_PARAM_PAUSE = 0x02

    fun requestControl(): ByteArray = byteArrayOf(OP_REQUEST_CONTROL.toByte())
    fun reset():          ByteArray = byteArrayOf(OP_RESET.toByte())
    fun startOrResume():  ByteArray = byteArrayOf(OP_START_OR_RESUME.toByte())
    fun stop():           ByteArray = byteArrayOf(OP_STOP_OR_PAUSE.toByte(), STOP_PARAM_STOP.toByte())
    fun pause():          ByteArray = byteArrayOf(OP_STOP_OR_PAUSE.toByte(), STOP_PARAM_PAUSE.toByte())

    /** Target speed in km/h. Encoded as uint16 LE, resolution 0.01 km/h. */
    fun setTargetSpeed(kmh: Double): ByteArray {
        val v = (kmh * 100).toInt().coerceIn(0, 0xFFFF)
        return byteArrayOf(
            OP_SET_TARGET_SPEED.toByte(),
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
        )
    }

    /** Target inclination in percent. Encoded as sint16 LE, resolution 0.1 %. */
    fun setTargetIncline(percent: Double): ByteArray {
        val v = (percent * 10).toInt().coerceIn(-0x8000, 0x7FFF)
        val u = if (v < 0) v + 0x10000 else v
        return byteArrayOf(
            OP_SET_TARGET_INCLINE.toByte(),
            (u and 0xFF).toByte(),
            ((u shr 8) and 0xFF).toByte(),
        )
    }
}
