package com.gsanders.phityo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gsanders.phityo.ble.ConnectionState
import com.gsanders.phityo.ble.FtmsClient
import com.gsanders.phityo.data.Settings
import com.gsanders.phityo.data.UnitSystem
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settings: Settings,
    client: FtmsClient,
    modifier: Modifier = Modifier,
) {
    val heightCm by settings.heightCm.collectAsState(initial = Settings.DEFAULT_HEIGHT_CM)
    val lastMac by settings.lastDeviceMac.collectAsState(initial = null)
    val units by settings.units.collectAsState(initial = UnitSystem.Imperial)
    val deviceName by client.deviceName.collectAsStateWithLifecycle()
    val currentMac by client.deviceMac.collectAsStateWithLifecycle()
    val state by client.state.collectAsStateWithLifecycle()
    val hasControl by client.hasControl.collectAsStateWithLifecycle()
    val controlDiag by client.controlDiag.collectAsStateWithLifecycle()
    val lastControlHex by client.lastControlHex.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    var heightInput by remember { mutableStateOf(heightCm.toString()) }
    LaunchedEffect(heightCm) { heightInput = heightCm.toString() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Units", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UnitSystem.entries.forEach { u ->
                        FilterChip(
                            selected = units == u,
                            onClick = { coroutineScope.launch { settings.setUnits(u) } },
                            label = { Text(if (u == UnitSystem.Metric) "Metric (km/h)" else "Imperial (mph)") },
                        )
                    }
                }
                Text(
                    "FTMS reports speed in km/h. If your treadmill console shows mph, pick Imperial so the app matches.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Body", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = heightInput,
                        onValueChange = { new ->
                            if (new.length <= 3 && new.all { it.isDigit() }) heightInput = new
                            val parsed = new.toIntOrNull()
                            if (parsed != null) coroutineScope.launch { settings.setHeightCm(parsed) }
                        },
                        label = { Text("Height (cm)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(180.dp),
                    )
                }
                Text(
                    "Used to estimate steps from distance (stride ≈ ${"%.2f".format(Settings.strideM(heightCm))} m).",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Treadmill", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val nameToShow = deviceName ?: lastMac?.let { "Saved: $it" } ?: "No device"
                Text(nameToShow, fontSize = 16.sp)
                currentMac?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { client.disconnect() }) { Text("Disconnect") }
                    OutlinedButton(onClick = { client.forget() }) { Text("Forget") }
                }
                Text(
                    "“Forget” clears the saved device so the app stops auto-reconnecting.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state == ConnectionState.CONNECTED) {
            val features by client.features.collectAsStateWithLifecycle()
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Remote control (FTMS)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (hasControl) "Granted" else "Not granted")
                    features?.let {
                        Text(
                            "Target support: speed=${it.supportsSpeedTarget}, incline=${it.supportsInclineTarget}",
                            fontSize = 12.sp,
                        )
                        Text(
                            "Raw 0x2ACC: ${it.rawFeaturesHex}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    controlDiag?.let {
                        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    lastControlHex?.let {
                        Text(
                            "Last raw indication: $it",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    OutlinedButton(
                        onClick = { client.retryControlRequest() },
                        enabled = !hasControl,
                    ) { Text("Retry control request") }
                }
            }
        }
    }
}
