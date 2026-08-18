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
import android.webkit.CookieManager
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
import java.util.UUID
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
        if (intent?.action == ACTION_BULK_RAINDROP_IMPORT) {
            val bookmarksJson = intent.getStringExtra(EXTRA_BOOKMARKS_JSON)
            if (bookmarksJson.isNullOrBlank()) {
                return START_NOT_STICKY
            }

            val runningCount = activeJobs.incrementAndGet()
            if (runningCount == 1) {
                acquireWakeLock()
                startForeground(NOTIFICATION_ID, buildNotification("Raindrop 북마크 가져오기를 준비하고 있습니다.", runningCount))
            } else {
                updateNotification("Raindrop 가져오기 작업을 추가했습니다.", runningCount)
            }

            executor.execute {
                runCatching {
                    processBulkRaindropImport(bookmarksJson)
                }.onFailure { error ->
                    updateNotification(error.toUserFacingNetworkMessage("Raindrop 가져오기"), activeJobs.get())
                }

                val remaining = activeJobs.decrementAndGet()
                if (remaining <= 0) {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                } else {
                    updateNotification("저장 작업을 계속 진행 중입니다.", remaining)
                }
            }

            return START_REDELIVER_INTENT
        }

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
        val offlineDir = OfflineArchiveStore.prepareDirectory(this, summary.id)
        val offlineMediaDir = OfflineArchiveStore.mediaDirectory(offlineDir)

        var rewrittenHtml = html
        var offlineHtml = html

        try {
            ArchiveJobStore.update(summary.id, "미디어 확인 중", 5)
            candidates.forEachIndexed { index, candidate ->
                val downloadProgress = 10 + ((index * 60) / maxOf(candidates.size, 1))
                ArchiveJobStore.update(summary.id, "미디어 다운로드 중 ${index + 1}/${candidates.size}", downloadProgress)
                updateNotification("미디어 업로드 중... ${index + 1}/${candidates.size}", activeJobs.get())
                val tempFile = downloadMediaToTempFile(candidate, pageUrl)
                try {
                    val offlineFile = copyMediaForOffline(tempFile, offlineMediaDir, index, candidate)
                    offlineHtml = replaceMediaReferences(
                        offlineHtml,
                        candidate.sourceUrl,
                        "media/${offlineFile.name}"
                    )

                    ArchiveJobStore.update(summary.id, "미디어 업로드 중 ${index + 1}/${candidates.size}", downloadProgress + 5)
                    val uploadedUrl = withNetworkRetry("미디어 업로드") {
                        uploadMediaFile(tempFile, candidate)
                    }
                    rewrittenHtml = replaceMediaReferences(rewrittenHtml, candidate.sourceUrl, uploadedUrl)
                } finally {
                    tempFile.delete()
                }
            }

            val offlineIndex = OfflineArchiveStore.writeIndex(offlineDir, offlineHtml)

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
                        savedAt = System.currentTimeMillis(),
                        localArchivePath = offlineIndex.absolutePath,
                        offlineSizeBytes = OfflineArchiveStore.sizeBytes(offlineIndex.absolutePath)
                    )
                )
            }
        } catch (error: Throwable) {
            offlineDir.deleteRecursively()
            throw error
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
            readTimeout = 600_000
            instanceFollowRedirects = true
            setRequestProperty("Referer", refererUrl)
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
            )
        }
        CookieManager.getInstance().getCookie(candidate.sourceUrl)?.takeIf { it.isNotBlank() }?.let {
            connection.setRequestProperty("Cookie", it)
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

    private fun copyMediaForOffline(
        file: File,
        mediaDir: File,
        index: Int,
        candidate: MediaCandidate
    ): File {
        val sourceName = Uri.parse(candidate.sourceUrl).lastPathSegment
            ?.substringBefore('?')
            ?.substringBefore('#')
            .orEmpty()
        val baseName = sourceName.substringBeforeLast('.', sourceName)
            .replace(Regex("""[^A-Za-z0-9._-]+"""), "_")
            .trim(' ', '.', '_', '-')
            .ifBlank { candidate.mediaType.removeSuffix("s").ifBlank { "media" } }
        val extension = file.extension
            .takeIf { it.isNotBlank() }
            ?.let { ".$it" }
            ?: guessExtension(candidate.sourceUrl, null, candidate.mediaType)
        val offlineFile = File(mediaDir, "${index}_${baseName}$extension")
        file.copyTo(offlineFile, overwrite = true)
        return offlineFile
    }

    private fun uploadMediaFile(file: File, candidate: MediaCandidate): String {
        val uploadInfo = requestMediaUploadLink(file, candidate)
        val uploadUrl = uploadInfo.getString("uploadUrl")
        val archiveUrl = uploadInfo.getString("url")
        val connection = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 600_000
            doOutput = true
            setFixedLengthStreamingMode(file.length())
            setRequestProperty("Content-Type", "application/octet-stream")
        }

        connection.outputStream.use { output ->
            file.inputStream().use { input ->
                input.copyTo(output)
            }
        }

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val responseText = connection.errorStream
                ?.bufferedReader()
                ?.use(BufferedReader::readText)
                .orEmpty()
            throw IllegalStateException(
                responseText.takeIf { it.isNotBlank() }
                    ?: "Dropbox 직접 업로드 실패: HTTP $responseCode"
            )
        }

        return if (archiveUrl.startsWith("/")) {
            "${BuildConfig.ARCHIVE_API_BASE_URL}$archiveUrl"
        } else {
            archiveUrl
        }
    }

    private fun requestMediaUploadLink(file: File, candidate: MediaCandidate): JSONObject {
        val endpoint = URL("${BuildConfig.ARCHIVE_API_BASE_URL}/api/media-upload-link")
        val body = JSONObject().apply {
            put("mediaType", candidate.mediaType)
            put("sourceUrl", candidate.sourceUrl)
            put("filename", file.name)
            put("contentType", guessContentType(file, candidate.mediaType))
        }

        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 600_000
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

        if (responseCode !in 200..299) {
            throw IllegalStateException(
                responseJson?.optString("error") ?: "미디어 업로드 링크 생성 실패: HTTP $responseCode"
            )
        }

        return responseJson ?: throw IllegalStateException("업로드 링크 응답이 비어 있습니다.")
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
            lowerName.endsWith(".webp") -> "image/webp"
            lowerName.endsWith(".webm") -> "video/webm"
            lowerName.endsWith(".mp4") -> "video/mp4"
            lowerName.endsWith(".mp3") -> "audio/mpeg"
            lowerName.endsWith(".ogg") -> "audio/ogg"
            mediaType == "videos" -> "video/mp4"
            mediaType == "audio" -> "audio/mpeg"
            else -> "application/octet-stream"
        }
    }

    private fun replaceMediaReferences(html: String, sourceUrl: String, uploadedUrl: String): String {
        var rewritten = html
        mediaUrlVariants(sourceUrl)
            .sortedByDescending { it.length }
            .forEach { variant ->
                rewritten = rewritten.replace(variant, uploadedUrl)
                rewritten = rewritten.replace(variant.replace("&", "&amp;"), uploadedUrl)
            }
        return rewritten
    }

    private fun mediaUrlVariants(sourceUrl: String): Set<String> {
        val parsed = Uri.parse(sourceUrl)
        val host = parsed.host ?: return setOf(sourceUrl)
        val encodedPath = parsed.encodedPath.orEmpty()
        val normalizedPath = encodedPath.replace("/./", "/")
        val dottedPath = if (normalizedPath.startsWith("/files/")) {
            "/.$normalizedPath"
        } else {
            normalizedPath
        }
        val query = parsed.encodedQuery?.let { "?$it" }.orEmpty()
        val paths = setOf(encodedPath, normalizedPath, dottedPath).filter { it.isNotBlank() }
        val schemes = setOfNotNull(parsed.scheme, "https", "http")

        return buildSet {
            add(sourceUrl)
            paths.forEach { path ->
                schemes.forEach { scheme ->
                    add("$scheme://$host$path$query")
                }
                add("//$host$path$query")
            }
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

    private fun processBulkRaindropImport(bookmarksJson: String) {
        val array = JSONArray(bookmarksJson)
        val total = array.length()
        var successCount = 0
        var failCount = 0

        for (i in 0 until total) {
            val item = array.optJSONObject(i) ?: continue
            val title = item.optString("title", "제목 없음")
            val link = item.optString("link", "")
            if (link.isBlank()) continue

            updateNotificationWithProgress(
                "Raindrop 저장 중 (${i + 1}/$total): $title",
                i + 1,
                total
            )

            val archiveId = UUID.randomUUID().toString()
            val offlineDir = OfflineArchiveStore.prepareDirectory(this, archiveId)

            try {
                // 1. 서버에 HTML 아카이브 생성 요청
                val saveUrl = "${BuildConfig.ARCHIVE_API_BASE_URL}/api/save-html"
                val payload = JSONObject().apply {
                    put("url", link)
                    put("title", title)
                }
                val (code, response) = httpPostJson(saveUrl, payload.toString())
                if (code in 200..299) {
                    val resJson = JSONObject(response)
                    val archiveUrl = resJson.optString("archiveUrl")
                    val generatedId = archiveUrl.substringAfterLast("/").removeSuffix(".html").ifBlank { archiveId }

                    // 2. 생성된 HTML을 다운로드하여 로컬 오프라인 사본 생성
                    val htmlContent = httpGet("${BuildConfig.ARCHIVE_API_BASE_URL}/archive/$generatedId")
                    val offlineIndex = OfflineArchiveStore.writeIndex(offlineDir, htmlContent)

                    ArchiveHistoryStore.add(
                        this,
                        ArchiveHistoryStore.Entry(
                            title = title,
                            archiveUrl = archiveUrl,
                            sourceUrl = link,
                            collectionTitle = "Raindrop",
                            savedAt = System.currentTimeMillis(),
                            localArchivePath = offlineIndex.absolutePath,
                            offlineSizeBytes = OfflineArchiveStore.sizeBytes(offlineIndex.absolutePath)
                        )
                    )
                    successCount++
                } else {
                    offlineDir.deleteRecursively()
                    failCount++
                }
            } catch (e: Throwable) {
                offlineDir.deleteRecursively()
                failCount++
            }

            Thread.sleep(150)
        }

        updateNotification("Raindrop 북마크 ${successCount}개 저장 완료 (실패 ${failCount}개)", 0)
    }

    private fun updateNotificationWithProgress(message: String, progress: Int, max: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_archive_24)
            .setContentTitle("Archive Saver (오프라인 저장 중)")
            .setContentText(message)
            .setProgress(max, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, SettingsActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
        manager.notify(NOTIFICATION_ID, notif)
    }

    private fun httpGet(urlStr: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        conn.setRequestProperty("Bypass-Tunnel-Reminder", "true")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun httpPostJson(urlStr: String, json: String): Pair<Int, String> {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 40000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.setRequestProperty("Bypass-Tunnel-Reminder", "true")

        conn.outputStream.use { os ->
            OutputStreamWriter(os, Charsets.UTF_8).use { it.write(json) }
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        val text = stream.bufferedReader().use { it.readText() }
        return code to text
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) {
            return
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ArchiveSaver::SaveWakeLock"
        ).apply {
            acquire(60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        const val ACTION_ENQUEUE_SAVE = "com.archivesaver.android.action.ENQUEUE_SAVE"
        const val ACTION_BULK_RAINDROP_IMPORT = "com.archivesaver.android.action.BULK_RAINDROP_IMPORT"
        const val EXTRA_JOB_FILE_PATH = "com.archivesaver.android.extra.JOB_FILE_PATH"
        const val EXTRA_BOOKMARKS_JSON = "com.archivesaver.android.extra.BOOKMARKS_JSON"

        private const val CHANNEL_ID = "archive_save_jobs"
        private const val NOTIFICATION_ID = 7401
    }
}
