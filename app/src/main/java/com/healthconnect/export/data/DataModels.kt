package com.healthconnect.export.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ===== Core data classes for Health Connect =====

@Serializable
data class DailyHealthRecord(
    @SerialName("date") val date: String,
    @SerialName("steps") val steps: StepsData? = null,
    @SerialName("heart_rate") val heartRate: HeartRateData? = null,
    @SerialName("sleep") val sleep: SleepData? = null,
    @SerialName("calories") val calories: CaloriesData? = null,
    @SerialName("metadata") val metadata: ExportMetadata
)

@Serializable
data class StepsData(
    @SerialName("total_steps") val totalSteps: Long,
    @SerialName("records_count") val recordsCount: Int
)

@Serializable
data class HeartRateData(
    @SerialName("avg_bpm") val avgBpm: Double,
    @SerialName("min_bpm") val minBpm: Int,
    @SerialName("max_bpm") val maxBpm: Int,
    @SerialName("records_count") val recordsCount: Int
)

@Serializable
data class SleepData(
    @SerialName("total_duration_minutes") val totalDurationMinutes: Long,
    @SerialName("sleep_stages") val sleepStages: Map<String, Long>,
    @SerialName("records_count") val recordsCount: Int
)

@Serializable
data class CaloriesData(
    @SerialName("total_calories_kcal") val totalCalories: Double,
    @SerialName("records_count") val recordsCount: Int
)

@Serializable
data class ExportMetadata(
    @SerialName("app_version") val appVersion: String,
    @SerialName("export_timestamp") val exportTimestamp: String,
    @SerialName("timezone") val timezone: String,
    @SerialName("source_device") val sourceDevice: String? = null
)

// ===== Export Configuration =====

enum class ExportFrequency(val displayName: String, val hours: Long) {
    MANUAL("Вручную", 0),
    DAILY("Раз в день", 24),
    WEEKLY("Раз в неделю", 168)
}

data class ExportConfig(
    val enabledTypes: Set<HealthDataType>,
    val frequency: ExportFrequency,
    val autoSyncDrive: Boolean,
    val webhookUrl: String = "",
    val autoSendWebhook: Boolean = false,
    val outputDirectory: String = "HealthConnectExport"
)

enum class HealthDataType(val displayName: String) {
    STEPS("Шаги"),
    HEART_RATE("Пульс"),
    SLEEP("Сон"),
    CALORIES("Калории")
}

// ===== Helpers =====

fun Instant.toDateString(): String =
    DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault()).format(this)

fun Instant.toDateTimeString(): String =
    DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault()).format(this)
