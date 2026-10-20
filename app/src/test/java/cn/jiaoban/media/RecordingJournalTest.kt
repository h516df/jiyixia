package cn.jiaoban.media

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingJournalTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun sessionDirectoryCannotEscapeRecordingsRoot() {
        val journal = RecordingJournal(temp.root, "../../private/记录")
        assertEquals(File(temp.root, "recordings").canonicalFile, journal.directory.parentFile.canonicalFile)
        assertTrue(journal.directory.name.matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun finalizedSegmentsSurviveNewJournalAndActiveFilesAreNotRecovered() {
        val first = RecordingJournal(temp.root, "session")
        val complete = first.newSegment().apply { writeText("synthetic finalized audio") }
        val interrupted = first.newSegment().apply { writeText("synthetic incomplete audio") }
        val finalized = first.finalizeSegment(complete)
        val recovered = RecordingJournal(temp.root, "session").recoverFinalized()
        assertEquals(listOf(finalized), recovered)
        assertTrue(interrupted.exists())
        assertFalse(complete.exists())
        assertEquals("synthetic finalized audio", recovered.single().readText())
    }

    @Test fun differentSessionsNeverMix() {
        val first = RecordingJournal(temp.root, "one")
        first.finalizeSegment(first.newSegment().apply { writeText("synthetic") })
        assertTrue(RecordingJournal(temp.root, "two").recoverFinalized().isEmpty())
    }

    @Test fun emptySegmentCannotBeCommitted() {
        val journal = RecordingJournal(temp.root, "session")
        val empty = journal.newSegment().apply { createNewFile() }
        try { journal.finalizeSegment(empty); fail("Empty segment accepted") } catch (_: IOException) { }
        assertTrue(empty.exists())
        assertTrue(journal.recoverFinalized().isEmpty())
    }

    @Test fun foreignFileCannotBeCommitted() {
        val journal = RecordingJournal(temp.root, "session")
        val foreign = temp.newFile("foreign.part").apply { writeText("synthetic") }
        try { journal.finalizeSegment(foreign); fail("Foreign file accepted") } catch (_: IllegalArgumentException) { }
        assertTrue(foreign.exists())
    }

    @Test fun successfulDeliveryDeduplicatesByContentNotName() {
        val one = temp.newFile("one.m4a").apply { writeText("synthetic") }
        val two = temp.newFile("two.m4a").apply { writeText("synthetic") }
        var calls = 0
        val delivery = RecordingDelivery(RecordingJournal(temp.root, "dedupe")) { calls++ }
        delivery.deliver(one)
        delivery.deliver(two)
        assertEquals(1, calls)
        assertTrue(one.exists())
        assertTrue(two.exists())
    }

    @Test fun failedCallbackCanRetryAndOriginalRemains() {
        val file = temp.newFile("retry.m4a").apply { writeText("synthetic") }
        var calls = 0
        val delivery = RecordingDelivery(RecordingJournal(temp.root, "retry")) { if (++calls == 1) throw IOException("Synthetic storage failure") }
        try { delivery.deliver(file); fail("Callback should fail") } catch (_: IOException) { }
        assertTrue(file.exists())
        delivery.deliver(file)
        delivery.deliver(file)
        assertEquals(2, calls)
        assertEquals("synthetic", file.readText())
    }

    @Test fun acknowledgedOriginalDoesNotReturnAfterReferenceRemovalAndRecreation() {
        val journal = RecordingJournal(temp.root, "removed-reference")
        val original = journal.finalizeSegment(journal.newSegment().apply { writeText("synthetic") })
        val draftReferences = mutableListOf<File>()
        RecordingDelivery(journal) { draftReferences.add(it) }.deliver(original)
        assertEquals(listOf(original), draftReferences)
        draftReferences.clear() // Simulates a durable user removal from the draft.
        val recreated = RecordingJournal(temp.root, "removed-reference")
        val delivery = RecordingDelivery(recreated) { draftReferences.add(it) }
        recreated.recoverFinalized().forEach(delivery::deliver)
        assertTrue(draftReferences.isEmpty())
        assertTrue(original.exists())
        assertEquals(listOf(original), recreated.recoverFinalized())
    }

    @Test fun failedCallbackHasNoDurableReceiptAndRecreationRetries() {
        val journal = RecordingJournal(temp.root, "pending")
        val original = journal.finalizeSegment(journal.newSegment().apply { writeText("synthetic") })
        try {
            RecordingDelivery(journal) { throw IOException("Synthetic failure") }.deliver(original)
            fail("Callback should fail")
        } catch (_: IOException) { }
        assertFalse(journal.wasDelivered(RecordingJournal.contentHash(original)))
        var calls = 0
        RecordingDelivery(RecordingJournal(temp.root, "pending")) { calls++ }.deliver(original)
        assertEquals(1, calls)
        assertTrue(original.exists())
    }


    @Test fun stoppedSegmentRenameFailureRetriesAfterProcessRecreation() {
        val failing = RecordingJournal(temp.root, "rename-failure", renameSegment = { _, _ -> false })
        val active = failing.newSegment().apply { writeText("synthetic finalized bytes") }
        try { failing.finalizeSegment(active); fail("Expected rename failure") } catch (_: IOException) { }
        assertEquals("synthetic finalized bytes", active.readText())
        assertFalse(failing.retryPendingFinalization())
        val restarted = RecordingJournal(temp.root, "rename-failure")
        assertTrue(restarted.retryPendingFinalization())
        assertEquals("synthetic finalized bytes", restarted.recoverFinalized().single().readText())
        assertTrue(restarted.retryPendingFinalization())
    }

    @Test fun stoppedSegmentSyncFailureRetriesWithoutPromotingUnstoppedPart() {
        val failing = RecordingJournal(temp.root, "sync-failure", syncSegment = { throw IOException("Synthetic fsync failure") })
        val stopped = failing.newSegment().apply { writeText("synthetic stopped bytes") }
        val crashPart = failing.newSegment().apply { writeText("synthetic unfinished bytes") }
        try { failing.finalizeSegment(stopped); fail("Expected sync failure") } catch (_: IOException) { }
        assertFalse(failing.retryPendingFinalization())
        val restarted = RecordingJournal(temp.root, "sync-failure")
        assertTrue(restarted.retryPendingFinalization())
        assertEquals("synthetic stopped bytes", restarted.recoverFinalized().single().readText())
        assertTrue(crashPart.exists())
        assertEquals("synthetic unfinished bytes", crashPart.readText())
    }

    @Test fun failedStopMarkerKeepsInMemoryEvidenceForRetry() {
        val journal = RecordingJournal(temp.root, "marker-failure")
        val stopped = journal.newSegment().apply { writeText("synthetic stopped bytes") }
        val obstruction = File(journal.directory, "${stopped.name}.stopped").apply { mkdir() }
        try { journal.finalizeSegment(stopped); fail("Expected marker failure") } catch (_: IOException) { }
        assertFalse(journal.retryPendingFinalization())
        assertTrue(stopped.exists())
        assertTrue(obstruction.delete()) // Remove only our synthetic empty test obstruction.
        assertTrue(journal.retryPendingFinalization())
        assertEquals("synthetic stopped bytes", journal.recoverFinalized().single().readText())
    }

}
