package com.gsanders.phityo.session

import com.gsanders.phityo.ble.FtmsClient
import com.gsanders.phityo.ble.TreadmillData
import com.gsanders.phityo.data.Session
import com.gsanders.phityo.data.SessionDao
import com.gsanders.phityo.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Watches the live TreadmillData stream and persists a [Session] row whenever
 * the user completes a workout.
 *
 * Start rule:  first sample with speed > 0.
 * End rule:    speed has been 0 for [IDLE_END_SECONDS]; recorded session uses
 *              the last non-idle sample as its end point (so the trailing
 *              idle time isn't counted).
 *
 * The treadmill's reported Elapsed Time and Total Distance are monotonic per
 * workout and reset when the console resets, so we rely on them rather than
 * integrating ourselves.
 */
class SessionRecorder(
    private val client: FtmsClient,
    private val dao: SessionDao,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private var active: Active? = null

    data class Active(
        val startWallMs: Long,
        val startElapsedSec: Int,
        val startDistanceM: Int,
        var lastWallMs: Long,
        var lastElapsedSec: Int,
        var lastDistanceM: Int,
        var lastSpeedKmh: Double,
        var lastInclinePct: Double,
        var lastKcal: Int?,
        var maxSpeedKmh: Double,
        var speedSum: Double,
        var inclineSum: Double,
        var samples: Int,
        var idleSinceMs: Long?,
    )

    fun start() {
        scope.launch {
            client.data.collect { onSample(it) }
        }
    }

    private suspend fun onSample(d: TreadmillData) {
        val speed = d.speedKmh ?: return
        val elapsed = d.elapsedSec ?: return
        val distance = d.distanceM ?: 0
        val incline = d.inclinePct ?: 0.0
        val now = System.currentTimeMillis()

        val a = active
        if (a == null) {
            if (speed > 0.0) {
                active = Active(
                    startWallMs = now,
                    startElapsedSec = elapsed,
                    startDistanceM = distance,
                    lastWallMs = now,
                    lastElapsedSec = elapsed,
                    lastDistanceM = distance,
                    lastSpeedKmh = speed,
                    lastInclinePct = incline,
                    lastKcal = d.totalKcal,
                    maxSpeedKmh = speed,
                    speedSum = speed,
                    inclineSum = incline,
                    samples = 1,
                    idleSinceMs = null,
                )
            }
            return
        }

        // Console reset mid-session: elapsed/distance went backward.
        if (elapsed < a.lastElapsedSec || distance < a.lastDistanceM) {
            finalize(a)
            active = null
            return
        }

        if (speed > 0.0) {
            a.lastWallMs = now
            a.lastElapsedSec = elapsed
            a.lastDistanceM = distance
            a.lastSpeedKmh = speed
            a.lastInclinePct = incline
            d.totalKcal?.let { a.lastKcal = it }
            if (speed > a.maxSpeedKmh) a.maxSpeedKmh = speed
            a.speedSum += speed
            a.inclineSum += incline
            a.samples += 1
            a.idleSinceMs = null
        } else {
            val idleStart = a.idleSinceMs ?: now.also { a.idleSinceMs = it }
            if (now - idleStart >= IDLE_END_SECONDS * 1000L) {
                finalize(a)
                active = null
            }
        }
    }

    private suspend fun finalize(a: Active) {
        // Use the treadmill's own elapsed-time and distance totals rather than
        // the delta since the app connected. This matches what the console
        // displays and what's reported via FTMS, just like we already do for
        // calories.
        val durationSec = a.lastElapsedSec.coerceAtLeast(1)
        val distanceM = a.lastDistanceM.coerceAtLeast(0)
        val avgSpeed = if (a.samples > 0) a.speedSum / a.samples else 0.0
        val avgIncline = if (a.samples > 0) a.inclineSum / a.samples else 0.0
        val kcal = a.lastKcal
        val heightCm = settings.heightCm.first()
        val stride = Settings.strideM(heightCm)
        val steps = if (stride > 0 && distanceM > 0) (distanceM / stride).toInt() else null

        val startTimeMs = a.lastWallMs - durationSec * 1000L

        dao.insert(
            Session(
                startTimeMs = startTimeMs,
                endTimeMs = a.lastWallMs,
                durationSec = durationSec,
                distanceM = distanceM,
                avgSpeedKmh = avgSpeed,
                maxSpeedKmh = a.maxSpeedKmh,
                avgInclinePct = avgIncline,
                kcal = kcal,
                steps = steps,
            )
        )
    }

    companion object {
        private const val IDLE_END_SECONDS = 15
    }
}
