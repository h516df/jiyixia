package cn.jiaoban.importing

import cn.jiaoban.*
import cn.jiaoban.data.TaskCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImportParserTest {
    @get:Rule val tmp=TemporaryFolder()
    private val now=Instant.parse("2026-10-19T00:00:00Z")
    private fun text(name:String,text:String)=ImportBundle.read(name,text.byteInputStream())
    private fun fixture(path:String)=javaClass.getResourceAsStream("/import-fixtures/$path")!!.readBytes()
    private fun zip(vararg entries:Pair<String,ByteArray>):ByteArray=ByteArrayOutputStream().also{out->ZipOutputStream(out).use{zip->entries.forEach{(name,bytes)->zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()}}}.toByteArray()
    @Test fun thinoDailyKeepsMultipleBlocksAndResidualWithoutRunningCode(){
        val bytes=fixture("obsidian/Daily/2024-05-22.md")
        val plan=ImportParser.parse(ImportBundle.read("2024-05-22.md",bytes.inputStream()),ImportFormat.THINO_DAILY,now=now)
        assertEquals(5,plan.records.size)
        assertEquals(4,plan.records.count{it.importInfo!!.originalDate!!.contains('T')})
        assertEquals(1,plan.records.count{it.text.contains("围栏内是假时间")})
        assertEquals(1,plan.records.count{it.text.contains("不在选定标题下")})
        assertTrue(plan.records.any{it.text.startsWith("[ ]")});assertTrue(plan.records.any{it.text.startsWith("[x]")})
        assertTrue(plan.records.all{it.journalDate==null});assertTrue(plan.warnings.any{it.contains("附件未找到")})
        assertEquals(plan.records.map{it.id},ImportParser.parse(ImportBundle.read("2024-05-22.md",bytes.inputStream()),ImportFormat.THINO_DAILY,now=now.plusSeconds(1)).records.map{it.id})
        assertEquals(0,plan.additions(plan.records).size)
    }
    @Test fun intentionalSameMinuteAndSameBodyDuplicatesSurvive(){
        val plan=ImportParser.parse(text("2024-01-02.md","# Journal\n- 8:05 相同文字\n- 8:05 相同文字"),ImportFormat.THINO_DAILY,now=now)
        assertEquals(2,plan.records.size);assertEquals(2,plan.records.map{it.id}.distinct().size)
    }
    @Test fun unknownThinoModeAndInvalidTimeFailBeforeAnyWrite(){
        assertThrows(IllegalArgumentException::class.java){ImportParser.parse(text("memo.md","id: abc\ncreatedAt: now"),ImportFormat.THINO_DAILY)}
        assertThrows(IllegalArgumentException::class.java){ImportParser.parse(text("2024-01-02.md","# Journal\n- 25:99 错误"),ImportFormat.THINO_DAILY)}
    }
    @Test fun headingMustMatchExactly(){assertThrows(IllegalArgumentException::class.java){ImportParser.parse(text("2024-01-02.md","# Journals\n- 12:00 测试"),ImportFormat.THINO_DAILY)}}
    @Test fun obsidianKeepsRawMarkdownAndDateOnlyWithoutInventingTime(){
        val raw="---\ntags: [生活]\n---\n# 原标题\n正文 ![[图片.png]]"
        val plan=ImportParser.parse(text("2024-05-22.md",raw),ImportFormat.OBSIDIAN,now=now)
        val record=plan.records.single();assertEquals(raw,record.text);assertEquals(LocalDate.of(2024,5,22),record.journalDate)
        assertEquals("2024-05-22",record.importInfo!!.originalDate);assertEquals(now,record.createdAt)
        assertArrayEquals(raw.toByteArray(),plan.assets.getValue(record.attachments.single().relativePath))
    }
    @Test fun undatedObsidianStaysRecordAndDoesNotUseFileMtime(){val plan=ImportParser.parse(text("想法.md","原文"),ImportFormat.OBSIDIAN,now=now);assertNull(plan.records.single().journalDate);assertNull(plan.records.single().importInfo!!.originalDate);assertTrue(plan.warnings.isNotEmpty())}
    @Test fun attachmentsResolveWithinSelectedVaultAndRemoteImagesAreNotFetched(){
        val archive=zip("Daily/2024-05-22.md" to "文字 ![图](../Attachments/a.png) ![](https://example.test/a.png)".toByteArray(),"Attachments/a.png" to byteArrayOf(1,2,3))
        val plan=ImportParser.parse(ImportBundle.read("vault.zip",archive.inputStream()),ImportFormat.OBSIDIAN,now=now)
        assertEquals(1,plan.records.single().attachments.count{!it.sourceFile});assertTrue(plan.warnings.any{it.contains("不下载")})
        assertFalse(plan.records.single().attachments.any{it.name=="vault.zip"})
        assertTrue(plan.records.single().attachments.any{it.name=="2024-05-22.md" && it.sourceFile})
    }
    @Test fun explicitRelativeAttachmentKeepsChosenFileAcrossRepeatAndBackup() {
        val archive=zip("Daily/n.md" to "![图](./a.png)".toByteArray(),
            "Daily/a.png" to byteArrayOf(1,2,3), "a.png" to byteArrayOf(8,9))
        val plan=ImportParser.parse(ImportBundle.read("vault.zip",archive.inputStream()),ImportFormat.OBSIDIAN,now=now)
        val media=plan.records.single().attachments.single{!it.sourceFile}
        assertArrayEquals(byteArrayOf(1,2,3),plan.assets.getValue(media.relativePath))
        assertFalse(plan.warnings.any{it.contains("歧义")})
        assertFalse(plan.assets.values.any{it.contentEquals(byteArrayOf(8,9))})
        val repeated=ImportParser.parse(ImportBundle.read("vault.zip",archive.inputStream()),ImportFormat.OBSIDIAN,now=now.plusSeconds(10))
        assertEquals(1,repeated.duplicateCount(plan.records));assertTrue(repeated.additions(plan.records).isEmpty())
        ImportStorage.install(tmp.root,plan.assets,plan.records.flatMap{it.attachments}.map{it.relativePath}.toSet())
        val backup=ByteArrayOutputStream().also{BackupPackage.export(plan.records,tmp.root,it)}.toByteArray()
        val restored=BackupPackage.parse(ImportBundle.read("backup.zip",backup.inputStream()))
        assertEquals(plan.records,restored.records)
        assertArrayEquals(byteArrayOf(1,2,3),restored.assets.getValue(media.relativePath))
    }
    @Test fun missingExplicitRelativeAttachmentNeverFallsBackToRoot() {
        for(link in listOf("./a.png","%2E%2Fa.png","../missing/a.png","../../a.png")) {
            val archive=zip("Daily/n.md" to "![图]($link)".toByteArray(),"a.png" to byteArrayOf(8,9))
            val plan=ImportParser.parse(ImportBundle.read("vault.zip",archive.inputStream()),ImportFormat.OBSIDIAN,now=now)
            assertTrue(link,plan.records.single().attachments.all{it.sourceFile})
            assertTrue(link,plan.warnings.any{it.contains("附件未找到")})
            assertFalse(link,plan.assets.values.any{it.contentEquals(byteArrayOf(8,9))})
            assertEquals("![图]($link)",plan.records.single().text)
        }
    }
    @Test fun ambiguousAttachmentIsNotSilentlyAssigned(){
        val archive=zip("2024-05-22.md" to "![[a.png]]".toByteArray(),"one/a.png" to byteArrayOf(1),"two/a.png" to byteArrayOf(2))
        val plan=ImportParser.parse(ImportBundle.read("vault.zip",archive.inputStream()),ImportFormat.OBSIDIAN)
        assertEquals(0,plan.records.single().attachments.count{!it.sourceFile});assertTrue(plan.warnings.any{it.contains("歧义")})
    }
    @Test fun zipSlipAbsoluteAndDuplicateNormalizedPathsAreRejected(){
        for(path in listOf("../escape.md","/absolute.md","C:/escape.md"))assertThrows(IllegalArgumentException::class.java){ImportBundle.read("bad.zip",zip(path to byteArrayOf(1)).inputStream())}
        assertThrows(IllegalArgumentException::class.java){ImportBundle.read("bad.zip",zip("a.md" to byteArrayOf(1),"./a.md" to byteArrayOf(2)).inputStream())}
    }
    @Test fun diariumSameTimestampEntriesRemainSeparateAndUnknownMetadataSurvives(){
        val bytes=fixture("diarium/entries.json");val plan=ImportParser.parse(ImportBundle.read("entries.json",bytes.inputStream()),ImportFormat.DIARIUM,now=now)
        assertEquals(2,plan.records.size);assertEquals(2,plan.records.map{it.id}.distinct().size)
        assertEquals("2024-05-22T08:05:00.1234567",plan.records.first().importInfo!!.originalDate)
        assertEquals(listOf("测试"),plan.records.first().importedTags);assertTrue(plan.records.first().importInfo!!.metadata!!.contains("tracker"))
        assertFalse(plan.records.first().text.contains("<strong>"));assertEquals(now,plan.records.first().createdAt)
    }
    @Test fun diariumObjectAndMediaZipAreSupported(){
        val json="""{"date":"2024-05-22T08:05:00.1234567","heading":"标题","html":"<p>正文</p>","tags":[]}"""
        val archive=zip("entry.json" to json.toByteArray(),"media/2024-05-22_080500123/a.png" to byteArrayOf(3,4))
        val plan=ImportParser.parse(ImportBundle.read("diarium.zip",archive.inputStream()),ImportFormat.DIARIUM)
        assertEquals(1,plan.records.single().attachments.count{!it.sourceFile})
    }
    @Test fun unknownJsonAndBadDateAreRejected(){
        for(raw in listOf("""{"entries":[]}""","""{"date":"not-a-date","html":"body"}"""))assertThrows(Exception::class.java){ImportParser.parse(text("x.json",raw),ImportFormat.DIARIUM)}
    }
    @Test fun htmlIsPlainTextAndNoRemoteResourceRuns(){val p=ImportParser.parse(text("x.json","""{"date":"2024-01-02T08:00:00","html":"<img src='https://example.test/a'><script>alert(1)</script><p onclick='x()'>正文</p>"}"""),ImportFormat.DIARIUM);assertTrue(p.records.single().text.contains("正文"));assertFalse(p.records.single().text.contains("<script>"));assertEquals(0,p.records.single().attachments.count{!it.sourceFile})}
    @Test fun mojiIsOriginalFileOnlyAndRequiresManualDate(){
        val bundle=text("墨记.pdf","%PDF-synthetic-only")
        assertThrows(IllegalArgumentException::class.java){ImportParser.parse(bundle,ImportFormat.MOJI_ORIGINAL)}
        val plan=ImportParser.parse(bundle,ImportFormat.MOJI_ORIGINAL,manualDate=LocalDate.of(2024,5,22),now=now)
        assertNull(plan.records.single().importInfo!!.originalDate);assertEquals(LocalDate.of(2024,5,22),plan.records.single().journalDate);assertTrue(plan.warnings.single().contains("仅收存"))
    }
    @Test fun importedMetadataAndAttachmentsRoundTripThroughOldCodec(){val record=ImportParser.parse(text("2024-05-22.md","完整原文"),ImportFormat.OBSIDIAN,now=now).records.single();assertEquals(record,TaskCodec.decode(TaskCodec.encode(listOf(record))).single())}
    @Test fun completeBackupRoundTripRestoresAttachmentsAndLegacyRecords(){
        val plan=ImportParser.parse(text("2024-05-22.md","完整原文"),ImportFormat.OBSIDIAN,now=now)
        ImportStorage.install(tmp.root,plan.assets,plan.records.flatMap{it.attachments}.map{it.relativePath}.toSet())
        val old=Task("legacy","旧事项",createdAt=now,kind=EntryKind.TODO)
        val records=listOf(old)+plan.records;val out=ByteArrayOutputStream();BackupPackage.export(records,tmp.root,out)
        val restored=BackupPackage.parse(ImportBundle.read("backup.zip",out.toByteArray().inputStream()))
        assertEquals(records,restored.records);assertEquals(0,restored.additions(records).size)
        assertArrayEquals(plan.assets.values.single(),restored.assets.values.single())
    }
    @Test fun corruptedBackupAttachmentIsRejected(){
        val plan=ImportParser.parse(text("2024-05-22.md","原文"),ImportFormat.OBSIDIAN)
        val attachment=plan.records.single().attachments.single()
        val archive=zip("manifest.json" to """{"format":"shiye-backup","version":1}""".toByteArray(),"records.json" to TaskCodec.encode(plan.records).toByteArray(),attachment.relativePath to byteArrayOf(9))
        assertThrows(IllegalArgumentException::class.java){BackupPackage.parse(ImportBundle.read("bad.zip",archive.inputStream()))}
    }

    @Test fun directoryPayloadAndEntryFloodAreRejected(){
        assertThrows(IllegalArgumentException::class.java){ImportBundle.read("bad.zip",zip("dir/" to byteArrayOf(1),"a.md" to byteArrayOf(2)).inputStream())}
        val entries=(0..ImportBundle.MAX_ENTRIES).map{"dir$it/" to byteArrayOf()}.toTypedArray()
        assertThrows(IllegalArgumentException::class.java){ImportBundle.read("bad.zip",zip(*entries).inputStream())}
    }
    @Test fun changedOriginalOrMediaIsNeverSilentlyDuplicate(){
        fun moji(value:Byte)=ImportParser.parse(ImportBundle.read("same.pdf",byteArrayOf(value).inputStream()),ImportFormat.MOJI_ORIGINAL,manualDate=LocalDate.of(2024,1,2),now=now)
        assertNotEquals(moji(1).records.single().id,moji(2).records.single().id)
        fun vault(value:Byte)=ImportParser.parse(ImportBundle.read("v.zip",zip("n.md" to "正文 ![[a.png]]".toByteArray(),"a.png" to byteArrayOf(value)).inputStream()),ImportFormat.OBSIDIAN,now=now)
        assertNotEquals(vault(1).records.single().id,vault(2).records.single().id)
        val a=vault(1).records.single();val b=vault(2).records.single().copy(id=a.id)
        assertEquals(1,ImportPlan(listOf(b),emptyMap(),emptyList(),"test").conflictCount(listOf(a)))
    }
    @Test fun headingAndTimestampScannerRespectLongFences(){
        val raw="````\n# Journal\n```\n- 1:00 假记录\n````\n# Journal\n- 2:00 真记录\n````\n# Other\n```\n- 3:00 也是代码\n````"
        val plan=ImportParser.parse(text("2024-01-02.md",raw),ImportFormat.THINO_DAILY,now=now)
        assertEquals(1,plan.records.count{it.importInfo!!.originalDate!!.contains('T')})
        assertTrue(plan.records.any{it.text.contains("也是代码")})
    }
    @Test fun malformedNestedBackupFieldsAreRejected(){
        val row=org.json.JSONArray(TaskCodec.encode(listOf(Task("a","正文",createdAt=now)))).getJSONObject(0)
        row.put("attachments",org.json.JSONArray().put(123))
        val files=mapOf("manifest.json" to "{\"format\":\"shiye-backup\",\"version\":1}".toByteArray(),"records.json" to org.json.JSONArray().put(row).toString().toByteArray())
        assertThrows(IllegalArgumentException::class.java){BackupPackage.parse(ImportBundle(files,"b.zip",byteArrayOf()))}
    }
    @Test fun exportRejectsOversizedAssetBeforeWritingOutput(){
        val file=File(tmp.root,"imports/"+"a".repeat(64));file.parentFile!!.mkdirs()
        java.io.RandomAccessFile(file,"rw").use{it.setLength(ImportBundle.MAX_FILE.toLong()+1)}
        val record=Task("a","正文",createdAt=now,attachments=listOf(ImportedAttachment("a".repeat(64),"a", "imports/"+"a".repeat(64),"application/octet-stream")))
        val out=ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java){BackupPackage.export(listOf(record),tmp.root,out)}
        assertEquals(0,out.size())
    }

    @Test fun titlelessDiariumCanExportAndRestoreOwnBackup(){
        val plan=ImportParser.parse(text("entries.json","{\"date\":\"2024-01-02T12:00:00\",\"heading\":\"\",\"html\":\"<p>无标题日记</p>\",\"tags\":[\"\",\"生活\"]}"),ImportFormat.DIARIUM,now=now)
        ImportStorage.install(tmp.root,plan.assets,plan.assets.keys)
        val out=ByteArrayOutputStream();BackupPackage.export(plan.records,tmp.root,out)
        val restored=BackupPackage.parse(ImportBundle.read("backup.zip",out.toByteArray().inputStream()))
        assertEquals(plan.records,restored.records)
    }

    @Test fun stableIdTimeAndDiariumMetadataChangesAreConflicts(){
        fun thino(time:String)=ImportParser.parse(text("2024-01-02.md","# Journal\n- $time 原文 ^stable"),ImportFormat.THINO_DAILY,now=now)
        assertEquals(1,thino("9:00").conflictCount(thino("8:00").records))
        fun diary(person:String)=ImportParser.parse(text("entries.json","{\"id\":\"stable\",\"date\":\"2024-01-02T12:00:00\",\"html\":\"正文\",\"people\":[\"$person\"]}"),ImportFormat.DIARIUM,now=now)
        assertEquals(1,diary("合成人物乙").conflictCount(diary("合成人物甲").records))
    }

    @Test fun vaultHiddenCredentialsAndUnreferencedFilesNeverEnterBackup(){
        val archive=zip("n.md" to "正文 ![[image.png]] ![[.obsidian/token.json]]".toByteArray(),"image.png" to byteArrayOf(1,2),".obsidian/token.json" to "synthetic-secret".toByteArray(),"unreferenced.txt" to "not accepted".toByteArray())
        val plan=ImportParser.parse(ImportBundle.read("vault.zip",archive.inputStream()),ImportFormat.OBSIDIAN,now=now)
        assertTrue(plan.warnings.any{it.contains("未导入")})
        assertFalse(plan.records.flatMap{it.attachments}.any{it.name=="vault.zip" || it.name=="token.json" || it.name=="unreferenced.txt"})
        assertFalse(plan.assets.values.any{it.contentEquals("synthetic-secret".toByteArray()) || it.contentEquals(archive)})
        ImportStorage.install(tmp.root,plan.assets,plan.assets.keys)
        val out=ByteArrayOutputStream();BackupPackage.export(plan.records,tmp.root,out)
        val backup=ImportBundle.read("backup.zip",out.toByteArray().inputStream())
        assertFalse(backup.files.values.any{it.contentEquals("synthetic-secret".toByteArray()) || it.contentEquals(archive)})
    }
}
