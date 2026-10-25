package com.zapline.iptv.lang

/** Internal language keys and their display names. "" means unknown. */
object Languages {
    val labels: LinkedHashMap<String, String> = linkedMapOf(
        "en" to "English",
        "ar" to "Arabic",
        "fr" to "French",
        "de" to "German",
        "es" to "Spanish",
        "it" to "Italian",
        "pt" to "Portuguese",
        "nl" to "Dutch",
        "tr" to "Turkish",
        "pl" to "Polish",
        "ru" to "Russian",
        "ua" to "Ukrainian",
        "hi" to "Hindi / Indian",
        "ta" to "Tamil / South Indian",
        "pa" to "Punjabi",
        "ur" to "Urdu",
        "bn" to "Bengali",
        "fa" to "Persian",
        "ku" to "Kurdish",
        "el" to "Greek",
        "ro" to "Romanian",
        "bg" to "Bulgarian",
        "sr" to "Balkan / Ex-YU",
        "hu" to "Hungarian",
        "cs" to "Czech / Slovak",
        "sq" to "Albanian",
        "sv" to "Swedish",
        "no" to "Norwegian",
        "da" to "Danish",
        "fi" to "Finnish",
        "he" to "Hebrew",
        "zh" to "Chinese",
        "ja" to "Japanese",
        "ko" to "Korean",
        "vi" to "Vietnamese",
        "th" to "Thai",
        "tl" to "Filipino",
        "id" to "Indonesian",
        "ms" to "Malay",
        "so" to "Somali",
        "am" to "Amharic",
    )

    fun label(code: String): String = if (code.isEmpty()) "Unknown" else labels[code] ?: code.uppercase()

    /** Resolves a user-typed language ("en", "English", "eng") to an internal key. */
    fun resolve(word: String): String? {
        val w = word.trim().lowercase()
        if (w.isEmpty()) return null
        if (w == "unknown" || w == "?") return ""
        if (labels.containsKey(w)) return w
        labels.entries.firstOrNull { (_, label) ->
            label.lowercase() == w || label.lowercase().split(" / ").any { it == w }
        }?.let { return it.key }
        return LanguageTagger.fromLanguageAttr(w)
    }
}
