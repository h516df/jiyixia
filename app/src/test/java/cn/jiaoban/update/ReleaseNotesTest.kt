package cn.jiaoban.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReleaseNotesTest {

    private val valid = """
        {"versionName":"0.3","versionCode":3,
         "新增":["App 里就能检查更新"],"改进":["打字更顺"],"修复":["键盘不再挤列表"],"说明":["升级不丢数据"]}
    """.trimIndent()

    @Test
    fun decodeKeepsOrderAndHeadings() {
        val notes = ReleaseNotes.decode(valid)
        assertEquals("0.3", notes.version)
        assertEquals(3, notes.code)
        assertEquals(listOf("新增", "改进", "修复", "说明"), notes.sections.map { it.first })
        assertEquals("App 里就能检查更新", notes.sections.first().second.first())
    }

    @Test
    fun decodeAllowsMissingHeadings() {
        val notes = ReleaseNotes.decode("""{"versionName":"0.3","versionCode":3,"新增":["只有一条"]}""")
        assertEquals(1, notes.sections.size)
    }

    @Test
    fun emptyNotesAreRejected() {
        val failed = runCatching {
            ReleaseNotes.decode("""{"versionName":"0.3","versionCode":3}""")
        }.exceptionOrNull()
        assertTrue(failed is UpdateFailure)
    }

    @Test
    fun badVersionIsRejected() {
        val failed = runCatching {
            ReleaseNotes.decode("""{"versionName":"最新","versionCode":3,"新增":["x"]}""")
        }.exceptionOrNull()
        assertTrue(failed is UpdateFailure)
    }
}

class UpdateVerifyTest {

    @Test
    fun parseShaFileTakesTheHash() {
        val hash = "b0d510d095d0400901e760fc49cc6376a3cf87f68557ff257422186bfe83a9d2"
        assertEquals(hash, UpdateVerify.parseShaFile("$hash  app-release.apk\n"))
        assertEquals(hash, UpdateVerify.parseShaFile(hash.uppercase()))
        assertTrue(runCatching { UpdateVerify.parseShaFile("没有哈希") }.exceptionOrNull() is UpdateFailure)
    }

    @Test
    fun nativeAbisReadsTheZip() {
        val file = File.createTempFile("fake", ".apk")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("lib/arm64-v8a/libx.so"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
            assertEquals(setOf("arm64-v8a"), UpdateVerify.nativeAbis(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun universalApkHasNoNativeAbis() {
        val file = File.createTempFile("fake", ".apk")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
            assertEquals(emptySet<String>(), UpdateVerify.nativeAbis(file))
        } finally {
            file.delete()
        }
    }
}
