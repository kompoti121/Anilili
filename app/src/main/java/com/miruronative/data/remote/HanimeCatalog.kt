package com.miruronative.data.remote

import com.miruronative.data.model.CoverImage
import com.miruronative.data.model.Media
import com.miruronative.data.model.MediaTag
import com.miruronative.data.model.MediaTitle
import com.miruronative.data.model.StreamItem
import com.miruronative.data.model.StudioConnection
import com.miruronative.data.model.StudioNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One entry of hanime's catalogue. The site ships its whole library as a single JSON file and
 * searches it in the browser, so this is the entire record we get — there is no per-title lookup
 * and, notably, no MAL or AniList id on it. Matching back to AniList is done on the titles.
 */
@Serializable
data class HanimeVideo(
    val id: Int,
    val name: String = "",
    val slug: String = "",
    /** Alternate names, including the Japanese one — the fallback that rescues localised titles. */
    @SerialName("search_titles") val searchTitles: String = "",
    val brand: String = "",
    /** Kept because it is the only synopsis these entries have — AniList holds none for them. */
    val description: String = "",
    @SerialName("poster_url") val posterUrl: String = "",
    @SerialName("released_at") val releasedAt: String = "",
    /** Content descriptors; carried through as media tags so search filters can reach them. */
    val tags: List<String> = emptyList(),
) {
    /** Everything in this series shares a slug once the episode number is off the end. */
    val seriesSlug: String get() = hanimeSeriesSlug(slug)
    val episodeNumber: Int get() = hanimeEpisodeNumber(slug)
}

/**
 * Strip the trailing episode number from a slug. 94% of the catalogue is numbered this way
 * (`yabai-fukushuu-yami-site-1`, `-2`), which is what lets 3,340 videos collapse into ~1,530
 * series that line up with AniList's one-entry-per-series model.
 */
fun hanimeSeriesSlug(slug: String): String = slug.trim().replace(TRAILING_SLUG_NUMBER, "")

/** Episode number carried by the slug; unnumbered entries are one-shots and count as episode 1. */
fun hanimeEpisodeNumber(slug: String): Int =
    TRAILING_SLUG_NUMBER.find(slug.trim())?.groupValues?.get(1)?.toIntOrNull() ?: 1

/**
 * Fold a title down to comparable text: lower case, punctuation to spaces, runs of space
 * collapsed. Punctuation is where these two catalogues disagree most — hanime writes
 * "Maki-chan to Nau" for AniList's "Maki-chan to Nau." and "I Can" for "I☆Can".
 */
fun normalizeHanimeTitle(value: String): String = value
    .lowercase()
    .map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
    .joinToString("")
    .split(' ')
    .filter(String::isNotBlank)
    .joinToString(" ")

/** Titles minus any trailing episode number, which AniList does not carry in the series name. */
fun hanimeTitleWithoutEpisode(value: String): String =
    value.trim().replace(TRAILING_TITLE_NUMBER, "").trim()

/**
 * Dice coefficient over character bigrams — cheap, order-tolerant, and stable on the short
 * romaji strings both catalogues use. 1.0 is identical; the caller decides the threshold.
 */
fun titleSimilarity(left: String, right: String): Double {
    val a = normalizeHanimeTitle(left)
    val b = normalizeHanimeTitle(right)
    if (a.isEmpty() || b.isEmpty()) return 0.0
    if (a == b) return 1.0
    val pairs = bigrams(a)
    val other = bigrams(b)
    if (pairs.isEmpty() || other.isEmpty()) return 0.0
    val counts = HashMap<String, Int>(other.size)
    other.forEach { counts[it] = (counts[it] ?: 0) + 1 }
    var shared = 0
    pairs.forEach { pair ->
        val left1 = counts[pair] ?: 0
        if (left1 > 0) {
            counts[pair] = left1 - 1
            shared++
        }
    }
    return (2.0 * shared) / (pairs.size + other.size)
}

/** The names an AniList entry can be known by, best-known first. */
fun aniListTitleCandidates(media: Media): List<String> = listOfNotNull(
    media.title.romaji,
    media.title.english,
    media.title.native,
    media.title.userPreferred,
).map(String::trim).filter(String::isNotEmpty).distinct()

/** The names a hanime series can be known by; `search_titles` is what carries the Japanese one. */
fun hanimeTitleCandidates(video: HanimeVideo): List<String> = listOf(
    video.name,
    hanimeTitleWithoutEpisode(video.name),
    video.searchTitles,
).map(String::trim).filter(String::isNotEmpty).distinct()

/** A matched hanime series and how confident the title match was. */
data class HanimeSeriesMatch(val seriesSlug: String, val episodes: List<HanimeVideo>, val score: Double)

/**
 * Below this a match is more likely to be a different show with a similar name than the right one,
 * and a wrong hentai match is worse than none — the viewer gets a video that is not what they
 * asked for rather than an honest "no source".
 */
const val HANIME_MATCH_THRESHOLD = 0.85

/**
 * Best hanime series for an AniList entry, or null when nothing clears the bar. Measured at ~80%
 * of a random catalogue sample; the misses are titles hanime lists under an English localisation
 * that AniList only holds in Japanese.
 */
fun matchHanimeSeries(
    media: Media,
    catalogue: List<HanimeVideo>,
    threshold: Double = HANIME_MATCH_THRESHOLD,
): HanimeSeriesMatch? {
    val wanted = aniListTitleCandidates(media)
    if (wanted.isEmpty() || catalogue.isEmpty()) return null
    var best: HanimeSeriesMatch? = null
    catalogue.groupBy(HanimeVideo::seriesSlug).forEach { (slug, episodes) ->
        var score = 0.0
        episodes.forEach { episode ->
            hanimeTitleCandidates(episode).forEach { candidate ->
                wanted.forEach { target ->
                    val similarity = titleSimilarity(target, candidate)
                    if (similarity > score) score = similarity
                }
            }
        }
        if (score >= threshold && score > (best?.score ?: 0.0)) {
            best = HanimeSeriesMatch(slug, episodes.sortedBy(HanimeVideo::episodeNumber), score)
        }
    }
    return best
}

/** Manifest streams, sharpest first — the player takes the head of this list as its default. */
fun sortHanimeStreams(streams: List<StreamItem>): List<StreamItem> =
    streams.sortedByDescending { it.height ?: 0 }

/**
 * Catalogue entries ride the app's normal [Media] rails so they appear in ordinary search results
 * rather than needing a screen of their own. AniList ids are always positive, so negating hanime's
 * own id yields a namespace that cannot collide with a real one and is trivially recognisable.
 */
fun hanimeMediaId(videoId: Int): Int = -videoId

fun isHanimeMediaId(id: Int): Boolean = id < 0

fun hanimeVideoId(mediaId: Int): Int = -mediaId

/** The genre every catalogue entry carries, so the existing genre filter reaches them. */
const val HANIME_GENRE = "Hentai"

/**
 * One series rendered as a [Media]. `isAdult` is set so the app's existing "Hide adult content"
 * switch governs these for free — with it on, they are filtered out exactly like AniList's own
 * adult entries, and no separate gate is needed.
 */
fun hanimeSeriesAsMedia(episodes: List<HanimeVideo>): Media? {
    val ordered = episodes.sortedBy(HanimeVideo::episodeNumber)
    val first = ordered.firstOrNull() ?: return null
    val title = hanimeTitleWithoutEpisode(first.name).ifBlank { first.name }
    return Media(
        id = hanimeMediaId(first.id),
        title = MediaTitle(romaji = title, english = title, userPreferred = title),
        coverImage = CoverImage(large = first.posterUrl, extraLarge = first.posterUrl),
        // Without this a catalogue-native entry opens on an empty detail page; AniList has no
        // record of these titles to fall back on.
        description = ordered.firstNotNullOfOrNull { it.description.takeIf(String::isNotBlank) },
        episodes = ordered.size,
        isAdult = true,
        seasonYear = hanimeReleaseYear(first.releasedAt),
        genres = listOf(HANIME_GENRE),
        tags = ordered.asSequence()
            .flatMap(HanimeVideo::tags)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .map { MediaTag(name = it, isAdult = true) }
            .toList(),
        studios = StudioConnection(
            nodes = listOfNotNull(first.brand.takeIf(String::isNotBlank)?.let { StudioNode(name = it) }),
        ),
    )
}

/** Year out of hanime's ISO release stamp (`2014-04-25T00:00:05.000Z`), or null if unparseable. */
fun hanimeReleaseYear(releasedAt: String): Int? =
    releasedAt.take(4).toIntOrNull()?.takeIf { it in 1900..2999 }

/**
 * Local search over the catalogue. hanime's own site works this way — the whole library is on the
 * device, so a query never leaves it. Prefix and substring hits rank above fuzzy ones so typing an
 * exact name puts it first.
 */
fun searchHanimeCatalogue(
    query: String,
    catalogue: List<HanimeVideo>,
    limit: Int = 30,
): List<Media> {
    val wanted = normalizeHanimeTitle(query)
    if (wanted.isBlank()) return emptyList()
    return catalogue.groupBy(HanimeVideo::seriesSlug).values
        .mapNotNull { episodes ->
            val best = episodes.maxOf { episode ->
                hanimeTitleCandidates(episode).maxOfOrNull { candidate ->
                    hanimeQueryScore(wanted, candidate)
                } ?: 0.0
            }
            if (best <= 0.0) null else episodes to best
        }
        .sortedWith(compareByDescending<Pair<List<HanimeVideo>, Double>> { it.second }
            .thenBy { hanimeTitleWithoutEpisode(it.first.first().name) })
        .take(limit)
        .mapNotNull { (episodes, _) -> hanimeSeriesAsMedia(episodes) }
}

/** Exact beats prefix beats substring beats fuzzy; anything weaker is not a result at all. */
private fun hanimeQueryScore(normalizedQuery: String, candidate: String): Double {
    val target = normalizeHanimeTitle(candidate)
    if (target.isBlank()) return 0.0
    return when {
        target == normalizedQuery -> 1.0
        target.startsWith(normalizedQuery) -> 0.95
        target.contains(normalizedQuery) -> 0.9
        else -> titleSimilarity(normalizedQuery, target).takeIf { it >= 0.7 } ?: 0.0
    }
}

private val TRAILING_SLUG_NUMBER = Regex("-(\\d{1,2})$")
private val TRAILING_TITLE_NUMBER = Regex("\\s+\\d{1,2}$")

private fun bigrams(value: String): List<String> =
    if (value.length < 2) listOf(value) else (0 until value.length - 1).map { value.substring(it, it + 2) }
