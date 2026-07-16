package cat.rumb.app.data.tracks

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Sync targets a track can be uploaded to. Stored as the `service` string. */
object SyncService {
    const val ENDURAIN = "ENDURAIN"
    const val WEBDAV = "WEBDAV"
    const val FOLDER = "FOLDER"
}

/** Per-(track, service) upload state. Stored as the `status` string. */
object SyncState {
    const val PENDING = "PENDING"
    const val UPLOADED = "UPLOADED"
    const val FAILED = "FAILED"
}

/**
 * Durable outbox: one row per (track, service) recording the last upload state. Doubles as the source
 * for the per-training status chips and the settings summary counts. A unique index on
 * (track_id, service) makes [SyncStatusDao.upsert] replace in place.
 */
@Entity(
    tableName = "sync_status",
    indices = [Index(value = ["track_id", "service"], unique = true)],
)
data class SyncStatusEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "track_id") val trackId: Long,
    val service: String,
    val status: String,
    /** Endurain activity id, saved file uri or WebDAV url — whatever identifies the remote copy. */
    @ColumnInfo(name = "remote_ref") val remoteRef: String? = null,
    val error: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** (service, status) → count, for the settings summary. */
data class SyncCount(val service: String, val status: String, val n: Int)

@Dao
interface SyncStatusDao {
    /** Replaces the row for the same (track_id, service) thanks to the unique index. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SyncStatusEntity)

    @Query("SELECT * FROM sync_status WHERE track_id = :trackId")
    fun forTrack(trackId: Long): Flow<List<SyncStatusEntity>>

    @Query("SELECT service, status, COUNT(*) AS n FROM sync_status GROUP BY service, status")
    fun observeCounts(): Flow<List<SyncCount>>

    @Query("SELECT * FROM sync_status WHERE status = 'FAILED'")
    suspend fun failed(): List<SyncStatusEntity>

    @Query("SELECT MAX(updated_at) FROM sync_status WHERE status = 'UPLOADED'")
    fun observeLastUploaded(): Flow<Long?>

    @Query("SELECT status FROM sync_status WHERE track_id = :trackId AND service = :service")
    suspend fun statusFor(trackId: Long, service: String): String?

    /** Track ids already uploaded to [service] — used to skip them in a bulk upload. */
    @Query("SELECT track_id FROM sync_status WHERE service = :service AND status = 'UPLOADED'")
    suspend fun uploadedTrackIds(service: String): List<Long>
}

/** Convenience writer used by the sync workers to record their outcome. No-op when trackId <= 0. */
object SyncStatusStore {
    fun dao(context: Context): SyncStatusDao =
        cat.rumb.app.RumbApplication.from(context).database.syncStatusDao()

    suspend fun mark(
        context: Context,
        trackId: Long,
        service: String,
        status: String,
        remoteRef: String? = null,
        error: String? = null,
    ) {
        if (trackId <= 0) return
        dao(context).upsert(
            SyncStatusEntity(
                trackId = trackId,
                service = service,
                status = status,
                remoteRef = remoteRef,
                error = error,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }
}
