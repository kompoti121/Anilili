package com.miruronative.data.remote

import com.miruronative.data.model.Media
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.StreamItem
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.util.Base64Compat
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Hentai Haven — a WordPress site whose stream is plain HLS once the wrapping is unpicked.
 *
 * Four hops, all plain HTTPS (no JS engine, so this runs off the WebView entirely):
 * 1. `/watch/{slug}/episode-{n}` carries an iframe at `player-logic/player.php?data=…`.
 * 2. That player page answers with a `<meta name="x-secure-token">` blob. Despite the `sha512-`
 *    prefix it is not a digest — it is the payload ROT13'd and base64'd three times over.
 * 3. Decoding it yields `{en, iv}`, which POST to `player-logic/api.php` as `a`/`b`.
 * 4. The reply carries the `.m3u8` plus an authorization token.
 *
 * The manifest is ordinary fMP4 HLS with no `EXT-X-KEY`, so ExoPlayer plays it directly; the
 * segments are *named* `.jpg` but sniff as fMP4, which Media3 handles by container rather than
 * extension. The authorization token is bound to the IP that called `api.php` and expires within
 * the hour, so resolution has to happen on-device immediately before playback — never cached.
 *
 * Entries carry no MAL or AniList id (same as [HanimeProvider]), so a title is bound to a series
 * by name, reusing the bigram matcher the hanime catalogue already uses.
 */
internal class HentaiHavenProvider(private val client: OkHttpClient, private val json: Json) {
    /** Slug per AniList id. Cheap to hold and it saves a search on every episode change. */
    private val slugs = ConcurrentHashMap<Int, String>()

    fun episodeAvailability(media: Media): EpisodeAvailability {
        val slug = seriesSlug(media) ?: error("Hentai Haven has no match for this title")
        val episodes = HentaiHavenParser.episodeNumbers(get("$BASE/watch/$slug/"), slug)
        if (episodes.isEmpty()) error("Hentai Haven listed no episodes for $slug")
        // Japanese audio with burnt-in or absent subtitles; there is no dub track to offer.
        return EpisodeAvailability(episodes, emptySet())
    }

    fun sources(media: Media, episode: Int): SourcesResult {
        val slug = seriesSlug(media) ?: error("Hentai Haven has no match for this title")
        // Trailing slash is the canonical form; without it every resolve eats a 301 first.
        val episodeUrl = "$BASE/watch/$slug/episode-$episode/"
        val data = HentaiHavenParser.playerDataParam(get(episodeUrl))
            ?: error("Hentai Haven episode $episode has no player iframe")

        val playerUrl = "$PLUGIN/player.php?data=$data&lang=en"
        val token = HentaiHavenParser.secureToken(get(playerUrl, referer = episodeUrl))
            ?: error("Hentai Haven player page carried no secure token")
        val config = HentaiHavenParser.decodeSecureToken(token)
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: error("Hentai Haven secure token did not decode")

        val en = config.string("en") ?: error("Hentai Haven token had no payload")
        val iv = config.string("iv") ?: error("Hentai Haven token had no iv")
        val reply = json.parseToJsonElement(postPlayerApi(en, iv, playerUrl)).jsonObject
        val hls = reply["data"]?.jsonObject?.get("sources")?.jsonArray
            ?.firstNotNullOfOrNull { (it as? JsonObject)?.string("src") }
            ?: error("Hentai Haven returned no sources for episode $episode")

        val stream = StreamItem(
            url = hls,
            type = "hls",
            quality = "Hentai Haven",
            audio = null,
            referer = "$BASE/",
            isActive = true,
            width = null,
            height = null,
            // hls.js on the site stamps these on every manifest and segment request. The CDN
            // currently serves without them, but it is the token's only carrier — send it.
            headers = authorizationHeaders(reply["authorization"] as? JsonObject),
            // Measured on device: Cronet dies ~1s in on this CDN with "Source error"; the same
            // manifest plays clean on the plain stack.
            avoidCronet = true,
        )
        DiagnosticsLog.event("HentaiHaven resolved slug=$slug episode=$episode")
        return SourcesResult(listOf(stream), emptyList(), null, null)
    }

    /** `X-Video-*` trio, or nothing when the reply omitted the block. */
    private fun authorizationHeaders(auth: JsonObject?): Map<String, String> {
        val token = auth?.string("token") ?: return emptyMap()
        return buildMap {
            put("X-Video-Token", token)
            auth.number("expiration")?.let { put("X-Video-Expiration", it) }
            auth.string("ip")?.let { put("X-Video-Ip", it) }
        }
    }

    /**
     * The series slug for an AniList entry. Its own search is a WordPress query, so we hand it the
     * romaji title and score what comes back — a wrong hentai match is worse than none, so anything
     * under the threshold is treated as "no source" rather than played.
     */
    private fun seriesSlug(media: Media): String? {
        slugs[media.id]?.let { return it }
        val candidates = aniListTitleCandidates(media)
        if (candidates.isEmpty()) return null
        val found = candidates.firstNotNullOfOrNull { title ->
            val query = HentaiHavenParser.searchQuery(title)
            if (query.isBlank()) return@firstNotNullOfOrNull null
            val html = runCatching { get("$BASE/?s=${query.urlEncoded()}&post_type=wp-manga") }
                .getOrElse { return@firstNotNullOfOrNull null }
            HentaiHavenParser.searchSlugs(html)
                .mapNotNull { slug ->
                    val score = candidates.maxOf { titleSimilarity(it, slugAsTitle(slug)) }
                    if (score >= MATCH_THRESHOLD) slug to score else null
                }
                .maxByOrNull { it.second }
                ?.first
        }
        return found?.also { slugs[media.id] = it }
    }

    private fun postPlayerApi(en: String, iv: String, referer: String): String {
        val body = FormBody.Builder()
            .add("action", "zarat_get_data_player_ajax")
            .add("a", en)
            .add("b", iv)
            .build()
        val request = Request.Builder().url("$PLUGIN/api.php")
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Origin", BASE)
            .header("X-Requested-With", "XMLHttpRequest")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Hentai Haven player API HTTP ${response.code}")
            return text
        }
    }

    private fun get(url: String, referer: String = "$BASE/"): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Accept", "text/html,application/json,*/*")
            // The site gates on an age-verification cookie before it renders a player.
            .header("Cookie", "agev=1")
            .get().build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Hentai Haven HTTP ${response.code}")
            return text
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    private fun JsonObject.number(key: String): String? =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull?.toString() ?: it.contentOrNull }

    private fun String.urlEncoded(): String = java.net.URLEncoder.encode(this, "UTF-8")

    companion object {
        private const val BASE = "https://hentaihaven.xxx"
        private const val PLUGIN = "$BASE/wp-content/plugins/player-logic"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"

        /** Same bar hanime uses; these two catalogues disagree mostly on punctuation, not words. */
        private const val MATCH_THRESHOLD = 0.85

        /** `onaji-zemi-no-someya-san` reads as a title once the hyphens are spaces. */
        fun slugAsTitle(slug: String): String = slug.replace('-', ' ')
    }
}

/** Pure parsing for [HentaiHavenProvider], kept separable so the wrapping can be tested offline. */
internal object HentaiHavenParser {
    /**
     * The site's `_r`: a letter-for-letter substitution of A–Z/a–z shifted by 13 — ROT13. Digits
     * and the base64 punctuation (`+`, `/`, `=`) fall through untouched, which is what lets it be
     * applied to base64 text without corrupting it.
     */
    fun rot13(value: String): String = value.map { char ->
        when {
            char in 'A'..'Z' -> 'A' + (char - 'A' + 13) % 26
            char in 'a'..'z' -> 'a' + (char - 'a' + 13) % 26
            else -> char
        }
    }.joinToString("")

    /**
     * `sha512-`-prefixed player token → the JSON it hides. Three rounds of ROT13-then-base64; the
     * prefix is decoration, there is no digest anywhere in this.
     */
    fun decodeSecureToken(token: String): String? = runCatching {
        var value = token.removePrefix("sha512-").trim()
        // ISO-8859-1 round-trips the intermediate rounds byte-for-byte; the payload is only
        // text again after the third decode.
        repeat(3) { value = String(Base64Compat.decode(rot13(value)), Charsets.ISO_8859_1) }
        value
    }.getOrNull()?.takeIf { it.trimStart().startsWith("{") }

    /**
     * The query to hand WordPress for a title. Its search wants every term to appear, which makes
     * a full romaji title brittle in two measured ways: AniList's trailing `.` (and `…`) matches
     * nothing, and flattening `Someya-san` to `Someya san` also matches nothing — so internal
     * punctuation is left exactly as-is and only the tail is trimmed. Capping at four words keeps
     * long titles findable; anything over-broad is filtered afterwards by scoring every result
     * against the *full* title, so a loose query costs nothing but a few extra rows.
     */
    fun searchQuery(title: String): String = title.trim()
        .split(WHITESPACE)
        .filter(String::isNotBlank)
        .take(4)
        .joinToString(" ")
        .trimEnd(*TRAILING_PUNCTUATION)

    /** Series slugs from a search page, minus WordPress's own `/watch/feed/` entry. */
    fun searchSlugs(html: String): List<String> = SEARCH_RESULT.findAll(html)
        .map { it.groupValues[1] }
        .filter { it != "feed" }
        .distinct()
        .toList()

    /**
     * Episode numbers a series page links to **for that series only**. The page also carries
     * "Popular" and "See More" widgets linking straight to other series' episodes, so an unscoped
     * scan silently inflates the count — a two-episode title picked up a third from the sidebar.
     */
    fun episodeNumbers(html: String, slug: String): Set<Int> {
        val scoped = Regex("""/watch/${Regex.escape(slug)}/episode-(\d+)""")
        return scoped.findAll(html).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()
    }

    /** The opaque `data=` blob on the player iframe, which the player page is addressed by. */
    fun playerDataParam(html: String): String? =
        PLAYER_IFRAME.find(html)?.groupValues?.get(1)?.takeIf(String::isNotBlank)

    /** The `x-secure-token` meta the player page carries its encoded config in. */
    fun secureToken(html: String): String? =
        SECURE_TOKEN.find(html)?.groupValues?.get(1)?.takeIf(String::isNotBlank)

    private val WHITESPACE = Regex("""\s+""")
    private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ':', ';', '!', '?', '-', '…', '\'', '"')
    private val SEARCH_RESULT = Regex("""href="https://hentaihaven\.xxx/watch/([a-z0-9-]+)/"""")
    private val EPISODE_LINK = Regex("""/watch/[a-z0-9-]+/episode-(\d+)""")
    private val PLAYER_IFRAME = Regex("""player\.php\?data=([^"&]+)""")
    private val SECURE_TOKEN = Regex("""name="x-secure-token"\s+content="([^"]+)"""")
}
