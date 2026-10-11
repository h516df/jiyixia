package cn.jiaoban.data

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.jiaoban.Task
import cn.jiaoban.TaskState
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

    fun start() {
        io.execute {
            val list = store.load()
            main.post {
                tasks = list
                loaded = true
            }
        }
    }

    fun add(text: String, from: String, due: LocalDate?) {
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
        tasks = next
        io.execute { store.save(next) }
    }
}
