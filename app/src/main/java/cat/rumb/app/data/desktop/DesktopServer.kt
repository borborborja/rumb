package cat.rumb.app.data.desktop

import android.content.Context
import cat.rumb.app.RumbApplication
import cat.rumb.app.data.competition.CompetitionAnalysis
import cat.rumb.app.data.debug.DebugLog
import cat.rumb.app.data.gpx.Gpx
import cat.rumb.app.data.gpx.Kml
import cat.rumb.app.data.gpx.Tcx
import cat.rumb.app.data.gpx.TrackFormat
import cat.rumb.app.data.gpx.formatFor
import cat.rumb.app.data.opentracks.model.GeoPoint
import cat.rumb.app.data.prefs.ViewerPreferences
import cat.rumb.app.data.tracks.CompetitionType
import cat.rumb.app.data.tracks.PersonalRecords
import cat.rumb.app.data.tracks.ProgressStats
import cat.rumb.app.data.tracks.TrackKind
import cat.rumb.app.data.tracks.TrackSource
import cat.rumb.app.data.tracks.TrackStatsCalculator
import cat.rumb.app.data.routing.RoutingProfile
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Embedded LAN web server for "desktop mode". Serves a self-contained SPA (assets/desktop/) plus a
 * small JSON API backed by the same repository and pure calculators the app uses. Auth: a 4-digit
 * PIN generated per session, exchanged for an in-memory token (cookie). Runs only while the desktop
 * screen is open. All reuse existing logic — no duplicated stats.
 */
class DesktopServer(
    private val context: Context,
    port: Int,
    private val pinProvider: () -> String,
) : NanoHTTPD(port) {

    private val app = RumbApplication.from(context)
    private val prefs = ViewerPreferences.get(context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    // Multiple clients (laptop + phone, or several tabs) can be authorized at once: a single token
    // field would make each fresh PIN entry evict every prior session, bouncing the others to the
    // PIN screen. Keep every issued token valid instead.
    private val tokens = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    override fun serve(session: IHTTPSession): Response {
        return try {
            route(session)
        } catch (e: Exception) {
            DebugLog.e("Desktop", "error servint ${session.uri}", e)
            json(Response.Status.INTERNAL_ERROR, OkDto(false, error = e.message))
        }
    }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri
        // Static SPA (no auth): landing asks for the PIN before hitting the API.
        if (session.method == Method.GET && (uri == "/" || !uri.startsWith("/api/"))) {
            return serveAsset(if (uri == "/") "/index.html" else uri)
        }
        if (uri == "/api/auth" && session.method == Method.POST) return handleAuth(session)
        // Everything else under /api requires a valid session token.
        if (!authorized(session)) return json(Response.Status.UNAUTHORIZED, OkDto(false, error = "auth"))

        // A malformed id (non-numeric, or an unmatched subpath like /api/track/5/foo) must be a clean
        // 404, not an uncaught NumberFormatException surfaced as HTTP 500.
        val notFound = json(Response.Status.NOT_FOUND, OkDto(false, error = "not found"))
        return when {
            uri == "/api/tracks" -> handleTracks(session)
            uri.startsWith("/api/track/") && uri.endsWith("/gpx") -> handleGpx(uri.removePrefix("/api/track/").removeSuffix("/gpx").toLongOrNull() ?: return notFound)
            uri.startsWith("/api/track/") && uri.endsWith("/rename") && session.method == Method.POST ->
                handleRename(uri.removePrefix("/api/track/").removeSuffix("/rename").toLongOrNull() ?: return notFound, session)
            uri.startsWith("/api/track/") && session.method == Method.DELETE ->
                handleDelete(uri.removePrefix("/api/track/").toLongOrNull() ?: return notFound)
            uri.startsWith("/api/track/") -> handleTrackDetail(uri.removePrefix("/api/track/").toLongOrNull() ?: return notFound)
            uri == "/api/records" -> handleRecords()
            uri == "/api/progress" -> handleProgress()
            uri == "/api/competitions" -> handleCompetitions()
            uri == "/api/competition/from-track" && session.method == Method.POST -> handleCreateCompetition(session)
            uri.startsWith("/api/competition/") && uri.endsWith("/rename") && session.method == Method.POST ->
                handleRenameCompetition(uri.removePrefix("/api/competition/").removeSuffix("/rename").toLongOrNull() ?: return notFound, session)
            uri.startsWith("/api/competition/") && session.method == Method.DELETE ->
                handleDeleteCompetition(uri.removePrefix("/api/competition/").toLongOrNull() ?: return notFound)
            uri.startsWith("/api/competition/") -> handleCompetitionDetail(uri.removePrefix("/api/competition/").toLongOrNull() ?: return notFound)
            uri == "/api/profiles" -> handleProfiles()
            uri == "/api/location" -> handleLocation()
            uri == "/api/route/preview" && session.method == Method.POST -> handleRoutePreview(session)
            uri == "/api/import" && session.method == Method.POST -> handleImport(session)
            uri == "/api/route" && session.method == Method.POST -> handleCreateRoute(session)
            else -> json(Response.Status.NOT_FOUND, OkDto(false, error = "not found"))
        }
    }

    // --- Auth ---

    private fun handleAuth(session: IHTTPSession): Response {
        val body = readBody(session)
        val pin = runCatching { json.decodeFromString<Map<String, String>>(body)["pin"] }.getOrNull()
        if (pin != null && pin == pinProvider()) {
            val t = java.util.UUID.randomUUID().toString()
            tokens.add(t)
            val res = json(Response.Status.OK, OkDto(true))
            res.addHeader("Set-Cookie", "rumb_session=$t; Path=/; HttpOnly; SameSite=Strict")
            return res
        }
        return json(Response.Status.UNAUTHORIZED, OkDto(false, error = "pin"))
    }

    private fun authorized(session: IHTTPSession): Boolean {
        if (tokens.isEmpty()) return false
        val cookie = session.headers["cookie"] ?: return false
        return cookie.split(";").any { c ->
            val trimmed = c.trim()
            trimmed.startsWith("rumb_session=") && tokens.contains(trimmed.removePrefix("rumb_session="))
        }
    }

    // --- Read endpoints ---

    private fun handleTracks(session: IHTTPSession): Response {
        val kind = session.parameters["kind"]?.firstOrNull()
        val all = runBlocking { app.trackRepository.observeSummaries().first() }
        val list = all.filter { !it.archived && (kind == null || it.kind == kind) }
            .sortedByDescending { it.createdAt }
            .map { it.toDto() }
        return json(Response.Status.OK, list)
    }

    private fun handleTrackDetail(id: Long): Response {
        val entity = runBlocking { app.trackRepository.get(id) } ?: return json(Response.Status.NOT_FOUND, OkDto(false))
        val points = runBlocking { app.trackRepository.loadGpxRoute(id) }
        val stats = TrackStatsCalculator.compute(points)
        val detail = TrackDetailDto(
            track = entity.toDto(),
            stats = stats.toDto(entity.activityType, prefs.userWeightKg),
            samples = TrackStatsCalculator.samples(points).map { it.toDto() },
        )
        return json(Response.Status.OK, detail)
    }

    private fun handleGpx(id: Long): Response {
        val entity = runBlocking { app.trackRepository.get(id) } ?: return json(Response.Status.NOT_FOUND, OkDto(false))
        val res = newFixedLengthResponse(Response.Status.OK, "application/gpx+xml", entity.gpx)
        res.addHeader("Content-Disposition", "attachment; filename=\"${entity.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}.gpx\"")
        return res
    }

    private fun handleRecords(): Response {
        val all = runBlocking { app.trackRepository.observeSummaries().first() }
        val records = runBlocking { PersonalRecords.compute(all, loadPoints = { app.trackRepository.loadGpxRoute(it) }) }
        return json(Response.Status.OK, records.map { it.toDto() })
    }

    private fun handleProgress(): Response {
        val all = runBlocking { app.trackRepository.observeSummaries().first() }
        val weeks = ProgressStats.weekly(all)
        val totals = ProgressStats.totals(all)
        val dto = ProgressDto(
            weeks = weeks.map { it.toDto() },
            streakWeeks = ProgressStats.streakWeeks(all),
            totalKm = totals.km,
            totalHours = totals.hours,
            totalAscentM = totals.ascentM.toInt(),
            totalCount = totals.count,
        )
        return json(Response.Status.OK, dto)
    }

    private fun handleCompetitions(): Response {
        val list = runBlocking {
            app.competitionRepository.observeCompetitions().first().filter { !it.archived }.map { c ->
                val attempts = app.competitionRepository.attemptsOnce(c.id)
                CompetitionSummaryDto(
                    c.id, c.name, c.type, c.activityType,
                    attempts.filter { it.timeMs > 0 }.minOfOrNull { it.timeMs }, attempts.size,
                )
            }
        }
        return json(Response.Status.OK, list)
    }

    private fun handleCompetitionDetail(id: Long): Response = runBlocking {
        val comp = app.competitionRepository.getCompetition(id) ?: return@runBlocking json(Response.Status.NOT_FOUND, OkDto(false))
        val attempts = app.competitionRepository.attemptsOnce(id) // sorted by time asc from the DAO
        val bestMs = attempts.filter { it.timeMs > 0 }.minOfOrNull { it.timeMs }
        val attemptDtos = attempts.map { a ->
            AttemptDto(
                a.id, a.createdAt, a.timeMs, a.distanceM, a.avgHr?.toInt(),
                gapMs = if (bestMs != null) a.timeMs - bestMs else 0L,
                isBest = bestMs != null && a.timeMs == bestMs,
            )
        }
        // Gap chart: the latest attempt vs the best (parsed from the inline GPX).
        val best = attempts.firstOrNull { it.timeMs == bestMs }
        val latest = attempts.filter { it.id != best?.id }.maxByOrNull { it.createdAt }
        val gap = if (best != null && latest != null) {
            val bestPts = runCatching { Gpx.read(best.gpx.byteInputStream()).points }.getOrDefault(emptyList())
            val latestPts = runCatching { Gpx.read(latest.gpx.byteInputStream()).points }.getOrDefault(emptyList())
            CompetitionAnalysis.gapOverDistance(bestPts, latestPts).map { GapDto(it.distM, it.gapSeconds) }
        } else {
            emptyList()
        }
        json(Response.Status.OK, CompetitionDetailDto(id, comp.name, comp.type, attemptDtos, gap))
    }

    private fun handleCreateCompetition(session: IHTTPSession): Response {
        val body = runCatching { json.decodeFromString<Map<String, String>>(readBody(session)) }.getOrNull()
            ?: return json(Response.Status.BAD_REQUEST, OkDto(false, error = "bad body"))
        val trackId = body["trackId"]?.toLongOrNull() ?: return json(Response.Status.BAD_REQUEST, OkDto(false, error = "trackId"))
        val type = if (body["type"] == CompetitionType.LAP) CompetitionType.LAP else CompetitionType.ROUTE
        val id = runBlocking {
            val track = app.trackRepository.get(trackId) ?: return@runBlocking null
            app.competitionRepository.createFromTrack(trackId, track.name, track.activityType, type, System.currentTimeMillis())
        } ?: return json(Response.Status.BAD_REQUEST, OkDto(false, error = "untimed"))
        return json(Response.Status.OK, OkDto(true, id = id))
    }

    private fun handleRenameCompetition(id: Long, session: IHTTPSession): Response {
        val name = runCatching { json.decodeFromString<Map<String, String>>(readBody(session))["name"] }.getOrNull()
            ?: return json(Response.Status.BAD_REQUEST, OkDto(false))
        runBlocking { app.competitionRepository.rename(id, name) }
        return json(Response.Status.OK, OkDto(true, id = id))
    }

    private fun handleDeleteCompetition(id: Long): Response {
        runBlocking { app.competitionRepository.delete(id) }
        return json(Response.Status.OK, OkDto(true, id = id))
    }

    // --- Write endpoints ---

    private fun handleImport(session: IHTTPSession): Response {
        val name = session.parameters["name"]?.firstOrNull() ?: "Import"
        val kind = session.parameters["kind"]?.firstOrNull() ?: TrackKind.ROUTE
        val fileName = session.parameters["filename"]?.firstOrNull()
        val body = readBody(session)
        val route = when (formatFor(fileName)) {
            TrackFormat.GPX -> Gpx.read(body.byteInputStream())
            TrackFormat.KML -> Kml.read(body.byteInputStream())
            TrackFormat.KMZ -> return json(Response.Status.BAD_REQUEST, OkDto(false, error = "kmz not supported over web"))
            TrackFormat.TCX -> Tcx.read(body.byteInputStream())
            TrackFormat.UNSUPPORTED -> Gpx.read(body.byteInputStream()) // assume GPX text
        }
        if (route.points.size < 2) return json(Response.Status.BAD_REQUEST, OkDto(false, error = "too few points"))
        val id = runBlocking {
            app.trackRepository.insertRoute(
                route.name?.takeIf { it.isNotBlank() } ?: name, route.points,
                TrackSource.GPX_IMPORT, remoteId = null, kind = kind,
            )
        }
        DebugLog.i("Desktop", "import web · $kind · ${route.points.size} punts · id=$id")
        return json(Response.Status.OK, OkDto(true, id = id))
    }

    /** Routing profiles with their localized labels (same set/labels as the mobile app). */
    private fun handleProfiles(): Response {
        val list = RoutingProfile.entries.map { ProfileDto(it.name, context.getString(it.labelRes)) }
        return json(Response.Status.OK, list)
    }

    /** Phone's last known location so the web map can open centered on the current position. */
    private fun handleLocation(): Response {
        val loc = lastKnownLocation() ?: return json(Response.Status.NOT_FOUND, OkDto(false, error = "no location"))
        return json(Response.Status.OK, LocationDto(loc.latitude, loc.longitude))
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun lastKnownLocation(): android.location.Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return null
        for (provider in listOf("fused", android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)) {
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()?.let { return it }
        }
        return null
    }

    /** Snaps the drawn waypoints to trails/roads and returns the polyline + distance/ascent (no save). */
    private fun handleRoutePreview(session: IHTTPSession): Response {
        val req = runCatching { json.decodeFromString<CreateRouteRequest>(readBody(session)) }.getOrNull()
            ?: return json(Response.Status.BAD_REQUEST, OkDto(false, error = "bad body"))
        if (req.waypoints.size < 2) return json(Response.Status.BAD_REQUEST, OkDto(false, error = "need >=2 waypoints"))
        val profile = runCatching { RoutingProfile.valueOf(req.profile) }.getOrDefault(RoutingProfile.HIKING)
        val geoPoints = req.waypoints.map { GeoPoint(it.lat, it.lng) }
        val routed = runBlocking { app.routingRepository.route(geoPoints, profile) }
        val preview = RoutePreviewDto(
            points = routed.points.map { WaypointDto(it.latitude, it.longitude) },
            distanceM = routed.distanceMeters,
            ascentM = routed.ascentMeters,
        )
        return json(Response.Status.OK, preview)
    }

    private fun handleCreateRoute(session: IHTTPSession): Response {
        val req = runCatching { json.decodeFromString<CreateRouteRequest>(readBody(session)) }.getOrNull()
            ?: return json(Response.Status.BAD_REQUEST, OkDto(false, error = "bad body"))
        if (req.waypoints.size < 2) return json(Response.Status.BAD_REQUEST, OkDto(false, error = "need >=2 waypoints"))
        val profile = runCatching { RoutingProfile.valueOf(req.profile) }.getOrDefault(RoutingProfile.HIKING)
        val geoPoints = req.waypoints.map { GeoPoint(it.lat, it.lng) }
        val id = runBlocking {
            val routed = app.routingRepository.route(geoPoints, profile)
            val points = if (routed.isEmpty) geoPoints.map { cat.rumb.app.data.gpx.GpxPoint(it.latitude, it.longitude) } else routed.points
            app.trackRepository.insertRoute(req.name.ifBlank { "Ruta" }, points, TrackSource.GPX_IMPORT, remoteId = null, kind = TrackKind.ROUTE)
        }
        DebugLog.i("Desktop", "ruta creada web · ${req.waypoints.size} waypoints · id=$id")
        return json(Response.Status.OK, OkDto(true, id = id))
    }

    private fun handleRename(id: Long, session: IHTTPSession): Response {
        val name = runCatching { json.decodeFromString<Map<String, String>>(readBody(session))["name"] }.getOrNull()
            ?: return json(Response.Status.BAD_REQUEST, OkDto(false))
        runBlocking { app.trackRepository.rename(id, name) }
        return json(Response.Status.OK, OkDto(true, id = id))
    }

    private fun handleDelete(id: Long): Response {
        runBlocking { app.trackRepository.delete(id) }
        return json(Response.Status.OK, OkDto(true, id = id))
    }

    // --- Helpers ---

    private fun readBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        session.parseBody(map)
        // NanoHTTPD puts a raw POST body under the "postData" key when there's no form encoding.
        return map["postData"] ?: map.values.firstOrNull() ?: ""
    }

    private fun serveAsset(path: String): Response {
        val asset = "desktop${path}"
        return runCatching {
            val bytes = context.assets.open(asset).readBytes()
            newFixedLengthResponse(Response.Status.OK, mimeFor(path), bytes.inputStream(), bytes.size.toLong())
        }.getOrElse { json(Response.Status.NOT_FOUND, OkDto(false, error = "no asset")) }
    }

    private fun mimeFor(path: String): String = when {
        path.endsWith(".html") -> "text/html"
        path.endsWith(".css") -> "text/css"
        path.endsWith(".js") -> "application/javascript"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".svg") -> "image/svg+xml"
        else -> "text/plain"
    }

    private inline fun <reified T> json(status: Response.Status, body: T): Response =
        newFixedLengthResponse(status, "application/json", json.encodeToString(body))

    companion object {
        /** Starts a server on the first free port from [preferred] up to +10; null if none free. */
        fun startOnFreePort(context: Context, preferred: Int, pinProvider: () -> String): DesktopServer? {
            for (p in preferred..preferred + 10) {
                val server = DesktopServer(context, p, pinProvider)
                if (runCatching { server.start(SOCKET_READ_TIMEOUT, false) }.isSuccess) {
                    DebugLog.i("Desktop", "servidor actiu al port $p")
                    return server
                }
            }
            DebugLog.w("Desktop", "cap port lliure entre $preferred i ${preferred + 10}")
            return null
        }
    }
}
