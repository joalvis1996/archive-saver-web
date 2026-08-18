package com.archivesaver.frontend_flutter

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            BACKGROUND_ARCHIVE_CHANNEL,
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "enqueueArchive" -> {
                    val url = call.argument<String>("url")
                    val accessToken = call.argument<String>("accessToken")
                    val backendUrl = call.argument<String>("backendUrl")
                    val createdAt = call.argument<Number>("createdAt")?.toLong()
                        ?: System.currentTimeMillis()

                    if (url.isNullOrBlank() || accessToken.isNullOrBlank() || backendUrl.isNullOrBlank()) {
                        result.error("INVALID_ARGUMENT", "저장 작업 정보가 올바르지 않습니다.", null)
                        return@setMethodCallHandler
                    }

                    requestNotificationPermissionIfNeeded()
                    result.success(enqueueArchive(url, accessToken, backendUrl, createdAt))
                }

                "enqueueCapturedArchive" -> {
                    val url = call.argument<String>("url")
                    val html = call.argument<String>("html")
                    val accessToken = call.argument<String>("accessToken")
                    val backendUrl = call.argument<String>("backendUrl")
                    val createdAt = call.argument<Number>("createdAt")?.toLong()
                        ?: System.currentTimeMillis()
                    if (
                        url.isNullOrBlank() || html.isNullOrBlank() ||
                        accessToken.isNullOrBlank() || backendUrl.isNullOrBlank()
                    ) {
                        result.error("INVALID_ARGUMENT", "캡처 저장 작업 정보가 올바르지 않습니다.", null)
                        return@setMethodCallHandler
                    }
                    requestNotificationPermissionIfNeeded()
                    executor.execute {
                        try {
                            val payloadFile = File(
                                noBackupFilesDir,
                                "archive_payload_${UUID.randomUUID()}.json",
                            )
                            payloadFile.writeText(
                                JSONObject()
                                    .put("url", url)
                                    .put("html", html)
                                    .put("clientCaptureMode", "flutter-webview")
                                    .toString(),
                                Charsets.UTF_8,
                            )
                            val id = enqueueArchive(
                                url,
                                accessToken,
                                backendUrl,
                                createdAt,
                                payloadFile.absolutePath,
                            )
                            runOnUiThread { result.success(id) }
                        } catch (error: Exception) {
                            runOnUiThread {
                                result.error("CAPTURE_QUEUE_FAILED", error.message, null)
                            }
                        }
                    }
                }

                "configureScreenCapture" -> {
                    val accessToken = call.argument<String>("accessToken")
                    val backendUrl = call.argument<String>("backendUrl")
                    if (accessToken.isNullOrBlank() || backendUrl.isNullOrBlank()) {
                        result.error("INVALID_ARGUMENT", "화면 저장 설정이 올바르지 않습니다.", null)
                    } else {
                        archivePreferences().edit()
                            .putString(
                                ScreenCaptureAccessibilityService.CONFIG_ACCESS_TOKEN,
                                accessToken,
                            )
                            .putString(
                                ScreenCaptureAccessibilityService.CONFIG_BACKEND_URL,
                                backendUrl,
                            )
                            .apply()
                        result.success(isScreenCaptureServiceEnabled())
                    }
                }

                "isScreenCaptureEnabled" -> result.success(isScreenCaptureServiceEnabled())

                "openAccessibilitySettings" -> {
                    requestNotificationPermissionIfNeeded()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    result.success(null)
                }

                "getArchiveJobs" -> executor.execute {
                    try {
                        val workManager = WorkManager.getInstance(applicationContext)
                        val jobs = workManager.getWorkInfosByTag(ArchiveSaveWorker.TAG).get()
                            .mapNotNull(::workInfoToMap)
                        runOnUiThread { result.success(jobs) }
                    } catch (error: Exception) {
                        runOnUiThread {
                            result.error("WORK_QUERY_FAILED", error.message, null)
                        }
                    }
                }

                "clearFinishedArchiveJobs" -> executor.execute {
                    try {
                        val workManager = WorkManager.getInstance(applicationContext)
                        val finishedIds = workManager
                            .getWorkInfosByTag(ArchiveSaveWorker.TAG)
                            .get()
                            .filter { it.state.isFinished }
                            .map { it.id.toString() }
                        val editor = archivePreferences().edit()
                        finishedIds.forEach { id ->
                            archivePreferences().getString("$id.payloadPath", null)?.let { path ->
                                runCatching { File(path).delete() }
                            }
                            editor.remove("$id.url")
                            editor.remove("$id.createdAt")
                            editor.remove("$id.payloadPath")
                            archivePreferences().getString("$id.screenshotPath", null)?.let { path ->
                                runCatching { File(path).delete() }
                            }
                            editor.remove("$id.screenshotPath")
                        }
                        editor.apply()
                        runOnUiThread { result.success(null) }
                    } catch (error: Exception) {
                        runOnUiThread {
                            result.error("WORK_CLEAR_FAILED", error.message, null)
                        }
                    }
                }

                else -> result.notImplemented()
            }
        }
    }

    private fun enqueueArchive(
        url: String,
        accessToken: String,
        backendUrl: String,
        createdAt: Long,
        payloadPath: String? = null,
    ): String {
        val request = OneTimeWorkRequestBuilder<ArchiveSaveWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setInputData(
                workDataOf(
                    ArchiveSaveWorker.KEY_URL to url,
                    ArchiveSaveWorker.KEY_ACCESS_TOKEN to accessToken,
                    ArchiveSaveWorker.KEY_BACKEND_URL to backendUrl,
                    ArchiveSaveWorker.KEY_CREATED_AT to createdAt,
                    ArchiveSaveWorker.KEY_PAYLOAD_PATH to payloadPath,
                ),
            )
            .addTag(ArchiveSaveWorker.TAG)
            .build()
        archivePreferences().edit()
            .putString("${request.id}.url", url)
            .putLong("${request.id}.createdAt", createdAt)
            .apply {
                if (payloadPath != null) putString("${request.id}.payloadPath", payloadPath)
            }
            .apply()
        WorkManager.getInstance(applicationContext)
            .beginUniqueWork(
                ArchiveSaveWorker.QUEUE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
            .enqueue()
        return request.id.toString()
    }

    private fun workInfoToMap(info: WorkInfo): Map<String, Any?>? {
        val id = info.id.toString()
        val preferences = archivePreferences()
        val url = preferences.getString("$id.url", null) ?: return null
        val output = info.outputData
        return mapOf(
            "id" to id,
            "url" to url,
            "createdAt" to preferences.getLong("$id.createdAt", 0L),
            "state" to info.state.name,
            "message" to output.getString(ArchiveSaveWorker.KEY_MESSAGE),
            "error" to output.getString(ArchiveSaveWorker.KEY_ERROR),
            "needsVerification" to output.getBoolean(
                ArchiveSaveWorker.KEY_NEEDS_VERIFICATION,
                false,
            ),
        )
    }

    private fun archivePreferences() =
        getSharedPreferences(ArchiveSaveWorker.PREFERENCES_NAME, MODE_PRIVATE)

    private fun isScreenCaptureServiceEnabled(): Boolean {
        val component = ComponentName(this, ScreenCaptureAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabledServices.split(':').any {
            ComponentName.unflattenFromString(it) == component
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST,
            )
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val BACKGROUND_ARCHIVE_CHANNEL =
            "com.archivesaver.frontendflutter/background_archive"
        private const val NOTIFICATION_PERMISSION_REQUEST = 4102
    }
}
