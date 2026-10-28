package dev.pier.reelguard.core

import org.json.JSONObject

/**
 * Detection rules are data, not code: view-id sets harvested from the IG build
 * (runbook in docs/DETECTION.md) plus a paused-marker that encodes the
 * live-verified rule "paused <=> <marker> node present". Keeping this as data
 * lets detection be repaired after IG updates without touching logic.
 */
data class DetectionSample(
    val label: String,
    val presentIds: List<String>,
    val expectPlaying: Boolean,
)

data class SurfaceRule(
    val id: String,
    val coreIdHints: List<String> = emptyList(),
    val idHints: List<String> = emptyList(),
    val negativeIdHints: List<String> = emptyList(),
    val pausedWhenIdPresent: String? = null,
    val windowActivityPatterns: List<String> = emptyList(),
    val textOrDescHints: List<String> = emptyList(),
    val notes: String = "",
    val samples: List<DetectionSample> = emptyList(),
) {
    /**
     * A surface matches when its core ids co-occur (confident), or - when no
     * core ids are defined - when any hint id is present. Negative hints veto.
     * Negative entries may be bare ids, pkg-qualified ids, or `prefix*`
     * globs, optionally followed by a "(...)" remark which is ignored.
     */
    fun matches(presentIds: Set<String>): Boolean {
        if (coreIdHints.isEmpty() && idHints.isEmpty()) return false
        if (!coreIdHints.all { it in presentIds }) return false
        if (coreIdHints.isEmpty() && idHints.none { it in presentIds }) return false
        return negativeIdHints.none { vetoMatches(it, presentIds) }
    }

    /** null when this surface defines no paused-marker (playing unknown). */
    fun playingFrom(presentIds: Set<String>): Boolean? =
        pausedWhenIdPresent?.let { marker -> !presentIds.contains(marker) }

    private fun vetoMatches(entry: String, presentIds: Set<String>): Boolean {
        val token = entry.substringBefore(" (").trim()
        if (token.isEmpty()) return false
        // Normalize through the last '/' so bare hints match pkg:id/name ids.
        return if (token.endsWith("*")) {
            val prefix = token.removeSuffix("*").substringAfterLast('/')
            presentIds.any { it.substringAfterLast('/').startsWith(prefix) }
        } else {
            token in presentIds ||
                presentIds.any { it.substringAfterLast('/') == token.substringAfterLast('/') }
        }
    }
}

data class DetectionRules(
    val schemaVersion: Int = 1,
    val packageNames: List<String> = emptyList(),
    val igVersionName: String = "",
    val igVersionCode: Long = 0L,
    val source: String = "",
    val notes: String = "",
    val surfaces: Map<String, SurfaceRule> = emptyMap(),
) {
    /** Evaluation order = [Surface] declaration order (most specific first). */
    fun orderedRules(): List<Pair<Surface, SurfaceRule>> =
        Surface.entries.mapNotNull { surface -> surfaces[surface.id]?.let { surface to it } }

    /** Every id one tree scan must look for. */
    fun referencedViewIds(): List<String> = orderedRules()
        .flatMap { (_, rule) -> rule.coreIdHints + rule.idHints + listOfNotNull(rule.pausedWhenIdPresent) }
        .distinct()

    /** First surface whose rule matches the present-id set, else null. */
    fun detect(presentIds: Set<String>): Surface? =
        orderedRules().firstOrNull { (_, rule) -> rule.matches(presentIds) }?.first

    fun playingFrom(surface: Surface, presentIds: Set<String>): Boolean? =
        surfaces[surface.id]?.playingFrom(presentIds)

    companion object {
        fun fromJson(json: String): DetectionRules {
            val root = JSONObject(json)
            val surfacesJson = root.optJSONObject("surfaces") ?: JSONObject()
            val surfaces = LinkedHashMap<String, SurfaceRule>()
            val keys = surfacesJson.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val obj = surfacesJson.optJSONObject(id) ?: continue
                surfaces[id] = SurfaceRule(
                    id = id,
                    coreIdHints = stringList(obj, "coreIdHints"),
                    idHints = stringList(obj, "idHints"),
                    negativeIdHints = stringList(obj, "negativeIdHints"),
                    pausedWhenIdPresent = obj.optString("pausedWhenIdPresent").takeUnless(String::isEmpty),
                    windowActivityPatterns = stringList(obj, "windowActivityPatterns"),
                    textOrDescHints = stringList(obj, "textOrDescHints"),
                    notes = obj.optString("notes"),
                    samples = samples(obj.optJSONArray("samples")),
                )
            }
            return DetectionRules(
                schemaVersion = root.optInt("schemaVersion", 1),
                packageNames = stringList(root, "packageNames"),
                igVersionName = root.optString("igVersionName"),
                igVersionCode = root.optLong("igVersionCode", 0L),
                source = root.optString("source"),
                notes = root.optString("notes"),
                surfaces = surfaces,
            )
        }

        private fun samples(array: org.json.JSONArray?): List<DetectionSample> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                DetectionSample(
                    label = obj.optString("label"),
                    presentIds = stringList(obj, "presentIds"),
                    expectPlaying = obj.optBoolean("expectPlaying", true),
                )
            }
        }

        private fun stringList(obj: JSONObject, key: String): List<String> =
            obj.optJSONArray(key)?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeUnless(String::isEmpty) }
            } ?: emptyList()
    }
}
