package com.ribau.baixador

import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InstagramAccessException(message: String) : Exception(message)

object InstagramResolver {
    private const val BASE = "https://www.instagram.com"
    fun fromBrowser(json: JSONObject, url: String): MediaCatalog = withSizes(CatalogParser.instagram(json, InstagramUrls.canonical(url)))
    fun resolve(input: String): MediaCatalog {
        val url = InstagramUrls.canonical(input)
        val code = requireNotNull(InstagramUrls.shortcode(url))
        val id = InstagramUrls.mediaId(code)
        var lastError: Exception? = null
        val headers = mutableMapOf("X-IG-App-ID" to "936619743392459", "X-ASBD-ID" to "359341",
            "X-IG-WWW-Claim" to "0", "Origin" to BASE, "Referer" to url,
            "X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json")
        val cookie = InstagramSession.cookieHeader()
        if (cookie.isNotBlank()) headers["Cookie"] = cookie
        if (InstagramSession.csrf().isNotBlank()) headers["X-CSRFToken"] = InstagramSession.csrf()
        // The media endpoint includes photos and preserves the order of mixed carousels.
        try {
            val response = MediaHttp.bytes("$BASE/api/v1/media/$id/info/", 8_000_000, headers,
                allowed = InstagramUrls::isInstagram).toString(Charsets.UTF_8)
            return withSizes(CatalogParser.instagram(JSONObject(response), url))
        } catch (e: Exception) {
            lastError = e
            if (e.message?.contains("429") == true) throw blocked(true)
        }
        try {
            val page = MediaHttp.bytes(url, 8_000_000, headers, allowed = InstagramUrls::isInstagram,
                onResponse = { response ->
                    response.headerFields.entries.filter { it.key?.equals("Set-Cookie", true) == true }
                        .flatMap { it.value }.forEach { android.webkit.CookieManager.getInstance().setCookie(BASE, it) }
                }).toString(Charsets.UTF_8)
            val refreshedCookie = InstagramSession.cookieHeader()
            if (refreshedCookie.isNotBlank()) headers["Cookie"] = refreshedCookie
            if (InstagramSession.csrf().isNotBlank()) headers["X-CSRFToken"] = InstagramSession.csrf()
            val lsd = Regex("\"LSD\",\\[\\],\\{\"token\":\"([^\"]+)\"").find(page)?.groupValues?.get(1).orEmpty()
            val variables = JSONObject().put("media_id", id).toString()
            val fields = mapOf("lsd" to lsd, "fb_api_caller_class" to "RelayModern", "server_timestamps" to "true",
                "fb_api_req_friendly_name" to "PolarisLoggedOutDesktopWWWPostRootContentQuery",
                "variables" to variables, "doc_id" to "27130156389949648")
            val body = fields.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }.toByteArray()
            val gqlHeaders = headers + mapOf("X-FB-LSD" to lsd, "Content-Type" to "application/x-www-form-urlencoded",
                "X-FB-Friendly-Name" to "PolarisLoggedOutDesktopWWWPostRootContentQuery")
            val response = MediaHttp.bytes("$BASE/api/graphql", 8_000_000, gqlHeaders, body,
                InstagramUrls::isInstagram).toString(Charsets.UTF_8).removePrefix("for (;;);")
            return withSizes(CatalogParser.instagram(JSONObject(response), url))
        } catch (e: Exception) { lastError = e }
        throw blocked(lastError?.message?.contains("429") == true)
    }
    private fun blocked(rateLimited: Boolean) = InstagramAccessException(
        if (rateLimited) "O Instagram limitou as consultas. Aguarde alguns minutos antes de tentar novamente. Fazer login pode ajudar, mas não remove um bloqueio temporário."
        else if (InstagramSession.hasSession()) "O Instagram não liberou este post para a sessão atual. Toque em Verificar sessão do Instagram, conclua qualquer confirmação no site e use Ler mídias deste post. Se houver limite de acesso, aguarde antes de tentar novamente."
        else "O Instagram pode exigir login mesmo em páginas públicas que você não segue. Toque em Entrar no Instagram, faça login no site oficial e volte para consultar o carrossel. Não é necessário seguir uma página pública.")

    private fun withSizes(catalog: MediaCatalog): MediaCatalog {
        val urls = catalog.items.flatMap { it.options }.filter { !it.audio && it.bytes <= 0 }.map { it.directUrl }.distinct()
        val pool = Executors.newFixedThreadPool(4)
        val sizes = try {
            val futures = pool.invokeAll(urls.map { url -> Callable { MediaHttp.size(url) } }, 20, TimeUnit.SECONDS)
            urls.zip(futures).associate { (url, result) -> url to runCatching { result.get() }.getOrDefault(0L) }
        } finally { pool.shutdownNow() }
        return catalog.copy(items = catalog.items.map { item -> item.copy(options = item.options.map {
            if (!it.audio && it.bytes <= 0) it.copy(bytes = sizes[it.directUrl] ?: 0) else it
        }) })
    }
}
