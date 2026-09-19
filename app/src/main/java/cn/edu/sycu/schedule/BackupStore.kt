package cn.edu.sycu.schedule

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

internal object BackupStore {
    val revision = mutableIntStateOf(0)
    val recoveryRequired = mutableStateOf(false)
    val inProgress = mutableStateOf(false)
    @Volatile var restoring = false
    private val lock = Mutex()
    private fun journal(context: Context) = AtomicFile(File(context.filesDir, "restore-rollback.json"))
    fun recoveryPending(context: Context): Boolean {
        val file = journal(context).baseFile
        return file.exists() || File(file.path + ".bak").exists()
    }
    private fun preferences(context: Context): JSONObject = JSONObject().apply {
        BackupCodec.groups.forEach { group ->
            val values = JSONObject()
            context.getSharedPreferences(group, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                if (BackupCodec.allowed(group, key)) values.put(key, if (value is Set<*>) JSONArray(value.toList().sortedBy { it.toString() }) else value)
            }
            put(group, values)
        }
    }
    private fun writePreferences(context: Context, data: JSONObject) {
        BackupCodec.groups.forEach { group ->
            val prefs = context.getSharedPreferences(group, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            prefs.all.keys.filter { BackupCodec.allowed(group, it) }.forEach { editor.remove(it) }
            val values = data.getJSONObject(group)
            values.keys().forEach { key ->
                when (val value = values.get(key)) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is String -> editor.putString(key, value)
                    is JSONArray -> editor.putStringSet(key, (0 until value.length()).map { value.getString(it) }.toSet())
                    else -> error("备份设置无效")
                }
            }
            check(editor.commit()) { "无法写入设置，请检查设备空间" }
        }
    }
    private suspend fun snapshot(context: Context, db: ScheduleDb, name: String): AppBackup = db.withTransaction {
        val tables = db.dao().timetables().first()
        val prefs = preferences(context)
        val display = prefs.getJSONObject("display")
        if (tables.none { it.id == display.optString("active_table") }) display.remove("active_table")
        AppBackup(name, Instant.now().toString(), tables.map { ImportedSchedule(it, db.dao().allCourses(it.id)) }, prefs)
    }
    suspend fun export(context: Context, name: String): String = lock.withLock {
        withContext(Dispatchers.IO) {
            val db = ScheduleDb.open(context)
            try { BackupCodec.encode(snapshot(context, db, name)) } finally { db.close() }
        }
    }
    private suspend fun replace(context: Context, db: ScheduleDb, backup: AppBackup) = db.withTransaction {
        db.dao().clearAllCourses()
        db.dao().clearAllTables()
        backup.schedules.forEach { db.dao().importSchedule(it) }
        writePreferences(context, backup.preferences)
    }
    suspend fun restore(context: Context, backup: AppBackup) = lock.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            val validated = BackupCodec.decode(BackupCodec.encode(backup))
            val db = ScheduleDb.open(context)
            val file = journal(context)
            try {
                check(!recoveryPending(context)) { "尚有未完成的恢复，请重新启动应用" }
                val previous = snapshot(context, db, "恢复前自动保护")
                replaceWithRollback(previous, validated, persist = {
                    val bytes = BackupCodec.encode(it).toByteArray(Charsets.UTF_8)
                    val stream = file.startWrite()
                    try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
                    // Do not mutate data until the protection snapshot can be read back.
                    file.openRead().use { input -> BackupCodec.read(input) }
                }, replace = { replace(context, db, it) }, clear = { file.delete(); check(!recoveryPending(context)) { "恢复记录尚未清理" } })
            } finally { db.close() }
        }
    }
    /** Run before opening UI or background readers. An interrupted restore rolls back on next launch. */
    fun recover(context: Context) {
        val file = journal(context)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return
        runBlocking(Dispatchers.IO) {
            val previous = file.openRead().use { BackupCodec.read(it) }
            val db = ScheduleDb.open(context)
            try { replace(context, db, previous); file.delete(); check(!recoveryPending(context)) } finally { db.close() }
        }
    }
}
