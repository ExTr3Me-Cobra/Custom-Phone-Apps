package com.labelrescue.core.fusion

import com.labelrescue.core.candidates.SlotDist
import com.labelrescue.core.image.IntRect
import com.labelrescue.core.usps.TrackingFormat

enum class Source(val label: String) {
    BARCODE("Barcode"),
    BARCODE_PARTIAL("Damaged barcode"),
    DATAMATRIX("2D code"),
    OCR("Printed digits"),
    /** The on-device damaged-digit classifier, run on each digit's crop. */
    DIGIT_MODEL("Digit model"),
}

/**
 * Evidence about the tracking number from one read: one decoder on one variant of one frame.
 * [slots] is per tracking-number position; null means "no information here".
 */
class Observation(
    val source: Source,
    val format: TrackingFormat,
    val slots: List<SlotDist?>,
    val weight: Double,
    val frame: Int,
    val variant: String,
    /** Where each position is on the frame (for zoomed crops), if known. */
    val boxes: List<IntRect?> = List(slots.size) { null },
    /** Per-position confidence of the location (higher = better crop). */
    val boxQuality: List<Double> = List(slots.size) { 0.0 },
) {
    init {
        require(slots.size == format.length && boxes.size == slots.size)
    }
}
