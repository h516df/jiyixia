package cn.jiaoban.importing

import android.os.Looper
import cn.jiaoban.*
import cn.jiaoban.data.*
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ImportTransactionTest {
    @get:Rule val tmp=TemporaryFolder()
    private val now=Instant.parse("2026-10-19T00:00:00Z")
    private fun await(predicate:()->Boolean){val end=System.nanoTime()+5_000_000_000;while(!predicate() && System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)};assertTrue(predicate())}
    private fun plan()=ImportParser.parse(ImportBundle.read("2024-05-22.md","合成导入正文".byteInputStream()),ImportFormat.OBSIDIAN,now=now)
    @Test fun importIsNonDestructiveAndRepeatAddsNothing(){
        val file=File(tmp.root,"tasks.json");val store=TaskStore(file)
        val legacy=Task("legacy","原待办正文",createdAt=now,kind=EntryKind.TODO,steps=listOf(TaskStep("s","原步骤",true)))
        store.save(listOf(legacy));val holder=TasksHolder.forStore(store);await{holder.loaded}
        val plan=plan();holder.importWriting(plan,tmp.root){};await{!holder.importing}
        assertEquals(legacy,store.load().first());assertEquals(1,holder.lastImportResult!!.added)
        holder.importWriting(plan,tmp.root){};await{!holder.importing}
        assertEquals(0,holder.lastImportResult!!.added);assertEquals(2,store.load().size)
        val a=plan.records.single().attachments.single();assertArrayEquals(plan.assets.getValue(a.relativePath),File(tmp.root,a.relativePath).readBytes())
    }
    @Test fun failedDatabaseWriteLeavesOldDataAndRollsBackOnlyNewAttachments(){
        val file=File(tmp.root,"tasks.json");val store=TaskStore(file);val old=Task("old","不应改变",createdAt=now,kind=EntryKind.NOTE);store.save(listOf(old))
        val before=file.readBytes();val blocker=File(file.path+".tmp");assertTrue(blocker.mkdir())
        val holder=TasksHolder.forStore(store);await{holder.loaded};val plan=plan()
        holder.importWriting(plan,tmp.root){};await{!holder.importing}
        assertNotNull(holder.lastImportResult!!.error);assertArrayEquals(before,file.readBytes());assertEquals(listOf(old),holder.savedTasks)
        assertTrue(plan.records.flatMap{it.attachments}.none{File(tmp.root,it.relativePath).exists()})
    }
    @Test fun existingIdConflictNeverOverwritesCurrentBody(){
        val plan=plan();val record=plan.records.single().copy(text="用户已编辑的正文")
        assertEquals(1,plan.conflictCount(listOf(record)));assertEquals(0,plan.duplicateCount(listOf(record)))
        val store=TaskStore(File(tmp.root,"tasks.json"));store.save(listOf(record));val holder=TasksHolder.forStore(store);await{holder.loaded}
        holder.importWriting(plan,tmp.root){};await{!holder.importing};assertEquals(record,store.load().single())
    }
    @Test fun importAndQuickWriteShareDurableQueueWithoutDroppingEither(){
        val store=TaskStore(File(tmp.root,"tasks.json"));store.save(emptyList());val holder=TasksHolder.forStore(store);await{holder.loaded}
        holder.importWriting(plan(),tmp.root){};var saved=false;holder.saveWriting("并行输入的独立想法",id="quick"){saved=it}
        await{!holder.importing && saved};assertEquals(2,store.load().size);assertTrue(store.load().any{it.id=="quick"})
    }
    @Test fun attachmentPathCannotEscapeImportsDirectory(){assertThrows(IllegalArgumentException::class.java){ImportStorage.install(tmp.root,mapOf("../outside" to byteArrayOf(1)),setOf("../outside"))};assertFalse(File(tmp.root.parentFile,"outside").exists())}

    @Test fun restoringExistingRecordRepairsMissingAssetAndRejectsCorruption(){
        val plan=plan();val record=plan.records.single();val store=TaskStore(File(tmp.root,"tasks.json"));store.save(listOf(record))
        val holder=TasksHolder.forStore(store);await{holder.loaded}
        holder.importWriting(plan,tmp.root){};await{!holder.importing}
        assertNull(holder.lastImportResult!!.error);assertEquals(0,holder.lastImportResult!!.added)
        val asset=File(tmp.root,record.attachments.single().relativePath);assertTrue(asset.isFile)
        asset.writeText("损坏")
        holder.importWriting(plan,tmp.root){};await{!holder.importing}
        assertNotNull(holder.lastImportResult!!.error);assertEquals("损坏",asset.readText());assertEquals(listOf(record),store.load())
    }
    @Test fun backupCommitFailurePreservesOriginalDatabaseBytes(){
        val file=File(tmp.root,"tasks.json");val store=TaskStore(file);store.save(listOf(Task("old","旧正文",createdAt=now)))
        val old=file.readBytes();val block=File(file.path+".bak");assertTrue(block.mkdir());File(block,"keep").writeText("block rename")
        assertThrows(IllegalStateException::class.java){store.save(listOf(Task("new","新正文",createdAt=now)))}
        assertArrayEquals(old,file.readBytes());assertFalse(File(file.path+".tmp").exists())
    }

    @Test fun appendingStableThinoBlockDoesNotRequireOldSourceInNewPackage(){
        fun daily(body:String)=ImportParser.parse(ImportBundle.read("2024-01-02.md",body.byteInputStream()),ImportFormat.THINO_DAILY,now=now)
        val first=daily("# Journal\n- 8:00 第一条 ^a")
        val second=daily("# Journal\n- 8:00 第一条 ^a\n- 9:00 第二条 ^b")
        val store=TaskStore(File(tmp.root,"tasks.json"));store.save(emptyList());val holder=TasksHolder.forStore(store);await{holder.loaded}
        holder.importWriting(first,tmp.root){};await{!holder.importing};assertNull(holder.lastImportResult!!.error)
        holder.importWriting(second,tmp.root){};await{!holder.importing}
        assertNull(holder.lastImportResult!!.error);assertEquals(2,store.load().size);assertEquals(first.records.single(),store.load().first())
        assertEquals(1,holder.lastImportResult!!.added)
    }
}
