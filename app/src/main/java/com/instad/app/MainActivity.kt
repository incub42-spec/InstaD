package com.instad.app

import android.content.ContentValues
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private companion object {
        const val KEY_LAST_SHARED = "last_shared_url"
    }

    private lateinit var urlInput: EditText
    private lateinit var downloadBtn: Button
    private lateinit var shareBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var previewImage: ImageView
    private lateinit var previewHint: TextView

    /** Uri последнего скачанного видео (MediaStore или FileProvider) для пересылки */
    private var lastVideoUri: Uri? = null
    private var lastMime: String = "video/*"
    private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        downloadBtn = findViewById(R.id.downloadBtn)
        shareBtn = findViewById(R.id.shareBtn)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        previewImage = findViewById(R.id.previewImage)
        previewHint = findViewById(R.id.previewHint)
        previewImage.setOnClickListener { openInGallery() }
        PhotoFallback.debugDir = filesDir

        downloadBtn.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(this, R.string.error_empty_url, Toast.LENGTH_SHORT).show()
            } else {
                startDownload(url)
            }
        }

        shareBtn.setOnClickListener { shareVideo() }

        findViewById<Button>(R.id.downloadsBtn).setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }

        findViewById<Button>(R.id.loginBtn).setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        // Только при первом создании: при пересоздании экрана (смена размера окна,
        // возврат из «недавних») тот же intent приходит снова, и качать повторно не нужно
        if (savedInstanceState == null) handleIntent(intent, fresh = false)
    }

    override fun onResume() {
        super.onResume()
        findViewById<Button>(R.id.loginBtn).setText(
            if (CookieStore.isLoggedIn()) R.string.btn_login_done else R.string.btn_login)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, fresh = true)
    }

    /**
     * Ссылка, прилетевшая через «Поделиться» из Instagram.
     * [fresh] = true — реальное новое действие пользователя (onNewIntent), качаем всегда.
     * [fresh] = false — intent, с которым задача была запущена когда-то; Android
     * доставляет его снова при перезапуске из «недавних», поэтому ту же ссылку,
     * что уже обрабатывали, второй раз не качаем — только показываем в поле.
     */
    private fun handleIntent(intent: Intent?, fresh: Boolean) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("""https?://\S+""").find(text)?.value ?: return
        urlInput.setText(url)
        val prefs = getSharedPreferences("main", MODE_PRIVATE)
        if (!fresh && prefs.getString(KEY_LAST_SHARED, null) == url) return
        prefs.edit().putString(KEY_LAST_SHARED, url).apply()
        startDownload(url)
    }

    private fun startDownload(url: String) {
        if (downloading) return
        downloading = true
        downloadBtn.isEnabled = false
        shareBtn.visibility = android.view.View.GONE
        previewImage.visibility = android.view.View.GONE
        previewHint.visibility = android.view.View.GONE
        progressBar.visibility = android.view.View.VISIBLE
        progressBar.isIndeterminate = true
        statusText.text = getString(R.string.status_init)
        lastVideoUri = null

        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    Engine.ensureInit(applicationContext)
                    // Отдельная папка на каждую загрузку, чтобы точно знать, какой файл наш
                    val dir = File(File(getExternalFilesDir(null), "downloads"),
                        System.currentTimeMillis().toString()).apply { mkdirs() }
                    val request = YoutubeDLRequest(url).apply {
                        addOption("--no-mtime")
                        // Instagram стал отдавать видео и звук раздельными DASH-потоками
                        // в VP9. Их пришлось бы склеивать через ffmpeg, а VP9 в mp4
                        // плохо принимают галерея и мессенджеры. Поэтому сначала берём
                        // готовый единый файл (H.264+AAC) и лишь при его отсутствии —
                        // склейку раздельных потоков.
                        addOption("-f", "b/bv*+ba/best")
                        addOption("-o", "${dir.absolutePath}/%(id)s.%(ext)s")
                        // Если пользователь вошёл в Instagram — качаем от имени
                        // его сессии, иначе часть постов недоступна
                        val cookies = CookieStore.cookieFile(applicationContext)
                        if (cookies.exists()) addOption("--cookies", cookies.absolutePath)
                    }
                    try {
                        YoutubeDL.getInstance().execute(request, url.hashCode().toString()) { progress, _, _ ->
                            runOnUiThread {
                                if (progress >= 0) {
                                    progressBar.isIndeterminate = false
                                    progressBar.progress = progress.toInt()
                                    statusText.text = getString(R.string.status_downloading, progress.toInt())
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Пост без видео — пробуем достать фото
                        if (PhotoFallback.looksLikeNoVideo(e.message)) {
                            PhotoFallback.download(url, dir)
                        } else throw e
                    }
                    dir.listFiles()?.maxByOrNull { it.length() }
                        ?: throw IllegalStateException("файл не скачался")
                }

                statusText.text = getString(R.string.status_processing)
                lastVideoUri = withContext(Dispatchers.IO) { exportMedia(file) }
                lastMime = mimeTypeOf(file)

                // Превью скачанного: кадр видео или само фото; тап открывает в галерее
                withContext(Dispatchers.IO) { makeThumb(file) }?.let {
                    previewImage.setImageBitmap(it)
                    previewImage.visibility = android.view.View.VISIBLE
                    previewHint.visibility = android.view.View.VISIBLE
                }

                statusText.text = getString(
                    if (isImage(file)) R.string.status_done_photo else R.string.status_done)
                shareBtn.visibility = android.view.View.VISIBLE
            } catch (e: Exception) {
                statusText.text = getString(R.string.status_error, humanError(e))
            } finally {
                downloading = false
                downloadBtn.isEnabled = true
                progressBar.visibility = android.view.View.GONE
            }
        }
    }

    private fun isImage(file: File) =
        file.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "heic")

    /**
     * Сохраняет файл в галерею — всё в DCIM/InstaD, чтобы был один альбом и возвращает
     * Uri для пересылки. На Android 9 и ниже галерея недоступна без разрешений —
     * файл остаётся в папке приложения, пересылка работает через FileProvider.
     */
    private fun exportMedia(file: File): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val image = isImage(file)
            val collection = if (image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "InstaD_${file.name}")
                put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeOf(file))
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DCIM + "/InstaD")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("не удалось сохранить в галерею")
            resolver.openOutputStream(uri)!!.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        }
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }

    private fun mimeTypeOf(file: File) = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        else -> "video/mp4"
    }

    /** Вытаскивает из вывода yt-dlp только строки ERROR, без предупреждений */
    private fun humanError(e: Exception): String {
        val msg = e.message ?: return e.javaClass.simpleName
        val errors = msg.lines().filter { it.startsWith("ERROR:") }
        return if (errors.isNotEmpty()) errors.joinToString("\n") else msg
    }

    /** Миниатюра для превью: для фото — уменьшенная картинка, для видео — кадр на 1-й секунде */
    private fun makeThumb(file: File): Bitmap? = runCatching {
        if (isImage(file)) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 1080) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        } else {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(file.path)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
                    mmr.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 720, 1280)
                else
                    mmr.getFrameAtTime(1_000_000)
            } finally {
                mmr.release()
            }
        }
    }.getOrNull()

    /** Открыть скачанный файл в галерее / системном просмотрщике */
    private fun openInGallery() {
        val uri = lastVideoUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, lastMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_no_viewer, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareVideo() {
        val uri = lastVideoUri ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = lastMime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
    }
}
