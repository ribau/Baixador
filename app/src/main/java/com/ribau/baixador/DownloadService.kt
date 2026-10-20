package com.ribau.baixador

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.youtubedl_android.YoutubeDL
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

data class DownloadStatus(val title: String, val message: String, val progress: Int = -1)

class DownloadService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    @Volatile private var processId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotification = 0L

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            cancel()
            if (status == null) stopSelf()
            return START_NOT_STICKY
        }
        if (status != null) return START_NOT_STICKY
        val url = intent?.getStringExtra("url")?.let(DownloadRules::extractUrl)
        if (url == null) { stopSelf(); return START_NOT_STICKY }
        val title = intent.getStringExtra("title") ?: "Mídia"
        val height = intent.getIntExtra("height", 0).coerceAtLeast(0)
        val audio = intent.getBooleanExtra("audio", false)
        val bitrate = intent.getIntExtra("bitrate", 192).takeIf { it in setOf(128, 192, 320) } ?: 192
        cancelled = false
        status = DownloadStatus(title, "Preparando…")
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Baixador:download")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        val id = UUID.randomUUID().toString()
        processId = id
        val record = JSONObject().put("id", id).put("title", title).put("url", url)
            .put("height", height).put("audio", audio).put("bitrate", bitrate)
            .put("state", "running").put("time", System.currentTimeMillis())
        History.put(this, record)
        executor.execute { download(record) }
        return START_NOT_STICKY
    }

    private fun download(record: JSONObject) {
        val folder = File(filesDir, "transfers/${record.getString("id")}")
        try {
            check(folder.mkdirs() || folder.isDirectory) { "Não foi possível criar a pasta temporária." }
            ensureActive()
            Engine.run(this) {
                val request = Engine.request(record.getString("url")).apply {
                    addOption("--no-mtime")
                    addOption("--match-filter", "!is_live")
                    addOption("--no-overwrites")
                    addOption("--restrict-filenames")
                    addOption("-P", folder.absolutePath)
                    addOption("-o", "%(title).120B [%(id)s].%(ext)s")
                    if (record.getBoolean("audio")) {
                        addOption("-f", "bestaudio/best")
                        addOption("-x")
                        addOption("--audio-format", "mp3")
                        addOption("--audio-quality", "${record.getInt("bitrate")}K")
                    } else {
                        addOption("-f", DownloadRules.formatSelector(record.getInt("height")))
                        addOption("--merge-output-format", "mp4")
                    }
                }
                ensureActive()
                YoutubeDL.execute(request, record.getString("id")) { progress, _, line ->
                    val percent = progress.toInt().coerceIn(0, 100)
                    publish(if (percent >= 100) "Processando a mídia…" else "Baixando… $percent%", percent)
                }
            }
            ensureActive()
            val files = folder.listFiles().orEmpty().filter {
                it.isFile && it.length() > 0 && it.extension.lowercase() in
                    setOf("mp4", "mkv", "webm", "mp3", "m4a", "opus", "ogg", "wav", "aac", "flac", "mov", "3gp")
            }
            check(files.size == 1) { "O motor não produziu um único arquivo de mídia válido." }
            publish("Salvando em Downloads/Baixador…")
            val file = files.single()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                ?: if (record.getBoolean("audio")) "audio/mpeg" else "video/mp4"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Baixador")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = checkNotNull(contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            record.put("pending", uri.toString())
            History.put(this, record)
            try {
                checkNotNull(contentResolver.openOutputStream(uri)).use { output ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                ensureActive()
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                record.put("uri", uri.toString()).put("mime", mime).put("state", "done")
                    .put("detail", "Salvo em Downloads/Baixador")
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            } finally { record.remove("pending") }
            History.put(this, record)
            completeNotification(record.getString("title"))
        } catch (e: Exception) {
            record.put("state", if (cancelled) "cancelled" else "error")
                .put("detail", if (cancelled) "Download cancelado." else Engine.error(e))
            History.put(this, record)
        } finally {
            folder.deleteRecursively()
            main.post {
                status = null
                processId = null
                wakeLock?.let { if (it.isHeld) it.release() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun ensureActive() { check(!cancelled) { "Download cancelado." } }

    private fun publish(message: String, progress: Int = -1) {
        status = status?.copy(message = message, progress = progress)
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotification > 750) {
            lastNotification = now
            getSystemService(NotificationManager::class.java).notify(1, notification())
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction(CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "downloads").setSmallIcon(R.drawable.ic_download)
            .setContentTitle(status?.title ?: "Baixador")
            .setContentText(status?.message ?: "Preparando…")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, status?.progress ?: 0, (status?.progress ?: -1) < 0)
            .addAction(Notification.Action.Builder(null, "Cancelar", cancel).build()).build()
    }

    private fun completeNotification(title: String) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(2,
            Notification.Builder(this, "downloads").setSmallIcon(R.drawable.ic_download)
                .setContentTitle("Download concluído").setContentText(title)
                .setContentIntent(open).setAutoCancel(true).build())
    }

    private fun cancel() {
        cancelled = true
        // Retry until completion, covering cancellation during initialization/process startup.
        main.post(object : Runnable {
            override fun run() {
                val id = processId ?: return
                runCatching { YoutubeDL.destroyProcessById(id) }
                main.postDelayed(this, 250)
            }
        })
    }

    override fun onTimeout(startId: Int, fgsType: Int) { cancel(); stopSelf() }
    override fun onDestroy() {
        if (processId != null) cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val CANCEL = "com.ribau.baixador.CANCEL"
        @Volatile var status: DownloadStatus? = null
            private set
        fun start(context: Context, url: String, title: String, height: Int, audio: Boolean, bitrate: Int) {
            context.startForegroundService(Intent(context, DownloadService::class.java)
                .putExtra("url", url).putExtra("title", title).putExtra("height", height)
                .putExtra("audio", audio).putExtra("bitrate", bitrate))
        }
    }
}
