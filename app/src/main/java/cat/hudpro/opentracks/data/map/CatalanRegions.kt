package cat.hudpro.opentracks.data.map

/** A predefined downloadable area. */
data class Region(val name: String, val bbox: BoundingBox)

/** Catalan provinces + the whole of Catalonia (approximate WGS84 bounding boxes). */
object CatalanRegions {
    val all: List<Region> = listOf(
        Region("Catalunya", BoundingBox(west = 0.15, south = 40.52, east = 3.33, north = 42.87)),
        Region("Barcelona", BoundingBox(west = 1.35, south = 41.15, east = 2.78, north = 42.32)),
        Region("Girona", BoundingBox(west = 2.09, south = 41.60, east = 3.33, north = 42.87)),
        Region("Lleida", BoundingBox(west = 0.15, south = 41.00, east = 1.65, north = 42.87)),
        Region("Tarragona", BoundingBox(west = 0.16, south = 40.52, east = 1.67, north = 41.62)),
    )
}
