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

@Database(entities = [ConfirmedNumber::class], version = 1, exportSchema = true)
abstract class HistoryDb : RoomDatabase() {
    abstract fun confirmed(): ConfirmedDao

    companion object {
        @Volatile private var instance: HistoryDb? = null
        fun get(ctx: Context): HistoryDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, HistoryDb::class.java, "history.db").build().also { instance = it }
        }
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
