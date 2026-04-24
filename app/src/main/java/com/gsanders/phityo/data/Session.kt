package com.gsanders.phityo.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationSec: Int,
    val distanceM: Int,
    val avgSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val avgInclinePct: Double,
    val kcal: Int?,
    val steps: Int?,
)
