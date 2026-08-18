package com.archivesaver.frontend_flutter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ArchiveSaveWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        val pageUrl = inputData.getString(KEY_URL) ?: return failure("저장할 URL이 없습니다.")
        val accessToken = inputData.getString(KEY_ACCESS_TOKEN)
            ?: return failure("로그인 정보가 없습니다.")
        val backendUrl = inputData.getString(KEY_BACKEND_URL)?.trimEnd('/')
            ?: return failure("백엔드 주소가 없습니다.")
        val payloadPath = inputData.getString(KEY_PAYLOAD_PATH)
        val screenshotPath = inputData.getString(KEY_SCREENSHOT_PATH)

        setForegroundAsync(createForegroundInfo(pageUrl)).get()

        return try {
            val isWebUrl = pageUrl.startsWith("https://") || pageUrl.startsWith("http://")
            if (isWebUrl) {
                val htmlResponse = postHtml(backendUrl, accessToken, pageUrl, payloadPath)
                if (htmlResponse.statusCode in 200..299) {
                    cleanupScreenshot(screenshotPath)
                    return success(htmlResponse, "전체 페이지가 저장되었습니다.")
                }
                if (htmlResponse.statusCode == 401) {
                    cleanupScreenshot(screenshotPath)
                    return failure("로그인 세션이 만료되었습니다. 앱을 열어 다시 시도해주세요.")
                }
                if (htmlResponse.statusCode >= 500 && runAttemptCount < MAX_RETRIES) {
                    return Result.retry()
                }

                // 단축키 저장은 HTML 보안 차단 시 당시 화면을 자동으로 대체 저장합니다.
                if (screenshotPath != null) {
                    val screenshotResponse = postScreenshot(
                        backendUrl,
                        accessToken,
                        File(screenshotPath),
                    )
                    return handleScreenshotResponse(screenshotResponse, screenshotPath)
                }

                if (htmlResponse.statusCode == 409) {
                    return Result.failure(
                        Data.Builder()
                            .putBoolean(KEY_NEEDS_VERIFICATION, true)
                            .putString(KEY_ERROR, responseError(htmlResponse, "보안 확인이 필요합니다."))
                            .build(),
                    )
                }
                return failure(responseError(htmlResponse, "서버 오류(${htmlResponse.statusCode})"))
            }

            if (screenshotPath != null) {
                val screenshotResponse = postScreenshot(
                    backendUrl,
                    accessToken,
                    File(screenshotPath),
                )
                handleScreenshotResponse(screenshotResponse, screenshotPath)
            } else {
                failure("저장할 화면 또는 URL이 없습니다.")
            }
        } catch (error: Exception) {
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                cleanupScreenshot(screenshotPath)
                failure("네트워크 오류: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private data class HttpResult(val statusCode: Int, val body: String)

    private fun postHtml(
        backendUrl: String,
        accessToken: String,
        pageUrl: String,
        payloadPath: String?,
    ): HttpResult {
        val connection = openConnection("$backendUrl/api/save-html", accessToken)
        return try {
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(
                    payloadPath?.let { File(it).readText(Charsets.UTF_8) }
                        ?: JSONObject().put("url", pageUrl).toString(),
                )
            }
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun postScreenshot(
        backendUrl: String,
        accessToken: String,
        screenshot: File,
    ): HttpResult {
        val connection = openConnection("$backendUrl/api/save-screenshot", accessToken)
        return try {
            writeScreenshotRequest(connection, screenshot)
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, accessToken: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 20 * 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Bypass-Tunnel-Reminder", "true")
        }

    private fun readResponse(connection: HttpURLConnection): HttpResult {
        val statusCode = connection.responseCode
        val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        val body = runCatching {
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        }.getOrDefault("")
        return HttpResult(statusCode, body)
    }

    private fun handleScreenshotResponse(response: HttpResult, screenshotPath: String): Result {
        return when {
            response.statusCode in 200..299 -> {
                cleanupScreenshot(screenshotPath)
                success(response, "현재 화면이 저장되었습니다.")
            }
            response.statusCode == 401 -> {
                cleanupScreenshot(screenshotPath)
                failure("로그인 세션이 만료되었습니다. 앱을 열어 다시 시도해주세요.")
            }
            response.statusCode >= 500 && runAttemptCount < MAX_RETRIES -> Result.retry()
            else -> {
                cleanupScreenshot(screenshotPath)
                failure(responseError(response, "화면 저장에 실패했습니다."))
            }
        }
    }

    private fun success(response: HttpResult, fallback: String): Result {
        val message = runCatching { JSONObject(response.body).optString("message") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: fallback
        return Result.success(Data.Builder().putString(KEY_MESSAGE, message).build())
    }

    private fun responseError(response: HttpResult, fallback: String): String =
        runCatching { JSONObject(response.body).optString("error") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: fallback

    private fun cleanupScreenshot(path: String?) {
        path?.let { runCatching { File(it).delete() } }
    }

    private fun writeScreenshotRequest(connection: HttpURLConnection, screenshot: File) {
        if (!screenshot.exists()) throw IllegalStateException("캡처 파일을 찾을 수 없습니다.")
        val boundary = "ArchiveSaver${System.currentTimeMillis()}"
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.setChunkedStreamingMode(256 * 1024)
        DataOutputStream(connection.outputStream).use { output ->
            fun writeTextField(name: String, value: String) {
                output.writeBytes("--$boundary\r\n")
                output.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                output.write(value.toByteArray(Charsets.UTF_8))
                output.writeBytes("\r\n")
            }
            writeTextField("sourcePackage", inputData.getString(KEY_SOURCE_PACKAGE) ?: "unknown")
            writeTextField("appLabel", inputData.getString(KEY_APP_LABEL) ?: "화면 캡처")
            writeTextField("capturedAt", inputData.getString(KEY_CAPTURED_AT) ?: "")
            output.writeBytes("--$boundary\r\n")
            output.writeBytes(
                "Content-Disposition: form-data; name=\"screenshot\"; filename=\"screen.png\"\r\n",
            )
            output.writeBytes("Content-Type: image/png\r\n\r\n")
            screenshot.inputStream().use { input -> input.copyTo(output) }
            output.writeBytes("\r\n--$boundary--\r\n")
            output.flush()
        }
    }

    private fun failure(message: String): Result = Result.failure(
        Data.Builder().putString(KEY_ERROR, message.take(1_000)).build(),
    )

    private fun createForegroundInfo(pageUrl: String): ForegroundInfo {
        createNotificationChannel()
        val openAppIntent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            id.hashCode(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val host = runCatching { URL(pageUrl).host }.getOrDefault(pageUrl)
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("페이지 아카이빙 중")
            .setContentText(host)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                id.hashCode(),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(id.hashCode(), notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "페이지 저장",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "백그라운드 페이지 저장 진행 상태"
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val TAG = "archive_save_work"
        const val QUEUE_NAME = "archive_save_queue"
        const val PREFERENCES_NAME = "archive_save_jobs"
        const val KEY_URL = "url"
        const val KEY_ACCESS_TOKEN = "accessToken"
        const val KEY_BACKEND_URL = "backendUrl"
        const val KEY_CREATED_AT = "createdAt"
        const val KEY_PAYLOAD_PATH = "payloadPath"
        const val KEY_SCREENSHOT_PATH = "screenshotPath"
        const val KEY_SOURCE_PACKAGE = "sourcePackage"
        const val KEY_APP_LABEL = "appLabel"
        const val KEY_CAPTURED_AT = "capturedAt"
        const val KEY_MESSAGE = "message"
        const val KEY_ERROR = "error"
        const val KEY_NEEDS_VERIFICATION = "needsVerification"
        private const val NOTIFICATION_CHANNEL_ID = "archive_save_progress"
        private const val MAX_RETRIES = 2
    }
}
