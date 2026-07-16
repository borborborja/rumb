package cat.rumb.app.data.recording

import kotlinx.serialization.Serializable

/** Where a lap boundary was placed during recording. Laps are orthogonal to pause-segments. */
enum class LapMarkType { START, SPLIT, END, ABORT }

/**
 * A lap boundary captured live: the point [seq] where the boundary lands, plus the running
 * distance/elapsed at that instant (so lap stats and crash-recovery need no recomputation).
 * START opens lap 1 (everything before is approach), SPLIT closes a lap and opens the next, END
 * closes the lap block (what follows is the return, not a lap), ABORT closes a lap that was
 * abandoned before going round and opens a fresh attempt at it (the abandoned stretch is not a lap).
 */
@Serializable
data class LapMark(
    val seq: Long,
    val distanceM: Double,
    val totalMs: Long,
    val type: LapMarkType,
)

/** Marks that open a lap: the two that close one open the next, and ABORT restarts the abandoned. */
val LapMarkType.opensLap: Boolean
    get() = this == LapMarkType.START || this == LapMarkType.SPLIT || this == LapMarkType.ABORT
