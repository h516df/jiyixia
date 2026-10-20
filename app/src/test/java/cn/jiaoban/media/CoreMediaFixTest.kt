package cn.jiaoban.media

import cn.jiaoban.importing.ImportStorage
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CoreMediaFixTest {
    @Test fun mediaAndImportShareAtomicPublisherAndPreserveOriginal() {
        val root=Files.createTempDirectory("core-media").toFile()
        try {
            val bytes="synthetic picture bytes".toByteArray()
            val media=MediaStorage.copy(root,bytes.inputStream(),"test.jpg","image/jpeg")
            val destination=MediaStorage.file(root,media.relativePath)
            assertArrayEquals(bytes,destination.readBytes())
            assertTrue(ImportStorage.install(root,mapOf(media.relativePath to bytes),setOf(media.relativePath)).isEmpty())
            val second=MediaStorage.copy(root,bytes.inputStream(),"test.jpg","image/jpeg")
            assertEquals(media,second)
            assertFalse(File(root,"imports").listFiles()!!.any{it.name.endsWith(".tmp")})
        } finally { root.deleteRecursively() }
    }
    @Test fun publisherConsumesTemporaryWithoutHardLinkAndDoesNotReplaceCorruptTarget() {
        val root=Files.createTempDirectory("core-publish").toFile()
        try {
            val temp=File(root,".new.tmp").apply{writeText("new bytes")}
            val hash=MediaStorage.hash(temp); val target=File(root,"$hash.jpg")
            assertTrue(AttachmentPublisher.publish(temp,target,hash))
            assertFalse(temp.exists()); assertEquals("new bytes",target.readText())
            target.writeText("keep damaged original")
            temp.writeText("new bytes")
            assertTrue(runCatching{AttachmentPublisher.publish(temp,target,hash)}.isFailure)
            assertEquals("keep damaged original",target.readText()); assertTrue(temp.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun concurrentMediaImportDoesNotOverwriteOrDuplicate() {
        val root=Files.createTempDirectory("concurrent-media").toFile()
        try {
            val bytes=ByteArray(1024){it.toByte()}; val hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            val relative="imports/$hash.jpg"
            val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val a=pool.submit { MediaStorage.copy(root,bytes.inputStream(),"test.jpg","image/jpeg") }
                val b=pool.submit { ImportStorage.install(root,mapOf(relative to bytes),setOf(relative)) }
                a.get();b.get();assertArrayEquals(bytes,File(root,relative).readBytes())
            } finally {pool.shutdownNow()}
        } finally {root.deleteRecursively()}
    }
    @Test fun laterImportFailureRetainsAlreadyPublishedContentForOtherDrafts() {
        val root=Files.createTempDirectory("import-retain").toFile()
        try {
            val bytes="shared original".toByteArray()
            val hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            val good="imports/$hash.jpg";val bad="imports/${"a".repeat(64)}.jpg"
            assertTrue(runCatching{ImportStorage.install(root,linkedMapOf(good to bytes,bad to "wrong".toByteArray()),linkedSetOf(good,bad))}.isFailure)
            assertArrayEquals(bytes,File(root,good).readBytes())
            assertFalse(File(root,bad).exists())
        } finally {root.deleteRecursively()}
    }
    @Test fun grantBeforeResumeStartsOnceOnlyAfterResume() {
        val request=MicrophoneStartRequest()
        assertFalse(request.granted(false)); assertTrue(request.resumed(true)); assertFalse(request.resumed(true))
    }
    @Test fun immediateGrantAndRevocationNeverStartOnUnrelatedResume() {
        val request=MicrophoneStartRequest()
        assertTrue(request.granted(true));assertFalse(request.resumed(true))
        request.granted(false);assertFalse(request.resumed(false));assertFalse(request.resumed(true))
        request.granted(false);request.cancel();assertFalse(request.resumed(true))
    }
}
