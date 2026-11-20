package com.labelrescue.core.barcode

import com.labelrescue.core.usps.TrackingFormat

/**
 * Known layouts of USPS GS1-128 barcodes, in code set C (two digits per symbol):
 * `[Start C] [FNC1] 420 ZIP [FNC1] tracking [check] [Stop]`, or without the routing part.
 */
class Gs1Structure(
    val name: String,
    /** "420" + ZIP digit count: 0, 8 or 12. */
    val routingDigits: Int,
    val trackingDigits: Int,
    /** Whether the tracking part is preceded by FNC1. */
    trackingFnc1: Boolean = true,
) {
    sealed class Slot {
        data object Fnc1 : Slot()
        /** Two data digits at [digitIndex], [digitIndex]+1 of the data string; [fixed] digits are known. */
        data class Pair(val digitIndex: Int, val fixedTens: Int? = null, val fixedUnits: Int? = null) : Slot() {
            fun allows(v: Int) = v in 0..99 && (fixedTens == null || v / 10 == fixedTens) && (fixedUnits == null || v % 10 == fixedUnits)
        }
    }

    val slots: List<Slot>
    val dataDigits = routingDigits + trackingDigits
    /** Symbols between start and stop, including the checksum symbol. */
    val symbolCount: Int

    val trackingFormat: TrackingFormat = when (trackingDigits) {
        22 -> TrackingFormat.IMPB_22
        26 -> TrackingFormat.IMPB_26
        20 -> TrackingFormat.IMPB_20
        else -> error("unsupported")
    }

    init {
        val s = ArrayList<Slot>()
        if (routingDigits > 0) {
            s += Slot.Fnc1
            val fixed = "420"
            for (i in 0 until routingDigits step 2) {
                s += Slot.Pair(i, fixed.getOrNull(i)?.minus('0'), fixed.getOrNull(i + 1)?.minus('0'))
            }
        }
        if (trackingFnc1) s += Slot.Fnc1
        for (i in 0 until trackingDigits step 2) s += Slot.Pair(routingDigits + i)
        slots = s
        symbolCount = s.size + 1
    }

    fun allows(slotIndex: Int, v: Int): Boolean = when (val sl = slots[slotIndex]) {
        Slot.Fnc1 -> v == Code128.FNC1
        is Slot.Pair -> sl.allows(v)
    }

    /**
     * Log prior: how common this layout is on current labels. 420+ZIP routing with a 22-digit
     * IMpb is the norm; 26-digit and bare forms are less common; legacy 20-digit barcodes are rare.
     */
    val prior: Double = kotlin.math.ln(
        when {
            trackingDigits == 22 && routingDigits > 0 -> 1.0
            trackingDigits == 22 -> 0.5
            trackingDigits == 26 -> 0.25
            else -> 0.05
        },
    )

    /** Mod-10 weight for a data digit (check digit has weight 1), or 0 if not part of the tracking number. */
    fun mod10Weight(dataIndex: Int): Int {
        val t = dataIndex - routingDigits
        if (t < 0) return 0
        val r = trackingDigits - 1 - t
        return if (r % 2 == 0) 1 else 3
    }

    override fun toString() = name

    companion object {
        val ALL = listOf(
            Gs1Structure("420+ZIP5+22", 8, 22),
            Gs1Structure("420+ZIP9+22", 12, 22),
            Gs1Structure("420+ZIP5+26", 8, 26),
            Gs1Structure("22", 0, 22),
            Gs1Structure("26", 0, 26),
            Gs1Structure("20", 0, 20),
            Gs1Structure("20 (no FNC1)", 0, 20, trackingFnc1 = false),
        )
        val SYMBOL_COUNTS = ALL.map { it.symbolCount }.toSortedSet()
    }
}
