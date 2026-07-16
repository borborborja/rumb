package cat.rumb.app.data.tracks

import cat.rumb.app.data.recording.LapMark
import cat.rumb.app.data.recording.LapMarkType
import cat.rumb.app.data.recording.opensLap
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * What a point range represents within a track that has laps. ABORTED is a lap that was given up
 * before going round: it happened, so it stays in the track, but it is not a lap and never races.
 */
enum class LapKind { APPROACH, LAP, RETURN, ABORTED }

/**
 * A contiguous range of a saved track's points forming one lap (or the approach/return around the
 * lap block). Boundaries are point INDICES into the track's parsed point list, so a lap is a virtual
 * slice — no GPX is duplicated. Editable: shift [startIdx]/[endIdx] to fix a mistimed button press.
 */
@kotlinx.serialization.Serializable
data class LapRange(
    val index: Int,
    val startIdx: Int,
    val endIdx: Int,
    val kind: LapKind,
)

object Laps {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(ranges: List<LapRange>): String = json.encodeToString(ranges)

    fun decode(raw: String?): List<LapRange> =
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<LapRange>>(raw) }.getOrDefault(emptyList())
                // Drop malformed ranges so callers can slice points.subList(startIdx, endIdx) safely.
                .filter { it.startIdx >= 0 && it.endIdx > it.startIdx }
        }

    /**
     * Builds lap ranges from the live [marks] and the saved [pointSeqs] (each recorded point's seq,
     * in save order). A mark's boundary seq maps to the first saved point whose seq >= it. The stretch
     * before the first START is APPROACH; each opening mark opens a range; after END comes the RETURN.
     * A range closed by an ABORT is the abandoned attempt, so it is ABORTED and takes no lap number.
     */
    fun fromMarks(marks: List<LapMark>, pointSeqs: List<Long>): List<LapRange> {
        if (marks.isEmpty() || pointSeqs.isEmpty()) return emptyList()
        fun idxOf(seq: Long): Int {
            val i = pointSeqs.indexOfFirst { it >= seq }
            return if (i < 0) pointSeqs.size else i
        }
        val out = mutableListOf<LapRange>()
        val firstStart = idxOf(marks.first().seq)
        if (firstStart > 0) out.add(LapRange(0, 0, firstStart, LapKind.APPROACH))
        val opens = marks.filter { it.type.opensLap }
        val endMark = marks.lastOrNull { it.type == LapMarkType.END }
        val blockEnd = endMark?.let { idxOf(it.seq) } ?: pointSeqs.size
        var lapNo = 0
        opens.forEachIndexed { i, mark ->
            val start = idxOf(mark.seq)
            val next = opens.getOrNull(i + 1)
            val end = next?.let { idxOf(it.seq) } ?: blockEnd
            if (end <= start) return@forEachIndexed
            val aborted = next?.type == LapMarkType.ABORT
            out.add(
                if (aborted) LapRange(0, start, end, LapKind.ABORTED)
                else LapRange(++lapNo, start, end, LapKind.LAP),
            )
        }
        if (endMark != null && blockEnd < pointSeqs.size) {
            out.add(LapRange(out.size, blockEnd, pointSeqs.size, LapKind.RETURN))
        }
        return out
    }
}
