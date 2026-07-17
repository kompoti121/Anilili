package com.miruronative.data.remote

import com.miruronative.data.model.Media
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.StreamItem
import com.miruronative.data.model.SubtitleItem
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * AniBD (epeng.animeapps.top) — a pure-JSON provider keyed directly by AniList id, sub-only.
 * `api2.php?epid={anilistId}` returns episode groups, `apilink.php?data={link}` returns player
 * pages whose HTML carries the HLS as `videoUrl: "..."`. Back-catalog uses `play2.php` (hardsub
 * video); recent titles use `playsub.php` with clean video plus a jwplayer-style `tracks` array
 * of soft `.vtt` subtitles — the upstream Anivexa provider drops those; we parse them.
 */
internal class AniBdProvider(private val client: OkHttpClient, private val json: Json) {
    private val catalogs = ConcurrentHashMap<Int, List<AniBdParser.Group>>()

    fun episodeAvailability(media: Media): EpisodeAvailability {
        val groups = groups(media.id)
        if (groups.isEmpty()) error("AniBD returned no episode catalog")
        fun numbers(audio: String): Set<Int> = groups
            .filter { it.audio == audio }
            .flatMap { group -> group.episodes.keys }
            .toSet()
        val availability = EpisodeAvailability(numbers("sub"), numbers("dub"))
        if (availability.sub.isEmpty() && availability.dub.isEmpty()) error("AniBD returned no episodes")
        return availability
    }

    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        val link = groups(media.id)
            .filter { it.audio == audio }
            .firstNotNullOfOrNull { it.episodes[episode] }
            ?: error("AniBD episode $episode has no ${audio.uppercase()} source")
        val players = (json.parseToJsonElement(get("$BASE/apilink.php?data=${link}", "$BASE/")) as? JsonArray)
            .orEmpty().mapNotNull { entry ->
                val obj = entry as? JsonObject ?: return@mapNotNull null
                val url = (obj["link"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val server = (obj["server"] as? JsonPrimitive)?.contentOrNull ?: "AniBD"
                server to url
            }
        if (players.isEmpty()) error("AniBD episode $episode has no players")

        val streams = mutableListOf<StreamItem>()
        val subtitles = mutableListOf<SubtitleItem>()
        players.take(3).forEach { (server, playerUrl) ->
            val origin = origin(playerUrl)
            val html = runCatching { get(playerUrl, "$origin/") }.getOrDefault("")
            val hls = AniBdParser.videoUrl(html, origin)
            if (hls != null) {
                streams += stream(hls, "hls", "AniBD $server", "$origin/", active = streams.none(StreamItem::isHls))
                // Subtitles are best-effort — a parse failure must never sink the stream itself.
                subtitles += runCatching { AniBdParser.trackSubtitles(html) }.getOrDefault(emptyList())
            } else if (html.isNotBlank()) {
                streams += stream(playerUrl, "embed", "AniBD $server embed", "$origin/", active = false)
            }
        }
        if (streams.isEmpty()) error("AniBD episode $episode has no playable sources")
        return SourcesResult(streams.distinctBy(StreamItem::url), subtitles.distinctBy(SubtitleItem::url), null, null)
    }

    private fun groups(anilistId: Int): List<AniBdParser.Group> {
        catalogs[anilistId]?.let { return it }
        val parsed = AniBdParser.parseGroups(json.parseToJsonElement(get("$BASE/api2.php?epid=$anilistId", "$BASE/")))
        // Only cache non-empty catalogs so a transient upstream hiccup isn't poisoned in.
        if (parsed.isNotEmpty()) catalogs[anilistId] = parsed
        return parsed
    }

    private fun get(url: String, referer: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Accept", "application/json, text/html, */*")
            .get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("AniBD HTTP ${response.code}")
            return body
        }
    }

    private fun stream(url: String, type: String, label: String, referer: String, active: Boolean) = StreamItem(
        url = url,
        type = type,
        quality = label,
        audio = null,
        referer = referer,
        isActive = active,
        width = null,
        height = null,
    )

    private fun origin(url: String): String = runCatching {
        URI(url).let { "${it.scheme}://${it.authority}" }
    }.getOrDefault(BASE)

    companion object {
        private const val BASE = "https://epeng.animeapps.top"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

internal object AniBdParser {
    /** One `api2.php` server group: audio kind plus episode number → provider link. */
    data class Group(val audio: String, val episodes: Map<Int, String>)

    /** `api2.php` items: `{server_name, server_data: [{name: "01", slug, link}]}`. */
    fun parseGroups(root: Any?): List<Group> =
        (root as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { group ->
            val name = (group["server_name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val audio = if (name.contains("dub", ignoreCase = true)) "dub" else "sub"
            val episodes = (group["server_data"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .mapNotNull { episode ->
                    fun field(key: String): String? = (episode[key] as? JsonPrimitive)?.contentOrNull
                    val number = (field("name") ?: field("slug"))?.toIntOrNull()?.takeIf { it >= 1 }
                        ?: return@mapNotNull null
                    val link = field("link")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    number to link
                }.toMap()
            Group(audio, episodes)
        }.filter { it.episodes.isNotEmpty() }

    /** ArtPlayer config: `videoUrl: "/r2/cache.../index.m3u8"` (relative to the player origin). */
    fun videoUrl(html: String, origin: String): String? {
        val raw = Regex("""videoUrl\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: return null
        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("/") -> "$origin$raw"
            else -> "$origin/$raw"
        }
    }

    /**
     * `playsub.php` pages embed jwplayer-style soft subtitles the upstream provider ignores:
     * `tracks: [{"label": "English", "file": "https://.../sub.vtt", "kind": "captions", ...}]`.
     * Hardsub `play2.php` pages have `tracks: []` — nothing to pull there.
     */
    fun trackSubtitles(html: String): List<SubtitleItem> {
        // NOTE: closing braces/brackets are escaped — Android's ICU regex engine rejects a bare
        // `}` (incomplete quantifier) that the desktop JVM used by unit tests accepts.
        val block = Regex("""tracks\s*:\s*\[([\s\S]*?)\]""").find(html)?.groupValues?.get(1) ?: return emptyList()
        return Regex("""\{[^{}]*\}""").findAll(block).mapNotNull { entry ->
            fun field(name: String): String? =
                Regex(""""$name"\s*:\s*"([^"]*)"""").find(entry.value)?.groupValues?.get(1)
            val file = field("file")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val kind = field("kind")
            if (kind != null && kind != "captions" && kind != "subtitles") return@mapNotNull null
            val label = field("label")?.takeIf(String::isNotBlank) ?: "Subtitle"
            SubtitleItem(file, label, language(label))
        }.distinctBy(SubtitleItem::url).toList()
    }

    private fun language(label: String): String = when {
        label.contains("eng", true) -> "en"
        label.contains("spa", true) -> "es"
        label.contains("por", true) -> "pt"
        else -> "und"
    }
}
