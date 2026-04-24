package com.gsanders.phityo.ui

import java.util.Calendar

enum class Period(val label: String) {
    Day("Today"),
    Week("This week"),
    Month("This month"),
    Year("This year"),
}

/** Returns [start, end) milliseconds covering the named [period] relative to [now]. */
fun rangeFor(period: Period, now: Long = System.currentTimeMillis()): LongRange {
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0);      cal.set(Calendar.MILLISECOND, 0)
    when (period) {
        Period.Day -> {}
        Period.Week -> {
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            cal.add(Calendar.DAY_OF_YEAR, -(dow - cal.firstDayOfWeek))
        }
        Period.Month -> cal.set(Calendar.DAY_OF_MONTH, 1)
        Period.Year -> {
            cal.set(Calendar.MONTH, Calendar.JANUARY)
            cal.set(Calendar.DAY_OF_MONTH, 1)
        }
    }
    val start = cal.timeInMillis
    return start until (now + 1)
}
