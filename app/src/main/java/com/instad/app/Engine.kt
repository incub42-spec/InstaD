package com.instad.app

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Ленивая одноразовая инициализация yt-dlp и ffmpeg.
 * Инициализация распаковывает python-окружение, поэтому выполняется
 * в фоне и строго один раз.
 */
object Engine {
    private val mutex = Mutex()
    private const val PREFS = "engine"
    private const val KEY_LAST_UPDATE = "last_ytdlp_update_nightly"
    private const val UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L

    @Volatile
    private var initialized = false

    suspend fun ensureInit(context: Context) {
        if (initialized) return
        mutex.withLock {
            if (initialized) return
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
            updateIfStale(context)
            initialized = true
        }
    }

    /**
     * Instagram часто ломает старые версии yt-dlp, поэтому обновляем
     * встроенный бинарник, но не чаще раза в сутки. Ошибки обновления
     * (нет сети и т.п.) не мешают попытке скачивания.
     */
    private fun updateIfStale(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_UPDATE, 0L)
        if (System.currentTimeMillis() - last < UPDATE_INTERVAL_MS) return
        runCatching {
            // Nightly: починки экстрактора Instagram попадают туда раньше стабильных релизов
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.NIGHTLY)
            prefs.edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
        }
    }
}
