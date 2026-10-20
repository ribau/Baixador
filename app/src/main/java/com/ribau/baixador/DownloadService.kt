package com.ribau.baixador

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.youtubedl_android.YoutubeDL
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.util.UUID
import java.util.concurrent.Executors

data class DownloadStatus(val title: String, val message: String, val progress: Int = -1)

class DownloadService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    @Volatile private var processId: String? = null
    @Volatile private var connection: HttpURLConnection? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotification = 0L
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            cancel()
            if (status == null) stopSelf()
            return START_NOT_STICKY
        }
        val jobId = intent?.getStringExtra("job")
        if (jobId == null || !jobId.matches(Regex("[a-f0-9-]{36}"))) { if (status == null) stopSelf(); return START_NOT_STICKY }
        val jobFile = File(filesDir, "jobs/$jobId.json")
        if (status != null) { jobFile.delete(); return START_NOT_STICKY }
        val jobs = runCatching { JSONArray(jobFile.readText()).objects() }.getOrDefault(emptyList())
        jobFile.delete()
        if (jobs.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        cancelled = false
        status = DownloadStatus("Baixador", "Preparando ${jobs.size} mídia(s)…")
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Baixador:download")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        executor.execute {
            var completed = 0
            try {
                jobs.forEachIndexed { index, record ->
                    record.put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis())
                    if (cancelled) {
                        record.put("state", "cancelled").put("detail", "Seleção cancelada antes de iniciar.")
                        History.put(this, record)
                    } else {
                        status = DownloadStatus(record.getString("title"), "Mídia ${index + 1} de ${jobs.size}")
                        if (download(record, index + 1, jobs.size)) completed++
                    }
                }
                completeNotification(if (cancelled) "Seleção cancelada" else "$completed de ${jobs.size} mídia(s) salva(s)")
            } finally {
                main.post {
                    status = null; processId = null
                    wakeLock?.let { if (it.isHeld) it.release() }
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun download(record: JSONObject, index: Int, total: Int): Boolean {
        val folder = File(filesDir, "transfers/${record.getString("id")}")
        val option = record.getJSONObject("option")
        val prefix = "[$index/$total] "
        processId = record.getString("id")
        record.put("state", "running")
        History.put(this, record)
        try {
            check(folder.mkdirs() || folder.isDirectory) { "Não foi possível criar a pasta temporária." }
            ensureActive()
            val direct = option.optString("directUrl")
            val audio = option.optBoolean("audio")
            val expectedSize = option.optLong("bytes")
            if (direct.isNotBlank() && !audio) {
                require(InstagramUrls.isCdn(direct)) { "Endereço de mídia do Instagram inválido." }
                val extension = option.optString("ext").takeIf { it in setOf("jpg", "png", "webp", "mp4") } ?: "mp4"
                val basename = "Instagram_${record.optString("mediaId").replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)}"
                var file = File(folder, "$basename.$extension")
                val c = MediaHttp.open(direct, headers = mapOf("Referer" to "https://www.instagram.com/"), allowed = InstagramUrls::isCdn)
                connection = c
                try {
                    check(c.responseCode in 200..299) { "HTTP ${c.responseCode}" }
                    val contentType = c.contentType.orEmpty().substringBefore(';')
                    val actualExtension = when (contentType) {
                        "image/webp" -> "webp"; "image/png" -> "png"; "image/jpeg" -> "jpg"; "video/mp4" -> "mp4"
                        else -> extension
                    }
                    file = File(folder, "$basename.$actualExtension")
                    check(contentType.startsWith("image/") || contentType.startsWith("video/") || contentType == "application/octet-stream") {
                        "O Instagram retornou uma página em vez do arquivo. Consulte o post novamente."
                    }
                    val length = c.contentLengthLong
                    var read = 0L
                    c.inputStream.use { input -> file.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count); read += count
                            val progress = if (length > 0) ((read * 100 / length).toInt().coerceIn(0, 100)) else -1
                            publish(prefix + MediaOption.sizeLabel(read) + " / " + MediaOption.sizeLabel(length), progress)
                        }
                    } }
                    check(length <= 0 || read == length) { "Download incompleto. Consulte o post e tente novamente." }
                } finally { c.disconnect(); connection = null }
            } else {
                Engine.run(this) {
                    val url = if (direct.isNotBlank()) direct else record.getString("url")
                    if (direct.isNotBlank()) require(InstagramUrls.isCdn(direct))
                    val request = Engine.request(url).apply {
                        addOption("--no-mtime"); addOption("--match-filter", "!is_live")
                        addOption("--no-overwrites"); addOption("--restrict-filenames")
                        addOption("-P", folder.absolutePath)
                        val output = if (direct.isNotBlank())
                            "Instagram_${record.optString("mediaId").replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)}.%(ext)s"
                        else "%(title).120B [%(id).60B].%(ext)s"
                        addOption("-o", output)
                        if (direct.isNotBlank()) addOption("--referer", "https://www.instagram.com/")
                        if (audio) {
                            addOption("-f", "bestaudio/best"); addOption("-x"); addOption("--audio-format", "mp3")
                            addOption("--audio-quality", "${option.optInt("bitrate", 192)}K")
                        } else {
                            addOption("-f", option.getString("selector"))
                            addOption("--merge-output-format", "mp4")
                        }
                    }
                    ensureActive()
                    YoutubeDL.execute(request, record.getString("id")) { progress, _, _ ->
                        val percent = progress.toInt().coerceIn(0, 100)
                        publish(prefix + (if (percent >= 100) "Processando…" else "Baixando… $percent%") +
                            if (expectedSize > 0) " • arquivo ${MediaOption.sizeLabel(expectedSize, option.optBoolean("estimated"))}" else "", percent)
                    }
                }
            }
            ensureActive()
            val files = folder.listFiles().orEmpty().filter {
                it.isFile && it.length() > 0 && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "mp4", "mkv", "webm", "mp3", "m4a", "opus", "ogg", "wav", "aac", "flac", "mov", "3gp")
            }
            check(files.size == 1) { "O motor não produziu um único arquivo de mídia válido." }
            publish(prefix + "Salvando em Downloads/Baixador…")
            save(files.single(), record)
            return true
        } catch (e: Exception) {
            record.put("state", if (cancelled) "cancelled" else "error")
                .put("detail", if (cancelled) "Download cancelado." else Engine.error(e))
            History.put(this, record)
            return false
        } finally { folder.deleteRecursively(); processId = null }
    }

    private fun save(file: File, record: JSONObject) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name); put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Baixador")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = checkNotNull(contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        record.put("pending", uri.toString()); History.put(this, record)
        try {
            checkNotNull(contentResolver.openOutputStream(uri)).use { output -> file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    ensureActive(); val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            } }
            ensureActive()
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            record.put("uri", uri.toString()).put("mime", mime).put("state", "done")
                .put("detail", "Salvo • ${MediaOption.sizeLabel(file.length())}").put("actualBytes", file.length())
        } catch (e: Exception) { contentResolver.delete(uri, null, null); throw e }
        finally { record.remove("pending") }
        History.put(this, record)
    }
    private fun ensureActive() { check(!cancelled) { "Download cancelado." } }
    private fun publish(message: String, progress: Int = -1) {
        status = status?.copy(message = message, progress = progress)
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotification > 750) {
            lastNotification = now; getSystemService(NotificationManager::class.java).notify(1, notification())
        }
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction(CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "downloads").setSmallIcon(R.drawable.ic_download)
            .setContentTitle(status?.title ?: "Baixador").setContentText(status?.message ?: "Preparando…")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, status?.progress ?: 0, (status?.progress ?: -1) < 0)
            .addAction(Notification.Action.Builder(null, "Cancelar seleção", cancel).build()).build()
    }
    private fun completeNotification(message: String) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(2, Notification.Builder(this, "downloads")
            .setSmallIcon(R.drawable.ic_download).setContentTitle(message).setContentText("Veja o resultado de cada item no histórico.")
            .setContentIntent(open).setAutoCancel(true).build())
    }
    private fun cancel() {
        cancelled = true
        // Never stop a child process on the UI thread: the library may wait for it to exit.
        Thread {
            while (cancelled && status != null) {
                connection?.disconnect()
                processId?.let { runCatching { YoutubeDL.destroyProcessById(it) } }
                Thread.sleep(250)
            }
        }.start()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { cancel(); stopSelf() }
    override fun onDestroy() {
        if (status != null) cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        executor.shutdown(); super.onDestroy()
    }
    companion object {
        const val CANCEL = "com.ribau.baixador.CANCEL"
        @Volatile var status: DownloadStatus? = null
            private set
        fun start(context: Context, jobs: List<JSONObject>) {
            require(jobs.isNotEmpty() && jobs.size <= 40) { "Escolha entre 1 e 40 mídias." }
            val id = UUID.randomUUID().toString()
            val folder = File(context.filesDir, "jobs").apply { mkdirs() }
            val file = File(folder, "$id.json")
            file.writeText(JSONArray(jobs).toString())
            try { context.startForegroundService(Intent(context, DownloadService::class.java).putExtra("job", id)) }
            catch (e: Exception) { file.delete(); throw e }
        }
    }
}
