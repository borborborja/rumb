package cat.hudpro.opentracks.viewer.audio

/** Interval configuration for progress announcements. */
data class AnnounceConfig(
    val byDistance: Boolean = true,
    val distanceStepKm: Double = 1.0,
    val byTime: Boolean = false,
    val timeStepMin: Int = 10,
)

/**
 * Decides when a progress announcement should fire, by distance and/or time milestones. Pure and
 * unit-testable: feed it the current cumulative distance/elapsed time and it returns the milestones
 * crossed since the last call (never repeating), including the split pace of the last distance step.
 */
class AnnouncementScheduler(private val config: AnnounceConfig) {

    enum class Reason { DISTANCE, TIME }

    data class Trigger(
        val reason: Reason,
        val distanceKm: Double,
        val elapsedSeconds: Long,
        /** Pace (min/km) over the last distance step; null for time triggers. */
        val splitPaceMinPerKm: Double?,
    )

    private var distanceSteps = 0
    private var timeSteps = 0
    private var lastStepElapsedSec = 0L

    fun update(distanceKm: Double, elapsedSeconds: Long): List<Trigger> {
        val out = mutableListOf<Trigger>()

        if (config.byDistance && config.distanceStepKm > 0) {
            while (distanceKm >= (distanceSteps + 1) * config.distanceStepKm) {
                distanceSteps++
                val milestoneKm = distanceSteps * config.distanceStepKm
                val dtSec = elapsedSeconds - lastStepElapsedSec
                val split = if (dtSec > 0 && config.distanceStepKm > 0) {
                    (dtSec / 60.0) / config.distanceStepKm
                } else {
                    null
                }
                lastStepElapsedSec = elapsedSeconds
                out.add(Trigger(Reason.DISTANCE, milestoneKm, elapsedSeconds, split))
            }
        }

        if (config.byTime && config.timeStepMin > 0) {
            val stepSec = config.timeStepMin * 60L
            while (elapsedSeconds >= (timeSteps + 1) * stepSec) {
                timeSteps++
                out.add(Trigger(Reason.TIME, distanceKm, timeSteps * stepSec, null))
            }
        }
        return out
    }

    fun reset() {
        distanceSteps = 0
        timeSteps = 0
        lastStepElapsedSec = 0L
    }
}
