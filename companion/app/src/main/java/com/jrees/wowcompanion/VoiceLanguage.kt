package com.jrees.wowcompanion

import java.util.Locale

/**
 * VOICE LANGUAGE (issue #477: "let me pick the speech language (Russian), independent of the app language"): what offline voice
 * (whisper.cpp) listens for, apart from Settings > Language (menus only: en / es / fr). [SAME] = the app's language (the default, as
 * before), [AUTO] = Whisper detects it per clip, else one of Whisper's own languages ([WHISPER]). App-wide, like Voice typing.
 *
 * Anything but English needs a multilingual model ([VoiceModels.Model.multi]): an English-only ".en" model cannot write Russian, so
 * a pick that needs one and has only English on disk offers the "Other languages" download with its size (the voice sheet's list,
 * [Voice.wantedModel]); nothing downloads by itself. [SAME] keeps its old fallback (the English model in English).
 * Pure (no Android): the tests run it off the device.
 */
object VoiceLanguage {
    /** its key in the "settings" prefs (Settings.voiceLanguage; ResetPlan's settings bucket) */
    const val PREF = "voiceLanguage"
    /** the pref's value for "Same as the app" (the default) */
    const val SAME = "app"
    /** Whisper detects the language per clip (whisper_full's own "auto": detect, then transcribe in it) */
    const val AUTO = "auto"

    /** the short list first (issue #477's ask, then the busiest WoW realm languages) */
    val SHORT = listOf("en", "es", "fr", "de", "ru", "pt", "it", "pl", "uk", "nl", "tr", "ko", "zh", "ja")

    /** whisper.cpp v1.9.5's languages (src/whisper.cpp g_lang), code to its English name */
    val WHISPER: Map<String, String> = linkedMapOf(
        "en" to "English", "zh" to "Chinese", "de" to "German", "es" to "Spanish", "ru" to "Russian", "ko" to "Korean",
        "fr" to "French", "ja" to "Japanese", "pt" to "Portuguese", "tr" to "Turkish", "pl" to "Polish", "ca" to "Catalan",
        "nl" to "Dutch", "ar" to "Arabic", "sv" to "Swedish", "it" to "Italian", "id" to "Indonesian", "hi" to "Hindi",
        "fi" to "Finnish", "vi" to "Vietnamese", "he" to "Hebrew", "uk" to "Ukrainian", "el" to "Greek", "ms" to "Malay",
        "cs" to "Czech", "ro" to "Romanian", "da" to "Danish", "hu" to "Hungarian", "ta" to "Tamil", "no" to "Norwegian",
        "th" to "Thai", "ur" to "Urdu", "hr" to "Croatian", "bg" to "Bulgarian", "lt" to "Lithuanian", "la" to "Latin",
        "mi" to "Maori", "ml" to "Malayalam", "cy" to "Welsh", "sk" to "Slovak", "te" to "Telugu", "fa" to "Persian",
        "lv" to "Latvian", "bn" to "Bengali", "sr" to "Serbian", "az" to "Azerbaijani", "sl" to "Slovenian", "kn" to "Kannada",
        "et" to "Estonian", "mk" to "Macedonian", "br" to "Breton", "eu" to "Basque", "is" to "Icelandic", "hy" to "Armenian",
        "ne" to "Nepali", "mn" to "Mongolian", "bs" to "Bosnian", "kk" to "Kazakh", "sq" to "Albanian", "sw" to "Swahili",
        "gl" to "Galician", "mr" to "Marathi", "pa" to "Punjabi", "si" to "Sinhala", "km" to "Khmer", "sn" to "Shona",
        "yo" to "Yoruba", "so" to "Somali", "af" to "Afrikaans", "oc" to "Occitan", "ka" to "Georgian", "be" to "Belarusian",
        "tg" to "Tajik", "sd" to "Sindhi", "gu" to "Gujarati", "am" to "Amharic", "yi" to "Yiddish", "lo" to "Lao",
        "uz" to "Uzbek", "fo" to "Faroese", "ht" to "Haitian Creole", "ps" to "Pashto", "tk" to "Turkmen", "nn" to "Nynorsk",
        "mt" to "Maltese", "sa" to "Sanskrit", "lb" to "Luxembourgish", "my" to "Myanmar", "bo" to "Tibetan", "tl" to "Tagalog",
        "mg" to "Malagasy", "as" to "Assamese", "tt" to "Tatar", "haw" to "Hawaiian", "ln" to "Lingala", "ha" to "Hausa",
        "ba" to "Bashkir", "jw" to "Javanese", "su" to "Sundanese", "yue" to "Cantonese")

    /** the picker's order: Same as the app, Auto-detect, the short list, then every other language by its English name */
    val ORDER: List<String> = listOf(SAME, AUTO) + SHORT +
        WHISPER.keys.filter { it !in SHORT }.sortedBy { WHISPER[it] }

    /** a saved value as the picker knows it (anything else, or none = [SAME]) */
    fun sanitize(v: String?): String = if (v != null && v in ORDER) v else SAME

    /** the app's language as a Whisper code ("es-ES" / "FR" -> "es" / "fr"; none = "en") */
    fun appCode(appLang: String): String = appLang.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_').ifEmpty { "en" }

    /** the language Whisper is given for the pick [pick]: the app's for [SAME], "auto", else the pick's code */
    fun resolve(pick: String, appLang: String): String = when (val p = sanitize(pick)) {
        SAME -> appCode(appLang)
        else -> p
    }

    /** [pick] (resolved) needs a multilingual model: anything but English (Auto-detect too) */
    fun needsMulti(pick: String, appLang: String): Boolean = resolve(pick, appLang) != "en"

    /** the language Android's own recognizer is asked for (its locale tag): the app's for [SAME] and [AUTO], else the pick's */
    fun androidTag(pick: String, appLang: String): String = when (val p = sanitize(pick)) {
        SAME, AUTO -> Voice.language(appCode(appLang))
        "en", "es", "fr" -> Voice.language(p)
        "jw" -> "jv"
        else -> p
    }

    /** the pick [by] steps on in [ORDER] (round at the ends) */
    fun step(pick: String, by: Int): String {
        val i = ORDER.indexOf(sanitize(pick)).coerceAtLeast(0)
        val n = ORDER.size
        return ORDER[((i + by) % n + n) % n]
    }

    /**
     * The picker's words for [pick] in the app's language [appLang]: "Same as the app" / "Auto-detect" (Tr's own words), else the
     * language's name as the device's locale data writes it in the app's language ("Russian", "ruso", "russe"), Whisper's English name
     * when the locale data has none
     */
    fun label(pick: String, appLang: String): String = when (val p = sanitize(pick)) {
        SAME -> "Same as the app"
        AUTO -> "Auto-detect"
        else -> name(p, appCode(appLang))
    }

    /** [code]'s name in [inLang] (a Whisper code: "jw" is ISO "jv") */
    fun name(code: String, inLang: String): String {
        val iso = if (code == "jw") "jv" else code
        val inLoc = Locale.forLanguageTag(inLang)
        val shown = runCatching { Locale.forLanguageTag(iso).getDisplayLanguage(inLoc) }.getOrNull()
        val n = if (shown.isNullOrBlank() || shown.equals(iso, ignoreCase = true)) WHISPER[code] ?: code else shown
        return n.replaceFirstChar { if (it.isLowerCase()) it.titlecase(inLoc) else it.toString() }
    }
}
