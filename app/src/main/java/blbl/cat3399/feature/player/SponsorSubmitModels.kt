package blbl.cat3399.feature.player

internal const val SPONSOR_SUBMIT_MIN_SEGMENT_MS: Long = 500L
internal const val SPONSOR_SUBMIT_MAX_SEGMENTS: Int = 10

internal enum class SponsorSubmitInteractionMode {
    MARK,
    DELETE,
    MOVE,
}

internal enum class SponsorSubmitMarkerKind {
    START,
    END,
}

internal data class SponsorSubmitMarker(
    val id: Long,
    val pairId: Long,
    val kind: SponsorSubmitMarkerKind,
    val timeMs: Long,
)

internal data class SponsorSubmitSegmentDraft(
    val pairId: Long,
    val start: SponsorSubmitMarker?,
    val end: SponsorSubmitMarker?,
) {
    val isComplete: Boolean
        get() = start != null && end != null && end.timeMs - start.timeMs >= SPONSOR_SUBMIT_MIN_SEGMENT_MS
}

internal data class SponsorSubmitSegmentForUpload(
    val startMs: Long,
    val endMs: Long,
)

internal sealed interface SponsorSubmitMarkResult {
    data class Placed(val marker: SponsorSubmitMarker) : SponsorSubmitMarkResult
    data object NoCapacity : SponsorSubmitMarkResult
    data object EndBeforeStart : SponsorSubmitMarkResult
}

internal class SponsorSubmitDraftState(
    maxSegments: Int = SPONSOR_SUBMIT_MAX_SEGMENTS,
) {
    val maxSegments: Int = maxSegments.coerceAtLeast(1)

    private val markers = ArrayList<SponsorSubmitMarker>()
    private var nextMarkerId = 1L
    private var nextPairId = 1L

    fun clear() {
        markers.clear()
        nextMarkerId = 1L
        nextPairId = 1L
    }

    fun markerCount(): Int = markers.size

    fun hasAnyMarker(): Boolean = markers.isNotEmpty()

    fun orderedMarkers(): List<SponsorSubmitMarker> =
        markers.sortedWith(compareBy<SponsorSubmitMarker> { it.timeMs }.thenBy { it.kind.ordinal }.thenBy { it.id })

    fun completeSegments(): List<SponsorSubmitSegmentForUpload> =
        drafts()
            .filter { it.isComplete }
            .mapNotNull { draft ->
                val start = draft.start ?: return@mapNotNull null
                val end = draft.end ?: return@mapNotNull null
                SponsorSubmitSegmentForUpload(
                    startMs = start.timeMs,
                    endMs = end.timeMs,
                )
            }

    fun drafts(): List<SponsorSubmitSegmentDraft> {
        if (markers.isEmpty()) return emptyList()
        val byPair = LinkedHashMap<Long, MutableList<SponsorSubmitMarker>>()
        markers.sortedBy { it.pairId }.forEach { marker ->
            byPair.getOrPut(marker.pairId) { ArrayList(2) }.add(marker)
        }
        return byPair.map { (pairId, pairMarkers) ->
            SponsorSubmitSegmentDraft(
                pairId = pairId,
                start = pairMarkers.firstOrNull { it.kind == SponsorSubmitMarkerKind.START },
                end = pairMarkers.firstOrNull { it.kind == SponsorSubmitMarkerKind.END },
            )
        }
    }

    fun placeMarker(timeMs: Long, durationMs: Long): SponsorSubmitMarkResult {
        val clamped = clampToDuration(timeMs, durationMs)
        val incomplete = drafts().firstOrNull { it.start != null && it.end == null }
        if (incomplete != null) {
            val start = incomplete.start ?: return SponsorSubmitMarkResult.NoCapacity
            if (clamped - start.timeMs < SPONSOR_SUBMIT_MIN_SEGMENT_MS) {
                return SponsorSubmitMarkResult.EndBeforeStart
            }
            val marker =
                SponsorSubmitMarker(
                    id = nextMarkerId++,
                    pairId = incomplete.pairId,
                    kind = SponsorSubmitMarkerKind.END,
                    timeMs = clamped,
                )
            markers.add(marker)
            return SponsorSubmitMarkResult.Placed(marker)
        }

        if (drafts().size >= maxSegments) return SponsorSubmitMarkResult.NoCapacity
        val marker =
            SponsorSubmitMarker(
                id = nextMarkerId++,
                pairId = nextPairId++,
                kind = SponsorSubmitMarkerKind.START,
                timeMs = clamped,
            )
        markers.add(marker)
        return SponsorSubmitMarkResult.Placed(marker)
    }

    fun markerById(id: Long?): SponsorSubmitMarker? {
        if (id == null) return null
        return markers.firstOrNull { it.id == id }
    }

    fun nearestMarkerAt(timeMs: Long, toleranceMs: Long): SponsorSubmitMarker? {
        val tolerance = toleranceMs.coerceAtLeast(0L)
        return markers
            .map { marker -> marker to kotlin.math.abs(marker.timeMs - timeMs) }
            .filter { (_, distance) -> distance <= tolerance }
            .minByOrNull { (_, distance) -> distance }
            ?.first
    }

    fun moveMarker(markerId: Long, timeMs: Long, durationMs: Long): Boolean {
        val index = markers.indexOfFirst { it.id == markerId }
        if (index < 0) return false
        val current = markers[index]
        val pair = markers.filter { it.pairId == current.pairId && it.id != current.id }
        val clampedRaw = clampToDuration(timeMs, durationMs)
        val clamped =
            when (current.kind) {
                SponsorSubmitMarkerKind.START -> {
                    val end = pair.firstOrNull { it.kind == SponsorSubmitMarkerKind.END }
                    if (end != null) clampedRaw.coerceAtMost((end.timeMs - SPONSOR_SUBMIT_MIN_SEGMENT_MS).coerceAtLeast(0L)) else clampedRaw
                }
                SponsorSubmitMarkerKind.END -> {
                    val start = pair.firstOrNull { it.kind == SponsorSubmitMarkerKind.START }
                    if (start != null) clampedRaw.coerceAtLeast(start.timeMs + SPONSOR_SUBMIT_MIN_SEGMENT_MS) else clampedRaw
                }
            }.let { clampToDuration(it, durationMs) }

        if (clamped == current.timeMs) return false
        markers[index] = current.copy(timeMs = clamped)
        return true
    }

    fun deleteMarkerOrPair(markerId: Long?): Boolean {
        val marker = markerById(markerId) ?: return false
        val pairMarkers = markers.filter { it.pairId == marker.pairId }
        return if (pairMarkers.size >= 2) {
            markers.removeAll { it.pairId == marker.pairId }
        } else {
            markers.removeAll { it.id == marker.id }
        }
    }

    fun deleteCompleteSegments(): Boolean {
        val completePairIds = drafts().filter { it.isComplete }.mapTo(HashSet()) { it.pairId }
        if (completePairIds.isEmpty()) return false
        return markers.removeAll { it.pairId in completePairIds }
    }

    private fun clampToDuration(timeMs: Long, durationMs: Long): Long {
        val duration = durationMs.coerceAtLeast(0L)
        return if (duration > 0L) timeMs.coerceIn(0L, duration) else timeMs.coerceAtLeast(0L)
    }
}

internal data class SponsorSubmitPanelState(
    val draft: SponsorSubmitDraftState = SponsorSubmitDraftState(),
) {
    var mode: SponsorSubmitInteractionMode = SponsorSubmitInteractionMode.MARK
    var cursorMs: Long = 0L
    var selectedMarkerId: Long? = null
    var movingMarkerId: Long? = null
    var wasPlayingBeforeOpen: Boolean = false
    var submitting: Boolean = false

    fun reset(positionMs: Long, wasPlaying: Boolean) {
        draft.clear()
        mode = SponsorSubmitInteractionMode.MARK
        cursorMs = positionMs.coerceAtLeast(0L)
        selectedMarkerId = null
        movingMarkerId = null
        wasPlayingBeforeOpen = wasPlaying
        submitting = false
    }
}
