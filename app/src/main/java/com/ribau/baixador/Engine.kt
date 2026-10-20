package com.ribau.baixador

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.util.concurrent.locks.ReentrantLock

data class MediaInfo(val title: String, val heights: List<Int>, val hasVideo: Boolean, val hasAudio: Boolean)

object Engine {
    private val lock = ReentrantLock()

    fun <T> run(context: Context, action: () -> T): T {
        check(lock.tryLock()) { "O motor está ocupado. Aguarde a operação atual terminar." }
        try {
            YoutubeDL.init(context.applicationContext)
            FFmpeg.init(context.applicationContext)
            return action()
        } finally {
            lock.unlock()
        }
    }

    fun request(url: String) = YoutubeDLRequest(url).apply {
        addOption("--no-playlist")
        addOption("--socket-timeout", 30)
        addOption("--retries", 3)
        addOption("--fragment-retries", 3)
    }

    fun info(context: Context, url: String, processId: String): MediaInfo = run(context) {
        val request = request(url).apply {
            addOption("--dump-single-json")
            addOption("--skip-download")
            addOption("--no-warnings")
        }
        val json = JSONObject(YoutubeDL.execute(request, processId, null).out)
        check(!json.has("entries")) { "Use o link de um vídeo individual, não de um perfil ou álbum." }
        check(!json.optBoolean("is_live")) { "Transmissões ao vivo ainda não são suportadas." }
        val formats = json.optJSONArray("formats")
        val heights = mutableSetOf<Int>()
        var video = false
        var audio = false
        fun inspect(format: JSONObject) {
            if (format.optString("vcodec") == "images" || format.optString("ext") == "mhtml") return
            val h = format.optInt("height", 0)
            val vcodec = format.optString("vcodec", "none")
            val acodec = format.optString("acodec", "none")
            val hasVideoCodec = vcodec !in setOf("none", "null", "")
            val hasAudioCodec = acodec !in setOf("none", "null", "")
            if (h > 0 || hasVideoCodec) video = true
            if (hasAudioCodec) audio = true
            if (h > 0 && hasVideoCodec) heights += h
        }
        if (formats != null) for (i in 0 until formats.length()) inspect(formats.getJSONObject(i))
        inspect(json)
        check(video || audio) { "Não foi encontrado vídeo ou áudio neste link. Posts só com fotos não são suportados." }
        MediaInfo(json.optString("title", "Mídia"), heights.sortedDescending(), video, audio)
    }

    fun update(context: Context): String = run(context) {
        YoutubeDL.updateYoutubeDL(context.applicationContext).toString()
    }

    fun error(error: Throwable): String {
        val raw = error.message.orEmpty()
        val hint = when {
            raw.contains("sign in", true) || raw.contains("login", true) ||
                raw.contains("cookies", true) || raw.contains("private", true) ->
                "A rede exige login ou bloqueou o acesso. Esta versão baixa links públicos e não importa sessões."
            raw.contains("Unsupported URL", true) -> "Esse tipo de link não é suportado pelo motor."
            raw.contains("403") || raw.contains("429") -> "A rede limitou o acesso. Atualize o motor ou tente mais tarde."
            raw.contains("No space", true) || raw.contains("ENOSPC", true) -> "Sem espaço livre no celular."
            else -> "Não foi possível concluir. Confira a conexão e tente atualizar o motor."
        }
        return "$hint\n\n${raw.takeLast(1600)}"
    }
}
