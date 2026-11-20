package com.labelrescue.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.labelrescue.core.verify.LookupOutcome
import com.labelrescue.core.verify.TrackingFacts
import com.labelrescue.core.verify.VerificationCache
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/** A tracking number the user confirmed. Mailer IDs and service codes are learned from these. */
@Entity(tableName = "confirmed", indices = [Index(value = ["tracking"], unique = true)])
data class ConfirmedNumber(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tracking: String,
    val confirmedAt: Long,
    /** "photo", "manual" or "import". */
    val source: String,
)

@Dao
interface ConfirmedDao {
    @Query("SELECT * FROM confirmed ORDER BY confirmedAt DESC")
    fun all(): Flow<List<ConfirmedNumber>>

    @Query("SELECT * FROM confirmed ORDER BY confirmedAt DESC")
    suspend fun allOnce(): List<ConfirmedNumber>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(n: ConfirmedNumber)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(n: List<ConfirmedNumber>)

    @Query("DELETE FROM confirmed WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM confirmed")
    suspend fun clear()
}

/**
 * Cached USPS lookups, so a number is never queried twice. [kind] is FOUND, NO_STATUS or
 * NOT_FOUND; [factsJson] holds the tracking facts for FOUND.
 */
@Entity(tableName = "verification")
data class VerificationEntry(
    @PrimaryKey val tracking: String,
    val kind: String,
    val factsJson: String?,
    val code: String?,
    val message: String?,
    val fetchedAt: Long,
)

@Dao
interface VerificationDao {
    @Query("SELECT * FROM verification WHERE tracking = :tracking")
    fun get(tracking: String): VerificationEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(e: VerificationEntry)

    @Query("SELECT COUNT(*) FROM verification")
    fun count(): Int

    @Query("DELETE FROM verification")
    fun clear()
}

@Database(entities = [ConfirmedNumber::class, VerificationEntry::class], version = 2, exportSchema = true)
abstract class HistoryDb : RoomDatabase() {
    abstract fun confirmed(): ConfirmedDao
    abstract fun verification(): VerificationDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `verification` (`tracking` TEXT NOT NULL, `kind` TEXT NOT NULL, `factsJson` TEXT, " +
                        "`code` TEXT, `message` TEXT, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`tracking`))",
                )
            }
        }

        @Volatile private var instance: HistoryDb? = null
        fun get(ctx: Context): HistoryDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, HistoryDb::class.java, "history.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}

/** Room-backed [VerificationCache] (call off the main thread). */
class RoomVerificationCache(private val dao: VerificationDao) : VerificationCache {
    override fun get(trackingNumber: String): LookupOutcome? {
        val e = dao.get(trackingNumber) ?: return null
        return when (e.kind) {
            "FOUND" -> e.factsJson?.let { runCatching { LookupOutcome.Found(TrackingFacts.fromJson(it)) }.getOrNull() }
            "NO_STATUS" -> LookupOutcome.NoStatusYet(e.message)
            "NOT_FOUND" -> LookupOutcome.NotFound(e.code, e.message)
            else -> null
        }
    }

    override fun put(trackingNumber: String, outcome: LookupOutcome) {
        val now = System.currentTimeMillis()
        val e = when (outcome) {
            is LookupOutcome.Found -> VerificationEntry(trackingNumber, "FOUND", outcome.facts.toJson(), null, null, now)
            is LookupOutcome.NoStatusYet -> VerificationEntry(trackingNumber, "NO_STATUS", null, null, outcome.message, now)
            is LookupOutcome.NotFound -> VerificationEntry(trackingNumber, "NOT_FOUND", null, outcome.code, outcome.message, now)
            is LookupOutcome.Failed -> return
        }
        dao.put(e)
    }
}

/** History file format for export/import: plain JSON holding only the confirmed numbers. */
object HistoryFile {
    const val FORMAT = "label-rescue-history"

    fun encode(items: List<ConfirmedNumber>): String = JSONObject()
        .put("format", FORMAT)
        .put("version", 1)
        .put("confirmed", JSONArray().apply {
            for (n in items) put(JSONObject().put("tracking", n.tracking).put("confirmedAt", n.confirmedAt).put("source", n.source))
        })
        .toString(2)

    fun decode(text: String): List<ConfirmedNumber> {
        val o = JSONObject(text)
        require(o.optString("format") == FORMAT) { "Not a Label Rescue history file" }
        val arr = o.getJSONArray("confirmed")
        return (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            ConfirmedNumber(tracking = e.getString("tracking").uppercase(), confirmedAt = e.optLong("confirmedAt", 0), source = e.optString("source", "import"))
        }.filter { n -> n.tracking.all { it.isLetterOrDigit() } && n.tracking.length in 13..34 }
    }
}
