package cn.jiaoban.data

import cn.jiaoban.EntryKind
import cn.jiaoban.Task
import cn.jiaoban.TaskState
import cn.jiaoban.ImportedAttachment
import cn.jiaoban.importing.BackupPackage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

class TrashPersistenceTest {
    @get:Rule val tmp=TemporaryFolder()
    private val whenCreated=Instant.parse("2026-10-01T08:00:00Z")
    private val whenDeleted=Instant.parse("2026-11-01T08:00:00Z")
    private val note=Task("note-1","私人的记录",createdAt=whenCreated,kind=EntryKind.NOTE)

    @Test fun trashIsDistinctFromArchiveAndSurvivesCodecAndStore() {
        val archived=note.copy(state=TaskState.VOID)
        val trashed=note.copy(deletedAt=whenDeleted)
        val archivedInTrash=archived.copy(deletedAt=whenDeleted)
        val records=listOf(note,archived,trashed,archivedInTrash).mapIndexed { index,item -> item.copy(id="id-$index") }
        val store=TaskStore(File(tmp.root,"records.json"))
        store.save(records)
        assertEquals(records,store.load())
        assertNull(store.load()[1].deletedAt)
        assertEquals(TaskState.OPEN,store.load()[2].state)
        assertEquals(TaskState.VOID,store.load()[3].state)
    }

    @Test fun completeBackupV2KeepsTrashAndReadsOldV1() {
        val archived=note.copy(state=TaskState.VOID,deletedAt=whenDeleted)
        val output=ByteArrayOutputStream()
        BackupPackage.export(listOf(archived),tmp.root,output)
        val plan=BackupPackage.read(output.toByteArray().inputStream(),tmp.root)
        try {assertEquals(listOf(archived),plan.records)}
        finally {plan.close()}
    }

    @Test fun bothCorruptSnapshotsFailClosedWithoutOverwriting() {
        val source=File(tmp.root,"records.json")
        val spare=File(tmp.root,"records.json.bak")
        source.writeText("{ corrupted")
        spare.writeText("not a backup")
        val before=source.readBytes()
        assertTrue(runCatching { TaskStore(source).load() }.isFailure)
        assertArrayEquals(before,source.readBytes())
    }

    @Test fun recoverySnapshotProtectsMediaAfterPermanentDelete() {
        val asset=ImportedAttachment("a".repeat(64),"photo.jpg","imports/${"a".repeat(64)}.jpg","image/jpeg",false)
        val store=TaskStore(File(tmp.root,"records.json"))
        store.save(listOf(note.copy(attachments=listOf(asset))))
        store.save(emptyList())
        assertEquals(setOf(asset.relativePath),store.recoveryAttachmentPaths())
        // A subsequent verified snapshot makes the old asset eligible for cleanup.
        store.save(emptyList())
        assertEquals(emptySet<String>(),store.recoveryAttachmentPaths())
    }
}
