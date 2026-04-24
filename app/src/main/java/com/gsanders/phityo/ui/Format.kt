package com.gsanders.phityo.ui

import com.gsanders.phityo.data.UnitSystem
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

private const val KM_PER_MILE = 1.609344

fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatDurationCompact(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "${seconds}s"
    }
}

fun formatSpeed(kmh: Double, units: UnitSystem): String = when (units) {
    UnitSystem.Metric   -> "%.2f".format(kmh)
    UnitSystem.Imperial -> "%.2f".format(kmh / KM_PER_MILE)
}

fun speedUnitLabel(units: UnitSystem) = if (units == UnitSystem.Metric) "km/h" else "mph"

fun formatDistance(meters: Int, units: UnitSystem): String = when (units) {
    UnitSystem.Metric   ->
        if (meters >= 1000) "%.2f km".format(meters / 1000.0) else "$meters m"
    UnitSystem.Imperial -> "%.2f mi".format(meters / (KM_PER_MILE * 1000.0))
}

/** Step size for +/- speed buttons, in km/h (what FTMS consumes). */
fun speedStepKmh(units: UnitSystem) = when (units) {
    UnitSystem.Metric -> 0.1
    UnitSystem.Imperial -> 0.1 * KM_PER_MILE
}

fun formatDate(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

fun relativeTime(ms: Long, now: Long = System.currentTimeMillis()): String {
    val deltaMs = now - ms
    if (deltaMs < 0) return formatDate(ms)
    val days = TimeUnit.MILLISECONDS.toDays(deltaMs)
    return when {
        days == 0L -> "Today " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
        days == 1L -> "Yesterday"
        days < 7   -> "${days}d ago"
        else       -> formatDate(ms).substringBefore(",")
    }
}
