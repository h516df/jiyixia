package cn.jiaoban.data

import cn.jiaoban.Task
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.json.JSONArray
import java.time.Instant

/**
 * 存一个 JSON 文件。写的时候先写临时文件再改名，旧文件留一份 .bak，
 * 主文件读不出来时自动退回备份，避免一次坏写把记录全丢掉。
 */
class TaskStore(private val file: File) {

    internal val identity: String get() = file.canonicalPath

    private val backup = File(file.parentFile, file.name + ".bak")

    fun load(): List<Task> {
        readOrNull(file)?.let { return it }
        readOrNull(backup)?.let { return it }
        if (!file.exists() && !backup.exists()) return emptyList()
        // Corruption and an empty library are NOT equivalent. Never let a later
        // save replace a damaged user's only surviving data with an empty list.
        throw IllegalStateException("记录文件及备用文件均无法读取；原文件未修改")
    }

    fun save(tasks: List<Task>) {
        val text = TaskCodec.encode(tasks)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            if (file.isFile && readOrNull(file)!=null) {
                val backupTmp = File(file.parentFile, file.name + ".bak.tmp")
                try {
                    backupTmp.outputStream().use { out -> file.inputStream().use { it.copyTo(out) }; out.fd.sync() }
                    atomicReplace(backupTmp, backup)
                } finally { if(backupTmp.isFile)backupTmp.delete() }
            }
            atomicReplace(tmp, file)
        } finally { if(tmp.isFile)tmp.delete() }
    }

    // File.renameTo cannot replace an existing target on some JVM platforms.
    // Require an atomic same-directory move. Unsupported filesystems fail closed;
    // never delete the destination first or fall back to a non-atomic overwrite.
    private fun atomicReplace(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (failure: java.io.IOException) {
            throw IllegalStateException("原子提交失败，目标文件未改动", failure)
        }
    }

    private fun readOrNull(f: File): List<Task>? {
        if (!f.isFile) return null
        return runCatching {
            val raw = f.readText(Charsets.UTF_8)
            val rows = JSONArray(raw)
            val ids = mutableSetOf<String>()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                require(row.getString("id").isNotBlank() && ids.add(row.getString("id")))
                Instant.parse(row.getString("createdAt"))
            }
            TaskCodec.decode(raw).also { require(it.size == rows.length()) }
        }.getOrNull()
    }
}
