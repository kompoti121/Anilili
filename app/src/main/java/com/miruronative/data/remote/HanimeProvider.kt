package com.miruronative.data.remote

import com.miruronative.data.cache.AppCache
import com.miruronative.data.model.Media
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.StreamItem
import com.miruronative.diagnostics.DiagnosticsLog
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * hanime, reached two ways.
 *
 * Its whole library ships as a single JSON file which the site searches in the browser, so we hold
 * that catalogue on the device: it is both the source of truth for which episodes exist and the
 * index behind hentai results in search. Entries carry no MAL or AniList id, so a normal AniList
 * title is bound to a series by name ([matchHanimeSeries]); catalogue-native entries skip that and
 * address their series directly.
 *
 * Streams come from [HanimeBridge] rather than from here — the v11 handshake encrypts its request
 * and decrypts its reply in WebAssembly, so only the real page can perform it.
 */
internal class HanimeProvider(
    private val context: android.content.Context,
    private val client: OkHttpClient,
    private val json: Json,
    private val cache: AppCache,
) {
    @Volatile private var bundled: List<HanimeVideo>? = null

    /**
     * Never blocks on the network. A refreshed copy is used when one has been downloaded, and the
     * bundled snapshot answers until then — so search works on a cold install, offline, and before
     * the WebView has produced credentials.
     */
    suspend fun catalogue(): List<HanimeVideo> =
        cache.getIfFresh(CACHE_KEY, ListSerializer(HanimeVideo.serializer()))
            ?.takeIf { it.isNotEmpty() }
            ?: bundledCatalogue()

    /**
     * Pull a fresh copy in the background. hanime keeps releasing, so the bundled snapshot is a
     * starting point rather than the whole story; without this the app would only ever know the
     * titles that existed the day the release was cut.
     */
    suspend fun refresh(force: Boolean = false) {
        runCatching {
            cache.getOrFetch(
                key = CACHE_KEY,
                serializer = ListSerializer(HanimeVideo.serializer()),
                ttlMs = CATALOGUE_TTL_MS,
                forceRefresh = force,
            ) { download() }
        }.onFailure { DiagnosticsLog.throwable("Hanime catalogue refresh failed", it) }
    }

    /** The snapshot shipped in the APK; parsed once and held, since it never changes. */
    private fun bundledCatalogue(): List<HanimeVideo> {
        bundled?.let { return it }
        val parsed = runCatching {
            context.assets.open(BUNDLED_ASSET).use { stream ->
                json.decodeFromString(
                    ListSerializer(HanimeVideo.serializer()),
                    stream.readBytes().decodeToString(),
                )
            }
        }.onFailure { DiagnosticsLog.throwable("Hanime bundled catalogue unreadable", it) }
            .getOrDefault(emptyList())
        DiagnosticsLog.event("Hanime bundled catalogue loaded entries=${parsed.size}")
        bundled = parsed
        return parsed
    }

    suspend fun episodeAvailability(media: Media): EpisodeAvailability {
        val episodes = seriesFor(media)
        if (episodes.isEmpty()) error("hanime has no match for this title")
        // hanime is Japanese audio with burnt-in or absent subtitles; there is no dub track to
        // offer, so everything lands under sub rather than inventing an empty dub row.
        return EpisodeAvailability(episodes.map(HanimeVideo::episodeNumber).toSet(), emptySet())
    }

    suspend fun sources(media: Media, episode: Int): SourcesResult {
        val video = seriesFor(media).firstOrNull { it.episodeNumber == episode }
            ?: error("hanime episode $episode is not in the catalogue")
        val hls = HanimeBridge.resolveStream(video.slug)
            ?: error("hanime did not return a stream for ${video.slug}")
        val stream = StreamItem(
            url = hls,
            type = "hls",
            quality = "hanime",
            audio = null,
            referer = "$ORIGIN/",
            isActive = true,
            width = null,
            height = null,
        )
        DiagnosticsLog.event("Hanime resolved slug=${video.slug} episode=$episode")
        return SourcesResult(sortHanimeStreams(listOf(stream)), emptyList(), null, null)
    }

    /** Episodes of the series this media refers to, however it was reached. */
    private suspend fun seriesFor(media: Media): List<HanimeVideo> {
        val catalogue = runCatching { catalogue() }.getOrElse {
            DiagnosticsLog.throwable("Hanime catalogue unavailable", it)
            return emptyList()
        }
        if (isHanimeMediaId(media.id)) {
            // Came from our own catalogue, so the series is known exactly — no guessing by name.
            val videoId = hanimeVideoId(media.id)
            val anchor = catalogue.firstOrNull { it.id == videoId } ?: return emptyList()
            return catalogue.filter { it.seriesSlug == anchor.seriesSlug }
                .sortedBy(HanimeVideo::episodeNumber)
        }
        return matchHanimeSeries(media, catalogue)?.episodes.orEmpty()
    }

    private suspend fun download(): List<HanimeVideo> {
        val credentials = HanimeBridge.credentials()
            ?: error("hanime credentials unavailable; the resolver WebView is not attached")
        val request = Request.Builder()
            .url(CATALOGUE_URL)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$ORIGIN/")
            .header("Origin", ORIGIN)
            .header("X-Signature-Version", "web2")
            .header("X-Time", credentials.time)
            .header("X-Signature", credentials.signature)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("hanime catalogue HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val parsed = json.decodeFromString(ListSerializer(HanimeVideo.serializer()), body)
            DiagnosticsLog.event("Hanime catalogue downloaded entries=${parsed.size}")
            return parsed
        }
    }

    private companion object {
        const val ORIGIN = "https://hanime.tv"
        const val CATALOGUE_URL = "https://guest.freeanimehentai.net/api/v11/search_hvs"
        const val CACHE_KEY = "hanime-catalogue"
        const val BUNDLED_ASSET = "hanime-catalogue.json"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/130.0.0.0 Mobile Safari/537.36"

        /** Several megabytes per download, and the library grows slowly — a day is generous. */
        const val CATALOGUE_TTL_MS = 24L * 60 * 60 * 1000
    }
}
