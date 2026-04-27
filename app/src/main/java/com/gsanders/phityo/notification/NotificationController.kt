package com.gsanders.phityo.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gsanders.phityo.MainActivity
import com.gsanders.phityo.R
import com.gsanders.phityo.ble.ConnectionState
import com.gsanders.phityo.ble.FtmsClient
import com.gsanders.phityo.data.Settings
import com.gsanders.phityo.data.UnitSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Posts an ongoing notification while the treadmill is connected, with a
 * single context-appropriate action: Stop while the belt is moving, Restart
 * (mirroring the in-app Resume card) when it's idle and we have saved targets.
 */
class NotificationController(
    private val ctx: Context,
    private val client: FtmsClient,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val nm = NotificationManagerCompat.from(ctx)

    fun start() {
        ensureChannel()
        val savedTargets = combine(
            settings.lastSpeedTenths,
            settings.lastInclinePct,
            settings.units,
        ) { s, i, u -> SavedTargets(s, i, u) }
        scope.launch {
            combine(
                client.state,
                client.deviceName,
                client.vendorState,
                client.data,
                savedTargets,
            ) { state, name, vState, data, t ->
                Inputs(state, name, vState, data.speedKmh ?: 0.0, t)
            }.collect { update(it) }
        }
    }

    private fun update(i: Inputs) {
        if (i.state != ConnectionState.CONNECTED) {
            nm.cancel(NOTIF_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val running = when (i.vendorState) {
            0x02, 0x03 -> true
            0x04 -> i.speedKmh >= 0.1
            else -> false
        }
        val text = when (i.vendorState) {
            0x02 -> "Starting…"
            0x03 -> "Running"
            0x04 -> if (i.speedKmh >= 0.1) "Cooling down" else "Idle"
            else -> "Idle"
        }

        val openApp = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(i.name ?: "Phityo")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp)
        when {
            running -> builder.addAction(action(TreadmillActionReceiver.ACTION_STOP, "Stop"))
            i.targets.hasTargets -> builder.addAction(
                action(TreadmillActionReceiver.ACTION_RESTART, restartLabel(i.targets))
            )
        }
        nm.notify(NOTIF_ID, builder.build())
    }

    private fun restartLabel(t: SavedTargets): String {
        val sp = t.speedTenths ?: return "Restart"
        val inc = t.inclinePct ?: return "Restart"
        val speedStr = when (t.units) {
            UnitSystem.Imperial -> "%.1f mph".format(sp / 10.0)
            UnitSystem.Metric -> "%.1f km/h".format(sp / 10.0 * KM_PER_MILE)
        }
        return "Restart $speedStr · $inc%"
    }

    private fun action(actionName: String, label: String): NotificationCompat.Action {
        val intent = Intent(ctx, TreadmillActionReceiver::class.java).setAction(actionName)
        val pi = PendingIntent.getBroadcast(
            ctx, actionName.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(0, label, pi).build()
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Treadmill controls",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Stop and restart while the treadmill is connected."
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private data class SavedTargets(
        val speedTenths: Int?,
        val inclinePct: Int?,
        val units: UnitSystem,
    ) {
        val hasTargets: Boolean get() = speedTenths != null && inclinePct != null
    }

    private data class Inputs(
        val state: ConnectionState,
        val name: String?,
        val vendorState: Int,
        val speedKmh: Double,
        val targets: SavedTargets,
    )

    companion object {
        const val CHANNEL_ID = "treadmill_control"
        const val NOTIF_ID = 1001
        private const val KM_PER_MILE = 1.609344
    }
}
