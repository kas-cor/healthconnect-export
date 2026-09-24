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
import com.healthconnect.export.data.LastSend
import com.healthconnect.export.viewmodel.ScheduleStatus
import com.healthconnect.export.worker.Every2HoursWebhookWorker
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    onScheduleHourChange: (Int?) -> Unit = {},
    lastSend: LastSend? = null,
    isIgnoringBatteryOptimizations: Boolean = false,
    onRequestBatteryExemption: () -> Unit = {},
    onSendMissingNow: () -> Unit = {},
    onRefreshDiagnostics: () -> Unit = {},
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
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RadioButton(
                        selected = frequency == freq,
                        onClick = { onFrequencyChange(freq) },
                    )
                    Text(exportFrequencyDisplayName(freq))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Time-of-day selector for periodic schedules
            if (frequency != ExportFrequency.MANUAL) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.schedule_time_label),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    ScheduleHourDropdown(
                        hour = scheduleHour,
                        onHourChange = onScheduleHourChange,
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
                modifier = Modifier.fillMaxWidth(),
            ) {
                Checkbox(
                    checked = autoSendWebhookEvery2Hours,
                    onCheckedChange = { onAutoSendEvery2HoursChange(it) },
                    enabled = webhookUrl.isNotBlank(),
                )
                Text(
                    text =
                        if (webhookUrl.isBlank()) {
                            stringResource(R.string.enter_url_first_every_2h)
                        } else {
                            stringResource(R.string.every_2_hours_webhook)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }

            // ── Delivery diagnostics ────────────────────────────────────────
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.last_send_label),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = lastSendSummary(lastSend),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onRefreshDiagnostics) {
                    Text(stringResource(R.string.refresh))
                }
            }

            LastSendWarning(lastSend)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text =
                        if (isIgnoringBatteryOptimizations) {
                            stringResource(R.string.battery_optimization_off)
                        } else {
                            stringResource(R.string.battery_optimization_hint)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (!isIgnoringBatteryOptimizations) {
                    TextButton(onClick = onRequestBatteryExemption) {
                        Text(stringResource(R.string.battery_optimization_allow))
                    }
                }
            }

            TextButton(onClick = onSendMissingNow, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.send_missing_now))
            }
        }
    }
}

/**
 * "dd.MM HH:mm · HTTP 200" for the last delivered payload, or a "never" label.
 * [LastSend] timestamps are epoch millis; the app always displays local time.
 */
@Composable
private fun lastSendSummary(lastSend: LastSend?): String {
    val timestampMs = lastSend?.successTimestampMs ?: return stringResource(R.string.last_send_never)
    val formatted = LAST_SEND_FORMAT.format(Instant.ofEpochMilli(timestampMs))
    val statusCode = lastSend.successStatusCode
    return if (statusCode == null) {
        formatted
    } else {
        stringResource(R.string.last_send_value, formatted, statusCode)
    }
}

/**
 * Shows either the stale-data warning (nothing delivered for longer than the
 * catch-up threshold) or the most recent delivery error, if any.
 */
@Composable
private fun LastSendWarning(lastSend: LastSend?) {
    if (lastSend == null) return
    val hours = lastSend.hoursSinceSuccess()
    val text =
        when {
            hours != null && hours >= Every2HoursWebhookWorker.STALE_AFTER_HOURS ->
                stringResource(R.string.last_send_stale, hours)
            lastSend.failureMessage != null ->
                stringResource(R.string.last_send_error, lastSend.failureStatusCode ?: 0, lastSend.failureMessage)
            else -> null
        }
    if (text != null) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private val LAST_SEND_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())

@Composable
fun exportFrequencyDisplayName(freq: ExportFrequency): String =
    when (freq) {
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
    onHourChange: (Int?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val defaultLabel = stringResource(R.string.schedule_time_default)

    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                text = hour?.let { "%02d:00".format(it) } ?: defaultLabel,
                style = MaterialTheme.typography.bodyMedium,
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(defaultLabel) },
                onClick = {
                    expanded = false
                    onHourChange(null)
                },
            )
            (0..23).forEach { h ->
                val label = "%02d:00".format(h)
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onHourChange(h)
                    },
                )
            }
        }
    }
}
