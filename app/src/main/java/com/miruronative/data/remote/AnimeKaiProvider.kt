package com.miruronative.data.remote

import com.miruronative.data.model.Media
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.StreamItem
import com.miruronative.data.model.SubtitleItem
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal class AnimeKaiProvider(private val client: OkHttpClient) {
    private data class Match(val base: String, val slug: String)
    private data class Page(val match: Match, val html: String)

    private val matches = ConcurrentHashMap<Int, Match>()
    @Volatile private var activeBase = MIRRORS.first()

    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        val match = matches[media.id] ?: resolveMatch(media).also { matches[media.id] = it }
        val page = episodePage(match, episode)
        matches[media.id] = page.match
        val servers = AnimeKaiParser.parseServers(page.html)
        if (servers.isEmpty()) error("AnimeKai episode $episode has no servers")

        val preferredLanguages = if (audio == "dub") listOf("dub") else listOf("hsub", "sub", "softsub")
        val pool = servers.filter { it.language in preferredLanguages }.ifEmpty { servers }
            .sortedByDescending { serverRank(it.videoUrl) }
        val groupSubtitles = pool.flatMap { AnimeKaiParser.embedSubtitles(it.videoUrl) }.distinctBy(SubtitleItem::url)

        pool.take(8).forEach { server ->
            val resolved = runCatching { resolveEmbed(server, page.match.base, audio, groupSubtitles) }.getOrNull()
            if (resolved != null) return resolved
        }

        val embeds = pool.map { server ->
            StreamItem(
                url = NativeProviderParsers.absoluteUrl(page.match.base, server.videoUrl),
                type = "embed",
                quality = "AnimeKai ${server.name}",
                audio = audio,
                referer = "${page.match.base}/",
                isActive = false,
                width = null,
                height = null,
            )
        }.distinctBy(StreamItem::url).mapIndexed { index, stream -> stream.copy(isActive = index == 0) }
        if (embeds.isEmpty()) error("AnimeKai episode $episode has no playable sources")
        return SourcesResult(embeds, groupSubtitles, null, null)
    }

    private fun resolveMatch(media: Media): Match {
        val bases = listOf(activeBase) + MIRRORS.filterNot { it == activeBase }
        bases.forEach { base ->
            val cards = titles(media).flatMap { title ->
                runCatching { search(base, title) }.getOrDefault(emptyList())
            }.distinctBy(AnimeKaiParser.Card::slug)
            if (cards.isEmpty()) return@forEach

            val ranked = cards.sortedByDescending { card -> score(media, card.title) }.take(7)
            var best: Pair<AnimeKaiParser.Card, Double>? = null
            ranked.forEach { card ->
                val cardScore = score(media, card.title)
                if (best == null || cardScore > best!!.second) best = card to cardScore
                if (media.idMal != null) {
                    val watch = runCatching { getKai("$base/watch/${card.slug}", "$base/") }.getOrNull()
                    if (watch != null && AnimeKaiParser.malId(watch) == media.idMal) {
                        activeBase = base
                        return Match(base, card.slug)
                    }
                }
            }

            val chosen = best
            if (chosen != null && chosen.second >= 0.35) {
                activeBase = base
                return Match(base, chosen.first.slug)
            }
        }
        error("AnimeKai match not found")
    }

    private fun search(base: String, query: String): List<AnimeKaiParser.Card> {
        val url = "$base/browser".toHttpUrl().newBuilder().addQueryParameter("keyword", query).build()
        return AnimeKaiParser.parseCards(getKai(url.toString(), "$base/"))
    }

    private fun episodePage(match: Match, episode: Int): Page {
        val bases = listOf(match.base, activeBase) + MIRRORS
        bases.distinct().forEach { base ->
            val url = "$base/watch/${match.slug}/ep-$episode"
            val html = runCatching { getKai(url, "$base/watch/${match.slug}") }.getOrNull()
            if (!html.isNullOrBlank() && AnimeKaiParser.parseServers(html).isNotEmpty()) {
                activeBase = base
                return Page(Match(base, match.slug), html)
            }
        }
        error("AnimeKai episode page is unavailable")
    }

    private fun resolveEmbed(
        server: AnimeKaiParser.Server,
        base: String,
        audio: String,
        groupSubtitles: List<SubtitleItem>,
    ): SourcesResult? {
        var embed = NativeProviderParsers.absoluteUrl(base, server.videoUrl)
        if (Regex("""anikai\.(?:to|cc)/iframe/""", RegexOption.IGNORE_CASE).containsMatchIn(embed)) {
            val wrapper = getKai(embed, "$base/")
            val nested = Regex("""<iframe\b[^>]*src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(wrapper)?.groupValues?.get(1)
            if (!nested.isNullOrBlank()) embed = NativeProviderParsers.absoluteUrl(embed, nested)
        }

        val referer = "${origin(embed)}/"
        val html = getEmbed(embed, "$base/")
        val hls = AnimeKaiParser.findHls(html)?.let { NativeProviderParsers.absoluteUrl(embed, it) }
            ?: return null
        if (!isHls(hls, referer)) return null

        val subtitles = (
            AnimeKaiParser.embedSubtitles(embed) +
                AnimeKaiParser.pageSubtitles(html) +
                groupSubtitles
            ).distinctBy(SubtitleItem::url)
        val stream = StreamItem(
            url = hls,
            type = "hls",
            quality = "AnimeKai ${server.name}",
            audio = audio,
            referer = referer,
            isActive = true,
            width = null,
            height = null,
        )
        return SourcesResult(listOf(stream), subtitles, null, null)
    }

    private fun getKai(url: String, referer: String): String = execute(
        Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get().build(),
    )

    private fun getEmbed(url: String, referer: String): String = execute(
        Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Accept", "text/html,*/*")
            .get().build(),
    )

    private fun isHls(url: String, referer: String): Boolean = runCatching {
        val origin = origin(url)
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Origin", origin)
            .get().build()
        client.newCall(request).execute().use { response ->
            response.isSuccessful && response.body?.string().orEmpty().trimStart().startsWith("#EXTM3U")
        }
    }.getOrDefault(false)

    private fun execute(request: Request): String = client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) error("AnimeKai HTTP ${response.code}")
        body
    }

    private fun score(media: Media, candidate: String): Double = titles(media).maxOfOrNull { title ->
        NativeProviderParsers.titleScore(title, candidate)
    } ?: 0.0

    private fun titles(media: Media): List<String> = listOfNotNull(
        media.title.english,
        media.title.romaji,
        media.title.native,
    ).filter(String::isNotBlank).distinct()

    private fun serverRank(url: String): Int = when {
        Regex("""vivibebe\.|bibiemb\.|vibeplayer\.""", RegexOption.IGNORE_CASE).containsMatchIn(url) -> 3
        Regex("""megaup|4spromax""", RegexOption.IGNORE_CASE).containsMatchIn(url) -> 1
        else -> 2
    }

    private fun origin(url: String): String = runCatching {
        URI(url).let { "${it.scheme}://${it.authority}" }
    }.getOrDefault(url.substringBefore('/', ""))

    companion object {
        private val MIRRORS = listOf(
            "https://www3.anikai.cc",
            "https://www1.anikai.cc",
            "https://www2.anikai.cc",
            "https://www4.anikai.cc",
            "https://anikai.cc",
        )
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

internal object AnimeKaiParser {
    data class Card(val slug: String, val title: String)
    data class Server(val language: String, val name: String, val videoUrl: String)

    fun parseCards(html: String): List<Card> {
        val starts = Regex("""<div\b[^>]*class=["'][^"']*\baitem\b[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(html).toList()
        return starts.mapNotNull { match ->
            val end = starts.firstOrNull { it.range.first > match.range.first }?.range?.first
                ?: (match.range.first + 5_000).coerceAtMost(html.length)
            val block = html.substring(match.range.first, end)
            val href = Regex("""href=["'](/watch/[^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val slug = Regex("""/watch/([a-z0-9][a-z0-9-]*)""", RegexOption.IGNORE_CASE)
                .find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val titleTag = Regex("""<a\b[^>]*class=["'][^"']*\btitle\b[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
                .find(block)?.value.orEmpty()
            val title = NativeProviderParsers.attr(titleTag, "data-en")
                .ifBlank { NativeProviderParsers.attr(titleTag, "title") }
                .ifBlank {
                    Regex("""<a\b[^>]*class=["'][^"']*\btitle\b[^"']*["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
                        .find(block)?.groupValues?.get(1)?.let(NativeProviderParsers::stripTags).orEmpty()
                }
                .ifBlank { slug.replace('-', ' ') }
            Card(slug, title)
        }.distinctBy(Card::slug)
    }

    fun malId(html: String): Int? = (
        Regex("""myanimelist\.net/anime/(\d+)""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: Regex("""data-mal(?:-id)?=["'](\d+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
        )?.toIntOrNull()

    fun parseServers(html: String): List<Server> {
        val groupTags = Regex("""<[a-z0-9]+\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)
            .filter { tag ->
                val classes = NativeProviderParsers.attr(tag.value, "class")
                Regex("""(?:^|\s)server-items(?:\s|$)""", RegexOption.IGNORE_CASE).containsMatchIn(classes) &&
                    NativeProviderParsers.attr(tag.value, "data-id").isNotBlank()
            }.toList()
        val servers = mutableListOf<Server>()
        groupTags.forEachIndexed { index, group ->
            val language = NativeProviderParsers.attr(group.value, "data-id").lowercase()
            val end = groupTags.getOrNull(index + 1)?.range?.first ?: html.length
            val block = html.substring(group.range.last + 1, end)
            Regex("""<(?:span|div|li|a)\b[^>]*\bdata-video\s*=\s*(["']).*?\1[^>]*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .findAll(block).forEach { tag ->
                    val video = NativeProviderParsers.attr(tag.value, "data-video")
                    if (video.isBlank()) return@forEach
                    val classes = NativeProviderParsers.attr(tag.value, "class")
                    if (!classes.contains("server", true)) return@forEach
                    val name = NativeProviderParsers.attr(tag.value, "data-name")
                        .ifBlank { NativeProviderParsers.attr(tag.value, "title") }
                        .ifBlank { "Server" }
                    servers += Server(language, name, video)
                }
        }
        return servers.distinctBy { "${it.language}:${it.videoUrl}" }
    }

    fun embedSubtitles(embed: String): List<SubtitleItem> {
        val url = queryValue(embed, listOf("sub", "caption_1", "c1_file", "sub_file", "subtitle"))
            ?: return emptyList()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return emptyList()
        val label = queryValue(embed, listOf("sub_1", "c1_label", "caption_label")) ?: "English"
        return listOf(SubtitleItem(url, label, language(label)))
    }

    fun pageSubtitles(html: String): List<SubtitleItem> = Regex(
        """file\s*:\s*["']([^"']+\.vtt[^"']*)["'][\s\S]{0,100}?label\s*:\s*["']([^"']*)["']""",
        RegexOption.IGNORE_CASE,
    ).findAll(html).map { match ->
        val url = decodeJavascript(match.groupValues[1])
        val label = NativeProviderParsers.decodeEntities(match.groupValues[2]).ifBlank { "Subtitle" }
        SubtitleItem(url, label, language(label))
    }.distinctBy(SubtitleItem::url).toList()

    fun findHls(html: String): String? {
        val patterns = listOf(
            Regex("""const\s+src\s*=\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:["']file["']|file)\s*:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
        )
        return patterns.firstNotNullOfOrNull { regex -> regex.find(html)?.groupValues?.get(1) }
            ?.let(::decodeJavascript)
            ?: NativeProviderParsers.hlsUrls(html).firstOrNull()
    }

    private fun queryValue(url: String, names: List<String>): String? {
        names.forEach { name ->
            val raw = Regex("""[?&]${Regex.escape(name)}=([^&]+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.get(1)
            if (raw != null) {
                return runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8.toString()) }.getOrDefault(raw)
            }
        }
        return null
    }

    private fun decodeJavascript(value: String): String = NativeProviderParsers.decodeEntities(value)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\/", "/")

    private fun language(label: String): String = when {
        label.contains("english", true) -> "en"
        label.contains("spanish", true) -> "es"
        label.contains("french", true) -> "fr"
        label.contains("german", true) -> "de"
        label.contains("portuguese", true) -> "pt"
        else -> "und"
    }
}
