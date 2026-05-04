package com.healthconnect.export.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.healthconnect.export.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class HealthConnectRepository(private val context: Context) {

    companion object {
        private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"
    }

    private var client: HealthConnectClient? = null
        get() {
            if (field == null) {
                try {
                    field = HealthConnectClient.getOrCreate(context)
                } catch (e: Exception) {
                    field = null
                }
            }
            return field
        }

    suspend fun isHealthConnectAvailable(): Boolean {
        return client != null
    }

    /**
     * Проверяет, установлено ли приложение Health Connect на устройстве
     */
    fun isHealthConnectInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(HEALTH_CONNECT_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Создаёт Intent для открытия экрана разрешений Health Connect
     * через ACTION_REQUEST_PERMISSIONS с разрешениями в extra
     */
    fun createHealthPermissionsIntent(permissions: Set<String>): Intent {
        return Intent("androidx.health.ACTION_REQUEST_PERMISSIONS").apply {
            `package` = HEALTH_CONNECT_PACKAGE
            putStringArrayListExtra(
                "androidx.health.extra.PERMISSION_NAMES",
                ArrayList(permissions)
            )
        }
    }

    /**
     * Возвращает ActivityResultContract для запроса разрешений Health Connect
     */
    fun createPermissionRequestContract(): ActivityResultContract<Set<String>, Set<String>> {
        return PermissionController.createRequestPermissionResultContract(context.packageName)
    }

    /**
     * Возвращает набор Health Connect разрешений для указанных типов данных
     */
    fun getPermissionsForTypes(types: Set<HealthDataType>): Set<String> {
        val permissions = mutableSetOf<String>()
        types.forEach { type ->
            when (type) {
                HealthDataType.STEPS -> permissions.add(HealthPermission.getReadPermission(StepsRecord::class))
                HealthDataType.HEART_RATE -> permissions.add(HealthPermission.getReadPermission(HeartRateRecord::class))
                HealthDataType.SLEEP -> permissions.add(HealthPermission.getReadPermission(SleepSessionRecord::class))
                HealthDataType.CALORIES -> permissions.add(HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class))
            }
        }
        return permissions
    }

    /**
     * Проверяет, какие разрешения уже предоставлены
     */
    suspend fun getGrantedPermissions(): Set<String> {
        val c = client ?: return emptySet()
        return c.permissionController.getGrantedPermissions()
    }

    /**
     * Проверяет, предоставлены ли все необходимые разрешения
     */
    suspend fun checkPermissions(types: Set<HealthDataType>): Boolean {
        val c = client ?: return false
        val required = getPermissionsForTypes(types)
        val granted = c.permissionController.getGrantedPermissions()
        return granted.containsAll(required)
    }

    /**
     * Reads all specified health data types for a single day
     */
    suspend fun readDay(
        date: LocalDate,
        types: Set<HealthDataType>
    ): DailyHealthRecord = withContext(Dispatchers.IO) {
        val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val end = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
        val timeFilter = TimeRangeFilter.between(start, end)

        val metadata = ExportMetadata(
            appVersion = "1.0.0",
            exportTimestamp = Instant.now().toDateTimeString(),
            timezone = ZoneId.systemDefault().id,
            sourceDevice = android.os.Build.MODEL
        )

        DailyHealthRecord(
            date = date.toString(),
            steps = if (types.contains(HealthDataType.STEPS)) readSteps(timeFilter) else null,
            heartRate = if (types.contains(HealthDataType.HEART_RATE)) readHeartRate(timeFilter) else null,
            sleep = if (types.contains(HealthDataType.SLEEP)) readSleep(timeFilter) else null,
            calories = if (types.contains(HealthDataType.CALORIES)) readCalories(timeFilter) else null,
            metadata = metadata
        )
    }

    /**
     * Reads multiple days of data
     */
    suspend fun readPeriod(
        startDate: LocalDate,
        endDate: LocalDate,
        types: Set<HealthDataType>
    ): List<DailyHealthRecord> {
        val records = mutableListOf<DailyHealthRecord>()
        var current = startDate
        while (!current.isAfter(endDate)) {
            records.add(readDay(current, types))
            current = current.plusDays(1)
        }
        return records
    }

    // ===== Private readers =====

    private suspend fun readSteps(filter: TimeRangeFilter): StepsData? {
        val c = client ?: return null
        val response = c.readRecords(ReadRecordsRequest(StepsRecord::class, timeRangeFilter = filter))
        if (response.records.isEmpty()) return null
        val total = response.records.sumOf { it.count }
        return StepsData(totalSteps = total, recordsCount = response.records.size)
    }

    private suspend fun readHeartRate(filter: TimeRangeFilter): HeartRateData? {
        val c = client ?: return null
        val response = c.readRecords(ReadRecordsRequest(HeartRateRecord::class, timeRangeFilter = filter))
        if (response.records.isEmpty()) return null
        val samples = response.records.flatMap { it.samples }
        if (samples.isEmpty()) return null
        val beats = samples.map { it.beatsPerMinute }
        return HeartRateData(
            avgBpm = beats.average(),
            minBpm = beats.min().toInt(),
            maxBpm = beats.max().toInt(),
            recordsCount = response.records.size
        )
    }

    private suspend fun readSleep(filter: TimeRangeFilter): SleepData? {
        val c = client ?: return null
        val response = c.readRecords(ReadRecordsRequest(SleepSessionRecord::class, timeRangeFilter = filter))
        if (response.records.isEmpty()) return null
        val totalMinutes = response.records.sumOf {
            ChronoUnit.MINUTES.between(it.startTime, it.endTime)
        }
        return SleepData(
            totalDurationMinutes = totalMinutes,
            sleepStages = emptyMap(),
            recordsCount = response.records.size
        )
    }

    private suspend fun readCalories(filter: TimeRangeFilter): CaloriesData? {
        val c = client ?: return null
        val response = c.readRecords(ReadRecordsRequest(TotalCaloriesBurnedRecord::class, timeRangeFilter = filter))
        if (response.records.isEmpty()) return null
        val total = response.records.sumOf { it.energy.inKilocalories }
        return CaloriesData(totalCalories = total, recordsCount = response.records.size)
    }
}
