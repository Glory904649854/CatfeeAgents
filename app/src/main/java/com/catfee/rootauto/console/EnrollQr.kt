package com.catfee.rootauto.console

import android.net.Uri
import org.json.JSONObject

/** Web 控制台注册二维码的内容。 */
data class EnrollPayload(
    val baseUrl: String,
    val code: String,
    val name: String? = null
)

/**
 * 支持两种二维码内容：
 *   1. catfee://enroll?url=http://host:9090&code=ABCD-2345&name=可选
 *   2. {"url":"http://host:9090","code":"ABCD-2345","name":"可选"}
 */
object EnrollQr {
    fun parse(raw: String): EnrollPayload? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        if (text.startsWith("{")) {
            return runCatching {
                val json = JSONObject(text)
                val base = json.optString("url").ifBlank { json.optString("baseUrl") }.trim()
                val code = json.optString("code").ifBlank { json.optString("token") }.trim()
                val name = json.optString("name").trim().ifBlank { null }
                if (base.isBlank() || code.isBlank()) {
                    null
                } else {
                    EnrollPayload(ConsoleSettings.normalizeBase(base), code, name)
                }
            }.getOrNull()
        }

        val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "catfee") return null
        if (uri.host?.lowercase() !in setOf("enroll", "bind")) return null

        val base = uri.getQueryParameter("url")?.trim()
            ?: uri.getQueryParameter("baseUrl")?.trim()
        val code = uri.getQueryParameter("code")?.trim()
            ?: uri.getQueryParameter("token")?.trim()
        if (base.isNullOrBlank() || code.isNullOrBlank()) return null

        val name = uri.getQueryParameter("name")?.trim()?.ifBlank { null }
        return EnrollPayload(ConsoleSettings.normalizeBase(base), code, name)
    }
}
