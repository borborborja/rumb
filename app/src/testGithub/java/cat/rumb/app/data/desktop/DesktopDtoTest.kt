package cat.rumb.app.data.desktop

import cat.rumb.app.data.tracks.FollowTrackEntity
import cat.rumb.app.data.tracks.TrackKind
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DesktopDtoTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun trackMapperDerivesKmAndDifficulty() {
        val e = FollowTrackEntity(
            id = 7, name = "Volta", gpx = "", kind = TrackKind.TRAINING,
            distanceMeters = 12_000.0, ascentM = 600.0, pointCount = 500, createdAt = 123,
            durationMs = 3_600_000, activityType = "mtb", municipality = "Vic",
        )
        val dto = e.toDto()
        assertThat(dto.distanceKm).isEqualTo(12.0)
        assertThat(dto.ascentM).isEqualTo(600)
        assertThat(dto.difficulty).isEqualTo("HARD") // kmEffort = 12 + 600/100 = 18.0 → HARD (>= 18)
        assertThat(dto.activityType).isEqualTo("mtb")
        assertThat(dto.municipality).isEqualTo("Vic")
        assertThat(dto.kind).isEqualTo("TRAINING")
    }

    @Test
    fun dtosSerializeToJsonRoundTrip() {
        val e = FollowTrackEntity(id = 1, name = "T", gpx = "", distanceMeters = 5000.0, ascentM = 100.0, createdAt = 1)
        val s = json.encodeToString(e.toDto())
        val back = json.decodeFromString<TrackDto>(s)
        assertThat(back.id).isEqualTo(1)
        assertThat(back.distanceKm).isEqualTo(5.0)

        val ok = json.decodeFromString<OkDto>(json.encodeToString(OkDto(true, id = 42)))
        assertThat(ok.ok).isTrue()
        assertThat(ok.id).isEqualTo(42)

        val req = json.decodeFromString<CreateRouteRequest>(
            """{"name":"R","profile":"HIKING","waypoints":[{"lat":41.0,"lng":2.0},{"lat":41.1,"lng":2.1}]}""",
        )
        assertThat(req.waypoints).hasSize(2)
        assertThat(req.profile).isEqualTo("HIKING")
    }

    @Test
    fun previewLocationAndProfileDtosSerialize() {
        val preview = RoutePreviewDto(listOf(WaypointDto(41.0, 2.0), WaypointDto(41.1, 2.1)), 1234.5, 56.7)
        val back = json.decodeFromString<RoutePreviewDto>(json.encodeToString(preview))
        assertThat(back.points).hasSize(2)
        assertThat(back.distanceM).isEqualTo(1234.5)
        assertThat(back.ascentM).isEqualTo(56.7)

        assertThat(json.decodeFromString<LocationDto>(json.encodeToString(LocationDto(41.4, 2.1))).lat).isEqualTo(41.4)
        assertThat(json.decodeFromString<ProfileDto>(json.encodeToString(ProfileDto("HIKING", "Senderisme"))).label)
            .isEqualTo("Senderisme")
    }
}
