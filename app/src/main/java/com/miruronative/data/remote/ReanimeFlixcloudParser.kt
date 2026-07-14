package com.miruronative.data.remote

import com.miruronative.data.model.SkipTimes
import com.miruronative.data.model.SubtitleItem

internal object ReanimeFlixcloudParser {
    fun subtitles(html: String, audio: String): List<SubtitleItem> {
        val array = Regex(
            """subtitles\s*:\s*\[([\s\S]*?)]""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1) ?: return emptyList()

        return Regex("""\{([^{}]*)\}""").findAll(array)
            .mapNotNull { match ->
                val block = match.groupValues[1]
                val url = jsField(block, "url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val label = jsField(block, "language").ifBlank { "Subtitle" }
                FlixSubtitle(
                    item = SubtitleItem(
                        url = NativeProviderParsers.decodeEntities(url),
                        label = NativeProviderParsers.decodeEntities(label),
                        language = language(label, url),
                    ),
                    isDefault = jsBoolean(block, "default"),
                )
            }
            .distinctBy { it.item.url }
            .sortedWith(compareBy<FlixSubtitle> { subtitleRank(it, audio) }.thenBy { it.item.label })
            .map { it.item }
            .toList()
    }

    fun skip(html: String): SkipTimes? {
        val intro = chapter(html, "intro_chapter") ?: chapterByTitle(html, "Intro")
        val outro = chapter(html, "outro_chapter")
            ?: chapterByTitle(html, "Outro")
            ?: chapterByTitle(html, "Credits")
        if (intro == null && outro == null) return null
        return SkipTimes(intro?.first, intro?.second, outro?.first, outro?.second)
    }

    fun defaultAudioTrack(html: String): Int? =
        Regex("""default_audio_track\s*:\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    private fun chapter(html: String, field: String): Pair<Double, Double>? =
        Regex(
            """${Regex.escape(field)}\s*:\s*\{\s*start\s*:\s*([0-9.]+)\s*,\s*end\s*:\s*([0-9.]+)""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.let { match ->
            val start = match.groupValues[1].toDoubleOrNull() ?: return@let null
            val end = match.groupValues[2].toDoubleOrNull() ?: return@let null
            start to end
        }

    private fun chapterByTitle(html: String, title: String): Pair<Double, Double>? =
        Regex(
            "\\{\\s*start\\s*:\\s*([0-9.]+)\\s*,\\s*end\\s*:\\s*([0-9.]+)\\s*,\\s*title\\s*:\\s*\"([^\"]+)\"",
            RegexOption.IGNORE_CASE,
        ).findAll(html).firstOrNull { it.groupValues[3].equals(title, ignoreCase = true) }?.let { match ->
            val start = match.groupValues[1].toDoubleOrNull() ?: return@let null
            val end = match.groupValues[2].toDoubleOrNull() ?: return@let null
            start to end
        }

    private fun jsField(block: String, name: String): String =
        Regex("${Regex.escape(name)}\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(block)
            ?.groupValues
            ?.get(1)
            ?.let(::decodeJsString)
            .orEmpty()

    private fun jsBoolean(block: String, name: String): Boolean =
        Regex("""${Regex.escape(name)}\s*:\s*true\b""", RegexOption.IGNORE_CASE).containsMatchIn(block)

    private fun decodeJsString(value: String): String {
        val unicode = Regex("""\\u([0-9a-fA-F]{4})""").replace(value) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        return unicode
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
    }

    private fun language(label: String, url: String): String {
        Regex("""_([a-z]{3})_\d+\.(?:ass|vtt|srt)""", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.get(1)
            ?.lowercase()
            ?.let { code -> threeLetterLanguage[code]?.let { return it } }
        val normalized = label.substringBefore('(').trim().lowercase()
        return languageNames[normalized] ?: "und"
    }

    private fun subtitleRank(subtitle: FlixSubtitle, audio: String): Int {
        val label = subtitle.item.label.lowercase()
        val englishPenalty = if (subtitle.item.language == "en" || label.startsWith("english")) 0 else 100
        val defaultBonus = if (subtitle.isDefault) -5 else 0
        val isForced = label.contains("forced") || label.contains("signs") ||
            label.contains("songs") || label.contains("episode name")
        val isFull = label.contains("full") || label.contains("dialogue") ||
            label.contains("cr") || label.contains("track")
        val kind = if (audio == "dub") {
            when {
                isForced -> -10
                isFull -> 10
                else -> 0
            }
        } else {
            when {
                isFull -> -10
                isForced -> 20
                else -> 0
            }
        }
        return englishPenalty + defaultBonus + kind
    }

    private data class FlixSubtitle(val item: SubtitleItem, val isDefault: Boolean)

    private val threeLetterLanguage = mapOf(
        "ara" to "ar",
        "chi" to "zh",
        "zho" to "zh",
        "eng" to "en",
        "fre" to "fr",
        "fra" to "fr",
        "ger" to "de",
        "deu" to "de",
        "ind" to "id",
        "ita" to "it",
        "may" to "ms",
        "msa" to "ms",
        "por" to "pt",
        "rus" to "ru",
        "spa" to "es",
        "tha" to "th",
        "vie" to "vi",
        "jpn" to "ja",
    )

    private val languageNames = mapOf(
        "arabic" to "ar",
        "chinese" to "zh",
        "english" to "en",
        "french" to "fr",
        "german" to "de",
        "indonesian" to "id",
        "italian" to "it",
        "malay" to "ms",
        "portuguese" to "pt",
        "russian" to "ru",
        "spanish" to "es",
        "thai" to "th",
        "vietnamese" to "vi",
        "japanese" to "ja",
    )
}
