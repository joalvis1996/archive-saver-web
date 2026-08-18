package com.archivesaver.android

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.archivesaver.android.databinding.ActivitySettingsBinding
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val dateFormat = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isShowingAllHistory = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.clearCacheButton.setOnClickListener {
            clearTemporaryCache()
            renderCacheSize()
            binding.root.postDelayed({ renderCacheSize() }, 500L)
            Toast.makeText(this, R.string.cache_cleaned, Toast.LENGTH_SHORT).show()
        }

        binding.allHistoryButton.setOnClickListener {
            isShowingAllHistory = !isShowingAllHistory
            renderHistory()
        }

        binding.importRaindropButton.setOnClickListener {
            showRaindropImportDialog()
        }

        renderHistory()
        renderCacheSize()
        renderAppInfo()
    }

    override fun onResume() {
        super.onResume()
        renderHistory()
        renderCacheSize()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun renderHistory() {
        val entries = ArchiveHistoryStore.getAll(this)
        binding.historyEmptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.historyListContainer.removeAllViews()

        binding.allHistoryButton.visibility = if (entries.size > 3) View.VISIBLE else View.GONE
        binding.allHistoryButton.text = if (isShowingAllHistory) {
            getString(R.string.collapse)
        } else {
            getString(R.string.view_all)
        }

        val visibleEntries = if (isShowingAllHistory) entries else entries.take(3)
        visibleEntries.forEachIndexed { index, entry ->
            binding.historyListContainer.addView(createHistoryRow(entry))
            if (index < visibleEntries.lastIndex) {
                binding.historyListContainer.addView(View(this).apply {
                    setBackgroundColor(getColor(R.color.border_subtle))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1.dp
                    ).apply {
                        marginStart = 56.dp
                        topMargin = 10.dp
                        bottomMargin = 10.dp
                    }
                })
            }
        }
    }

    private fun createHistoryRow(entry: ArchiveHistoryStore.Entry): View {
        val hasOfflineArchive = OfflineArchiveStore.hasLocalArchive(entry.localArchivePath)
        return LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                if (hasOfflineArchive) {
                    startActivity(OfflineArchiveActivity.intent(this@SettingsActivity, entry.localArchivePath))
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.archiveUrl)))
                }
            }

            addView(LinearLayout(this@SettingsActivity).apply {
                gravity = android.view.Gravity.CENTER
                background = getDrawable(R.drawable.icon_tile_background)
                layoutParams = LinearLayout.LayoutParams(44.dp, 44.dp)

                addView(ImageView(this@SettingsActivity).apply {
                    setImageResource(if (entry.title.contains("영상")) R.drawable.ic_play_24 else R.drawable.ic_document_24)
                    layoutParams = LinearLayout.LayoutParams(24.dp, 24.dp)
                })
            })

            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginStart = 12.dp
                }

                addView(TextView(this@SettingsActivity).apply {
                    text = entry.title.ifBlank { shortUrlLabel(entry.sourceUrl) }
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    maxLines = 1
                })

                addView(LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = 4.dp
                    }

                    addView(TextView(this@SettingsActivity).apply {
                        text = entry.collectionTitle
                        background = getDrawable(R.drawable.status_chip_background)
                        setTextColor(getColor(R.color.accent))
                        textSize = 11f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        maxLines = 1
                    })

                    addView(TextView(this@SettingsActivity).apply {
                        text = if (hasOfflineArchive) {
                            val bytes = entry.offlineSizeBytes.takeIf { it > 0 }
                                ?: OfflineArchiveStore.sizeBytes(entry.localArchivePath)
                            "오프라인 ${formatBytes(bytes)} · ${dateFormat.format(Date(entry.savedAt))}"
                        } else {
                            dateFormat.format(Date(entry.savedAt))
                        }
                        setTextColor(getColor(R.color.text_secondary))
                        textSize = 12f
                        maxLines = 1
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            marginStart = 10.dp
                        }
                    })
                })
            })

            addView(ImageView(this@SettingsActivity).apply {
                setImageResource(if (hasOfflineArchive) R.drawable.ic_open_20 else R.drawable.ic_external_20)
                contentDescription = getString(R.string.open_archive)
                layoutParams = LinearLayout.LayoutParams(24.dp, 24.dp).apply {
                    marginStart = 12.dp
                }
            })
        }
    }

    private fun showRaindropImportDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 20.dp, 24.dp, 20.dp)
        }

        val titleView = TextView(this).apply {
            text = getString(R.string.raindrop_import_section_title)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
        }
        dialogView.addView(titleView)

        val spinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                48.dp
            ).apply { topMargin = 14.dp }
        }
        dialogView.addView(spinner)

        val statusTextView = TextView(this).apply {
            text = "컬렉션 및 북마크 목록 조회 중..."
            setTextColor(getColor(R.color.text_secondary))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12.dp }
        }
        dialogView.addView(statusTextView)

        val bookmarkListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                180.dp
            ).apply { topMargin = 10.dp }
        }
        val scrollView = androidx.core.widget.NestedScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                180.dp
            )
            addView(bookmarkListContainer)
        }
        dialogView.addView(scrollView)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("가져오기 및 오프라인 저장", null)
            .setNegativeButton("취소", null)
            .create()

        dialog.show()

        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positiveButton.isEnabled = false

        data class BookmarkItem(val title: String, val link: String)
        val collectionsList = mutableListOf<Pair<String, Long>>()
        val currentBookmarks = mutableListOf<BookmarkItem>()

        // 1. 컬렉션 조회
        executor.execute {
            try {
                val collectionsUrl = "${BuildConfig.ARCHIVE_API_BASE_URL}/api/collections"
                val response = httpGet(collectionsUrl)
                if (response.trim().startsWith("<")) {
                    throw IllegalStateException("서버가 최신 버전으로 배포되지 않았거나 HTML을 반환했습니다.")
                }
                val jsonArray = JSONArray(response)
                collectionsList.clear()
                collectionsList.add("전체 북마크 (All)" to 0L)
                collectionsList.add("미분류 (Unsorted)" to -1L)
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(i) ?: continue
                    val title = item.optString("title", "컬렉션")
                    val id = item.optLong("_id", 0L)
                    collectionsList.add(title to id)
                }

                mainHandler.post {
                    val adapter = ArrayAdapter(
                        this,
                        android.R.layout.simple_spinner_dropdown_item,
                        collectionsList.map { it.first }
                    )
                    spinner.adapter = adapter
                }
            } catch (e: Exception) {
                mainHandler.post {
                    statusTextView.text = "컬렉션 조회 실패: ${e.message}"
                }
            }
        }

        // 2. 스피너 선택 시 북마크 목록 조회
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = collectionsList.getOrNull(position) ?: return
                val collectionId = selected.second
                statusTextView.text = "'${selected.first}' 북마크 불러오는 중..."
                bookmarkListContainer.removeAllViews()
                positiveButton.isEnabled = false

                executor.execute {
                    try {
                        val bookmarksUrl = "${BuildConfig.ARCHIVE_API_BASE_URL}/api/raindrop/bookmarks?collectionId=$collectionId&perpage=50"
                        val response = httpGet(bookmarksUrl)
                        if (response.trim().startsWith("<")) {
                            throw IllegalStateException("백엔드 서버에 /api/raindrop/bookmarks 배포가 필요합니다.")
                        }
                        val resObj = JSONObject(response)
                        val itemsArray = resObj.optJSONArray("items") ?: JSONArray()
                        currentBookmarks.clear()
                        for (i in 0 until itemsArray.length()) {
                            val item = itemsArray.optJSONObject(i) ?: continue
                            val title = item.optString("title", "제목 없음")
                            val link = item.optString("link", "")
                            if (link.startsWith("http://") || link.startsWith("https://")) {
                                currentBookmarks.add(BookmarkItem(title, link))
                            }
                        }

                        mainHandler.post {
                            statusTextView.text = "가져올 북마크: ${currentBookmarks.size}개"
                            bookmarkListContainer.removeAllViews()
                            currentBookmarks.forEach { bm ->
                                val row = TextView(this@SettingsActivity).apply {
                                    text = "• ${bm.title}\n   ${bm.link}"
                                    textSize = 12f
                                    setTextColor(getColor(R.color.text_primary))
                                    setPadding(4.dp, 4.dp, 4.dp, 4.dp)
                                    maxLines = 2
                                }
                                bookmarkListContainer.addView(row)
                            }
                            positiveButton.isEnabled = currentBookmarks.isNotEmpty()
                        }
                    } catch (e: Exception) {
                        mainHandler.post {
                            statusTextView.text = "북마크 조회 실패: ${e.message}"
                        }
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        // 3. 가져오기 시작 버튼
        positiveButton.setOnClickListener {
            dialog.dismiss()
            startBulkImport(currentBookmarks)
        }
    }

    private fun startBulkImport(bookmarks: List<Any>) {
        if (bookmarks.isEmpty()) return

        val progressDialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 20.dp, 24.dp, 20.dp)
        }

        val titleView = TextView(this).apply {
            text = "Raindrop 북마크 가져오는 중"
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
        }
        progressDialogView.addView(titleView)

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = bookmarks.size
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16.dp }
        }
        progressDialogView.addView(progressBar)

        val progressStatusText = TextView(this).apply {
            text = "준비 중..."
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8.dp }
        }
        progressDialogView.addView(progressStatusText)

        val progressDialog = AlertDialog.Builder(this)
            .setView(progressDialogView)
            .setCancelable(false)
            .create()

        progressDialog.show()

        executor.execute {
            var successCount = 0
            var failCount = 0

            bookmarks.forEachIndexed { index, bmObj ->
                val (title, link) = if (bmObj is Pair<*, *>) {
                    bmObj.first.toString() to bmObj.second.toString()
                } else {
                    // Reflection or smart cast
                    val t = bmObj.javaClass.getMethod("getTitle").invoke(bmObj) as String
                    val l = bmObj.javaClass.getMethod("getLink").invoke(bmObj) as String
                    t to l
                }

                mainHandler.post {
                    progressBar.progress = index + 1
                    progressStatusText.text = "(${index + 1}/${bookmarks.size}) '$title' 저장 중..."
                }

                try {
                    val archiveId = UUID.randomUUID().toString()
                    val offlineDir = OfflineArchiveStore.prepareDirectory(this@SettingsActivity, archiveId)

                    // 1. 서버에 HTML 아카이브 요청
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
                            this@SettingsActivity,
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
                } catch (e: Exception) {
                    failCount++
                }

                Thread.sleep(150)
            }

            mainHandler.post {
                progressDialog.dismiss()
                renderHistory()
                Toast.makeText(
                    this@SettingsActivity,
                    "Raindrop 가져오기 완료: 성공 ${successCount}건, 실패 ${failCount}건",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun httpGet(urlStr: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 25000
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

    private fun renderCacheSize() {
        binding.cacheSizeText.text = formatBytes(cacheDir.sizeBytes())
    }

    private fun renderAppInfo() {
        binding.versionText.text = BuildConfig.VERSION_NAME
        binding.serverText.text = BuildConfig.ARCHIVE_API_BASE_URL.removePrefix("https://")
    }

    private fun clearTemporaryCache() {
        val cutoff = System.currentTimeMillis() - 5 * 60 * 1000L
        cacheDir.walkTopDown()
            .filter { it.isFile }
            .filter { file ->
                file.lastModified() < cutoff &&
                    (file.name.startsWith("archive_") || file.parentFile?.name == "archive_jobs")
            }
            .forEach { file -> runCatching { file.delete() } }

        runCatching { WebStorage.getInstance().deleteAllData() }
        runCatching {
            WebView(this).apply {
                clearCache(true)
                clearHistory()
                destroy()
            }
        }
    }

    private fun File.sizeBytes(): Long {
        if (!exists()) return 0L
        if (isFile) return length()
        return walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.1f MB", mb)
        } else {
            "${bytes / 1024} KB"
        }
    }

    private fun shortUrlLabel(url: String): String {
        return runCatching {
            val uri = Uri.parse(url)
            listOfNotNull(uri.host, uri.path).joinToString("")
        }.getOrDefault(url)
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
