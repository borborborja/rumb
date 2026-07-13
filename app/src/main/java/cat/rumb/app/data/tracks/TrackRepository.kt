package cat.rumb.app.data.tracks

import android.content.ContentResolver
import android.net.Uri
import cat.rumb.app.data.gpx.Gpx
import cat.rumb.app.data.gpx.GpxPoint
import cat.rumb.app.data.opentracks.model.GeoPoint
import cat.rumb.app.viewer.hud.MetricsCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Manages the library of followable routes (imported GPX or synced from Endurain). */
class TrackRepository(
    private val dao: FollowTrackDao,
    private val contentResolver: ContentResolver,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    fun observeSummaries(): Flow<List<FollowTrackEntity>> = dao.observeSummaries()

    suspend fun importGpx(uri: Uri, fallbackName: String): Long = withContext(Dispatchers.IO) {
        val route = contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open $uri" }
            Gpx.read(input)
        }
        val name = route.name?.takeIf { it.isNotBlank() } ?: fallbackName
        insertRoute(name, route.points, TrackSource.GPX_IMPORT, remoteId = null)
    }

    /**
     * Imports any supported track file (GPX/KML/KMZ/TCX, detected by [fileName]'s extension) as a
     * [kind] (route to follow or training). Throws IllegalArgumentException on unsupported formats.
     */
    suspend fun importAny(
        uri: Uri,
        fileName: String?,
        fallbackName: String,
        kind: String,
        collection: String = "General",
        activityType: String? = null,
    ): Long =
        withContext(Dispatchers.IO) {
            val route = contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open $uri" }
                when (cat.rumb.app.data.gpx.formatFor(fileName)) {
                    cat.rumb.app.data.gpx.TrackFormat.GPX -> Gpx.read(input)
                    cat.rumb.app.data.gpx.TrackFormat.KML -> cat.rumb.app.data.gpx.Kml.read(input)
                    cat.rumb.app.data.gpx.TrackFormat.KMZ -> cat.rumb.app.data.gpx.Kml.readKmz(input)
                    cat.rumb.app.data.gpx.TrackFormat.TCX -> cat.rumb.app.data.gpx.Tcx.read(input)
                    cat.rumb.app.data.gpx.TrackFormat.UNSUPPORTED ->
                        throw IllegalArgumentException("Format no suportat: ${fileName ?: "?"}")
                }
            }
            require(route.points.size >= 2) { "El fitxer no conté cap track" }
            val name = route.name?.takeIf { it.isNotBlank() } ?: fallbackName
            insertRoute(
                name, route.points, TrackSource.GPX_IMPORT, remoteId = null, kind = kind,
                collection = collection, activityType = activityType,
            )
        }

    suspend fun insertRoute(
        name: String,
        points: List<GpxPoint>,
        source: TrackSource,
        remoteId: Long?,
        kind: String = TrackKind.ROUTE,
        collection: String = "General",
        activityType: String? = null,
        competitionRefId: Long? = null,
    ): Long = withContext(Dispatchers.IO) {
        val gpxText = Gpx.write(name, points)
        dao.insert(
            FollowTrackEntity(
                name = name,
                collection = collection,
                source = source,
                distanceMeters = routeDistance(points.map { it.toGeoPoint() }),
                pointCount = points.size,
                createdAt = now(),
                gpx = gpxText,
                remoteId = remoteId,
                kind = kind,
                activityType = activityType,
                ascentM = ascent(points),
                startLat = points.firstOrNull()?.latitude,
                startLon = points.firstOrNull()?.longitude,
                metaDone = true,
                competitionRefId = competitionRefId,
                durationMs = durationMs(points),
            ),
        )
    }

    suspend fun renameCollection(oldName: String, newName: String, kind: String) =
        withContext(Dispatchers.IO) { dao.renameCollection(oldName, newName, kind) }

    /** Loads the full entity (including the GPX blob) for the detail/edit screens. */
    suspend fun get(id: Long): FollowTrackEntity? = withContext(Dispatchers.IO) { dao.getById(id) }

    suspend fun rename(id: Long, name: String) = withContext(Dispatchers.IO) { dao.rename(id, name) }

    suspend fun setCollection(id: Long, collection: String) =
        withContext(Dispatchers.IO) { dao.setCollection(id, collection) }

    suspend fun setActivityType(id: Long, type: String?) =
        withContext(Dispatchers.IO) { dao.setActivityType(id, type) }

    /** Marks/unmarks a track as a competition reference. */
    suspend fun setCompetition(id: Long, flag: Boolean) =
        withContext(Dispatchers.IO) { dao.setCompetition(id, flag) }

    /** Archives/unarchives a single track (kept intact, incl. its folder). */
    suspend fun setArchived(id: Long, flag: Boolean) =
        withContext(Dispatchers.IO) { dao.setArchived(id, flag) }

    /** Archives/unarchives a whole competition; membership (attempt links) is preserved. */
    suspend fun setCompetitionArchived(refId: Long, flag: Boolean) =
        withContext(Dispatchers.IO) { dao.setCompetitionArchived(refId, flag) }

    /** Removes one attempt from its competition; the track itself stays in the library. */
    suspend fun removeFromCompetition(id: Long) =
        withContext(Dispatchers.IO) { dao.clearCompetitionRef(id) }

    /** Deletes a competition (unflags the reference, unlinks attempts). All tracks stay. */
    suspend fun dissolveCompetition(refId: Long) = withContext(Dispatchers.IO) {
        dao.unlinkAttempts(refId)
        dao.setCompetition(refId, false)
        dao.setCompetitionArchived(refId, false)
    }

    /** Existing folder names (collections) for [kind], for pickers outside the manager. */
    suspend fun collections(kind: String): List<String> =
        withContext(Dispatchers.IO) { dao.collections(kind) }

    /** Copies a saved training into the routes-to-follow tab; returns the new id. */
    suspend fun duplicateAsRoute(id: Long): Long? = withContext(Dispatchers.IO) {
        val existing = dao.getById(id) ?: return@withContext null
        dao.insert(
            existing.copy(
                id = 0,
                kind = TrackKind.ROUTE,
                collection = "General",
                createdAt = now(),
                remoteId = null,
                // A route copy is fully independent of the library original: it carries no
                // competition membership and no archive state of its own.
                isCompetition = false,
                competitionRefId = null,
                competitionArchived = false,
                archived = false,
            ),
        )
    }

    /** Overwrites the geometry (and name) of an existing route, preserving its other metadata. */
    suspend fun updateRoute(id: Long, name: String, points: List<GpxPoint>) = withContext(Dispatchers.IO) {
        val existing = dao.getById(id) ?: return@withContext
        dao.insert(
            existing.copy(
                name = name,
                distanceMeters = routeDistance(points.map { it.toGeoPoint() }),
                pointCount = points.size,
                gpx = Gpx.write(name, points),
                ascentM = ascent(points),
                startLat = points.firstOrNull()?.latitude,
                startLon = points.firstOrNull()?.longitude,
                metaDone = true,
                municipality = null,
            ),
        )
    }

    suspend fun loadRoute(id: Long): List<GeoPoint> = withContext(Dispatchers.IO) {
        loadGpxRoute(id).map { it.toGeoPoint() }
    }

    /** Loads the full route points including elevation/time (for the elevation profile). */
    suspend fun loadGpxRoute(id: Long): List<GpxPoint> = withContext(Dispatchers.IO) {
        val entity = dao.getById(id) ?: return@withContext emptyList()
        entity.gpx.byteInputStream().use { Gpx.read(it) }.points
    }

    /** Bounding box (with margin) enclosing the route, for the "download this route's map" flow. */
    suspend fun routeBoundingBox(id: Long): cat.rumb.app.data.map.BoundingBox? =
        withContext(Dispatchers.IO) {
            cat.rumb.app.data.map.RouteCoverageCalculator.boundingBox(loadRoute(id))
        }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) { dao.delete(id) }

    suspend fun knownEndurainIds(): Set<Long> = withContext(Dispatchers.IO) { dao.knownRemoteIds().toSet() }

    companion object {
        fun routeDistance(points: List<GeoPoint>): Double {
            var acc = 0.0
            for (i in 1 until points.size) acc += MetricsCalculator.distanceMeters(points[i - 1], points[i])
            return acc
        }

        /** Total elapsed ms between the first and last timed points; 0 when the track is untimed. */
        fun durationMs(points: List<GpxPoint>): Long {
            val first = points.firstOrNull { it.time != null }?.time
            val last = points.lastOrNull { it.time != null }?.time
            return if (first != null && last != null && last > first) {
                java.time.Duration.between(first, last).toMillis()
            } else {
                0L
            }
        }

        /** Cumulative positive elevation change (m) along the route; 0 if elevations are missing. */
        fun ascent(points: List<GpxPoint>): Double {
            var gain = 0.0
            var prev: Double? = null
            for (p in points) {
                val e = p.elevation ?: continue
                prev?.let { if (e > it) gain += e - it }
                prev = e
            }
            return gain
        }
    }
}
