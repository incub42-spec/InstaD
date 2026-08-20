package com.instad.app

import android.content.Context
import android.webkit.CookieManager
import java.io.File

/**
 * Экспорт cookies из WebView-сессии Instagram в файл формата Netscape,
 * который понимает yt-dlp (--cookies). Файл лежит в приватной папке
 * приложения и никуда не отправляется, кроме запросов к Instagram.
 */
object CookieStore {

    fun cookieFile(context: Context): File = File(context.filesDir, "instagram_cookies.txt")

    fun isLoggedIn(): Boolean =
        CookieManager.getInstance().getCookie("https://www.instagram.com")
            ?.contains("sessionid=") == true

    /** @return true, если в сессии есть sessionid (вход выполнен) */
    fun export(context: Context): Boolean {
        val raw = CookieManager.getInstance().getCookie("https://www.instagram.com")
            ?: return false
        // getCookie не отдаёт срок жизни — ставим условные полгода,
        // yt-dlp важно лишь, что cookie не просрочен
        val expiry = System.currentTimeMillis() / 1000 + 180L * 24 * 3600
        val sb = StringBuilder("# Netscape HTTP Cookie File\n")
        raw.split("; ").forEach { pair ->
            val i = pair.indexOf('=')
            if (i > 0) {
                val name = pair.substring(0, i).trim()
                val value = pair.substring(i + 1).trim()
                sb.append(".instagram.com\tTRUE\t/\tTRUE\t$expiry\t$name\t$value\n")
            }
        }
        cookieFile(context).writeText(sb.toString())
        return raw.contains("sessionid=")
    }
}
