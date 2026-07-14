package cat.rumb.app.data.map

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TileUrlTest {

    @Test
    fun xyzSourceSubstitutesZxyInOrder() {
        val url = TileDownloader.tileUrl(MapSource.ICGC_TOPO, z = 5, x = 3, y = 7)
        assertThat(url).contains("/5/3/7.png")
        assertThat(url).doesNotContain("{")
    }

    @Test
    fun tmsSourceInvertsTheYRow() {
        // z=5 → rows 0..31, so logical y=7 maps to server row 2^5-1-7 = 24.
        val url = TileDownloader.tileUrl(MapSource.IGN_MTN, z = 5, x = 3, y = 7)
        // Template order is {z}/{y}/{x}: expect .../5/24/3.jpeg
        assertThat(url).contains("/5/24/3.jpeg")
        assertThat(url).doesNotContain("{")
    }

    @Test
    fun esriKeepsNormalYButZyxOrder() {
        val url = TileDownloader.tileUrl(MapSource.ESRI_IMAGERY, z = 5, x = 3, y = 7)
        assertThat(url).endsWith("/tile/5/7/3")
    }

    @Test
    fun subdomainTemplateIsExpanded() {
        val url = TileDownloader.tileUrl(MapSource.OPENTOPOMAP, z = 5, x = 3, y = 7)
        assertThat(url).doesNotContain("{s}")
        assertThat(url).matches("https://[abc]\\.tile\\.opentopomap\\.org/5/3/7\\.png")
    }
}
