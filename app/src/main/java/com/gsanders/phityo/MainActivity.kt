package com.gsanders.phityo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gsanders.phityo.ble.ConnectionState
import com.gsanders.phityo.ui.HistoryScreen
import com.gsanders.phityo.ui.LiveScreen
import com.gsanders.phityo.ui.SettingsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private sealed class Dest(val route: String, val label: String) {
    data object Live    : Dest("live",    "Live")
    data object History : Dest("history", "History")
    data object Settings: Dest("settings","Settings")
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val app = application as PhityoApp

        val permLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            val blePerms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            // First-launch path: lifecycle observer's onStart already ran
            // before the user accepted, so trigger the connect here.
            if (blePerms.all { results[it] == true }) autoConnectOnStartup(app)
        }
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        permLauncher.launch(perms)

        // Connection follows activity foreground: connect on every START,
        // and on STOP, watch for idle and disconnect once the workout has
        // truly settled (vendor state back to 0x00 / speed back to 0). Lets
        // the treadmill enter standby whenever the user isn't looking.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                app.idleDisconnectJob?.cancel()
                app.idleDisconnectJob = null
                if (hasBlePerms()) autoConnectOnStartup(app)
            }
            override fun onStop(owner: LifecycleOwner) {
                if (isChangingConfigurations) return
                scheduleDisconnectIfIdle(app)
            }
        })

        setContent {
            MaterialTheme {
                Surface { PhityoNav(app) }
            }
        }
    }

    private fun hasBlePerms(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun scheduleDisconnectIfIdle(app: PhityoApp) {
        app.idleDisconnectJob?.cancel()
        app.idleDisconnectJob = app.appScope.launch {
            var idleSinceMs: Long? = null
            while (true) {
                if (app.client.state.value != ConnectionState.CONNECTED) return@launch
                val speed = app.client.data.value.speedKmh ?: 0.0
                val vState = app.client.vendorState.value
                // Cooldown (0x04) is excluded from "running" — belt is at 0,
                // we just need to wait out the firmware's summary screen.
                val running = speed > 0.0 || vState == 0x02 || vState == 0x03
                val now = System.currentTimeMillis()
                if (running) {
                    idleSinceMs = null
                } else {
                    val mark = idleSinceMs
                    if (mark == null) {
                        idleSinceMs = now
                    } else if (now - mark >= IDLE_DISCONNECT_MS) {
                        app.client.disconnect()
                        return@launch
                    }
                }
                delay(IDLE_POLL_MS)
            }
        }
    }

    companion object {
        private const val IDLE_DISCONNECT_MS = 10_000L
        private const val IDLE_POLL_MS = 1_000L
    }

    private fun autoConnectOnStartup(app: PhityoApp) {
        app.appScope.launch {
            if (app.client.state.value != ConnectionState.DISCONNECTED) return@launch
            if (!app.settings.autoReconnectEnabled.first()) return@launch
            val savedMac = app.settings.lastDeviceMac.first()
            if (savedMac != null) {
                app.client.autoReconnectIfKnown()
            } else {
                app.client.startScan()
            }
        }
    }
}

@Composable
private fun PhityoNav(app: PhityoApp) {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val items = listOf(Dest.Live, Dest.History, Dest.Settings)

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.treadmill2),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = 0.08f,
            modifier = Modifier.fillMaxSize(),
        )

        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar {
                    items.forEach { dest ->
                        val selected = currentRoute == dest.route ||
                            backStackEntry?.destination?.hierarchy?.any { it.route == dest.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(dest.route) {
                                    popUpTo(nav.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = when (dest) {
                                        Dest.Live     -> Icons.Filled.PlayArrow
                                        Dest.History  -> Icons.Filled.DateRange
                                        Dest.Settings -> Icons.Filled.Settings
                                    },
                                    contentDescription = dest.label,
                                )
                            },
                            label = { Text(dest.label) },
                        )
                    }
                }
            },
        ) { inner: PaddingValues ->
            NavHost(
                navController = nav,
                startDestination = Dest.Live.route,
                modifier = Modifier.padding(inner),
            ) {
                composable(Dest.Live.route)    { LiveScreen(app.client, app.settings) }
                composable(Dest.History.route) { HistoryScreen(app.db.sessionDao(), app.settings) }
                composable(Dest.Settings.route){ SettingsScreen(app.settings, app.client) }
            }
        }
    }
}
