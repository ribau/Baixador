package com.ribau.baixador

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.StatusBarManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.*
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.*
import com.yausername.youtubedl_android.YoutubeDL
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

open class QuickActivity : MainActivity()
class ClipboardActivity : QuickActivity()

open class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val images = Executors.newFixedThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())
    private val analysisId = "info-${UUID.randomUUID()}"
    private lateinit var link: EditText
    private lateinit var infoText: TextView
    private lateinit var statusText: TextView
    private lateinit var selectionText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var analyze: Button
    private lateinit var paste: Button
    private lateinit var download: Button
    private lateinit var update: Button
    private lateinit var login: Button
    private lateinit var logout: Button
    private lateinit var selectAll: Button
    private lateinit var clearSelection: Button
    private lateinit var itemView: LinearLayout
    private lateinit var historyView: LinearLayout
    private lateinit var cancel: Button
    private var catalog: MediaCatalog? = null
    private var busy = false
    private var generation = 0
    private var pasteOnFocus = false
    private var lastClipboard: String? = null
    private var historyFingerprint = ""
    private var clipboardDialog: AlertDialog? = null
    private data class Row(val item: MediaItem, val check: CheckBox, val quality: Spinner)
    private val rows = mutableListOf<Row>()
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val preferences by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { if (hasWindowFocus()) checkClipboard() }
    private val ticker = object : Runnable { override fun run() { refresh(); handler.postDelayed(this, 700) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(20)) }
        val scroll = ScrollView(this).apply { addView(layout); isFillViewport = true }
        if (this !is QuickActivity) scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                else { @Suppress("DEPRECATION") insets.systemWindowInsets }
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(scroll)
        layout.addView(label("Baixador 1.1", 28f))
        layout.addView(label("Fotos, vídeos e áudio • Downloads/Baixador"))
        link = EditText(this).apply {
            hint = "Cole o link aqui"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(false); maxLines = 4; id = View.generateViewId()
        }
        layout.addView(link)
        paste = button("Colar link") { pasteClipboard() }; layout.addView(paste)
        analyze = button("Consultar mídias, qualidade e tamanho") { analyzeLink() }; layout.addView(analyze)
        infoText = label("Carrosséis do Instagram: escolha cada foto ou vídeo abaixo. Outros sites: vídeo e MP3.")
        layout.addView(infoText)
        login = button("Entrar no Instagram") {
            startActivityForResult(Intent(this, InstagramLoginActivity::class.java)
                .putExtra("post", DownloadRules.extractUrl(link.text.toString())), 20)
        }; layout.addView(login)
        logout = button("Remover sessão do Instagram") {
            AlertDialog.Builder(this).setTitle("Remover sessão deste app?")
                .setMessage("Isso remove os cookies locais do Baixador. Sua sessão no app oficial do Instagram continua igual.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Remover") { _, _ ->
                    CookieManager.getInstance().removeAllCookies {
                        CookieManager.getInstance().flush(); WebStorage.getInstance().deleteAllData()
                        clearCatalog(); infoText.text = "Sessão removida deste app."; refresh()
                    }
                }.show()
        }; layout.addView(logout)
        selectAll = button("Selecionar todas") { rows.forEach { it.check.isChecked = true }; updateSelection() }
        clearSelection = button("Limpar seleção") { rows.forEach { it.check.isChecked = false }; updateSelection() }
        layout.addView(selectAll); layout.addView(clearSelection)
        itemView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; layout.addView(itemView)
        selectionText = label(""); layout.addView(selectionText)
        download = button("Baixar selecionadas") { startDownload() }; layout.addView(download)
        statusText = label(""); layout.addView(statusText)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }; layout.addView(progress)
        cancel = button("Cancelar toda a seleção") { startService(Intent(this, DownloadService::class.java).setAction(DownloadService.CANCEL)) }
        layout.addView(cancel)
        update = button("Atualizar motor de vídeo (yt-dlp)") { updateEngine() }; layout.addView(update)
        if (this is QuickActivity) {
            layout.addView(button("Abrir app e histórico") { startActivity(Intent(this, MainActivity::class.java)); finish() })
            layout.addView(button("Fechar popup") { finish() })
        } else {
            layout.addView(Switch(this).apply {
                text = "Sugerir link copiado ao voltar ao app"; isChecked = preferences.getBoolean("clipboard", false)
                setOnCheckedChangeListener { _, checked -> preferences.edit().putBoolean("clipboard", checked).apply(); if (checked) checkClipboard() }
            })
            layout.addView(button("Adicionar atalho Baixar link") { addTile() })
            layout.addView(label("Use Compartilhar → Baixador ou copie um link e toque em Baixar link nos ajustes rápidos. O Android não permite detectar cópias em outros apps automaticamente."))
        }
        layout.addView(label("Histórico", 22f))
        historyView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; layout.addView(historyView)
        link.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { clearCatalog() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        if (savedInstanceState != null) {
            link.setText(savedInstanceState.getString("link")); infoText.text = "Consulte novamente para carregar as mídias."
        } else acceptIntent(intent)
        refresh()
    }
    private fun clearCatalog() {
        generation++; catalog = null; rows.clear(); itemView.removeAllViews(); updateSelection()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("link", link.text.toString()); super.onSaveInstanceState(outState) }
    @Deprecated("Android activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 20 && resultCode == RESULT_OK && !busy && DownloadService.status == null) {
            val name = data?.getStringExtra("postData")
            val post = data?.getStringExtra("post")
            if (name?.matches(Regex("[a-f0-9-]{36}")) == true && post?.let(InstagramUrls::shortcode) != null) {
                link.setText(post)
                analyzeLink(java.io.File(filesDir, "browser-metadata/$name.json")); return
            }
        }
        if (requestCode == 20 && resultCode == RESULT_OK && !busy && DownloadService.status == null &&
            DownloadRules.extractUrl(link.text.toString())?.let(InstagramUrls::shortcode) != null) analyzeLink()
    }
    private fun acceptIntent(incoming: Intent) {
        val text = when (incoming.action) {
            Intent.ACTION_SEND -> incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            Intent.ACTION_PROCESS_TEXT -> incoming.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        if (text != null) {
            val url = DownloadRules.extractUrl(text)
            if (url != null) { link.setText(url); analyzeLink() } else infoText.text = "O texto compartilhado não contém um link válido."
        }
        pasteOnFocus = this is ClipboardActivity
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !::link.isInitialized) return
        if (pasteOnFocus) { pasteOnFocus = false; pasteClipboard() } else checkClipboard()
    }
    private fun clipboardUrl(): String? = runCatching {
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.let(DownloadRules::extractUrl)
    }.getOrNull()
    private fun pasteClipboard() {
        val url = clipboardUrl() ?: return toast("Copie um link de mídia primeiro.")
        lastClipboard = url; link.setText(url); analyzeLink()
    }
    private fun checkClipboard() {
        if (this is QuickActivity || busy || DownloadService.status != null || !preferences.getBoolean("clipboard", false) || clipboardDialog?.isShowing == true) return
        val url = clipboardUrl() ?: return
        if (url == lastClipboard || url == link.text.toString()) return
        lastClipboard = url
        clipboardDialog = AlertDialog.Builder(this).setTitle("Usar link copiado?").setMessage(url)
            .setNegativeButton("Agora não", null).setPositiveButton("Consultar") { _, _ -> link.setText(url); analyzeLink() }.show()
    }
    private fun analyzeLink(browserData: java.io.File? = null) {
        if (busy || DownloadService.status != null) return toast("Aguarde a operação atual terminar.")
        val url = DownloadRules.extractUrl(link.text.toString()) ?: return toast("Cole um link http ou https válido.")
        clearCatalog(); val currentGeneration = generation; busy = true
        infoText.text = "Consultando mídias e tamanhos… Pode levar alguns segundos."; refresh()
        executor.execute {
            val result = runCatching {
                if (browserData != null) {
                    try { InstagramResolver.fromBrowser(JSONObject(browserData.readText()), url) }
                    finally { browserData.delete() }
                } else Engine.info(applicationContext, url, analysisId)
            }
            runOnUiThread {
                if (isDestroyed || isFinishing || currentGeneration != generation) return@runOnUiThread
                busy = false
                result.onSuccess { catalog = it; infoText.text = it.title; showItems(it) }
                    .onFailure { infoText.text = Engine.error(it) }
                refresh()
            }
        }
    }
    private fun showItems(value: MediaCatalog) {
        val currentGeneration = generation
        value.items.forEach { item ->
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
            val check = CheckBox(this).apply { text = "${item.title} • ${item.kind}"; isChecked = value.items.size == 1 }
            card.addView(check)
            if (item.thumbnail.startsWith("https://")) {
                val image = ImageView(this).apply {
                    contentDescription = "Prévia de ${item.title}"; scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(0xffeeeeee.toInt())
                }
                card.addView(image, LinearLayout.LayoutParams(-1, dp(160)))
                images.execute {
                    val bitmap = runCatching {
                        val bytes = MediaHttp.bytes(item.thumbnail, 3_000_000)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        val options = BitmapFactory.Options().apply {
                            var sample = 1
                            while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 640) sample *= 2
                            inSampleSize = sample
                        }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    }.getOrNull()
                    runOnUiThread { if (!isDestroyed && generation == currentGeneration) {
                        if (bitmap != null) image.setImageBitmap(bitmap) else image.visibility = View.GONE
                    } }
                }
            }
            val quality = Spinner(this)
            quality.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, item.options.map { it.display() }).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            card.addView(quality)
            rows += Row(item, check, quality)
            check.setOnCheckedChangeListener { _, _ -> updateSelection() }
            quality.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { updateSelection() }
            }
            itemView.addView(card)
        }
        updateSelection()
    }
    private fun updateSelection() {
        if (!::selectionText.isInitialized) return
        val selected = rows.filter { it.check.isChecked }.mapNotNull { it.item.options.getOrNull(it.quality.selectedItemPosition) }
        val unknown = selected.count { it.bytes <= 0 }
        val known = selected.sumOf { it.bytes }
        selectionText.text = if (selected.isEmpty()) "Selecione as mídias que deseja baixar." else
            "${selected.size} selecionada(s) • ${if (unknown > 0) "subtotal conhecido: " else "total: "}${MediaOption.sizeLabel(known, selected.any { it.estimated })}" +
                (if (unknown > 0) " • $unknown sem tamanho informado" else "") +
                "\n≈ indica estimativa do arquivo final; conversão para MP3 e junção de vídeo podem alterar o tamanho."
        download.isEnabled = !busy && DownloadService.status == null && selected.isNotEmpty()
    }
    private fun startDownload() {
        val value = catalog ?: return
        if (busy || DownloadService.status != null) return toast("Aguarde a operação atual.")
        val jobs = rows.filter { it.check.isChecked }.mapNotNull { row ->
            val option = row.item.options.getOrNull(row.quality.selectedItemPosition) ?: return@mapNotNull null
            JSONObject().put("url", value.sourceUrl).put("title", row.item.title).put("mediaId", row.item.id).put("option", option.json())
        }
        runCatching { DownloadService.start(this, jobs) }.onSuccess {
            download.isEnabled = false
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }.onFailure { infoText.text = Engine.error(it) }
    }
    private fun updateEngine() {
        if (busy || DownloadService.status != null) return
        busy = true; infoText.text = "Atualizando yt-dlp…"; refresh()
        executor.execute {
            val result = runCatching { Engine.update(applicationContext) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false; clearCatalog()
                infoText.text = result.fold({ "Motor atualizado/verificado. Consulte o link novamente." }, Engine::error); refresh()
            }
        }
    }
    private fun refresh() {
        val current = DownloadService.status; val idle = current == null && !busy
        link.isEnabled = idle; paste.isEnabled = idle; analyze.isEnabled = idle; update.isEnabled = idle
        login.isEnabled = idle; logout.isEnabled = idle
        login.text = if (InstagramSession.hasSession()) "Verificar sessão do Instagram" else "Entrar no Instagram"
        logout.visibility = if (InstagramSession.hasSession()) View.VISIBLE else View.GONE
        selectAll.visibility = if (rows.size > 1) View.VISIBLE else View.GONE
        clearSelection.visibility = selectAll.visibility
        selectAll.isEnabled = idle; clearSelection.isEnabled = idle
        rows.forEach { it.check.isEnabled = idle; it.quality.isEnabled = idle }
        updateSelection()
        statusText.text = current?.let { "${it.title}\n${it.message}" } ?: if (busy) "Aguarde…" else ""
        progress.visibility = if (current != null || busy) View.VISIBLE else View.GONE
        progress.isIndeterminate = busy || (current?.progress ?: -1) < 0; progress.progress = current?.progress ?: 0
        cancel.visibility = if (current != null) View.VISIBLE else View.GONE
        val items = History.all(this); val fingerprint = items.toString()
        if (fingerprint == historyFingerprint) return
        historyFingerprint = fingerprint; historyView.removeAllViews()
        if (items.isEmpty()) historyView.addView(label("Os downloads aparecerão aqui."))
        items.forEach { item ->
            val state = item.optString("state")
            val stateLabel = when (state) { "done" -> "Concluído"; "running" -> "Em andamento"; "cancelled" -> "Cancelado"; else -> "Falhou" }
            historyView.addView(label("${item.optString("title")}\n$stateLabel" +
                if (state == "done") " • ${MediaOption.sizeLabel(item.optLong("actualBytes"))}" else "", 17f))
            if (state == "done") {
                historyView.addView(button("Abrir arquivo") { openMedia(item) })
                historyView.addView(button("Compartilhar arquivo") { shareMedia(item) })
            } else if (state != "running") {
                historyView.addView(button("Ver motivo") { AlertDialog.Builder(this).setTitle(stateLabel).setMessage(item.optString("detail")).setPositiveButton("OK", null).show() })
                historyView.addView(button("Consultar novamente") {
                    if (busy || DownloadService.status != null) toast("Aguarde a operação atual.")
                    else { link.setText(item.optString("url")); analyzeLink() }
                })
            }
        }
    }
    private fun openMedia(item: JSONObject) = launchMedia(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.getString("uri")), item.getString("mime")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    private fun shareMedia(item: JSONObject) {
        val uri = Uri.parse(item.getString("uri"))
        val send = Intent(Intent.ACTION_SEND).setType(item.getString("mime")).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("Mídia", uri); launchMedia(Intent.createChooser(send, "Compartilhar mídia"))
    }
    private fun launchMedia(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast("Arquivo indisponível ou nenhum app compatível instalado.") } }
    private fun addTile() {
        if (Build.VERSION.SDK_INT >= 33) getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, ClipboardTile::class.java), "Baixar link", Icon.createWithResource(this, R.drawable.ic_download), mainExecutor
        ) { result -> if (result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED && result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) tileInstructions() }
        else tileInstructions()
    }
    private fun tileInstructions() { AlertDialog.Builder(this).setTitle("Adicionar atalho").setMessage("Abra os ajustes rápidos, toque em Editar e arraste Baixar link para os atalhos ativos.").setPositiveButton("OK", null).show() }
    private fun label(value: String, size: Float = 15f) = TextView(this).apply { text = value; textSize = size; setPadding(0, dp(8), 0, dp(8)) }
    private fun button(value: String, action: () -> Unit) = Button(this).apply { text = value; isAllCaps = false; setOnClickListener { action() } }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onResume() { super.onResume(); clipboard.addPrimaryClipChangedListener(clipboardListener); handler.post(ticker) }
    override fun onPause() { handler.removeCallbacks(ticker); clipboard.removePrimaryClipChangedListener(clipboardListener); super.onPause() }
    override fun onDestroy() {
        generation++; clipboardDialog?.dismiss()
        Thread { runCatching { YoutubeDL.destroyProcessById(analysisId) } }.start()
        images.shutdownNow(); executor.shutdown(); super.onDestroy()
    }
}
