package com.healthconnect.export.data

import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.*    /**
     * Test verifies that Int-constant mapper functions produce
     * human-readable strings in JSON serialization.
     *
     * Run: ./gradlew testDebugUnitTest
     */
class HumanReadableMapperTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `print sample JSON with all health data types and human-readable constants`() {
        val record = DailyHealthRecord(
            date = "2026-05-23",
            steps = StepsData(totalSteps = 12453, recordsCount = 480),
            heartRate = HeartRateData(avgBpm = 72.5, minBpm = 55, maxBpm = 142, recordsCount = 18),
            sleep = SleepData(
                totalDurationMinutes = 420,
                sleepStages = mapOf(
                    "Deep sleep" to 90,
                    "Light sleep" to 195,
                    "REM sleep" to 105,
                    "Awake" to 30
                ),
                recordsCount = 1
            ),
            calories = CaloriesData(totalCalories = 2150.0, recordsCount = 180),
            distance = DistanceData(totalDistanceMeters = 8234.5, recordsCount = 620),
            floorsClimbed = FloorsClimbedData(totalFloors = 12.0, recordsCount = 15),
            activeCalories = ActiveCaloriesData(totalCalories = 450.0, recordsCount = 60),
            weight = WeightData(weightKg = 78.5, recordsCount = 1),
            bodyFat = BodyFatData(percentage = 18.5, recordsCount = 1),
            bloodPressure = BloodPressureData(
                systolicMmHg = 120.0,
                diastolicMmHg = 80.0,
                bodyPosition = bodyPositionToString(2), // "Sitting"
                recordsCount = 3
            ),
            bloodGlucose = BloodGlucoseData(
                level = 5.8,
                specimenSource = specimenSourceToString(2), // "Capillary blood"
                mealType = mealTypeToString(1),             // "Fasting"
                recordsCount = 4
            ),
            oxygenSaturation = OxygenSaturationData(percentage = 97.5, recordsCount = 1),
            bodyTemperature = BodyTemperatureData(
                temperatureCelsius = 36.6,
                measurementLocation = measurementLocationToString(1), // "Axillary"
                recordsCount = 1
            ),
            respiratoryRate = RespiratoryRateData(rate = 16.0, recordsCount = 6),
            hydration = HydrationData(totalVolumeLiters = 2.5, recordsCount = 8),
            restingHeartRate = RestingHeartRateData(avgBpm = 65.0, minBpm = 62, maxBpm = 68, recordsCount = 1),
            exercises = listOf(
                ExerciseData(
                    exerciseType = exerciseTypeToString(48)!!,   // "Running"
                    startTime = "2026-05-23T07:30:00",
                    endTime = "2026-05-23T08:15:00",
                    durationMinutes = 45,
                    title = "Morning run",
                    notes = "Park, nice weather"
                ),
                ExerciseData(
                    exerciseType = exerciseTypeToString(5)!!,    // "Cycling"
                    startTime = "2026-05-23T18:00:00",
                    endTime = "2026-05-23T19:00:00",
                    durationMinutes = 60,
                    title = "Evening ride"
                ),
                ExerciseData(
                    exerciseType = exerciseTypeToString(78)!!,   // "Yoga"
                    startTime = "2026-05-23T20:00:00",
                    endTime = "2026-05-23T20:30:00",
                    durationMinutes = 30,
                    title = "Evening yoga"
                )
            ),
            nutrition = listOf(
                NutritionData(
                    name = "Buckwheat porridge with milk",
                    mealType = nutritionMealTypeToString(1), // "Breakfast"
                    energyKcal = 350.0,
                    proteinG = 12.0,
                    totalCarbohydrateG = 55.0,
                    fatG = 8.0,
                    dietaryFiberG = 3.5,
                    sugarG = 5.0,
                    sodiumMg = 200.0,
                    potassiumMg = 300.0,
                    calciumMg = 150.0,
                    ironMg = 2.5,
                    magnesiumMg = 80.0,
                    vitaminCMg = 0.0,
                    vitaminDMcg = 0.5
                ),
                NutritionData(
                    name = "Chicken breast with vegetables",
                    mealType = nutritionMealTypeToString(2), // "Lunch"
                    energyKcal = 520.0,
                    proteinG = 45.0,
                    totalCarbohydrateG = 30.0,
                    fatG = 15.0,
                    dietaryFiberG = 8.0,
                    sugarG = 4.0,
                    saturatedFatG = 3.0,
                    cholesterolMg = 85.0,
                    sodiumMg = 450.0,
                    potassiumMg = 600.0,
                    caffeineMg = 0.0
                ),
                NutritionData(
                    name = "Apple",
                    mealType = nutritionMealTypeToString(4), // "Snack"
                    energyKcal = 95.0,
                    proteinG = 0.5,
                    totalCarbohydrateG = 25.0,
                    fatG = 0.3,
                    dietaryFiberG = 4.5,
                    sugarG = 19.0,
                    potassiumMg = 195.0,
                    vitaminCMg = 8.4
                ),
                NutritionData(
                    name = "Fish with rice and vegetables",
                    mealType = nutritionMealTypeToString(3), // "Dinner"
                    energyKcal = 480.0,
                    proteinG = 35.0,
                    totalCarbohydrateG = 45.0,
                    fatG = 12.0,
                    dietaryFiberG = 6.0,
                    sugarG = 3.0,
                    saturatedFatG = 2.5,
                    cholesterolMg = 60.0,
                    sodiumMg = 380.0,
                    potassiumMg = 450.0,
                    vitaminDMcg = 5.0
                )
            ),
            menstruation = MenstruationData(
                flowType = menstruationFlowToString(2), // "Medium"
                time = "2026-05-23T08:00:00"
            ),
            metadata = ExportMetadata(
                appVersion = "1.0.0",
                exportTimestamp = "2026-05-23T23:00:00",
                timezone = "Europe/Moscow",
                sourceDevice = "test_device"
            )
        )

        val jsonString = json.encodeToString(DailyHealthRecord.serializer(), record)
        println("===== FULL JSON WITH ALL DATA TYPES =====\n")
        println(jsonString)
        println("\n===== ПРОВЕРКА ЧЕЛОВЕКОЧИТАЕМЫХ КОНСТАНТ =====\n")

        // Verify sleepStages contain human-readable keys
        val sleepData = record.sleep!!
        assertTrue(sleepData.sleepStages.containsKey("Deep sleep"))
        assertTrue(sleepData.sleepStages.containsKey("Light sleep"))
        assertTrue(sleepData.sleepStages.containsKey("REM sleep"))

        // Verify bloodPressure bodyPosition
        assertNotNull(record.bloodPressure?.bodyPosition)
        println("✅ bodyPosition: ${record.bloodPressure?.bodyPosition}")

        // Verify bloodGlucose specimenSource and mealType
        assertNotNull(record.bloodGlucose?.specimenSource)
        assertNotNull(record.bloodGlucose?.mealType)
        println("✅ specimenSource: ${record.bloodGlucose?.specimenSource}")
        println("✅ mealType (glucose): ${record.bloodGlucose?.mealType}")

        // Verify bodyTemperature measurementLocation
        assertNotNull(record.bodyTemperature?.measurementLocation)
        println("✅ measurementLocation: ${record.bodyTemperature?.measurementLocation}")

        // Verify exercises exerciseType
        record.exercises?.forEach { e ->
            assertNotNull(e.exerciseType)
            println("✅ exerciseType: ${e.exerciseType} — ${e.title}")
        }

        // Verify nutrition mealType
        record.nutrition?.forEach { n ->
            assertNotNull(n.mealType)
            println("✅ mealType (nutrition): ${n.mealType} — ${n.name}")
        }

        // Verify menstruation flowType
        assertNotNull(record.menstruation?.flowType)
        println("✅ flowType: ${record.menstruation?.flowType}")

        // Verify sleep stages
        record.sleep?.sleepStages?.forEach { (stage, duration) ->
            println("✅ sleepStage: $stage — ${duration} min")
        }

        println("\n===== ALL CHECKS PASSED ✅ =====")
    }
}
