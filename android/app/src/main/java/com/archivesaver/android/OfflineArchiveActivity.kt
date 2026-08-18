package com.archivesaver.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class OfflineArchiveActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val indexPath = intent.getStringExtra(EXTRA_INDEX_PATH).orEmpty()
        val indexFile = File(indexPath)
        if (!indexFile.isFile) {
            Toast.makeText(this, "오프라인 사본을 찾지 못했습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            webChromeClient = WebChromeClient()
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        setContentView(webView)
        webView.loadUrl(Uri.fromFile(indexFile).toString())
    }

    companion object {
        private const val EXTRA_INDEX_PATH = "com.archivesaver.android.extra.OFFLINE_INDEX_PATH"

        fun intent(context: Context, indexPath: String): Intent {
            return Intent(context, OfflineArchiveActivity::class.java)
                .putExtra(EXTRA_INDEX_PATH, indexPath)
        }
    }
}
