package com.tiktokvpn.app.core

import org.json.JSONObject

/** A single message coming out of the core, already decoded. */
sealed interface CoreEvent {
    data class Progress(val phase: String, val completed: Int, val total: Int) : CoreEvent
    data class Handshaking(val endpoint: String) : CoreEvent
    data class Connected(val endpoint: String) : CoreEvent
    data class Stats(val rxBytes: Long, val txBytes: Long) : CoreEvent
    data class Completed(val payload: JSONObject?) : CoreEvent
    data class Error(val code: String, val message: String, val retryable: Boolean) : CoreEvent
}

fun parseCoreEvent(raw: String): CoreEvent? {
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    return when (val type = json.optString("type")) {
        "completed" -> CoreEvent.Completed(json.optJSONObject("payload"))
        "error" -> {
            val error = json.optJSONObject("error")
            CoreEvent.Error(
                code = error?.optString("code").orEmpty().ifBlank { "operation_failed" },
                message = error?.optString("message").orEmpty(),
                retryable = error?.optBoolean("retryable") == true
            )
        }
        "handshaking" -> CoreEvent.Handshaking(json.optString("message"))
        "connected" -> CoreEvent.Connected(json.optString("message"))
        "stats" -> {
            val payload = json.optJSONObject("payload")
            CoreEvent.Stats(
                rxBytes = payload?.optLong("rxBytes") ?: 0L,
                txBytes = payload?.optLong("txBytes") ?: 0L
            )
        }
        else -> CoreEvent.Progress(
            phase = json.optString("phase").ifBlank { type },
            completed = json.optInt("completed"),
            total = json.optInt("total")
        )
    }
}
