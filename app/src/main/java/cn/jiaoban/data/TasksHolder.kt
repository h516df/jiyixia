package cn.jiaoban.data

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.jiaoban.Task
import cn.jiaoban.TaskState
import cn.jiaoban.ExpiryPolicy
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

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jiaoban-io").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    var tasks by mutableStateOf<List<Task>>(emptyList())
        private set

    var loaded by mutableStateOf(false)
        private set

    /** 读盘还没回来时用户就动过数据了，这时不能再拿读到的旧快照覆盖内存。 */
    private var dirty = false

    fun start() {
        io.execute {
            val list = store.load()
            main.post {
                // 读盘期间如果用户已经记了东西，别拿旧快照把它盖掉
                if (!dirty) tasks = list
                loaded = true
            }
        }
    }

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
    ) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val from = fromOverride ?: PersonParser.parse(clean, recentFroms())
        val due = if (overrideDue) dueOverride else DueParser.withDefault(clean, today)
        add(clean, from, due, expiryPolicy)
    }

    fun add(text: String, from: String, due: LocalDate?, expiryPolicy: ExpiryPolicy = ExpiryPolicy.MUST_FINISH) {
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
            )
        )
    }

    fun update(task: Task) = commit(tasks.map { if (it.id == task.id) task else it })

    fun setState(id: String, state: TaskState) = commit(
        tasks.map {
            if (it.id != id) {
                it
            } else {
                it.copy(state = state, closedAt = if (state == TaskState.OPEN) null else Instant.now())
            }
        }
    )

    /** 最近交代过事的人，用来少打字。 */
    fun recentFroms(): List<String> =
        tasks.asReversed().map { it.from.trim() }.filter { it.isNotEmpty() }.distinct().take(6)

    private fun commit(next: List<Task>) {
        dirty = true
        tasks = next
        io.execute { store.save(next) }
    }
}
