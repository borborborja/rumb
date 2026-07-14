package cat.rumb.app.data.tracks

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

enum class TrackSource { GPX_IMPORT, ENDURAIN, RECORDED }

/** What a saved track IS: a route to follow, or a recorded/imported training. */
object TrackKind {
    const val ROUTE = "ROUTE"
    const val TRAINING = "TRAINING"
}

class Converters {
    @TypeConverter fun sourceToString(s: TrackSource): String = s.name
    @TypeConverter fun stringToSource(s: String): TrackSource = TrackSource.valueOf(s)
}

/** A route the user can follow from the viewer. The GPX text is stored inline for simplicity. */
@Entity(tableName = "follow_tracks")
data class FollowTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val collection: String = "General",
    val source: TrackSource = TrackSource.GPX_IMPORT,
    @ColumnInfo(name = "distance_meters") val distanceMeters: Double = 0.0,
    @ColumnInfo(name = "point_count") val pointCount: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0L,
    @ColumnInfo(name = "gpx") val gpx: String,
    /** Remote id when [source] is ENDURAIN. */
    @ColumnInfo(name = "remote_id") val remoteId: Long? = null,
    /** [TrackKind.ROUTE] (to follow) or [TrackKind.TRAINING] (recorded/imported activity). */
    val kind: String = TrackKind.ROUTE,
    /** Activity type id (predefined like "run"/"mtb" or "custom_<uuid>"); null = unassigned. */
    @ColumnInfo(name = "activity_type") val activityType: String? = null,
    /** Municipality of the start point, reverse-geocoded once via Nominatim. */
    val municipality: String? = null,
    /** Total ascent in meters, persisted so lists can sort by difficulty without parsing GPX. */
    @ColumnInfo(name = "ascent_m") val ascentM: Double = 0.0,
    @ColumnInfo(name = "start_lat") val startLat: Double? = null,
    @ColumnInfo(name = "start_lon") val startLon: Double? = null,
    /** True once ascent/start have been extracted (municipality may still be pending). */
    @ColumnInfo(name = "meta_done") val metaDone: Boolean = false,
    /** True when this track is a competition reference (appears in the Competition tab). */
    @ColumnInfo(name = "is_competition") val isCompetition: Boolean = false,
    /** Set on attempts recorded in competition mode: the reference track's id. */
    @ColumnInfo(name = "competition_ref_id") val competitionRefId: Long? = null,
    /** Total elapsed ms (first→last timed point). null = pending; 0 = checked, untimed. */
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    /** Archived tracks leave the normal lists (the "Arxivats" pseudo-folder) but keep everything. */
    val archived: Boolean = false,
    /** On competition references: the whole competition is archived (membership is kept). */
    @ColumnInfo(name = "competition_archived") val competitionArchived: Boolean = false,
    /** Lap ranges (JSON of [cat.rumb.app.data.tracks.LapRange]); null/blank = no laps. */
    val laps: String? = null,
    /** On trainings: the route id this activity was recorded while following (null = free recording). */
    @ColumnInfo(name = "followed_route_id") val followedRouteId: Long? = null,
)

/** Projection for the municipality backfill queue. */
data class IdLatLon(
    val id: Long,
    @ColumnInfo(name = "start_lat") val startLat: Double,
    @ColumnInfo(name = "start_lon") val startLon: Double,
)

@Dao
interface FollowTrackDao {
    @Query(
        "SELECT id, name, collection, source, distance_meters, point_count, created_at, remote_id, kind, " +
            "activity_type, municipality, ascent_m, start_lat, start_lon, meta_done, " +
            "is_competition, competition_ref_id, duration_ms, archived, competition_archived, laps, " +
            "followed_route_id, '' AS gpx " +
            "FROM follow_tracks ORDER BY created_at DESC",
    )
    fun observeSummaries(): Flow<List<FollowTrackEntity>>

    /** Trainings recorded while following [routeId], newest first (GPX blob omitted). */
    @Query(
        "SELECT id, name, collection, source, distance_meters, point_count, created_at, remote_id, kind, " +
            "activity_type, municipality, ascent_m, start_lat, start_lon, meta_done, " +
            "is_competition, competition_ref_id, duration_ms, archived, competition_archived, laps, " +
            "followed_route_id, '' AS gpx " +
            "FROM follow_tracks WHERE followed_route_id = :routeId AND kind = 'TRAINING' ORDER BY created_at DESC",
    )
    suspend fun trainingsForRoute(routeId: Long): List<FollowTrackEntity>

    @Query("UPDATE follow_tracks SET collection = :newName WHERE collection = :oldName AND kind = :kind")
    suspend fun renameCollection(oldName: String, newName: String, kind: String)

    @Query("SELECT * FROM follow_tracks WHERE id = :id")
    suspend fun getById(id: Long): FollowTrackEntity?

    @Query("UPDATE follow_tracks SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE follow_tracks SET collection = :collection WHERE id = :id")
    suspend fun setCollection(id: Long, collection: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: FollowTrackEntity): Long

    @Query("DELETE FROM follow_tracks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT remote_id FROM follow_tracks WHERE source = 'ENDURAIN' AND remote_id IS NOT NULL")
    suspend fun knownRemoteIds(): List<Long>

    @Query("UPDATE follow_tracks SET activity_type = :type WHERE id = :id")
    suspend fun setActivityType(id: Long, type: String?)

    @Query("UPDATE follow_tracks SET ascent_m = :ascent, start_lat = :lat, start_lon = :lon, meta_done = 1 WHERE id = :id")
    suspend fun setMeta(id: Long, ascent: Double, lat: Double?, lon: Double?)

    @Query("UPDATE follow_tracks SET municipality = :municipality WHERE id = :id")
    suspend fun setMunicipality(id: Long, municipality: String)

    @Query("SELECT id FROM follow_tracks WHERE meta_done = 0")
    suspend fun idsNeedingMeta(): List<Long>

    @Query("SELECT id, start_lat, start_lon FROM follow_tracks WHERE municipality IS NULL AND start_lat IS NOT NULL")
    suspend fun needingMunicipality(): List<IdLatLon>

    @Query("SELECT DISTINCT collection FROM follow_tracks WHERE kind = :kind")
    suspend fun collections(kind: String): List<String>

    @Query("UPDATE follow_tracks SET is_competition = :flag WHERE id = :id")
    suspend fun setCompetition(id: Long, flag: Boolean)

    @Query("UPDATE follow_tracks SET duration_ms = :ms WHERE id = :id")
    suspend fun setDuration(id: Long, ms: Long)

    @Query("SELECT id FROM follow_tracks WHERE duration_ms IS NULL")
    suspend fun idsNeedingDuration(): List<Long>

    @Query("UPDATE follow_tracks SET archived = :flag WHERE id = :id")
    suspend fun setArchived(id: Long, flag: Boolean)

    @Query("UPDATE follow_tracks SET competition_archived = :flag WHERE id = :id")
    suspend fun setCompetitionArchived(id: Long, flag: Boolean)

    @Query("UPDATE follow_tracks SET competition_ref_id = NULL WHERE id = :id")
    suspend fun clearCompetitionRef(id: Long)

    @Query("UPDATE follow_tracks SET laps = :laps WHERE id = :id")
    suspend fun setLaps(id: Long, laps: String?)

    /** Dissolves a competition: unflags the reference and unlinks every attempt. Tracks stay. */
    @Query("UPDATE follow_tracks SET competition_ref_id = NULL WHERE competition_ref_id = :refId")
    suspend fun unlinkAttempts(refId: Long)
}

@Database(
    entities = [
        FollowTrackEntity::class,
        cat.rumb.app.data.recording.RecordingEntity::class,
        cat.rumb.app.data.recording.RecordingPointEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class RumbDatabase : RoomDatabase() {
    abstract fun followTrackDao(): FollowTrackDao
    abstract fun recordingDao(): cat.rumb.app.data.recording.RecordingDao

    companion object {
        /** v8: link a training to the route it was recorded while following. */
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE follow_tracks ADD COLUMN followed_route_id INTEGER")
            }
        }

        /** v7: manual laps — boundary marks on recordings, lap ranges on saved tracks. */
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE follow_tracks ADD COLUMN laps TEXT")
                db.execSQL("ALTER TABLE recordings ADD COLUMN laps TEXT")
            }
        }

        /** v6: per-track archive + archived competitions. */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE follow_tracks ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE follow_tracks ADD COLUMN competition_archived INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v5: competition mode (reference flag, attempt link, total duration). */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE follow_tracks ADD COLUMN is_competition INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE follow_tracks ADD COLUMN competition_ref_id INTEGER")
                db.execSQL("ALTER TABLE follow_tracks ADD COLUMN duration_ms INTEGER")
            }
        }

        /** v4: activity type, municipality and sortable metadata on follow_tracks. */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE follow_tracks ADD COLUMN activity_type TEXT")
                db.execSQL("ALTER TABLE follow_tracks ADD COLUMN municipality TEXT")
                db.execSQL("ALTER TABLE follow_tracks ADD COLUMN ascent_m REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE follow_tracks ADD COLUMN start_lat REAL")
                db.execSQL("ALTER TABLE follow_tracks ADD COLUMN start_lon REAL")
                db.execSQL("ALTER TABLE follow_tracks ADD COLUMN meta_done INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3: route/training kind on follow_tracks (recorded tracks become trainings). */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE follow_tracks ADD COLUMN kind TEXT NOT NULL DEFAULT 'ROUTE'")
                db.execSQL("UPDATE follow_tracks SET kind = 'TRAINING' WHERE source = 'RECORDED'")
            }
        }

        /** v2: crash-safe native recording tables. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recordings` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`started_at` INTEGER NOT NULL, `state` TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recording_points` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`recording_id` INTEGER NOT NULL, `seq` INTEGER NOT NULL, " +
                        "`segment` INTEGER NOT NULL, `lat` REAL NOT NULL, `lng` REAL NOT NULL, " +
                        "`alt` REAL, `time_ms` INTEGER NOT NULL, `speed` REAL NOT NULL, " +
                        "`bearing` REAL, `hr` REAL, `cad` REAL, `power` REAL)",
                )
            }
        }
    }
}
