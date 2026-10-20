package com.ribau.baixador

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class MediaOption(
    val label: String, val selector: String = "", val directUrl: String = "",
    val bytes: Long = 0, val estimated: Boolean = false, val audio: Boolean = false,
    val bitrate: Int = 192, val ext: String = "mp4"
) {
    fun display() = "$label • ${sizeLabel(bytes, estimated)}"
    fun json() = JSONObject().put("label", label).put("selector", selector).put("directUrl", directUrl)
        .put("bytes", bytes).put("estimated", estimated).put("audio", audio).put("bitrate", bitrate).put("ext", ext)
    companion object {
        fun sizeLabel(bytes: Long, estimated: Boolean = false): String {
            if (bytes <= 0) return "tamanho não informado"
            val mb = bytes / 1_000_000.0
            val value = if (mb >= 1000) String.format(Locale("pt", "BR"), "%.2f GB", mb / 1000)
                else String.format(Locale("pt", "BR"), "%.2f MB", mb)
            return (if (estimated) "≈ " else "") + value
        }
    }
}
data class MediaItem(val id: String, val title: String, val kind: String, val thumbnail: String,
                     val options: List<MediaOption>)
data class MediaCatalog(val sourceUrl: String, val title: String, val items: List<MediaItem>)

object CatalogParser {
    private fun size(format: JSONObject, duration: Double): Pair<Long, Boolean> {
        val exact = format.optLong("filesize", 0)
        if (exact > 0) return exact to false
        val approximate = format.optLong("filesize_approx", 0)
        if (approximate > 0) return approximate to true
        val bitrate = format.optDouble("tbr", 0.0)
        return if (duration > 0 && bitrate > 0) (duration * bitrate * 1000 / 8).toLong() to true else 0L to false
    }
    fun youtube(json: JSONObject, url: String): MediaCatalog {
        require(!json.has("entries")) { "Use o link de um vídeo individual. Carrosséis são suportados pelo leitor do Instagram." }
        require(!json.optBoolean("is_live")) { "Transmissões ao vivo ainda não são suportadas." }
        val duration = json.optDouble("duration", 0.0)
        val formats = json.optJSONArray("formats")?.objects().orEmpty().filter {
            it.optString("vcodec") != "images" && it.optString("ext") != "mhtml" && !it.optBoolean("has_drm")
        }.ifEmpty { listOf(json) }
        fun hasCodec(f: JSONObject, name: String) = f.optString(name) !in listOf("", "none", "null")
        val audioOnly = formats.filter { !hasCodec(it, "vcodec") && hasCodec(it, "acodec") }
        val bestAudio = audioOnly.maxByOrNull { it.optDouble("abr", it.optDouble("tbr", 0.0)) }
        val videoFormats = formats.filter { hasCodec(it, "vcodec") || it.optInt("height") > 0 }
            .groupBy { it.optInt("height") }.toSortedMap(compareByDescending { it })
        val options = videoFormats.mapNotNull { (height, group) ->
            // Each displayed option is an exact format selection; its size uses the same streams.
            val video = group.maxWithOrNull(compareBy<JSONObject> { it.optString("ext") == "mp4" }
                .thenBy { it.optDouble("tbr", 0.0) }) ?: return@mapNotNull null
            val id = video.optString("format_id")
            if (id.isBlank()) return@mapNotNull null
            val audio = if (hasCodec(video, "acodec")) null else bestAudio
            val (vsize, vest) = size(video, duration)
            val (asize, aest) = audio?.let { size(it, duration) } ?: (0L to false)
            val known = vsize > 0 && (audio == null || asize > 0)
            MediaOption(if (height > 0) "Vídeo ${height}p" else "Vídeo original",
                selector = id + (audio?.let { "+${it.optString("format_id")}" } ?: ""),
                bytes = if (known) vsize + asize else 0, estimated = vest || aest || audio != null,
                ext = video.optString("ext", "mp4"))
        }.toMutableList()
        if (formats.any { hasCodec(it, "acodec") }) {
            for (rate in listOf(128, 192, 320)) options += MediaOption("MP3 $rate kbps", selector = "bestaudio/best",
                bytes = if (duration > 0) (duration * rate * 1000 / 8).toLong() else 0,
                estimated = true, audio = true, bitrate = rate, ext = "mp3")
        }
        require(options.isNotEmpty()) { "Nenhum formato de mídia disponível neste link." }
        val title = json.optString("title", "Mídia")
        return MediaCatalog(url, title, listOf(MediaItem(json.optString("id", "media"), title,
            if (videoFormats.isEmpty()) "Áudio" else "Vídeo", json.optString("thumbnail"), options)))
    }

    fun instagram(json: JSONObject, url: String): MediaCatalog {
        val data = json.optJSONObject("data")
        val product = json.optJSONArray("items")?.optJSONObject(0)
            ?: data?.optJSONObject("xig_polaris_media")?.optJSONObject("if_not_gated_logged_out")
            ?: data?.optJSONObject("xdt_shortcode_media") ?: data?.optJSONObject("shortcode_media")
            ?: json.optJSONObject("graphql")?.optJSONObject("shortcode_media") ?: json
        val carousel = product.optJSONArray("carousel_media")?.objects()
            ?: product.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")?.objects()?.mapNotNull { it.optJSONObject("node") }
            ?: listOf(product)
        val owner = product.optJSONObject("user")?.optString("username")
            ?: product.optJSONObject("owner")?.optString("username") ?: "Instagram"
        val items = carousel.mapIndexed { index, media ->
            val dimensions = media.optJSONObject("dimensions")
            val images = media.optJSONObject("image_versions2")?.optJSONArray("candidates")?.objects()
                ?: media.optJSONArray("display_resources")?.objects()?.map {
                    JSONObject().put("url", it.optString("src")).put("width", it.optInt("config_width"))
                        .put("height", it.optInt("config_height"))
                } ?: emptyList()
            val thumbnail = images.maxByOrNull { it.optInt("width") }?.optString("url")
                ?: media.optString("display_url")
            val video = media.optInt("media_type") == 2 || media.optBoolean("is_video") ||
                media.optString("__typename") == "GraphVideo" || media.has("video_versions")
            val candidates = if (video) media.optJSONArray("video_versions")?.objects() ?: listOf(
                JSONObject().put("url", media.optString("video_url"))
                    .put("width", dimensions?.optInt("width") ?: 0).put("height", dimensions?.optInt("height") ?: 0))
                else images.ifEmpty { listOf(JSONObject().put("url", thumbnail)
                    .put("width", dimensions?.optInt("width") ?: 0).put("height", dimensions?.optInt("height") ?: 0)) }
            val choices = candidates.filter { InstagramUrls.isCdn(it.optString("url")) }
                .sortedByDescending { it.optInt("width") * it.optLong("height") }
                .distinctBy { "${it.optInt("width")}x${it.optInt("height")}" }.take(4).map {
                    val w = it.optInt("width"); val h = it.optInt("height")
                    MediaOption("${if (video) "Vídeo" else "Foto"} ${if (w > 0 && h > 0) "${w}×${h}" else "original"}",
                        directUrl = it.getString("url"), bytes = it.optLong("file_size", it.optLong("filesize")),
                        ext = if (video) "mp4" else "jpg")
                }.toMutableList()
            require(choices.isNotEmpty()) { "O Instagram não disponibilizou a mídia ${index + 1}. Entre novamente no Instagram e consulte o post." }
            if (video && media.optBoolean("has_audio", true)) {
                val duration = media.optDouble("video_duration", 0.0)
                for (rate in listOf(128, 192, 320)) choices += MediaOption("MP3 $rate kbps",
                    directUrl = choices.first().directUrl, bytes = if (duration > 0) (duration * rate * 1000 / 8).toLong() else 0,
                    estimated = true, audio = true, bitrate = rate, ext = "mp3")
            }
            MediaItem(media.optString("pk", media.optString("id", "item${index + 1}")),
                "Item ${index + 1} • $owner", if (video) "Vídeo" else "Foto", thumbnail, choices)
        }
        require(items.isNotEmpty()) { "Carrossel sem mídias disponíveis." }
        return MediaCatalog(url, "Post de $owner • ${items.size} mídia(s)", items)
    }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
