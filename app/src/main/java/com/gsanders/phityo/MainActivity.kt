package com.gsanders.phityo

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
            val granted = results.values.all { it }
            if (granted) autoConnectOnStartup(app)
        }
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permLauncher.launch(perms)

        setContent {
            MaterialTheme {
                Surface { PhityoNav(app) }
            }
        }
    }

    private fun autoConnectOnStartup(app: PhityoApp) {
        app.appScope.launch {
            if (app.client.state.value != ConnectionState.DISCONNECTED) return@launch
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
