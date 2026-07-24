package com.miruronative.data.remote

import com.miruronative.data.model.SkipTimes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * AniSkip community skip-time database (aniskip.com), keyed by MAL id. Fallback for streaming
 * providers that don't carry intro/outro markers of their own (most Anivexa providers).
 */
class AniSkipClient(
    private val engine: HttpEngine,
    private val json: Json,
) {
    /** Intro/outro windows for one episode; null when AniSkip has none (unknown episodes 404). */
    suspend fun skipTimes(malId: Int, episode: Int): SkipTimes? {
        // okhttp's query builder percent-encoded the square brackets; the encoded
        // form is kept verbatim so the request stays byte-identical.
        val url = "https://api.aniskip.com/v2/skip-times/$malId/$episode" +
            "?types%5B%5D=op&types%5B%5D=ed&episodeLength=0"
        val result = engine.get(url, mapOf("Accept" to "application/json"))
        if (result.code == 404) return null
        if (!result.isSuccessful) error("AniSkip HTTP ${result.code}")
        val root = json.parseToJsonElement(result.body).jsonObject
        if (root["found"]?.jsonPrimitive?.booleanOrNull != true) return null
        var intro: Pair<Double, Double>? = null
        var outro: Pair<Double, Double>? = null
        root["results"]?.jsonArray?.forEach { element ->
            val item = element.jsonObject
            val interval = item["interval"]?.jsonObject ?: return@forEach
            val start = interval["startTime"]?.jsonPrimitive?.doubleOrNull ?: return@forEach
            val end = interval["endTime"]?.jsonPrimitive?.doubleOrNull ?: return@forEach
            when (item["skipType"]?.jsonPrimitive?.contentOrNull) {
                "op" -> intro = start to end
                "ed" -> outro = start to end
            }
        }
        if (intro == null && outro == null) return null
        return SkipTimes(intro?.first, intro?.second, outro?.first, outro?.second)
    }
}
