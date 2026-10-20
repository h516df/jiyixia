package cn.jiaoban.media

import cn.jiaoban.ImportedAttachment
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Original bytes only. A selected provider URI is never the durable copy. */
object MediaStorage {
    const val MAX_FILE = 1024L * 1024 * 1024
    private val pathPattern = Regex("imports/[a-f0-9]{64}(?:\\.[a-z0-9]{1,8})?")
    fun file(root: File, relative: String): File {
        require(pathPattern.matches(relative)) { "附件路径无效" }
        val directory = File(root, "imports")
        require(directory.canonicalFile == File(root.canonicalFile, "imports")) { "附件目录无效" }
        return File(root, relative).also {
            require(it.canonicalFile.parentFile == directory.canonicalFile) { "附件路径越界" }
        }
    }
    fun hash(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    @Synchronized fun copy(root: File, input: InputStream, name: String, mime: String, maxBytes: Long = MAX_FILE): ImportedAttachment {
        require(mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")) { "请选择照片、视频或录音" }
        val directory = File(root, "imports")
        require(directory.canonicalFile == File(root.canonicalFile, "imports")) { "附件目录无效" }
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory) { "无法创建附件目录" }
        val temporary = File(directory, ".${UUID.randomUUID()}.tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            temporary.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    count += n; require(count <= maxBytes) { "单个附件最多 1 GiB，原文件未改动" }
                    digest.update(buffer, 0, n); output.write(buffer, 0, n)
                }
                require(count > 0) { "文件为空，未添加" }; output.fd.sync()
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val extension = name.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            val relative = "imports/$hash" + (extension?.let { ".$it" } ?: "")
            val destination = file(root, relative)
            AttachmentPublisher.publish(temporary, destination, hash)
            return ImportedAttachment(hash, name.substringAfterLast('/').substringAfterLast('\\').take(240).ifBlank { "媒体附件" }, relative, mime)
        } finally { temporary.delete() }
    }
}
