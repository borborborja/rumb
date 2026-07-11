package cat.hudpro.opentracks.data.map

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TileMathTest {

    @Test
    fun tmsRowConversion() {
        assertThat(TileMath.xyzToTmsRow(0, 0)).isEqualTo(0)
        assertThat(TileMath.xyzToTmsRow(0, 1)).isEqualTo(1)
        assertThat(TileMath.xyzToTmsRow(1, 1)).isEqualTo(0)
        assertThat(TileMath.xyzToTmsRow(5, 4)).isEqualTo(10) // 15 - 5
    }

    @Test
    fun lonLatToTileKnownValues() {
        // Barcelona (2.1734, 41.3851) at z=12 → tile x=2072, y=1529 (standard OSM slippy formula).
        assertThat(TileMath.lonToTileX(2.1734, 12)).isEqualTo(2072)
        assertThat(TileMath.latToTileY(41.3851, 12)).isEqualTo(1529)
    }

    @Test
    fun originAndAntimeridianClamp() {
        assertThat(TileMath.lonToTileX(-180.0, 2)).isEqualTo(0)
        assertThat(TileMath.latToTileY(85.0, 2)).isEqualTo(0)
        assertThat(TileMath.lonToTileX(179.999, 2)).isEqualTo(3)
    }

    @Test
    fun bboxRangeAndCount() {
        val bbox = BoundingBox(west = 2.0, south = 41.3, east = 2.3, north = 41.5)
        val range = TileMath.tileRangeForBbox(bbox, 12)
        assertThat(range.xMin).isLessThanOrEqualTo(range.xMax)
        assertThat(range.yMin).isLessThanOrEqualTo(range.yMax)
        assertThat(range.count).isEqualTo(
            (range.xMax - range.xMin + 1).toLong() * (range.yMax - range.yMin + 1),
        )
    }

    @Test
    fun tileCountGrowsWithZoom() {
        val bbox = BoundingBox(west = 0.15, south = 40.52, east = 3.33, north = 42.87)
        val low = TileMath.tileCount(bbox, 8, 10)
        val high = TileMath.tileCount(bbox, 8, 13)
        assertThat(high).isGreaterThan(low)
    }
}
