package cn.jiaoban.media

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/** All in-process attachment writers share this lock. Android app-data may reject hard links;
 * a completed, synced same-directory temporary file is published by atomic rename instead.
 * Existing content is verified, never replaced. No partial target is exposed on failure. */
object AttachmentPublisher {
    @Synchronized fun publish(temporary: File, destination: File, expectedHash: String): Boolean {
        require(temporary.canonicalFile.parentFile == destination.canonicalFile.parentFile) { "附件临时目录无效" }
        if (destination.exists()) {
            require(destination.isFile && MediaStorage.hash(destination) == expectedHash) { "已有附件校验失败，未覆盖" }
            return false
        }
        require(temporary.isFile && MediaStorage.hash(temporary) == expectedHash) { "附件校验失败" }
        // Same private directory and one shared writer lock: no delete-first or copy fallback.
        Files.move(temporary.toPath(), destination.toPath(), ATOMIC_MOVE)
        return true
    }
}
