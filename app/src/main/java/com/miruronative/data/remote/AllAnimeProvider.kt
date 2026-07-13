package com.miruronative.data.remote

import com.miruronative.data.model.Media
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.StreamItem
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class AllAnimeProvider(
    private val client: OkHttpClient,
    private val json: Json,
) {
    private data class Candidate(
        val id: String,
        val title: String,
        val englishTitle: String?,
        val malId: Int?,
    )

    private val showIds = ConcurrentHashMap<Int, String>()

    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        val showId = showIds[media.id] ?: resolveShow(media, audio).also { showIds[media.id] = it }
        val sourceUrls = fetchSourceUrls(showId, audio, episode)
        val native = mutableListOf<StreamItem>()
        val embeds = mutableListOf<StreamItem>()

        sourceUrls.sortedByDescending(AllAnimeCodec.Source::priority).forEach { source ->
            if (source.name.equals("Ss-Hls", true)) return@forEach
            val rawUrl = source.url
            if (rawUrl.startsWith("--")) {
                val path = AllAnimeCodec.decodeSourceUrl(rawUrl)
                if (path.contains("/clock")) native += resolveClock(path, audio, source.name)
                return@forEach
            }
            if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) return@forEach

            val isDirect = source.type.equals("player", true) || MEDIA_URL.containsMatchIn(rawUrl)
            if (isDirect) {
                val type = if (rawUrl.contains(".m3u8", true)) "hls" else "mp4"
                native += stream(rawUrl, type, qualityLabel(source.name, source.quality), audio, REFERER)
            } else {
                embeds += stream(rawUrl, "embed", source.name.ifBlank { "AllAnime embed" }, audio, REFERER)
            }
        }

        val verified = native.distinctBy(StreamItem::url).filter(::isPlayable)
        val streams = (verified + embeds.distinctBy(StreamItem::url)).mapIndexed { index, item ->
            item.copy(isActive = index == 0)
        }
        if (streams.isEmpty()) error("AllAnime episode $episode has no playable sources")
        return SourcesResult(streams, emptyList(), null, null)
    }

    private fun resolveShow(media: Media, audio: String): String {
        val candidates = titles(media).flatMap { title ->
            runCatching { search(title, audio, media.isAdult) }.getOrDefault(emptyList())
        }.distinctBy(Candidate::id)
        media.idMal?.let { malId -> candidates.firstOrNull { it.malId == malId }?.let { return it.id } }

        val chosen = candidates.maxByOrNull { candidate ->
            titles(media).maxOfOrNull { title ->
                maxOf(
                    NativeProviderParsers.titleScore(title, candidate.title),
                    NativeProviderParsers.titleScore(title, candidate.englishTitle.orEmpty()),
                )
            } ?: 0.0
        } ?: error("AllAnime match not found")
        val score = titles(media).maxOfOrNull { title ->
            maxOf(
                NativeProviderParsers.titleScore(title, chosen.title),
                NativeProviderParsers.titleScore(title, chosen.englishTitle.orEmpty()),
            )
        } ?: 0.0
        if (score < 0.35) error("AllAnime title match was too weak")
        return chosen.id
    }

    private fun search(query: String, audio: String, allowAdult: Boolean): List<Candidate> {
        val variables = buildJsonObject {
            putJsonObject("search") {
                put("allowAdult", allowAdult)
                put("allowUnknown", false)
                put("query", query)
            }
            put("limit", 20)
            put("page", 1)
            put("translationType", if (audio == "dub") "dub" else "sub")
            put("countryOrigin", "ALL")
        }
        val root = postGraphQl(SEARCH_QUERY, variables)
        val edges = (((root["data"] as? JsonObject)?.get("shows") as? JsonObject)?.get("edges") as? JsonArray)
            .orEmpty()
        return edges.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.string("_id") ?: return@mapNotNull null
            Candidate(
                id = id,
                title = item.string("name") ?: id,
                englishTitle = item.string("englishName"),
                malId = item.int("malId"),
            )
        }
    }

    private fun postGraphQl(query: String, variables: JsonObject): JsonObject {
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(API).post(body).allAnimeHeaders().build()
        return executeText(request).let(json::parseToJsonElement).jsonObject
    }

    private fun fetchSourceUrls(showId: String, audio: String, episode: Int): List<AllAnimeCodec.Source> {
        val variables = buildJsonObject {
            put("showId", showId)
            put("translationType", if (audio == "dub") "dub" else "sub")
            put("episodeString", episode.toString())
        }
        val extensions = buildJsonObject {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", SOURCES_HASH)
            }
        }
        val url = API.toHttpUrl().newBuilder()
            .addQueryParameter("variables", variables.toString())
            .addQueryParameter("extensions", extensions.toString())
            .build()
        val request = Request.Builder().url(url).get().allAnimeHeaders().build()
        val root = json.parseToJsonElement(executeText(request)) as? JsonObject
            ?: error("AllAnime returned invalid source data")
        val data = root["data"] as? JsonObject ?: error("AllAnime returned no source data")
        val encrypted = data.string("tobeparsed")
        val decoded = if (!encrypted.isNullOrBlank()) {
            AllAnimeCodec.decrypt(encrypted)
        } else {
            data["episode"]
        }
        return AllAnimeCodec.parseSources(decoded)
    }

    private fun resolveClock(path: String, audio: String, fallbackName: String): List<StreamItem> {
        val url = NativeProviderParsers.absoluteUrl("https://allanime.day", path)
        val request = Request.Builder().url(url).get().allAnimeHeaders().build()
        val root = runCatching { json.parseToJsonElement(executeText(request)) as? JsonObject }.getOrNull()
            ?: return emptyList()
        return (root["links"] as? JsonArray).orEmpty().mapNotNull { element ->
            val link = element as? JsonObject ?: return@mapNotNull null
            val streamUrl = link.string("link") ?: link.string("url") ?: return@mapNotNull null
            val hls = link.boolean("hls") || streamUrl.contains(".m3u8", true) ||
                streamUrl.contains("repackager.wixmp", true)
            stream(
                streamUrl,
                if (hls) "hls" else "mp4",
                qualityLabel(fallbackName, link.string("resolutionStr")),
                audio,
                REFERER,
            )
        }
    }

    private fun isPlayable(item: StreamItem): Boolean = runCatching {
        val builder = Request.Builder().url(item.url).header("User-Agent", USER_AGENT).header("Referer", REFERER)
        if (!item.isHls) builder.header("Range", "bytes=0-1")
        client.newCall(builder.get().build()).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) return@use false
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            if ("text/html" in contentType) return@use false
            if (!item.isHls) return@use true
            response.body?.string().orEmpty().trimStart().startsWith("#EXTM3U")
        }
    }.getOrDefault(true)

    private fun executeText(request: Request): String = client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) error("AllAnime HTTP ${response.code}")
        body
    }

    private fun Request.Builder.allAnimeHeaders(): Request.Builder = this
        .header("User-Agent", USER_AGENT)
        .header("Referer", REFERER)
        .header("Origin", ORIGIN)
        .header("Accept", "application/json, */*")

    private fun stream(
        url: String,
        type: String,
        label: String,
        audio: String,
        referer: String,
    ) = StreamItem(url, type, label, audio, referer, false, null, null)

    private fun qualityLabel(source: String, quality: String?): String = listOf(
        "AllAnime",
        quality?.takeIf(String::isNotBlank),
        source.takeIf(String::isNotBlank),
    ).filterNotNull().joinToString(" ")

    private fun titles(media: Media): List<String> = listOfNotNull(
        media.title.english,
        media.title.romaji,
        media.title.native,
    ).filter(String::isNotBlank).distinct()

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.boolean(name: String): Boolean = (this[name] as? JsonPrimitive)?.contentOrNull == "true"

    companion object {
        private const val API = "https://api.allanime.day/api"
        private const val REFERER = "https://youtu-chan.com/"
        private const val ORIGIN = "https://youtu-chan.com"
        private const val SOURCES_HASH =
            "d405d0edd690624b66baba3068e0edc3ac90f1597d898a1ec8db4e5c43c00fec"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val MEDIA_URL = Regex("""\.(?:m3u8|mp4)(?:[?#]|$)""", RegexOption.IGNORE_CASE)
        private const val SEARCH_QUERY =
            "query(\$search: SearchInput, \$limit: Int, \$page: Int, \$translationType: VaildTranslationTypeEnumType, \$countryOrigin: VaildCountryOriginEnumType) { shows(search: \$search, limit: \$limit, page: \$page, translationType: \$translationType, countryOrigin: \$countryOrigin) { edges { _id name englishName malId availableEpisodes } } }"
    }
}

internal object AllAnimeCodec {
    data class Source(
        val name: String,
        val url: String,
        val type: String,
        val quality: String?,
        val priority: Int,
    )

    private val hexMap = mapOf(
        "79" to 'A', "7a" to 'B', "7b" to 'C', "7c" to 'D', "7d" to 'E', "7e" to 'F', "7f" to 'G',
        "70" to 'H', "71" to 'I', "72" to 'J', "73" to 'K', "74" to 'L', "75" to 'M', "76" to 'N',
        "77" to 'O', "68" to 'P', "69" to 'Q', "6a" to 'R', "6b" to 'S', "6c" to 'T', "6d" to 'U',
        "6e" to 'V', "6f" to 'W', "60" to 'X', "61" to 'Y', "62" to 'Z', "59" to 'a', "5a" to 'b',
        "5b" to 'c', "5c" to 'd', "5d" to 'e', "5e" to 'f', "5f" to 'g', "50" to 'h', "51" to 'i',
        "52" to 'j', "53" to 'k', "54" to 'l', "55" to 'm', "56" to 'n', "57" to 'o', "48" to 'p',
        "49" to 'q', "4a" to 'r', "4b" to 's', "4c" to 't', "4d" to 'u', "4e" to 'v', "4f" to 'w',
        "40" to 'x', "41" to 'y', "42" to 'z', "08" to '0', "09" to '1', "0a" to '2', "0b" to '3',
        "0c" to '4', "0d" to '5', "0e" to '6', "0f" to '7', "00" to '8', "01" to '9', "15" to '-',
        "16" to '.', "67" to '_', "46" to '~', "02" to ':', "17" to '/', "07" to '?', "1b" to '#',
        "63" to '[', "65" to ']', "78" to '@', "19" to '!', "1c" to '$', "1e" to '&', "10" to '(',
        "11" to ')', "12" to '*', "13" to '+', "14" to ',', "03" to ';', "05" to '=', "1d" to '%',
    )

    fun decodeSourceUrl(value: String): String {
        if (!value.startsWith("--")) return value
        return value.drop(2).chunked(2).mapNotNull(hexMap::get).joinToString("")
            .replace("/clock", "/clock.json")
    }

    fun decrypt(encoded: String): JsonElement {
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size > 29) { "AllAnime encrypted payload is too short" }
        val iv = bytes.copyOfRange(1, 13)
        val counter = iv + byteArrayOf(0, 0, 0, 2)
        val ciphertext = bytes.copyOfRange(13, bytes.size - 16)
        val key = MessageDigest.getInstance("SHA-256").digest(KEY_SEED.toByteArray(StandardCharsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(counter))
        val plaintext = cipher.doFinal(ciphertext).toString(StandardCharsets.UTF_8)
        return Json.parseToJsonElement(plaintext)
    }

    fun parseSources(value: JsonElement?): List<Source> {
        val root = value as? JsonObject ?: return emptyList()
        val sourceUrls = ((root["episode"] as? JsonObject)?.get("sourceUrls") as? JsonArray)
            ?: (root["sourceUrls"] as? JsonArray)
            ?: return emptyList()
        return sourceUrls.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val url = item.primitive("sourceUrl") ?: return@mapNotNull null
            Source(
                name = item.primitive("sourceName").orEmpty(),
                url = url,
                type = item.primitive("type").orEmpty(),
                quality = item.primitive("resolutionStr"),
                priority = item.primitive("priority")?.toIntOrNull() ?: 0,
            )
        }
    }

    private fun JsonObject.primitive(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private const val KEY_SEED = "Xot36i3lK3:v1"
}
