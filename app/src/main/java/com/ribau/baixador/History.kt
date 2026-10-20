package com.ribau.baixador

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

object History {
    private fun prefs(context: Context) = context.getSharedPreferences("history", Context.MODE_PRIVATE)
    @Synchronized fun all(context: Context): List<JSONObject> {
        val array = runCatching { JSONArray(prefs(context).getString("items", "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
    @Synchronized fun put(context: Context, item: JSONObject) {
        val items = listOf(item) + all(context).filter { it.optString("id") != item.optString("id") }
        prefs(context).edit().putString("items", JSONArray(items.take(40)).toString()).commit()
    }
    @Synchronized fun markInterrupted(context: Context) {
        all(context).filter { it.optString("state") == "running" }.forEach {
            val pending = it.optString("pending")
            if (pending.isNotBlank()) {
                runCatching { context.contentResolver.delete(Uri.parse(pending), null, null) }
                it.remove("pending")
            }
            it.put("state", "error").put("detail", "O Android encerrou o processo. Toque em Tentar novamente.")
            put(context, it)
        }
        // This directory contains only this app's unpublished temporary transfers.
        java.io.File(context.filesDir, "transfers").deleteRecursively()
    }
}
