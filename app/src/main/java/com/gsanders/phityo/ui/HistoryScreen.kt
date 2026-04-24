package com.gsanders.phityo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.gsanders.phityo.R
import com.gsanders.phityo.data.PeriodSummary
import com.gsanders.phityo.data.Session
import com.gsanders.phityo.data.SessionDao
import com.gsanders.phityo.data.Settings
import com.gsanders.phityo.data.UnitSystem

@Composable
fun HistoryScreen(
    dao: SessionDao,
    settings: Settings,
    modifier: Modifier = Modifier,
) {
    var period by remember { mutableStateOf(Period.Week) }
    val range = remember(period) { rangeFor(period) }
    val summary by remember(range) {
        dao.observeSummary(range.first, range.last)
    }.collectAsState(initial = PeriodSummary(0, 0, 0, null, null))

    val sessions by remember { dao.observeAll() }.collectAsState(initial = emptyList())
    val units by settings.units.collectAsState(initial = UnitSystem.Imperial)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Period.entries.forEach { p ->
                FilterChip(
                    selected = period == p,
                    onClick = { period = p },
                    label = { Text(p.label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        SummaryCard(period = period, summary = summary, units = units)

        Text(
            "All sessions",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (sessions.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Image(
                    painter = painterResource(id = R.drawable.treadmill),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(160.dp).clip(RoundedCornerShape(20.dp)),
                )
                Text(
                    "No sessions yet. Finish a walk and it'll appear here.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sessions, key = { it.id }) { SessionRow(it, units) }
            }
        }
    }
}

@Composable
private fun SummaryCard(period: Period, summary: PeriodSummary, units: UnitSystem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(period.label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Stat("Sessions", summary.sessions.toString())
                Stat("Distance", formatDistance(summary.totalDistanceM, units))
                Stat("Time", formatDurationCompact(summary.totalDurationSec))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Stat("Calories", summary.totalKcal?.let { "$it kcal" } ?: "—")
                Stat("Steps", summary.totalSteps?.let { "%,d".format(it) } ?: "—")
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SessionRow(s: Session, units: UnitSystem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(relativeTime(s.startTimeMs), fontWeight = FontWeight.SemiBold)
                Text(formatDuration(s.durationSec), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val unit = speedUnitLabel(units)
                Text(formatDistance(s.distanceM, units), fontSize = 14.sp)
                Text("avg ${formatSpeed(s.avgSpeedKmh, units)} $unit", fontSize = 14.sp)
                Text("max ${formatSpeed(s.maxSpeedKmh, units)}", fontSize = 14.sp)
                s.kcal?.let { Text("$it kcal", fontSize = 14.sp) }
            }
        }
    }
}
