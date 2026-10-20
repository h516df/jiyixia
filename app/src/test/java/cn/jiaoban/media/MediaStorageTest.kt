package cn.jiaoban.media

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files

class MediaStorageTest {
    @Test fun copiesOriginalAndDeduplicatesWithoutProviderUri() {
        val root=Files.createTempDirectory("media-test").toFile()
        try {
            val bytes=ByteArray(1024*1024){(it%251).toByte()}
            val first=MediaStorage.copy(root,ByteArrayInputStream(bytes),"picture.jpg","image/jpeg")
            val second=MediaStorage.copy(root,ByteArrayInputStream(bytes),"picture.jpg","image/jpeg")
            assertEquals(first,second);assertArrayEquals(bytes,MediaStorage.file(root,first.relativePath).readBytes())
            assertEquals(1,File(root,"imports").listFiles()!!.size)
            assertEquals(first,AttachmentDraft.decode(AttachmentDraft.encode(listOf(first))).single())
        } finally { root.deleteRecursively() }
    }
    @Test fun boundedReadFailureLeavesExistingMediaUntouched() {
        val root=Files.createTempDirectory("media-test").toFile()
        try {
            val good=MediaStorage.copy(root,ByteArrayInputStream(byteArrayOf(1,2)),"voice.m4a","audio/mp4")
            assertTrue(runCatching { MediaStorage.copy(root,ByteArrayInputStream(ByteArray(101)),"large.mp4","video/mp4",100) }.isFailure)
            val fail=object:InputStream(){override fun read():Int=throw java.io.IOException("synthetic failure")}
            assertTrue(runCatching{MediaStorage.copy(root,fail,"broken.mp4","video/mp4")}.isFailure)
            assertArrayEquals(byteArrayOf(1,2),MediaStorage.file(root,good.relativePath).readBytes())
            assertEquals(1,File(root,"imports").listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }
    @Test fun damagedExistingTargetAndTraversalAreNeverOverwritten() {
        val root=Files.createTempDirectory("media-test").toFile()
        try {
            val bytes=byteArrayOf(1,2,3)
            val item=MediaStorage.copy(root,bytes.inputStream(),"file.mp4","video/mp4")
            val file=MediaStorage.file(root,item.relativePath);file.writeText("damaged")
            assertTrue(runCatching{MediaStorage.copy(root,bytes.inputStream(),"file.mp4","video/mp4")}.isFailure)
            assertEquals("damaged",file.readText())
            assertTrue(runCatching{MediaStorage.file(root,"../escape")}.isFailure)
        } finally { root.deleteRecursively() }
    }
}
