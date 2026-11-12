package com.tanvir.voiceassistant.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SavedText(val id: Long, val title: String, val body: String, val kind: String, val createdAt: Long)

class LocalStore(context: Context) : SQLiteOpenHelper(context, "tanvir_voice.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE saved_text (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, body TEXT NOT NULL, kind TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE vocabulary (id INTEGER PRIMARY KEY AUTOINCREMENT, term TEXT NOT NULL UNIQUE, note TEXT NOT NULL DEFAULT '')")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun save(title: String, body: String, kind: String) {
        writableDatabase.insert("saved_text", null, ContentValues().apply {
            put("title", title); put("body", body); put("kind", kind); put("created_at", System.currentTimeMillis())
        })
    }
    fun history(): List<SavedText> {
        val rows = mutableListOf<SavedText>()
        readableDatabase.rawQuery("SELECT id,title,body,kind,created_at FROM saved_text ORDER BY created_at DESC", null).use { cursor ->
            while (cursor.moveToNext()) rows.add(SavedText(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getLong(4)))
        }
        return rows
    }
    fun deleteSaved(id: Long) {
        writableDatabase.delete("saved_text", "id=?", arrayOf(id.toString()))
    }
    fun addVocabulary(term: String, note: String = ""): Boolean {
        val values = ContentValues().apply { put("term", term.trim()); put("note", note) }
        return writableDatabase.insertWithOnConflict("vocabulary", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    fun vocabulary(): List<String> {
        val rows = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT term FROM vocabulary ORDER BY term COLLATE NOCASE", null).use { cursor ->
            while (cursor.moveToNext()) rows.add(cursor.getString(0))
        }
        return rows
    }
    fun deleteVocabulary(term: String) {
        writableDatabase.delete("vocabulary", "term=?", arrayOf(term))
    }
}
