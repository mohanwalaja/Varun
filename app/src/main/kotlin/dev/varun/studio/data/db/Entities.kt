package dev.varun.studio.data.db

/**
 * Row types for the local SQLite database.
 *
 * Plain data classes rather than Room: Room would add an annotation processor,
 * a codegen step and several megabytes of dependencies to a project whose
 * database is six small tables. SQLiteOpenHelper does the job and keeps the
 * build fast and auditable on a phone-only workflow.
 */

data class ModelRecord(
    val id: Long = 0,
    val displayName: String,
    val fileName: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val quantTag: String?,
    val format: String = "GGUF",
    val sourceUri: String?,
    val origin: String,           // "import" | "download" | "catalog:<id>"
    val sha256: String?,
    val addedAt: Long,
    val lastLoadedAt: Long?,
    val notes: String?,
)

data class ChatSessionRecord(
    val id: Long = 0,
    val title: String,
    val modelName: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val workspacePath: String?,
)

data class ChatMessageRecord(
    val id: Long = 0,
    val sessionId: Long,
    val role: String,             // "user" | "assistant" | "system"
    val content: String,
    val createdAt: Long,
    // Inference statistics. Only an assistant message produced by a real
    // generation carries these; records built purely to project existing chat
    // history back into a prompt have none, so they default to null. Every
    // persistence path still passes explicit values.
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val elapsedMs: Long? = null,
    val resultCode: String? = null,
)

data class TaskRecord(
    val id: Long = 0,
    val kind: String,             // see AgentTask.Kind
    val title: String,
    val state: String,            // QUEUED | RUNNING | DONE | FAILED | CANCELLED
    val progress: Int,            // 0..100
    val createdAt: Long,
    val updatedAt: Long,
    val detail: String?,
    val errorMessage: String?,
    val workspacePath: String?,
)

data class JournalRecord(
    val id: Long = 0,
    val workspacePath: String,
    val relativePath: String,
    val kind: String,             // PROPOSED | APPLIED | REJECTED | ROLLED_BACK
    val note: String?,
    val backupPath: String?,
    val sha256: String?,
    val bytes: Long,
    val createdAt: Long,
)

data class AuditReportRecord(
    val id: Long = 0,
    val workspacePath: String,
    val title: String,
    val markdown: String,
    val findings: Int,
    val testedLocally: Boolean,
    val createdAt: Long,
    val modelName: String?,
)

data class SettingRecord(
    val key: String,
    val value: String,
)
