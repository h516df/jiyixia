package cn.jiaoban.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import cn.jiaoban.*
import cn.jiaoban.data.*
import cn.jiaoban.parse.*
import cn.jiaoban.update.Updater
import kotlinx.coroutines.launch
import java.time.*

private enum class Screen(val label: String, val icon: ImageVector) {
    MAIN("事情", Icons.Default.Home), PEOPLE("按人", Icons.Default.Person),
    HISTORY("历史", Icons.Default.Check), SETTINGS("设置", Icons.Default.Settings), UPDATE("检查更新", Icons.Default.Refresh)
}
private enum class Filter(val label: String) { ALL("全部"), TODAY("今天"), OVERDUE("逾期"), LATER("接下来"), WORKING("进行中"), WAITING("等回复") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(store: TaskStore, today: LocalDate, sharedText: String? = null, onSharedHandled: () -> Unit = {}) {
    val holder = remember { TasksHolder(store).also { it.start() } }
    val context = LocalContext.current
    val updater = remember { Updater(context) }
    val focus = LocalFocusManager.current
    var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var noteEditing by remember { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var sharedDraft by rememberSaveable { mutableStateOf("") }
    var incomingShare by rememberSaveable { mutableStateOf<String?>(null) }
    var person by rememberSaveable { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun notify(message: String) { scope.launch { snack.showSnackbar(message) } }
    fun changeState(task: Task, state: TaskState) {
        val before = holder.tasks.firstOrNull { it.id == task.id } ?: return
        if(task != before) holder.update(task)
        val expected = holder.setState(task.id, state) ?: return
        editId = null
        scope.launch {
            val result = snack.showSnackbar(when(state) {
                TaskState.DONE -> "已办完，记录留在历史"; TaskState.VOID -> "已作废，记录仍保留"; else -> "已恢复"
            }, actionLabel = "撤销", duration = SnackbarDuration.Short)
            if(result == SnackbarResult.ActionPerformed && !holder.undoChange(before, expected))
                snack.showSnackbar("已有新的进展，请在详情里调整")
        }
    }
    LaunchedEffect(sharedText, holder.loaded) {
        if(holder.loaded && !sharedText.isNullOrBlank()) {
            if(creating || editId != null || noteEditing) {
                incomingShare = sharedText.trim()
            } else {
                sharedDraft = sharedText.trim(); creating = true; searching = false; screen = Screen.MAIN
            }
            onSharedHandled()
        }
    }
    BackHandler(enabled = detailId != null || searching || screen != Screen.MAIN) {
        when {
            detailId != null -> detailId = null
            searching -> { searching = false; query = ""; focus.clearFocus() }
            screen == Screen.PEOPLE && person != null -> person = null
            screen == Screen.UPDATE -> screen = Screen.SETTINGS
            else -> screen = Screen.MAIN
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing, containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(title = {
                if(detailId != null) Text("事项详情", fontWeight = FontWeight.SemiBold)
                else if(searching) OutlinedTextField(query, { query = it }, singleLine = true,
                    placeholder = { Text("搜事情或交代人") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                else Column {
                    Text(if(screen == Screen.MAIN) "交办" else screen.label, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                    if(screen == Screen.MAIN) Text("${today.monthValue}月${today.dayOfMonth}日 · 星期${listOf("一","二","三","四","五","六","日")[today.dayOfWeek.value-1]}",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }, navigationIcon = {
                if(detailId != null) IconButton(onClick = { detailId = null }) { Icon(Icons.Default.ArrowBack, "返回记录") }
                else if(screen == Screen.UPDATE) IconButton(onClick = { screen = Screen.SETTINGS }) {
                    Icon(Icons.Default.ArrowBack, "返回设置")
                }
            }, actions = {
                if(detailId != null) TextButton(onClick = { editId = detailId }) { Text("编辑") }
                else if(searching) TextButton(onClick = { searching = false; query = ""; focus.clearFocus() }) { Text("取消") }
                else if(screen != Screen.UPDATE && screen != Screen.SETTINGS) IconButton(onClick = { searching = true }) {
                    Icon(Icons.Default.Search, "搜索全部记录")
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
        bottomBar = {
            if(detailId == null && !searching && screen != Screen.UPDATE) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Screen.entries.filter { it != Screen.UPDATE }.forEach { item ->
                    NavigationBarItem(selected = screen == item,
                        onClick = { screen = item; person = null; focus.clearFocus() },
                        icon = { Icon(item.icon, null) }, label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.primary,selectedTextColor = MaterialTheme.colorScheme.primary))
                }
            }
        },
        floatingActionButton = {
            if(holder.loaded && detailId == null && !searching && screen == Screen.MAIN) ExtendedFloatingActionButton(
                onClick = { sharedDraft = ""; creating = true }, icon = { Icon(Icons.Default.Add, null) },
                text = { Text("记一件") },
                modifier = Modifier.zIndex(2f).semantics(mergeDescendants = true) { contentDescription = "记一件" },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary)
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
        holder.saveError?.let { message ->
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { holder.retrySave() }) { Text("重试保存") }
                }
            }
        }
        val innerPadding = PaddingValues(0.dp)
        val detail = holder.tasks.firstOrNull { it.id == detailId }
        when {
            detail != null -> TaskDetail(detail, today, onStage = { holder.setWorkStage(detail.id,it) },
                onNote = { holder.addFollowUp(detail.id,it) }, onState = { changeState(detail,it) },
                onNoteEditing = { noteEditing = it })
            !holder.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            searching -> RecordList(searchAll(holder.tasks, query), today, innerPadding,
                if(query.isBlank()) "输入关键词，找回所有记录" else "没有找到“$query”",
                "正文、交代人和跟进记录都能搜到", { detailId = it.id })
            screen == Screen.MAIN -> Home(holder.tasks, today, innerPadding, { detailId = it.id },
                { changeState(it, TaskState.DONE) }, { sharedDraft = ""; creating = true },
                { task, stage -> holder.setWorkStage(task.id,stage) })
            screen == Screen.PEOPLE -> People(holder.tasks, today, innerPadding, person, { person = it }, { detailId = it.id })
            screen == Screen.HISTORY -> History(holder.tasks, today, innerPadding, { detailId = it.id })
            screen == Screen.UPDATE -> UpdateScreen(updater, innerPadding)
            else -> Settings(innerPadding) { screen = Screen.UPDATE }
        }
        }
    }
    if(holder.loaded && (creating || editId != null)) {
        val task = holder.tasks.firstOrNull { it.id == editId }
        Editor(task, if(creating) sharedDraft else "", today, holder.recentFroms(),
            onDismiss = { creating = false; editId = null; sharedDraft = "" },
            onSave = { text, from, due, policy ->
                if(task == null) holder.addFromText(text, today, fromOverride = from, dueOverride = due,
                    overrideDue = true, expiryPolicy = policy)
                else holder.update(task.copy(text = text.trim(), from = from.trim(), due = due, expiryPolicy = policy))
                creating = false; editId = null; sharedDraft = ""; focus.clearFocus()
                notify(if(task == null) "已记下" else "已保存")
            }, onState = { updated, state -> changeState(updated, state) })
    }
    if(incomingShare != null) AlertDialog(onDismissRequest = { incomingShare = null },
        title = { Text("收到新的分享") },
        text = { Text("当前编辑还没有结束。继续当前编辑会取消这次分享；也可以放弃当前改动，改记分享内容。") },
        confirmButton = { TextButton(onClick = { incomingShare = null }) { Text("继续当前编辑") } },
        dismissButton = { TextButton(onClick = {
            sharedDraft = incomingShare ?: ""; incomingShare = null
            editId = null; detailId = null; noteEditing = false; creating = true; searching = false; screen = Screen.MAIN
        }) { Text("改记分享内容") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Home(tasks: List<Task>, today: LocalDate, padding: PaddingValues,
                 onOpen: (Task) -> Unit, onDone: (Task) -> Unit, onAdd: () -> Unit, onStage: (Task, WorkStage) -> Unit) {
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var expiredOpen by rememberSaveable { mutableStateOf(false) }
    val active = activeTasks(tasks, today)
    val overdue = followUpOverdue(tasks, today)
    val current = dueToday(active, today)
    val later = active.filter { bucketOf(it, today) != Bucket.TODAY && bucketOf(it, today) != Bucket.OVERDUE }
    val expired = expiredTasks(tasks, today)
    LazyColumn(Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Surface(Modifier.fillMaxWidth().padding(vertical = 8.dp),shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Count("今天到期", current.size, filter == Filter.TODAY, Modifier.weight(1f)) { filter = Filter.TODAY }
                Count("逾期待办", overdue.size, filter == Filter.OVERDUE, Modifier.weight(1f)) { filter = Filter.OVERDUE }
                Count("手上待办", active.size, filter == Filter.ALL, Modifier.weight(1f)) { filter = Filter.ALL }
            }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Filter.entries.forEach { f -> FilterChip(filter == f, { filter = f }, label = { Text(f.label) }) }
            }
        }
        val groups = when(filter) {
            Filter.ALL -> listOf("逾期 · 继续跟进" to overdue, "今天要办" to current, "接下来" to later)
            Filter.TODAY -> listOf("今天要办" to current)
            Filter.OVERDUE -> listOf("逾期 · 继续跟进" to overdue)
            Filter.LATER -> listOf("接下来" to later)
            Filter.WORKING -> listOf("正在办理" to active.filter { it.workStage == WorkStage.WORKING })
            Filter.WAITING -> listOf("等回复 · 继续跟进" to active.filter { it.workStage == WorkStage.WAITING })
        }
        if(groups.all { it.second.isEmpty() }) item {
            Empty(if(filter == Filter.ALL) "暂时没有要办的事" else when(filter) {
                Filter.TODAY -> "今天没有到期的事"; Filter.OVERDUE -> "没有逾期待办"
                Filter.WORKING -> "还没有进行中的事"; Filter.WAITING -> "没有等回复的事"
                else -> "暂时没有后续安排"
            }, if(filter == Filter.ALL) "领导交代、微信消息，都可以记在这里" else "切到“全部”查看其他事情",
                if(filter == Filter.ALL) onAdd else null)
        }
        groups.forEach { (title, records) ->
            if(records.isNotEmpty()) {
                item(key = "section-$title") { Section(title, records.size) }
                items(records, key = { it.id }) { TaskCard(it, today, onOpen, onDone, onStage) }
            }
        }
        if(expired.isNotEmpty() && filter == Filter.ALL) {
            item(key = "expired-toggle") {
                Surface(onClick = { expiredOpen = !expiredOpen }, shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("到期失效 · ${expired.size} 件", fontWeight = FontWeight.Medium)
                            Text("记录仍保留，点开查看或改为必须办完", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(if(expiredOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            if(expiredOpen) "收起到期失效" else "展开到期失效")
                    }
                }
            }
            if(expiredOpen) items(expired, key = { "expired-${it.id}" }) { TaskCard(it, today, onOpen) }
        }
    }
}
@Composable private fun Count(label: String, count: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.semantics { this.selected = selected }, shape = RoundedCornerShape(16.dp),
        color = if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Column(Modifier.heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(count.toString(), fontSize = 23.sp, fontWeight = FontWeight.SemiBold,
                color = if(label == "逾期待办" && count > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = if(selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun Section(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text("$count 件", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun TaskCard(task: Task, today: LocalDate, onOpen: (Task) -> Unit, onDone: ((Task) -> Unit)? = null, onStage: ((Task, WorkStage) -> Unit)? = null) {
    val urgent = bucketOf(task,today) == Bucket.OVERDUE && !isExpired(task,today) && task.state == TaskState.OPEN
    val status = when {
        task.state == TaskState.DONE -> "已办完"; task.state == TaskState.VOID -> "已作废"
        isExpired(task,today) -> "到期失效"; urgent -> "逾期 ${-daysLeft(task,today)!!} 天"
        bucketOf(task,today) == Bucket.TODAY -> "今天"; else -> "待办"
    }
    Surface(onClick = { onOpen(task) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(task.text, fontSize = 16.sp, lineHeight = 23.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(personLabel(task.from), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(if(task.state != TaskState.OPEN || isExpired(task,today)) status else task.workStage.label,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(recordMeta(task,today) + if(task.state == TaskState.OPEN) " · " + if(task.expiryPolicy == ExpiryPolicy.MUST_FINISH) "必须办完" else "到期失效" else "",
                    style = MaterialTheme.typography.bodySmall, color = if(urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                task.followUps.lastOrNull { it.kind == FollowUpKind.NOTE }?.let { note ->
                    Text("最近：${note.text}",maxLines = 2,overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(onStage != null && task.state == TaskState.OPEN && !isExpired(task,today)) {
                    val stageAction=when(task.workStage){WorkStage.WORKING->"等回复";WorkStage.WAITING->"继续办理";else->"开始办"}
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick={onStage(task,if(task.workStage==WorkStage.WORKING) WorkStage.WAITING else WorkStage.WORKING)},
                            modifier=Modifier.semantics { contentDescription="$stageAction：${task.text}" },
                            contentPadding=PaddingValues(horizontal=8.dp)) {
                            Text(stageAction)
                        }
                        TextButton(onClick={onOpen(task)},contentPadding=PaddingValues(horizontal=8.dp)){Text("查看 / 记进展")}
                    }
                }
            }
            if(onDone != null && task.state == TaskState.OPEN) IconButton(onClick = { onDone(task) }) {
                Icon(Icons.Default.Check, "办完：${task.text}", tint = MaterialTheme.colorScheme.primary)
            } else Icon(Icons.Default.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun Empty(title: String, subtitle: String, onAdd: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(Icons.Default.Check, null, Modifier.padding(18.dp).size(28.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if(onAdd != null) TextButton(onClick = onAdd) { Text("记下第一件事") }
    }
}
@Composable private fun RecordList(tasks: List<Task>, today: LocalDate, padding: PaddingValues, empty: String,
                                   subtitle: String = "记录还会留在这里", onOpen: (Task) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if(tasks.isEmpty()) item { Empty(empty,subtitle) }
        else {
            item { Text("共 ${tasks.size} 件", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(tasks, key = { it.id }) { TaskCard(it,today,onOpen) }
        }
    }
}
@Composable private fun History(tasks: List<Task>, today: LocalDate, padding: PaddingValues, onOpen: (Task) -> Unit) {
    var state by rememberSaveable { mutableStateOf<TaskState?>(null) }
    Column(Modifier.fillMaxSize().padding(padding)) {
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null to "全部", TaskState.DONE to "已办完", TaskState.VOID to "已作废").forEach { (key,label) ->
                FilterChip(state == key, { state = key }, label = { Text(label) })
            }
        }
        val records = sortForHistory(tasks).filter { state == null || it.state == state }
        LazyColumn(Modifier.fillMaxSize(),contentPadding = PaddingValues(20.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if(records.isEmpty()) item { Empty("还没有历史记录","办完和作废的事情会留在这里，随时可以恢复") }
            records.groupBy { it.closedAt?.atZone(ZoneId.systemDefault())?.toLocalDate() }.forEach { (date, group) ->
                item { Section(date?.let { "${it.year}年${it.monthValue}月${it.dayOfMonth}日" } ?: "未记录处理时间",group.size) }
                items(group,key = { it.id }) { TaskCard(it,today,onOpen) }
            }
        }
    }
}
@Composable private fun People(tasks: List<Task>, today: LocalDate, padding: PaddingValues, selected: String?,
                               onSelect: (String?) -> Unit, onOpen: (Task) -> Unit) {
    if(selected != null) Column(Modifier.fillMaxSize().padding(padding)) {
        TextButton(onClick = { onSelect(null) }, modifier = Modifier.padding(horizontal = 12.dp)) {
            Icon(Icons.Default.ArrowBack,null,Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("全部交代人")
        }
        Text(personLabel(selected),Modifier.padding(horizontal = 20.dp),fontWeight = FontWeight.Bold,style = MaterialTheme.typography.headlineSmall)
        RecordList(sortForPerson(tasks,selected),today,PaddingValues(0.dp),"这个人还没有记录",onOpen = onOpen)
    } else LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding = PaddingValues(20.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val stats = peopleStats(tasks,today)
        if(stats.isEmpty()) item { Empty("还没有交代人","记事时写上名字，会自动整理到这里") }
        else {
            item { Text("谁交代的，一眼找回",color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(stats,key = { it.name }) { stat ->
                val mine = tasks.filter { it.from.trim() == stat.name }
                val overdue = followUpOverdue(mine,today).size
                val waiting = activeTasks(mine,today).count { it.workStage == WorkStage.WAITING }
                Surface(onClick = { onSelect(stat.name) },shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Surface(shape = CircleShape,color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(personLabel(stat.name).take(1),Modifier.padding(14.dp),fontWeight = FontWeight.Bold,color = MaterialTheme.colorScheme.primary)
                        }
                        Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(personLabel(stat.name),fontWeight = FontWeight.SemiBold,fontSize = 17.sp)
                            Text("待办 ${activeTasks(mine,today).size} 件" + if(waiting > 0) " · 等回复 $waiting 件" else " · 共 ${stat.total} 件",
                                style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if(overdue > 0) Text("$overdue 件逾期需要跟进",style = MaterialTheme.typography.labelSmall,color = MaterialTheme.colorScheme.error)
                        }
                        Icon(Icons.Default.KeyboardArrowRight,null)
                    }
                }
            }
        }
    }
}
@Composable private fun Settings(padding: PaddingValues,onUpdate: () -> Unit) {
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName,0).versionName }
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Surface(shape = RoundedCornerShape(24.dp),color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(22.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("交办",fontSize = 24.sp,fontWeight = FontWeight.Bold)
                Text("别人交代的，记得住、办得好。",color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("当前版本 $version",style = MaterialTheme.typography.bodySmall)
            }
        }
        Surface(onClick = onUpdate,shape = RoundedCornerShape(20.dp)) {
            Row(Modifier.fillMaxWidth().padding(20.dp),verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("检查更新",fontWeight = FontWeight.SemiBold)
                    Text("查看中文更新说明，下载后由系统安装",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.KeyboardArrowRight,null)
            }
        }
        Text("两种事情，两种处理方式",style = MaterialTheme.typography.titleMedium,fontWeight = FontWeight.SemiBold)
        Text("必须办完：到了期限还没办完，继续留在首页，标成逾期。\n\n到期失效：期限过后自动收起，记录仍可查看和恢复。",
            style = MaterialTheme.typography.bodyMedium,color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("记录保存在手机上。办完、作废和到期都不会删除记录。系统备份行为取决于手机设置。",
            style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable private fun Editor(task: Task?,initial: String,today: LocalDate,known: List<String>,
    onDismiss: () -> Unit,onSave: (String,String,LocalDate?,ExpiryPolicy) -> Unit,onState: (Task,TaskState) -> Unit) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var text by rememberSaveable(task?.id,initial) { mutableStateOf(task?.text ?: initial) }
    var fromManual by rememberSaveable(task?.id,initial) { mutableStateOf(task != null) }
    var fromDraft by rememberSaveable(task?.id,initial) { mutableStateOf(task?.from ?: "") }
    var dueManual by rememberSaveable(task?.id,initial) { mutableStateOf(task != null) }
    var dueDraft by rememberSaveable(task?.id,initial) { mutableStateOf(task?.due?.toString()) }
    var policy by rememberSaveable(task?.id,initial) { mutableStateOf(task?.expiryPolicy ?: ExpiryPolicy.MUST_FINISH) }
    var calendar by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var voidConfirm by remember { mutableStateOf(false) }
    val parsedFrom = remember(text,known) { PersonParser.parse(text,known) }
    val parsedDue = remember(text,today) { DueParser.parse(text,today) }
    val from = if(fromManual) fromDraft else parsedFrom
    val due = if(dueManual) dueDraft?.let { LocalDate.parse(it) } else parsedDue ?: today.plusDays(DueParser.DEFAULT_DAYS)
    val dirty = if(task == null) text.isNotBlank() else text != task.text || from != task.from || due != task.due || policy != task.expiryPolicy
    fun dismiss() { if(dirty) discard = true else onDismiss() }
    fun setDue(value: LocalDate?) { dueManual = true; dueDraft = value?.toString() }
    fun edited() = task!!.copy(text = text.trim(),from = from.trim(),due = due,expiryPolicy = policy)
    LaunchedEffect(Unit) { if(task == null && initial.isEmpty()) focus.requestFocus() }
    ModalBottomSheet(onDismissRequest = { dismiss() },sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            if(target == SheetValue.Hidden && dirty) { discard = true; false } else true
        }),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if(task == null) "记一件交办" else "编辑交办",fontSize = 22.sp,fontWeight = FontWeight.Bold)
                    Text(if(task == null) "写一句话，人和日期会自动识别" else "全文、交代人和期限都在这里",
                        style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { dismiss() }) { Icon(Icons.Default.Close,"关闭详情") }
            }
            OutlinedTextField(text,{ text = it },label = { Text("交代了什么") },placeholder = { Text("例如：王总让周五交活动表") },
                minLines = 3,maxLines = 7,modifier = Modifier.fillMaxWidth().focusRequester(focus),shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default))
            OutlinedTextField(from,{ fromManual = true; fromDraft = it },label = { Text("谁交代的（可不填）") },
                singleLine = true,modifier = Modifier.fillMaxWidth(),shape = RoundedCornerShape(16.dp))
            if(task == null && !fromManual && parsedFrom.isNotEmpty())
                Text("已识别交代人：$parsedFrom，可直接修改",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.primary)
            Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("期限",fontWeight = FontWeight.SemiBold)
                    Text(due?.let { "${it.year}年${it.monthValue}月${it.dayOfMonth}日" } ?: "没有期限",color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { focusManager.clearFocus(); calendar = true }) {
                    Icon(Icons.Default.DateRange,null,Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("选择日期")
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0L to "今天",1L to "明天",3L to "三天后",7L to "一周后").forEach { (days,label) ->
                    FilterChip(due == today.plusDays(days),{ setDue(today.plusDays(days)) },label = { Text(label) })
                }
                FilterChip(due == null,{ setDue(null) },label = { Text("无期限") })
            }
            if(task == null && !dueManual) Text(if(parsedDue != null) "已识别期限，可点日历修改"
                else "未提期限，暂按三天后；可改日期或选无期限",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("到了期限之后",fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Policy("必须办完","逾期继续跟进",policy == ExpiryPolicy.MUST_FINISH,Modifier.weight(1f)) { policy = ExpiryPolicy.MUST_FINISH }
                Policy("到期失效","到期自动收起",policy == ExpiryPolicy.EXPIRES,Modifier.weight(1f)) { policy = ExpiryPolicy.EXPIRES }
            }
            Button(onClick = { onSave(text,from,due,policy) },enabled = text.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),shape = RoundedCornerShape(16.dp)) {
                Text(if(task == null) "记下" else "保存修改")
            }
            if(task != null) {
                if(task.state == TaskState.OPEN) Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { onState(edited(),TaskState.DONE) },enabled = text.isNotBlank()) { Text("办完了") }
                    TextButton(onClick = { voidConfirm = true },enabled = text.isNotBlank()) { Text("作废这件事",color = MaterialTheme.colorScheme.error) }
                } else OutlinedButton(onClick = { onState(edited(),TaskState.OPEN) },enabled = text.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()) { Text("恢复为待办") }
                Text("记录于 ${task.createdAt.atZone(ZoneId.systemDefault()).toLocalDate()} · 记录不会被删除",
                    style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if(calendar) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = (due ?: today).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { calendar = false },confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let { setDue(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }; calendar = false
            },enabled = picker.selectedDateMillis != null) { Text("确定日期") }
        },dismissButton = { TextButton(onClick = { calendar = false }) { Text("取消") } }) {
            DatePicker(picker,title = { Text("选择期限",Modifier.padding(24.dp)) })
        }
    }
    if(discard) AlertDialog(onDismissRequest = { discard = false },title = { Text("还有未保存的内容") },
        text = { Text("继续编辑，或放弃这次改动。") },
        confirmButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } },
        dismissButton = { TextButton(onClick = { discard = false; onDismiss() }) { Text("放弃改动") } })
    if(voidConfirm) AlertDialog(onDismissRequest = { voidConfirm = false },title = { Text("作废这件事？") },
        text = { Text("会收进历史，不删除记录，之后仍可恢复。") },
        confirmButton = { TextButton(onClick = { voidConfirm = false; onState(edited(),TaskState.VOID) }) { Text("确认作废") } },
        dismissButton = { TextButton(onClick = { voidConfirm = false }) { Text("继续保留") } })
}
@Composable private fun Policy(title: String,subtitle: String,selected: Boolean,modifier: Modifier,onClick: () -> Unit) {
    Surface(onClick = onClick,modifier = modifier,shape = RoundedCornerShape(16.dp),
        color = if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = if(selected) BorderStroke(1.dp,MaterialTheme.colorScheme.primary) else null) {
        Column(Modifier.padding(14.dp),verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title,fontWeight = FontWeight.SemiBold,color = if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(subtitle,style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
