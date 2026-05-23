package com.healthconnect.export.repository

import com.healthconnect.export.data.DailyHealthRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class WebhookRepository {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * Отправляет массив записей на webhook URL через POST с JSON телом
     * @return результат отправки
     */
    suspend fun sendRecords(
        webhookUrl: String,
        records: List<DailyHealthRecord>,
        authToken: String? = null
    ): WebhookResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(webhookUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            if (!authToken.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $authToken")
            }
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000

            val body = json.encodeToString(records)

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(body)
                writer.flush()
            }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            connection.disconnect()

            if (responseCode in 200..299) {
                WebhookResult.Success(
                    statusCode = responseCode,
                    responseBody = responseBody
                )
            } else {
                WebhookResult.Error(
                    statusCode = responseCode,
                    message = "Сервер вернул код $responseCode: $responseBody"
                )
            }
        } catch (e: Exception) {
            WebhookResult.Error(
                statusCode = 0,
                message = e.message ?: "Неизвестная ошибка"
            )
        }
    }

    /**
     * Проверяет, что URL выглядит как валидный webhook
     */
    fun isValidWebhookUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            val parsed = URL(url)
            parsed.protocol in listOf("http", "https") &&
                parsed.host.isNotBlank()
        } catch (e: Exception) {
            false
        }
    }
}

sealed class WebhookResult {
    data class Success(
        val statusCode: Int,
        val responseBody: String
    ) : WebhookResult()

    data class Error(
        val statusCode: Int,
        val message: String
    ) : WebhookResult()
}
