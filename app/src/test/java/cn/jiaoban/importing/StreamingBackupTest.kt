package cn.jiaoban.importing

import cn.jiaoban.ImportedAttachment
import cn.jiaoban.Task
import cn.jiaoban.data.TaskCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StreamingBackupTest {
    @get:Rule val tmp=TemporaryFolder()
    private val manifest="{\"format\":\"shiye-backup\",\"version\":1}".toByteArray()
    private fun record(path:String,hash:String)=Task("synthetic","合成音频",createdAt=Instant.parse("2026-01-01T00:00:00Z"),attachments=listOf(ImportedAttachment(hash,"audio.m4a",path,"audio/mp4")))
    private fun zip(entries:List<Pair<String,ByteArray>>):ByteArray=ByteArrayOutputStream().also{out->ZipOutputStream(out).use{z->entries.forEach{(name,bytes)->z.putNextEntry(ZipEntry(name));z.write(bytes);z.closeEntry()}}}.toByteArray()
    private fun archive(records:List<Task> = emptyList(),extra:List<Pair<String,ByteArray>> = emptyList())=zip(listOf("manifest.json" to manifest,"records.json" to TaskCodec.encode(records).toByteArray())+extra)
    private fun assertRejected(bytes:ByteArray,limits:BackupPackage.Limits=BackupPackage.Limits()) {
        val cache=tmp.newFolder()
        assertThrows(Exception::class.java){BackupPackage.read(bytes.inputStream(),cache,limits)}
        assertEquals(emptyList<File>(),cache.listFiles()!!.toList())
    }

    @Test fun streamsLargerThan32MiBRoundTripWithoutAssetByteArrays() {
        val root=tmp.newFolder();val raw=File(root,"source")
        raw.outputStream().use{out->val block=ByteArray(64*1024){(it%251).toByte()};repeat(529){out.write(block)}}
        val hash=raw.inputStream().use{streamDigest(it).first};val path="imports/$hash.m4a"
        val source=File(root,path);source.parentFile!!.mkdirs();assertTrue(raw.renameTo(source))
        val records=listOf(record(path,hash));val backup=File(tmp.root,"backup.zip")
        backup.outputStream().use{BackupPackage.export(records,root,it)}
        val cache=tmp.newFolder()
        val input=object:FilterInputStream(backup.inputStream()) {
            override fun read(b:ByteArray,off:Int,len:Int):Int {assertTrue(len<=64*1024);return super.read(b,off,len)}
        }
        val plan=BackupPackage.read(input,cache)
        assertTrue(plan.assets.isEmpty());assertEquals(records,plan.records)
        assertEquals(source.length(),plan.stagedAssets.getValue(path).length())
        val restored=tmp.newFolder();ImportStorage.install(restored,plan,plan.assetPaths)
        assertEquals(hash,File(restored,path).inputStream().use{streamDigest(it).first})
        plan.close();assertEquals(0,cache.listFiles()!!.size)
        assertTrue(File(restored,path).exists())
    }

    @Test fun acceptsOldVersionOneAndEmptyBackup() {
        val cache=tmp.newFolder()
        BackupPackage.read(archive().inputStream(),cache).use{assertTrue(it.records.isEmpty());assertTrue(it.assetPaths.isEmpty())}
        assertEquals(0,cache.listFiles()!!.size)
    }

    @Test fun rejectsHashTraversalAbsoluteNonCanonicalAndMissingAssets() {
        val hash=digest(byteArrayOf(1));val path="imports/$hash.mp4"
        assertRejected(archive(listOf(record(path,hash)),listOf(path to byteArrayOf(2))))
        assertRejected(archive(listOf(record(path,hash))))
        for(pathName in listOf("../escape","/escape","imports/../escape","imports//${hash}.mp4","imports\\${hash}.mp4","./records.json"))assertRejected(archive(extra=listOf(pathName to byteArrayOf(1))))
        assertRejected(archive(listOf(record(path,"b".repeat(64))),listOf(path to byteArrayOf(1))))
    }

    @Test fun rejectsDuplicateZipNames() {
        // ZipOutputStream forbids duplicates: create equal-length names, then patch
        // both local and central-directory names without changing payload CRCs.
        val bytes=zip(listOf("manifest.json" to manifest,"records.json" to "[]".toByteArray(),"recordx.json" to "[]".toByteArray()))
        val from="recordx.json".toByteArray();val to="records.json".toByteArray()
        for(i in 0..bytes.size-from.size)if(from.indices.all{bytes[i+it]==from[it]})to.copyInto(bytes,i)
        assertRejected(bytes)
    }

    @Test fun enforcesActualUncompressedCompressedMetadataAndCountLimits() {
        val bytes=ByteArray(4096);val hash=digest(bytes);val path="imports/$hash"
        val backup=archive(listOf(record(path,hash)),listOf(path to bytes))
        assertRejected(backup,BackupPackage.Limits(asset=4095))
        assertRejected(backup,BackupPackage.Limits(total=4095))
        assertRejected(backup,BackupPackage.Limits(metadata=1))
        assertRejected(backup,BackupPackage.Limits(entries=2))
        assertRejected(backup,BackupPackage.Limits(archive=10))
        val tooMany=(0..ImportBundle.MAX_RECORDS).map{Task("id-$it","text",createdAt=Instant.EPOCH)}
        assertRejected(archive(tooMany))
    }

    @Test fun failurePreservesDamagedExistingAssetAndPreviewForRetry() {
        val first=byteArrayOf(1);val second=byteArrayOf(2)
        val h1=digest(first);val h2=digest(second);val p1="imports/$h1";val p2="imports/$h2"
        val records=listOf(record(p1,h1),record(p2,h2).copy(id="second"))
        val cache=tmp.newFolder();val plan=BackupPackage.read(archive(records,listOf(p1 to first,p2 to second)).inputStream(),cache)
        val target=tmp.newFolder();File(target,p2).also{it.parentFile!!.mkdirs();it.writeText("damaged")}
        assertThrows(IllegalArgumentException::class.java){ImportStorage.install(target,plan,linkedSetOf(p1,p2))}
        assertFalse(File(target,p1).exists());assertEquals("damaged",File(target,p2).readText())
        assertTrue(plan.stagedAssets.values.all{it.isFile})
        assertEquals(1,cache.listFiles()!!.size)
        plan.close();assertEquals(0,cache.listFiles()!!.size)
    }

    @Test fun previewLeaseSurvivesUiDisposalUntilImportFinishes() {
        val bytes=byteArrayOf(1);val hash=digest(bytes);val path="imports/$hash"
        val cache=tmp.newFolder();val plan=BackupPackage.read(archive(listOf(record(path,hash)),listOf(path to bytes)).inputStream(),cache)
        plan.retain();plan.close();assertTrue(plan.stagedAssets.getValue(path).exists())
        ImportStorage.install(tmp.newFolder(),plan,plan.assetPaths)
        plan.close();plan.close();assertEquals(0,cache.listFiles()!!.size)
        assertThrows(IllegalStateException::class.java){plan.retain()}
    }

    @Test fun exportAndInstallRejectSymbolicLinkEscapesBeforeMutation() {
        val bytes=byteArrayOf(1);val hash=digest(bytes);val path="imports/$hash"
        val root=tmp.newFolder();val external=tmp.newFolder();File(external,hash).writeBytes(bytes)
        Files.createSymbolicLink(File(root,"imports").toPath(),external.toPath())
        val out=ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java){BackupPackage.export(listOf(record(path,hash)),root,out)}
        assertEquals(0,out.size())
        assertThrows(IllegalArgumentException::class.java){ImportStorage.install(root,mapOf(path to bytes),setOf(path))}
        assertArrayEquals(bytes,File(external,hash).readBytes())
    }

    @Test fun ignoresUnreferencedAssetsRatherThanKeepingExternalArchive() {
        val bytes=byteArrayOf(5);val path="imports/${digest(bytes)}"
        val cache=tmp.newFolder()
        BackupPackage.read(archive(extra=listOf(path to bytes)).inputStream(),cache).use{plan->
            assertTrue(plan.assetPaths.isEmpty())
            assertTrue(cache.walkTopDown().none{it.isFile})
        }
        assertEquals(0,cache.listFiles()!!.size)
    }
    @Test fun readFailureAndInterruptionCleanUpPrivateStaging() {
        val cache=tmp.newFolder();val bytes=archive()
        val broken=object:FilterInputStream(bytes.inputStream()) {
            private var count=0
            override fun read(b:ByteArray,off:Int,len:Int):Int {
                if(count++>0)throw java.io.IOException("synthetic provider failure")
                return super.read(b,off,minOf(len,40))
            }
        }
        assertThrows(java.io.IOException::class.java){BackupPackage.read(broken,cache)}
        assertEquals(0,cache.listFiles()!!.size)
        try {
            Thread.currentThread().interrupt()
            assertThrows(java.io.InterruptedIOException::class.java){BackupPackage.read(bytes.inputStream(),cache)}
        } finally {Thread.interrupted()}
        assertEquals(0,cache.listFiles()!!.size)
    }

    @Test fun stagedTamperingIsRejectedWithoutReplacingExistingData() {
        val bytes=byteArrayOf(1);val hash=digest(bytes);val path="imports/$hash"
        val cache=tmp.newFolder();val root=tmp.newFolder()
        BackupPackage.read(archive(listOf(record(path,hash)),listOf(path to bytes)).inputStream(),cache).use{plan->
            plan.stagedAssets.getValue(path).writeText("tampered")
            assertThrows(IllegalArgumentException::class.java){ImportStorage.install(root,plan,plan.assetPaths)}
            assertFalse(File(root,path).exists())
            assertTrue(root.walkTopDown().none{it.isFile})
            assertTrue(plan.stagedAssets.getValue(path).exists())
        }
    }

}
