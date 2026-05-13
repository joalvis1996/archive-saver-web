package com.archivesaver.android

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.archivesaver.android.databinding.ActivityMainBinding
import com.google.android.material.tabs.TabLayout
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

class MainActivity : AppCompatActivity() {

    data class MediaCandidate(
        val sourceUrl: String,
        val mediaType: String
    )

    private lateinit var binding: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var shouldAutoSaveAfterLoad = false
    private var isSaving = false
    private var currentCaptureUrl: String? = null
    private var pendingSharedUrl: String? = null
    private val htmlCaptureBuffer = StringBuilder()
    private val jobStoreListener = ArchiveJobStore.Listener { jobs ->
        mainHandler.post { renderJobList(jobs) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureCollectionPicker()
        configureWebView()
        configureButtons()
        configureTabs()
        handleIncomingIntent(intent)
        prefillClipboardUrlIfEmpty()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        prefillClipboardUrlIfEmpty()
    }

    override fun onStart() {
        super.onStart()
        ArchiveJobStore.addListener(jobStoreListener)
    }

    override fun onStop() {
        super.onStop()
        ArchiveJobStore.removeListener(jobStoreListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        networkExecutor.shutdownNow()
    }

    private fun configureButtons() {
        binding.loadButton.setOnClickListener {
            loadCurrentInput(autoSave = false)
        }

        binding.saveButton.setOnClickListener {
            saveCurrentInputOrLoadedPage()
        }

        binding.clearFinishedJobsButton.setOnClickListener {
            ArchiveJobStore.clearFinished()
        }

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun configureTabs() {
        binding.contentTabs.addTab(binding.contentTabs.newTab().setText(R.string.jobs_tab))
        binding.contentTabs.addTab(binding.contentTabs.newTab().setText(R.string.preview_tab))
        binding.contentTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val showPreview = tab.position == 1
                binding.jobsContent.visibility = if (showPreview) View.INVISIBLE else View.VISIBLE
                binding.previewCard.visibility = if (showPreview) View.VISIBLE else View.INVISIBLE
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun configureCollectionPicker() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            resources.getStringArray(R.array.collection_options)
        )
        binding.collectionInput.setAdapter(adapter)
        binding.collectionInput.setText(getString(R.string.collection_default), false)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val sharedUrl = extractSharedUrl(intent) ?: return
        if (isSaving) {
            pendingSharedUrl = sharedUrl
            updateStatus("현재 페이지를 저장 작업에 등록한 뒤 새 링크를 입력합니다.")
            return
        }

        applySharedUrl(sharedUrl)
    }

    private fun applySharedUrl(sharedUrl: String) {
        binding.urlInput.setText(sharedUrl)
        binding.collectionInput.setText(getString(R.string.collection_default), false)
        shouldAutoSaveAfterLoad = false
        currentCaptureUrl = null
        htmlCaptureBuffer.setLength(0)
        binding.progressBar.progress = 0
        updateStatus("공유된 링크를 입력했습니다. 저장하기를 누르면 이 페이지를 저장합니다.")
    }

    private fun applyPendingSharedUrlIfAny() {
        val sharedUrl = pendingSharedUrl ?: return
        pendingSharedUrl = null
        applySharedUrl(sharedUrl)
    }

    private fun prefillClipboardUrlIfEmpty() {
        if (!binding.urlInput.text.isNullOrBlank()) {
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
        val clipboardUrl = extractFirstUrl(clipText) ?: return
        binding.urlInput.setText(clipboardUrl)
        updateStatus("클립보드의 링크를 입력했습니다.")
    }

    private fun loadCurrentInput(autoSave: Boolean) {
        val rawUrl = binding.urlInput.text?.toString()?.trim().orEmpty()
        if (rawUrl.isBlank()) {
            updateStatus("URL을 먼저 입력하세요.")
            return
        }
        loadUrl(rawUrl, autoSave)
    }

    private fun loadUrl(rawUrl: String, autoSave: Boolean) {
        val normalized = normalizeUrl(rawUrl)
        if (normalized == null) {
            updateStatus("유효한 URL 형식이 아닙니다.")
            return
        }

        shouldAutoSaveAfterLoad = autoSave
        binding.progressBar.progress = 10
        binding.webView.loadUrl(normalized)
    }

    private fun saveCurrentInputOrLoadedPage() {
        val inputUrl = normalizeUrl(binding.urlInput.text?.toString().orEmpty())
        if (inputUrl == null) {
            updateStatus("URL을 먼저 입력하세요.")
            return
        }

        val loadedUrl = binding.webView.url?.let(::normalizeUrl)
        if (loadedUrl != inputUrl) {
            updateStatus("입력한 페이지를 먼저 여는 중...")
            loadUrl(inputUrl, autoSave = true)
            return
        }

        captureAndSaveCurrentPage()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)
        binding.webView.addJavascriptInterface(ArchiveSaverBridge(), "ArchiveSaverBridge")

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = false

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                updateStatus("페이지 로드 중...")
                setButtonsEnabled(false)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                setButtonsEnabled(true)
                updateStatus("페이지 로드 완료")
                if (shouldAutoSaveAfterLoad) {
                    shouldAutoSaveAfterLoad = false
                    mainHandler.postDelayed({ captureAndSaveCurrentPage() }, 1000L)
                }
            }
        }
    }

    private fun captureAndSaveCurrentPage() {
        val currentUrl = binding.webView.url ?: normalizeUrl(binding.urlInput.text?.toString().orEmpty())
        if (currentUrl.isNullOrBlank()) {
            updateStatus("먼저 저장할 페이지를 열어주세요.")
            return
        }

        if (isSaving) {
            return
        }

        isSaving = true
        setButtonsEnabled(false)
        updateStatus("지연 로딩 미디어를 준비하는 중...")
        binding.progressBar.progress = 20
        currentCaptureUrl = currentUrl
        htmlCaptureBuffer.setLength(0)

        binding.webView.evaluateJavascript(PREPARE_PAGE_SCRIPT) {
            mainHandler.postDelayed({
                updateStatus("페이지 HTML을 추출하는 중...")
                binding.progressBar.progress = 45
                binding.webView.evaluateJavascript(CAPTURE_VIA_BRIDGE_SCRIPT, null)
            }, 1800L)
        }
    }

    private fun performArchiveRequest(url: String, html: String): Pair<Int, String> {
        val endpoint = URL("${BuildConfig.ARCHIVE_API_BASE_URL}/api/save-html")
        val body = JSONObject().apply {
            put("url", url)
            put("html", html)
            put("collectionTitle", selectedCollectionTitle())
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

    private fun postArchiveRequest(url: String, html: String) {
        networkExecutor.execute {
            val result = runCatching {
                withNetworkRetry("아카이브 저장") {
                    performArchiveRequest(url, html)
                }
            }

            mainHandler.post {
                val (code, message) = result.getOrElse { -1 to (it.message ?: "알 수 없는 오류") }
                if (code in 200..299) {
                    binding.progressBar.progress = 100
                    updateStatus(message)
                } else {
                    finishSaveWithError(message)
                    return@post
                }
                isSaving = false
                setButtonsEnabled(true)
                currentCaptureUrl = null
                htmlCaptureBuffer.setLength(0)
            }
        }
    }

    private fun collectMediaCandidatesAndSave(pageUrl: String, html: String) {
        updateStatus("영상/GIF 링크를 추출하는 중...")
        binding.progressBar.progress = 72
        binding.webView.evaluateJavascript(MEDIA_CANDIDATES_SCRIPT) { rawJson ->
            val candidates = parseMediaCandidates(rawJson)
            enqueueBackgroundSave(pageUrl, html, candidates)
        }
    }

    private fun enqueueBackgroundSave(
        pageUrl: String,
        html: String,
        candidates: List<MediaCandidate>
    ) {
        val jobId = UUID.randomUUID().toString()
        val collectionTitle = selectedCollectionTitle()
        val pageTitle = pageTitleLabel(pageUrl)
        val jobFile = runCatching {
            val jobsDir = File(cacheDir, "archive_jobs").apply { mkdirs() }
            File.createTempFile("archive_job_", ".json", jobsDir).apply {
                writeText(
                    JSONObject().apply {
                        put("id", jobId)
                        put("title", pageTitle)
                        put("url", pageUrl)
                        put("html", html)
                        put("collectionTitle", collectionTitle)
                        put(
                            "mediaCandidates",
                            JSONArray().apply {
                                candidates
                                    .filter { it.sourceUrl.isNotBlank() }
                                    .distinctBy { "${it.mediaType}:${it.sourceUrl}" }
                                    .forEach { candidate ->
                                        put(JSONObject().apply {
                                            put("url", candidate.sourceUrl)
                                            put("mediaType", candidate.mediaType)
                                        })
                                    }
                            }
                        )
                    }.toString(),
                    Charsets.UTF_8
                )
            }
        }.getOrElse { error ->
            finishSaveWithError(error.message ?: "저장 작업 파일을 만들지 못했습니다.")
            return
        }

        ArchiveJobStore.upsert(
            ArchiveJobStore.Job(
                id = jobId,
                title = pageTitle,
                url = pageUrl,
                collectionTitle = collectionTitle,
                status = "백그라운드 저장 대기",
                progress = 0
            )
        )

        val intent = Intent(this, ArchiveSaveService::class.java).apply {
            action = ArchiveSaveService.ACTION_ENQUEUE_SAVE
            putExtra(ArchiveSaveService.EXTRA_JOB_FILE_PATH, jobFile.absolutePath)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        binding.progressBar.progress = 100
        updateStatus("백그라운드 저장을 시작했습니다. 다른 링크도 계속 추가할 수 있습니다.")
        isSaving = false
        setButtonsEnabled(true)
        currentCaptureUrl = null
        htmlCaptureBuffer.setLength(0)
        applyPendingSharedUrlIfAny()
    }

    private fun uploadMediaCandidatesAndSave(
        pageUrl: String,
        html: String,
        candidates: List<MediaCandidate>
    ) {
        networkExecutor.execute {
            val result = runCatching {
                val uniqueCandidates = candidates
                    .filter { it.sourceUrl.isNotBlank() }
                    .distinctBy { "${it.mediaType}:${it.sourceUrl}" }

                var rewrittenHtml = html
                val total = uniqueCandidates.size

                uniqueCandidates.forEachIndexed { index, candidate ->
                    mainHandler.post {
                        updateStatus("미디어 업로드 중... ${index + 1}/$total")
                        binding.progressBar.progress = 75 + ((index * 12) / maxOf(total, 1))
                    }

                    val tempFile = downloadMediaToTempFile(candidate, pageUrl)
                    try {
                        val uploadedUrl = withNetworkRetry("미디어 업로드") {
                            uploadMediaFile(tempFile, candidate)
                        }
                        rewrittenHtml = rewrittenHtml.replace(candidate.sourceUrl, uploadedUrl)
                    } finally {
                        tempFile.delete()
                    }
                }

                withNetworkRetry("아카이브 저장") {
                    performArchiveRequest(pageUrl, rewrittenHtml)
                }
            }

            mainHandler.post {
                val (code, message) = result.getOrElse { -1 to (it.message ?: "알 수 없는 오류") }
                if (code in 200..299) {
                    binding.progressBar.progress = 100
                    updateStatus(message)
                } else {
                    finishSaveWithError(message)
                    return@post
                }
                isSaving = false
                setButtonsEnabled(true)
                currentCaptureUrl = null
                htmlCaptureBuffer.setLength(0)
            }
        }
    }

    private fun finishSaveWithError(message: String) {
        binding.progressBar.progress = 0
        updateStatus("저장 실패: $message")
        isSaving = false
        setButtonsEnabled(true)
        currentCaptureUrl = null
        htmlCaptureBuffer.setLength(0)
        applyPendingSharedUrlIfAny()
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.loadButton.isEnabled = enabled && !isSaving
        binding.saveButton.isEnabled = enabled && !isSaving
    }

    private fun updateStatus(message: String) {
        binding.statusText.text = message
    }

    private fun renderJobList(jobs: List<ArchiveJobStore.Job>) {
        val activeCount = jobs.count { !it.isFinished }
        val finishedCount = jobs.count { it.isFinished }
        binding.activeJobSummary.text = if (activeCount > 0) {
            "•  ${activeCount}개 진행 중"
        } else {
            getString(R.string.no_active_jobs)
        }
        binding.clearFinishedJobsButton.visibility = if (finishedCount > 0) View.VISIBLE else View.GONE

        binding.emptyJobsText.visibility = if (jobs.isEmpty()) View.VISIBLE else View.GONE
        binding.jobListContainer.visibility = if (jobs.isEmpty()) View.GONE else View.VISIBLE
        binding.jobListContainer.removeAllViews()

        jobs.reversed().take(5).forEach { job ->
            binding.jobListContainer.addView(createJobRow(job))
        }
    }

    private fun createJobRow(job: ArchiveJobStore.Job): View {
        val row = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            background = getDrawable(R.drawable.job_item_background)
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8.dp
            }
        }

        row.addView(LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER
            background = getDrawable(R.drawable.icon_tile_background)
            layoutParams = LinearLayout.LayoutParams(44.dp, 44.dp)
            addView(ImageView(this@MainActivity).apply {
                setImageResource(if (job.title.contains("영상") || job.status.contains("미디어")) R.drawable.ic_play_24 else R.drawable.ic_document_24)
                layoutParams = LinearLayout.LayoutParams(24.dp, 24.dp)
            })
        })

        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginStart = 12.dp
            }

            addView(TextView(this@MainActivity).apply {
                text = job.title.ifBlank { shortUrlLabel(job.url) }
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
            })

            addView(TextView(this@MainActivity).apply {
                text = "${job.collectionTitle}  ·  ${shortUrlLabel(job.url)}"
                setTextColor(
                    getColor(
                        when {
                            job.isFailed -> R.color.error
                            else -> R.color.text_secondary
                        }
                    )
                )
                textSize = 12f
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 4.dp
                }
            })
        })

        val rightStatus = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 12.dp
            }
        }

        rightStatus.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(52.dp, LinearLayout.LayoutParams.WRAP_CONTENT)

            addView(TextView(this@MainActivity).apply {
                text = if (job.isFinished && !job.isFailed) "" else "${job.progress}%"
                setTextColor(getColor(if (job.isFailed) R.color.error else R.color.accent))
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })

            addView(TextView(this@MainActivity).apply {
                text = when {
                    job.isFailed -> "실패"
                    job.isFinished -> "완료"
                    job.status.contains("업로드") -> "업로드 중"
                    else -> "저장 중"
                }
                setTextColor(getColor(if (job.isFailed) R.color.error else R.color.text_secondary))
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 1.dp
                }
            })
        })

        rightStatus.addView(CircularProgressView(this).apply {
            progress = if (job.isFinished && !job.isFailed) 100 else job.progress
            layoutParams = LinearLayout.LayoutParams(34.dp, 34.dp).apply {
                marginStart = 6.dp
            }
        })
        row.addView(rightStatus)

        return row
    }

    private fun pageTitleLabel(url: String): String {
        return binding.webView.title
            ?.trim()
            ?.substringBefore(" - ")
            ?.takeIf { it.isNotBlank() && !it.equals("about:blank", ignoreCase = true) }
            ?: shortUrlLabel(url)
    }

    private fun shortUrlLabel(url: String): String {
        return runCatching {
            val uri = Uri.parse(url)
            listOfNotNull(uri.host, uri.path).joinToString("")
        }.getOrDefault(url)
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

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
                "$label 실패: Archive Saver 서버 주소를 찾지 못했습니다. 휴대폰 인터넷, Private DNS 또는 VPN 상태를 확인한 뒤 다시 시도해주세요."
            hasCause<SocketTimeoutException>() ->
                "$label 실패: 서버 응답이 늦어졌습니다. 잠시 후 다시 시도해주세요."
            hasCause<IOException>() ->
                "$label 실패: 서버와 연결하지 못했습니다. 네트워크 상태를 확인한 뒤 다시 시도해주세요."
            message.isNullOrBlank() ->
                "$label 실패: 알 수 없는 오류가 발생했습니다."
            else -> message.orEmpty()
        }
    }

    private fun selectedCollectionTitle(): String {
        return binding.collectionInput.text
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.DEFAULT_COLLECTION_TITLE
    }

    private inner class ArchiveSaverBridge {
        @JavascriptInterface
        fun onCaptureStarted() {
            mainHandler.post {
                htmlCaptureBuffer.setLength(0)
                updateStatus("캡처한 HTML을 조립하는 중...")
                binding.progressBar.progress = 60
            }
        }

        @JavascriptInterface
        fun onHtmlChunk(chunk: String?) {
            if (chunk.isNullOrEmpty()) {
                return
            }

            synchronized(htmlCaptureBuffer) {
                htmlCaptureBuffer.append(chunk)
            }
        }

        @JavascriptInterface
        fun onCaptureFinished() {
            mainHandler.post {
                val html = synchronized(htmlCaptureBuffer) { htmlCaptureBuffer.toString() }
                val url = currentCaptureUrl
                if (html.isBlank() || url.isNullOrBlank()) {
                    finishSaveWithError("페이지 HTML을 읽지 못했습니다.")
                    return@post
                }

                collectMediaCandidatesAndSave(url, html)
            }
        }

        @JavascriptInterface
        fun onCaptureError(message: String?) {
            mainHandler.post {
                finishSaveWithError(message ?: "페이지 캡처 스크립트가 실패했습니다.")
            }
        }
    }

    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent == null) {
            return null
        }

        val candidates = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_TEXT),
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
            intent.dataString
        )

        for (candidate in candidates) {
            val extracted = extractFirstUrl(candidate)
            if (extracted != null) {
                return extracted
            }
        }

        return null
    }

    private fun extractFirstUrl(value: String?): String? {
        if (value.isNullOrBlank()) {
            return null
        }

        val regex = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
        return regex.find(value)?.value
    }

    private fun normalizeUrl(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            return null
        }

        val parsed = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            Uri.parse(trimmed)
        } else {
            Uri.parse("https://$trimmed")
        }

        return if (parsed.scheme.isNullOrBlank() || parsed.host.isNullOrBlank()) {
            null
        } else {
            normalizeFmkoreaDocumentUrl(parsed).toString()
        }
    }

    private fun normalizeFmkoreaDocumentUrl(uri: Uri): Uri {
        val host = uri.host.orEmpty().lowercase()
        if (host != "m.fmkorea.com" && host != "www.fmkorea.com" && host != "fmkorea.com") {
            return uri
        }

        val documentId = uri.getQueryParameter("document_srl")
            ?.takeIf { it.matches(Regex("""\d+""")) }
            ?: return uri

        val board = uri.getQueryParameter("mid")
            ?.takeIf { it.isNotBlank() }
            ?: uri.pathSegments.firstOrNull()
            ?: "best"

        return uri.buildUpon()
            .scheme("https")
            .authority("m.fmkorea.com")
            .encodedPath("/$board/$documentId")
            .encodedQuery(null)
            .fragment(null)
            .build()
    }

    private fun decodeJavascriptString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") {
            return ""
        }

        return runCatching {
            JSONArray("[$raw]").getString(0)
        }.getOrDefault("")
    }

    private fun parseMediaCandidates(rawJson: String?): List<MediaCandidate> {
        val json = decodeJavascriptString(rawJson)
        if (json.isBlank()) {
            return emptyList()
        }

        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val sourceUrl = item.optString("url")
                    val mediaType = item.optString("mediaType")
                    if (sourceUrl.isNotBlank() && mediaType.isNotBlank()) {
                        add(MediaCandidate(sourceUrl, mediaType))
                    }
                }
            }
        }.getOrDefault(emptyList())
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

    companion object {
        private const val PREPARE_PAGE_SCRIPT = """
            (async function() {
              const lazyAttrs = [
                'data-src',
                'data-lazy-src',
                'data-original',
                'data-url',
                'data-file',
                'data-image',
                'data-img',
                'data-thumb',
                'data-actualsrc'
              ];
              const srcsetAttrs = ['data-srcset', 'data-lazy-srcset'];
              const resolveUrl = (value) => {
                if (!value) return '';
                try {
                  return new URL(value, location.href).href;
                } catch (_) {
                  return value;
                }
              };
              const isPlaceholder = (value) => {
                const src = String(value || '').toLowerCase();
                return !src ||
                  src.startsWith('data:image') ||
                  src.includes('blank') ||
                  src.includes('loading') ||
                  src.includes('transparent') ||
                  src.includes('spacer');
              };
              document.querySelectorAll('img, video, audio, source, iframe').forEach((node) => {
                lazyAttrs.forEach((attr) => {
                  const value = node.getAttribute(attr);
                  if (value) {
                    const absoluteValue = resolveUrl(value);
                    node.setAttribute(attr, absoluteValue);
                    if (!node.getAttribute('src') || isPlaceholder(node.getAttribute('src'))) {
                      node.setAttribute('src', absoluteValue);
                    }
                  }
                });
                if (node.src) {
                  node.setAttribute('src', node.src);
                }
                srcsetAttrs.forEach((attr) => {
                  const srcset = node.getAttribute(attr);
                  if (srcset) {
                    node.setAttribute(attr, srcset);
                    if (!node.getAttribute('srcset')) {
                      node.setAttribute('srcset', srcset);
                    }
                  }
                });
                node.removeAttribute('loading');
              });
              document.querySelectorAll('video, audio').forEach((node) => {
                node.setAttribute('controls', '');
                node.removeAttribute('autoplay');
              });
              const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
              let stableCount = 0;
              let previousHeight = document.body.scrollHeight;
              for (let i = 0; i < 60; i += 1) {
                const targetY = Math.min(
                  document.body.scrollHeight,
                  Math.floor(i * window.innerHeight * 0.75)
                );
                window.scrollTo(0, targetY);
                await sleep(220);
                const currentHeight = document.body.scrollHeight;
                stableCount = currentHeight === previousHeight ? stableCount + 1 : 0;
                previousHeight = currentHeight;
                if (targetY >= currentHeight - window.innerHeight && stableCount >= 4) {
                  break;
                }
              }
              document.querySelectorAll('img').forEach((img) => {
                const bestSrc = img.currentSrc || img.src || '';
                if (bestSrc) {
                  img.setAttribute('src', bestSrc);
                }
              });
              window.scrollTo(0, 0);
              return true;
            })();
        """

        private const val CAPTURE_VIA_BRIDGE_SCRIPT = """
            (function() {
              try {
                const html = '<!DOCTYPE html>\n' + document.documentElement.outerHTML;
                const chunkSize = 180000;
                if (!window.ArchiveSaverBridge) {
                  return false;
                }
                window.ArchiveSaverBridge.onCaptureStarted();
                for (let i = 0; i < html.length; i += chunkSize) {
                  window.ArchiveSaverBridge.onHtmlChunk(html.slice(i, i + chunkSize));
                }
                window.ArchiveSaverBridge.onCaptureFinished();
                return true;
              } catch (error) {
                if (window.ArchiveSaverBridge) {
                  window.ArchiveSaverBridge.onCaptureError(String(error));
                }
                return false;
              }
            })();
        """

        private const val MEDIA_CANDIDATES_SCRIPT = """
            (function() {
              const items = [];
              const seen = new Set();
              const pushItem = (url, mediaType) => {
                if (!url) {
                  return;
                }
                let absoluteUrl = '';
                try {
                  absoluteUrl = new URL(url, location.href).href;
                } catch (_) {
                  absoluteUrl = url;
                }
                if (
                  !absoluteUrl ||
                  absoluteUrl.startsWith('data:') ||
                  absoluteUrl.startsWith('blob:') ||
                  absoluteUrl.startsWith('about:') ||
                  seen.has(mediaType + ':' + absoluteUrl)
                ) {
                  return;
                }
                seen.add(mediaType + ':' + absoluteUrl);
                items.push({ url: absoluteUrl, mediaType });
              };
              const pushSrcset = (srcset, mediaType) => {
                if (!srcset) {
                  return;
                }
                srcset.split(',').forEach((part) => {
                  const url = part.trim().split(/\s+/)[0];
                  pushItem(url, mediaType);
                });
              };

              document.querySelectorAll('video').forEach((video) => {
                if (video.src) {
                  pushItem(video.src, 'videos');
                }
                video.querySelectorAll('source').forEach((source) => {
                  if (source.src) {
                    pushItem(source.src, 'videos');
                  }
                });
              });

              document.querySelectorAll('audio').forEach((audio) => {
                if (audio.src) {
                  pushItem(audio.src, 'audio');
                }
                audio.querySelectorAll('source').forEach((source) => {
                  if (source.src) {
                    pushItem(source.src, 'audio');
                  }
                });
              });

              document.querySelectorAll('img').forEach((img) => {
                [
                  img.currentSrc,
                  img.src,
                  img.getAttribute('src'),
                  img.getAttribute('data-src'),
                  img.getAttribute('data-lazy-src'),
                  img.getAttribute('data-original'),
                  img.getAttribute('data-url'),
                  img.getAttribute('data-file'),
                  img.getAttribute('data-image'),
                  img.getAttribute('data-img'),
                  img.getAttribute('data-thumb'),
                  img.getAttribute('data-actualsrc')
                ].forEach((src) => pushItem(src, 'images'));
                pushSrcset(img.getAttribute('srcset'), 'images');
                pushSrcset(img.getAttribute('data-srcset'), 'images');
                pushSrcset(img.getAttribute('data-lazy-srcset'), 'images');
              });

              return JSON.stringify(items);
            })();
        """
    }
}
