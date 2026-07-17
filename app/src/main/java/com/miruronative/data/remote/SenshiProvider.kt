package com.miruronative.data.remote

import com.miruronative.data.model.Media
import com.miruronative.data.model.SkipTimes
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.StreamItem
import com.miruronative.data.model.SubtitleItem
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Senshi (senshi.live) — a pure-JSON provider keyed directly by MyAnimeList id, so there is no
 * search or title-matching phase at all. `/episodes/{malId}` returns the full catalog in one
 * request (episode titles, filler/recap flags and intro/outro skip times included) and
 * `/episode-embeds/{malId}/{ep}` returns a direct HLS url plus StreamNin/FileMoon embed fallbacks.
 */
internal class SenshiProvider(private val client: OkHttpClient, private val json: Json) {
    private val catalogs = ConcurrentHashMap<Int, Map<Int, SenshiParser.EpisodeMeta>>()

    fun episodeAvailability(media: Media): EpisodeAvailability {
        val malId = media.idMal ?: error("Senshi needs a MyAnimeList id")
        val catalog = catalog(malId)
        if (catalog.isEmpty()) error("Senshi returned no episode catalog")
        // Dub coverage isn't in the episode list; probe the first episode's embeds once.
        val hasDub = runCatching {
            embeds(malId, catalog.keys.min()).any(SenshiParser::isDub)
        }.getOrDefault(false)
        return EpisodeAvailability(catalog.keys, if (hasDub) catalog.keys else emptySet())
    }

    /** Rich per-episode metadata from the last catalog fetch (empty until [episodeAvailability] ran). */
    fun episodeMeta(media: Media): Map<Int, SenshiParser.EpisodeMeta> =
        media.idMal?.let(catalogs::get).orEmpty()

    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        val malId = media.idMal ?: error("Senshi needs a MyAnimeList id")
        val source = embeds(malId, episode).firstOrNull { SenshiParser.isDub(it) == (audio == "dub") }
            ?: error("Senshi episode $episode has no ${audio.uppercase()} source")

        val streams = mutableListOf<StreamItem>()
        source.string("url")?.takeIf(String::isNotBlank)?.let { hls ->
            streams += stream(hls, "hls", "Senshi", audio, active = true)
        }
        source.string("server2")?.takeIf(String::isNotBlank)?.let { embed ->
            streams += stream(embed, "embed", "Senshi StreamNin", audio, active = streams.isEmpty())
        }
        source.string("serverFM")?.takeIf(String::isNotBlank)?.let { embed ->
            streams += stream(embed, "embed", "Senshi FileMoon", audio, active = streams.isEmpty())
        }
        if (streams.isEmpty()) error("Senshi episode $episode has no playable sources")

        val skip = runCatching { catalog(malId) }.getOrDefault(emptyMap())[episode]?.skip
        val download = source.string("download")?.takeIf(String::isNotBlank)
        val subtitles = source.string("masked_base_url")?.trimEnd('/')
            ?.let { base -> sidecarSubtitles(base, audio) }
            .orEmpty()
        return SourcesResult(streams.distinctBy(StreamItem::url), subtitles, skip, download)
    }

    /**
     * Senshi's API omits subtitles, but its own web player loads sidecar JSONs served next to the
     * stream: `{base}/sub_filemoon.json` (absolute CDN .vtt urls) and `{base}/sub_artplayer.json`
     * (.ass files relative to the base; rendered by JASSUB on the site). Dub streams use the
     * `dub_` prefix. Back-catalog titles 404 on both — those videos are hardsubbed, so no
     * sideloaded track is needed.
     */
    private fun sidecarSubtitles(base: String, audio: String): List<SubtitleItem> {
        val prefix = if (audio == "dub") "dub" else "sub"
        val fileMoon = sidecar("$base/${prefix}_filemoon.json", base)
        if (fileMoon.isNotEmpty()) return fileMoon
        return sidecar("$base/${prefix}_artplayer.json", base)
    }

    private fun sidecar(url: String, base: String): List<SubtitleItem> = runCatching {
        SenshiParser.parseSubtitleSidecar(json.parseToJsonElement(get(url)), base)
    }.getOrDefault(emptyList())

    private fun catalog(malId: Int): Map<Int, SenshiParser.EpisodeMeta> {
        catalogs[malId]?.let { return it }
        val parsed = SenshiParser.parseCatalog(json.parseToJsonElement(get("$BASE/episodes/$malId")))
        // Only cache non-empty catalogs so a transient upstream hiccup isn't poisoned in.
        if (parsed.isNotEmpty()) catalogs[malId] = parsed
        return parsed
    }

    private fun embeds(malId: Int, episode: Int): List<JsonObject> =
        (json.parseToJsonElement(get("$BASE/episode-embeds/$malId/$episode")) as? JsonArray)
            .orEmpty().mapNotNull { it as? JsonObject }

    private fun get(url: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$BASE/")
            .header("Accept", "application/json, */*")
            .get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Senshi HTTP ${response.code}")
            return body
        }
    }

    private fun stream(url: String, type: String, label: String, audio: String, active: Boolean) = StreamItem(
        url = url,
        type = type,
        quality = label,
        audio = audio,
        referer = "$BASE/",
        isActive = active,
        width = null,
        height = null,
    )

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    companion object {
        private const val BASE = "https://senshi.live"
        private const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64; rv:146.0) Gecko/20100101 Firefox/146.0"
    }
}

internal object SenshiParser {
    data class EpisodeMeta(val title: String?, val filler: Boolean, val skip: SkipTimes?)

    /** `/episodes/{malId}` items: `{ep_id, ep_title, ep_filler, intro_start, intro_end, ...}`. */
    fun parseCatalog(root: Any?): Map<Int, EpisodeMeta> =
        (root as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { item ->
            val number = (item["ep_id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            number to EpisodeMeta(
                title = (item["ep_title"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank),
                filler = (item["ep_filler"] as? JsonPrimitive)?.booleanOrNull == true,
                skip = skipTimes(item),
            )
        }.toMap()

    /** Embed entries mark dub audio via `status: "Dub"` (subs are `"HardSub"`/`"SoftSub"`). */
    fun isDub(embed: JsonObject): Boolean =
        (embed["status"] as? JsonPrimitive)?.contentOrNull.equals("dub", ignoreCase = true)

    /**
     * Subtitle sidecar entries — filemoon: `{src: <absolute url>, label, default}`; artplayer:
     * `{url: <file relative to the stream base>, html, default}` plus a trailing `url:"none"`
     * "Off" row the site player appends. Default track first.
     */
    fun parseSubtitleSidecar(root: Any?, base: String): List<SubtitleItem> =
        (root as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { item ->
            fun field(name: String): String? = (item[name] as? JsonPrimitive)?.contentOrNull
            val raw = field("src") ?: field("url") ?: return@mapNotNull null
            if (raw.isBlank() || raw.equals("none", ignoreCase = true)) return@mapNotNull null
            val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "$base/$raw"
            val label = field("label") ?: field("html") ?: "Subtitle"
            val default = (item["default"] as? JsonPrimitive)?.booleanOrNull == true
            Triple(url, label, default)
        }.sortedByDescending { it.third }
            .map { (url, label, _) -> SubtitleItem(url, label, language(label)) }
            .distinctBy(SubtitleItem::url)

    private fun language(label: String): String = when {
        label.contains("eng", true) -> "en"
        label.contains("spa", true) -> "es"
        label.contains("por", true) -> "pt"
        label.contains("fre", true) || label.contains("fra", true) -> "fr"
        label.contains("ger", true) || label.contains("deu", true) -> "de"
        label.contains("ara", true) -> "ar"
        else -> "und"
    }

    private fun skipTimes(item: JsonObject): SkipTimes? {
        fun value(name: String): Double? = (item[name] as? JsonPrimitive)?.doubleOrNull
        fun range(start: String, end: String): Pair<Double?, Double?> {
            val s = value(start)
            val e = value(end)
            // A 0..0 (or inverted) range is Senshi's "no data" placeholder.
            return if (s != null && e != null && e > s) s to e else null to null
        }
        val intro = range("intro_start", "intro_end")
        val outro = range("outro_start", "outro_end")
        if (intro.first == null && outro.first == null) return null
        return SkipTimes(intro.first, intro.second, outro.first, outro.second)
    }
}
