package com.mammyframe.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Where AI rotate remembers each photo. One row per photo, so saving a result only writes that
 * photo (not the whole list), which matters with tens of thousands of photos.
 */
class RotateDb(c: Context) : SQLiteOpenHelper(c, "airotate.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE photos (
                uri TEXT PRIMARY KEY, folder TEXT NOT NULL, name TEXT, mime TEXT,
                modified INTEGER, size INTEGER, origExif INTEGER, fix INTEGER, certainty REAL,
                decision INTEGER, applied INTEGER, method TEXT)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun loadAll(): List<AiRotate.Rec> = readableDatabase.query("photos", null, null, null, null, null, null).use { cur ->
        val out = ArrayList<AiRotate.Rec>(cur.count)
        while (cur.moveToNext()) {
            out.add(
                AiRotate.Rec(
                    uri = cur.getString(0), folder = cur.getString(1), name = cur.getString(2) ?: "",
                    mime = cur.getString(3) ?: "", modified = cur.getLong(4), size = cur.getLong(5),
                    origExif = cur.getInt(6), fix = cur.getInt(7), certainty = cur.getFloat(8),
                    decision = cur.getInt(9), applied = cur.getInt(10),
                    method = runCatching { AiRotate.Method.valueOf(cur.getString(11)) }.getOrDefault(AiRotate.Method.APP),
                )
            )
        }
        out
    }

    fun put(r: AiRotate.Rec) {
        writableDatabase.insertWithOnConflict("photos", null, ContentValues().apply {
            put("uri", r.uri); put("folder", r.folder); put("name", r.name); put("mime", r.mime)
            put("modified", r.modified); put("size", r.size); put("origExif", r.origExif)
            put("fix", r.fix); put("certainty", r.certainty); put("decision", r.decision)
            put("applied", r.applied); put("method", r.method.name)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun delete(uri: String) {
        writableDatabase.delete("photos", "uri = ?", arrayOf(uri))
    }

    /** Many changes in one go (much faster, and all-or-nothing). */
    fun <T> batch(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }
}
