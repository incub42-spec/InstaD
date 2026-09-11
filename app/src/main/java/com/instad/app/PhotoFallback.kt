package com.instad.app

import android.webkit.CookieManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Запасной путь для постов без видео: достаёт фотографию поста
 * из embed-страницы Instagram. Пока поддерживается главное фото
 * поста (для карусели — первое).
 */
object PhotoFallback {

    /** Куда складывать HTML для отладки, если фото не нашлось (внутреннее хранилище) */
    @JvmStatic var debugDir: File? = null

    // Именно такой «минимальный» UA заставляет Instagram отдать простую
    // серверную разметку embed-страницы вместо JS-приложения
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"

    fun looksLikeNoVideo(msg: String?): Boolean =
        msg != null && (msg.contains("There is no video in this post")
                || msg.contains("No video formats found"))

    fun download(url: String, dir: File): File {
        val shortcode = Regex("""instagram\.com/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""")
            .find(url)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("не удалось разобрать ссылку")

        val embedUrl = "https://www.instagram.com/p/$shortcode/embed/"
        // Анонимно Instagram отдаёт простую разметку с фото; страница
        // для вошедшего пользователя — гигантское JS-приложение
        val anon = httpGetText(embedUrl, withCookies = false)
        var imgUrl = extractImageUrl(anon)
        if (imgUrl == null) {
            android.util.Log.e("InstaD", "anon len=${anon.length} head=${anon.take(1200)}")
            val auth = httpGetText(embedUrl, withCookies = true)
            imgUrl = extractImageUrl(auth)
            if (imgUrl == null) {
                android.util.Log.e("InstaD", "auth len=${auth.length} head=${auth.take(1200)}")
                (debugDir ?: dir).resolve("embed_anon.html").writeText(anon)
                (debugDir ?: dir).resolve("embed_auth.html").writeText(auth)
                throw IllegalStateException("в посте не нашлось ни видео, ни фото")
            }
        }

        val file = File(dir, "$shortcode.jpg")
        httpGetFile(imgUrl, file)
        if (file.length() < 1024) throw IllegalStateException("фото не скачалось")
        return file
    }

    private fun extractImageUrl(html: String): String? {
        // Серверная разметка embed-страницы
        Regex("""<img[^>]*EmbeddedMediaImage[^>]*>""").find(html)?.value?.let { tag ->
            Regex("""src="([^"]+)"""").find(tag)?.groupValues?.get(1)
                ?.let { return it.replace("&amp;", "&") }
        }
        // JSON внутри страницы (в т.ч. вариант для вошедшего пользователя)
        Regex(""""display_url"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?.let { return unescapeJson(it) }
        Regex("""display_url\\":\\"([^\\"]+)\\"""").find(html)?.groupValues?.get(1)
            ?.let { return unescapeJson(it) }
        return null
    }

    private fun unescapeJson(s: String): String = s
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")

    private fun connect(url: String, withCookies: Boolean): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", UA)
        if (withCookies) {
            CookieManager.getInstance().getCookie("https://www.instagram.com")
                ?.let { conn.setRequestProperty("Cookie", it) }
        }
        return conn
    }

    private fun httpGetText(url: String, withCookies: Boolean): String {
        val conn = connect(url, withCookies)
        try {
            if (conn.responseCode != 200)
                throw IllegalStateException("Instagram ответил ${conn.responseCode}")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGetFile(url: String, dest: File) {
        // Фото лежит на CDN — cookies не нужны
        val conn = connect(url, withCookies = false)
        try {
            if (conn.responseCode != 200)
                throw IllegalStateException("не удалось скачать фото: ${conn.responseCode}")
            conn.inputStream.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        } finally {
            conn.disconnect()
        }
    }
}
