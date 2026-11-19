package com.music.tuner.rds

/**
 * North American (RBDS) station codes spell the station's call letters: the standard
 * (NRSC-4) way of turning a PI code back into them.
 */
object CallLetters {
    /** Stations old enough to have three-letter call signs get codes from this table. */
    private val THREE_LETTERS = mapOf(
        0x99A5 to "KBW", 0x9992 to "KOY", 0x9978 to "WHO", 0x99A6 to "KCY", 0x9993 to "KPQ", 0x999C to "WHP",
        0x9990 to "KDB", 0x9964 to "KQV", 0x999D to "WIL", 0x99A7 to "KDF", 0x9994 to "KSD", 0x997A to "WIP",
        0x9950 to "KEX", 0x9965 to "KSL", 0x99B3 to "WIS", 0x9951 to "KFH", 0x9966 to "KUJ", 0x997B to "WJR",
        0x9952 to "KFI", 0x9995 to "KUT", 0x99B4 to "WJW", 0x9953 to "KGA", 0x9967 to "KVI", 0x99B5 to "WJZ",
        0x9991 to "KGB", 0x9968 to "KWG", 0x997C to "WKY", 0x9954 to "KGO", 0x9996 to "KXL", 0x997D to "WLS",
        0x9955 to "KGU", 0x9997 to "KXO", 0x997E to "WLW", 0x9956 to "KGW", 0x996B to "KYW", 0x999E to "WMC",
        0x9957 to "KGY", 0x9999 to "WBT", 0x999F to "WMT", 0x99AA to "KHQ", 0x996D to "WBZ", 0x9981 to "WOC",
        0x9958 to "KID", 0x996E to "WDZ", 0x99A0 to "WOI", 0x9959 to "KIT", 0x996F to "WEW", 0x9983 to "WOL",
        0x995A to "KJR", 0x999A to "WGH", 0x9984 to "WOR", 0x995B to "KLO", 0x9971 to "WGL", 0x99A1 to "WOW",
        0x995C to "KLZ", 0x9972 to "WGN", 0x99B9 to "WRC", 0x995D to "KMA", 0x9973 to "WGR", 0x99A2 to "WRR",
        0x995E to "KMJ", 0x999B to "WGY", 0x99A3 to "WSB", 0x995F to "KNX", 0x9975 to "WHA", 0x99A4 to "WSM",
        0x9960 to "KOA", 0x9976 to "WHB", 0x9988 to "WWJ", 0x99AB to "KOB", 0x9977 to "WHK", 0x9989 to "WWL",
    )

    /**
     * The call letters [pi] spells, or null when it doesn't spell any (a nationally linked
     * network like NPR, a Canadian or Mexican code, or one outside the scheme).
     */
    fun of(pi: Int): String? {
        var code = pi and 0xFFFF
        // Codes with a zero digit are sent with "A" and "F" in front.
        when {
            (code and 0xFFF0) == 0xAFA0 && (code and 0x000F) < 0xA -> code = (code shl 12) and 0xFFFF
            (code and 0xFF00) == 0xAF00 -> code = (code shl 8) and 0xFFFF
            (code and 0xF000) == 0xA000 -> code = ((code and 0x0F00) shl 4) or (code and 0x00FF)
        }
        return when (code) {
            in 0x1000..0x54A7 -> spell('K', code - 0x1000)
            in 0x54A8..0x994F -> spell('W', code - 0x54A8)
            in 0x9950..0x9EFF -> THREE_LETTERS[code]
            else -> null
        }
    }

    /** [pi] written as RDS tools show it ("0x54A8", "54A8") or as a number. */
    fun of(pi: String): String? = parsePi(pi)?.let(::of)

    fun parsePi(pi: String): Int? = pi.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.takeIf { it in 0..0xFFFF }

    private fun spell(first: Char, n: Int): String =
        "$first${'A' + n / 676}${'A' + n / 26 % 26}${'A' + n % 26}"

    /** The PI code call letters spell (the other way round), e.g. for tests and manual entries. */
    fun piOf(callLetters: String): Int? {
        val letters = callLetters.uppercase()
        if (letters.length != 4 || letters.any { it !in 'A'..'Z' }) {
            return THREE_LETTERS.entries.firstOrNull { it.value == letters }?.key
        }
        val n = (letters[1] - 'A') * 676 + (letters[2] - 'A') * 26 + (letters[3] - 'A')
        return when (letters[0]) {
            'K' -> 0x1000 + n
            'W' -> 0x54A8 + n
            else -> null
        }
    }

    /** Call letters at the start of a name stations send, e.g. "WXYZ-FM" or "WXYZ-HD2": "WXYZ". */
    fun fromName(name: String?): String? {
        val match = Regex("""^\s*([KWC][A-Z]{2,3})(?:[-\s]?(?:FM|HD\d?|LP|LP-FM))*\b""").find(name?.uppercase() ?: return null) ?: return null
        return match.groupValues[1]
    }
}
