package com.gsanders.phityo.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class UnitSystem { Metric, Imperial }

private val Context.dataStore by preferencesDataStore(name = "phityo_settings")

class Settings(private val ctx: Context) {

    val heightCm: Flow<Int> = ctx.dataStore.data.map { it[KEY_HEIGHT_CM] ?: DEFAULT_HEIGHT_CM }
    val lastDeviceMac: Flow<String?> = ctx.dataStore.data.map { it[KEY_LAST_MAC] }
    val units: Flow<UnitSystem> = ctx.dataStore.data.map {
        when (it[KEY_UNITS]) {
            "metric" -> UnitSystem.Metric
            else     -> UnitSystem.Imperial
        }
    }

    /**
     * Last target speed (0.1 mph units) and incline (whole %) the user set
     * via the app. Null until the user has ever set one — in that case the
     * Resume-last UI should be hidden entirely rather than guess a default.
     */
    val lastSpeedTenths: Flow<Int?> = ctx.dataStore.data.map { it[KEY_LAST_SPEED_TENTHS] }
    val lastInclinePct:  Flow<Int?> = ctx.dataStore.data.map { it[KEY_LAST_INCLINE_PCT] }

    suspend fun setHeightCm(cm: Int) {
        ctx.dataStore.edit { it[KEY_HEIGHT_CM] = cm.coerceIn(120, 230) }
    }

    suspend fun setLastDeviceMac(mac: String?) {
        ctx.dataStore.edit {
            if (mac == null) it.remove(KEY_LAST_MAC) else it[KEY_LAST_MAC] = mac
        }
    }

    suspend fun setUnits(u: UnitSystem) {
        ctx.dataStore.edit { it[KEY_UNITS] = if (u == UnitSystem.Metric) "metric" else "imperial" }
    }

    suspend fun setLastTargets(speedTenths: Int, inclinePct: Int) {
        ctx.dataStore.edit {
            it[KEY_LAST_SPEED_TENTHS] = speedTenths.coerceIn(0, 255)
            it[KEY_LAST_INCLINE_PCT] = inclinePct.coerceIn(0, 255)
        }
    }

    companion object {
        private val KEY_HEIGHT_CM         = intPreferencesKey("height_cm")
        private val KEY_LAST_MAC          = stringPreferencesKey("last_device_mac")
        private val KEY_UNITS             = stringPreferencesKey("units")
        private val KEY_LAST_SPEED_TENTHS = intPreferencesKey("last_speed_tenths")
        private val KEY_LAST_INCLINE_PCT  = intPreferencesKey("last_incline_pct")
        const val DEFAULT_HEIGHT_CM = 175

        /** Walking stride length in meters. Rough model: ~0.414 × height. */
        fun strideM(heightCm: Int): Double = heightCm * 0.00414
    }
}
