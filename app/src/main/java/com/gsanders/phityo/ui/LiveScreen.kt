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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay

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
    val vendorState by client.vendorState.collectAsStateWithLifecycle()
    val units by settings.units.collectAsState(initial = UnitSystem.Imperial)
    val lastSpeedTenths by settings.lastSpeedTenths.collectAsState(initial = null)
    val lastInclinePct  by settings.lastInclinePct.collectAsState(initial = null)

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
            ConnectionState.CONNECTED -> {
                val sp = lastSpeedTenths
                val inc = lastInclinePct
                // Show the Resume card whenever the belt has effectively
                // stopped: either the firmware reports idle (0x00), or
                // we're in cooldown (0x04) with the reported speed already
                // at ~0. Never during countdown (0x02) or running (0x03).
                val beltStopped = when (vendorState) {
                    0x00 -> true
                    0x04 -> (data.speedKmh ?: 0.0) < 0.1
                    else -> false
                }
                val showResume = beltStopped && sp != null && inc != null
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (showResume) {
                        ResumeLastCard(
                            speedTenths = sp!!,
                            inclinePct = inc!!,
                            units = units,
                            onConfirm = { client.startWithTargets(sp, inc) },
                        )
                    }
                    ControlPanel(client = client, canControl = true)
                }
            }
        }
    }
}

@Composable
private fun ResumeLastCard(
    speedTenths: Int,
    inclinePct: Int,
    units: UnitSystem,
    onConfirm: () -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val speedStr = "${formatSpeed(speedTenthsToKmh(speedTenths), units)} ${speedUnitLabel(units)}"
    val inclineStr = "$inclinePct%"

    Card(
        onClick = { showDialog = true },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    "Resume last",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                )
                Text(
                    "$speedStr · $inclineStr",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                "Start →",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }

    if (showDialog) {
        ResumeConfirmDialog(
            speedStr = speedStr,
            inclineStr = inclineStr,
            onDismiss = { showDialog = false },
            onCountdownDone = {
                showDialog = false
                onConfirm()
            },
        )
    }
}

@Composable
private fun ResumeConfirmDialog(
    speedStr: String,
    inclineStr: String,
    onDismiss: () -> Unit,
    onCountdownDone: () -> Unit,
) {
    // null = user hasn't tapped Start yet; once set, ticks down 3 → 0.
    var remaining by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(remaining) {
        val r = remaining ?: return@LaunchedEffect
        if (r <= 0) {
            onCountdownDone()
            return@LaunchedEffect
        }
        delay(1000)
        remaining = r - 1
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start treadmill?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "$speedStr · $inclineStr",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "The belt will ramp to this speed after a 3-second countdown. " +
                        "Step on and hold the rails first.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (remaining == null) remaining = 3 },
                enabled = remaining == null,
            ) {
                Text(
                    when (remaining) {
                        null -> "Start"
                        else -> "Starting in ${remaining}…"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private const val KM_PER_MILE_UI = 1.609344
private fun speedTenthsToKmh(tenths: Int): Double = (tenths / 10.0) * KM_PER_MILE_UI

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
