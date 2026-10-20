package com.ribau.baixador

import android.webkit.CookieManager

object InstagramSession {
    fun cookieHeader(): String = CookieManager.getInstance().getCookie("https://www.instagram.com/").orEmpty()
    fun hasSession() = cookieHeader().split(';').any { it.trim().startsWith("sessionid=") && it.substringAfter('=').isNotBlank() }
    fun csrf(): String = cookieHeader().split(';').firstOrNull { it.trim().startsWith("csrftoken=") }?.substringAfter('=').orEmpty()
}
