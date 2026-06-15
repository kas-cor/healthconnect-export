package com.healthconnect.export.repository

import android.util.Log
import com.healthconnect.export.data.DailyHealthRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class WebhookPayload(
    val messages: List<DailyHealthRecord>
)

class WebhookRepository {

    companion object {
        private const val TAG = "WebhookRepo"
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * Отправляет массив записей на webhook URL через POST с JSON телом
     * с автоматическим retry при временных ошибках (1 повтор через 1 секунду).
     * @return результат отправки
     */
    suspend fun sendRecords(
        webhookUrl: String,
        records: List<DailyHealthRecord>,
        authToken: String? = null
    ): WebhookResult = withContext(Dispatchers.IO) {
        val maxAttempts = 2
        var lastResult: WebhookResult? = null
        for (attempt in 1..maxAttempts) {
            lastResult = trySend(webhookUrl, records, authToken)
            when (lastResult) {
                is WebhookResult.Success -> return@withContext lastResult
                is WebhookResult.Error -> {
                    val error = lastResult as WebhookResult.Error
                    // Retry only on network/timeout/server errors (status 0 = connection error, 5xx = server error)
                    if (attempt < maxAttempts && (error.statusCode == 0 || error.statusCode in 500..599)) {
                        Log.w(TAG, "sendRecords attempt $attempt failed (${error.statusCode}), retrying...")
                        delay(1000)
                    } else {
                        return@withContext lastResult
                    }
                }
            }
        }
        lastResult ?: WebhookResult.Error(0, "No result from sendRecords")
    }

    private suspend fun trySend(
        webhookUrl: String,
        records: List<DailyHealthRecord>,
        authToken: String?
    ): WebhookResult {
        return try {
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

            val body = json.encodeToString(WebhookPayload(records))

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
