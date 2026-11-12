package com.circaso.feature.auth.domain

/**
 * A country on the phone number screen's picker (2026-11-12): Kenya by default, any country by choice. [lengths] is how
 * many digits follow the calling code; most countries get a wide range and the server (and the code sender) has the last
 * word, while the countries most Circaso people come from are exact so a slip is caught before a code is sent.
 */
data class Country(
    /** ISO 3166-1 alpha-2, like `KE`. */
    val iso: String,
    val name: String,
    /** The calling code without `+`, like `254`; NANP islands carry their area code too (`1876` for Jamaica). */
    val dialCode: String,
    val lengths: IntRange = 4..(15 - dialCode.length),
    /** Italy, San Marino and the Vatican keep the leading 0 after the calling code; everywhere else it's dropped. */
    val keepsLeadingZero: Boolean = false,
    /** A made-up number for the field's placeholder, digits only; empty where we don't have a good example. */
    val example: String = "",
) {
    /** The flag emoji, made from the two regional indicator letters. */
    val flag: String get() = iso.uppercase().map { regionalIndicator(it) }.joinToString("")

    private fun regionalIndicator(letter: Char): String {
        val code = 0x1F1E6 + (letter - 'A')
        // A code point above U+FFFF as its UTF-16 surrogate pair (common code has no Character.toChars).
        val v = code - 0x10000
        return charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }
}

object Countries {
    val Kenya = Country("KE", "Kenya", "254", 9..9, example = "712345678")

    /** Placeholder examples (fictional numbers) for the countries checked exactly. */
    private val examples = mapOf(
        "UG" to "712345678", "TZ" to "712345678", "RW" to "788123456", "NG" to "8021234567", "GH" to "241234567",
        "ZA" to "712345678", "US" to "2015550123", "CA" to "4165550123", "GB" to "7700900123", "AE" to "501234567",
        "IN" to "9123456789",
    )

    /** Countries checked exactly: East Africa, the neighbours Circaso people call most, and the large diasporas. */
    private val exact = mapOf(
        "UG" to 9..9, "TZ" to 9..9, "RW" to 9..9, "BI" to 8..8, "SS" to 9..9, "ET" to 9..9, "SO" to 7..9,
        "NG" to 10..10, "GH" to 9..9, "ZA" to 9..9, "ZM" to 9..9, "MW" to 9..9, "CD" to 9..9, "ZW" to 9..9,
        "US" to 10..10, "CA" to 10..10, "GB" to 10..10, "IN" to 10..10, "AE" to 9..9, "SA" to 9..9, "QA" to 8..8,
        "DE" to 7..11, "FR" to 9..9, "NL" to 9..9, "AU" to 9..9, "CN" to 11..11,
    )

    private val raw = """
        AF|Afghanistan|93;AL|Albania|355;DZ|Algeria|213;AS|American Samoa|1684;AD|Andorra|376;AO|Angola|244;AI|Anguilla|1264;
        AG|Antigua and Barbuda|1268;AR|Argentina|54;AM|Armenia|374;AW|Aruba|297;AU|Australia|61;AT|Austria|43;AZ|Azerbaijan|994;
        BS|Bahamas|1242;BH|Bahrain|973;BD|Bangladesh|880;BB|Barbados|1246;BY|Belarus|375;BE|Belgium|32;BZ|Belize|501;BJ|Benin|229;
        BM|Bermuda|1441;BT|Bhutan|975;BO|Bolivia|591;BA|Bosnia and Herzegovina|387;BW|Botswana|267;BR|Brazil|55;
        VG|British Virgin Islands|1284;BN|Brunei|673;BG|Bulgaria|359;BF|Burkina Faso|226;BI|Burundi|257;KH|Cambodia|855;
        CM|Cameroon|237;CA|Canada|1;CV|Cape Verde|238;KY|Cayman Islands|1345;CF|Central African Republic|236;TD|Chad|235;
        CL|Chile|56;CN|China|86;CO|Colombia|57;KM|Comoros|269;CG|Congo|242;CD|Congo (DRC)|243;CK|Cook Islands|682;CR|Costa Rica|506;
        CI|Côte d’Ivoire|225;HR|Croatia|385;CU|Cuba|53;CW|Curaçao|599;CY|Cyprus|357;CZ|Czechia|420;DK|Denmark|45;DJ|Djibouti|253;
        DM|Dominica|1767;DO|Dominican Republic|1809;EC|Ecuador|593;EG|Egypt|20;SV|El Salvador|503;GQ|Equatorial Guinea|240;
        ER|Eritrea|291;EE|Estonia|372;SZ|Eswatini|268;ET|Ethiopia|251;FK|Falkland Islands|500;FO|Faroe Islands|298;FJ|Fiji|679;
        FI|Finland|358;FR|France|33;GF|French Guiana|594;PF|French Polynesia|689;GA|Gabon|241;GM|Gambia|220;GE|Georgia|995;
        DE|Germany|49;GH|Ghana|233;GI|Gibraltar|350;GR|Greece|30;GL|Greenland|299;GD|Grenada|1473;GP|Guadeloupe|590;GU|Guam|1671;
        GT|Guatemala|502;GG|Guernsey|44;GN|Guinea|224;GW|Guinea-Bissau|245;GY|Guyana|592;HT|Haiti|509;HN|Honduras|504;
        HK|Hong Kong|852;HU|Hungary|36;IS|Iceland|354;IN|India|91;ID|Indonesia|62;IR|Iran|98;IQ|Iraq|964;IE|Ireland|353;
        IM|Isle of Man|44;IL|Israel|972;IT|Italy|39;JM|Jamaica|1876;JP|Japan|81;JE|Jersey|44;JO|Jordan|962;KZ|Kazakhstan|7;
        KE|Kenya|254;KI|Kiribati|686;XK|Kosovo|383;KW|Kuwait|965;KG|Kyrgyzstan|996;LA|Laos|856;LV|Latvia|371;LB|Lebanon|961;
        LS|Lesotho|266;LR|Liberia|231;LY|Libya|218;LI|Liechtenstein|423;LT|Lithuania|370;LU|Luxembourg|352;MO|Macao|853;
        MG|Madagascar|261;MW|Malawi|265;MY|Malaysia|60;MV|Maldives|960;ML|Mali|223;MT|Malta|356;MH|Marshall Islands|692;
        MQ|Martinique|596;MR|Mauritania|222;MU|Mauritius|230;YT|Mayotte|262;MX|Mexico|52;FM|Micronesia|691;MD|Moldova|373;
        MC|Monaco|377;MN|Mongolia|976;ME|Montenegro|382;MS|Montserrat|1664;MA|Morocco|212;MZ|Mozambique|258;MM|Myanmar|95;
        NA|Namibia|264;NR|Nauru|674;NP|Nepal|977;NL|Netherlands|31;NC|New Caledonia|687;NZ|New Zealand|64;NI|Nicaragua|505;
        NE|Niger|227;NG|Nigeria|234;NU|Niue|683;KP|North Korea|850;MK|North Macedonia|389;MP|Northern Mariana Islands|1670;
        NO|Norway|47;OM|Oman|968;PK|Pakistan|92;PW|Palau|680;PS|Palestine|970;PA|Panama|507;PG|Papua New Guinea|675;
        PY|Paraguay|595;PE|Peru|51;PH|Philippines|63;PL|Poland|48;PT|Portugal|351;PR|Puerto Rico|1787;QA|Qatar|974;
        RE|Réunion|262;RO|Romania|40;RU|Russia|7;RW|Rwanda|250;KN|Saint Kitts and Nevis|1869;LC|Saint Lucia|1758;
        VC|Saint Vincent and the Grenadines|1784;WS|Samoa|685;SM|San Marino|378;ST|São Tomé and Príncipe|239;SA|Saudi Arabia|966;
        SN|Senegal|221;RS|Serbia|381;SC|Seychelles|248;SL|Sierra Leone|232;SG|Singapore|65;SX|Sint Maarten|1721;SK|Slovakia|421;
        SI|Slovenia|386;SB|Solomon Islands|677;SO|Somalia|252;ZA|South Africa|27;KR|South Korea|82;SS|South Sudan|211;ES|Spain|34;
        LK|Sri Lanka|94;SD|Sudan|249;SR|Suriname|597;SE|Sweden|46;CH|Switzerland|41;SY|Syria|963;TW|Taiwan|886;TJ|Tajikistan|992;
        TZ|Tanzania|255;TH|Thailand|66;TL|Timor-Leste|670;TG|Togo|228;TO|Tonga|676;TT|Trinidad and Tobago|1868;TN|Tunisia|216;
        TR|Türkiye|90;TM|Turkmenistan|993;TC|Turks and Caicos Islands|1649;TV|Tuvalu|688;VI|U.S. Virgin Islands|1340;UG|Uganda|256;
        UA|Ukraine|380;AE|United Arab Emirates|971;GB|United Kingdom|44;US|United States|1;UY|Uruguay|598;UZ|Uzbekistan|998;
        VU|Vanuatu|678;VA|Vatican City|39;VE|Venezuela|58;VN|Vietnam|84;YE|Yemen|967;ZM|Zambia|260;ZW|Zimbabwe|263
    """

    /** Every country, A to Z. */
    val all: List<Country> = raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { entry ->
        val (iso, name, dial) = entry.split('|')
        when {
            iso == Kenya.iso -> Kenya
            else -> Country(
                iso, name, dial, exact[iso] ?: 4..(15 - dial.length),
                keepsLeadingZero = iso in setOf("IT", "SM", "VA"), example = examples[iso].orEmpty(),
            )
        }
    }

    fun byIso(iso: String): Country? = all.firstOrNull { it.iso.equals(iso, ignoreCase = true) }

    /** Countries matching what was typed in the picker's search: a name, an ISO code, or a calling code (`+44`, `44`). */
    fun search(query: String): List<Country> {
        val q = query.trim()
        if (q.isEmpty()) return all
        val digits = q.filter { it.isDigit() }
        if (digits.isNotEmpty() && q.all { it.isDigit() || it == '+' || it == ' ' }) {
            return all.filter { it.dialCode.startsWith(digits) }.sortedBy { it.dialCode.length }
        }
        val starts = all.filter { it.name.startsWith(q, ignoreCase = true) || it.iso.equals(q, ignoreCase = true) }
        val contains = all.filter { it !in starts && it.name.contains(q, ignoreCase = true) }
        return starts + contains
    }

    /**
     * The country whose calling code starts [digits] (an international number's digits after `+`), longest code first, so
     * `1876…` is Jamaica rather than the US. Where several share a code, the most likely one wins (Kenya, US, UK, Russia).
     */
    /**
     * A number typed after "+", one key at a time: the country and the digits after its code once the code can't be a
     * longer one still ("+1" waits, since +1268 is Antigua; "+44" is the UK at once). Null while it could still be either.
     */
    fun resolveTyped(digits: String): Pair<Country, String>? {
        if (all.any { it.dialCode.length > digits.length && it.dialCode.startsWith(digits) }) return null
        val country = forInternational(digits) ?: return null
        return country to digits.drop(country.dialCode.length)
    }

    fun forInternational(digits: String): Country? {
        val preferred = setOf("KE", "US", "GB", "RU", "IT", "FI", "RE")
        return all.filter { digits.startsWith(it.dialCode) }
            .sortedWith(compareByDescending<Country> { it.dialCode.length }.thenByDescending { it.iso in preferred })
            .firstOrNull()
    }
}
