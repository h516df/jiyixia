package cn.jiaoban.data

import cn.jiaoban.Task
import java.io.File

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
        tmp.writeText(text, Charsets.UTF_8)
        if (file.isFile) {
            runCatching { file.copyTo(backup, overwrite = true) }
        }
        if (!tmp.renameTo(file)) {
            file.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun readOrNull(f: File): List<Task>? {
        if (!f.isFile) return null
        return runCatching { TaskCodec.decode(f.readText(Charsets.UTF_8)) }.getOrNull()
    }
}
