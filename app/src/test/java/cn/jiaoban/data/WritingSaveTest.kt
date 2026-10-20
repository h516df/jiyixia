package cn.jiaoban.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class WritingSaveTest {
    private fun await(predicate:()->Boolean) {
        val deadline=System.nanoTime()+5_000_000_000L
        while(!predicate() && System.nanoTime()<deadline){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
        assertTrue(predicate())
    }
    @Test fun diskFailureReportsFailureAndStableRetryDoesNotDuplicate(){
        val file=File(RuntimeEnvironment.getApplication().cacheDir,"writing-${UUID.randomUUID()}.json")
        val store=TaskStore(file);store.save(emptyList())
        val blocker=File(file.path+".tmp");assertTrue(blocker.mkdir())
        val holder=TasksHolder(store);holder.start();await{holder.loaded}
        var result:Boolean?=null
        holder.saveWriting("无法落盘时仍保留的正文",id="stable"){result=it}
        await{result!=null};assertEquals(false,result);assertNotNull(holder.saveError);assertTrue(store.load().isEmpty());assertTrue(holder.savedTasks.isEmpty())
        assertTrue(blocker.delete());result=null
        holder.saveWriting("无法落盘时仍保留的正文",id="stable"){result=it}
        await{result!=null};assertEquals(true,result);assertNull(holder.saveError)
        assertEquals(1,store.load().size);assertEquals("stable",store.load().single().id)
    }
    @Test fun recreatedOwnerUsesOneSnapshotAndWriter(){
        val file=File(RuntimeEnvironment.getApplication().cacheDir,"owner-${UUID.randomUUID()}.json")
        val first=TasksHolder.forStore(TaskStore(file));await{first.loaded}
        var completions=0
        first.saveWriting("重建前第一条",id="one"){if(it)completions++}
        val recreated=TasksHolder.forStore(TaskStore(file))
        assertSame(first,recreated)
        recreated.saveWriting("重建后第二条",id="two"){if(it)completions++}
        await{completions==2}
        assertEquals(listOf("重建前第一条","重建后第二条"),TaskStore(file).load().map{it.text})
        assertEquals(TaskStore(file).load(),recreated.savedTasks)
    }
    @Test fun sameIdAndBodyRetryQueuedBeforeFailureStillUpserts(){
        val file=File(RuntimeEnvironment.getApplication().cacheDir,"retry-${UUID.randomUUID()}.json")
        val store=TaskStore(file);store.save(emptyList())
        val blocker=File(file.path+".tmp");assertTrue(blocker.mkdir())
        val holder=TasksHolder.forStore(store);await{holder.loaded}
        val ioField=TasksHolder::class.java.getDeclaredField("io").apply{isAccessible=true}
        val io=ioField.get(holder) as java.util.concurrent.ExecutorService
        val between=java.util.concurrent.CountDownLatch(1)
        var first:Boolean?=null;var second:Boolean?=null
        holder.saveWriting("重建后重复提交的正文",id="stable"){first=it}
        io.execute{between.await(10,java.util.concurrent.TimeUnit.SECONDS)}
        holder.saveWriting("重建后重复提交的正文",id="stable"){second=it}
        try {await{first!=null};assertEquals(false,first);assertTrue(blocker.delete())}
        finally{between.countDown()}
        await{second!=null};assertEquals(true,second)
        assertEquals(1,store.load().size);assertEquals("重建后重复提交的正文",store.load().single().text)
    }
    @Test fun sequentialWritesKeepBothBodies(){
        val store=TaskStore(File(RuntimeEnvironment.getApplication().cacheDir,"writing-${UUID.randomUUID()}.json"))
        val holder=TasksHolder(store);var completions=0
        holder.saveWriting("第一条",id="one"){if(it)completions++}
        holder.saveWriting("第二条",id="two"){if(it)completions++}
        await{completions==2};assertEquals(listOf("第一条","第二条"),store.load().map{it.text})
    }
    @Test fun mediaOnlySaveRetryAndEditingRetainOriginalAttachments(){
        val store=TaskStore(File(RuntimeEnvironment.getApplication().cacheDir,"media-${UUID.randomUUID()}.json"))
        val holder=TasksHolder(store);holder.start();await{holder.loaded}
        val a=cn.jiaoban.ImportedAttachment("a".repeat(64),"voice.m4a","imports/${"a".repeat(64)}.m4a","audio/mp4")
        var done=false
        holder.saveWriting("",id="media",attachments=listOf(a)){done=it};await{done}
        assertEquals(listOf(a),store.load().single().attachments)
        done=false;holder.saveWriting("加一句话",id="media"){done=it};await{done}
        assertEquals(listOf(a),store.load().single().attachments)
        done=false;holder.saveWriting("",id="media",attachments=listOf(a)){done=it};await{done}
        assertEquals(1,store.load().size);assertEquals("",store.load().single().text)
        assertNull(holder.saveWriting("",id="empty",attachments=emptyList()){})
    }
}

