package com.healthconnect.export.repository

import android.content.Context
import androidx.core.content.ContextCompat
import com.healthconnect.export.data.CsvMapper
import com.healthconnect.export.data.DailyHealthRecord
import com.healthconnect.export.data.ExportConfig
import com.healthconnect.export.data.ExportFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class LocalExportRepository(
    private val context: Context,
) {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    /**
     * Gets the export directory. Tries external documents dir first, falls back to app files.
     */
    fun getExportDirectory(config: ExportConfig): File {
        val baseDir =
            ContextCompat
                .getExternalFilesDirs(context, null)
                .firstOrNull { it != null && (it.exists() || it.mkdirs()) }
                ?: context.filesDir
        val exportDir = File(baseDir, config.outputDirectory)
        if (!exportDir.exists()) exportDir.mkdirs()
        return exportDir
    }

    /**
     * Returns the expected filename for a given date in the given [format].
     */
    fun getFilenameForDate(
        date: LocalDate,
        format: ExportFormat = ExportFormat.JSON,
    ): String {
        val extension =
            when (format) {
                ExportFormat.JSON -> "json"
                ExportFormat.CSV -> "csv"
            }
        return "health_${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}.$extension"
    }

    /**
     * Checks if a day's export already exists in the configured format
     */
    fun isExported(
        date: LocalDate,
        config: ExportConfig,
    ): Boolean {
        val file = File(getExportDirectory(config), getFilenameForDate(date, config.exportFormat))
        return file.exists() && file.length() > 0
    }

    /**
     * Saves a single day's record as a file in the configured format (JSON or CSV).
     * Also removes the counterpart file of the other format, so switching the
     * export format does not leave duplicate files for the same day.
     */
    suspend fun saveDailyRecord(
        record: DailyHealthRecord,
        config: ExportConfig,
    ): File =
        withContext(Dispatchers.IO) {
            val dir = getExportDirectory(config)
            val date = LocalDate.parse(record.date)
            val file = File(dir, getFilenameForDate(date, config.exportFormat))
            val counterpartFormat = if (config.exportFormat == ExportFormat.JSON) ExportFormat.CSV else ExportFormat.JSON
            File(dir, getFilenameForDate(date, counterpartFormat)).delete()
            val content =
                when (config.exportFormat) {
                    ExportFormat.JSON -> json.encodeToString(record)
                    ExportFormat.CSV -> CsvMapper.header() + "\n" + CsvMapper.recordToCsv(record)
                }
            file.writeText(content)
            file
        }

    /**
     * Saves multiple daily records
     */
    suspend fun saveRecords(
        records: List<DailyHealthRecord>,
        config: ExportConfig,
    ): List<File> =
        withContext(Dispatchers.IO) {
            records.map { saveDailyRecord(it, config) }
        }

    /**
     * Lists all exported files (JSON and CSV) with their dates
     */
    fun listExportedFiles(config: ExportConfig): List<Pair<LocalDate, File>> {
        val dir = getExportDirectory(config)
        val files = dir.listFiles { f -> f.name.endsWith(".json") || f.name.endsWith(".csv") }
        if (files == null) return emptyList()
        return files
            .mapNotNull { file ->
                val dateStr =
                    file.name
                        .removePrefix("health_")
                        .removeSuffix(".json")
                        .removeSuffix(".csv")
                try {
                    LocalDate.parse(dateStr) to file
                } catch (_: Exception) {
                    null
                }
            }.sortedBy { it.first }
    }

    /**
     * Deletes exports older than the retention period (in days)
     */
    fun cleanupOldExports(
        daysToKeep: Int,
        config: ExportConfig,
    ) {
        val cutoff = LocalDate.now().minusDays(daysToKeep.toLong())
        listExportedFiles(config).forEach { (date, file) ->
            if (date.isBefore(cutoff)) file.delete()
        }
    }

    /**
     * Deletes all export files (JSON and CSV) for the given date.
     * Returns true if any file was deleted, false if none existed.
     */
    fun deleteExport(
        date: LocalDate,
        config: ExportConfig,
    ): Boolean {
        val dir = getExportDirectory(config)
        val jsonFile = File(dir, getFilenameForDate(date, ExportFormat.JSON))
        val csvFile = File(dir, getFilenameForDate(date, ExportFormat.CSV))
        var deleted = false
        if (jsonFile.exists()) deleted = jsonFile.delete() || deleted
        if (csvFile.exists()) deleted = csvFile.delete() || deleted
        return deleted
    }
}
