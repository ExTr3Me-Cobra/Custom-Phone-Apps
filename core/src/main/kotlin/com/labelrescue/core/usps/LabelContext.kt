package com.labelrescue.core.usps

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * What the label itself says: destination and origin ZIPs, mail class and date.
 * Read from OCR text, for the training report and the ranking hints.
 */
data class LabelContext(
    val destinationZip: String? = null,
    val destinationCity: String? = null,
    val originZip: String? = null,
    val originCity: String? = null,
    /** "Priority Mail", "Ground Advantage", ... */
    val mailClass: String? = null,
    /** Date printed on the label (ship / label date). */
    val labelDate: LocalDate? = null,
) {
    val isEmpty: Boolean get() = destinationZip == null && destinationCity == null && originZip == null && originCity == null && mailClass == null && labelDate == null

    companion object {
        private val ZIP = Regex("(?<![0-9])([0-9]{5})(?:-[0-9]{4})?(?![0-9])")
        private val CITY_ST_ZIP = Regex("([A-Z][A-Z .'-]{1,30}?)[ ,]+([A-Z]{2})[ ]+([0-9]{5})(?:-[0-9]{4})?(?![0-9])")
        private val MAILED_FROM = Regex("MAILED\\s+FROM\\s+(?:ZIP\\s*(?:CODE)?\\s*)?:?\\s*([0-9]{5})")
        private val NUMERIC_DATE = Regex("(?<![0-9])(0?[1-9]|1[0-2])[/.-](0?[1-9]|[12][0-9]|3[01])[/.-]([0-9]{4}|[0-9]{2})(?![0-9])")
        private val ISO_DATE = Regex("(?<![0-9])(20[0-9]{2})-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])(?![0-9])")
        private val TEXT_DATE = Regex("(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*\\.?\\s+([0-9]{1,2}),?\\s+(20[0-9]{2})")
        private val SHIP_TO = Regex("SHIP\\s*TO|DELIVER\\s*TO|\\bTO:")

        /**
         * Best-effort reading of the label text (OCR). [barcodeZip] (from the 420 routing prefix) is
         * the most reliable destination ZIP when present.
         */
        fun fromLabelText(text: String, barcodeZip: String? = null): LabelContext {
            val t = text.uppercase()
            val shipTo = SHIP_TO.find(t)?.range?.first ?: -1
            val addresses = CITY_ST_ZIP.findAll(t).map { Triple(it.range.first, it.groupValues[1].trim(), it.groupValues[3]) }.toList()
            val dest = barcodeZip?.take(5)
                ?: addresses.firstOrNull { shipTo >= 0 && it.first > shipTo }?.third
                ?: addresses.lastOrNull()?.third
            val destCity = addresses.firstOrNull { it.third == dest }?.second?.let(::cleanCity)
            val mailedFrom = MAILED_FROM.find(t)?.groupValues?.get(1)
            val origin = mailedFrom
                ?: addresses.firstOrNull { it.third != dest && (shipTo < 0 || it.first < shipTo) }?.third
                ?: ZIP.findAll(t).map { it.groupValues[1] }.firstOrNull { it != dest && (shipTo < 0 || t.indexOf(it) < shipTo) }
            val originCity = addresses.firstOrNull { it.third == origin && it.third != dest }?.second?.let(::cleanCity)
            val service = ServiceHints.serviceHints(t).firstOrNull()?.service
            return LabelContext(dest, destCity, origin, originCity, service, findDate(t))
        }

        private fun cleanCity(s: String): String? {
            // Drop words that are street parts rather than the city (keep the last 1-3 words).
            val words = s.split(' ').filter { it.isNotBlank() }
            return words.takeLast(minOf(3, words.size)).joinToString(" ").takeIf { it.length >= 2 }
        }

        fun findDate(t: String): LocalDate? {
            ISO_DATE.find(t)?.let { m -> return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull() }
            NUMERIC_DATE.find(t)?.let { m ->
                val y = m.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
                return runCatching { LocalDate.of(y, m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull()
            }
            TEXT_DATE.find(t)?.let { m ->
                val month = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC").indexOf(m.groupValues[1]) + 1
                return runCatching { LocalDate.of(m.groupValues[3].toInt(), month, m.groupValues[2].toInt()) }.getOrNull()
            }
            return null
        }

        fun formatDate(d: LocalDate?): String = d?.format(DateTimeFormatter.ofPattern("MM/dd/yyyy")) ?: ""

        fun parseDate(s: String): LocalDate? = findDate(s.uppercase().trim())
    }
}
