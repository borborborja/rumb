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
    fun ignWmtsSubstitutesMatrixRowCol() {
        val url = TileDownloader.tileUrl(MapSource.IGN_MTN, z = 5, x = 3, y = 7)
        assertThat(url).contains("TileMatrix=5").contains("TileRow=7").contains("TileCol=3")
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

    @Test
    fun keyedSourceInjectsTheStoredApiKey() {
        try {
            TileApiKeys.set("tracestrack", "MYKEY123")
            val url = TileDownloader.tileUrl(MapSource.TRACESTRACK_TOPO, z = 5, x = 3, y = 7)
            assertThat(url).contains("key=MYKEY123")
            assertThat(url).contains("/5/3/7.png")
            assertThat(url).doesNotContain("{") // no {z}/{x}/{y}/{key} left dangling
        } finally {
            TileApiKeys.set("tracestrack", null)
        }
    }

    @Test
    fun keyedSourceIsHiddenUntilKeyedThenSelectable() {
        TileApiKeys.set("tracestrack", null)
        assertThat(MapSource.TRACESTRACK_TOPO.needsApiKey).isTrue()
        assertThat(MapSource.TRACESTRACK_TOPO.isSelectable).isFalse()
        try {
            TileApiKeys.set("tracestrack", "K")
            assertThat(MapSource.TRACESTRACK_TOPO.isSelectable).isTrue()
        } finally {
            TileApiKeys.set("tracestrack", null)
        }
    }

    @Test
    fun keylessSourceNeedsNoKeyAndIsAlwaysSelectable() {
        assertThat(MapSource.ICGC_TOPO.needsApiKey).isFalse()
        assertThat(MapSource.ICGC_TOPO.isSelectable).isTrue()
    }
}
