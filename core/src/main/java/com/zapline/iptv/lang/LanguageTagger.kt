package com.zapline.iptv.lang

import com.zapline.iptv.model.Channel
import java.text.Normalizer

/**
 * Works out a channel's language from whatever the playlist actually provides.
 *
 * Order of evidence (strongest first):
 *  1. tvg-language attribute
 *  2. a language word in the channel name ("Al Jazeera English", "BBC Arabic")
 *  3. the group title: prefix token ("UK | Sports"), language word, suffix token, country name
 *  4. channel-name prefix/suffix/bracket tokens ("UK: BBC One", "CNN (US)", "[UK]")
 *  5. tvg-country attribute
 *  6. tvg-id country suffix ("BBCOne.uk")
 */
object LanguageTagger {

    /** ISO 3166 country codes -> language (true ISO meaning, e.g. AR = Argentina). */
    private val isoCountry = HashMap<String, String>()
    /** IPTV shorthand tokens used as name/group prefixes (AR = Arabic here). */
    private val shortTokens = HashMap<String, String>()
    /** Short tokens that are safe to match as standalone words inside group titles. */
    private val groupWordTokens = HashMap<String, String>()
    /** Words naming a language. */
    private val languageWords = HashMap<String, String>()
    /** Single-word country / region names (group titles only). */
    private val countryWords = HashMap<String, String>()
    /** Multi-word country names, stored padded with spaces. */
    private val countryPhrases = ArrayList<Pair<String, String>>()
    /** tvg-language values: ISO 639-1/2 codes and English names. */
    private val languageAttr = HashMap<String, String>()

    private const val DELIMS = "|:-–—]/)»•│┃｜¦★●>_~"

    init {
        fun iso(lang: String, vararg c: String) = c.forEach { isoCountry[it] = lang }
        iso("en", "GB", "UK", "US", "CA", "AU", "NZ", "IE", "NG", "ZA", "GH", "KE", "JM", "TT")
        iso("fr", "FR"); iso("de", "DE", "AT"); iso("it", "IT"); iso("nl", "NL")
        iso("es", "ES", "MX", "AR", "CO", "CL", "PE", "VE", "EC", "UY", "PY", "BO", "CU", "DO", "GT", "HN", "SV", "NI", "CR", "PA", "PR")
        iso("pt", "PT", "BR"); iso("tr", "TR"); iso("pl", "PL"); iso("ru", "RU"); iso("ua", "UA")
        iso("hi", "IN"); iso("ur", "PK"); iso("bn", "BD"); iso("fa", "IR", "AF")
        iso("el", "GR", "CY"); iso("ro", "RO", "MD"); iso("bg", "BG"); iso("sr", "RS", "HR", "BA", "ME", "MK", "SI")
        iso("hu", "HU"); iso("cs", "CZ", "SK"); iso("sq", "AL", "XK")
        iso("sv", "SE"); iso("no", "NO"); iso("da", "DK"); iso("fi", "FI"); iso("he", "IL")
        iso("zh", "CN", "HK", "TW"); iso("ja", "JP"); iso("ko", "KR"); iso("vi", "VN"); iso("th", "TH")
        iso("tl", "PH"); iso("id", "ID"); iso("ms", "MY"); iso("so", "SO"); iso("am", "ET")
        iso("ar", "SA", "AE", "EG", "MA", "DZ", "TN", "LB", "IQ", "SY", "JO", "KW", "QA", "OM", "BH", "YE", "LY", "SD", "PS")

        shortTokens.putAll(isoCountry)
        fun st(lang: String, vararg t: String) = t.forEach { shortTokens[it] = lang }
        st("en", "EN", "ENG", "USA", "CAN", "AUS", "IRL", "GBR", "NZL")
        st("ar", "AR", "ARA", "ARAB", "MENA", "KSA", "UAE")
        st("fr", "FRA", "FRE"); st("de", "GER", "DEU", "AUT"); st("es", "ESP", "SPA", "MEX", "LAT", "LATAM", "LATIN")
        st("pt", "POR", "BRA", "PRT"); st("it", "ITA"); st("nl", "NED", "NLD", "HOL")
        st("tr", "TUR", "TRK"); st("pl", "POL"); st("ru", "RUS"); st("ua", "UKR")
        st("hi", "IND", "HI", "HIN"); st("ur", "PAK", "UR"); st("bn", "BAN", "BGD", "BD"); st("fa", "IRN", "FA", "PER")
        st("ku", "KU", "KUR", "KRD"); st("el", "GRE", "GRC", "EL"); st("ro", "ROM", "ROU"); st("bg", "BUL", "BGR")
        st("sr", "SRB", "CRO", "HRV", "BIH", "EXYU", "YU", "MKD", "SLO"); st("hu", "HUN")
        st("cs", "CZE", "SVK", "CZ", "CS"); st("sq", "ALB")
        st("sv", "SWE", "SV"); st("no", "NOR"); st("da", "DEN", "DNK", "DA"); st("fi", "FIN")
        st("he", "ISR", "HEB"); st("zh", "CHN", "ZH"); st("ja", "JPN"); st("ko", "KOR")
        st("vi", "VIE", "VNM"); st("th", "THA"); st("tl", "PHI", "PHL", "PINOY"); st("id", "IDN", "INDO")
        st("ms", "MYS", "MAL"); st("so", "SOM"); st("am", "ETH")

        // Short tokens that are unambiguous as a whole word inside a group title.
        // Excludes things that collide with ordinary words (DE, IT, NO, IN, ES, AL, POR, DEN, FIN...).
        for (t in listOf(
            "UK", "US", "USA", "GB", "IE", "AU", "NZ", "ENG", "IRL", "AUS", "CAN",
            "FR", "FRA", "GER", "NL", "NED", "TR", "TUR", "PL", "POL", "RU", "RUS", "UKR",
            "PT", "BR", "BRA", "ITA", "ESP", "MX", "MEX", "LATAM", "LATIN",
            "GR", "GRE", "RO", "BG", "BUL", "HU", "HUN", "CZ", "SK", "SE", "SWE", "DK", "FI",
            "ALB", "SRB", "CRO", "EXYU", "ARAB", "KSA", "UAE", "MENA", "KUR", "IND", "PAK",
            "IRN", "ISR", "CHN", "JPN", "KOR", "PHI", "PINOY",
        )) shortTokens[t]?.let { groupWordTokens[t] = it }

        fun lw(lang: String, vararg w: String) = w.forEach { languageWords[it] = lang }
        lw("en", "ENGLISH"); lw("ar", "ARABIC", "ARABE", "ARABI")
        lw("fr", "FRENCH", "FRANCAIS", "FRANCAISE"); lw("de", "GERMAN", "DEUTSCH")
        lw("es", "SPANISH", "ESPANOL", "CASTELLANO", "LATINO", "LATINA"); lw("it", "ITALIAN", "ITALIANO")
        lw("pt", "PORTUGUESE", "PORTUGUES"); lw("nl", "DUTCH", "NEDERLANDS"); lw("tr", "TURKISH", "TURKCE")
        lw("pl", "POLISH", "POLSKI"); lw("ru", "RUSSIAN"); lw("ua", "UKRAINIAN")
        lw("hi", "HINDI", "MARATHI", "GUJARATI"); lw("ta", "TAMIL", "TELUGU", "MALAYALAM", "KANNADA")
        lw("pa", "PUNJABI"); lw("ur", "URDU"); lw("bn", "BENGALI", "BANGLA")
        lw("fa", "PERSIAN", "FARSI"); lw("ku", "KURDISH", "KURDI")
        lw("el", "GREEK"); lw("ro", "ROMANIAN"); lw("bg", "BULGARIAN")
        lw("sr", "SERBIAN", "CROATIAN", "BOSNIAN", "MACEDONIAN", "BALKAN"); lw("hu", "HUNGARIAN")
        lw("cs", "CZECH", "SLOVAK"); lw("sq", "ALBANIAN", "SHQIP")
        lw("sv", "SWEDISH", "SVENSKA"); lw("no", "NORWEGIAN", "NORSK"); lw("da", "DANISH", "DANSK"); lw("fi", "FINNISH")
        lw("he", "HEBREW"); lw("zh", "CHINESE", "MANDARIN", "CANTONESE"); lw("ja", "JAPANESE"); lw("ko", "KOREAN")
        lw("vi", "VIETNAMESE"); lw("th", "THAI"); lw("tl", "FILIPINO", "TAGALOG"); lw("id", "INDONESIAN")
        lw("ms", "MALAY"); lw("so", "SOMALI"); lw("am", "AMHARIC", "ETHIOPIAN")

        fun cw(lang: String, vararg w: String) = w.forEach {
            if (it.contains(' ')) countryPhrases += " $it " to lang else countryWords[it] = lang
        }
        cw("en", "UNITED KINGDOM", "GREAT BRITAIN", "BRITAIN", "BRITISH", "ENGLAND", "SCOTLAND", "WALES",
            "IRELAND", "IRISH", "UNITED STATES", "AMERICA", "AMERICAN", "CANADA", "CANADIAN", "AUSTRALIA",
            "AUSTRALIAN", "NEW ZEALAND", "NIGERIA", "SOUTH AFRICA", "GHANA", "KENYA", "JAMAICA", "CARIBBEAN")
        cw("fr", "FRANCE"); cw("de", "GERMANY", "DEUTSCHLAND", "AUSTRIA", "OSTERREICH")
        cw("es", "LATIN AMERICA", "SOUTH AMERICA", "CENTRAL AMERICA", "SPAIN", "ESPANA", "MEXICO", "ARGENTINA", "COLOMBIA", "CHILE", "PERU", "VENEZUELA", "ECUADOR")
        cw("pt", "PORTUGAL", "BRAZIL", "BRASIL"); cw("it", "ITALY", "ITALIA"); cw("nl", "NETHERLANDS", "HOLLAND")
        cw("tr", "TURKEY", "TURKIYE"); cw("pl", "POLAND", "POLSKA"); cw("ru", "RUSSIA"); cw("ua", "UKRAINE")
        cw("hi", "INDIA", "INDIAN"); cw("ur", "PAKISTAN"); cw("bn", "BANGLADESH"); cw("fa", "IRAN", "AFGHANISTAN")
        cw("ku", "KURDISTAN"); cw("el", "GREECE", "CYPRUS"); cw("ro", "ROMANIA", "MOLDOVA"); cw("bg", "BULGARIA")
        cw("sr", "SERBIA", "CROATIA", "BOSNIA", "MONTENEGRO", "MACEDONIA", "SLOVENIA", "EX YU", "YUGOSLAVIA")
        cw("hu", "HUNGARY"); cw("cs", "CZECHIA", "SLOVAKIA", "CZECH REPUBLIC"); cw("sq", "ALBANIA", "KOSOVO")
        cw("sv", "SWEDEN"); cw("no", "NORWAY"); cw("da", "DENMARK"); cw("fi", "FINLAND"); cw("he", "ISRAEL")
        cw("zh", "CHINA", "HONG KONG", "TAIWAN"); cw("ja", "JAPAN"); cw("ko", "KOREA"); cw("vi", "VIETNAM")
        cw("th", "THAILAND"); cw("tl", "PHILIPPINES"); cw("id", "INDONESIA"); cw("ms", "MALAYSIA")
        cw("so", "SOMALIA"); cw("am", "ETHIOPIA", "ERITREA")
        cw("ar", "ARAB", "ARABIA", "SAUDI", "EGYPT", "MOROCCO", "ALGERIA", "TUNISIA", "LEBANON", "IRAQ", "SYRIA",
            "JORDAN", "KUWAIT", "QATAR", "EMIRATES", "OMAN", "BAHRAIN", "YEMEN", "LIBYA", "SUDAN", "PALESTINE", "MAGHREB")

        fun la(lang: String, vararg w: String) = w.forEach { languageAttr[it] = lang }
        la("en", "en", "eng", "english"); la("ar", "ar", "ara", "arabic"); la("fr", "fr", "fra", "fre", "french")
        la("de", "de", "deu", "ger", "german"); la("es", "es", "spa", "spanish"); la("it", "it", "ita", "italian")
        la("pt", "pt", "por", "portuguese"); la("nl", "nl", "nld", "dut", "dutch"); la("tr", "tr", "tur", "turkish")
        la("pl", "pl", "pol", "polish"); la("ru", "ru", "rus", "russian"); la("ua", "ukr", "ukrainian")
        la("hi", "hi", "hin", "hindi", "mr", "mar", "marathi", "gu", "guj", "gujarati")
        la("ta", "ta", "tam", "tamil", "te", "tel", "telugu", "ml", "mal", "malayalam", "kn", "kan", "kannada")
        la("pa", "pa", "pan", "punjabi"); la("ur", "ur", "urd", "urdu"); la("bn", "bn", "ben", "bengali", "bangla")
        la("fa", "fa", "fas", "per", "persian", "farsi"); la("ku", "ku", "kur", "kurdish")
        la("el", "el", "ell", "gre", "greek"); la("ro", "ro", "ron", "rum", "romanian"); la("bg", "bg", "bul", "bulgarian")
        la("sr", "sr", "srp", "serbian", "hr", "hrv", "croatian", "bs", "bos", "bosnian", "mk", "mkd", "macedonian")
        la("hu", "hu", "hun", "hungarian"); la("cs", "cs", "ces", "cze", "czech", "sk", "slk", "slo", "slovak")
        la("sq", "sq", "sqi", "alb", "albanian"); la("sv", "sv", "swe", "swedish")
        la("no", "no", "nor", "nb", "nob", "nn", "norwegian"); la("da", "da", "dan", "danish"); la("fi", "fi", "fin", "finnish")
        la("he", "he", "heb", "hebrew"); la("zh", "zh", "zho", "chi", "chinese", "mandarin", "cantonese", "yue")
        la("ja", "ja", "jpn", "japanese"); la("ko", "ko", "kor", "korean"); la("vi", "vi", "vie", "vietnamese")
        la("th", "th", "tha", "thai"); la("tl", "tl", "fil", "tgl", "filipino", "tagalog")
        la("id", "id", "ind", "indonesian"); la("ms", "ms", "msa", "may", "malay"); la("so", "so", "som", "somali")
        la("am", "am", "amh", "amharic")
        // Note: "uk" is deliberately absent - it is ISO Ukrainian but people use it to mean United Kingdom.
    }

    /** Tags every channel, caching the group-level result (groups repeat thousands of times). */
    fun detectAll(list: List<Channel>): List<Channel> {
        val groupCache = HashMap<String, String>()
        return list.map { c ->
            val g = groupCache.getOrPut(c.group) { fromGroup(c.group) }
            c.copy(detectedLang = detect(c, g))
        }
    }

    fun detect(c: Channel, groupLang: String = fromGroup(c.group)): String {
        fromLanguageAttr(c.tvgLanguage)?.let { return it }
        scanWords(words(c.name), languageWords, null)?.let { return it }
        if (groupLang.isNotEmpty()) return groupLang
        fromName(c.name)?.let { return it }
        fromCountryAttr(c.tvgCountry)?.let { return it }
        fromTvgId(c.tvgId)?.let { return it }
        if (RegionTagger.isUkBrand(c.name)) return "en"
        return ""
    }

    fun fromGroup(group: String): String {
        prefixToken(group)?.let { t -> shortTokens[t]?.let { return it } }
        val w = words(group)
        scanWords(w, languageWords, null)?.let { return it }
        suffixToken(group)?.let { t -> shortTokens[t]?.let { return it } }
        bracketTokens(group).firstNotNullOfOrNull { shortTokens[it] }?.let { return it }
        val padded = " " + w.joinToString(" ") + " "
        countryPhrases.firstOrNull { padded.contains(it.first) }?.let { return it.second }
        scanWords(w, countryWords, groupWordTokens)?.let { return it }
        return ""
    }

    fun fromName(name: String): String? {
        prefixToken(name)?.let { t -> shortTokens[t]?.let { return it } }
        suffixToken(name)?.let { t -> shortTokens[t]?.let { return it } }
        return bracketTokens(name).firstNotNullOfOrNull { shortTokens[it] }
    }

    fun fromLanguageAttr(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return value.split(';', ',', '/', '|')
            .map { stripAccents(it).trim().lowercase() }
            .firstNotNullOfOrNull { languageAttr[it] }
    }

    fun fromCountryAttr(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return value.split(';', ',', '/', '|').firstNotNullOfOrNull { isoCountry[it.trim().uppercase()] }
    }

    fun fromTvgId(id: String?): String? {
        if (id.isNullOrBlank()) return null
        val core = id.substringBefore('@').trim()
        val dot = core.lastIndexOf('.')
        if (dot < 0 || dot == core.length - 1) return null
        val suffix = core.substring(dot + 1).uppercase()
        return if (suffix.length == 2) isoCountry[suffix] else null
    }

    // ---- tokenising helpers ----

    private fun stripAccents(s: String): String = normalize(s)

    /**
     * Folds the decorative text providers use into plain letters: styled Unicode (𝗨𝗞, ＵＫ) via NFKC,
     * flag emoji (🇬🇧) into a bracketed code "[GB]", and accents stripped.
     */
    fun normalize(s: String): String {
        val withFlags = flagsToText(s)
        val k = Normalizer.normalize(withFlags, Normalizer.Form.NFKC)
        return Normalizer.normalize(k, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    }

    private fun flagsToText(s: String): String {
        if (s.none { it.isSurrogate() }) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            if (cp in 0x1F1E6..0x1F1FF && i + n < s.length) {
                val cp2 = s.codePointAt(i + n)
                if (cp2 in 0x1F1E6..0x1F1FF) {
                    sb.append('[').append('A' + (cp - 0x1F1E6)).append('A' + (cp2 - 0x1F1E6)).append(']')
                    i += n + Character.charCount(cp2)
                    continue
                }
            }
            sb.appendCodePoint(cp)
            i += n
        }
        return sb.toString()
    }

    internal fun words(s: String): List<String> =
        stripAccents(s).uppercase().split(Regex("[^A-Z]+")).filter { it.isNotEmpty() }

    private fun scanWords(words: List<String>, primary: Map<String, String>, secondary: Map<String, String>?): String? {
        for (w in words) {
            primary[w]?.let { return it }
            secondary?.get(w)?.let { return it }
        }
        return null
    }

    /** "UK | Sports", "[UK] BBC", "|UK| News", "UK: BBC One", or a bare "UK". */
    internal fun prefixToken(s: String): String? {
        val t = stripAccents(s).uppercase()
        var i = 0
        while (i < t.length && t[i] !in 'A'..'Z') i++
        val start = i
        while (i < t.length && t[i] in 'A'..'Z') i++
        if (i - start !in 2..5) return null
        var j = i
        while (j < t.length && t[j] == ' ') j++
        if (j == t.length) return t.substring(start, i)
        return if (DELIMS.indexOf(t[j]) >= 0) t.substring(start, i) else null
    }

    /** "CNN (US)", "Sports | UK", "ESPN - US". */
    internal fun suffixToken(s: String): String? {
        val t = stripAccents(s).uppercase().trimEnd()
        var i = t.length
        while (i > 0 && (t[i - 1] == ']' || t[i - 1] == ')' || t[i - 1] == ' ')) i--
        val end = i
        while (i > 0 && t[i - 1] in 'A'..'Z') i--
        if (end - i !in 2..5) return null
        var j = i - 1
        while (j >= 0 && t[j] == ' ') j--
        if (j < 0) return null
        val c = t[j]
        return if (c == '[' || c == '(' || DELIMS.indexOf(c) >= 0) t.substring(i, end) else null
    }

    private val bracketRe = Regex("[\\[(]\\s*([A-Z]{2,4})\\s*[\\])]")

    /** Any "[XX]" or "(XX)" token, e.g. "BBC One [UK] HD". */
    internal fun bracketTokens(s: String): List<String> =
        bracketRe.findAll(stripAccents(s).uppercase()).map { it.groupValues[1] }.toList()
}
