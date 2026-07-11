package cat.hudpro.opentracks.data.opentracks

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import cat.hudpro.opentracks.data.opentracks.model.GeoPoint
import cat.hudpro.opentracks.data.opentracks.model.TRACKPOINT_TYPE_PAUSE
import cat.hudpro.opentracks.data.opentracks.model.TRACKPOINT_TYPE_TRACKPOINT
import cat.hudpro.opentracks.data.opentracks.model.Trackpoint
import cat.hudpro.opentracks.data.opentracks.model.TrackpointsDebug
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

private val PAUSE_LAT_LONG = (100 * APIConstants.LAT_LON_FACTOR).toInt()

class TrackpointReaderTest {

    private lateinit var contentResolver: ContentResolver
    private lateinit var cursor: Cursor
    private val testUri: Uri = mockk()

    @BeforeEach
    fun setUp() {
        contentResolver = mockk()
        cursor = mockk()
        every { cursor.getColumnIndexOrThrow(TrackpointReader.ID) } returns 1
        every { cursor.getColumnIndexOrThrow(TrackpointReader.TRACKID) } returns 2
        every { cursor.getColumnIndexOrThrow(TrackpointReader.LATITUDE) } returns 3
        every { cursor.getColumnIndexOrThrow(TrackpointReader.LONGITUDE) } returns 4
        every { cursor.getColumnIndexOrThrow(TrackpointReader.TIME) } returns 5
        every { cursor.getColumnIndexOrThrow(TrackpointReader.SPEED) } returns 6
        // Sensor columns are absent in these fixtures (optDouble returns null when index < 0).
        every { cursor.getColumnIndex(TrackpointReader.ELEVATION) } returns -1
        every { cursor.getColumnIndex(TrackpointReader.HEARTRATE) } returns -1
        every { cursor.getColumnIndex(TrackpointReader.CADENCE) } returns -1
        every { cursor.getColumnIndex(TrackpointReader.POWER) } returns -1
        every { cursor.getColumnIndex(TrackpointReader.BEARING) } returns -1
        every { cursor.close() } just Runs
    }

    @Test
    fun readsV2WithPauseSplittingDebug() {
        val tp1 = trackpoint()
        val tp2 = tp1.copy(id = 3L, type = TRACKPOINT_TYPE_PAUSE, speed = 0.0)

        every {
            contentResolver.query(
                testUri,
                TrackpointReader.PROJECTION_V2,
                "${TrackpointReader.ID}> ? AND ${TrackpointReader.TYPE} IN (-2, -1, 0, 1, 3)",
                arrayOf("1"),
                null,
            )
        } returns cursor
        every { cursor.getColumnIndex(TrackpointReader.TYPE) } returns 7
        every { cursor.moveToNext() } returnsMany listOf(true, true, true, false)
        every { cursor.getLong(1) } returnsMany listOf(tp1.id, tp2.id, 0)
        every { cursor.getLong(2) } returnsMany listOf(tp1.trackId, tp2.trackId, 0)
        every { cursor.getInt(3) } returnsMany listOf(
            (tp1.latLong!!.latitude * APIConstants.LAT_LON_FACTOR).toInt(),
            PAUSE_LAT_LONG,
            (130 * APIConstants.LAT_LON_FACTOR).toInt(), // invalid
        )
        every { cursor.getInt(4) } returnsMany listOf(
            (tp1.latLong!!.longitude * APIConstants.LAT_LON_FACTOR).toInt(),
            PAUSE_LAT_LONG,
            0,
        )
        every { cursor.getLong(5) } returnsMany listOf(tp1.time.toEpochMilli(), tp2.time.toEpochMilli(), 0)
        every { cursor.getDouble(6) } returnsMany listOf(tp1.speed, tp2.speed, 0.0)
        every { cursor.getInt(7) } returnsMany listOf(tp1.type, tp2.type, TRACKPOINT_TYPE_TRACKPOINT)

        val result = TrackpointReader.readTrackpointsBySegments(contentResolver, testUri, 1, 2)

        assertThat(result.segments).hasSize(1)
        assertThat(result.segments[0]).hasSize(2)
        assertThat(result.segments[0][0]).isEqualTo(tp1)
        assertThat(result.segments[0][1]).isEqualTo(tp2)
        assertThat(result.debug).isEqualTo(
            TrackpointsDebug(
                trackpointsReceived = 3,
                trackpointsInvalid = 1,
                trackpointsPause = 1,
                segments = 1,
                protocolVersion = 2,
            ),
        )
    }

    @Test
    fun v1UsesNoTypeFilter() {
        val tp1 = trackpoint()
        every {
            contentResolver.query(testUri, TrackpointReader.PROJECTION_V1, "${TrackpointReader.ID}> ?", arrayOf("0"), null)
        } returns cursor
        every { cursor.getColumnIndex(TrackpointReader.TYPE) } returns -1
        every { cursor.moveToNext() } returnsMany listOf(true, false)
        every { cursor.getLong(1) } returnsMany listOf(tp1.id, 0)
        every { cursor.getLong(2) } returnsMany listOf(tp1.trackId, 0)
        every { cursor.getInt(3) } returnsMany listOf((tp1.latLong!!.latitude * APIConstants.LAT_LON_FACTOR).toInt(), 0)
        every { cursor.getInt(4) } returnsMany listOf((tp1.latLong!!.longitude * APIConstants.LAT_LON_FACTOR).toInt(), 0)
        every { cursor.getLong(5) } returnsMany listOf(tp1.time.toEpochMilli(), 0)
        every { cursor.getDouble(6) } returnsMany listOf(tp1.speed, 0.0)

        val result = TrackpointReader.readTrackpointsBySegments(contentResolver, testUri, null, 1)

        assertThat(result.segments).hasSize(1)
        assertThat(result.segments[0][0]).isEqualTo(tp1)
    }

    private fun trackpoint() = Trackpoint(
        trackId = 1L,
        id = 2L,
        latLong = GeoPoint(50.9, 9.1),
        type = TRACKPOINT_TYPE_TRACKPOINT,
        speed = 1.1,
        time = Instant.now().truncatedTo(ChronoUnit.MILLIS),
    )
}
