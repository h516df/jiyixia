package cn.jiaoban.media

import android.app.Application
import android.content.Context
import cn.jiaoban.ImportedAttachment
import java.lang.IllegalStateException
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceSessionStoreTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before fun resetDraftBefore() {
        assertTrue(app.getSharedPreferences("voice_session_v1", Context.MODE_PRIVATE).edit().clear().commit())
    }

    @After fun resetDraftAfter() {
        assertTrue(app.getSharedPreferences("voice_session_v1", Context.MODE_PRIVATE).edit().clear().commit())
        // Controller is an object shared by later Robolectric suites in this JVM.
        VoiceSessionController.recover(app)
        assertEquals(VoiceCapturePhase.IDLE, VoiceSessionController.state.value.phase)
    }

    @Test fun completedSegmentsRemainOneDurableNoteAcrossStoreRecreation() {
        val id = UUID.randomUUID().toString()
        val one = ImportedAttachment("a".repeat(64), "first.m4a", "imports/${"a".repeat(64)}.m4a", "audio/mp4")
        val two = ImportedAttachment("b".repeat(64), "second.m4a", "imports/${"b".repeat(64)}.m4a", "audio/mp4")
        val store = VoiceSessionStore(app)
        store.save(VoiceSessionState(sessionId = id, phase = VoiceCapturePhase.RECORDING, elapsedMs = 30_000))
        store.append(id, one)
        store.append(id, two)
        store.append(id, one)
        store.save(store.read().copy(phase = VoiceCapturePhase.STOPPED, elapsedMs = 65_000))
        val restored = VoiceSessionStore(app).read()
        assertEquals(id, restored.sessionId)
        assertEquals(VoiceCapturePhase.STOPPED, restored.phase)
        assertEquals(65_000, restored.elapsedMs)
        assertEquals(listOf(one, two), restored.attachments)
        assertEquals(2, restored.completedSegments)
    }

    @Test fun acknowledgementOnlyClearsMatchingFinishedSession() {
        val id = UUID.randomUUID().toString()
        val store = VoiceSessionStore(app)
        val previous = VoiceSessionState(sessionId = id, phase = VoiceCapturePhase.RECORDING)
        store.save(previous)
        VoiceSessionController.acknowledgeSaved(app, id)
        assertEquals(previous, store.read())
        store.save(previous.copy(phase = VoiceCapturePhase.STOPPED))
        VoiceSessionController.acknowledgeSaved(app, "unrelated-session")
        assertEquals(id, store.read().sessionId)
        VoiceSessionController.acknowledgeSaved(app, id)
        assertEquals(VoiceSessionState(), store.read())
    }

    @Test fun processRecoveryDoesNotResumeAnInterruptedMicrophone() {
        val id = UUID.randomUUID().toString()
        val store = VoiceSessionStore(app)
        store.save(VoiceSessionState(sessionId = id, phase = VoiceCapturePhase.RECORDING, elapsedMs = 9_000))
        VoiceSessionController.recover(app)
        assertEquals(VoiceCapturePhase.INTERRUPTED, VoiceSessionController.state.value.phase)
        assertEquals(id, VoiceSessionController.state.value.sessionId)
        assertTrue(VoiceSessionController.state.value.message.orEmpty().contains("中断"))
        assertEquals(VoiceCapturePhase.INTERRUPTED, VoiceSessionStore(app).read().phase)
    }

    @Test fun corruptAttachmentIndexCannotBeSilentlyOverwrittenOrAcknowledged() {
        val id = UUID.randomUUID().toString()
        val raw = "not-valid-json"
        val prefs = app.getSharedPreferences("voice_session_v1", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("session", id).putString("phase", "STOPPED")
            .putString("attachments", raw).commit())
        val store = VoiceSessionStore(app)
        val broken = store.read()
        assertTrue(broken.attachmentSaveFailed)
        VoiceSessionController.acknowledgeSaved(app, id)
        assertEquals(raw, prefs.getString("attachments", null))
        try {
            store.save(broken.copy(phase = VoiceCapturePhase.INTERRUPTED))
            fail("corrupt draft must not be rewritten")
        } catch (_: IllegalStateException) {
            assertEquals(raw, prefs.getString("attachments", null))
        }
    }
}
