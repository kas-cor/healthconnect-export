package com.healthconnect.export.repository

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.healthconnect.export.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class HealthConnectRepository(private val context: Context) {

    companion object {
        private const val TAG = "HealthConnectRepo"
        private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"
        private const val PAGE_SIZE = 10000

        /**
         * Пакеты приложений для фильтрации dataOrigin, упорядоченные по приоритету.
         * Если данные есть от нескольких источников, берётся первый из списка.
         */
        val PREFERRED_PACKAGES = listOf(
            "com.mi.health",              // Xiaomi Mi Fitness
            "com.xiaomi.hm.health",       // Xiaomi Wear / Mi Band
            "com.google.android.apps.fitness", // Google Fit
            "com.samsung.android.wearable.health", // Samsung Health
            "com.huawei.health",          // Huawei Health
            "com.hmdm.wearable.health",   // Nokia Health
            "com.sec.android.app.shealth", // Samsung S Health
            "com.htc.fitness",            // HTC
            "com.sonymobile.advancedwidget.health" // Sony
        )
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

    // ===== Pagination helper =====

    /**
     * Вычитывает ВСЕ записи для указанного типа, используя пагинацию.
     * Стандартный readRecords без pageToken возвращает до 10 000 записей,
     * а на некоторых прошивках (MIUI/HyperOS) может быть меньше (напр. 1000).
     * Чтобы гарантированно получить всё, используем pageToken из ответа.
     */
    private suspend fun <T> readAllPages(request: ReadRecordsRequest<T>): List<T> {
        val c = client ?: return emptyList()
        val allRecords = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val pageRequest = ReadRecordsRequest(
                recordType = request.recordType,
                timeRangeFilter = request.timeRangeFilter,
                dataOriginFilter = request.dataOriginFilter,
                ascendingOrder = request.ascendingOrder,
                pageSize = PAGE_SIZE,
                pageToken = pageToken
            )
            val response = c.readRecords(pageRequest)
            allRecords.addAll(response.records)
            pageToken = response.pageToken
            Log.d(TAG, "readAllPages: got ${allRecords.size} records, pageToken=$pageToken")
        } while (pageToken != null)
        return allRecords
    }

    // ===== Private readers =====

    private suspend fun readSteps(filter: TimeRangeFilter): StepsData? {
        val c = client ?: return null
        val allRecords = readAllPages(ReadRecordsRequest(StepsRecord::class, timeRangeFilter = filter))
        if (allRecords.isEmpty()) return null

        val filtered = filterByPreferredOrigin(allRecords)
        if (filtered.isEmpty()) return null

        val deduped = mergeOverlappingIntervals(filtered)
        val total = deduped.sumOf { it.count }

        Log.d(TAG, "readSteps: raw=${allRecords.size}, filtered=${filtered.size}, deduped=${deduped.size}, totalSteps=$total")
        return StepsData(totalSteps = total, recordsCount = deduped.size)
    }

    /**
     * Объединяет StepsRecord с близкими/перекрывающимися временными интервалами.
     */
    private fun mergeOverlappingIntervals(records: List<StepsRecord>): List<StepsRecord> {
        if (records.size <= 1) return records

        val sorted = records.sortedBy { it.startTime }
        val result = mutableListOf<StepsRecord>()

        var current = sorted.first()
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            val gap = Duration.between(current.endTime, next.startTime)

            if (!gap.isNegative && gap.seconds <= 3) {
                val extendedEnd = if (next.endTime > current.endTime) next.endTime else current.endTime
                current = StepsRecord(
                    count = current.count + next.count,
                    startTime = current.startTime,
                    endTime = extendedEnd
                )
            } else if (gap.isNegative) {
                val extendedEnd = if (next.endTime > current.endTime) next.endTime else current.endTime
                current = StepsRecord(
                    count = current.count + next.count,
                    startTime = current.startTime,
                    endTime = extendedEnd
                )
            } else {
                result.add(current)
                current = next
            }
        }
        result.add(current)

        return result
    }

    /**
     * Фильтрует записи по предпочитаемому пакету-источнику данных.
     * Необходимо, чтобы одни и те же шаги не суммировались от Mi Fitness и Google Fit одновременно.
     */
    private fun <T> filterByPreferredOrigin(records: List<T>): List<T> {
        if (records.isEmpty()) return records

        val groupedByOrigin = records.groupBy { record ->
            val origin = (record as? androidx.health.connect.client.records.Record)?.metadata?.dataOrigin
            origin?.packageName ?: "unknown"
        }

        Log.d(TAG, "filterByPreferredOrigin: sources=${groupedByOrigin.keys}")

        for (preferred in PREFERRED_PACKAGES) {
            if (groupedByOrigin.containsKey(preferred)) {
                Log.d(TAG, "filterByPreferredOrigin: using '$preferred', ignoring ${groupedByOrigin.keys - preferred}")
                return groupedByOrigin[preferred]!!
            }
        }

        Log.d(TAG, "filterByPreferredOrigin: no preferred source, using all (${records.size} records from ${groupedByOrigin.keys})")
        return records
    }

    private suspend fun readHeartRate(filter: TimeRangeFilter): HeartRateData? {
        val c = client ?: return null
        val allRecords = readAllPages(ReadRecordsRequest(HeartRateRecord::class, timeRangeFilter = filter))
        if (allRecords.isEmpty()) return null
        val samples = allRecords.flatMap { it.samples }
        if (samples.isEmpty()) return null
        val beats = samples.map { it.beatsPerMinute }
        return HeartRateData(
            avgBpm = beats.average(),
            minBpm = beats.min().toInt(),
            maxBpm = beats.max().toInt(),
            recordsCount = allRecords.size
        )
    }

    private suspend fun readSleep(filter: TimeRangeFilter): SleepData? {
        val c = client ?: return null
        val allRecords = readAllPages(ReadRecordsRequest(SleepSessionRecord::class, timeRangeFilter = filter))
        if (allRecords.isEmpty()) return null
        val totalMinutes = allRecords.sumOf {
            ChronoUnit.MINUTES.between(it.startTime, it.endTime)
        }
        val stageDurations = mutableMapOf<String, Long>()
        allRecords.forEach { record ->
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
            recordsCount = allRecords.size
        )
    }

    private suspend fun readCalories(filter: TimeRangeFilter): CaloriesData? {
        val c = client ?: return null
        val allRecords = readAllPages(ReadRecordsRequest(TotalCaloriesBurnedRecord::class, timeRangeFilter = filter))
        if (allRecords.isEmpty()) return null
        val filtered = filterByPreferredOrigin(allRecords)
        if (filtered.isEmpty()) return null
        val total = filtered.sumOf { it.energy.inKilocalories }
        return CaloriesData(totalCalories = total, recordsCount = filtered.size)
    }

    private suspend fun readDistance(filter: TimeRangeFilter): DistanceData? {
        val c = client ?: return null
        val allRecords = readAllPages(ReadRecordsRequest(DistanceRecord::class, timeRangeFilter = filter))
        if (allRecords.isEmpty()) return null
        val filtered = filterByPreferredOrigin(allRecords)
        if (filtered.isEmpty()) return null
        val total = filtered.sumOf { it.distance.inMeters }
        return DistanceData(totalDistanceMeters = total, recordsCount = filtered.size)
    }

    private suspend fun readFloorsClimbed(filter: TimeRangeFilter): FloorsClimbedData? {
        val c = client ?: return null
        val allRecords = readAllPages(ReadRecordsRequest(FloorsClimbedRecord::class, timeRangeFilter = filter))
        if (allRecords.isEmpty()) return null
        val filtered = filterByPreferredOrigin(allRecords)
        if (filtered.isEmpty()) return null
        val total = filtered.sumOf { it.floors }
        return FloorsClimbedData(totalFloors = total, recordsCount = filtered.size)
    }

    private suspend fun readActiveCalories(filter: TimeRangeFilter): ActiveCaloriesData? {
        val c = client ?: return null
        val allRecords = readAllPages(ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, timeRangeFilter = filter))
        if (allRecords.isEmpty()) return null
        val filtered = filterByPreferredOrigin(allRecords)
        if (filtered.isEmpty()) return null
        val total = filtered.sumOf { it.energy.inKilocalories }
        return ActiveCaloriesData(totalCalories = total, recordsCount = filtered.size)
    }

    private suspend fun readWeight(filter: TimeRangeFilter): WeightData? {
        val c = client ?: return null
        val allRecords = readAllPages(ReadRecordsRequest(WeightRecord::class, timeRangeFilter = filter))
        if (allRecords.isEmpty()) return null
        // WeightRecord stores weight in kilograms
        val avg = allRecords.map { it.weight.inKilograms }.average()
        return WeightData(weightKg = avg, recordsCount = allRecords.size)
    }

    private suspend fun readBodyFat(filter: TimeRangeFilter): BodyFatData? {
        val c = client ?: return null
        val allRecords = readAllPages(ReadRecordsRequest(BodyFatRecord::class, timeRangeFilter = filter))
        if (allRecords.isEmpty()) return null
        val avg = allRecords.map { it.percentage.value }.average()
        return BodyFatData(percentage = avg, recordsCount = allRecords.size)
    }

    private suspend fun readBloodPressure(filter: TimeRangeFilter): BloodPressureData? {
        val c = client ?: return null
        val allRecords = readAllPages(ReadRecordsRequest(BloodPressureRecord::class, timeRangeFilter = filter))
        if (allRecords.isEmpty()) return null
        val systolicAvg = allRecords.map { it.systolic.inMillimetersOfMercury }.average()
        val diastolicAvg = allRecords.map { it.diastolic.inMillimetersOfMercury }.average()
        val position = bodyPositionToString(allRecords.firstOrNull()?.bodyPosition)
        return BloodPressureData(
            systolicMmHg = systolicAvg,
            diastolicMmHg = diastolicAvg,
            bodyPosition = position,
            recordsCount = allRecords.size
        )
    }

    private suspend fun readBloodGlucose(filter: TimeRangeFilter): BloodGlucoseData? {
        val c = client ?: return null
        val allRecords = readAllPages(ReadRecordsRequest(BloodGlucoseRecord::class, timeRangeFilter = filter))
        if (allRecords.isEmpty()) return null
        val avg = allRecords.map { it.level.inMillimolesPerLiter }.average()
        val source = specimenSourceToString(allRecords.firstOrNull()?.specimenSource)
        val mealType = mealTypeToString(allRecords.firstOrNull()?.mealType)
        return BloodGlucoseData(
            level = avg,
            specimenSource = source,
            mealType = mealType,
            recordsCount = allRecords.size
        )
    }

    private suspend fun readOxygenSaturation(filter: TimeRangeFilter): OxygenSaturationData? {
        val c = client ?: return null
        val allRecords = readAllPages(ReadRecordsRequest(OxygenSaturationRecord::class, timeRangeFilter = filter))
        if (allRecords.isEmpty()) return null
        val avg = allRecords.map { it.percentage.value }.average()
        return OxygenSaturationData(percentage = avg, recordsCount = allRecords.size)
    }

    private suspend fun readBodyTemperature(filter: TimeRangeFilter): BodyTemperatureData? {
        val c = client ?: return null
        val allRecords = readAllPages(ReadRecordsRequest(BodyTemperatureRecord::class, timeRangeFilter = filter))
        if (allRecords.isEmpty()) return null
        val avg = allRecords.map { it.temperature.inCelsius }.average()
        val location = measurementLocationToString(allRecords.firstOrNull()?.measurementLocation)
        return BodyTemperatureData(
            temperatureCelsius = avg,
            measurementLocation = location,
            recordsCount = allRecords.size
        )
    }

    private suspend fun readRespiratoryRate(filter: TimeRangeFilter): RespiratoryRateData? {
        val c = client ?: return null
        val allRecords = readAllPages(ReadRecordsRequest(RespiratoryRateRecord::class, timeRangeFilter = filter))
        if (allRecords.isEmpty()) return null
        val avg = allRecords.map { it.rate }.average()
        return RespiratoryRateData(rate = avg, recordsCount = allRecords.size)
    }

    private suspend fun readHydration(filter: TimeRangeFilter): HydrationData? {
        val c = client ?: return null
        val allRecords = readAllPages(ReadRecordsRequest(HydrationRecord::class, timeRangeFilter = filter))
        if (allRecords.isEmpty()) return null
        val total = allRecords.sumOf { it.volume.inLiters }
        return HydrationData(totalVolumeLiters = total, recordsCount = allRecords.size)
    }

    private suspend fun readRestingHeartRate(filter: TimeRangeFilter): RestingHeartRateData? {
        val c = client ?: return null
        val allRecords = readAllPages(ReadRecordsRequest(RestingHeartRateRecord::class, timeRangeFilter = filter))
        if (allRecords.isEmpty()) return null
        val bpmValues = allRecords.map { it.beatsPerMinute }
        return RestingHeartRateData(
            avgBpm = bpmValues.average(),
            minBpm = bpmValues.min(),
            maxBpm = bpmValues.max(),
            recordsCount = allRecords.size
        )
    }

    private suspend fun readExercises(filter: TimeRangeFilter): List<ExerciseData>? {
        val c = client ?: return null
        val allRecords = readAllPages(ReadRecordsRequest(ExerciseSessionRecord::class, timeRangeFilter = filter))
        if (allRecords.isEmpty()) return null
        return allRecords.map { record ->
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
        val allRecords = readAllPages(ReadRecordsRequest(NutritionRecord::class, timeRangeFilter = filter))
        if (allRecords.isEmpty()) return null
        return allRecords.map { record ->
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

    private suspend fun readSpeed(filter: TimeRangeFilter): SpeedData? {
        val c = client ?: return null
        val allRecords = readAllPages(ReadRecordsRequest(SpeedRecord::class, timeRangeFilter = filter))
        if (allRecords.isEmpty()) return null
        val allSamples = allRecords.flatMap { it.samples }
        if (allSamples.isEmpty()) return null
        val avgSpeed = allSamples.map { it.speed.inMetersPerSecond }.average()
        return SpeedData(
            avgSpeedMetersPerSecond = avgSpeed,
            recordsCount = allRecords.size
        )
    }

    private suspend fun readMenstruation(filter: TimeRangeFilter): MenstruationData? {
        val c = client ?: return null
        val allRecords = readAllPages(ReadRecordsRequest(MenstruationFlowRecord::class, timeRangeFilter = filter))
        if (allRecords.isEmpty()) return null
        val record = allRecords.first()
        return MenstruationData(
            flowType = menstruationFlowToString(record.flow),
            time = record.time.toDateTimeString()
        )
    }
}
