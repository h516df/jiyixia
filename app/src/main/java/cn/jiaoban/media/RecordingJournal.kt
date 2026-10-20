package cn.jiaoban.media

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** The final filename is the journal commit: only a successfully stopped recording is renamed.
 * Interrupted .part files are deliberately retained, but are never advertised as playable audio.
 * A process kill can lose the current M4A segment; completed segments remain recoverable.
 */
class RecordingJournal(
    filesDir: File,
    sessionId: String,
    private val syncSegment: (File) -> Unit = { file -> RandomAccessFile(file, "rw").use { it.fd.sync() } },
    private val renameSegment: (File, File) -> Boolean = { source, target -> source.renameTo(target) },
) {
    private val stoppedInMemory = linkedSetOf<File>()
    private val baseDirectory = filesDir.canonicalFile
    private val recordingsDirectory = File(baseDirectory, "recordings")
    val directory = File(recordingsDirectory, sha256(sessionId.toByteArray(Charsets.UTF_8)))

    private fun requireContainedDirectory() {
        require(recordingsDirectory.canonicalFile.parentFile == baseDirectory)
        require(directory.canonicalFile.parentFile == recordingsDirectory.canonicalFile)
    }

    fun newSegment(): File {
        requireContainedDirectory()
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建录音目录")
        return File(directory, "${System.currentTimeMillis()}-${UUID.randomUUID()}.part")
    }

    fun finalizeSegment(active: File): File {
        requireContainedDirectory()
        require(active.canonicalFile.parentFile == directory.canonicalFile)
        require(active.parentFile?.canonicalFile == directory.canonicalFile && active.extension == "part")
        if (!active.isFile || active.length() == 0L) throw IOException("录音片段为空")
        // This method must only be called after MediaRecorder.stop() succeeds.
        // Keep memory evidence even if persisting the stopped marker fails.
        stoppedInMemory.add(active)
        val marker = File(directory, "${active.name}.stopped")
        if (!marker.isFile) {
            val temporary = File(directory, "${active.name}-${UUID.randomUUID()}.stop-part")
            FileOutputStream(temporary).use { output ->
                output.write(active.name.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!temporary.renameTo(marker)) throw IOException("无法保存录音完成标记；原件仍保留")
        }
        syncSegment(active)
        val completed = File(directory, "${active.nameWithoutExtension}.m4a")
        if (!renameSegment(active, completed)) throw IOException("无法保存录音片段；原件仍保留")
        stoppedInMemory.remove(active)
        return completed
    }

    /** Only explicit successful-stop markers (or current-process evidence) allow promotion.
     * An ordinary abandoned .part from a process crash is never promoted. */
    fun retryPendingFinalization(): Boolean {
        requireContainedDirectory()
        val pending = stoppedInMemory.toMutableSet()
        directory.listFiles()?.filter { it.extension == "stopped" && it.isFile && it.length() <= 200L }
            ?.forEach { marker ->
                val name = marker.name.removeSuffix(".stopped")
                if (marker.canonicalFile.parentFile == directory.canonicalFile &&
                    name.endsWith(".part") && marker.readText(Charsets.UTF_8) == name) {
                    val file = File(directory, name)
                    if (file.isFile) pending.add(file)
                }
            }
        var allFinalized = true
        pending.forEach { file ->
            if (runCatching { finalizeSegment(file) }.isFailure) allFinalized = false
        }
        return allFinalized
    }

    fun recoverFinalized(): List<File> {
        requireContainedDirectory()
        return directory.listFiles()
            ?.filter { it.isFile && it.extension == "m4a" && it.length() > 0L && it.canonicalFile.parentFile == directory.canonicalFile }
            ?.sortedBy { it.name }.orEmpty()
    }


    /** A durable delivery receipt prevents a deliberately removed draft attachment reappearing.
     * Written only after the caller has durably saved its attachment reference. */
    internal fun wasDelivered(hash: String): Boolean {
        requireContainedDirectory()
        require(hash.matches(Regex("[0-9a-f]{64}")))
        val receipt = File(directory, "$hash.delivered")
        return receipt.isFile && receipt.canonicalFile.parentFile == directory.canonicalFile &&
            receipt.length() == 64L && receipt.readText(Charsets.US_ASCII) == hash
    }

    internal fun acknowledgeDelivery(hash: String) {
        requireContainedDirectory()
        require(hash.matches(Regex("[0-9a-f]{64}")))
        if (wasDelivered(hash)) return
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法保存录音回执")
        val temporary = File(directory, "$hash-${UUID.randomUUID()}.receipt-part")
        FileOutputStream(temporary).use { output ->
            output.write(hash.toByteArray(Charsets.US_ASCII))
            output.fd.sync()
        }
        if (!temporary.renameTo(File(directory, "$hash.delivered"))) {
            throw IOException("附件已保存，但录音回执写入失败；原件仍保留")
        }
    }

    companion object {
        fun contentHash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Durable hash receipts suppress repeat delivery across controller recreation. If the process
 * exits after callback success but before receipt commit, the caller must deduplicate the retry. */
internal class RecordingDelivery(
    private val journal: RecordingJournal,
    private val callback: (File) -> Unit,
) {
    fun deliver(file: File) {
        val hash = RecordingJournal.contentHash(file)
        if (!journal.wasDelivered(hash)) {
            callback(file)
            journal.acknowledgeDelivery(hash)
        }
    }
}
