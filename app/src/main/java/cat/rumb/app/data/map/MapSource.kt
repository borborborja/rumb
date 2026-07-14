package cat.rumb.app.data.map

/**
 * Catalogue of online base-map sources. ICGC serves standard EPSG:3857 tiles
 * (grid MON3857NW, zoom 1-20, PNG) with no API key, licensed CC-BY © ICGC.
 * See https://geoserveis.icgc.cat/servei/catalunya/mapa-base/wmts/1.0.0/WMTSCapabilities.xml
 */
enum class MapSource(
    val id: String,
    val displayName: String,
    val kind: Kind,
    /** Raster XYZ template ({z}/{x}/{y}) or vector style-JSON URL depending on [kind]. */
    val url: String,
    val attribution: String,
    val maxZoom: Int = 20,
    /** Tile-server subdomains to expand `{s}` (e.g. "abc"); null = no subdomain. */
    val subdomains: String? = null,
    /** Tile numbering scheme. TMS inverts the Y row (used by IGN's *.idee.es endpoints). */
    val scheme: Scheme = Scheme.XYZ,
    /** False = online only: excluded from offline region download and route prefetch. */
    val offlineAllowed: Boolean = true,
) {
    OSM(
        id = "osm",
        displayName = "OpenStreetMap",
        kind = Kind.RASTER,
        url = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        attribution = "© OpenStreetMap contributors",
        maxZoom = 19,
    ),
    ICGC_TOPO(
        id = "icgc_topografic",
        displayName = "ICGC Topogràfic",
        kind = Kind.RASTER,
        url = "https://geoserveis.icgc.cat/servei/catalunya/mapa-base/wmts/topografic/MON3857NW/{z}/{x}/{y}.png",
        attribution = "© Institut Cartogràfic i Geològic de Catalunya (ICGC)",
    ),
    ICGC_TOPO_GRIS(
        id = "icgc_topografic_gris",
        displayName = "ICGC Topogràfic gris",
        kind = Kind.RASTER,
        url = "https://geoserveis.icgc.cat/servei/catalunya/mapa-base/wmts/topografic-gris/MON3857NW/{z}/{x}/{y}.png",
        attribution = "© Institut Cartogràfic i Geològic de Catalunya (ICGC)",
    ),
    ICGC_ORTO(
        id = "icgc_orto",
        displayName = "ICGC Ortofoto",
        kind = Kind.RASTER,
        url = "https://geoserveis.icgc.cat/servei/catalunya/mapa-base/wmts/orto/MON3857NW/{z}/{x}/{y}.png",
        attribution = "© Institut Cartogràfic i Geològic de Catalunya (ICGC)",
    ),
    ICGC_ORTO_HIBRIDA(
        id = "icgc_orto_hibrida",
        displayName = "ICGC Ortofoto híbrida",
        kind = Kind.RASTER,
        url = "https://geoserveis.icgc.cat/servei/catalunya/mapa-base/wmts/orto-hibrida/MON3857NW/{z}/{x}/{y}.png",
        attribution = "© Institut Cartogràfic i Geològic de Catalunya (ICGC), © OpenStreetMap contributors",
    ),
    ICGC_GEOLOGIC(
        id = "icgc_geologic",
        displayName = "ICGC Geològic",
        kind = Kind.RASTER,
        url = "https://geoserveis.icgc.cat/servei/catalunya/mapa-base/wmts/geologic/MON3857NW/{z}/{x}/{y}.png",
        attribution = "© Institut Cartogràfic i Geològic de Catalunya (ICGC)",
    ),

    // ---- Global / national sources (coverage beyond Catalonia) ----

    /** IGN España MTN topographic — national coverage. TMS endpoint (inverted Y). */
    IGN_MTN(
        id = "ign_mtn",
        displayName = "IGN Topogràfic (Espanya)",
        kind = Kind.RASTER,
        url = "https://tms-mapa-raster.idee.es/1.0.0/mapa-raster/{z}/{y}/{x}.jpeg",
        attribution = "© Instituto Geográfico Nacional (IGN España)",
        maxZoom = 18,
        scheme = Scheme.TMS,
    ),
    /** Esri World Imagery — global satellite. Placeholder order is {z}/{y}/{x} (standard XYZ). */
    ESRI_IMAGERY(
        id = "esri_imagery",
        displayName = "Esri Satèl·lit",
        kind = Kind.RASTER,
        url = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        attribution = "© Esri, Maxar, Earthstar Geographics",
        maxZoom = 19,
    ),
    /** OpenTopoMap — global topographic. Online only (server discourages bulk download). */
    OPENTOPOMAP(
        id = "opentopomap",
        displayName = "OpenTopoMap",
        kind = Kind.RASTER,
        url = "https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png",
        attribution = "© OpenTopoMap (CC-BY-SA) · © OpenStreetMap contributors",
        maxZoom = 17,
        subdomains = "abc",
        offlineAllowed = false,
    ),
    /** CyclOSM — cycling-oriented OSM. Online only (fair-use community tiles). */
    CYCLOSM(
        id = "cyclosm",
        displayName = "CyclOSM",
        kind = Kind.RASTER,
        url = "https://{s}.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
        attribution = "© CyclOSM · © OpenStreetMap contributors",
        maxZoom = 20,
        subdomains = "abc",
        offlineAllowed = false,
    );

    enum class Scheme { XYZ, TMS }

    enum class Kind { RASTER, VECTOR_STYLE }

    companion object {
        val DEFAULT = ICGC_TOPO
        fun byId(id: String?): MapSource = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
