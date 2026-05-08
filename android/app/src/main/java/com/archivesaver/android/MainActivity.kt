package com.archivesaver.android

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
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
    private val htmlCaptureBuffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureCollectionPicker()
        configureWebView()
        configureButtons()
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

    override fun onDestroy() {
        super.onDestroy()
        networkExecutor.shutdownNow()
    }

    private fun configureButtons() {
        binding.loadButton.setOnClickListener {
            loadCurrentInput(autoSave = false)
        }

        binding.saveButton.setOnClickListener {
            if (binding.webView.url.isNullOrBlank()) {
                loadCurrentInput(autoSave = true)
            } else {
                captureAndSaveCurrentPage()
            }
        }
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
        binding.urlInput.setText(sharedUrl)
        binding.collectionInput.setText(getString(R.string.collection_default), false)
        updateStatus("공유된 링크를 입력했습니다. 페이지를 열고 저장을 눌러주세요.")
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
            val result = runCatching { performArchiveRequest(url, html) }

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
            if (candidates.isEmpty()) {
                updateStatus("캡처한 페이지를 서버에 저장하는 중...")
                binding.progressBar.progress = 78
                postArchiveRequest(pageUrl, html)
                return@evaluateJavascript
            }

            uploadMediaCandidatesAndSave(pageUrl, html, candidates)
        }
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
                    val uploadedUrl = uploadMediaFile(tempFile, candidate)
                    rewrittenHtml = rewrittenHtml.replace(candidate.sourceUrl, uploadedUrl)
                    tempFile.delete()
                }

                performArchiveRequest(pageUrl, rewrittenHtml)
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
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.loadButton.isEnabled = enabled && !isSaving
        binding.saveButton.isEnabled = enabled && !isSaving
    }

    private fun updateStatus(message: String) {
        binding.statusText.text = message
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
            parsed.toString()
        }
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
              const lazyAttrs = ['data-src', 'data-lazy-src', 'data-original', 'data-url'];
              document.querySelectorAll('img, video, audio, source, iframe').forEach((node) => {
                lazyAttrs.forEach((attr) => {
                  const value = node.getAttribute(attr);
                  if (value && !node.getAttribute('src')) {
                    node.setAttribute('src', value);
                  }
                });
                if (node.src) {
                  node.setAttribute('src', node.src);
                }
                const srcset = node.getAttribute('data-srcset');
                if (srcset && !node.getAttribute('srcset')) {
                  node.setAttribute('srcset', srcset);
                }
              });
              document.querySelectorAll('video, audio').forEach((node) => {
                node.setAttribute('controls', '');
                node.removeAttribute('autoplay');
              });
              const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
              let previousHeight = 0;
              for (let i = 0; i < 20; i += 1) {
                window.scrollTo(0, document.body.scrollHeight);
                await sleep(350);
                const currentHeight = document.body.scrollHeight;
                if (currentHeight === previousHeight) {
                  break;
                }
                previousHeight = currentHeight;
              }
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
                if (!url || seen.has(mediaType + ':' + url)) {
                  return;
                }
                seen.add(mediaType + ':' + url);
                items.push({ url, mediaType });
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
                const src = img.currentSrc || img.src || '';
                if (/\.gif(?:$|\?)/i.test(src)) {
                  pushItem(src, 'images');
                }
              });

              return JSON.stringify(items);
            })();
        """
    }
}
