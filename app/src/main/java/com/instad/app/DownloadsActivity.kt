package com.instad.app

import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Экран «Мои загрузки»: сетка всего, что InstaD сохранил в галерею.
 * Тап — открыть, долгое нажатие — поделиться или удалить.
 */
class DownloadsActivity : AppCompatActivity() {

    data class Item(val uri: Uri, val mime: String, val isVideo: Boolean)

    private val items = mutableListOf<Item>()
    private lateinit var adapter: ItemAdapter
    private lateinit var emptyText: TextView
    private val thumbs = LruCache<Uri, Bitmap>(60)

    // Android 11+: удаление файла, который система не считает «нашим»
    // (например, после переустановки), требует подтверждения пользователя
    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()) { reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        emptyText = findViewById(R.id.emptyText)
        val grid = findViewById<GridView>(R.id.grid)
        adapter = ItemAdapter()
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ -> open(items[pos]) }
        grid.setOnItemLongClickListener { _, _, pos, _ -> showActions(items[pos]); true }
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { queryAll() }
            items.clear()
            items.addAll(list)
            adapter.notifyDataSetChanged()
            emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /** Все файлы InstaD из галереи: текущая папка DCIM/InstaD и старые Movies/Pictures */
    private fun queryAll(): List<Item> {
        val result = mutableListOf<Pair<Long, Item>>()
        val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val collections = listOf(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false)
        for ((collection, isVideo) in collections) {
            contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.DATE_ADDED),
                "$pathCol LIKE ?", arrayOf("%InstaD%"), null
            )?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                    val mime = c.getString(1) ?: if (isVideo) "video/*" else "image/*"
                    result += c.getLong(2) to Item(uri, mime, isVideo)
                }
            }
        }
        return result.sortedByDescending { it.first }.map { it.second }
    }

    private fun loadThumb(item: Item): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentResolver.loadThumbnail(item.uri, Size(400, 400), null)
        } else {
            val id = ContentUris.parseId(item.uri)
            @Suppress("DEPRECATION")
            if (item.isVideo)
                MediaStore.Video.Thumbnails.getThumbnail(contentResolver, id,
                    MediaStore.Video.Thumbnails.MINI_KIND, null)
            else
                MediaStore.Images.Thumbnails.getThumbnail(contentResolver, id,
                    MediaStore.Images.Thumbnails.MINI_KIND, null)
        }
    }.getOrNull()

    private fun open(item: Item) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, item.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, R.string.error_no_viewer, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showActions(item: Item) {
        val actions = arrayOf(getString(R.string.action_share), getString(R.string.action_delete))
        AlertDialog.Builder(this)
            .setItems(actions) { _, which -> if (which == 0) share(item) else confirmDelete(item) }
            .show()
    }

    private fun share(item: Item) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = item.mime
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
    }

    private fun confirmDelete(item: Item) {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> delete(item) }
            .show()
    }

    private fun delete(item: Item) {
        try {
            contentResolver.delete(item.uri, null, null)
            thumbs.remove(item.uri)
            reload()
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pending = MediaStore.createDeleteRequest(contentResolver, listOf(item.uri))
                deleteLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
            } else {
                Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    inner class ItemAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false)
            val item = items[position]
            val img = view.findViewById<ImageView>(R.id.thumb)
            view.findViewById<View>(R.id.playBadge).visibility =
                if (item.isVideo) View.VISIBLE else View.GONE
            img.tag = item.uri
            val cached = thumbs.get(item.uri)
            if (cached != null) {
                img.setImageBitmap(cached)
            } else {
                img.setImageBitmap(null)
                lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) { loadThumb(item) } ?: return@launch
                    thumbs.put(item.uri, bmp)
                    // Ячейка могла быть переиспользована под другой файл, пока грузилась миниатюра
                    if (img.tag == item.uri) img.setImageBitmap(bmp)
                }
            }
            return view
        }
    }
}
