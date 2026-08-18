package com.archivesaver.frontend_flutter

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureAccessibilityService : AccessibilityService() {
    private var lastVolumeDownAt = 0L
    private val isCapturing = AtomicBoolean(false)

    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (
            event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.action != KeyEvent.ACTION_DOWN ||
            event.repeatCount != 0
        ) return false

        val now = SystemClock.elapsedRealtime()
        if (now - lastVolumeDownAt in 80..DOUBLE_PRESS_WINDOW_MS) {
            lastVolumeDownAt = 0L
            scheduleCapture()
        } else {
            lastVolumeDownAt = now
        }
        return false
    }

    private fun scheduleCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            showToast("Android 11 이상에서 화면 저장을 지원합니다.")
            return
        }
        if (!isCapturing.compareAndSet(false, true)) return
        prepareCapture()
    }

    private fun prepareCapture() {
        // 볼륨 패널이 전면에 떠 있어도 그 아래 실제 앱 창을 즉시 선택합니다.
        val root = windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() != SYSTEM_UI_PACKAGE }
            ?: rootInActiveWindow
        val sourcePackage = root?.packageName?.toString() ?: "unknown"
        val pageUrl = extractBrowserUrl(
            sourcePackage,
            root,
            requireExplicitScheme = true,
        )
        captureCurrentScreen(sourcePackage, pageUrl, root?.windowId)
    }

    private fun captureCurrentScreen(
        sourcePackage: String,
        pageUrl: String?,
        windowId: Int?,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            isCapturing.set(false)
            return
        }

        val appLabel = runCatching {
            val info = packageManager.getApplicationInfo(sourcePackage, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(sourcePackage)

        val callback = object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val hardwareBuffer = result.hardwareBuffer
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                            hardwareBuffer,
                            result.colorSpace,
                        ) ?: throw IllegalStateException("화면 이미지를 읽지 못했습니다.")
                        val bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        hardwareBitmap.recycle()
                        hardwareBuffer.close()

                        val file = File(
                            noBackupFilesDir,
                            "screen_${System.currentTimeMillis()}.png",
                        )
                        file.outputStream().use { output ->
                            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                throw IllegalStateException("PNG 저장에 실패했습니다.")
                            }
                        }
                        bitmap.recycle()
                        enqueueCapture(file, sourcePackage, appLabel, pageUrl)
                        showToast(
                            if (pageUrl != null) {
                                "전체 페이지 저장을 시작했습니다."
                            } else {
                                "현재 화면 저장을 시작했습니다."
                            },
                        )
                    } catch (error: Exception) {
                        showToast("화면을 저장하지 못했습니다: ${error.message}")
                    } finally {
                        isCapturing.set(false)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    isCapturing.set(false)
                    showToast("이 화면은 캡처할 수 없습니다. ($errorCode)")
                }
            }

        // Android 14 이상에서는 앱 창만 캡처해 시스템 볼륨 UI가 이미지에 섞이지 않습니다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && windowId != null) {
            takeScreenshotOfWindow(windowId, mainExecutor, callback)
        } else {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
        }
    }

    private fun extractBrowserUrl(
        sourcePackage: String,
        root: AccessibilityNodeInfo?,
        requireExplicitScheme: Boolean = false,
    ): String? {
        if (root == null || sourcePackage !in SUPPORTED_BROWSER_PACKAGES) return null
        var bestUrl: String? = null
        var bestScore = Int.MIN_VALUE
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited < 800) {
            val node = pending.removeFirst()
            visited += 1
            val viewId = node.viewIdResourceName?.lowercase().orEmpty()
            val idLooksLikeAddressBar = ADDRESS_BAR_ID_HINTS.any(viewId::contains)
            val values = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString(),
            )
            for (value in values) {
                val cleanedValue = value.replace(CONTROL_OR_FORMAT_CHARACTER, "").trim()
                if (
                    requireExplicitScheme &&
                    !cleanedValue.startsWith("http://") &&
                    !cleanedValue.startsWith("https://")
                ) continue
                val normalized = normalizeUrl(value) ?: continue
                val score =
                    (if (idLooksLikeAddressBar) 100 else 0) +
                    (if (node.isEditable) 30 else 0) +
                    (if (node.isFocused) 10 else 0)
                if (score > bestScore && score >= 30) {
                    bestScore = score
                    bestUrl = normalized
                }
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(pending::addLast)
            }
        }
        return bestUrl
    }

    private fun normalizeUrl(rawValue: String): String? {
        val value = rawValue.replace(CONTROL_OR_FORMAT_CHARACTER, "").trim()
        if (value.isBlank() || value.any(Char::isWhitespace)) return null
        val candidate = if (value.startsWith("http://") || value.startsWith("https://")) {
            value
        } else {
            "https://$value"
        }
        val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (!host.contains('.') || host.length > 253) return null
        return uri.toString()
    }

    private fun enqueueCapture(
        file: File,
        sourcePackage: String,
        appLabel: String,
        pageUrl: String?,
    ) {
        val preferences = getSharedPreferences(
            ArchiveSaveWorker.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        val accessToken = preferences.getString(CONFIG_ACCESS_TOKEN, null)
        val backendUrl = preferences.getString(CONFIG_BACKEND_URL, null)
        if (accessToken.isNullOrBlank() || backendUrl.isNullOrBlank()) {
            file.delete()
            throw IllegalStateException("Archive Saver 앱을 먼저 열어 로그인해주세요.")
        }

        val createdAt = System.currentTimeMillis()
        val archiveUrl = pageUrl ?: "screenshot://$sourcePackage"
        val request = OneTimeWorkRequestBuilder<ArchiveSaveWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setInputData(
                workDataOf(
                    ArchiveSaveWorker.KEY_URL to archiveUrl,
                    ArchiveSaveWorker.KEY_ACCESS_TOKEN to accessToken,
                    ArchiveSaveWorker.KEY_BACKEND_URL to backendUrl,
                    ArchiveSaveWorker.KEY_CREATED_AT to createdAt,
                    ArchiveSaveWorker.KEY_SCREENSHOT_PATH to file.absolutePath,
                    ArchiveSaveWorker.KEY_SOURCE_PACKAGE to sourcePackage,
                    ArchiveSaveWorker.KEY_APP_LABEL to appLabel,
                    ArchiveSaveWorker.KEY_CAPTURED_AT to SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        Locale.KOREA,
                    ).format(Date()),
                ),
            )
            .addTag(ArchiveSaveWorker.TAG)
            .build()

        preferences.edit()
            .putString("${request.id}.url", archiveUrl)
            .putLong("${request.id}.createdAt", createdAt)
            .putString("${request.id}.screenshotPath", file.absolutePath)
            .apply()
        WorkManager.getInstance(applicationContext)
            .beginUniqueWork(
                ArchiveSaveWorker.QUEUE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
            .enqueue()
    }

    private fun showToast(message: String) {
        mainExecutor.execute {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        const val CONFIG_ACCESS_TOKEN = "screenCapture.accessToken"
        const val CONFIG_BACKEND_URL = "screenCapture.backendUrl"
        private const val DOUBLE_PRESS_WINDOW_MS = 550L
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private val CONTROL_OR_FORMAT_CHARACTER = Regex("""[\p{Cc}\p{Cf}]""")
        private val SUPPORTED_BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.sec.android.app.sbrowser",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "com.microsoft.emmx",
            "com.brave.browser",
            "com.opera.browser",
            "com.naver.whale",
        )
        private val ADDRESS_BAR_ID_HINTS = listOf(
            "url_bar",
            "location_bar",
            "address_bar",
            "omnibox",
            "url_field",
            "location_edit",
        )
    }
}
