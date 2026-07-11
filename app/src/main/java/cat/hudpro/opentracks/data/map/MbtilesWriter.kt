package cat.hudpro.opentracks.data.map

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

/**
 * Writes tiles into an MBTiles 1.3 SQLite archive (the format MapLibre reads via `mbtiles://`).
 * Rows are stored in TMS order per the MBTiles spec (see [TileMath.xyzToTmsRow]).
 */
class MbtilesWriter(private val file: File) : Closeable {

    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null)

    init {
        db.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles (zoom_level, tile_column, tile_row)")
    }

    fun writeMetadata(name: String, bbox: BoundingBox, minZoom: Int, maxZoom: Int, attribution: String) {
        db.beginTransaction()
        try {
            db.delete("metadata", null, null)
            val meta = mapOf(
                "name" to name,
                "format" to "png",
                "type" to "baselayer",
                "version" to "1.0",
                "minzoom" to minZoom.toString(),
                "maxzoom" to maxZoom.toString(),
                "bounds" to "${bbox.west},${bbox.south},${bbox.east},${bbox.north}",
                "attribution" to attribution,
            )
            meta.forEach { (k, v) ->
                db.insert("metadata", null, ContentValues().apply { put("name", k); put("value", v) })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** True if a tile at the given XYZ coordinate is already stored. */
    fun hasTile(z: Int, x: Int, yXyz: Int): Boolean {
        val row = TileMath.xyzToTmsRow(yXyz, z)
        db.rawQuery(
            "SELECT 1 FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
            arrayOf(z.toString(), x.toString(), row.toString()),
        ).use { return it.moveToFirst() }
    }

    fun putTile(z: Int, x: Int, yXyz: Int, bytes: ByteArray) {
        val row = TileMath.xyzToTmsRow(yXyz, z)
        db.insertWithOnConflict(
            "tiles",
            null,
            ContentValues().apply {
                put("zoom_level", z)
                put("tile_column", x)
                put("tile_row", row)
                put("tile_data", bytes)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    inline fun batch(block: () -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    fun beginTransaction() = db.beginTransaction()
    fun setTransactionSuccessful() = db.setTransactionSuccessful()
    fun endTransaction() = db.endTransaction()

    fun tileCount(): Long =
        db.rawQuery("SELECT COUNT(*) FROM tiles", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    override fun close() = db.close()
}
