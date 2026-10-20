package com.ribau.baixador

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.*
import android.widget.*

class InstagramLoginActivity : Activity() {
    private lateinit var browser: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var reading = false
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        root.addView(TextView(this).apply {
            text = "instagram.com • sessão salva somente neste app. Faça login diretamente no site e resolva qualquer confirmação solicitada."
            setPadding(20, 20, 20, 10)
        })
        root.addView(Button(this).apply {
            text = "Voltar e usar sessão"; isAllCaps = false
            setOnClickListener {
                CookieManager.getInstance().flush()
                if (InstagramSession.hasSession()) { setResult(RESULT_OK); finish() }
                else Toast.makeText(this@InstagramLoginActivity, "Conclua o login do Instagram primeiro.", Toast.LENGTH_LONG).show()
            }
        })
        val post = intent.getStringExtra("post")?.takeIf { InstagramUrls.shortcode(it) != null }
        val target = post?.let(InstagramUrls::canonical)
        if (target != null) root.addView(Button(this).apply {
            text = "Ler mídias deste post"; isAllCaps = false
            setOnClickListener { readPost(target, this) }
        })
        browser = WebView(this)
        browser.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = MediaHttp.USER_AGENT
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false)
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val allowed = request?.url?.toString()?.let(InstagramUrls::isInstagram) == true
                if (!allowed) Toast.makeText(this@InstagramLoginActivity,
                    "Use o login direto do Instagram nesta tela.", Toast.LENGTH_SHORT).show()
                return !allowed
            }
        }
        root.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        browser.loadUrl(if (InstagramSession.hasSession() && target != null) target
            else "https://www.instagram.com/accounts/login/" + (target?.let {
                "?next=" + java.net.URLEncoder.encode(java.net.URI(it).path, "UTF-8")
            } ?: ""))
    }
    private fun readPost(post: String, button: Button) {
        if (reading) return
        if (runCatching { java.net.URI(browser.url.orEmpty()).host }.getOrNull() != "www.instagram.com") {
            Toast.makeText(this, "Aguarde a página oficial do Instagram carregar.", Toast.LENGTH_LONG).show(); return
        }
        val id = InstagramUrls.mediaId(requireNotNull(InstagramUrls.shortcode(post)))
        reading = true; button.isEnabled = false; button.text = "Consultando este post…"
        // Fetch only post metadata on the official origin, using the browser's own session/network stack.
        // No JavaScript bridge, password interception or cookie export is exposed to the page.
        browser.evaluateJavascript("""
            window.__baixadorMediaResult = null;
            fetch('/api/v1/media/$id/info/', {credentials:'same-origin', headers:{'X-IG-App-ID':'936619743392459','X-Requested-With':'XMLHttpRequest'}})
              .then(async r => { const body = await r.text(); window.__baixadorMediaResult = JSON.stringify({status:r.status, body:body.slice(0,8000000)}); })
              .catch(() => { window.__baixadorMediaResult = JSON.stringify({status:0,body:''}); });
        """.trimIndent(), null)
        var attempts = 0
        val poll = object : Runnable {
            override fun run() {
                if (isDestroyed || isFinishing) return
                if (++attempts > 45) { fail(); return }
                if (browser.url?.let { InstagramUrls.isInstagram(it) } != true) { fail(); return }
                browser.evaluateJavascript("window.__baixadorMediaResult") { value ->
                    if (isDestroyed || isFinishing) return@evaluateJavascript
                    if (value == "null" || value.isNullOrBlank()) { handler.postDelayed(this, 700); return@evaluateJavascript }
                    runCatching {
                        val envelope = org.json.JSONObject(org.json.JSONTokener(value).nextValue().toString())
                        check(envelope.optInt("status") == 200)
                        val body = envelope.getString("body")
                        check(body.length <= 8_000_000)
                        val json = org.json.JSONObject(body)
                        CatalogParser.instagram(json, post)
                        val name = java.util.UUID.randomUUID().toString()
                        val folder = java.io.File(filesDir, "browser-metadata").apply { mkdirs() }
                        java.io.File(folder, "$name.json").writeText(body)
                        setResult(RESULT_OK, Intent().putExtra("postData", name).putExtra("post", post)); finish()
                    }.onFailure { fail() }
                }
            }
            private fun fail() {
                reading = false; button.isEnabled = true; button.text = "Ler mídias deste post"
                Toast.makeText(this@InstagramLoginActivity,
                    "O Instagram não liberou os dados. Conclua o login e qualquer confirmação no site; em caso de limite, aguarde e tente novamente.", Toast.LENGTH_LONG).show()
            }
        }
        handler.postDelayed(poll, 700)
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); browser.stopLoading(); browser.destroy(); super.onDestroy() }
}
