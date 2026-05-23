package com.healthconnect.export.repository

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
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
        private const val TAG = "HealthConnectRepo"
        private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"
    }

    private var client: HealthConnectClient? = null
        get() {
            if (field == null) {
                try {
                    val status = HealthConnectClient.getSdkStatus(context)
                    if (status == HealthConnectClient.SDK_AVAILABLE) {
                        field = HealthConnectClient.getOrCreate(context)
                    }
                } catch (_: Exception) {
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
        } catch (_: Exception) {
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
                HealthDataType.DISTANCE -> permissions.add(HealthPermission.getReadPermission(DistanceRecord::class))
                HealthDataType.FLOORS_CLIMBED -> permissions.add(HealthPermission.getReadPermission(FloorsClimbedRecord::class))
                HealthDataType.ACTIVE_CALORIES -> permissions.add(HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class))
                HealthDataType.WEIGHT -> permissions.add(HealthPermission.getReadPermission(WeightRecord::class))
                HealthDataType.BODY_FAT -> permissions.add(HealthPermission.getReadPermission(BodyFatRecord::class))
                HealthDataType.BLOOD_PRESSURE -> permissions.add(HealthPermission.getReadPermission(BloodPressureRecord::class))
                HealthDataType.BLOOD_GLUCOSE -> permissions.add(HealthPermission.getReadPermission(BloodGlucoseRecord::class))
                HealthDataType.OXYGEN_SATURATION -> permissions.add(HealthPermission.getReadPermission(OxygenSaturationRecord::class))
                HealthDataType.BODY_TEMPERATURE -> permissions.add(HealthPermission.getReadPermission(BodyTemperatureRecord::class))
                HealthDataType.RESPIRATORY_RATE -> permissions.add(HealthPermission.getReadPermission(RespiratoryRateRecord::class))
                HealthDataType.HYDRATION -> permissions.add(HealthPermission.getReadPermission(HydrationRecord::class))
                HealthDataType.RESTING_HEART_RATE -> permissions.add(HealthPermission.getReadPermission(RestingHeartRateRecord::class))
                HealthDataType.EXERCISE -> permissions.add(HealthPermission.getReadPermission(ExerciseSessionRecord::class))
                HealthDataType.NUTRITION -> permissions.add(HealthPermission.getReadPermission(NutritionRecord::class))
                HealthDataType.MENSTRUATION -> permissions.add(HealthPermission.getReadPermission(MenstruationFlowRecord::class))
                HealthDataType.CARDIOVASCULAR -> permissions.add(HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class))
                HealthDataType.SPEED -> permissions.add(HealthPermission.getReadPermission(SpeedRecord::class))
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
     * Проверяет, предоставлены ли все необходимые разрешения для указанных типов
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
            distance = if (types.contains(HealthDataType.DISTANCE)) readDistance(timeFilter) else null,
            floorsClimbed = if (types.contains(HealthDataType.FLOORS_CLIMBED)) readFloorsClimbed(timeFilter) else null,
            activeCalories = if (types.contains(HealthDataType.ACTIVE_CALORIES)) readActiveCalories(timeFilter) else null,
            weight = if (types.contains(HealthDataType.WEIGHT)) readWeight(timeFilter) else null,
            bodyFat = if (types.contains(HealthDataType.BODY_FAT)) readBodyFat(timeFilter) else null,
            bloodPressure = if (types.contains(HealthDataType.BLOOD_PRESSURE)) readBloodPressure(timeFilter) else null,
            bloodGlucose = if (types.contains(HealthDataType.BLOOD_GLUCOSE)) readBloodGlucose(timeFilter) else null,
            oxygenSaturation = if (types.contains(HealthDataType.OXYGEN_SATURATION)) readOxygenSaturation(timeFilter) else null,
            bodyTemperature = if (types.contains(HealthDataType.BODY_TEMPERATURE)) readBodyTemperature(timeFilter) else null,
            respiratoryRate = if (types.contains(HealthDataType.RESPIRATORY_RATE)) readRespiratoryRate(timeFilter) else null,
            hydration = if (types.contains(HealthDataType.HYDRATION)) readHydration(timeFilter) else null,
            restingHeartRate = if (types.contains(HealthDataType.RESTING_HEART_RATE)) readRestingHeartRate(timeFilter) else null,
            exercises = if (types.contains(HealthDataType.EXERCISE)) readExercises(timeFilter) else null,
            nutrition = if (types.contains(HealthDataType.NUTRITION)) readNutrition(timeFilter) else null,
            cardiovascular = if (types.contains(HealthDataType.CARDIOVASCULAR)) readCardiovascular(timeFilter) else null,
            speed = if (types.contains(HealthDataType.SPEED)) readSpeed(timeFilter) else null,
            menstruation = if (types.contains(HealthDataType.MENSTRUATION)) readMenstruation(timeFilter) else null,
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
        val stageDurations = mutableMapOf<String, Long>()
        response.records.forEach { record ->
            record.stages.forEach { stage ->
                val name = sleepStageToString(stage.stage) ?: "Неизвестно"
                val duration = ChronoUnit.MINUTES.between(stage.startTime, stage.endTime)
                if (duration > 0) {
                    stageDurations[name] = (stageDurations[name] ?: 0) + duration
                }
            }
        }
        return SleepData(
            totalDurationMinutes = totalMinutes,
            sleepStages = stageDurations,
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

    private suspend fun readDistance(filter: TimeRangeFilter): DistanceData? {
        val c = client ?: return null
        val response = c.readRecords(ReadRecordsRequest(DistanceRecord::class, timeRangeFilter = filter))
        if (response.records.isEmpty()) return null
        val total = response.records.sumOf { it.distance.inMeters }
        return DistanceData(totalDistanceMeters = total, recordsCount = response.records.size)
    }

    private suspend fun readFloorsClimbed(filter: TimeRangeFilter): FloorsClimbedData? {
        val c = client ?: return null
        val response = c.readRecords(ReadRecordsRequest(FloorsClimbedRecord::class, timeRangeFilter = filter))
        if (response.records.isEmpty()) return null
        val total = response.records.sumOf { it.floors }
        return FloorsClimbedData(totalFloors = total, recordsCount = response.records.size)
    }

    private suspend fun readActiveCalories(filter: TimeRangeFilter): ActiveCaloriesData? {
        val c = client ?: return null
        val response = c.readRecords(ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, timeRangeFilter = filter))
        if (response.records.isEmpty()) return null
        val total = response.records.sumOf { it.energy.inKilocalories }
        return ActiveCaloriesData(totalCalories = total, recordsCount = response.records.size)
    }

    private suspend fun readWeight(filter: TimeRangeFilter): WeightData? {
        val c = client ?: return null
        val response = c.readRecords(ReadRecordsRequest(WeightRecord::class, timeRangeFilter = filter))
        if (response.records.isEmpty()) return null
        // WeightRecord stores weight in kilograms
        val avg = response.records.map { it.weight.inKilograms }.average()
        return WeightData(weightKg = avg, recordsCount = response.records.size)
    }

    private suspend fun readBodyFat(filter: TimeRangeFilter): BodyFatData? {
        val c = client ?: return null
        val response = c.readRecords(ReadRecordsRequest(BodyFatRecord::class, timeRangeFilter = filter))
        if (response.records.isEmpty()) return null
        val avg = response.records.map { it.percentage.value }.average()
        return BodyFatData(percentage = avg, recordsCount = response.records.size)
    }

    private suspend fun readBloodPressure(filter: TimeRangeFilter): BloodPressureData? {
        val c = client ?: return null
        val response = c.readRecords(ReadRecordsRequest(BloodPressureRecord::class, timeRangeFilter = filter))
        if (response.records.isEmpty()) return null
        val systolicAvg = response.records.map { it.systolic.inMillimetersOfMercury }.average()
        val diastolicAvg = response.records.map { it.diastolic.inMillimetersOfMercury }.average()
        val position = bodyPositionToString(response.records.firstOrNull()?.bodyPosition)
        return BloodPressureData(
            systolicMmHg = systolicAvg,
            diastolicMmHg = diastolicAvg,
            bodyPosition = position,
            recordsCount = response.records.size
        )
    }

    private suspend fun readBloodGlucose(filter: TimeRangeFilter): BloodGlucoseData? {
        val c = client ?: return null
        val response = c.readRecords(ReadRecordsRequest(BloodGlucoseRecord::class, timeRangeFilter = filter))
        if (response.records.isEmpty()) return null
        val avg = response.records.map { it.level.inMillimolesPerLiter }.average()
        val source = specimenSourceToString(response.records.firstOrNull()?.specimenSource)
        val mealType = mealTypeToString(response.records.firstOrNull()?.mealType)
        return BloodGlucoseData(
            level = avg,
            specimenSource = source,
            mealType = mealType,
            recordsCount = response.records.size
        )
    }

    private suspend fun readOxygenSaturation(filter: TimeRangeFilter): OxygenSaturationData? {
        val c = client ?: return null
        val response = c.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, timeRangeFilter = filter))
        if (response.records.isEmpty()) return null
        val avg = response.records.map { it.percentage.value }.average()
        return OxygenSaturationData(percentage = avg, recordsCount = response.records.size)
    }

    private suspend fun readBodyTemperature(filter: TimeRangeFilter): BodyTemperatureData? {
        val c = client ?: return null
        val response = c.readRecords(ReadRecordsRequest(BodyTemperatureRecord::class, timeRangeFilter = filter))
        if (response.records.isEmpty()) return null
        val avg = response.records.map { it.temperature.inCelsius }.average()
        val location = measurementLocationToString(response.records.firstOrNull()?.measurementLocation)
        return BodyTemperatureData(
            temperatureCelsius = avg,
            measurementLocation = location,
            recordsCount = response.records.size
        )
    }

    private suspend fun readRespiratoryRate(filter: TimeRangeFilter): RespiratoryRateData? {
        val c = client ?: return null
        val response = c.readRecords(ReadRecordsRequest(RespiratoryRateRecord::class, timeRangeFilter = filter))
        if (response.records.isEmpty()) return null
        val avg = response.records.map { it.rate }.average()
        return RespiratoryRateData(rate = avg, recordsCount = response.records.size)
    }

    private suspend fun readHydration(filter: TimeRangeFilter): HydrationData? {
        val c = client ?: return null
        val response = c.readRecords(ReadRecordsRequest(HydrationRecord::class, timeRangeFilter = filter))
        if (response.records.isEmpty()) return null
        val total = response.records.sumOf { it.volume.inLiters }
        return HydrationData(totalVolumeLiters = total, recordsCount = response.records.size)
    }

    private suspend fun readRestingHeartRate(filter: TimeRangeFilter): RestingHeartRateData? {
        val c = client ?: return null
        val response = c.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, timeRangeFilter = filter))
        if (response.records.isEmpty()) return null
        val bpmValues = response.records.map { it.beatsPerMinute }
        return RestingHeartRateData(
            avgBpm = bpmValues.average(),
            minBpm = bpmValues.min(),
            maxBpm = bpmValues.max(),
            recordsCount = response.records.size
        )
    }

    private suspend fun readExercises(filter: TimeRangeFilter): List<ExerciseData>? {
        val c = client ?: return null
        val response = c.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, timeRangeFilter = filter))
        if (response.records.isEmpty()) return null
        return response.records.map { record ->
            ExerciseData(
                exerciseType = exerciseTypeToString(record.exerciseType) ?: "Неизвестно",
                startTime = record.startTime.toDateTimeString(),
                endTime = record.endTime.toDateTimeString(),
                durationMinutes = ChronoUnit.MINUTES.between(record.startTime, record.endTime),
                title = record.title,
                notes = record.notes
            )
        }
    }

    private suspend fun readNutrition(filter: TimeRangeFilter): List<NutritionData>? {
        val c = client ?: return null
        val response = c.readRecords(ReadRecordsRequest(NutritionRecord::class, timeRangeFilter = filter))
        if (response.records.isEmpty()) return null
        return response.records.map { record ->
            NutritionData(
                name = record.name,
                mealType = nutritionMealTypeToString(record.mealType),
                energyKcal = record.energy?.inKilocalories,
                proteinG = record.protein?.inGrams,
                totalCarbohydrateG = record.totalCarbohydrate?.inGrams,
                fatG = record.totalFat?.inGrams,
                dietaryFiberG = record.dietaryFiber?.inGrams,
                sugarG = record.sugar?.inGrams,
                saturatedFatG = record.saturatedFat?.inGrams,
                transFatG = record.transFat?.inGrams,
                cholesterolMg = record.cholesterol?.inMilligrams,
                sodiumMg = record.sodium?.inMilligrams,
                potassiumMg = record.potassium?.inMilligrams,
                caffeineMg = record.caffeine?.inMilligrams,
                calciumMg = record.calcium?.inMilligrams,
                ironMg = record.iron?.inMilligrams,
                magnesiumMg = record.magnesium?.inMilligrams,
                vitaminCMg = record.vitaminC?.inMilligrams,
                vitaminDMcg = record.vitaminD?.inMicrograms
            )
        }
    }

    private suspend fun readCardiovascular(filter: TimeRangeFilter): CardiovascularData? {
        val c = client ?: return null
        val response = c.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, timeRangeFilter = filter))
        if (response.records.isEmpty()) return null
        val avgHrv = response.records.map { it.heartRateVariabilityMillis }.average()
        return CardiovascularData(
            hrvRmssdMs = avgHrv,
            recordsCount = response.records.size
        )
    }

    private suspend fun readSpeed(filter: TimeRangeFilter): SpeedData? {
        val c = client ?: return null
        val response = c.readRecords(ReadRecordsRequest(SpeedRecord::class, timeRangeFilter = filter))
        if (response.records.isEmpty()) return null
        val allSamples = response.records.flatMap { it.samples }
        if (allSamples.isEmpty()) return null
        val avgSpeed = allSamples.map { it.speed.inMetersPerSecond }.average()
        return SpeedData(
            avgSpeedMetersPerSecond = avgSpeed,
            recordsCount = response.records.size
        )
    }

    private suspend fun readMenstruation(filter: TimeRangeFilter): MenstruationData? {
        val c = client ?: return null
        val response = c.readRecords(ReadRecordsRequest(MenstruationFlowRecord::class, timeRangeFilter = filter))
        if (response.records.isEmpty()) return null
        val record = response.records.first()
        return MenstruationData(
            flowType = menstruationFlowToString(record.flow),
            time = record.time.toDateTimeString()
        )
    }
}
