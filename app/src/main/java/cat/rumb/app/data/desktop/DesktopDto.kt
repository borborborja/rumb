package cat.rumb.app.data.desktop

import cat.rumb.app.data.tracks.Calories
import cat.rumb.app.data.tracks.DifficultyCalculator
import cat.rumb.app.data.tracks.FollowTrackEntity
import cat.rumb.app.data.tracks.Record
import cat.rumb.app.data.tracks.TrackSample
import cat.rumb.app.data.tracks.TrackStats
import cat.rumb.app.data.tracks.WeekBucket
import kotlinx.serialization.Serializable

/**
 * JSON transfer objects for the desktop web API. Dedicated DTOs keep the domain models free of
 * serialization concerns; mappers below build them from the existing pure calculators.
 */

@Serializable
data class TrackDto(
    val id: Long,
    val name: String,
    val kind: String,
    val collection: String,
    val activityType: String?,
    val municipality: String?,
    val distanceKm: Double,
    val ascentM: Int,
    val pointCount: Int,
    val createdAt: Long,
    val durationMs: Long?,
    val difficulty: String,
    val isCompetition: Boolean,
    val archived: Boolean,
)

@Serializable
data class StatsDto(
    val distanceKm: Double,
    val totalTimeS: Long?,
    val movingTimeS: Long?,
    val avgSpeedKmh: Double?,
    val maxSpeedKmh: Double?,
    val ascentM: Int,
    val descentM: Int,
    val avgHr: Int?,
    val maxHr: Int?,
    val avgCadence: Int?,
    val avgPower: Int?,
    val kcal: Int,
)

@Serializable
data class SampleDto(
    val distM: Double,
    val lat: Double,
    val lon: Double,
    val ele: Float?,
    val speedKmh: Float?,
    val hr: Float?,
)

@Serializable
data class TrackDetailDto(val track: TrackDto, val stats: StatsDto, val samples: List<SampleDto>)

@Serializable
data class RecordDto(val kind: String, val valueMs: Long?, val value: Double?, val trackId: Long, val trackName: String, val dateMs: Long)

@Serializable
data class WeekDto(val startEpochDay: Long, val km: Double, val hours: Double, val ascentM: Int, val count: Int)

@Serializable
data class ProgressDto(
    val weeks: List<WeekDto>,
    val streakWeeks: Int,
    val totalKm: Double,
    val totalHours: Double,
    val totalAscentM: Int,
    val totalCount: Int,
)

@Serializable
data class CompetitionSummaryDto(
    val id: Long,
    val name: String,
    val type: String,
    val activityType: String?,
    val bestMs: Long?,
    val attemptCount: Int,
)

@Serializable
data class GapDto(val distM: Double, val gapSeconds: Double)

@Serializable
data class AttemptDto(
    val id: Long,
    val dateMs: Long,
    val timeMs: Long,
    val distanceM: Double,
    val avgHr: Int?,
    val gapMs: Long,
    val isBest: Boolean,
)

@Serializable
data class CompetitionDetailDto(val id: Long, val name: String, val type: String, val attempts: List<AttemptDto>, val gap: List<GapDto>)

@Serializable
data class WaypointDto(val lat: Double, val lng: Double)

@Serializable
data class CreateRouteRequest(val name: String, val profile: String, val waypoints: List<WaypointDto>)

@Serializable
data class OkDto(val ok: Boolean, val id: Long? = null, val error: String? = null)

@Serializable
data class ProfileDto(val id: String, val label: String)

@Serializable
data class LocationDto(val lat: Double, val lng: Double)

/** Live preview of a drawn route: the snapped (magnetized) polyline + running distance/ascent. */
@Serializable
data class RoutePreviewDto(val points: List<WaypointDto>, val distanceM: Double, val ascentM: Double)

// --- Mappers ---

fun FollowTrackEntity.toDto(): TrackDto = TrackDto(
    id = id,
    name = name,
    kind = kind,
    collection = collection,
    activityType = activityType,
    municipality = municipality?.takeIf { it.isNotBlank() },
    distanceKm = distanceMeters / 1000.0,
    ascentM = ascentM.toInt(),
    pointCount = pointCount,
    createdAt = createdAt,
    durationMs = durationMs,
    difficulty = DifficultyCalculator.bandOf(distanceMeters, ascentM).name,
    isCompetition = isCompetition,
    archived = archived,
)

fun TrackStats.toDto(activityType: String?, weightKg: Int): StatsDto = StatsDto(
    distanceKm = distanceM / 1000.0,
    totalTimeS = totalTime?.seconds,
    movingTimeS = movingTime?.seconds,
    avgSpeedKmh = avgSpeedKmh,
    maxSpeedKmh = maxSpeedKmh,
    ascentM = ascentM.toInt(),
    descentM = descentM.toInt(),
    avgHr = avgHr?.toInt(),
    maxHr = maxHr?.toInt(),
    avgCadence = avgCadence?.toInt(),
    avgPower = avgPower?.toInt(),
    kcal = Calories.kcal(activityType, weightKg, movingTime ?: totalTime),
)

fun TrackSample.toDto(): SampleDto = SampleDto(distM, lat, lon, elevation, speedKmh, hr)

fun Record.toDto(): RecordDto = RecordDto(kind.name, valueMs, value, trackId, trackName, dateMs)

fun WeekBucket.toDto(): WeekDto = WeekDto(startEpochDay, km, hours, ascentM.toInt(), count)
