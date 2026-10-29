package dev.varun.studio.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.varun.studio.core.AppLog

/**
 * Local SQLite store for task history, models, chats, the change journal,
 * audit reports and settings.
 *
 * Single-writer discipline: all mutations go through [writable] on a background
 * dispatcher owned by the repository layer. Reads use a short-lived cursor and
 * are always closed, so a long AI generation cannot leak database handles.
 */
class VarunDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $T_MODELS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                display_name TEXT NOT NULL,
                file_name TEXT NOT NULL,
                absolute_path TEXT NOT NULL UNIQUE,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                quant_tag TEXT,
                format TEXT NOT NULL DEFAULT 'GGUF',
                source_uri TEXT,
                origin TEXT NOT NULL DEFAULT 'import',
                sha256 TEXT,
                added_at INTEGER NOT NULL,
                last_loaded_at INTEGER,
                notes TEXT
            )""",
        )
        db.execSQL(
            """CREATE TABLE $T_SESSIONS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                model_name TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                workspace_path TEXT
            )""",
        )
        db.execSQL(
            """CREATE TABLE $T_MESSAGES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                prompt_tokens INTEGER,
                completion_tokens INTEGER,
                elapsed_ms INTEGER,
                result_code TEXT,
                FOREIGN KEY(session_id) REFERENCES $T_SESSIONS(id) ON DELETE CASCADE
            )""",
        )
        db.execSQL("CREATE INDEX idx_messages_session ON $T_MESSAGES(session_id, created_at)")
        db.execSQL(
            """CREATE TABLE $T_TASKS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                title TEXT NOT NULL,
                state TEXT NOT NULL,
                progress INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                detail TEXT,
                error_message TEXT,
                workspace_path TEXT
            )""",
        )
        db.execSQL(
            """CREATE TABLE $T_JOURNAL (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                workspace_path TEXT NOT NULL,
                relative_path TEXT NOT NULL,
                kind TEXT NOT NULL,
                note TEXT,
                backup_path TEXT,
                sha256 TEXT,
                bytes INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )""",
        )
        db.execSQL("CREATE INDEX idx_journal_ws ON $T_JOURNAL(workspace_path, created_at)")
        db.execSQL(
            """CREATE TABLE $T_REPORTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                workspace_path TEXT NOT NULL,
                title TEXT NOT NULL,
                markdown TEXT NOT NULL,
                findings INTEGER NOT NULL DEFAULT 0,
                tested_locally INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                model_name TEXT
            )""",
        )
        db.execSQL(
            """CREATE TABLE $T_SETTINGS (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 is the first shipping schema. Future migrations must be additive
        // and must never DROP a table containing user source code or history.
        if (oldVersion < newVersion) {
            AppLog.i("VarunDatabase: migrating $oldVersion -> $newVersion (no-op for v1)")
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    // ---------------------------------------------------------------- models

    fun upsertModel(m: ModelRecord): Long {
        val cv = ContentValues().apply {
            put("display_name", m.displayName)
            put("file_name", m.fileName)
            put("absolute_path", m.absolutePath)
            put("size_bytes", m.sizeBytes)
            put("quant_tag", m.quantTag)
            put("format", m.format)
            put("source_uri", m.sourceUri)
            put("origin", m.origin)
            put("sha256", m.sha256)
            put("added_at", m.addedAt)
            put("last_loaded_at", m.lastLoadedAt)
            put("notes", m.notes)
        }
        val existing = writableDatabase.rawQuery(
            "SELECT id FROM $T_MODELS WHERE absolute_path = ?", arrayOf(m.absolutePath),
        ).use { if (it.moveToFirst()) it.getLong(0) else null }
        return if (existing != null) {
            writableDatabase.update(T_MODELS, cv, "id = ?", arrayOf(existing.toString())).toLong()
            existing
        } else {
            writableDatabase.insert(T_MODELS, null, cv)
        }
    }

    fun models(): List<ModelRecord> = readableDatabase.rawQuery(
        "SELECT * FROM $T_MODELS ORDER BY added_at DESC", null,
    ).use { c -> c.mapRows(::modelFrom) }

    fun modelById(id: Long): ModelRecord? = readableDatabase.rawQuery(
        "SELECT * FROM $T_MODELS WHERE id = ?", arrayOf(id.toString()),
    ).use { c -> if (c.moveToFirst()) modelFrom(c) else null }

    fun markModelLoaded(id: Long, at: Long) {
        writableDatabase.execSQL(
            "UPDATE $T_MODELS SET last_loaded_at = ? WHERE id = ?", arrayOf<Any>(at, id),
        )
    }

    fun deleteModel(id: Long): Int =
        writableDatabase.delete(T_MODELS, "id = ?", arrayOf(id.toString()))

    private fun modelFrom(c: Cursor) = ModelRecord(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        displayName = c.getString(c.getColumnIndexOrThrow("display_name")),
        fileName = c.getString(c.getColumnIndexOrThrow("file_name")),
        absolutePath = c.getString(c.getColumnIndexOrThrow("absolute_path")),
        sizeBytes = c.getLong(c.getColumnIndexOrThrow("size_bytes")),
        quantTag = c.nullableString("quant_tag"),
        format = c.getString(c.getColumnIndexOrThrow("format")) ?: "GGUF",
        sourceUri = c.nullableString("source_uri"),
        origin = c.getString(c.getColumnIndexOrThrow("origin")) ?: "import",
        sha256 = c.nullableString("sha256"),
        addedAt = c.getLong(c.getColumnIndexOrThrow("added_at")),
        lastLoadedAt = c.nullableLong("last_loaded_at"),
        notes = c.nullableString("notes"),
    )

    // -------------------------------------------------------------- sessions

    fun insertSession(s: ChatSessionRecord): Long {
        val cv = ContentValues().apply {
            put("title", s.title)
            put("model_name", s.modelName)
            put("created_at", s.createdAt)
            put("updated_at", s.updatedAt)
            put("workspace_path", s.workspacePath)
        }
        return writableDatabase.insert(T_SESSIONS, null, cv)
    }

    fun sessions(): List<ChatSessionRecord> = readableDatabase.rawQuery(
        "SELECT * FROM $T_SESSIONS ORDER BY updated_at DESC LIMIT 200", null,
    ).use { c ->
        c.mapRows {
            ChatSessionRecord(
                id = it.getLong(it.getColumnIndexOrThrow("id")),
                title = it.getString(it.getColumnIndexOrThrow("title")),
                modelName = it.nullableString("model_name"),
                createdAt = it.getLong(it.getColumnIndexOrThrow("created_at")),
                updatedAt = it.getLong(it.getColumnIndexOrThrow("updated_at")),
                workspacePath = it.nullableString("workspace_path"),
            )
        }
    }

    fun deleteSession(id: Long): Int {
        writableDatabase.delete(T_MESSAGES, "session_id = ?", arrayOf(id.toString()))
        return writableDatabase.delete(T_SESSIONS, "id = ?", arrayOf(id.toString()))
    }

    /**
     * Session a message belongs to, or null when the row is gone.
     *
     * Needed because an in-place message UPDATE must not change its own
     * created_at (that would reorder the transcript), yet the session's
     * "last activity" timestamp should still move forward.
     */
    fun messageSessionId(messageId: Long): Long? =
        readableDatabase.rawQuery(
            "SELECT session_id FROM $T_MESSAGES WHERE id = ? LIMIT 1",
            arrayOf(messageId.toString()),
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    fun touchSession(id: Long, at: Long, title: String?) {
        if (title != null) {
            writableDatabase.execSQL(
                "UPDATE $T_SESSIONS SET updated_at = ?, title = ? WHERE id = ?",
                arrayOf<Any>(at, title, id),
            )
        } else {
            writableDatabase.execSQL(
                "UPDATE $T_SESSIONS SET updated_at = ? WHERE id = ?", arrayOf<Any>(at, id),
            )
        }
    }

    fun insertMessage(m: ChatMessageRecord): Long {
        val cv = ContentValues().apply {
            put("session_id", m.sessionId)
            put("role", m.role)
            put("content", m.content)
            put("created_at", m.createdAt)
            put("prompt_tokens", m.promptTokens)
            put("completion_tokens", m.completionTokens)
            put("elapsed_ms", m.elapsedMs)
            put("result_code", m.resultCode)
        }
        return writableDatabase.insert(T_MESSAGES, null, cv)
    }

    fun messages(sessionId: Long, limit: Int = 500): List<ChatMessageRecord> =
        readableDatabase.rawQuery(
            "SELECT * FROM $T_MESSAGES WHERE session_id = ? ORDER BY created_at ASC, id ASC LIMIT ?",
            arrayOf(sessionId.toString(), limit.toString()),
        ).use { c ->
            c.mapRows {
                ChatMessageRecord(
                    id = it.getLong(it.getColumnIndexOrThrow("id")),
                    sessionId = it.getLong(it.getColumnIndexOrThrow("session_id")),
                    role = it.getString(it.getColumnIndexOrThrow("role")),
                    content = it.getString(it.getColumnIndexOrThrow("content")),
                    createdAt = it.getLong(it.getColumnIndexOrThrow("created_at")),
                    promptTokens = it.nullableInt("prompt_tokens"),
                    completionTokens = it.nullableInt("completion_tokens"),
                    elapsedMs = it.nullableLong("elapsed_ms"),
                    resultCode = it.nullableString("result_code"),
                )
            }
        }

    fun updateMessageTokens(id: Long, prompt: Int?, completion: Int?, elapsedMs: Long?, code: String?) {
        writableDatabase.execSQL(
            """UPDATE $T_MESSAGES SET prompt_tokens = ?, completion_tokens = ?,
               elapsed_ms = ?, result_code = ? WHERE id = ?""",
            arrayOf<Any?>(prompt, completion, elapsedMs, code, id),
        )
    }

    // ----------------------------------------------------------------- tasks

    fun insertTask(t: TaskRecord): Long {
        val cv = ContentValues().apply {
            put("kind", t.kind)
            put("title", t.title)
            put("state", t.state)
            put("progress", t.progress)
            put("created_at", t.createdAt)
            put("updated_at", t.updatedAt)
            put("detail", t.detail)
            put("error_message", t.errorMessage)
            put("workspace_path", t.workspacePath)
        }
        return writableDatabase.insert(T_TASKS, null, cv)
    }

    fun updateTaskState(id: Long, state: String, progress: Int, error: String? = null, detail: String? = null) {
        writableDatabase.execSQL(
            """UPDATE $T_TASKS SET state = ?, progress = ?, updated_at = ?,
               error_message = COALESCE(?, error_message),
               detail = COALESCE(?, detail) WHERE id = ?""",
            arrayOf<Any?>(state, progress, System.currentTimeMillis(), error, detail, id),
        )
    }

    fun tasks(limit: Int = 100): List<TaskRecord> = readableDatabase.rawQuery(
        "SELECT * FROM $T_TASKS ORDER BY updated_at DESC LIMIT ?", arrayOf(limit.toString()),
    ).use { c ->
        c.mapRows {
            TaskRecord(
                id = it.getLong(it.getColumnIndexOrThrow("id")),
                kind = it.getString(it.getColumnIndexOrThrow("kind")),
                title = it.getString(it.getColumnIndexOrThrow("title")),
                state = it.getString(it.getColumnIndexOrThrow("state")),
                progress = it.getInt(it.getColumnIndexOrThrow("progress")),
                createdAt = it.getLong(it.getColumnIndexOrThrow("created_at")),
                updatedAt = it.getLong(it.getColumnIndexOrThrow("updated_at")),
                detail = it.nullableString("detail"),
                errorMessage = it.nullableString("error_message"),
                workspacePath = it.nullableString("workspace_path"),
            )
        }
    }

    fun deleteFinishedTasks(): Int =
        writableDatabase.delete(T_TASKS, "state IN ('DONE','FAILED','CANCELLED')", null)

    // -------------------------------------------------------------- journal

    fun insertJournal(j: JournalRecord): Long {
        val cv = ContentValues().apply {
            put("workspace_path", j.workspacePath)
            put("relative_path", j.relativePath)
            put("kind", j.kind)
            put("note", j.note)
            put("backup_path", j.backupPath)
            put("sha256", j.sha256)
            put("bytes", j.bytes)
            put("created_at", j.createdAt)
        }
        return writableDatabase.insert(T_JOURNAL, null, cv)
    }

    fun journal(workspacePath: String?, limit: Int = 300): List<JournalRecord> {
        val (sql, args) = if (workspacePath == null) {
            "SELECT * FROM $T_JOURNAL ORDER BY created_at DESC LIMIT ?" to arrayOf(limit.toString())
        } else {
            "SELECT * FROM $T_JOURNAL WHERE workspace_path = ? ORDER BY created_at DESC LIMIT ?" to
                arrayOf(workspacePath, limit.toString())
        }
        return readableDatabase.rawQuery(sql, args).use { c ->
            c.mapRows {
                JournalRecord(
                    id = it.getLong(it.getColumnIndexOrThrow("id")),
                    workspacePath = it.getString(it.getColumnIndexOrThrow("workspace_path")),
                    relativePath = it.getString(it.getColumnIndexOrThrow("relative_path")),
                    kind = it.getString(it.getColumnIndexOrThrow("kind")),
                    note = it.nullableString("note"),
                    backupPath = it.nullableString("backup_path"),
                    sha256 = it.nullableString("sha256"),
                    bytes = it.getLong(it.getColumnIndexOrThrow("bytes")),
                    createdAt = it.getLong(it.getColumnIndexOrThrow("created_at")),
                )
            }
        }
    }

    // -------------------------------------------------------------- reports

    fun insertReport(r: AuditReportRecord): Long {
        val cv = ContentValues().apply {
            put("workspace_path", r.workspacePath)
            put("title", r.title)
            put("markdown", r.markdown)
            put("findings", r.findings)
            put("tested_locally", if (r.testedLocally) 1 else 0)
            put("created_at", r.createdAt)
            put("model_name", r.modelName)
        }
        return writableDatabase.insert(T_REPORTS, null, cv)
    }

    fun reports(limit: Int = 50): List<AuditReportRecord> = readableDatabase.rawQuery(
        "SELECT * FROM $T_REPORTS ORDER BY created_at DESC LIMIT ?", arrayOf(limit.toString()),
    ).use { c ->
        c.mapRows {
            AuditReportRecord(
                id = it.getLong(it.getColumnIndexOrThrow("id")),
                workspacePath = it.getString(it.getColumnIndexOrThrow("workspace_path")),
                title = it.getString(it.getColumnIndexOrThrow("title")),
                markdown = it.getString(it.getColumnIndexOrThrow("markdown")),
                findings = it.getInt(it.getColumnIndexOrThrow("findings")),
                testedLocally = it.getInt(it.getColumnIndexOrThrow("tested_locally")) != 0,
                createdAt = it.getLong(it.getColumnIndexOrThrow("created_at")),
                modelName = it.nullableString("model_name"),
            )
        }
    }

    fun reportById(id: Long): AuditReportRecord? = reports(200).firstOrNull { it.id == id }

    // ------------------------------------------------------------- settings

    fun getSetting(key: String): String? = readableDatabase.rawQuery(
        "SELECT value FROM $T_SETTINGS WHERE key = ?", arrayOf(key),
    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun putSetting(key: String, value: String) {
        val cv = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        writableDatabase.insertWithOnConflict(T_SETTINGS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun allSettings(): Map<String, String> = readableDatabase.rawQuery(
        "SELECT key, value FROM $T_SETTINGS", null,
    ).use { c ->
        val map = LinkedHashMap<String, String>()
        while (c.moveToNext()) map[c.getString(0)] = c.getString(1)
        map
    }

    /** Erase everything. Called from Settings -> Erase all local data. */
    fun wipe() {
        val db = writableDatabase
        for (t in listOf(T_MESSAGES, T_SESSIONS, T_TASKS, T_JOURNAL, T_REPORTS, T_MODELS, T_SETTINGS)) {
            db.delete(t, null, null)
        }
    }

    private fun Cursor.nullableString(col: String): String? {
        val i = getColumnIndex(col)
        return if (i < 0 || isNull(i)) null else getString(i)
    }

    private fun Cursor.nullableLong(col: String): Long? {
        val i = getColumnIndex(col)
        return if (i < 0 || isNull(i)) null else getLong(i)
    }

    private fun Cursor.nullableInt(col: String): Int? {
        val i = getColumnIndex(col)
        return if (i < 0 || isNull(i)) null else getInt(i)
    }

    private inline fun <T> Cursor.mapRows(f: (Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        while (moveToNext()) out.add(f(this))
        return out
    }

    companion object {
        private const val DB_NAME = "varun.db"
        private const val DB_VERSION = 1

        const val T_MODELS = "models"
        const val T_SESSIONS = "chat_sessions"
        const val T_MESSAGES = "chat_messages"
        const val T_TASKS = "tasks"
        const val T_JOURNAL = "change_journal"
        const val T_REPORTS = "audit_reports"
        const val T_SETTINGS = "settings"

        @Volatile
        private var instance: VarunDatabase? = null

        fun get(context: Context): VarunDatabase =
            instance ?: synchronized(this) {
                instance ?: VarunDatabase(context).also { instance = it }
            }
    }
}
