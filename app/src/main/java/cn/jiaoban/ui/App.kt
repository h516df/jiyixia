package cn.jiaoban.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.jiaoban.Bucket
import cn.jiaoban.MAIN_BUCKETS
import cn.jiaoban.Task
import cn.jiaoban.TaskState
import cn.jiaoban.bucketOf
import cn.jiaoban.data.TaskStore
import cn.jiaoban.data.TasksHolder
import cn.jiaoban.metaLine
import cn.jiaoban.searchAll
import cn.jiaoban.sortForHistory
import cn.jiaoban.sortForMain
import cn.jiaoban.sortForOverdue
import cn.jiaoban.summaryLine
import java.time.LocalDate

private enum class AppScreen { MAIN, HISTORY }

/** 期限的常用选项。领导交代的事大多落在今天到一周之间。 */
enum class DuePreset(val label: String, val days: Long?) {
    TODAY("今天", 0),
    TOMORROW("明天", 1),
    THREE("三天后", 3),
    WEEK("一周后", 7),
    NONE("没期限", null);

    fun resolve(today: LocalDate): LocalDate? = days?.let { today.plusDays(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(store: TaskStore) {
    val holder = remember { TasksHolder(store).also { it.start() } }
    val today = LocalDate.now()
    var screen by remember { mutableStateOf(AppScreen.MAIN) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Task?>(null) }
    var overdueOpen by remember { mutableStateOf(false) }

    BackHandler(enabled = searching || screen != AppScreen.MAIN) {
        if (searching) {
            searching = false
            query = ""
        } else {
            screen = AppScreen.MAIN
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (searching) {
                SearchBar(
                    query = query,
                    onQuery = { query = it },
                    onClose = {
                        searching = false
                        query = ""
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = if (screen == AppScreen.MAIN) "交办" else "办完和作废的",
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (screen == AppScreen.MAIN && holder.loaded && holder.tasks.isNotEmpty()) {
                                Text(
                                    text = summaryLine(holder.tasks, today),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = {
                        if (screen == AppScreen.MAIN) {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Default.Search, contentDescription = "搜索")
                            }
                            TextButton(onClick = { screen = AppScreen.HISTORY }) { Text("历史") }
                        } else {
                            TextButton(onClick = { screen = AppScreen.MAIN }) { Text("回到清单") }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
        floatingActionButton = {
            if (screen == AppScreen.MAIN && !searching) {
                FloatingActionButton(
                    onClick = { showAdd = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "记一条")
                }
            }
        },
    ) { inner ->
        val hasOpen = holder.tasks.any { it.state == TaskState.OPEN }
        when {
            !holder.loaded -> LoadingState(inner)
            screen == AppScreen.HISTORY -> HistoryScreen(holder, inner, onOpen = { editing = it })
            searching -> SearchScreen(holder, query, inner, onOpen = { editing = it })
            !hasOpen -> EmptyState(inner, hasHistory = holder.tasks.isNotEmpty()) { showAdd = true }
            else -> MainList(
                holder = holder,
                today = today,
                contentPadding = inner,
                overdueOpen = overdueOpen,
                onToggleOverdue = { overdueOpen = !overdueOpen },
                onOpen = { editing = it },
                onDone = { holder.setState(it.id, TaskState.DONE) },
            )
        }
    }

    if (showAdd) {
        AddSheet(
            recentFroms = holder.recentFroms(),
            today = today,
            onDismiss = { showAdd = false },
            onSave = { text, from, due ->
                holder.add(text, from, due)
                showAdd = false
            },
        )
    }

    editing?.let { task ->
        TaskSheet(
            task = task,
            today = today,
            onDismiss = { editing = null },
            onSave = { updated ->
                holder.update(updated)
                editing = null
            },
            onDone = { updated ->
                holder.update(updated)
                holder.setState(updated.id, TaskState.DONE)
                editing = null
            },
            onVoid = { updated ->
                holder.update(updated)
                holder.setState(updated.id, TaskState.VOID)
                editing = null
            },
            onReopen = { updated ->
                holder.update(updated)
                holder.setState(updated.id, TaskState.OPEN)
                editing = null
            },
        )
    }
}

@Composable
private fun MainList(
    holder: TasksHolder,
    today: LocalDate,
    contentPadding: PaddingValues,
    overdueOpen: Boolean,
    onToggleOverdue: () -> Unit,
    onOpen: (Task) -> Unit,
    onDone: (Task) -> Unit,
) {
    val open = holder.tasks.filter { it.state == TaskState.OPEN }
    val ordered = sortForMain(open, today)
    val overdue = sortForOverdue(holder.tasks, today)
    val groups = MAIN_BUCKETS
        .map { bucket -> bucket to ordered.filter { bucketOf(it, today) == bucket } }
        .filter { it.second.isNotEmpty() }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        groups.forEach { (bucket, list) ->
            item(key = "header-" + bucket.name) { SectionHeader(bucket.label, list.size) }
            items(list, key = { it.id }) { task ->
                TaskRow(task = task, today = today, onOpen = onOpen, onDone = onDone)
            }
        }
        if (overdue.isNotEmpty()) {
            item(key = "overdue-header") {
                OverdueHeader(count = overdue.size, open = overdueOpen, onToggle = onToggleOverdue)
            }
            if (overdueOpen) {
                items(overdue, key = { "overdue-" + it.id }) { task ->
                    TaskRow(task = task, today = today, onOpen = onOpen, dim = true)
                }
            }
        }
        item(key = "tail") { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun SearchScreen(
    holder: TasksHolder,
    query: String,
    contentPadding: PaddingValues,
    onOpen: (Task) -> Unit,
) {
    val today = LocalDate.now()
    val hits = searchAll(holder.tasks, query)
    if (hits.isEmpty()) {
        CenteredHint(
            contentPadding = contentPadding,
            text = if (query.isBlank()) "输入几个字，过期、办完、作废的都能翻出来" else "没找到和“$query”有关的事",
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "hits") { HintHeader("找到 ${hits.size} 件") }
        items(hits, key = { "hit-" + it.id }) { task ->
            TaskRow(
                task = task,
                today = today,
                onOpen = onOpen,
                dim = task.state != TaskState.OPEN,
                stateNote = when (task.state) {
                    TaskState.DONE -> "已办完"
                    TaskState.VOID -> "已作废"
                    TaskState.OPEN -> null
                },
            )
        }
        item(key = "tail") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun HistoryScreen(
    holder: TasksHolder,
    contentPadding: PaddingValues,
    onOpen: (Task) -> Unit,
) {
    val today = LocalDate.now()
    val closed = sortForHistory(holder.tasks)
    if (closed.isEmpty()) {
        CenteredHint(contentPadding = contentPadding, text = "还没有办完或作废的事")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "hint") { HintHeader("办完和作废的都留在这，点开可以恢复") }
        items(closed, key = { "closed-" + it.id }) { task ->
            TaskRow(
                task = task,
                today = today,
                onOpen = onOpen,
                dim = true,
                stateNote = if (task.state == TaskState.DONE) "已办完" else "已作废",
            )
        }
        item(key = "tail") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TaskRow(
    task: Task,
    today: LocalDate,
    onOpen: (Task) -> Unit,
    onDone: ((Task) -> Unit)? = null,
    dim: Boolean = false,
    stateNote: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(task) }
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor(task, today), CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.text,
                fontSize = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = listOfNotNull(metaLine(task, today), stateNote).joinToString(" · "),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onDone != null) {
            IconButton(onClick = { onDone(task) }) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "办完了",
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(text = "$count", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OverdueHeader(count: Int, open: Boolean, onToggle: () -> Unit) {
    Column {
        HorizontalDivider(
            modifier = Modifier.padding(top = 18.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "已过期 $count 件",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (open) "收起" else "点开看看",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun HintHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LoadingState(contentPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text("正在读记录", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CenteredHint(contentPadding: PaddingValues, text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyState(contentPadding: PaddingValues, hasHistory: Boolean, onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (hasHistory) "手上没有活的事" else "还没有人交代事",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (hasHistory) {
                "都办完了。过期的会自己沉到底下，不用你收拾。"
            } else {
                "别人让你做的事，三秒记一条：什么事、谁交代的、什么时候要。\n到期没办，它自己沉到底下，还能搜回来。"
            },
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        Button(onClick = onAdd) { Text("记一条") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        title = {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                placeholder = { Text("搜人、搜事") },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = "清空")
                        }
                    }
                },
            )
        },
        actions = { TextButton(onClick = onClose) { Text("关掉") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddSheet(
    recentFroms: List<String>,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (String, String, LocalDate?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf(DuePreset.THREE) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("记一条", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("什么事") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (text.isNotBlank()) onSave(text, from, preset.resolve(today)) },
                ),
            )
            OutlinedTextField(
                value = from,
                onValueChange = { from = it },
                label = { Text("谁交代的") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (recentFroms.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recentFroms.take(4).forEach { name ->
                        AssistChip(onClick = { from = name }, label = { Text(name) })
                    }
                }
            }
            Text("什么时候要", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DuePreset.entries.forEach { option ->
                    FilterChip(
                        selected = preset == option,
                        onClick = { preset = option },
                        label = { Text(option.label) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("算了") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onSave(text, from, preset.resolve(today)) },
                    enabled = text.isNotBlank(),
                ) { Text("记下来") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TaskSheet(
    task: Task,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (Task) -> Unit,
    onDone: (Task) -> Unit,
    onVoid: (Task) -> Unit,
    onReopen: (Task) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf(task.text) }
    var from by remember { mutableStateOf(task.from) }
    var due by remember { mutableStateOf(task.due) }
    val presetMatch = DuePreset.entries.firstOrNull { it.resolve(today) == due }
    val customDue = due
    val edited = task.copy(text = text.trim().ifBlank { task.text }, from = from.trim(), due = due)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = when (task.state) {
                    TaskState.OPEN -> "改一改"
                    TaskState.DONE -> "这件已经办完"
                    TaskState.VOID -> "这件已经作废"
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("什么事") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = from,
                onValueChange = { from = it },
                label = { Text("谁交代的") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("什么时候要", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DuePreset.entries.forEach { option ->
                    FilterChip(
                        selected = presetMatch == option,
                        onClick = { due = option.resolve(today) },
                        label = { Text(option.label) },
                    )
                }
                if (presetMatch == null && customDue != null) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text("原期限 ${customDue.monthValue}月${customDue.dayOfMonth}日") },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { onSave(edited) }, enabled = text.isNotBlank()) { Text("保存") }
                if (task.state == TaskState.OPEN) {
                    TextButton(onClick = { onDone(edited) }) { Text("办完了") }
                    TextButton(onClick = { onVoid(edited) }) { Text("作废") }
                } else {
                    TextButton(onClick = { onReopen(edited) }) { Text("恢复成没办") }
                }
            }
            Text(
                text = "作废不是删除，在“历史”里随时能恢复。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun dotColor(task: Task, today: LocalDate): Color = when (bucketOf(task, today)) {
    Bucket.TODAY -> Color(0xFFC2410C)
    Bucket.OVERDUE -> Color(0xFF9A3412)
    Bucket.TOMORROW -> Color(0xFFD97706)
    Bucket.THIS_WEEK -> Color(0xFF27705F)
    Bucket.LATER -> Color(0xFF7A8B7F)
    Bucket.NO_DUE -> Color(0xFFB9AE9C)
    Bucket.CLOSED -> Color(0xFFB9AE9C)
}
