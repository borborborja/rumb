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
    /** False = online only: excluded from offline region download and route prefetch. */
    val offlineAllowed: Boolean = true,
    /**
     * Non-null = this provider needs a user-supplied API key (the [url] carries a `{key}`
     * placeholder). The value is the provider id used to store/look up the key (e.g. "tracestrack").
     */
    val apiKeyProvider: String? = null,
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

    /** IGN España MTN topographic — national coverage, via the WMTS GetTile endpoint (standard XYZ). */
    IGN_MTN(
        id = "ign_mtn",
        displayName = "IGN Topogràfic (Espanya)",
        kind = Kind.RASTER,
        url = "https://www.ign.es/wmts/mapa-raster?service=WMTS&request=GetTile&version=1.0.0" +
            "&layer=MTN&style=default&tilematrixset=GoogleMapsCompatible&format=image/jpeg" +
            "&TileMatrix={z}&TileRow={y}&TileCol={x}",
        attribution = "© Instituto Geográfico Nacional (IGN España)",
        maxZoom = 19,
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
    ),

    /**
     * Tracestrack Topo — global topographic. Needs a free per-user API key (`{key}` in the URL);
     * hidden from the base-map pickers until the user enters and verifies a key in Map layers.
     * Online only (fair-use quota).
     */
    TRACESTRACK_TOPO(
        id = "tracestrack_topo",
        displayName = "Tracestrack Topo",
        kind = Kind.RASTER,
        url = "https://tile.tracestrack.com/topo__/{z}/{x}/{y}.png?key={key}",
        attribution = "© Tracestrack · © OpenStreetMap contributors",
        maxZoom = 18,
        offlineAllowed = false,
        apiKeyProvider = "tracestrack",
    );

    enum class Kind { RASTER, VECTOR_STYLE }

    /** Whether this source needs a user-supplied API key before it can render. */
    val needsApiKey: Boolean get() = apiKeyProvider != null

    /**
     * Ready to be offered as a base map: keyless, or a keyed provider whose key is loaded. Keyed maps
     * without a key are hidden from the pickers (they'd render blank) and only appear once activated
     * in Map layers.
     */
    val isSelectable: Boolean get() = apiKeyProvider == null || TileApiKeys.get(apiKeyProvider) != null

    companion object {
        val DEFAULT = ICGC_TOPO
        fun byId(id: String?): MapSource = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
