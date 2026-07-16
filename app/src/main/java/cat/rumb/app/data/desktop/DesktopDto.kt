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

// --- Settings ---

@Serializable
data class SettingsDto(
    val units: UnitsDto,
    val profile: ProfileSettingsDto,
    val recording: RecordingDto,
    val map: MapSettingsDto,
    val audio: AudioDto,
    val general: GeneralDto,
    val endurain: EndurainDto,
    val webdav: WebDavDto,
    val folder: FolderDto,
    val sync: SyncStatusDto,
)

@Serializable data class UnitsDto(val distanceUnit: String, val elevationUnit: String, val speedUnit: String)
@Serializable data class ProfileSettingsDto(val userMaxHr: Int, val userWeightKg: Int, val userAge: Int, val userSex: String)
@Serializable data class RecordingDto(
    val recGpsIntervalSec: Int, val recMinDistanceM: Float, val recMaxAccuracyM: Float,
    val recAutoPause: Boolean, val recAutoPauseSec: Int, val recBarometer: Boolean,
    val lapManagementEnabled: Boolean, val autoLapByPosition: Boolean,
)
@Serializable data class MapSettingsDto(
    val mapCacheSizeMb: Int, val prefetchOnFollow: Boolean, val trackColorMode: String?, val trackColor: String,
    val followColor: String, val followWidth: Float, val followArrows: Boolean, val followProgress: Boolean,
    val trackingPointStyle: String, val trackingPointColor: String, val trackingPointSize: Float,
    val offRouteThresholdM: Int, val offRouteSound: Boolean, val offRouteVibrate: Boolean, val offRouteSpoken: Boolean,
)
@Serializable data class AudioDto(
    val announceEnabled: Boolean, val announceMode: String, val announceLang: String, val announceBeepSound: Int,
    val turnHeadsUp: Boolean, val turnVoice: Boolean, val announceByDistance: Boolean, val announceDistanceKm: Float,
    val announceByTime: Boolean, val announceTimeMin: Int, val annDistanceTime: Boolean, val annPace: Boolean,
    val annSplitPace: Boolean, val annElevation: Boolean, val annHeartRate: Boolean,
)
@Serializable data class GeneralDto(
    val keepScreenOn: Boolean, val fullscreen: Boolean, val adaptiveZoom: Boolean, val mapOrientation: String,
    val recCountdown: Boolean, val competitionHalo: Boolean, val competitionShowSeconds: Boolean, val desktopServerPort: Int,
)
// --- Map management ---

@Serializable data class MapSourceDto(
    val id: String, val displayName: String, val attribution: String, val maxZoom: Int, val offlineAllowed: Boolean,
    // Per-map display options the SPA applies (except in the route editor). Read by name in JS, so
    // appended at the end — safe for both kotlinx (name-based) and the browser.
    val detailReduction: Int = 0, val grayscale: Boolean = false, val opacity: Float = 1f,
)
@Serializable data class RegionDto(val name: String, val west: Double, val south: Double, val east: Double, val north: Double)
@Serializable data class OfflineMapDto(
    val name: String, val path: String, val sizeBytes: Long, val sourceId: String?,
    val sectors: List<cat.rumb.app.data.map.OfflineSector>,
)
@Serializable data class MapsDto(
    val sources: List<MapSourceDto>,
    val offline: List<OfflineMapDto>,
    val activeBaseMapId: String?,
    val cacheSizeMb: Int,
    val regions: List<RegionDto>,
)
@Serializable data class EstimateDto(val tiles: Long, val mb: Long, val overLimit: Boolean)
@Serializable data class DownloadProgressDto(val state: String, val done: Int, val total: Int, val failed: Int)

@Serializable data class EndurainDto(val host: String?, val apiKeySet: Boolean)
@Serializable data class WebDavDto(val url: String?, val userSet: Boolean)
@Serializable data class FolderDto(val enabled: Boolean, val folderSet: Boolean)
@Serializable data class SyncStatusDto(val pending: Int, val failed: Int, val lastUploadedMs: Long?)

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
