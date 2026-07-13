package com.miruronative.data.remote

import com.miruronative.data.ProviderCatalog
import com.miruronative.data.model.EpisodeItem
import com.miruronative.data.model.EpisodesResult
import com.miruronative.data.model.Media
import com.miruronative.data.model.ProviderData
import com.miruronative.data.model.SkipTimes
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.StreamItem
import com.miruronative.data.model.SubtitleItem
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Native Android implementation of the additional providers that used to be reached through
 * Anivexa-API. Episode rows are built from AniList immediately; provider matching and stream
 * extraction happen lazily when a user starts an episode.
 */
class AnivexaClient(
    private val client: OkHttpClient,
    private val json: Json,
    private val aniList: AniListClient,
) {
    private data class Candidate(val slug: String, val title: String)
    private data class MegaPlayEmbed(val url: String, val referer: String, val skip: SkipTimes?)
    private data class MegaPlayResult(
        val embedUrl: String,
        val origin: String,
        val streams: List<StreamItem>,
        val subtitles: List<SubtitleItem>,
        val skip: SkipTimes?,
    )

    private val mediaCache = boundedMap<Int, Media>(100)
    private val identityCache = boundedMap<String, String>(250)
    private val allAnime = AllAnimeProvider(client, json)
    private val animeKai = AnimeKaiProvider(client)

    suspend fun getEpisodes(anilistId: Int): EpisodesResult = withContext(Dispatchers.IO) {
        val media = media(anilistId)
        val aired = media.nextAiringEpisode?.episode?.minus(1)?.takeIf { it > 0 }
        val count = when {
            media.status == "RELEASING" && aired != null -> aired
            media.episodes != null && media.episodes > 0 -> media.episodes
            media.format == "MOVIE" -> 1
            else -> 1
        }

        EpisodesResult(
            ProviderCatalog.anivexaProviders.map { provider ->
                val sub = episodeRows(provider, anilistId, "sub", count)
                val dub = if (provider == "anizone") emptyList() else episodeRows(provider, anilistId, "dub", count)
                ProviderData(provider, sub, dub)
            },
        )
    }

    suspend fun getSources(episodeId: String): SourcesResult = withContext(Dispatchers.IO) {
        val request = NativeProviderParsers.episodeRequest(episodeId)
            ?: error("Invalid native provider episode id: $episodeId")
        val media = media(request.anilistId)
        when (request.provider) {
            "anikoto" -> anikoto(media, request.audio, request.episode)
            "allanime" -> allAnime.sources(media, request.audio, request.episode)
            "animekai" -> animeKai.sources(media, request.audio, request.episode)
            "reanime" -> reanime(media, request.audio, request.episode)
            "anizone" -> anizone(media, request.episode)
            "animegg" -> animegg(media, request.audio, request.episode)
            "anineko" -> anineko(media, request.audio, request.episode)
            "2dhive" -> twoDhive(media, request.audio, request.episode)
            else -> error("Unsupported native provider: ${request.provider}")
        }
    }

    private suspend fun media(id: Int): Media = mediaCache[id]
        ?: aniList.animeInfo(id)?.also { mediaCache[id] = it }
        ?: error("Anime $id was not found on AniList")

    private fun episodeRows(provider: String, id: Int, audio: String, count: Int): List<EpisodeItem> =
        (1..count).map { number ->
            EpisodeItem(
                pipeId = "watch/$provider/$id/$audio/$provider-$number",
                number = number.toDouble(),
                title = "Episode $number",
                image = null,
                filler = false,
            )
        }

    // ---- AniKoto / MegaPlay ---------------------------------------------------------------

    private fun anikoto(media: Media, audio: String, episode: Int): SourcesResult {
        val base = "https://megaplay.buzz"
        val primary = MegaPlayEmbed("$base/stream/ani/${media.id}/$episode/$audio", "https://hianimes.re/", null)
        val primaryResult = megaPlaySources(primary)
        val result = if (primaryResult.streams.any { it.isHls }) {
            primaryResult
        } else {
            runCatching { anikotoSiteEmbed(media, audio, episode)?.let(::megaPlaySources) }
                .getOrNull()
                ?.takeIf { it.streams.any(StreamItem::isHls) }
                ?: primaryResult
        }

        val streams = result.streams.toMutableList()
        streams += stream(result.embedUrl, "embed", "MegaPlay embed", "${result.origin}/", active = streams.isEmpty())
        return SourcesResult(streams.distinctBy { it.url }, result.subtitles.distinctBy { it.url }, result.skip, null)
    }

    private fun megaPlaySources(embed: MegaPlayEmbed): MegaPlayResult {
        var embedUrl = embed.url
        var page = runCatching { getText(embedUrl, mapOf("Referer" to embed.referer)) }.getOrDefault("")
        val iframe = Regex("""<iframe\b[^>]*src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(page)?.groupValues?.get(1)
        if (page.data("id").isNullOrBlank() && !iframe.isNullOrBlank()) {
            embedUrl = NativeProviderParsers.absoluteUrl(embedUrl, iframe)
            page = runCatching { getText(embedUrl, mapOf("Referer" to embed.referer)) }.getOrDefault(page)
        }

        val streams = mutableListOf<StreamItem>()
        val subtitles = mutableListOf<SubtitleItem>()
        val fileId = page.data("id")
        val origin = origin(embedUrl)
        var skip = embed.skip
        if (!fileId.isNullOrBlank()) {
            val source = runCatching {
                getJson("$origin/stream/getSources?id=${enc(fileId)}&id=${enc(fileId)}", mapOf(
                    "Referer" to "$origin/",
                    "X-Requested-With" to "XMLHttpRequest",
                )) as? JsonObject
            }.getOrNull()
            val sourceFile = (source?.get("sources") as? JsonObject)?.string("file")
            if (!sourceFile.isNullOrBlank()) {
                streams += stream(sourceFile, "hls", "MegaPlay", "$origin/", active = true)
            }
            (source?.get("tracks") as? JsonArray).orEmpty().forEach { track ->
                val obj = track as? JsonObject ?: return@forEach
                val kind = obj.string("kind")?.lowercase()
                if (kind != null && kind != "captions" && kind != "subtitles") return@forEach
                val file = obj.string("file") ?: return@forEach
                val item = SubtitleItem(file, obj.string("label") ?: "Subtitle", language(obj.string("label")))
                val isDefault = (obj["default"] as? JsonPrimitive)?.booleanOrNull == true
                if (isDefault) subtitles.add(0, item) else subtitles += item
            }
            skip = skipTimes(source) ?: skip
        }
        return MegaPlayResult(embedUrl, origin, streams.distinctBy { it.url }, subtitles.distinctBy { it.url }, skip)
    }

    private fun anikotoSiteEmbed(media: Media, audio: String, episode: Int): MegaPlayEmbed? {
        val base = "https://anikototv.to"
        val key = "anikoto-site:${media.id}"
        val cached = identityCache[key]?.let { listOf(Candidate(it, it.replace('-', ' '))) }.orEmpty()
        val candidates = (cached + anikotoSiteCandidates(base, media))
            .distinctBy { it.slug }
            .sortedByDescending { candidateScore(media, it) }
            .take(12)
        candidates.forEach { candidate ->
            val embed = runCatching { anikotoSiteEmbedForSlug(base, candidate.slug, media, audio, episode) }
                .getOrNull()
            if (embed != null) {
                identityCache[key] = candidate.slug
                return embed
            }
        }
        return null
    }

    private fun anikotoSiteCandidates(base: String, media: Media): List<Candidate> =
        titles(media).flatMap { query ->
            runCatching {
                val search = getJson("$base/ajax/anime/search?keyword=${enc(query)}", xhr("$base/"))
                val html = htmlResult(search)
                Regex(
                    """<a\b([^>]*href=["'][^"']*/watch/[^"']+["'][^>]*)>([\s\S]*?)</a>""",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
                ).findAll(html).mapNotNull { match ->
                    val href = NativeProviderParsers.attr(match.groupValues[1], "href")
                    val slug = Regex("""/watch/([^/?#"']+)""").find(href)?.groupValues?.get(1)
                        ?: return@mapNotNull null
                    Candidate(
                        slug,
                        NativeProviderParsers.stripTags(match.groupValues[2]).ifBlank { slug.replace('-', ' ') },
                    )
                }.toList()
            }.getOrDefault(emptyList())
        }.distinctBy { it.slug }

    private fun anikotoSiteEmbedForSlug(
        base: String,
        slug: String,
        media: Media,
        audio: String,
        episode: Int,
    ): MegaPlayEmbed? {
        val watchUrl = "$base/watch/$slug/ep-$episode"
        val watch = getText(watchUrl, mapOf("Referer" to "$base/"))
        val animeId = Regex("""data-anime-id=["'](\d+)["']""", RegexOption.IGNORE_CASE)
            .find(watch)?.groupValues?.get(1)
            ?: return null
        val episodes = getJson("$base/ajax/episode/list/$animeId", xhr(watchUrl)) as? JsonObject ?: return null
        val episodeTag = anikotoEpisodeTag(htmlResult(episodes), media, audio, episode) ?: return null
        val serverToken = NativeProviderParsers.attr(episodeTag, "data-ids").takeIf { it.isNotBlank() } ?: return null
        val servers = getJson("$base/ajax/server/list?servers=${enc(serverToken)}", xhr(watchUrl)) as? JsonObject
            ?: return null
        val linkId = anikotoServerLink(htmlResult(servers), audio) ?: return null
        val resolved = getJson("$base/ajax/server?get=${enc(linkId)}", xhr(watchUrl)) as? JsonObject ?: return null
        val result = (resolved["result"] as? JsonObject) ?: resolved
        val url = result.string("url") ?: return null
        return MegaPlayEmbed(url, "$base/", anikotoSkipData(result["skip_data"] as? JsonObject))
    }

    private fun anikotoEpisodeTag(html: String, media: Media, audio: String, episode: Int): String? {
        val expectedMal = media.idMal?.toString()
        val audioAttr = if (audio == "dub") "data-dub" else "data-sub"
        val tags = Regex("""<a\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)
            .map { it.value }
            .filter { tag ->
                val num = NativeProviderParsers.attr(tag, "data-num").toIntOrNull()
                val slug = NativeProviderParsers.attr(tag, "data-slug").toIntOrNull()
                (num == episode || slug == episode) &&
                    NativeProviderParsers.attr(tag, audioAttr).let { it.isBlank() || it == "1" }
            }
            .toList()
        if (expectedMal != null) {
            return tags.firstOrNull { tag -> NativeProviderParsers.attr(tag, "data-mal") == expectedMal }
        }
        return tags.firstOrNull()
    }

    private fun anikotoServerLink(html: String, audio: String): String? {
        val items = Regex(
            """<li\b[^>]*\bdata-link-id=["'][^"']+["'][^>]*>[\s\S]*?</li>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(html).map { it.value }.toList()
        val compatible = items.filter { item ->
            NativeProviderParsers.attr(item, "data-type").let { it.isBlank() || it.equals(audio, true) }
        }.ifEmpty { items }
        val preferred = compatible.firstOrNull {
            NativeProviderParsers.attr(it, "data-sv-id").equals("e54", true) ||
                it.contains("Vidstream", ignoreCase = true)
        } ?: compatible.firstOrNull()
        return preferred?.let { NativeProviderParsers.attr(it, "data-link-id").takeIf { link -> link.isNotBlank() } }
    }

    private fun anikotoSkipData(root: JsonObject?): SkipTimes? {
        fun range(name: String): Pair<Double?, Double?> {
            val values = root?.get(name) as? JsonArray ?: return null to null
            return ((values.getOrNull(0) as? JsonPrimitive)?.doubleOrNull) to
                ((values.getOrNull(1) as? JsonPrimitive)?.doubleOrNull)
        }
        val intro = range("intro")
        val outro = range("outro")
        if (intro.first == null && intro.second == null && outro.first == null && outro.second == null) return null
        return SkipTimes(intro.first, intro.second, outro.first, outro.second)
    }

    // ---- ReAnime --------------------------------------------------------------------------

    private fun reanime(media: Media, audio: String, episode: Int): SourcesResult {
        val base = "https://reanime.to"
        val title = titles(media).firstOrNull() ?: error("ReAnime requires a title")
        val search = getJson("$base/api/v1/search?q=${enc(title)}&limit=8")
        val results = when (search) {
            is JsonArray -> search
            is JsonObject -> (search["results"] as? JsonArray) ?: (search["data"] as? JsonArray) ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        val slug = results.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.string("anime_id") ?: obj.string("slug") ?: obj.string("id") ?: return@mapNotNull null
            val titleObject = obj["title"] as? JsonObject
            val candidateTitle = titleObject?.string("english")
                ?: titleObject?.string("romaji")
                ?: obj.string("title")
                ?: obj.string("name")
                ?: id
            Candidate(id, candidateTitle)
        }.maxByOrNull { candidateScore(media, it) }?.slug ?: error("ReAnime match not found")

        val watch = runCatching { getJson("$base/api/watch/$slug/$episode") as? JsonObject }.getOrNull()
        val flix = runCatching { getJson("$base/api/flix/${media.id}/$episode") as? JsonObject }.getOrNull()
        val links = buildList {
            addAll((watch?.get("episode_links") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject })
            addAll((flix?.get("servers") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject })
        }
        val accepted = if (audio == "dub") setOf("dub", "s-dub") else setOf("sub", "s-sub")
        val streams = links.mapNotNull { link ->
            val kind = link.string("dataType")?.lowercase() ?: return@mapNotNull null
            if (kind !in accepted) return@mapNotNull null
            var url = link.string("dataLink") ?: return@mapNotNull null
            // ReAnime serves dual-audio embeds: sub and dub share one URL and the site
            // selects the English track by appending a=1 (audio track index).
            if (audio == "dub" && !url.contains(Regex("""[?&]a="""))) {
                url += if ('?' in url) "&a=1" else "?a=1"
            }
            stream(url, "embed", link.string("serverName") ?: "ReAnime", "$base/", active = false)
        }.distinctBy { it.url }.mapIndexed { index, item -> item.copy(isActive = index == 0) }
        return SourcesResult(streams, emptyList(), skipTimes(watch), null)
    }

    // ---- AniZone --------------------------------------------------------------------------

    private fun anizone(media: Media, episode: Int): SourcesResult {
        val base = "https://anizone.to"
        val slug = resolveSlug("anizone", media) { query ->
            val html = getText("$base/anime?search=${enc(query)}")
            val animeLinks = Regex(
                """href=["'](?:https://anizone\.to)?/anime/([a-z0-9-]+)(?:[/?#][^"']*)?["']""",
                RegexOption.IGNORE_CASE,
            )
            val fallbackTitle = Regex(
                """getTitle\(this\.anmTitles,\s*'((?:\\'|[^'])*)'\)""",
                RegexOption.IGNORE_CASE,
            )
            animeLinks.findAll(html)
                .map { link ->
                    val start = (link.range.first - 3_000).coerceAtLeast(0)
                    val prefix = html.substring(start, link.range.first)
                    val title = fallbackTitle.findAll(prefix).lastOrNull()?.groupValues?.get(1)
                        ?.replace("\\'", "'")
                        ?: link.groupValues[1].replace('-', ' ')
                    Candidate(link.groupValues[1], title)
                }
                .distinctBy { it.slug }
                .toList()
        }
        val pageUrl = "$base/anime/$slug/$episode"
        val html = getText(pageUrl, mapOf("Referer" to "$base/anime/$slug"))
        val hls = Regex("""<media-player[^>]+src=["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let(NativeProviderParsers::decodeEntities)
            ?: NativeProviderParsers.hlsUrls(html).firstOrNull()
            ?: error("AniZone episode $episode has no HLS stream")
        val subtitles = Regex("""<track\b([^>]*)>""", RegexOption.IGNORE_CASE).findAll(html).mapNotNull { match ->
            val tag = match.value
            if (!NativeProviderParsers.attr(tag, "kind").equals("subtitles", true)) return@mapNotNull null
            val url = NativeProviderParsers.attr(tag, "src").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val label = NativeProviderParsers.attr(tag, "label").ifBlank { "Subtitle" }
            SubtitleItem(NativeProviderParsers.absoluteUrl(base, url), label, NativeProviderParsers.attr(tag, "srclang").ifBlank { language(label) })
        }.toList()
        return SourcesResult(listOf(stream(hls, "hls", "AniZone", pageUrl, true)), subtitles, null, null)
    }

    // ---- AnimeGG --------------------------------------------------------------------------

    private fun animegg(media: Media, audio: String, episode: Int): SourcesResult {
        val base = "https://www.animegg.org"
        val slug = resolveSlug("animegg", media) { query ->
            val html = getText("$base/search/?q=${enc(query)}")
            Regex("""<a\b([^>]*class=["'][^"']*\bmse\b[^"']*["'][^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .mapNotNull { match ->
                    val href = NativeProviderParsers.attr(match.groupValues[1], "href")
                    val id = Regex("""^/series/([^/?#]+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
                    val strong = Regex("""<strong[^>]*>([\s\S]*?)</strong>""", RegexOption.IGNORE_CASE)
                        .find(match.groupValues[2])?.groupValues?.get(1)
                    Candidate(id, strong?.let(NativeProviderParsers::stripTags) ?: id.replace('-', ' '))
                }.toList()
        }
        val series = getText("$base/series/$slug")
        val episodeSlug = Regex("""<li\b[^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE).findAll(series).mapNotNull { match ->
            val block = match.groupValues[1]
            if (!block.contains("anm_det_pop")) return@mapNotNull null
            val number = Regex("""<strong[^>]*>[\s\S]*?(\d+)\s*</strong>""", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            if (number != episode) return@mapNotNull null
            val tag = Regex("""<a\b[^>]*class=["'][^"']*anm_det_pop[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
                .find(block)?.value ?: return@mapNotNull null
            NativeProviderParsers.attr(tag, "href").trimStart('/').substringBefore('#')
        }.firstOrNull() ?: error("AnimeGG episode $episode was not found")

        val watchUrl = "$base/$episodeSlug"
        val watch = getText(watchUrl, mapOf("Referer" to base))
        val tabs = Regex("""<a\b[^>]*data-toggle=["']tab["'][^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(watch)
            .mapNotNull { match ->
                val id = NativeProviderParsers.attr(match.value, "data-id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val version = NativeProviderParsers.attr(match.value, "data-version")
                val tabAudio = if (version.startsWith("dub", true)) "dub" else "sub"
                if (tabAudio != audio) return@mapNotNull null
                id to NativeProviderParsers.attr(match.value, "data-mirror").ifBlank { "AnimeGG" }
            }.toList()
        val streams = mutableListOf<StreamItem>()
        tabs.take(4).forEachIndexed { index, (id, server) ->
            val embed = "$base/embed/$id"
            val embedHtml = runCatching { getText(embed, mapOf("Referer" to base)) }.getOrDefault("")
            NativeProviderParsers.hlsUrls(embedHtml).forEach { url ->
                streams += stream(NativeProviderParsers.absoluteUrl(base, url), "hls", server, embed, streams.isEmpty())
            }
            // AnimeGG serves progressive MP4s through jwplayer (`file: "/play/.../video.mp4?for=..."`).
            Regex("""file:\s*["']([^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE)
                .findAll(embedHtml).forEach { match ->
                    val url = NativeProviderParsers.absoluteUrl(base, match.groupValues[1])
                    streams += stream(url, "video", server, embed, streams.isEmpty())
                }
            streams += stream(embed, "embed", "$server embed", embed, streams.isEmpty() && index == 0)
        }
        return SourcesResult(streams.distinctBy { it.url }, emptyList(), null, null)
    }

    // ---- AniNeko --------------------------------------------------------------------------

    private fun anineko(media: Media, audio: String, episode: Int): SourcesResult {
        val base = "https://anineko.to"
        val slug = resolveSlug("anineko", media) { query ->
            val html = getText("$base/browser?keyword=${enc(query)}")
            Regex("""<a\b([^>]*class=["'][^"']*nv-anime-thumb[^"']*["'][^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .mapNotNull { match ->
                    val href = NativeProviderParsers.attr(match.groupValues[1], "href")
                    val id = Regex("""/watch/([^/?#]+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
                    Candidate(id, NativeProviderParsers.stripTags(match.groupValues[2]).ifBlank { id.replace('-', ' ') })
                }.toList()
        }
        val watchUrl = "$base/watch/$slug/ep-$episode"
        val html = getText(watchUrl, mapOf("Referer" to "$base/watch/$slug"))
        val embeds = mutableListOf<String>()
        Regex("""<div\b[^>]*class=["'][^"']*nv-server-grid[^"']*["'][^>]*data-id=["']([^"']+)["'][^>]*>([\s\S]*?)(?=<div\b[^>]*class=["'][^"']*nv-server-grid|$)""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { panel ->
                val panelAudio = if (panel.groupValues[1].contains("dub", true)) "dub" else "sub"
                if (panelAudio == audio) {
                    Regex("""data-video=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .findAll(panel.groupValues[2]).forEach { embeds += NativeProviderParsers.decodeEntities(it.groupValues[1]) }
                }
            }
        val streams = mutableListOf<StreamItem>()
        val subtitles = mutableListOf<SubtitleItem>()
        embeds.distinct().take(4).forEachIndexed { index, embed ->
            val embedHtml = runCatching { getText(embed, mapOf("Referer" to "$base/")) }.getOrDefault("")
            NativeProviderParsers.hlsUrls(embedHtml).forEach { hls ->
                streams += stream(hls, "hls", "AniNeko", embed, streams.isEmpty())
            }
            subtitles += embedQuerySubtitles(embed)
            streams += stream(embed, "embed", "AniNeko embed", embed, streams.isEmpty() && index == 0)
        }
        return SourcesResult(streams.distinctBy { it.url }, subtitles.distinctBy { it.url }, null, null)
    }

    /**
     * AniNeko softsub servers pass their subtitle tracks to the embed player through the URL
     * (`?sub=<vtt>` or `?caption_1=<vtt>&sub_1=English`); recover them for native playback.
     */
    private fun embedQuerySubtitles(embedUrl: String): List<SubtitleItem> {
        val query = embedUrl.substringAfter('?', "")
        if (query.isBlank()) return emptyList()
        val params = query.split('&').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
        }
        val byName = params.toMap()
        return params.mapNotNull { (key, value) ->
            if (!value.startsWith("http")) return@mapNotNull null
            val label = when {
                key == "sub" -> "English"
                key.startsWith("caption") -> byName["sub${key.removePrefix("caption")}"] ?: "Subtitle"
                else -> return@mapNotNull null
            }
            SubtitleItem(value, label, language(label))
        }
    }

    // ---- 2Dhive ---------------------------------------------------------------------------

    private fun twoDhive(media: Media, audio: String, episode: Int): SourcesResult {
        val malId = media.idMal ?: error("2Dhive needs a MyAnimeList id")
        val base = "https://2dhive.com"
        val referer = "$base/episode?anime=$malId&ep_num=$episode"
        val streams = mutableListOf<StreamItem>()
        val subtitles = mutableListOf<SubtitleItem>()

        val props = runCatching { extractAstroProps(getText(referer)) }.getOrNull()
        val matchingServers = (props?.get("servers") as? JsonArray).orEmpty().mapNotNull { element ->
            val server = element as? JsonObject ?: return@mapNotNull null
            val isDub = server["dub"]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false
            if (!server.string("server_name").equals("HAdfree", true) || isDub != (audio == "dub")) {
                return@mapNotNull null
            }
            server.string("slug")
        }
        matchingServers.take(3).forEach { slug ->
            val direct = runCatching {
                (getJson("$base/api/hadfree?slug=${enc(slug)}", mapOf("Referer" to referer)) as? JsonObject)
                    ?.string("streamUrl")
            }.getOrNull()
            if (!direct.isNullOrBlank()) {
                val type = if (direct.contains(".m3u8", true)) "hls" else "video"
                streams += stream(direct, type, "2Dhive HAdfree", referer, streams.isEmpty())
            }
        }

        if (audio == "sub") {
            val hiAnime = runCatching {
                getJson("$base/api/hianime?mal_id=$malId&ep_num=$episode", mapOf("Referer" to referer)) as? JsonObject
            }.getOrNull()
            val hls = hiAnime?.string("m3u8")
            val usableHls = hls?.takeIf { url ->
                runCatching { getText(url, mapOf("Referer" to referer)).trimStart().startsWith("#EXTM3U") }
                    .getOrDefault(false)
            }
            usableHls?.let { streams += stream(it, "hls", "2Dhive hiAnime", referer, streams.isEmpty()) }
            if (usableHls != null) {
                hiAnime?.string("subtitle")?.let { subtitles += SubtitleItem(it, "English", "en") }
            }
        }
        val embed = "https://megaplay.buzz/stream/mal/$malId/$episode/${if (audio == "dub") "dub" else "sub"}"
        streams += stream(embed, "embed", "2Dhive MegaPlay", referer, streams.isEmpty())
        return SourcesResult(streams, subtitles, null, null)
    }

    // ---- Shared ---------------------------------------------------------------------------

    private fun resolveSlug(provider: String, media: Media, search: (String) -> List<Candidate>): String {
        val key = "$provider:${media.id}"
        identityCache[key]?.let { return it }
        val candidates = titles(media).flatMap { title -> runCatching { search(title) }.getOrDefault(emptyList()) }
            .distinctBy { it.slug }
        val chosen = candidates.maxByOrNull { candidateScore(media, it) }
            ?: error("${ProviderCatalog.label(provider)} match not found")
        if (candidateScore(media, chosen) < 0.28) error("${ProviderCatalog.label(provider)} title match was too weak")
        identityCache[key] = chosen.slug
        return chosen.slug
    }

    private fun <K, V> boundedMap(maxEntries: Int): MutableMap<K, V> = Collections.synchronizedMap(
        object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
                size > maxEntries
        },
    )

    private fun candidateScore(media: Media, candidate: Candidate): Double {
        var score = titles(media).maxOfOrNull { NativeProviderParsers.titleScore(it, candidate.title) } ?: 0.0
        score = maxOf(score, titles(media).maxOfOrNull { NativeProviderParsers.titleScore(it, candidate.slug) } ?: 0.0)
        media.seasonYear?.let { year ->
            val candidateYear = Regex("""\b(19|20)\d{2}\b""").find(candidate.title)?.value?.toIntOrNull()
            if (candidateYear != null) score *= if (candidateYear == year) 1.15 else 0.55
        }
        return score.coerceAtMost(1.0)
    }

    private fun titles(media: Media): List<String> = listOfNotNull(
        media.title.english,
        media.title.romaji,
        media.title.native,
    ).filter { it.isNotBlank() }.distinct()

    private fun xhr(referer: String): Map<String, String> = mapOf(
        "Referer" to referer,
        "X-Requested-With" to "XMLHttpRequest",
    )

    private fun htmlResult(element: JsonElement?): String = when (element) {
        is JsonObject -> element.string("html") ?: element.string("result") ?: htmlResult(element["result"])
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        else -> ""
    }

    private fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/json,*/*")
        headers.forEach(builder::header)
        client.newCall(builder.get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code} fetching $url")
            return body
        }
    }

    private fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement =
        json.parseToJsonElement(getText(url, headers + ("Accept" to "application/json, */*")))

    private fun stream(url: String, type: String, label: String, referer: String?, active: Boolean) = StreamItem(
        url = url,
        type = type,
        quality = label,
        audio = null,
        referer = referer,
        isActive = active,
        width = null,
        height = null,
    )

    private fun skipTimes(root: JsonObject?): SkipTimes? {
        fun range(name: String): Pair<Double?, Double?> {
            val obj = root?.get(name) as? JsonObject
            return (obj?.number("start")) to (obj?.number("end"))
        }
        val intro = range("intro")
        val outro = range("outro")
        if (intro.first == null && intro.second == null && outro.first == null && outro.second == null) return null
        return SkipTimes(intro.first, intro.second, outro.first, outro.second)
    }

    /** Decode Astro's compact `[type, value]` serialization used in 2Dhive component props. */
    private fun extractAstroProps(html: String): JsonObject? {
        val marker = html.indexOf("prefetchedHls")
        if (marker < 0) return null
        val valueStart = html.lastIndexOf("props=\"", marker).takeIf { it >= 0 }?.plus(7) ?: return null
        val valueEnd = html.indexOf('"', valueStart).takeIf { it > valueStart } ?: return null
        val raw = NativeProviderParsers.decodeEntities(html.substring(valueStart, valueEnd))
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
        return JsonObject(root.mapValues { (_, value) -> astroDecode(value) })
    }

    private fun astroDecode(value: JsonElement): JsonElement {
        if (value !is JsonArray || value.size < 2) {
            return if (value is JsonObject) JsonObject(value.mapValues { astroDecode(it.value) }) else value
        }
        val type = (value[0] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return value
        val data = value[1]
        return when (type) {
            0 -> if (data is JsonObject) JsonObject(data.mapValues { astroDecode(it.value) }) else data
            1 -> if (data is JsonArray) JsonArray(data.map(::astroDecode)) else data
            else -> data
        }
    }

    private fun language(label: String?): String = when (label?.lowercase()) {
        "english", "en" -> "en"
        "japanese", "ja" -> "ja"
        "french", "fr" -> "fr"
        "german", "de" -> "de"
        "spanish", "es" -> "es"
        "portuguese", "pt" -> "pt"
        else -> "und"
    }

    private fun origin(url: String): String = runCatching {
        URI(url).let { "${it.scheme}://${it.authority}" }
    }.getOrDefault(url.substringBefore('/', ""))

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    private fun String.data(name: String): String? = Regex("""data-${Regex.escape(name)}=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        .find(this)?.groupValues?.get(1)?.let(NativeProviderParsers::decodeEntities)

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
