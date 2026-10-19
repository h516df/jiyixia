package cn.jiaoban.update

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ReadyUpdateStoreTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    private val bytes="synthetic apk bytes, not executable".toByteArray()
    private val sha get()=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun record()=ReadyUpdate("Jiaoban-v0.99-universal.apk",bytes.size.toLong(),sha,sha,99)
    @Before fun clean(){ReadyUpdateStore(context).clear()}
    @Test fun completedMetadataSurvivesNewStoreAndBytesAreReverified(){
        val first=ReadyUpdateStore(context);val record=record()
        first.beginDownload();assertTrue(first.wasInterrupted())
        first.file(record).apply{parentFile!!.mkdirs();writeBytes(bytes)};first.save(record)
        val recreated=ReadyUpdateStore(context)
        assertEquals(record,recreated.load());assertFalse(recreated.wasInterrupted());assertArrayEquals(bytes,recreated.verifyBytes(record).readBytes())
    }
    @Test fun changedCacheCannotPassInstallReverification(){
        val store=ReadyUpdateStore(context);val record=record();val file=store.file(record)
        file.parentFile!!.mkdirs();file.writeBytes(bytes);store.save(record)
        file.writeBytes(ByteArray(bytes.size){7})
        assertThrows(UpdateFailure::class.java){ReadyUpdateStore(context).verifyBytes(record)}
    }
    @Test fun missingCacheCannotBeTreatedAsReady(){
        val store=ReadyUpdateStore(context);val record=record();store.file(record).delete();store.save(record)
        assertThrows(UpdateFailure::class.java){store.verifyBytes(record)}
    }
    @Test fun invalidPersistentPathsSizesAndHashesAreRejected(){
        for(value in listOf(record().copy(name="../outside.apk"),record().copy(name="Other-v1-universal.apk"),record().copy(size=0),record().copy(size=UpdateConfig.MAX_APK+1),record().copy(sha256="bad"),record().copy(apiDigest="0".repeat(64)),record().copy(code=0))){
            assertThrows(UpdateFailure::class.java){ReadyUpdate.decode(value.encode())}
        }
    }
    @Test fun interruptedDownloadHasNoReadyPackageAfterRecreation(){
        val store=ReadyUpdateStore(context);store.beginDownload()
        val recreated=ReadyUpdateStore(context);assertTrue(recreated.wasInterrupted());assertNull(recreated.load())
    }
    @Test fun launchWithoutCachedUpdateOnlyRestoresLocalState(){
        val updater=Updater(context)
        val end=System.nanoTime()+5_000_000_000
        while(updater.state is UpdateState.Restoring && System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)}
        assertTrue(updater.state is UpdateState.Idle)
        assertFalse(updater.busy)
    }
    @Test fun applicationOwnerIsSharedAcrossUiContexts(){
        assertSame(Updater.forApplication(context),Updater.forApplication(ContextWrapper(context)))
    }

    private fun await(test:()->Boolean){val end=System.nanoTime()+5_000_000_000;while(!test() && System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)};assertTrue(test())}
    private fun seedReady(){val store=ReadyUpdateStore(context);val r=record();store.file(r).apply{parentFile!!.mkdirs();writeBytes(bytes)};store.save(r)}
    @Test fun cachedArchiveRejectionNeverBecomesReady(){
        seedReady()
        val updater=Updater(context,archiveVerifier={_,_,_->throw UpdateFailure("合成签名拒绝")})
        await{updater.state !is UpdateState.Restoring}
        assertTrue(updater.state is UpdateState.Failed);assertNull(ReadyUpdateStore(context).load())
    }
    @Test fun permissionReturnAndDoubleInstallAreVerifiedExactlyOnce(){
        seedReady();var allowed=false;val count=java.util.concurrent.atomic.AtomicInteger()
        val block=java.util.concurrent.atomic.AtomicBoolean(false);val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val updater=Updater(context,archiveVerifier={_,_,_->if(block.get()){entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))}},installAllowed={allowed},installer={_,_->count.incrementAndGet()})
        await{updater.state is UpdateState.ReadyToInstall}
        updater.install();assertTrue(updater.state is UpdateState.NeedInstallPermission);assertEquals(0,count.get())
        allowed=true;block.set(true);updater.install();assertTrue(updater.state is UpdateState.Verifying)
        assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));updater.install();release.countDown()
        await{updater.state is UpdateState.ReadyToInstall};assertEquals(1,count.get())
    }
    @Test fun cancelPreventsLateAuthorizationPersistence(){
        val updater=Updater(context);await{updater.state !is UpdateState.Restoring};updater.cancel()
        val method=Updater::class.java.getDeclaredMethod("persistAuthorization",UpdateAuthorization::class.java).apply{isAccessible=true}
        val exception=assertThrows(java.lang.reflect.InvocationTargetException::class.java){method.invoke(updater,UpdateAuthorization("synthetic-access","synthetic-refresh",9999999999999L,9999999999999L))}
        assertTrue(exception.cause is InterruptedException);assertNull(UpdateTokenStore(context).load())
    }
}
