package com.archivesaver.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ArchiveSaveService : Service() {

    private data class MediaCandidate(
        val sourceUrl: String,
        val mediaType: String
    )

    private data class JobSummary(
        val id: String,
        val title: String,
        val url: String,
        val collectionTitle: String
    )

    private lateinit var executor: ExecutorService
    private val activeJobs = AtomicInteger(0)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newFixedThreadPool(3)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_ENQUEUE_SAVE) {
            return START_NOT_STICKY
        }

        val jobFilePath = intent.getStringExtra(EXTRA_JOB_FILE_PATH)
        if (jobFilePath.isNullOrBlank()) {
            return START_NOT_STICKY
        }

        val jobFile = File(jobFilePath)
        val summary = readJobSummary(jobFile)
        ArchiveJobStore.upsert(
            ArchiveJobStore.Job(
                id = summary.id,
                title = summary.title,
                url = summary.url,
                collectionTitle = summary.collectionTitle,
                status = "저장 작업 대기",
                progress = 0
            )
        )

        val runningCount = activeJobs.incrementAndGet()
        if (runningCount == 1) {
            acquireWakeLock()
            startForeground(NOTIFICATION_ID, buildNotification("저장 작업을 준비하고 있습니다.", runningCount))
        } else {
            updateNotification("저장 작업을 추가했습니다.", runningCount)
        }

        executor.execute {
            runCatching {
                processJob(jobFile, summary)
            }.onFailure { error ->
                ArchiveJobStore.update(
                    summary.id,
                    error.toUserFacingNetworkMessage("저장"),
                    0,
                    isFinished = true,
                    isFailed = true
                )
                updateNotification(error.toUserFacingNetworkMessage("저장"), activeJobs.get())
            }

            val remaining = activeJobs.decrementAndGet()
            if (remaining <= 0) {
                releaseWakeLock()
                updateNotification("저장 작업이 완료되었습니다.", 0)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            } else {
                updateNotification("저장 작업을 계속 진행 중입니다.", remaining)
            }
        }

        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
        releaseWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun readJobSummary(jobFile: File): JobSummary {
        val job = JSONObject(jobFile.readText(Charsets.UTF_8))
        return JobSummary(
            id = job.optString("id").takeIf { it.isNotBlank() } ?: jobFile.nameWithoutExtension,
            title = job.optString("title"),
            url = job.optString("url"),
            collectionTitle = job.optString("collectionTitle", BuildConfig.DEFAULT_COLLECTION_TITLE)
        )
    }

    private fun processJob(jobFile: File, summary: JobSummary) {
        val job = JSONObject(jobFile.readText(Charsets.UTF_8))
        val pageUrl = summary.url
        val html = job.getString("html")
        val collectionTitle = summary.collectionTitle
        val candidates = parseMediaCandidates(job.optJSONArray("mediaCandidates"))

        var rewrittenHtml = html
        ArchiveJobStore.update(summary.id, "미디어 확인 중", 5)
        candidates.forEachIndexed { index, candidate ->
            val downloadProgress = 10 + ((index * 60) / maxOf(candidates.size, 1))
            ArchiveJobStore.update(summary.id, "미디어 다운로드 중 ${index + 1}/${candidates.size}", downloadProgress)
            updateNotification("미디어 업로드 중... ${index + 1}/${candidates.size}", activeJobs.get())
            val tempFile = downloadMediaToTempFile(candidate, pageUrl)
            try {
                ArchiveJobStore.update(summary.id, "미디어 업로드 중 ${index + 1}/${candidates.size}", downloadProgress + 5)
                val uploadedUrl = withNetworkRetry("미디어 업로드") {
                    uploadMediaFile(tempFile, candidate)
                }
                rewrittenHtml = rewrittenHtml.replace(candidate.sourceUrl, uploadedUrl)
            } finally {
                tempFile.delete()
            }
        }

        ArchiveJobStore.update(summary.id, "아카이브 저장 중", 85)
        updateNotification("아카이브 저장 중...", activeJobs.get())
        val (code, message) = withNetworkRetry("아카이브 저장") {
            performArchiveRequest(pageUrl, rewrittenHtml, collectionTitle)
        }

        if (code !in 200..299) {
            throw IllegalStateException(message)
        }

        jobFile.delete()
        extractArchiveUrl(message)?.let { archiveUrl ->
            ArchiveHistoryStore.add(
                this,
                ArchiveHistoryStore.Entry(
                    title = summary.title,
                    archiveUrl = archiveUrl,
                    sourceUrl = pageUrl,
                    collectionTitle = collectionTitle,
                    savedAt = System.currentTimeMillis()
                )
            )
        }
        ArchiveJobStore.update(summary.id, "저장 완료", 100, isFinished = true)
        updateNotification("저장 완료", activeJobs.get())
    }

    private fun extractArchiveUrl(message: String): String? {
        return message
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun parseMediaCandidates(array: JSONArray?): List<MediaCandidate> {
        if (array == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val sourceUrl = item.optString("url")
                val mediaType = item.optString("mediaType")
                if (sourceUrl.isNotBlank() && mediaType.isNotBlank()) {
                    add(MediaCandidate(sourceUrl, mediaType))
                }
            }
        }
    }

    private fun performArchiveRequest(
        url: String,
        html: String,
        collectionTitle: String
    ): Pair<Int, String> {
        val endpoint = URL("${BuildConfig.ARCHIVE_API_BASE_URL}/api/save-html")
        val body = JSONObject().apply {
            put("url", url)
            put("html", html)
            put("collectionTitle", collectionTitle)
            put("clientCaptureMode", "android-webview")
        }

        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(body.toString())
        }

        val responseCode = connection.responseCode
        val responseText = (
            if (responseCode in 200..299) connection.inputStream else connection.errorStream
        )?.bufferedReader()?.use(BufferedReader::readText).orEmpty()

        val responseJson = responseText.takeIf { it.isNotBlank() }?.let(::JSONObject)
        val archiveUrl = responseJson?.optString("archiveUrl").orEmpty()
        val message = responseJson?.optString("message")
            ?.takeIf { it.isNotBlank() }
            ?: responseJson?.optString("error")?.takeIf { it.isNotBlank() }
            ?: "HTTP $responseCode"

        return responseCode to if (archiveUrl.isBlank()) message else "$message\n$archiveUrl"
    }

    private fun downloadMediaToTempFile(candidate: MediaCandidate, refererUrl: String): File {
        val connection = (URL(candidate.sourceUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            setRequestProperty("Referer", refererUrl)
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
            )
        }

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            throw IllegalStateException("미디어 다운로드 실패: HTTP $responseCode")
        }

        val extension = guessExtension(candidate.sourceUrl, connection.contentType, candidate.mediaType)
        val tempFile = File.createTempFile("archive_${candidate.mediaType}_", extension, cacheDir)

        connection.inputStream.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }

        return tempFile
    }

    private fun uploadMediaFile(file: File, candidate: MediaCandidate): String {
        val boundary = "----ArchiveSaver${System.currentTimeMillis()}"
        val endpoint = URL("${BuildConfig.ARCHIVE_API_BASE_URL}/api/upload-media")
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
        }

        connection.outputStream.use { output ->
            writeFormField(output, boundary, "mediaType", candidate.mediaType)
            writeFormField(output, boundary, "sourceUrl", candidate.sourceUrl)
            writeFilePart(output, boundary, "file", file, guessContentType(file, candidate.mediaType))
            output.write("--$boundary--\r\n".toByteArray())
        }

        val responseCode = connection.responseCode
        val responseText = (
            if (responseCode in 200..299) connection.inputStream else connection.errorStream
        )?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        val responseJson = responseText.takeIf { it.isNotBlank() }?.let(::JSONObject)

        if (responseCode !in 200..299) {
            throw IllegalStateException(
                responseJson?.optString("error") ?: "미디어 업로드 실패: HTTP $responseCode"
            )
        }

        val uploadedUrl = responseJson?.optString("url")?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("업로드 응답에 URL이 없습니다.")

        return if (uploadedUrl.startsWith("/")) {
            "${BuildConfig.ARCHIVE_API_BASE_URL}$uploadedUrl"
        } else {
            uploadedUrl
        }
    }

    private fun writeFormField(output: OutputStream, boundary: String, name: String, value: String) {
        output.write("--$boundary\r\n".toByteArray())
        output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
        output.write(value.toByteArray(Charsets.UTF_8))
        output.write("\r\n".toByteArray())
    }

    private fun writeFilePart(
        output: OutputStream,
        boundary: String,
        fieldName: String,
        file: File,
        contentType: String
    ) {
        output.write("--$boundary\r\n".toByteArray())
        output.write(
            "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"${file.name}\"\r\n".toByteArray()
        )
        output.write("Content-Type: $contentType\r\n\r\n".toByteArray())
        file.inputStream().use { input ->
            input.copyTo(output)
        }
        output.write("\r\n".toByteArray())
    }

    private fun guessExtension(sourceUrl: String, contentType: String?, mediaType: String): String {
        val path = Uri.parse(sourceUrl).lastPathSegment.orEmpty()
        val existingExt = path.substringAfterLast('.', "")
        if (existingExt.isNotBlank()) {
            return ".${existingExt.substringBefore('?')}"
        }

        val lowerType = contentType.orEmpty().lowercase()
        return when {
            "gif" in lowerType -> ".gif"
            "webm" in lowerType -> ".webm"
            "mpeg" in lowerType -> ".mp3"
            "ogg" in lowerType -> ".ogg"
            "png" in lowerType -> ".png"
            "jpeg" in lowerType || "jpg" in lowerType -> ".jpg"
            mediaType == "videos" -> ".mp4"
            mediaType == "audio" -> ".mp3"
            else -> ".bin"
        }
    }

    private fun guessContentType(file: File, mediaType: String): String {
        val lowerName = file.name.lowercase()
        return when {
            lowerName.endsWith(".gif") -> "image/gif"
            lowerName.endsWith(".png") -> "image/png"
            lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") -> "image/jpeg"
            lowerName.endsWith(".webm") -> "video/webm"
            lowerName.endsWith(".mp4") -> "video/mp4"
            lowerName.endsWith(".mp3") -> "audio/mpeg"
            lowerName.endsWith(".ogg") -> "audio/ogg"
            mediaType == "videos" -> "video/mp4"
            mediaType == "audio" -> "audio/mpeg"
            else -> "application/octet-stream"
        }
    }

    private fun <T> withNetworkRetry(label: String, attempts: Int = 3, block: () -> T): T {
        var lastError: Throwable? = null
        repeat(attempts) { index ->
            try {
                return block()
            } catch (error: Throwable) {
                lastError = error
                if (!error.isRetryableNetworkError() || index == attempts - 1) {
                    throw IllegalStateException(error.toUserFacingNetworkMessage(label), error)
                }
                Thread.sleep(900L * (index + 1))
            }
        }

        throw IllegalStateException(
            lastError?.toUserFacingNetworkMessage(label) ?: "$label 중 알 수 없는 오류가 발생했습니다.",
            lastError
        )
    }

    private fun Throwable.isRetryableNetworkError(): Boolean {
        return hasCause<UnknownHostException>() ||
            hasCause<SocketTimeoutException>() ||
            hasCause<IOException>()
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun Throwable.toUserFacingNetworkMessage(label: String): String {
        return when {
            hasCause<UnknownHostException>() ->
                "$label 실패: Archive Saver 서버 주소를 찾지 못했습니다."
            hasCause<SocketTimeoutException>() ->
                "$label 실패: 서버 응답이 늦어졌습니다."
            hasCause<IOException>() ->
                "$label 실패: 서버와 연결하지 못했습니다."
            message.isNullOrBlank() ->
                "$label 실패: 알 수 없는 오류가 발생했습니다."
            else -> message.orEmpty()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Archive Saver",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Archive Saver 저장 진행 상태"
        }

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(message: String, runningJobs: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message, runningJobs))
    }

    private fun buildNotification(message: String, runningJobs: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_archive_24)
            .setContentTitle("Archive Saver")
            .setContentText(
                if (runningJobs > 0) "$message (${runningJobs}개 진행 중)" else message
            )
            .setOngoing(runningJobs > 0)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) {
            return
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ArchiveSaver::SaveWakeLock"
        ).apply {
            acquire(30 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        const val ACTION_ENQUEUE_SAVE = "com.archivesaver.android.action.ENQUEUE_SAVE"
        const val EXTRA_JOB_FILE_PATH = "com.archivesaver.android.extra.JOB_FILE_PATH"

        private const val CHANNEL_ID = "archive_save_jobs"
        private const val NOTIFICATION_ID = 7401
    }
}
