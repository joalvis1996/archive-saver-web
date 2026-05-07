package com.archivesaver.android

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

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

        configureWebView()
        configureButtons()
        handleIncomingIntent(intent, autoSave = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent, autoSave = true)
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

    private fun handleIncomingIntent(intent: Intent?, autoSave: Boolean) {
        val sharedUrl = extractSharedUrl(intent) ?: return
        binding.urlInput.setText(sharedUrl)
        updateStatus("공유된 페이지를 열고 있습니다...")
        loadUrl(sharedUrl, autoSave)
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

    private fun postArchiveRequest(url: String, html: String) {
        networkExecutor.execute {
            val result = runCatching {
                val endpoint = URL("${BuildConfig.ARCHIVE_API_BASE_URL}/api/save-html")
                val body = JSONObject().apply {
                    put("url", url)
                    put("html", html)
                    put("collectionTitle", BuildConfig.DEFAULT_COLLECTION_TITLE)
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

                responseCode to if (archiveUrl.isBlank()) message else "$message\n$archiveUrl"
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

                updateStatus("캡처한 페이지를 서버에 저장하는 중...")
                binding.progressBar.progress = 75
                postArchiveRequest(url, html)
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
    }
}
