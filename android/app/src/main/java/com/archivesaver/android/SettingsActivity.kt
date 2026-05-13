package com.archivesaver.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.archivesaver.android.databinding.ActivitySettingsBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val dateFormat = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA)

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

        renderHistory()
        renderCacheSize()
        renderAppInfo()
    }

    override fun onResume() {
        super.onResume()
        renderHistory()
        renderCacheSize()
    }

    private fun renderHistory() {
        val entries = ArchiveHistoryStore.getAll(this)
        binding.historyEmptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.historyListContainer.removeAllViews()

        val visibleEntries = entries.take(3)
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
        return LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.archiveUrl)))
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
                        text = dateFormat.format(Date(entry.savedAt))
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
                setImageResource(R.drawable.ic_external_20)
                contentDescription = getString(R.string.open_archive)
                layoutParams = LinearLayout.LayoutParams(24.dp, 24.dp).apply {
                    marginStart = 12.dp
                }
            })
        }
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
