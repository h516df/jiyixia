package cn.jiaoban.media

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Host lifecycle checks only. Real microphone, calls, routing and OEM interruptions require device QA. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class VoiceCaptureTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test fun constructionNeverStartsMicrophoneAndRepeatedStopIsSafe() {
        val capture = VoiceCapture(app, "synthetic-lifecycle") { fail("No completed audio expected") }
        assertEquals(VoiceCapturePhase.IDLE, capture.state.value.phase)
        capture.stop()
        capture.stop()
        assertEquals(VoiceCapturePhase.STOPPED, capture.state.value.phase)
        capture.close()
        capture.close()
        capture.start()
        assertEquals(VoiceCapturePhase.STOPPED, capture.state.value.phase)
    }

    @Test fun deniedPermissionProducesExplicitErrorWithoutStarting() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val capture = VoiceCapture(app, "synthetic-denied") { fail("No completed audio expected") }
        capture.start()
        assertEquals(VoiceCapturePhase.ERROR, capture.state.value.phase)
        assertTrue(capture.state.value.message.orEmpty().contains("权限"))
        capture.close()
    }

    @Test fun communicationModeDoesNotAutoStartAfterCallEnds() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val manager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        val capture = VoiceCapture(app, "synthetic-call") { fail("No completed audio expected") }
        capture.start()
        assertEquals(VoiceCapturePhase.INTERRUPTED, capture.state.value.phase)
        manager.mode = AudioManager.MODE_NORMAL
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(VoiceCapturePhase.INTERRUPTED, capture.state.value.phase)
        capture.close()
    }

    @Test fun failedAttachmentRecoveryRetainsOriginalAndRetriesExplicitly() {
        val session = "synthetic-recovery-${java.util.UUID.randomUUID()}"
        val journal = RecordingJournal(app.filesDir, session)
        val original = journal.finalizeSegment(journal.newSegment().apply { writeText("synthetic-not-real-audio") })
        var calls = 0
        val capture = VoiceCapture(app, session) {
            if (++calls == 1) throw java.io.IOException("Synthetic reference storage failure")
        }
        capture.recover()
        assertTrue(original.isFile)
        assertTrue(capture.state.value.message.orEmpty().contains("附件保存失败"))
        assertTrue(capture.state.value.attachmentSaveFailed)
        capture.recover()
        assertEquals(2, calls)
        assertNull(capture.state.value.message)
        assertFalse(capture.state.value.attachmentSaveFailed)
        capture.recover()
        assertEquals(2, calls)
        assertTrue(original.isFile)
        assertEquals(1, capture.state.value.completedSegments)
        capture.close()
    }


    @Test fun pendingSuccessfulStopKeepsSaveBlockedAcrossRepeatedStopsUntilRecovery() {
        val session = "synthetic-pending-${java.util.UUID.randomUUID()}"
        val journal = RecordingJournal(app.filesDir, session)
        val stopped = journal.newSegment().apply { writeText("synthetic stopped audio") }
        val obstruction = java.io.File(journal.directory, "${stopped.nameWithoutExtension}.m4a").apply { mkdir() }
        try { journal.finalizeSegment(stopped); fail("Expected synthetic rename failure") } catch (_: java.io.IOException) { }
        var delivered = 0
        val capture = VoiceCapture(app, session) { delivered++ }
        capture.recover()
        assertTrue(capture.state.value.attachmentSaveFailed)
        capture.stop()
        capture.stop()
        assertTrue(capture.state.value.attachmentSaveFailed)
        assertEquals(VoiceCapturePhase.ERROR, capture.state.value.phase)
        assertTrue(stopped.isFile)
        assertEquals(0, delivered)
        assertTrue(obstruction.delete())
        capture.recover()
        assertFalse(capture.state.value.attachmentSaveFailed)
        assertEquals(1, delivered)
        assertEquals("synthetic stopped audio", journal.recoverFinalized().single().readText())
        capture.close()
    }

}
