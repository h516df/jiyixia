package cn.jiaoban.data

import cn.jiaoban.Task
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * 存一个 JSON 文件。写的时候先写临时文件再改名，旧文件留一份 .bak，
 * 主文件读不出来时自动退回备份，避免一次坏写把记录全丢掉。
 */
class TaskStore(private val file: File) {

    internal val identity: String get() = file.canonicalPath

    private val backup = File(file.parentFile, file.name + ".bak")

    fun load(): List<Task> {
        readOrNull(file)?.let { return it }
        return readOrNull(backup) ?: emptyList()
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
        return runCatching { TaskCodec.decode(f.readText(Charsets.UTF_8)) }.getOrNull()
    }
}
