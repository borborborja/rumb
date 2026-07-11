package cat.hudpro.opentracks.data.tracks

import android.content.ContentResolver
import android.net.Uri
import cat.hudpro.opentracks.data.gpx.Gpx
import cat.hudpro.opentracks.data.gpx.GpxPoint
import cat.hudpro.opentracks.data.opentracks.model.GeoPoint
import cat.hudpro.opentracks.viewer.hud.MetricsCalculator
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

    suspend fun insertRoute(
        name: String,
        points: List<GpxPoint>,
        source: TrackSource,
        remoteId: Long?,
    ): Long = withContext(Dispatchers.IO) {
        val gpxText = Gpx.write(name, points)
        dao.insert(
            FollowTrackEntity(
                name = name,
                source = source,
                distanceMeters = routeDistance(points.map { it.toGeoPoint() }),
                pointCount = points.size,
                createdAt = now(),
                gpx = gpxText,
                remoteId = remoteId,
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

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) { dao.delete(id) }

    suspend fun knownEndurainIds(): Set<Long> = withContext(Dispatchers.IO) { dao.knownRemoteIds().toSet() }

    companion object {
        fun routeDistance(points: List<GeoPoint>): Double {
            var acc = 0.0
            for (i in 1 until points.size) acc += MetricsCalculator.distanceMeters(points[i - 1], points[i])
            return acc
        }
    }
}
