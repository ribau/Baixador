package com.ribau.baixador

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.StatusBarManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.*
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.widget.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import com.yausername.youtubedl_android.YoutubeDL

open class QuickActivity : MainActivity()
class ClipboardActivity : QuickActivity()

open class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val analysisId = "info-${UUID.randomUUID()}"
    private lateinit var link: EditText
    private lateinit var infoText: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var analyze: Button
    private lateinit var paste: Button
    private lateinit var download: Button
    private lateinit var update: Button
    private lateinit var mode: Spinner
    private lateinit var quality: Spinner
    private lateinit var historyView: LinearLayout
    private lateinit var cancel: Button
    private var info: MediaInfo? = null
    private var analyzedUrl: String? = null
    private var busy = false
    private var heights = listOf(0)
    private var modeValues = listOf(false, true)
    private var pasteOnFocus = false
    private var lastClipboard: String? = null
    private var historyFingerprint = ""
    private var clipboardDialog: AlertDialog? = null
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val preferences by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (hasWindowFocus()) checkClipboard()
    }
    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 700) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        val scroll = ScrollView(this).apply { addView(layout); isFillViewport = true }
        if (this !is QuickActivity) {
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val bars = if (Build.VERSION.SDK_INT >= 30) {
                    insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsets
                }
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(scroll)
        layout.addView(label("Baixador", 28f))
        layout.addView(label("Vídeo ou áudio • salvos em Downloads/Baixador"))
        link = EditText(this).apply {
            hint = "Cole o link aqui"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(false)
            maxLines = 4
            id = View.generateViewId()
        }
        layout.addView(link)
        paste = button("Colar link") { pasteClipboard(true) }; layout.addView(paste)
        analyze = button("Consultar mídia e qualidades") { analyzeLink() }; layout.addView(analyze)
        infoText = label("YouTube, Instagram, TikTok, Facebook, X e outros sites compatíveis com yt-dlp.")
        layout.addView(infoText)
        layout.addView(label("O que baixar"))
        mode = Spinner(this); layout.addView(mode)
        setItems(mode, listOf("Vídeo", "Somente áudio (MP3)"))
        layout.addView(label("Qualidade"))
        quality = Spinner(this); layout.addView(quality)
        mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { refreshQuality() }
        }
        refreshQuality()
        download = button("Baixar") { startDownload() }; layout.addView(download)
        statusText = label(""); layout.addView(statusText)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        layout.addView(progress)
        cancel = button("Cancelar download") {
            startService(Intent(this, DownloadService::class.java).setAction(DownloadService.CANCEL))
        }; layout.addView(cancel)
        update = button("Atualizar motor de download") { updateEngine() }; layout.addView(update)
        if (this is QuickActivity) {
            layout.addView(button("Abrir app e histórico") {
                startActivity(Intent(this, MainActivity::class.java)); finish()
            })
            layout.addView(button("Fechar popup") { finish() })
        } else {
            layout.addView(Switch(this).apply {
                text = "Sugerir link copiado ao voltar ao app"
                isChecked = preferences.getBoolean("clipboard", false)
                setOnCheckedChangeListener { _, checked ->
                    preferences.edit().putBoolean("clipboard", checked).apply()
                    if (checked) checkClipboard()
                }
            })
            layout.addView(button("Adicionar atalho “Baixar link”") { addTile() })
            layout.addView(label("Para baixar sem abrir a tela principal: use Compartilhar → Baixador na rede social ou copie o link e toque em “Baixar link” nos ajustes rápidos. O Android não permite detectar cópias em outros apps automaticamente."))
        }
        layout.addView(label("Histórico", 22f))
        historyView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(historyView)
        link.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                info = null; analyzedUrl = null
                download.isEnabled = false
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        if (savedInstanceState != null) {
            link.setText(savedInstanceState.getString("link"))
            infoText.text = "Consulte a mídia para carregar as qualidades."
        } else acceptIntent(intent)
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("link", link.text.toString())
        super.onSaveInstanceState(outState)
    }

    private fun acceptIntent(incoming: Intent) {
        val text = when (incoming.action) {
            Intent.ACTION_SEND -> incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            Intent.ACTION_PROCESS_TEXT -> incoming.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        if (text != null) {
            val url = DownloadRules.extractUrl(text)
            if (url != null) { link.setText(url); analyzeLink() }
            else infoText.text = "O texto compartilhado não contém um link http ou https válido."
        }
        pasteOnFocus = this is ClipboardActivity
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !::link.isInitialized) return
        if (pasteOnFocus) {
            pasteOnFocus = false
            pasteClipboard(true)
        } else checkClipboard()
    }

    private fun clipboardUrl(): String? = runCatching {
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            ?.coerceToText(this)?.toString()?.let(DownloadRules::extractUrl)
    }.getOrNull()

    private fun pasteClipboard(consult: Boolean) {
        val url = clipboardUrl()
        if (url == null) { toast("Copie um link de mídia primeiro."); return }
        lastClipboard = url
        link.setText(url)
        if (consult) analyzeLink()
    }

    private fun checkClipboard() {
        if (this is QuickActivity || busy || DownloadService.status != null ||
            !preferences.getBoolean("clipboard", false) || clipboardDialog?.isShowing == true) return
        val url = clipboardUrl() ?: return
        if (url == lastClipboard || url == link.text.toString()) return
        lastClipboard = url
        clipboardDialog = AlertDialog.Builder(this).setTitle("Usar link copiado?")
            .setMessage(url).setNegativeButton("Agora não", null)
            .setPositiveButton("Consultar") { _, _ -> link.setText(url); analyzeLink() }.show()
    }

    private fun analyzeLink() {
        if (busy || DownloadService.status != null) { toast("Aguarde a operação atual terminar."); return }
        val url = DownloadRules.extractUrl(link.text.toString())
        if (url == null) { infoText.text = "Cole um link http ou https válido."; return }
        busy = true; info = null; analyzedUrl = null
        infoText.text = "Consultando a mídia… A primeira consulta prepara o motor e pode demorar."
        refresh()
        executor.execute {
            val result = runCatching { Engine.info(applicationContext, url, analysisId) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                result.onSuccess {
                    info = it; analyzedUrl = url
                    infoText.text = it.title
                    modeValues = buildList { if (it.hasVideo) add(false); if (it.hasAudio) add(true) }
                    setItems(mode, modeValues.map { audio -> if (audio) "Somente áudio (MP3)" else "Vídeo" })
                    refreshQuality()
                }.onFailure { infoText.text = Engine.error(it) }
                refresh()
            }
        }
    }

    private fun refreshQuality() {
        if (!::quality.isInitialized) return
        if (isAudio()) setItems(quality, listOf("MP3 • 128 kbps", "MP3 • 192 kbps", "MP3 • 320 kbps")).also {
            quality.setSelection(1)
        } else {
            heights = listOf(0) + (info?.heights ?: emptyList())
            setItems(quality, heights.map { if (it == 0) "Melhor disponível" else "Até ${it}p" })
        }
    }

    private fun isAudio() = modeValues.getOrElse(mode.selectedItemPosition) { false }

    private fun startDownload() {
        val media = info ?: return
        val url = analyzedUrl ?: return
        if (busy || DownloadService.status != null) { toast("Já existe uma operação em andamento."); return }
        val audio = isAudio()
        val height = if (audio) 0 else heights.getOrElse(quality.selectedItemPosition) { 0 }
        val bitrate = if (audio) listOf(128, 192, 320).getOrElse(quality.selectedItemPosition) { 192 } else 192
        runCatching { DownloadService.start(this, url, media.title, height, audio, bitrate) }
            .onSuccess {
                download.isEnabled = false
                statusText.text = "Iniciando download…"
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
                }
            }.onFailure { infoText.text = Engine.error(it) }
    }

    private fun updateEngine() {
        if (busy || DownloadService.status != null) return
        busy = true
        infoText.text = "Atualizando yt-dlp…"; refresh()
        executor.execute {
            val result = runCatching { Engine.update(applicationContext) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                infoText.text = result.fold({ "Motor atualizado/verificado. Consulte o link novamente." }, Engine::error)
                info = null; analyzedUrl = null
                refresh()
            }
        }
    }

    private fun refresh() {
        val current = DownloadService.status
        val idle = current == null && !busy
        link.isEnabled = !busy
        paste.isEnabled = idle; analyze.isEnabled = idle; update.isEnabled = idle
        download.isEnabled = idle && info != null
        mode.isEnabled = idle; quality.isEnabled = idle
        statusText.text = current?.let { "${it.title}\n${it.message}" } ?: if (busy) "Aguarde…" else ""
        progress.visibility = if (current != null || busy) View.VISIBLE else View.GONE
        progress.isIndeterminate = busy || (current?.progress ?: -1) < 0
        progress.progress = current?.progress ?: 0
        cancel.visibility = if (current != null) View.VISIBLE else View.GONE
        val items = History.all(this)
        val fingerprint = items.toString()
        if (fingerprint == historyFingerprint) return
        historyFingerprint = fingerprint
        historyView.removeAllViews()
        if (items.isEmpty()) historyView.addView(label("Os downloads aparecerão aqui."))
        items.forEach { item ->
            val state = item.optString("state")
            val stateLabel = when (state) {
                "done" -> "Concluído"
                "running" -> "Em andamento"
                "cancelled" -> "Cancelado"
                else -> "Falhou"
            }
            historyView.addView(label("${item.optString("title")}\n$stateLabel", 17f))
            if (state == "done") {
                historyView.addView(button("Abrir arquivo") { openMedia(item) })
                historyView.addView(button("Compartilhar arquivo") { shareMedia(item) })
            } else if (state != "running") {
                historyView.addView(button("Ver motivo") {
                    AlertDialog.Builder(this).setTitle(stateLabel).setMessage(item.optString("detail"))
                        .setPositiveButton("OK", null).show()
                })
                historyView.addView(button("Tentar novamente") {
                    if (busy || DownloadService.status != null) toast("Aguarde a operação atual.")
                    else { link.setText(item.optString("url")); analyzeLink() }
                })
            }
        }
    }

    private fun openMedia(item: JSONObject) = launchMedia(Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(item.getString("uri")), item.getString("mime"))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))

    private fun shareMedia(item: JSONObject) {
        val uri = Uri.parse(item.getString("uri"))
        val send = Intent(Intent.ACTION_SEND).setType(item.getString("mime"))
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("Mídia", uri)
        launchMedia(Intent.createChooser(send, "Compartilhar mídia"))
    }

    private fun launchMedia(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { toast("Arquivo indisponível ou nenhum app compatível instalado.") }
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, ClipboardTile::class.java), "Baixar link",
                Icon.createWithResource(this, R.drawable.ic_download), mainExecutor
            ) { result -> if (result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED &&
                result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) tileInstructions() }
        } else tileInstructions()
    }

    private fun tileInstructions() {
        AlertDialog.Builder(this).setTitle("Adicionar atalho")
            .setMessage("Abra os ajustes rápidos puxando a barra de notificações duas vezes, toque em Editar e arraste “Baixar link” para os atalhos ativos.")
            .setPositiveButton("OK", null).show()
    }
    private fun label(value: String, size: Float = 15f) = TextView(this).apply {
        text = value; textSize = size; setPadding(0, dp(8), 0, dp(8))
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; setOnClickListener { action() }
    }
    private fun setItems(spinner: Spinner, items: List<String>) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onResume() {
        super.onResume()
        clipboard.addPrimaryClipChangedListener(clipboardListener)
        handler.post(ticker)
    }
    override fun onPause() {
        handler.removeCallbacks(ticker)
        clipboard.removePrimaryClipChangedListener(clipboardListener)
        super.onPause()
    }
    override fun onDestroy() {
        clipboardDialog?.dismiss()
        runCatching { YoutubeDL.destroyProcessById(analysisId) }
        executor.shutdown()
        super.onDestroy()
    }
}
