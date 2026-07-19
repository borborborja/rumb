package cat.rumb.app.scale

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One saved weigh-in (yours only — guest readings are never persisted). Height/age/sex are stored
 * as a SNAPSHOT taken at weigh time, so the derived composition of an old weigh-in stays stable even
 * after you later edit your profile. The composition itself is not stored; it is recomputed on read
 * from these fields via [BodyComposition], so a fixed formula improvement reflows history for free.
 */
@Entity(tableName = "weigh_ins")
data class WeighInEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val weightKg: Double,
    val impedanceOhm: Int?,
    val heightCm: Int,
    val ageYears: Int,
    /** "M", "F" or "" (unknown), mirroring ViewerPreferences.userSex. */
    val sex: String,
) {
    fun metrics(): BodyMetrics = BodyComposition.compute(
        weightKg = weightKg,
        impedanceOhm = impedanceOhm,
        heightCm = heightCm,
        ageYears = ageYears,
        sex = when (sex) {
            "M" -> Sex.MALE
            "F" -> Sex.FEMALE
            else -> null
        },
    )
}

@Dao
interface WeighInDao {
    @Query("SELECT * FROM weigh_ins ORDER BY timestamp ASC")
    fun observeAll(): Flow<List<WeighInEntity>>

    @Insert
    suspend fun insert(weighIn: WeighInEntity): Long

    @Query("DELETE FROM weigh_ins WHERE id = :id")
    suspend fun delete(id: Long)
}
