package com.healthconnect.export.data

/**
 * Flattens a [DailyHealthRecord] into a single CSV row.
 *
 * The schema is a fixed list of (column, extractor) pairs, so the header and
 * the row always stay in sync. Missing sections are written as empty cells.
 */
object CsvMapper {
    private val columns: List<Pair<String, (DailyHealthRecord) -> Any?>> =
        listOf(
            "date" to { r -> r.date },
            "steps_total" to { r -> r.steps?.totalSteps },
            "steps_records" to { r -> r.steps?.recordsCount },
            "heart_rate_avg_bpm" to { r -> r.heartRate?.avgBpm },
            "heart_rate_min_bpm" to { r -> r.heartRate?.minBpm },
            "heart_rate_max_bpm" to { r -> r.heartRate?.maxBpm },
            "heart_rate_records" to { r -> r.heartRate?.recordsCount },
            "sleep_total_duration_minutes" to { r -> r.sleep?.totalDurationMinutes },
            "sleep_records" to { r -> r.sleep?.recordsCount },
            "calories_total_kcal" to { r -> r.calories?.totalCalories },
            "calories_records" to { r -> r.calories?.recordsCount },
            "distance_total_meters" to { r -> r.distance?.totalDistanceMeters },
            "distance_records" to { r -> r.distance?.recordsCount },
            "floors_climbed_total" to { r -> r.floorsClimbed?.totalFloors },
            "floors_climbed_records" to { r -> r.floorsClimbed?.recordsCount },
            "active_calories_total_kcal" to { r -> r.activeCalories?.totalCalories },
            "active_calories_records" to { r -> r.activeCalories?.recordsCount },
            "weight_kg" to { r -> r.weight?.weightKg },
            "weight_records" to { r -> r.weight?.recordsCount },
            "body_fat_percentage" to { r -> r.bodyFat?.percentage },
            "body_fat_records" to { r -> r.bodyFat?.recordsCount },
            "blood_pressure_systolic_mmhg" to { r -> r.bloodPressure?.systolicMmHg },
            "blood_pressure_diastolic_mmhg" to { r -> r.bloodPressure?.diastolicMmHg },
            "blood_pressure_records" to { r -> r.bloodPressure?.recordsCount },
            "blood_glucose_level_mmol_per_l" to { r -> r.bloodGlucose?.level },
            "blood_glucose_records" to { r -> r.bloodGlucose?.recordsCount },
            "oxygen_saturation_percentage" to { r -> r.oxygenSaturation?.percentage },
            "oxygen_saturation_records" to { r -> r.oxygenSaturation?.recordsCount },
            "body_temperature_celsius" to { r -> r.bodyTemperature?.temperatureCelsius },
            "body_temperature_records" to { r -> r.bodyTemperature?.recordsCount },
            "respiratory_rate" to { r -> r.respiratoryRate?.rate },
            "respiratory_rate_records" to { r -> r.respiratoryRate?.recordsCount },
            "hydration_total_liters" to { r -> r.hydration?.totalVolumeLiters },
            "hydration_records" to { r -> r.hydration?.recordsCount },
            "resting_heart_rate_avg_bpm" to { r -> r.restingHeartRate?.avgBpm },
            "resting_heart_rate_records" to { r -> r.restingHeartRate?.recordsCount },
            "exercises_count" to { r -> r.exercises?.size },
            "nutrition_count" to { r -> r.nutrition?.size },
            "speed_avg_m_per_s" to { r -> r.speed?.avgSpeedMetersPerSecond },
            "speed_records" to { r -> r.speed?.recordsCount },
            "menstruation_flow" to { r -> r.menstruation?.flowType },
            "menstruation_time" to { r -> r.menstruation?.time },
            "metadata_app_version" to { r -> r.metadata.appVersion },
            "metadata_export_timestamp" to { r -> r.metadata.exportTimestamp },
            "metadata_timezone" to { r -> r.metadata.timezone },
            "metadata_source_device" to { r -> r.metadata.sourceDevice },
        )

    /** CSV header row (column names). */
    fun header(): String = columns.joinToString(",") { it.first }

    /**
     * Converts a [DailyHealthRecord] to a single CSV row (without trailing newline).
     * Values containing commas, quotes or newlines are escaped per RFC 4180.
     */
    fun recordToCsv(record: DailyHealthRecord): String =
        columns.joinToString(",") { (_, extractor) ->
            csvCell(extractor(record))
        }

    private fun csvCell(value: Any?): String {
        if (value == null) return ""
        val raw =
            when (value) {
                is Double -> formatDouble(value)
                is Float -> formatDouble(value.toDouble())
                else -> value.toString()
            }
        return if (raw.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + raw.replace("\"", "\"\"") + "\""
        } else {
            raw
        }
    }

    /** Formats a double without unnecessary trailing zeros (e.g. 72.0 → "72"). */
    private fun formatDouble(value: Double): String {
        if (value == value.toLong().toDouble()) {
            return value.toLong().toString()
        }
        return value.toString()
    }
}
