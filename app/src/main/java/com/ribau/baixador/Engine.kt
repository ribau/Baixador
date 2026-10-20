package com.ribau.baixador

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.util.concurrent.locks.ReentrantLock

object Engine {
    private val lock = ReentrantLock()
    fun <T> run(context: Context, action: () -> T): T {
        check(lock.tryLock()) { "O motor está ocupado. Aguarde a operação atual terminar." }
        try {
            YoutubeDL.init(context.applicationContext)
            FFmpeg.init(context.applicationContext)
            return action()
        } finally { lock.unlock() }
    }
    fun request(url: String) = YoutubeDLRequest(url).apply {
        addOption("--no-playlist")
        addOption("--socket-timeout", 30)
        addOption("--retries", 3)
        addOption("--fragment-retries", 3)
    }
    fun info(context: Context, input: String, processId: String): MediaCatalog {
        if (InstagramUrls.shortcode(input) != null) return InstagramResolver.resolve(input)
        return run(context) {
            val request = request(input).apply {
                addOption("--dump-single-json"); addOption("--skip-download"); addOption("--no-warnings")
            }
            CatalogParser.youtube(JSONObject(YoutubeDL.execute(request, processId, null).out), input)
        }
    }
    fun update(context: Context): String = run(context) {
        YoutubeDL.updateYoutubeDL(context.applicationContext).toString()
    }
    fun error(error: Throwable): String {
        if (error is InstagramAccessException) return error.message.orEmpty()
        val raw = error.message.orEmpty().replace(Regex("(?i)(sessionid|csrftoken|ds_user_id)=([^;\\s]+)"), "$1=[oculto]")
        val hint = when {
            raw.contains("sign in", true) || raw.contains("login", true) || raw.contains("cookies", true) || raw.contains("private", true) ->
                "A rede exige uma sessão válida. Para posts do Instagram, use Entrar no Instagram e consulte novamente."
            raw.contains("Unsupported URL", true) -> "Esse tipo de link não é suportado pelo motor."
            raw.contains("403") || raw.contains("429") -> "A rede limitou o acesso ou o link de mídia expirou. Consulte o post novamente; se persistir, aguarde antes de tentar."
            raw.contains("No space", true) || raw.contains("ENOSPC", true) -> "Sem espaço livre no celular."
            else -> "Não foi possível concluir a operação."
        }
        return "$hint\n\n${raw.takeLast(1400)}"
    }
}
