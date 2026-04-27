package com.gsanders.phityo.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gsanders.phityo.PhityoApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TreadmillActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? PhityoApp ?: return
        when (intent.action) {
            ACTION_STOP -> app.client.stop()
            ACTION_RESTART -> app.appScope.launch {
                val sp = app.settings.lastSpeedTenths.first()
                val inc = app.settings.lastInclinePct.first()
                if (sp != null && inc != null) {
                    app.client.startWithTargets(sp, inc)
                } else {
                    app.client.start()
                }
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.gsanders.phityo.action.STOP"
        const val ACTION_RESTART = "com.gsanders.phityo.action.RESTART"
    }
}
