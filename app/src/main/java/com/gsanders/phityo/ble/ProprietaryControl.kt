package com.gsanders.phityo.ble

/**
 * Trailviber (FS-A1139C) proprietary control protocol over BLE service 0xFFF0.
 * See memory/reference_trailviber_proprietary.md for the reverse-engineering
 * notes. Frames are `0x02 <body> <XOR-of-body> 0x03`.
 */
object ProprietaryControl {

    private const val STX: Byte = 0x02
    private const val ETX: Byte = 0x03

    /** Handshake captured from the stock app; replayed verbatim. */
    val HANDSHAKE_BODY = byteArrayOf(
        0xAB.toByte(), 0x0A, 0x54, 0x25, 0x3A, 0x31, 0x04, 0x4D, 0x11,
    )

    val QUERY_A_BODY = byteArrayOf(0x50, 0x02)
    val QUERY_B_BODY = byteArrayOf(0x50, 0x03)

    /** Status poll; sent periodically to keep the session alive. */
    val POLL_BODY = byteArrayOf(0x51)

    /** Start command body (no useful parameters). */
    val START_BODY = byteArrayOf(
        0x53, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )

    /** Stop command body. */
    val STOP_BODY = byteArrayOf(0x53, 0x03)

    /**
     * Build a "set target" command.
     * @param speedMphTenths Speed as 0.1-mph units, clamped 0..255 (treadmill min ~7).
     * @param inclinePct     Incline as whole percent, clamped 0..255.
     */
    fun setTargetBody(speedMphTenths: Int, inclinePct: Int): ByteArray = byteArrayOf(
        0x53, 0x02,
        speedMphTenths.coerceIn(0, 255).toByte(),
        inclinePct.coerceIn(0, 255).toByte(),
    )

    /** Wrap a body with STX/XOR/ETX. */
    fun frame(body: ByteArray): ByteArray {
        val out = ByteArray(body.size + 3)
        out[0] = STX
        System.arraycopy(body, 0, out, 1, body.size)
        var xor = 0
        for (b in body) xor = xor xor (b.toInt() and 0xFF)
        out[body.size + 1] = xor.toByte()
        out[body.size + 2] = ETX
        return out
    }

    /** Return body bytes if the frame is well-formed, or null. */
    fun parseFrame(bytes: ByteArray): ByteArray? {
        if (bytes.size < 3) return null
        if (bytes[0] != STX) return null
        if (bytes[bytes.size - 1] != ETX) return null
        val body = bytes.copyOfRange(1, bytes.size - 2)
        val expected = bytes[bytes.size - 2].toInt() and 0xFF
        var xor = 0
        for (b in body) xor = xor xor (b.toInt() and 0xFF)
        return if (xor == expected) body else null
    }

    /**
     * Decoded status from a 0x51-prefixed poll response.
     * All fields are best-effort; several bytes in the captured frames stay
     * zero and likely carry calories/steps once populated.
     */
    data class Status(
        val state: Int,
        val speedMphTenths: Int,
        val inclinePct: Int,
        val elapsedSec: Int,
        val distanceThousandthMiles: Int,
    )

    fun parseStatus(body: ByteArray): Status? {
        if (body.size < 9 || (body[0].toInt() and 0xFF) != 0x51) return null
        val state = body[1].toInt() and 0xFF
        val speed = body[2].toInt() and 0xFF
        val incline = body[3].toInt() and 0xFF
        val elapsed = (body[4].toInt() and 0xFF) or ((body[5].toInt() and 0xFF) shl 8)
        val distance = body[8].toInt() and 0xFF
        return Status(state, speed, incline, elapsed, distance)
    }
}
