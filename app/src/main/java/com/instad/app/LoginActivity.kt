package com.instad.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Обычная страница входа Instagram в WebView. Пользователь вводит
 * логин и пароль сам, на странице Instagram; приложение забирает
 * только cookies сессии для yt-dlp.
 */
class LoginActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (CookieStore.export(this@LoginActivity)) {
                    Toast.makeText(this@LoginActivity,
                        R.string.login_done, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
        webView.loadUrl("https://www.instagram.com/accounts/login/")
    }
}
