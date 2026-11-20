package com.labelrescue.core.verify

/** What USPS reported about one tracking number (fields from the Tracking 3.x API response). */
data class TrackingFacts(
    val trackingNumber: String,
    val mailClass: String? = null,
    val destinationZip: String? = null,
    val destinationCity: String? = null,
    val destinationState: String? = null,
    val originZip: String? = null,
    val originCity: String? = null,
    val originState: String? = null,
    val statusCategory: String? = null,
    val statusSummary: String? = null,
    val events: List<Event> = emptyList(),
) {
    data class Event(
        val type: String?,
        val code: String?,
        /** ISO-8601 as USPS sends it, e.g. 2023-08-02T07:31:00Z. */
        val timestamp: String?,
        val city: String?,
        val state: String?,
        val zip: String?,
    )

    val hasHistory: Boolean get() = events.isNotEmpty() || !statusCategory.isNullOrBlank() || !statusSummary.isNullOrBlank()

    fun toJson(): String = Json.write(
        mapOf(
            "trackingNumber" to trackingNumber, "mailClass" to mailClass, "destinationZIP" to destinationZip,
            "destinationCity" to destinationCity, "destinationState" to destinationState, "originZIP" to originZip,
            "originCity" to originCity, "originState" to originState, "statusCategory" to statusCategory,
            "statusSummary" to statusSummary,
            "trackingEvents" to events.map {
                mapOf("eventType" to it.type, "eventCode" to it.code, "eventTimestamp" to it.timestamp, "eventCity" to it.city, "eventState" to it.state, "eventZIP" to it.zip)
            },
        ),
    )

    companion object {
        /** Reads one tracking object (as in the API response). */
        fun fromMap(m: Map<*, *>): TrackingFacts {
            fun s(k: String): String? = (m[k] as? String)?.takeIf { it.isNotBlank() }
            val events = (m["trackingEvents"] as? List<*>).orEmpty().mapNotNull { e ->
                val em = e as? Map<*, *> ?: return@mapNotNull null
                fun es(k: String) = (em[k] as? String)?.takeIf { it.isNotBlank() }
                Event(es("eventType"), es("eventCode"), es("eventTimestamp") ?: es("GMTTimestamp"), es("eventCity"), es("eventState"), es("eventZIP"))
            }
            return TrackingFacts(
                trackingNumber = s("trackingNumber") ?: "",
                mailClass = s("mailClass")?.let(::stripMarkup),
                destinationZip = s("destinationZIP") ?: s("destinationZIPCode"),
                destinationCity = s("destinationCity"), destinationState = s("destinationState"),
                originZip = s("originZIP"), originCity = s("originCity"), originState = s("originState"),
                statusCategory = s("statusCategory"), statusSummary = s("statusSummary") ?: s("status"),
                events = events,
            )
        }

        fun fromJson(text: String): TrackingFacts = fromMap(Json.parse(text) as Map<*, *>)

        /** "Priority Mail<SUP>&reg;</SUP>" → "Priority Mail". */
        fun stripMarkup(s: String): String = s.replace(Regex("<[^>]*>"), "").replace(Regex("&[#a-zA-Z0-9]+;"), "").trim()
    }
}
