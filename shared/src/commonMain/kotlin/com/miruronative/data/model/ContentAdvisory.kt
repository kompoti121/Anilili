package com.miruronative.data.model

data class ContentAdvisory(
    val isAdult: Boolean,
    val labels: List<String>,
)

/** AniList has tags rather than formal content ratings, so only explicit, prominent tags are used. */
fun Media.contentAdvisory(): ContentAdvisory {
    val prominentTags = tags
        .asSequence()
        .filterNot { it.isMediaSpoiler || it.isGeneralSpoiler }
        .filter { (it.rank ?: 100) >= MIN_ADVISORY_RANK }
        .map { it.name.lowercase() }
        .toSet()
    val normalizedGenres = genres.map { it.lowercase() }.toSet()

    val labels = buildList {
        if (prominentTags.hasAny(VIOLENCE_TAGS)) add("Violence")
        if (isAdult || normalizedGenres.contains("ecchi") || prominentTags.hasAny(SEXUAL_TAGS)) {
            add("Sexual content")
        }
        if (prominentTags.hasAny(LANGUAGE_TAGS)) add("Strong language")
        if (
            isAdult ||
            normalizedGenres.any { it == "horror" || it == "psychological" } ||
            prominentTags.hasAny(MATURE_TAGS)
        ) {
            add("Mature themes")
        }
    }
    return ContentAdvisory(isAdult = isAdult, labels = labels.distinct())
}

private fun Set<String>.hasAny(keywords: Set<String>): Boolean =
    any { value -> keywords.any { keyword -> value == keyword || value.contains(keyword) } }

private const val MIN_ADVISORY_RANK = 35

private val VIOLENCE_TAGS = setOf(
    "assassins",
    "body horror",
    "death game",
    "gore",
    "torture",
    "violence",
    "war",
)

private val SEXUAL_TAGS = setOf(
    "ecchi",
    "nudity",
    "prostitution",
    "psychosexual",
    "sex work",
    "sexual content",
)

private val LANGUAGE_TAGS = setOf(
    "profanity",
    "strong language",
)

private val MATURE_TAGS = setOf(
    "alcohol",
    "body horror",
    "bullying",
    "cannibalism",
    "crime",
    "drug use",
    "gambling",
    "human trafficking",
    "psychosexual",
    "slavery",
    "suicide",
    "torture",
    "tragedy",
)
