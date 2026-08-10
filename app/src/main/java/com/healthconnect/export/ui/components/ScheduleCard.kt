package com.healthconnect.export.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.healthconnect.export.R
import com.healthconnect.export.data.ExportFrequency
import com.healthconnect.export.viewmodel.ScheduleStatus

@Composable
fun ScheduleCard(
    frequency: ExportFrequency,
    scheduleStatus: ScheduleStatus,
    onFrequencyChange: (ExportFrequency) -> Unit,
    onSchedule: () -> Unit,
    onCancel: () -> Unit,
    autoSendWebhookEvery2Hours: Boolean = false,
    webhookUrl: String = "",
    onAutoSendEvery2HoursChange: (Boolean) -> Unit = {},
    scheduleHour: Int? = null,
    onScheduleHourChange: (Int?) -> Unit = {}
) {
    MaterialCard {
        Column {
            MaterialSectionHeader(
                icon = Icons.Default.Schedule,
                title = stringResource(R.string.scheduled_export),
            )

            Spacer(modifier = Modifier.height(8.dp))

            ExportFrequency.entries.forEach { freq ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RadioButton(
                        selected = frequency == freq,
                        onClick = { onFrequencyChange(freq) }
                    )
                    Text(exportFrequencyDisplayName(freq))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Time-of-day selector for periodic schedules
            if (frequency != ExportFrequency.MANUAL) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.schedule_time_label),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    ScheduleHourDropdown(
                        hour = scheduleHour,
                        onHourChange = onScheduleHourChange
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            when (scheduleStatus) {
                is ScheduleStatus.NotScheduled -> {
                    if (frequency != ExportFrequency.MANUAL) {
                        Button(onClick = onSchedule, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.enable_schedule))
                        }
                    }
                }
                is ScheduleStatus.Scheduled -> {
                    Text(scheduleStatus.nextRun, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.disable))
                    }
                }
                is ScheduleStatus.Running -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            // Every 2 hours webhook checkbox
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = autoSendWebhookEvery2Hours,
                    onCheckedChange = { onAutoSendEvery2HoursChange(it) },
                    enabled = webhookUrl.isNotBlank()
                )
                Text(
                    text = if (webhookUrl.isBlank()) stringResource(R.string.enter_url_first_every_2h)
                           else stringResource(R.string.every_2_hours_webhook),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun exportFrequencyDisplayName(freq: ExportFrequency): String = when (freq) {
    ExportFrequency.MANUAL -> stringResource(R.string.freq_manual)
    ExportFrequency.DAILY -> stringResource(R.string.freq_daily)
    ExportFrequency.WEEKLY -> stringResource(R.string.freq_weekly)
}

/**
 * Dropdown for picking the hour of day at which the periodic export runs.
 * "Default" means the export runs relative to the moment it was scheduled.
 */
@Composable
private fun ScheduleHourDropdown(
    hour: Int?,
    onHourChange: (Int?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val defaultLabel = stringResource(R.string.schedule_time_default)

    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                text = hour?.let { "%02d:00".format(it) } ?: defaultLabel,
                style = MaterialTheme.typography.bodyMedium
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(defaultLabel) },
                onClick = {
                    expanded = false
                    onHourChange(null)
                }
            )
            (0..23).forEach { h ->
                val label = "%02d:00".format(h)
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onHourChange(h)
                    }
                )
            }
        }
    }
}
