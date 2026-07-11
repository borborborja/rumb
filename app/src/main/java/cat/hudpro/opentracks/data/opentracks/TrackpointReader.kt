package cat.hudpro.opentracks.data.opentracks

import android.content.ContentResolver
import android.net.Uri
import cat.hudpro.opentracks.data.opentracks.model.GeoPoint
import cat.hudpro.opentracks.data.opentracks.model.TRACKPOINT_TYPE_PAUSE
import cat.hudpro.opentracks.data.opentracks.model.TRACKPOINT_TYPE_TRACKPOINT
import cat.hudpro.opentracks.data.opentracks.model.Trackpoint
import cat.hudpro.opentracks.data.opentracks.model.TrackpointsBySegments
import cat.hudpro.opentracks.data.opentracks.model.TrackpointsDebug
import java.time.Instant

private const val PAUSE_LATITUDE: Double = 100.0

/**
 * Reads trackpoints from the OpenTracks content provider and splits them into segments.
 * Ported from OSMDashboard; lat/lon stored as integer microdegrees (÷ 1e6).
 */
object TrackpointReader {
    const val ID = "_id"
    const val TRACKID = "trackid"
    const val LATITUDE = "latitude"
    const val LONGITUDE = "longitude"
    const val TIME = "time"
    const val TYPE = "type"
    const val SPEED = "speed"

    // Per-point sensor/measurement columns. OpenTracks' provider does not filter the projection,
    // so a dashboard may request any table column; they are null when the data wasn't recorded.
    const val ELEVATION = "elevation" // altitude in meters
    const val HEARTRATE = "sensor_heartrate" // bpm
    const val CADENCE = "sensor_cadence" // rpm
    const val POWER = "sensor_power" // watts
    const val BEARING = "bearing" // degrees

    private val SENSOR_COLUMNS = arrayOf(ELEVATION, HEARTRATE, CADENCE, POWER, BEARING)

    val PROJECTION_V1 = arrayOf(ID, TRACKID, LATITUDE, LONGITUDE, TIME, SPEED) + SENSOR_COLUMNS
    val PROJECTION_V2 = arrayOf(ID, TRACKID, LATITUDE, LONGITUDE, TIME, TYPE, SPEED) + SENSOR_COLUMNS

    fun readTrackpointsBySegments(
        resolver: ContentResolver,
        data: Uri,
        lastId: Long?,
        protocolVersion: Int,
    ): TrackpointsBySegments {
        val debug = TrackpointsDebug(protocolVersion = protocolVersion)
        var segment = mutableListOf<Trackpoint>()
        val segments = mutableListOf<MutableList<Trackpoint>>()
        var projection = PROJECTION_V2
        var typeQuery = " AND $TYPE IN (-2, -1, 0, 1, 3)"
        if (protocolVersion < 2) { // fallback to old Dashboard API
            projection = PROJECTION_V1
            typeQuery = ""
        }
        resolver.query(
            data,
            projection,
            "$ID> ?$typeQuery",
            arrayOf((lastId ?: 0L).toString()),
            null,
        ).use { cursor ->
            var lastTrackpoint: Trackpoint? = null
            while (cursor?.moveToNext() == true) {
                debug.trackpointsReceived++
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(ID))
                val trackId = cursor.getLong(cursor.getColumnIndexOrThrow(TRACKID))
                val latitude =
                    cursor.getInt(cursor.getColumnIndexOrThrow(LATITUDE)) / APIConstants.LAT_LON_FACTOR
                val longitude =
                    cursor.getInt(cursor.getColumnIndexOrThrow(LONGITUDE)) / APIConstants.LAT_LON_FACTOR
                val speed = cursor.getDouble(cursor.getColumnIndexOrThrow(SPEED))
                val time = Instant.ofEpochMilli(cursor.getLong(cursor.getColumnIndexOrThrow(TIME)))

                var type: Int = TRACKPOINT_TYPE_TRACKPOINT
                val typeIndex = cursor.getColumnIndex(TYPE)
                if (typeIndex > -1) {
                    type = cursor.getInt(typeIndex)
                }

                if (lastTrackpoint == null || lastTrackpoint.trackId != trackId) {
                    if (segment.isNotEmpty()) {
                        segments.add(segment)
                    }
                    segment = mutableListOf()
                }

                val latLong = if (MapUtils.isValid(latitude, longitude)) {
                    GeoPoint(latitude, longitude)
                } else {
                    null
                }

                if (latLong != null) {
                    lastTrackpoint = Trackpoint(
                        trackId = trackId,
                        id = id,
                        latLong = latLong,
                        type = type,
                        speed = speed,
                        time = time,
                        altitude = cursor.optDouble(ELEVATION),
                        heartRate = cursor.optDouble(HEARTRATE),
                        cadence = cursor.optDouble(CADENCE),
                        power = cursor.optDouble(POWER),
                        bearing = cursor.optDouble(BEARING),
                    )
                    segment.add(lastTrackpoint)
                } else if (type == TRACKPOINT_TYPE_PAUSE || latitude == PAUSE_LATITUDE) {
                    debug.trackpointsPause++
                    if (segment.isNotEmpty()) {
                        val previousTrackpoint = segment.last()
                        segment.add(
                            Trackpoint(
                                trackId = trackId,
                                id = id,
                                latLong = previousTrackpoint.latLong,
                                type = TRACKPOINT_TYPE_PAUSE,
                                speed = speed,
                                time = time,
                            ),
                        )
                    }
                    lastTrackpoint = null
                } else {
                    debug.trackpointsInvalid++
                }
            }
        }
        if (segment.isNotEmpty()) {
            segments.add(segment)
        }
        debug.segments = segments.size

        return TrackpointsBySegments(segments, debug)
    }

    /** Reads a nullable Double column by name; returns null if the column is absent or the value null. */
    private fun android.database.Cursor.optDouble(column: String): Double? {
        val idx = getColumnIndex(column)
        if (idx < 0 || isNull(idx)) return null
        return getDouble(idx)
    }
}
