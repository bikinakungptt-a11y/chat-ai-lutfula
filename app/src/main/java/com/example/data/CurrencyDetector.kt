package com.example.data

import java.util.Locale

/** Detects fiat currency questions from user text using ISO codes and common Indonesian/English aliases. */
data class CurrencyQuery(
    val amount: Double = 1.0,
    val fromCode: String,
    val toCode: String,
    val originalText: String
)

object CurrencyDetector {
    val supportedCodes: Set<String> = setOf(
        "USD", "IDR", "EUR", "GBP", "JPY", "AUD", "CAD", "SGD", "MYR", "THB", "PHP", "VND",
        "CNY", "HKD", "KRW", "INR", "AED", "SAR", "QAR", "KWD", "OMR", "BHD", "TRY", "RUB",
        "CHF", "NZD", "BRL", "MXN", "ZAR", "IRR", "PKR", "BDT", "EGP", "NOK", "SEK", "DKK",
        "PLN", "CZK", "HUF", "ILS", "ARS", "CLP", "COP", "PEN", "TWD"
    )

    private val aliases: Map<String, String> = mapOf(
        "usd" to "USD", "us dollar" to "USD", "u s dollar" to "USD", "dollar amerika" to "USD", "dolar amerika" to "USD", "dollar" to "USD", "dolar" to "USD", "$" to "USD",
        "idr" to "IDR", "rupiah" to "IDR", "rp" to "IDR", "ribu rupiah" to "IDR",
        "eur" to "EUR", "euro" to "EUR", "€" to "EUR",
        "gbp" to "GBP", "pound sterling" to "GBP", "pound" to "GBP", "£" to "GBP",
        "jpy" to "JPY", "yen jepang" to "JPY", "yen" to "JPY",
        "aud" to "AUD", "australian dollar" to "AUD", "dolar australia" to "AUD",
        "cad" to "CAD", "canadian dollar" to "CAD", "dolar kanada" to "CAD",
        "sgd" to "SGD", "singapore dollar" to "SGD", "dolar singapura" to "SGD",
        "myr" to "MYR", "ringgit malaysia" to "MYR", "ringgit" to "MYR",
        "thb" to "THB", "baht thailand" to "THB", "baht" to "THB",
        "php" to "PHP", "peso filipina" to "PHP", "philippine peso" to "PHP",
        "vnd" to "VND", "dong vietnam" to "VND", "dong" to "VND",
        "cny" to "CNY", "yuan china" to "CNY", "renminbi" to "CNY", "yuan" to "CNY",
        "hkd" to "HKD", "hong kong dollar" to "HKD", "dolar hong kong" to "HKD",
        "krw" to "KRW", "won korea" to "KRW", "won" to "KRW", "₩" to "KRW",
        "inr" to "INR", "rupee india" to "INR", "rupee" to "INR", "₹" to "INR",
        "aed" to "AED", "dirham uae" to "AED", "dirham emirates" to "AED", "dirham" to "AED",
        "sar" to "SAR", "riyal saudi" to "SAR", "saudi riyal" to "SAR", "riyal" to "SAR",
        "qar" to "QAR", "riyal qatar" to "QAR", "qatar riyal" to "QAR",
        "kwd" to "KWD", "dinar kuwait" to "KWD", "kuwaiti dinar" to "KWD",
        "omr" to "OMR", "rial oman" to "OMR", "omani rial" to "OMR",
        "bhd" to "BHD", "dinar bahrain" to "BHD", "bahraini dinar" to "BHD",
        "try" to "TRY", "lira turki" to "TRY", "turkish lira" to "TRY", "lira" to "TRY", "₺" to "TRY",
        "rub" to "RUB", "rubel rusia" to "RUB", "russian ruble" to "RUB", "ruble" to "RUB", "rubel" to "RUB", "₽" to "RUB",
        "chf" to "CHF", "franc swiss" to "CHF", "swiss franc" to "CHF", "franc" to "CHF",
        "nzd" to "NZD", "new zealand dollar" to "NZD", "dolar selandia baru" to "NZD",
        "brl" to "BRL", "real brasil" to "BRL", "brazilian real" to "BRL",
        "mxn" to "MXN", "peso meksiko" to "MXN", "mexican peso" to "MXN",
        "zar" to "ZAR", "rand afrika" to "ZAR", "south african rand" to "ZAR", "rand" to "ZAR",
        "irr" to "IRR", "rial iran" to "IRR", "iranian rial" to "IRR",
        "pkr" to "PKR", "rupee pakistan" to "PKR", "pakistani rupee" to "PKR",
        "bdt" to "BDT", "taka bangladesh" to "BDT", "taka" to "BDT",
        "egp" to "EGP", "pound mesir" to "EGP", "egyptian pound" to "EGP",
        "nok" to "NOK", "krone norwegia" to "NOK", "norwegian krone" to "NOK",
        "sek" to "SEK", "krona swedia" to "SEK", "swedish krona" to "SEK",
        "dkk" to "DKK", "krone denmark" to "DKK", "danish krone" to "DKK",
        "pln" to "PLN", "zloty polandia" to "PLN", "polish zloty" to "PLN", "zloty" to "PLN",
        "czk" to "CZK", "koruna ceko" to "CZK", "czech koruna" to "CZK",
        "huf" to "HUF", "forint hungaria" to "HUF", "hungarian forint" to "HUF",
        "ils" to "ILS", "shekel israel" to "ILS", "israeli shekel" to "ILS", "shekel" to "ILS",
        "ars" to "ARS", "peso argentina" to "ARS", "argentine peso" to "ARS",
        "clp" to "CLP", "peso chili" to "CLP", "chilean peso" to "CLP",
        "cop" to "COP", "peso kolombia" to "COP", "colombian peso" to "COP",
        "pen" to "PEN", "sol peru" to "PEN", "peruvian sol" to "PEN",
        "twd" to "TWD", "taiwan dollar" to "TWD", "dolar taiwan" to "TWD"
    )

    fun detect(message: String): CurrencyQuery? {
        val text = message.lowercase(Locale.US)
        val mentions = findCurrencyMentions(text)
        val hasCurrencyIntent = Regex("\\b(kurs|rate|exchange|mata\\s*uang|berapa|convert|konversi|to|ke)\\b").containsMatchIn(text)
        if (mentions.isEmpty() || !hasCurrencyIntent) return null

        val amount = extractAmount(text) ?: 1.0
        val uniqueCodes = mentions.map { it.code }.distinct()
        val from = uniqueCodes.first()
        val to = when {
            uniqueCodes.size >= 2 -> uniqueCodes[1]
            from != "IDR" -> "IDR"
            else -> "USD"
        }

        return CurrencyQuery(
            amount = amount,
            fromCode = from,
            toCode = to,
            originalText = message
        )
    }

    fun allAliasNames(): List<String> = aliases.keys.sorted()

    private data class CurrencyMention(val code: String, val start: Int, val end: Int)

    private fun findCurrencyMentions(text: String): List<CurrencyMention> {
        val found = mutableListOf<CurrencyMention>()
        aliases.forEach { (alias, code) ->
            val regex = if (alias.length == 1 && !alias[0].isLetterOrDigit()) {
                Regex(Regex.escape(alias))
            } else {
                Regex("(?<![a-z0-9])${Regex.escape(alias)}(?![a-z0-9])")
            }
            regex.findAll(text).forEach { match ->
                found.add(CurrencyMention(code, match.range.first, match.range.last + 1))
            }
        }
        return found.sortedWith(compareBy<CurrencyMention> { it.start }.thenBy { it.end })
            .fold(mutableListOf()) { acc, mention ->
                if (acc.none { it.start == mention.start && it.end == mention.end }) acc.add(mention)
                acc
            }
    }

    private fun extractAmount(text: String): Double? {
        val number = Regex("""\b\d+(?:[.,]\d+)?\b""").find(text)?.value ?: return null
        val normalized = if (number.contains(',') && !number.contains('.')) {
            number.replace(',', '.')
        } else {
            number.replace(",", "")
        }
        return normalized.toDoubleOrNull()
    }
}
