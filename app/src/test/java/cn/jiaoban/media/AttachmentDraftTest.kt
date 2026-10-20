package cn.jiaoban.media

import android.content.Context
import android.content.SharedPreferences
import cn.jiaoban.ImportedAttachment
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AttachmentDraftTest {
    private fun preferences()=RuntimeEnvironment.getApplication().getSharedPreferences("attachment-${UUID.randomUUID()}",Context.MODE_PRIVATE)
    private fun media(char:Char)=ImportedAttachment(char.toString().repeat(64),"$char.m4a","imports/${char.toString().repeat(64)}.m4a","audio/mp4")
    @Test fun sessionIdentityAndSeedBecomeVisibleTogether() {
        val prefs=preferences();AttachmentDraft.open(prefs,"A",null,null,"A",null,listOf(media('a')))
        var mismatched=false
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,_->
            if(prefs.getString("session",null)=="B" && AttachmentDraft.decode(prefs.getString("attachments",null))!=listOf(media('b')))mismatched=true
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        AttachmentDraft.open(prefs,"B",null,null,"B",null,listOf(media('b')))
        assertFalse(mismatched);assertEquals(listOf(media('b')),AttachmentDraft.decode(prefs.getString("attachments",null)))
    }
    @Test fun appendAndSessionReplacementAreSerializedAndStaleVoiceCannotWrite() {
        val prefs=preferences();AttachmentDraft.open(prefs,"A",null,null,"A",null,listOf(media('a')))
        val pool=Executors.newFixedThreadPool(2);val entered=CountDownLatch(1);val release=CountDownLatch(1);val switching=CountDownLatch(1)
        try {
            val copy=pool.submit { synchronized(AttachmentDraft) {entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));AttachmentDraft.append(prefs,"A",listOf(media('c')))} }
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            val next=pool.submit {switching.countDown();AttachmentDraft.open(prefs,"B",null,null,"B",null,listOf(media('b')))}
            assertTrue(switching.await(5,TimeUnit.SECONDS));assertFalse(next.isDone)
            release.countDown();copy.get(5,TimeUnit.SECONDS);next.get(5,TimeUnit.SECONDS)
            assertEquals("B",prefs.getString("session",null));assertEquals(listOf(media('b')),AttachmentDraft.decode(prefs.getString("attachments",null)))
            assertTrue(runCatching {AttachmentDraft.append(prefs,"A",listOf(media('c')))}.isFailure)
            assertEquals(listOf(media('b')),AttachmentDraft.decode(prefs.getString("attachments",null)))
        } finally {release.countDown();pool.shutdownNow()}
    }
    @Test fun completedDraftRejectsLateAttachmentAndTextUpdatePreservesMedia() {
        val prefs=preferences();AttachmentDraft.open(prefs,"A",null,null,"A",null,emptyList())
        AttachmentDraft.append(prefs,"A",listOf(media('a')))
        AttachmentDraft.open(prefs,"A",null,null,"changed",null,emptyList())
        assertEquals(listOf(media('a')),AttachmentDraft.decode(prefs.getString("attachments",null)))
        synchronized(AttachmentDraft){prefs.edit().putString("completed","A").commit()}
        assertTrue(runCatching{AttachmentDraft.append(prefs,"A",listOf(media('b')))}.isFailure)
    }
    @Test fun failedDurableCommitThrowsEvenIfSharedPreferencesMemoryChanged() {
        val prefs=preferences()
        val failing=object:SharedPreferences by prefs {
            override fun edit():SharedPreferences.Editor {
                val delegate=prefs.edit()
                lateinit var proxy:SharedPreferences.Editor
                proxy=java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,arrayOf(SharedPreferences.Editor::class.java)) { _,method,args->
                    if(method.name=="commit") {delegate.apply();false}
                    else {val result=method.invoke(delegate,*(args ?: emptyArray()));if(result is SharedPreferences.Editor)proxy else result}
                } as SharedPreferences.Editor
                return proxy
            }
        }
        assertTrue(runCatching{AttachmentDraft.open(failing,"A",null,null,"text",null,listOf(media('a')))}.isFailure)
        AttachmentDraft.open(prefs,"A",null,null,"text",null,listOf(media('a')))
        assertEquals(listOf(media('a')),AttachmentDraft.decode(prefs.getString("attachments",null)))
    }
    @Test fun unchangedRecompositionsDoNotCommitButFailedCommitMustRetry() {
        val prefs=preferences();var commits=0;var fail=false
        val observed=object:SharedPreferences by prefs {
            override fun edit():SharedPreferences.Editor {
                val delegate=prefs.edit();lateinit var proxy:SharedPreferences.Editor
                proxy=java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,arrayOf(SharedPreferences.Editor::class.java)) { _,method,args->
                    if(method.name=="commit") {commits++;if(fail){delegate.apply();false}else delegate.commit()}
                    else {val result=method.invoke(delegate,*(args ?: emptyArray()));if(result is SharedPreferences.Editor)proxy else result}
                } as SharedPreferences.Editor
                return proxy
            }
        }
        AttachmentDraft.open(observed,"A",null,null,"text",null,emptyList())
        repeat(20){AttachmentDraft.open(observed,"A",null,null,"text",null,emptyList())}
        assertEquals(1,commits)
        fail=true;assertTrue(runCatching{AttachmentDraft.open(observed,"A",null,null,"changed",null,emptyList())}.isFailure)
        fail=false;AttachmentDraft.open(observed,"A",null,null,"changed",null,emptyList())
        assertEquals(3,commits)
        AttachmentDraft.open(observed,"A",null,null,"changed",null,emptyList());assertEquals(3,commits)
    }
    @Test fun newBrandKeepsExistingApplicationIdentity() {
        val context=RuntimeEnvironment.getApplication()
        assertEquals("记一下",context.getString(cn.jiaoban.R.string.app_name))
        assertEquals("cn.jiaoban",context.packageName)
    }
}
