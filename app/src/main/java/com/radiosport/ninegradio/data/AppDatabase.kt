package com.radiosport.ninegradio.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// ═════════════════════════════════════════════════════════════════════════════
//  ENTITIES
// ═════════════════════════════════════════════════════════════════════════════

/** A named list that groups bookmarks — equivalent to RFAnalyzer's BookmarkList. */
@Entity(tableName = "bookmark_lists")
data class BookmarkList(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val notes: String = "",
    val color: Int = 0xFF2196F3.toInt(),
    val createdAt: Long = System.currentTimeMillis()
)

/** A saved memory channel (like a radio memory preset). */
@Entity(tableName = "memory_channels")
data class MemoryChannel(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val frequencyHz: Long,
    val demodMode: String,
    val sampleRate: Int = 1_920_000,
    val gain: Int = 0,
    val squelch: Float = -100f,
    val biasTee: Boolean = false,
    val directSampling: Int = 0,
    val ppmCorrection: Int = 0,
    val group: String = "Default",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis()
)

/** A frequency bookmark with full metadata — inspired by RFAnalyzer's Station entity. */
@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookmarkList::class,
            parentColumns = ["id"],
            childColumns = ["bookmarkListId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("bookmarkListId"), Index("frequencyHz"), Index("favorite")]
)
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val frequencyHz: Long,
    val label: String,
    val demodMode: String = "",          // e.g. "NFM", "AM", "USB" — empty = no preference
    val bandwidth: Int = 0,              // 0 = use mode default
    val squelch: Float = -100f,          // -100 = disabled
    val notes: String = "",
    val color: Int = 0xFF2196F3.toInt(),
    val favorite: Boolean = false,
    val bookmarkListId: Long? = null,    // null = "Uncategorized"
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** A scanner entry (frequency range to scan). */
@Entity(tableName = "scan_entries")
data class ScanEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val startFreqHz: Long,
    val stopFreqHz: Long,
    val stepHz: Long,
    val demodMode: String,
    val squelch: Float,
    val dwellTimeMs: Int = 200,
    val enabled: Boolean = true
)

/** Recorded signal log entry. */
@Entity(tableName = "signal_log")
data class SignalLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val frequencyHz: Long,
    val demodMode: String,
    val signalDb: Float,
    val timestamp: Long = System.currentTimeMillis(),
    val notes: String = ""
)

/** IQ or audio recording metadata. */
@Entity(tableName = "recordings")
data class RecordingMeta(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val filePath: String,
    val type: String,   // "IQ" or "AUDIO"
    val frequencyHz: Long,
    val sampleRate: Int,
    val demodMode: String,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val createdAt: Long = System.currentTimeMillis()
)

// ═════════════════════════════════════════════════════════════════════════════
//  DAOs
// ═════════════════════════════════════════════════════════════════════════════

@Dao
interface MemoryChannelDao {
    @Query("SELECT * FROM memory_channels ORDER BY `group`, name")
    fun getAll(): Flow<List<MemoryChannel>>

    @Query("SELECT * FROM memory_channels WHERE `group` = :group ORDER BY name")
    fun getByGroup(group: String): Flow<List<MemoryChannel>>

    @Query("SELECT DISTINCT `group` FROM memory_channels ORDER BY `group`")
    fun getGroups(): Flow<List<String>>

    @Query("SELECT * FROM memory_channels WHERE id = :id")
    suspend fun getById(id: Int): MemoryChannel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(channel: MemoryChannel): Long

    @Update
    suspend fun update(channel: MemoryChannel)

    @Delete
    suspend fun delete(channel: MemoryChannel)

    @Query("UPDATE memory_channels SET lastUsedAt = :time WHERE id = :id")
    suspend fun updateLastUsed(id: Int, time: Long = System.currentTimeMillis())

    @Query("SELECT * FROM memory_channels WHERE frequencyHz BETWEEN :low AND :high")
    suspend fun getInRange(low: Long, high: Long): List<MemoryChannel>
}

@Dao
interface BookmarkListDao {
    @Query("SELECT * FROM bookmark_lists ORDER BY name ASC")
    fun getAll(): Flow<List<BookmarkList>>

    @Query("SELECT * FROM bookmark_lists WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): BookmarkList?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(list: BookmarkList): Long

    @Update
    suspend fun update(list: BookmarkList)

    @Delete
    suspend fun delete(list: BookmarkList)

    @Query("DELETE FROM bookmark_lists")
    suspend fun deleteAll()
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY frequencyHz")
    fun getAll(): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE favorite = 1 ORDER BY frequencyHz")
    fun getFavorites(): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE bookmarkListId = :listId ORDER BY frequencyHz")
    fun getByList(listId: Long): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE bookmarkListId IS NULL ORDER BY frequencyHz")
    fun getUncategorized(): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE frequencyHz BETWEEN :low AND :high ORDER BY frequencyHz")
    fun getInRange(low: Long, high: Long): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE frequencyHz BETWEEN :low AND :high ORDER BY frequencyHz")
    suspend fun getInRangeOnce(low: Long, high: Long): List<Bookmark>

    @Query("""
        SELECT * FROM bookmarks
        WHERE (:search = '' OR label LIKE '%' || :search || '%' OR notes LIKE '%' || :search || '%')
        AND (:onlyFavorites = 0 OR favorite = 1)
        AND (:bookmarkListId = -1 OR (bookmarkListId = :bookmarkListId OR (:bookmarkListId = -2 AND bookmarkListId IS NULL)))
        ORDER BY frequencyHz ASC
    """)
    fun getFiltered(search: String, onlyFavorites: Boolean, bookmarkListId: Long): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): Bookmark?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: Bookmark): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(bookmarks: List<Bookmark>)

    @Update
    suspend fun update(bookmark: Bookmark)

    @Delete
    suspend fun delete(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM bookmarks WHERE bookmarkListId = :listId")
    suspend fun deleteByList(listId: Long)

    @Query("UPDATE bookmarks SET favorite = :fav, updatedAt = :now WHERE id = :id")
    suspend fun setFavorite(id: Int, fav: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE bookmarks SET bookmarkListId = :listId, updatedAt = :now WHERE id IN (:ids)")
    suspend fun moveToList(ids: List<Int>, listId: Long?, now: Long = System.currentTimeMillis())
}

@Dao
interface ScanEntryDao {
    @Query("SELECT * FROM scan_entries ORDER BY startFreqHz")
    fun getAll(): Flow<List<ScanEntry>>

    @Query("SELECT * FROM scan_entries WHERE enabled = 1 ORDER BY startFreqHz")
    fun getEnabled(): Flow<List<ScanEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ScanEntry): Long

    @Update
    suspend fun update(entry: ScanEntry)

    @Delete
    suspend fun delete(entry: ScanEntry)
}

@Dao
interface SignalLogDao {
    @Query("SELECT * FROM signal_log ORDER BY timestamp DESC LIMIT 1000")
    fun getRecent(): Flow<List<SignalLog>>

    @Insert
    suspend fun insert(log: SignalLog)

    @Query("DELETE FROM signal_log WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface RecordingMetaDao {
    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun getAll(): Flow<List<RecordingMeta>>

    @Insert
    suspend fun insert(meta: RecordingMeta): Long

    @Delete
    suspend fun delete(meta: RecordingMeta)

    @Query("SELECT SUM(fileSizeBytes) FROM recordings")
    suspend fun getTotalSizeBytes(): Long?
}

// ═════════════════════════════════════════════════════════════════════════════
//  DATABASE
// ═════════════════════════════════════════════════════════════════════════════

@Database(
    entities = [
        BookmarkList::class,
        MemoryChannel::class,
        Bookmark::class,
        ScanEntry::class,
        SignalLog::class,
        RecordingMeta::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarkListDao(): BookmarkListDao
    abstract fun memoryChannelDao(): MemoryChannelDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun scanEntryDao(): ScanEntryDao
    abstract fun signalLogDao(): SignalLogDao
    abstract fun recordingMetaDao(): RecordingMetaDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create bookmark_lists table (no DEFAULT clauses so Room sees defaultValue='undefined')
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS bookmark_lists (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        notes TEXT NOT NULL,
                        color INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """)

                // Recreate bookmarks table so Room's schema validation passes.
                //
                // Why: ALTER TABLE … ADD COLUMN stores an explicit DEFAULT in SQLite's
                // column metadata.  Room compares that metadata against the entity and
                // expects defaultValue = 'undefined' for every column that has no
                // @ColumnInfo(defaultValue = …) annotation.  The mismatch causes the
                // IllegalStateException seen in the crash log.
                //
                // The fix is the standard Room-recommended "copy table" migration:
                //   1. create the new table with the exact DDL Room would generate,
                //   2. copy existing rows,
                //   3. drop the old table,
                //   4. rename.

                // Step 1 – new table with FK, no DEFAULT clauses on non-PK columns
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS bookmarks_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        frequencyHz INTEGER NOT NULL,
                        label TEXT NOT NULL,
                        demodMode TEXT NOT NULL,
                        bandwidth INTEGER NOT NULL,
                        squelch REAL NOT NULL,
                        notes TEXT NOT NULL,
                        color INTEGER NOT NULL,
                        favorite INTEGER NOT NULL,
                        bookmarkListId INTEGER,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(bookmarkListId) REFERENCES bookmark_lists(id) ON DELETE SET NULL ON UPDATE NO ACTION
                    )
                """)

                // Step 2 – copy existing rows; fill new columns with sensible values
                database.execSQL("""
                    INSERT INTO bookmarks_new (id, frequencyHz, label, demodMode, bandwidth, squelch,
                                               notes, color, favorite, bookmarkListId, createdAt, updatedAt)
                    SELECT id, frequencyHz, label,
                           '' AS demodMode,
                           0  AS bandwidth,
                           -100.0 AS squelch,
                           '' AS notes,
                           color,
                           0  AS favorite,
                           NULL AS bookmarkListId,
                           createdAt,
                           createdAt AS updatedAt
                    FROM bookmarks
                """)

                // Step 3 – drop old table
                database.execSQL("DROP TABLE bookmarks")

                // Step 4 – rename
                database.execSQL("ALTER TABLE bookmarks_new RENAME TO bookmarks")

                // Indices
                database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_bookmarkListId ON bookmarks (bookmarkListId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_frequencyHz ON bookmarks (frequencyHz)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_favorite ON bookmarks (favorite)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context, AppDatabase::class.java, "ninegradio.db")
                    .addMigrations(MIGRATION_1_2)
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            // Pre-populate with a default bookmark list
                        }
                    })
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  PRE-DEFINED FREQUENCY DATABASE (common bands)
// ═════════════════════════════════════════════════════════════════════════════

object FrequencyDatabase {
    data class FrequencyEntry(
        val name: String,
        val frequencyHz: Long,
        val mode: String,
        val category: String
    )

    val commonFrequencies = listOf(
        // FM Broadcast
        FrequencyEntry("FM Broadcast Band Start", 87_500_000, "WFM", "Broadcast"),
        FrequencyEntry("FM Broadcast Band End",  107_900_000, "WFM", "Broadcast"),

        // Aviation
        FrequencyEntry("ATIS (Generic)",        127_000_000, "AM", "Aviation"),
        FrequencyEntry("International Distress", 121_500_000, "AM", "Aviation"),
        FrequencyEntry("ADS-B",               1_090_000_000, "ADSB", "Aviation"),
        FrequencyEntry("ACARS",                 131_550_000, "ACARS", "Aviation"),

        // Weather
        FrequencyEntry("NOAA WX-1",             162_400_000, "NFM", "Weather"),
        FrequencyEntry("NOAA WX-2",             162_425_000, "NFM", "Weather"),
        FrequencyEntry("NOAA WX-3",             162_450_000, "NFM", "Weather"),
        FrequencyEntry("NOAA WX-4",             162_475_000, "NFM", "Weather"),
        FrequencyEntry("NOAA WX-5",             162_500_000, "NFM", "Weather"),
        FrequencyEntry("NOAA WX-6",             162_525_000, "NFM", "Weather"),
        FrequencyEntry("NOAA WX-7",             162_550_000, "NFM", "Weather"),

        // Amateur Radio
        FrequencyEntry("2m Calling (NA)",       146_520_000, "NFM", "Ham Radio"),
        FrequencyEntry("70cm Calling (NA)",     446_000_000, "NFM", "Ham Radio"),
        FrequencyEntry("40m AM/SSB",              7_200_000, "USB", "Ham Radio"),
        FrequencyEntry("20m USB",                14_225_000, "USB", "Ham Radio"),
        FrequencyEntry("80m USB",                 3_900_000, "LSB", "Ham Radio"),
        FrequencyEntry("WSPR 20m",               14_095_600, "USB", "Ham Radio"),
        FrequencyEntry("FT8 20m",                14_074_000, "USB", "Ham Radio"),

        // Marine
        FrequencyEntry("Marine Channel 16",     156_800_000, "NFM", "Marine"),
        FrequencyEntry("Marine Channel 22A",    157_100_000, "NFM", "Marine"),

        // ISM/IoT
        FrequencyEntry("433 MHz ISM",           433_920_000, "NFM", "ISM"),
        FrequencyEntry("315 MHz ISM",           315_000_000, "NFM", "ISM"),
        FrequencyEntry("868 MHz LoRa",          868_000_000, "NFM", "ISM"),
        FrequencyEntry("915 MHz LoRa",          915_000_000, "NFM", "ISM"),

        // GSM/Cellular (RX only - monitoring, not decoding)
        FrequencyEntry("GSM 900 DL",            935_000_000, "NFM", "Cellular"),
        FrequencyEntry("LTE Band 3 DL (1750 MHz)", 1_750_000_000, "NFM", "Cellular"),

        // Shortwave Broadcasting
        FrequencyEntry("49m Shortwave",           6_000_000, "AM", "Shortwave"),
        FrequencyEntry("25m Shortwave",          11_600_000, "AM", "Shortwave"),
        FrequencyEntry("19m Shortwave",          15_100_000, "AM", "Shortwave"),

        // HF Beacons
        FrequencyEntry("CHU Canada 3.33MHz",      3_330_000, "AM", "HF Beacon"),
        FrequencyEntry("CHU Canada 7.85MHz",      7_850_000, "AM", "HF Beacon"),
        FrequencyEntry("WWV 5MHz",                5_000_000, "AM", "HF Beacon"),
        FrequencyEntry("WWV 10MHz",              10_000_000, "AM", "HF Beacon"),
        FrequencyEntry("WWV 15MHz",              15_000_000, "AM", "HF Beacon"),

        // Satellite
        FrequencyEntry("NOAA-15 APT",           137_620_000, "NFM", "Satellite"),
        FrequencyEntry("NOAA-18 APT",           137_912_500, "NFM", "Satellite"),
        FrequencyEntry("NOAA-19 APT",           137_100_000, "NFM", "Satellite"),
        FrequencyEntry("Meteor-M2 LRPT",        137_100_000, "NFM", "Satellite"),
        FrequencyEntry("ISS APRS",              145_825_000, "APRS", "Satellite"),

        // Paging
        FrequencyEntry("FLEX 929MHz",           929_587_500, "FLEX", "Paging"),

        // APRS
        FrequencyEntry("APRS 144.39MHz (NA)",   144_390_000, "APRS", "APRS"),
        FrequencyEntry("APRS 144.80MHz (EU)",   144_800_000, "APRS", "APRS")
    )
}
