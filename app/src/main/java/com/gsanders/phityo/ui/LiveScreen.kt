package com.gsanders.phityo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gsanders.phityo.R
import com.gsanders.phityo.ble.ConnectionState
import com.gsanders.phityo.ble.FtmsClient
import com.gsanders.phityo.ble.TrainingStatus
import com.gsanders.phityo.data.Settings
import com.gsanders.phityo.data.UnitSystem

@Composable
fun LiveScreen(
    client: FtmsClient,
    settings: Settings,
    modifier: Modifier = Modifier,
) {
    val state by client.state.collectAsStateWithLifecycle()
    val data by client.data.collectAsStateWithLifecycle()
    val training by client.trainingStatus.collectAsStateWithLifecycle()
    val name by client.deviceName.collectAsStateWithLifecycle()
    val hasControl by client.hasControl.collectAsStateWithLifecycle()
    val units by settings.units.collectAsState(initial = UnitSystem.Imperial)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = statusLine(state, name, training, hasControl),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Middle region takes all remaining vertical space, so its content
        // (stats when connected, hero otherwise) always has room and always
        // re-renders when state transitions.
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (state == ConnectionState.CONNECTED) {
                Stats(data = data, units = units)
            } else {
                Hero(state = state)
            }
        }

        when (state) {
            ConnectionState.DISCONNECTED -> Button(
                onClick = { client.startScan() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Scan & connect") }
            ConnectionState.SCANNING -> OutlinedButton(
                onClick = { client.stopScan() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancel scan") }
            ConnectionState.CONNECTING -> OutlinedButton(
                onClick = { client.disconnect() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancel") }
            ConnectionState.CONNECTED -> ControlPanel(
                client = client,
                canControl = true,
            )
        }
    }
}

@Composable
private fun Stats(
    data: com.gsanders.phityo.ble.TreadmillData,
    units: UnitSystem,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StatRow("Speed",    data.speedKmh?.let { formatSpeed(it, units) } ?: "—", speedUnitLabel(units))
        StatRow("Distance", data.distanceM?.let { formatDistance(it, units) } ?: "—", "")
        StatRow("Incline",  data.inclinePct?.let { "%.1f".format(it) } ?: "—", "%")
        StatRow("Time",     data.elapsedSec?.let(::formatDuration) ?: "—", "")
        StatRow("Calories", data.totalKcal?.toString() ?: "—", "kcal")
    }
}

@Composable
private fun Hero(state: ConnectionState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(id = R.drawable.treadmill),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(240.dp)
                .clip(RoundedCornerShape(24.dp)),
        )
        Text(
            text = when (state) {
                ConnectionState.SCANNING -> "Looking for your treadmill…"
                ConnectionState.CONNECTING -> "Connecting…"
                ConnectionState.CONNECTED -> "Step on and start walking."
                ConnectionState.DISCONNECTED -> "Ready when you are."
            },
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ControlPanel(
    client: FtmsClient,
    canControl: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = { client.start() },
                enabled = canControl,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) { Text("Start", fontSize = 18.sp) }
            Button(
                onClick = { client.stop() },
                enabled = canControl,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Stop", fontSize = 18.sp) }
        }
        AdjustRow(
            label = "Speed",
            onMinus = { client.bumpSpeed(-1) },
            onPlus  = { client.bumpSpeed(+1) },
            enabled = canControl,
        )
        AdjustRow(
            label = "Incline",
            onMinus = { client.bumpIncline(-1) },
            onPlus  = { client.bumpIncline(+1) },
            enabled = canControl,
        )
    }
}

@Composable
private fun AdjustRow(label: String, onMinus: () -> Unit, onPlus: () -> Unit, enabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, modifier = Modifier.width(70.dp), fontSize = 16.sp)
        OutlinedButton(
            onClick = onMinus,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        ) { Text("−", fontSize = 22.sp) }
        OutlinedButton(
            onClick = onPlus,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        ) { Text("+", fontSize = 22.sp) }
    }
}

@Composable
private fun StatRow(label: String, value: String, unit: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
            if (unit.isNotEmpty()) {
                Text(
                    " $unit",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }
        }
    }
}

private fun statusLine(
    state: ConnectionState,
    name: String?,
    training: TrainingStatus,
    hasControl: Boolean,
): String {
    val base = when (state) {
        ConnectionState.DISCONNECTED -> "Disconnected"
        ConnectionState.SCANNING -> "Scanning…"
        ConnectionState.CONNECTING -> "Connecting to ${name ?: "treadmill"}…"
        ConnectionState.CONNECTED -> "Connected: ${name ?: "treadmill"}"
    }
    if (state != ConnectionState.CONNECTED) return base
    val ctrl = if (hasControl) "control ok" else "requesting control…"
    val ts = if (training != TrainingStatus.Unknown) "  •  ${training.name}" else ""
    return "$base  •  $ctrl$ts"
}
