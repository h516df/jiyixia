package cn.jiaoban.data

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.jiaoban.*
import cn.jiaoban.parse.DueParser
import cn.jiaoban.parse.PersonParser
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 内存里的一份列表 + 落盘。改动只从主线程发起，落盘排在单线程队列里按顺序做，
 * 所以界面立刻更新，磁盘写入不会互相插队。
 */
class TasksHolder(private val store: TaskStore) {
    companion object {
        // One process-scoped owner per database. Activity/theme recreation must not
        // spawn another stale snapshot writer or race on the same temporary file.
        // Contains only File-backed stores, never an Activity or Context.
        val processId:String=UUID.randomUUID().toString()
        private val owners=mutableMapOf<String,TasksHolder>()
        @Synchronized fun forStore(store:TaskStore):TasksHolder =
            owners.getOrPut(store.identity){TasksHolder(store).also{it.start()}}
    }


    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jiaoban-io").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    var tasks by mutableStateOf<List<Task>>(emptyList())
        private set

    /** Last successful disk snapshot. UI never presents a failed write as saved. */
    var savedTasks by mutableStateOf<List<Task>>(emptyList())
        private set

    var loaded by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    /** 读盘还没回来时用户就动过数据了，这时不能再拿读到的旧快照覆盖内存。 */
    private var dirty = false
    private var revision = 0L
    var saveError by mutableStateOf<String?>(null)
        private set

    fun start() {
        io.execute {
            val result = runCatching { store.load() }
            main.post {
                if (result.isSuccess) {
                    if (!dirty) {tasks = result.getOrThrow();savedTasks = result.getOrThrow()}
                    loadError = null; loaded = true
                } else {
                    loadError = "资料无法读取，已停止写入以保护原文件。请勿卸载或清除数据。"
                    loaded = false
                }
            }
        }
    }
    fun retryLoad() { if (!loaded) start() }

    /**
     * 一句话进来，人和期限都自己认：认出谁交代的、认出什么时候要，认不出就默认 3 天。
     * 输入框和「分享进来」走的是同一条路。
     */
    fun addFromText(
        text: String,
        today: LocalDate,
        fromOverride: String? = null,
        dueOverride: LocalDate? = null,
        overrideDue: Boolean = false,
        expiryPolicy: ExpiryPolicy = ExpiryPolicy.MUST_FINISH,
        kind: EntryKind = EntryKind.DELEGATED,
        journalDate: LocalDate? = null,
    ) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val from = if(kind==EntryKind.DELEGATED) fromOverride ?: PersonParser.parse(clean, recentFroms()) else ""
        val due = if(kind==EntryKind.NOTE) null else if (overrideDue) dueOverride else if(kind==EntryKind.TODO) DueParser.parse(clean,today) else DueParser.withDefault(clean, today)
        add(clean, from, due, expiryPolicy,kind,journalDate)
    }

    fun add(text: String, from: String, due: LocalDate?, expiryPolicy: ExpiryPolicy = ExpiryPolicy.MUST_FINISH,kind:EntryKind = EntryKind.DELEGATED, journalDate: LocalDate? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        commit(
            tasks + Task(
                id = UUID.randomUUID().toString(),
                text = trimmed,
                from = from.trim(),
                due = due,
                state = TaskState.OPEN,
                createdAt = Instant.now(),
                expiryPolicy = expiryPolicy,
                kind = kind,
                journalDate = if(kind == EntryKind.NOTE) journalDate else null,
            )
        )
    }

    fun update(task: Task) = commit(tasks.map { if (it.id == task.id) task else it })

    /** Stable ID permits retrying a failed quick record without appending duplicates. */
    fun saveWriting(text:String, journalDate:LocalDate?=null, id:String?=null, attachments:List<cn.jiaoban.ImportedAttachment>?=null, onSaved:(Boolean)->Unit):String? {
        val clean=text.trim()
        val previous=tasks.firstOrNull{it.id==id}
        if(clean.isEmpty() && (attachments ?: previous?.attachments.orEmpty()).isEmpty()) return null
        if(previous!=null && previous.kind!=EntryKind.NOTE) return null
        if(previous?.deletedAt != null) return null
        val entry=previous?.copy(text=clean,journalDate=journalDate,attachments=attachments ?: previous.attachments) ?: Task(
            id=id ?: UUID.randomUUID().toString(),text=clean,createdAt=Instant.now(),kind=EntryKind.NOTE,
            expiryPolicy=ExpiryPolicy.MUST_FINISH,journalDate=journalDate,attachments=attachments.orEmpty())
        commit(if(previous==null)tasks+entry else tasks.map{if(it.id==entry.id)entry else it},onSaved,forceIds=setOf(entry.id))
        return entry.id
    }

    private fun change(id: String, transform: (Task) -> Task): Task? {
        val before = tasks.firstOrNull { it.id == id } ?: return null
        val after = transform(before)
        if(after != before) commit(tasks.map { if(it.id == id) after else it })
        return after
    }
    fun setState(id: String, state: TaskState) = change(id) { it.withState(state) }
    fun moveToTrash(ids:Set<String>, onSaved:(Boolean)->Unit) {
        if(ids.isEmpty()) return
        val whenDeleted=Instant.now()
        commit(tasks.map { if(it.id in ids && it.kind==EntryKind.NOTE && it.deletedAt==null) it.copy(deletedAt=whenDeleted) else it },onSaved)
    }
    fun restoreFromTrash(ids:Set<String>, onSaved:(Boolean)->Unit) {
        if(ids.isEmpty()) return
        commit(tasks.map { if(it.id in ids && it.deletedAt!=null) it.copy(deletedAt=null) else it },onSaved)
    }
    fun setPinned(ids:Set<String>, pinned:Boolean, onSaved:(Boolean)->Unit) {
        if(ids.isEmpty()) return
        commit(tasks.map { if(it.id in ids && it.kind==EntryKind.NOTE && it.deletedAt==null) it.copy(pinned=pinned) else it },onSaved)
    }
    fun setArchived(ids:Set<String>, archived:Boolean, onSaved:(Boolean)->Unit) {
        if(ids.isEmpty()) return
        commit(tasks.map { if(it.id in ids && it.kind==EntryKind.NOTE && it.deletedAt==null) it.withState(if(archived)TaskState.VOID else TaskState.OPEN) else it },onSaved)
    }
    /** Physical media cleanup must happen only after this durable metadata save. */
    fun purgeTrash(ids:Set<String>, onSaved:(Boolean,List<cn.jiaoban.ImportedAttachment>)->Unit) {
        val removed=tasks.filter { it.id in ids && it.deletedAt!=null && it.kind==EntryKind.NOTE }
        if(removed.isEmpty()) { onSaved(true,emptyList());return }
        commit(tasks.filterNot { candidate -> removed.any { it.id==candidate.id } }) { ok ->
            onSaved(ok,if(ok)removed.flatMap { it.attachments } else emptyList())
        }
    }
    fun setWorkStage(id: String, stage: WorkStage) = change(id) { it.withWorkStage(stage) }
    fun addFollowUp(id: String, text: String) = change(id) { it.withFollowUp(text) }

    /** 只撤销对应快照；后续新进展或编辑不能被一个旧“撤销”按钮覆盖。 */
    fun undoChange(before: Task, expected: Task): Boolean {
        if(tasks.firstOrNull { it.id == before.id } != expected) return false
        update(before)
        return true
    }
    var importing by mutableStateOf(false)
        private set
    data class ImportResult(val added:Int=0,val skipped:Int=0,val error:String?=null)
    var lastImportResult by mutableStateOf<ImportResult?>(null)
        private set
    fun importWriting(plan:cn.jiaoban.importing.ImportPlan,filesDir:java.io.File,onDone:(ImportResult)->Unit) {
        if(importing)return
        plan.retain()
        importing=true;lastImportResult=null;dirty=true
        val currentRevision=++revision
        io.execute {
            val result=runCatching {
                val existing=store.load()
                val additions=plan.additions(existing)
                val matchingExisting=existing.filter { old -> plan.records.any { it.id==old.id } }
                val needed=(additions.flatMap{it.attachments}.map{it.relativePath} + matchingExisting.flatMap{it.attachments}.map{it.relativePath}.filter{it in plan.assetPaths}).toSet()
                cn.jiaoban.importing.ImportStorage.install(filesDir,plan,needed)
                val merged=existing+additions
                store.save(merged)
                Triple(merged,additions.size,plan.records.size-additions.size)
            }
            plan.close()
            // Keep complete published attachments after a failed metadata save: another editor
            // may already reference the same content hash. The original library remains unchanged.
            main.post {
                importing=false
                result.getOrNull()?.let{(merged,_,_)->savedTasks=merged}
                if(currentRevision==revision)tasks=savedTasks
                lastImportResult=result.fold({ImportResult(it.second,it.third)},{ImportResult(error=it.message ?: "导入失败，原资料未改动")})
                onDone(lastImportResult!!)
            }
        }
    }

    private var failedRetry:List<Task>?=null
    fun retrySave() = commit(failedRetry ?: tasks)

    /** 最近交代过事的人，用来少打字。 */
    fun recentFroms(): List<String> =
        tasks.asReversed().map { it.from.trim() }.filter { it.isNotEmpty() }.distinct().take(6)

    private fun commit(next: List<Task>, onSaved:((Boolean)->Unit)?=null,forceIds:Set<String> = emptySet()) {
        val before=tasks.associateBy{it.id}
        val updates=next.filter{before[it.id]!=it || it.id in forceIds}.associateBy{it.id}
        val removed=before.keys-next.map{it.id}.toSet()
        dirty=true
        tasks=next
        val currentRevision=++revision
        io.execute {
            // Apply this operation to the latest durable snapshot, not a queued
            // optimistic full copy. A failed earlier edit cannot hitchhike on B.
            val result=runCatching {
                val base=store.load()
                val present=base.map{it.id}.toSet()
                val persisted=base.filter{it.id !in removed}.map{updates[it.id] ?: it} + updates.values.filter{it.id !in present}
                store.save(persisted)
                persisted
            }
            main.post {
                result.getOrNull()?.let{savedTasks=it}
                if(currentRevision==revision) {
                    tasks=savedTasks
                    saveError=if(result.isFailure)"改动暂未保存到手机，请重试" else null
                    failedRetry=if(result.isFailure && onSaved==null)next else null
                }
                onSaved?.invoke(result.isSuccess)
            }
        }
    }
}

