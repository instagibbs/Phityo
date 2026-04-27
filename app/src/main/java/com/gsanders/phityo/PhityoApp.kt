package com.gsanders.phityo

import android.app.Application
import com.gsanders.phityo.ble.FtmsClient
import com.gsanders.phityo.data.PhityoDb
import com.gsanders.phityo.data.Settings
import com.gsanders.phityo.notification.NotificationController
import com.gsanders.phityo.session.SessionRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

class PhityoApp : Application() {

    val appScope = CoroutineScope(SupervisorJob())

    lateinit var settings: Settings
        private set

    lateinit var db: PhityoDb
        private set

    lateinit var client: FtmsClient
        private set

    lateinit var recorder: SessionRecorder
        private set

    lateinit var notifications: NotificationController
        private set

    /**
     * Background-idle watcher started by MainActivity.onStop. Held here so a
     * recreated activity instance can still cancel the watcher its predecessor
     * launched.
     */
    var idleDisconnectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        db = PhityoDb.get(this)
        client = FtmsClient(this, settings, appScope)
        recorder = SessionRecorder(this, client, db.sessionDao(), settings, appScope)
        recorder.start()
        notifications = NotificationController(this, client, settings, appScope)
        notifications.start()
        // Note: we do NOT auto-connect here because BLE permissions aren't
        // granted yet on first launch. MainActivity kicks off the connection
        // once permissions are confirmed.
    }
}
