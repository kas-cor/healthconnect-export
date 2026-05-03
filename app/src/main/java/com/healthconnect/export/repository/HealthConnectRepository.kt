package com.healthconnect.export.repository

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
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

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun checkPermissions(): Boolean {
        // Requires user to grant permissions in Health Connect app
        return true // Simplified - actual check uses requestPermissionActivityContract
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
        val response = client.readRecords(ReadRecordsRequest(StepsRecord::class, timeRangeFilter = filter))
        if (response.records.isEmpty()) return null
        val total = response.records.sumOf { it.count }
        return StepsData(totalSteps = total, recordsCount = response.records.size)
    }

    private suspend fun readHeartRate(filter: TimeRangeFilter): HeartRateData? {
        val response = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, timeRangeFilter = filter))
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
        val response = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, timeRangeFilter = filter))
        if (response.records.isEmpty()) return null
        val totalMinutes = response.records.sumOf {
            ChronoUnit.MINUTES.between(it.startTime, it.endTime)
        }
        val stages = mutableMapOf<String, Long>()
        response.records.forEach { record ->
            record.stages?.forEach { stage ->
                val key = stage.type.toString()
                val minutes = ChronoUnit.MINUTES.between(stage.startTime, stage.endTime)
                stages[key] = (stages[key] ?: 0) + minutes
            }
        }
        return SleepData(
            totalDurationMinutes = totalMinutes,
            sleepStages = stages,
            recordsCount = response.records.size
        )
    }

    private suspend fun readCalories(filter: TimeRangeFilter): CaloriesData? {
        val response = client.readRecords(ReadRecordsRequest(TotalCaloriesBurnedRecord::class, timeRangeFilter = filter))
        if (response.records.isEmpty()) return null
        val total = response.records.sumOf { it.energy.inKilocalories }
        return CaloriesData(totalCalories = total, recordsCount = response.records.size)
    }
}
