package com.gsanders.phityo.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class PeriodSummary(
    val sessions: Int,
    val totalDistanceM: Int,
    val totalDurationSec: Int,
    val totalKcal: Int?,
    val totalSteps: Int?,
)

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: Session): Long

    @Query("SELECT * FROM sessions ORDER BY startTimeMs DESC")
    fun observeAll(): Flow<List<Session>>

    @Query("""
        SELECT
            COUNT(*)               AS sessions,
            COALESCE(SUM(distanceM), 0)    AS totalDistanceM,
            COALESCE(SUM(durationSec), 0)  AS totalDurationSec,
            SUM(kcal)              AS totalKcal,
            SUM(steps)             AS totalSteps
        FROM sessions
        WHERE startTimeMs >= :fromMs AND startTimeMs < :untilMs
    """)
    fun observeSummary(fromMs: Long, untilMs: Long): Flow<PeriodSummary>
}
