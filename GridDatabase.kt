package ge.grid.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cars")
data class CarEntity(
    @PrimaryKey val id: String,
    val nickname: String,
    val make: String,
    val model: String,
    val year: Int,
    val trim: String = "",
    val fuel: String = "",
    val modifications: String = "",
    val photoUri: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "trips", indices = [Index("carId"), Index("startedAt")],
    foreignKeys = [ForeignKey(entity = CarEntity::class, parentColumns = ["id"], childColumns = ["carId"], onDelete = ForeignKey.RESTRICT)])
data class TripEntity(
    @PrimaryKey val id: String,
    val carId: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val status: String = "recording",
    val pausedAt: Long? = null,
    val pausedTotalMs: Long = 0L,
    val distanceMeters: Double = 0.0,
    val movingMs: Long = 0,
    val maxSpeedMps: Double? = null,
    val lastAccuracyMeters: Float? = null,
    val synced: Boolean = false,
    val serverStatus: String = "local"
)

@Entity(tableName = "points", primaryKeys = ["tripId", "timestampMs"], indices = [Index("tripId")],
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)])
data class PointEntity(
    val tripId: String,
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val gpsSpeedMps: Float?,
    val contributesMeters: Double
)

@Dao
interface GridDao {
    @Query("SELECT * FROM cars ORDER BY createdAt DESC") fun cars(): Flow<List<CarEntity>>
    @Query("SELECT * FROM trips ORDER BY startedAt DESC") fun trips(): Flow<List<TripEntity>>
    @Query("SELECT * FROM trips WHERE status IN ('recording','paused') ORDER BY startedAt DESC LIMIT 1") fun activeTrip(): Flow<TripEntity?>
    @Query("SELECT * FROM trips WHERE status IN ('recording','paused') ORDER BY startedAt DESC LIMIT 1") suspend fun getActiveTrip(): TripEntity?
    @Query("SELECT * FROM trips WHERE id=:id LIMIT 1") suspend fun getTrip(id: String): TripEntity?
    @Query("SELECT * FROM cars WHERE id=:id LIMIT 1") suspend fun getCar(id: String): CarEntity?
    @Query("SELECT * FROM points WHERE tripId=:tripId ORDER BY timestampMs ASC") fun points(tripId: String): Flow<List<PointEntity>>
    @Query("SELECT * FROM points WHERE tripId=:tripId ORDER BY timestampMs DESC LIMIT 1") suspend fun lastPoint(tripId: String): PointEntity?
    @Query("SELECT * FROM points WHERE tripId=:tripId ORDER BY timestampMs ASC") suspend fun getPoints(tripId: String): List<PointEntity>
    @Query("SELECT * FROM trips WHERE synced=0 AND status='finished' ORDER BY startedAt LIMIT 15") suspend fun pendingTrips(): List<TripEntity>
    @Query("SELECT SUM(distanceMeters) FROM trips WHERE status='finished'") fun totalDistance(): Flow<Double?>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun addCar(car: CarEntity)
    @Update suspend fun updateCar(car: CarEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun addTrip(trip: TripEntity)
    @Update suspend fun updateTrip(trip: TripEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addPoint(point: PointEntity): Long
    @Query("DELETE FROM trips WHERE id=:id AND status='finished'") suspend fun deleteTrip(id: String): Int
    @Query("DELETE FROM cars WHERE id=:id") suspend fun deleteCar(id: String): Int
    @Query("DELETE FROM points") suspend fun erasePoints()
    @Query("DELETE FROM trips") suspend fun eraseTrips()
    @Query("DELETE FROM cars") suspend fun eraseCars()
}

@Database(entities = [CarEntity::class, TripEntity::class, PointEntity::class], version = 1, exportSchema = true)
abstract class GridDatabase : RoomDatabase() { abstract fun dao(): GridDao }
