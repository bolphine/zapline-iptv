package com.zapline.iptv.lang

import com.zapline.iptv.model.Channel

/**
 * Works out which country a channel belongs to (separately from its language: UK vs US are both English).
 * Codes are ISO 3166 alpha-2, except the United Kingdom, which is "UK" (not "GB") because that's how
 * people and playlists refer to it.
 *
 * Order of evidence: tvg-country > group tokens/names > channel-name tokens > tvg-id suffix > UK broadcaster names.
 */
object RegionTagger {

    val labels: Map<String, String> = linkedMapOf(
        "UK" to "United Kingdom", "IE" to "Ireland", "US" to "United States", "CA" to "Canada",
        "AU" to "Australia", "NZ" to "New Zealand", "ZA" to "South Africa", "NG" to "Nigeria", "GH" to "Ghana",
        "KE" to "Kenya", "JM" to "Jamaica", "FR" to "France", "DE" to "Germany", "AT" to "Austria",
        "CH" to "Switzerland", "BE" to "Belgium", "NL" to "Netherlands", "ES" to "Spain", "PT" to "Portugal",
        "IT" to "Italy", "GR" to "Greece", "CY" to "Cyprus", "TR" to "Turkey", "PL" to "Poland", "RU" to "Russia",
        "UA" to "Ukraine", "RO" to "Romania", "BG" to "Bulgaria", "RS" to "Serbia", "HR" to "Croatia",
        "BA" to "Bosnia", "ME" to "Montenegro", "MK" to "North Macedonia", "SI" to "Slovenia", "AL" to "Albania",
        "XK" to "Kosovo", "HU" to "Hungary", "CZ" to "Czechia", "SK" to "Slovakia", "SE" to "Sweden",
        "NO" to "Norway", "DK" to "Denmark", "FI" to "Finland", "IS" to "Iceland", "IL" to "Israel",
        "MX" to "Mexico", "AR" to "Argentina", "BR" to "Brazil", "CO" to "Colombia", "CL" to "Chile",
        "PE" to "Peru", "VE" to "Venezuela", "EC" to "Ecuador", "IN" to "India", "PK" to "Pakistan",
        "BD" to "Bangladesh", "IR" to "Iran", "AF" to "Afghanistan", "CN" to "China", "HK" to "Hong Kong",
        "TW" to "Taiwan", "JP" to "Japan", "KR" to "South Korea", "VN" to "Vietnam", "TH" to "Thailand",
        "PH" to "Philippines", "ID" to "Indonesia", "MY" to "Malaysia", "SG" to "Singapore",
        "SA" to "Saudi Arabia", "AE" to "UAE", "EG" to "Egypt", "MA" to "Morocco", "DZ" to "Algeria",
        "TN" to "Tunisia", "LB" to "Lebanon", "IQ" to "Iraq", "SY" to "Syria", "JO" to "Jordan", "KW" to "Kuwait",
        "QA" to "Qatar", "OM" to "Oman", "BH" to "Bahrain", "YE" to "Yemen", "LY" to "Libya", "SD" to "Sudan",
        "PS" to "Palestine", "SO" to "Somalia", "ET" to "Ethiopia",
    )

    fun label(code: String): String = if (code.isEmpty()) "Unknown" else labels[code] ?: code

    /** Real ISO codes (tvg-country, tvg-id). */
    private val iso = HashMap<String, String>()
    /** IPTV shorthand used as name/group prefixes (here AR means Arabic, so it is not a country). */
    private val short = HashMap<String, String>()
    /** Tokens safe to match as a standalone word inside a group title. */
    private val groupWords = HashMap<String, String>()
    private val countryWords = HashMap<String, String>()
    /** Multi-word names; "" means "this phrase names no single country" and stops the scan. */
    private val phrases = ArrayList<Pair<String, String>>()

    init {
        for (code in labels.keys) iso[code] = code
        iso["GB"] = "UK"
        iso["XK"] = "XK"

        short.putAll(iso)
        short.remove("AR")   // "AR |" in IPTV means Arabic
        short.remove("SV")   // Swedish shorthand, not El Salvador
        fun s(code: String, vararg t: String) = t.forEach { short[it] = code }
        s("UK", "GBR", "BRIT"); s("US", "USA"); s("CA", "CAN"); s("AU", "AUS"); s("IE", "IRL"); s("NZ", "NZL")
        s("FR", "FRA"); s("DE", "GER", "DEU"); s("AT", "AUT"); s("ES", "ESP"); s("MX", "MEX"); s("PT", "POR", "PRT")
        s("BR", "BRA"); s("IT", "ITA"); s("NL", "NED", "NLD", "HOL"); s("TR", "TUR", "TRK"); s("PL", "POL")
        s("RU", "RUS"); s("UA", "UKR"); s("IN", "IND"); s("PK", "PAK"); s("BD", "BGD"); s("IR", "IRN")
        s("GR", "GRE", "GRC"); s("RO", "ROM", "ROU"); s("BG", "BUL", "BGR"); s("RS", "SRB"); s("HR", "CRO", "HRV")
        s("BA", "BIH"); s("HU", "HUN"); s("CZ", "CZE"); s("SK", "SVK"); s("SE", "SWE"); s("NO", "NOR")
        s("DK", "DEN", "DNK"); s("FI", "FIN"); s("AL", "ALB"); s("IL", "ISR"); s("CN", "CHN"); s("JP", "JPN")
        s("KR", "KOR"); s("VN", "VNM"); s("TH", "THA"); s("PH", "PHI", "PHL"); s("ID", "IDN"); s("MY", "MYS")
        s("SA", "KSA"); s("AE", "UAE"); s("SO", "SOM"); s("ET", "ETH")

        for (t in listOf(
            "UK", "US", "USA", "GB", "GBR", "IE", "AU", "NZ", "IRL", "AUS", "CAN", "FR", "FRA", "GER", "NL", "NED",
            "TR", "TUR", "PL", "POL", "RU", "RUS", "UKR", "PT", "BR", "BRA", "ITA", "ESP", "MX", "MEX", "GR", "GRE",
            "RO", "BG", "BUL", "HU", "HUN", "CZ", "SK", "SE", "SWE", "DK", "FI", "ALB", "SRB", "CRO", "KSA", "UAE",
            "IND", "PAK", "IRN", "ISR", "CHN", "JPN", "KOR", "PHI",
        )) short[t]?.let { groupWords[t] = it }

        fun w(code: String, vararg names: String) = names.forEach {
            if (it.contains(' ')) phrases += " $it " to code else countryWords[it] = code
        }
        // Phrases that must not fall through to "AMERICA" etc.
        w("", "LATIN AMERICA", "SOUTH AMERICA", "CENTRAL AMERICA", "NORTH AMERICA", "MIDDLE EAST")
        w("UK", "UNITED KINGDOM", "GREAT BRITAIN", "NORTHERN IRELAND", "BRITAIN", "BRITISH", "ENGLAND", "SCOTLAND", "WALES")
        w("IE", "IRELAND", "IRISH"); w("US", "UNITED STATES", "AMERICA", "AMERICAN"); w("CA", "CANADA", "CANADIAN")
        w("AU", "AUSTRALIA", "AUSTRALIAN"); w("NZ", "NEW ZEALAND"); w("ZA", "SOUTH AFRICA"); w("NG", "NIGERIA")
        w("GH", "GHANA"); w("KE", "KENYA"); w("JM", "JAMAICA"); w("FR", "FRANCE"); w("DE", "GERMANY", "DEUTSCHLAND")
        w("AT", "AUSTRIA", "OSTERREICH"); w("CH", "SWITZERLAND", "SCHWEIZ", "SUISSE"); w("BE", "BELGIUM", "BELGIQUE")
        w("NL", "NETHERLANDS", "HOLLAND"); w("ES", "SPAIN", "ESPANA"); w("PT", "PORTUGAL"); w("IT", "ITALY", "ITALIA")
        w("GR", "GREECE"); w("CY", "CYPRUS"); w("TR", "TURKEY", "TURKIYE"); w("PL", "POLAND", "POLSKA")
        w("RU", "RUSSIA"); w("UA", "UKRAINE"); w("RO", "ROMANIA"); w("BG", "BULGARIA"); w("RS", "SERBIA")
        w("HR", "CROATIA"); w("BA", "BOSNIA"); w("AL", "ALBANIA"); w("XK", "KOSOVO"); w("HU", "HUNGARY")
        w("CZ", "CZECHIA", "CZECH REPUBLIC"); w("SK", "SLOVAKIA"); w("SE", "SWEDEN"); w("NO", "NORWAY")
        w("DK", "DENMARK"); w("FI", "FINLAND"); w("IL", "ISRAEL"); w("MX", "MEXICO"); w("AR", "ARGENTINA")
        w("BR", "BRAZIL", "BRASIL"); w("CO", "COLOMBIA"); w("CL", "CHILE"); w("PE", "PERU"); w("IN", "INDIA", "INDIAN")
        w("PK", "PAKISTAN"); w("BD", "BANGLADESH"); w("IR", "IRAN"); w("AF", "AFGHANISTAN"); w("CN", "CHINA")
        w("HK", "HONG KONG"); w("TW", "TAIWAN"); w("JP", "JAPAN"); w("KR", "KOREA"); w("PH", "PHILIPPINES")
        w("SA", "SAUDI"); w("AE", "EMIRATES"); w("EG", "EGYPT"); w("MA", "MOROCCO"); w("DZ", "ALGERIA")
        w("TN", "TUNISIA"); w("LB", "LEBANON"); w("IQ", "IRAQ"); w("SY", "SYRIA"); w("JO", "JORDAN")
        w("KW", "KUWAIT"); w("QA", "QATAR"); w("SO", "SOMALIA"); w("ET", "ETHIOPIA")
    }

    /**
     * UK broadcaster names, used only when nothing else identifies the country.
     * Note "SKY SPORTS" (plural) is UK; Sky Sport (singular) is Germany/Italy/NZ.
     */
    private val ukBrands = Regex(
        "^(?:BBC|CBBC|CBEEBIES|ITV\\w*|CITV|STV|UTV|S4C|CHANNEL [45]|E4|MORE4|FILM4|4SEVEN|5 ?USA|5 ?STAR|5 ?ACTION|" +
            "DAVE|U ?DAVE|U ?W|U ?GOLD|U ?ALIBI|U ?YESTERDAY|U ?DRAMA|GOLD|ALIBI|YESTERDAY|BT SPORT|TNT SPORTS|" +
            "SKY SPORTS|SKY NEWS|SKY ONE|SKY ATLANTIC|SKY CINEMA|SKY SHOWCASE|SKY WITNESS|SKY MAX|SKY ARTS|SKY CRIME|" +
            "SKY COMEDY|SKY MIX|SKY KIDS|SKY HISTORY|SKY NATURE|SKY DOCUMENTARIES|PREMIER SPORTS|GB NEWS|TALK ?TV|" +
            "QUEST|PICK|EDEN|BLAZE|REALLY|LBC|TRUE CRIME|CHALLENGE)\\b"
    )

    /** Name with any leading "XX |" style tag removed, normalised to upper-case words. */
    private fun bareName(name: String): String =
        java.text.Normalizer.normalize(name.replace(Regex("^\\W*[A-Za-z]{2,4}\\s*[|:\\]\\-]\\s*"), ""), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").uppercase()
            .replace(Regex("[^A-Z0-9&]+"), " ").trim()

    fun isUkBrand(name: String): Boolean = ukBrands.containsMatchIn(bareName(name))

    fun detectAll(list: List<Channel>): List<Channel> {
        val groupCache = HashMap<String, String>()
        return list.map { c -> c.copy(detectedRegion = detect(c, groupCache.getOrPut(c.group) { fromGroup(c.group) })) }
    }

    fun detect(c: Channel, groupRegion: String = fromGroup(c.group)): String {
        fromCountryAttr(c.tvgCountry)?.let { return it }
        if (groupRegion.isNotEmpty()) return groupRegion
        fromName(c.name)?.let { return it }
        fromTvgId(c.tvgId)?.let { return it }
        if (isUkBrand(c.name)) return "UK"
        return ""
    }

    fun fromGroup(group: String): String {
        LanguageTagger.prefixToken(group)?.let { t -> short[t]?.let { return it } }
        LanguageTagger.suffixToken(group)?.let { t -> short[t]?.let { return it } }
        LanguageTagger.bracketTokens(group).firstNotNullOfOrNull { short[it] }?.let { return it }
        val w = LanguageTagger.words(group)
        val padded = " " + w.joinToString(" ") + " "
        phrases.firstOrNull { padded.contains(it.first) }?.let { return it.second }
        for (word in w) {
            countryWords[word]?.let { return it }
            groupWords[word]?.let { return it }
        }
        return ""
    }

    fun fromName(name: String): String? {
        LanguageTagger.prefixToken(name)?.let { t -> short[t]?.let { return it } }
        LanguageTagger.suffixToken(name)?.let { t -> short[t]?.let { return it } }
        return LanguageTagger.bracketTokens(name).firstNotNullOfOrNull { short[it] }
    }

    fun fromCountryAttr(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return value.split(';', ',', '/', '|').firstNotNullOfOrNull { iso[it.trim().uppercase()] }
    }

    fun fromTvgId(id: String?): String? {
        if (id.isNullOrBlank()) return null
        val core = id.substringBefore('@').trim()
        val dot = core.lastIndexOf('.')
        if (dot < 0 || dot == core.length - 1) return null
        val suffix = core.substring(dot + 1).uppercase()
        return if (suffix.length == 2) iso[suffix] else null
    }
}
