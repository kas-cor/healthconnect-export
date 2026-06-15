package com.healthconnect.export.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.healthconnect.export.R
import com.healthconnect.export.data.ExportSummary

/**
 * Card displaying a dashboard summary of exported health data.
 * Shows key metrics (steps, heart rate, calories, distance, sleep, active calories)
 * in a 3-column grid layout with an animated appearance.
 */
@Composable
fun ExportSummaryCard(
    summary: ExportSummary,
    onDismiss: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.dashboard_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.dashboard_dismiss),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Period
            Text(
                stringResource(R.string.dashboard_period, summary.startDate, summary.endDate, summary.daysCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Metrics grid — 3 columns
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Steps
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.DirectionsWalk,
                    value = formatNumber(summary.totalSteps),
                    label = stringResource(R.string.dashboard_label_steps),
                    available = summary.totalSteps > 0
                )
                // Heart Rate
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Favorite,
                    value = if (summary.avgHeartRate > 0) "${summary.avgHeartRate.toInt()} bpm" else stringResource(R.string.dashboard_no_data),
                    label = stringResource(R.string.dashboard_label_hr),
                    available = summary.avgHeartRate > 0
                )
                // Calories
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.LocalFireDepartment,
                    value = if (summary.totalCalories > 0) formatNumber(summary.totalCalories.toLong()) else stringResource(R.string.dashboard_no_data),
                    label = stringResource(R.string.dashboard_label_calories),
                    available = summary.totalCalories > 0
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Second row — 3 columns
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Distance
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Straighten,
                    value = if (summary.totalDistanceMeters > 0) {
                        val km = summary.totalDistanceMeters / 1000.0
                        if (km >= 1) String.format("%.1f km", km) else "${summary.totalDistanceMeters.toInt()} m"
                    } else stringResource(R.string.dashboard_no_data),
                    label = stringResource(R.string.dashboard_label_distance),
                    available = summary.totalDistanceMeters > 0
                )
                // Sleep
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Nightlight,
                    value = if (summary.avgSleepMinutes > 0) formatMinutesToHours(summary.avgSleepMinutes) else stringResource(R.string.dashboard_no_data),
                    label = stringResource(R.string.dashboard_label_sleep),
                    available = summary.avgSleepMinutes > 0
                )
                // Active Calories
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Bolt,
                    value = if (summary.totalActiveCalories > 0) formatNumber(summary.totalActiveCalories.toLong()) else stringResource(R.string.dashboard_no_data),
                    label = stringResource(R.string.dashboard_label_active_cal),
                    available = summary.totalActiveCalories > 0
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Dismiss button
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.dashboard_dismiss))
            }
        }
    }
}

/**
 * Individual stat card used within the ExportSummaryCard grid.
 * Displays an icon, a value, and a label with visual distinction for unavailable data.
 */
@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    value: String,
    label: String,
    available: Boolean
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (available) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(20.dp),
                tint = if (available) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                color = if (available) MaterialTheme.colorScheme.onSurface
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Formats a long number for display, e.g. 1_500_000 -> "1.5M", 1_500 -> "1,500".
 */
private fun formatNumber(value: Long): String {
    return when {
        value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format("%,d", value)
        else -> value.toString()
    }
}

/**
 * Formats minutes into a human-readable hours/minutes string.
 * e.g. 150 -> "2h 30m"
 */
private fun formatMinutesToHours(minutes: Long): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
}
