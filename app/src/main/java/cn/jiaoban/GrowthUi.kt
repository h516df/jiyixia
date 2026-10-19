package cn.jiaoban

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.jiaoban.ui.FullPage
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ReviewEntry(tasks:List<Task>,today:LocalDate,modifier:Modifier=Modifier,onOpen:(Task)->Unit) {
    var opened by rememberSaveable{mutableStateOf(false)}
    var souvenirs by rememberSaveable{mutableStateOf(false)}
    var selected by remember{mutableStateOf<GrowthBadge?>(null)}
    val context=LocalContext.current
    val store=remember{GrowthStore(context)}
    val stats=actionGrowth(tasks,today)
    val badges=GrowthRules.badges(stats.metrics.filter{it.key in listOf("done","notes")})
    var earned by remember{mutableStateOf(store.earned())}
    LaunchedEffect(badges){earned=store.settle(badges)}
    val records=completedRecords(tasks)
    val colors=MaterialTheme.colorScheme
    Surface(onClick={opened=true},modifier=modifier.fillMaxWidth(),color=colors.background) {
        Row(Modifier.heightIn(min=68.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(Icons.Default.Check,null,Modifier.size(22.dp),tint=colors.primary)
            Text("最近做成的事",Modifier.weight(1f).padding(start=14.dp),style=MaterialTheme.typography.bodyLarge)
            Icon(Icons.Default.KeyboardArrowRight,null,tint=colors.onSurfaceVariant)
        }
    }
    if(opened)FullPage({opened=false;souvenirs=false}) {
        Scaffold(contentWindowInsets=WindowInsets.safeDrawing,containerColor=colors.background,
            topBar={TopAppBar(title={Text(if(souvenirs)"日常纪念" else "回顾",style=MaterialTheme.typography.titleMedium)},
                navigationIcon={IconButton(onClick={if(souvenirs)souvenirs=false else opened=false}){Icon(Icons.Default.ArrowBack,"返回")}},
                colors=TopAppBarDefaults.topAppBarColors(containerColor=colors.background))}) {padding->
            LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                if(!souvenirs) {
                    item {Text("最近做成的事",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)}
                    if(records.isEmpty())item {Text("做完的小事，会留在这里。",Modifier.padding(vertical=32.dp),color=colors.onSurfaceVariant)}
                    records.groupBy{it.closedAt?.atZone(ZoneId.systemDefault())?.toLocalDate()}.forEach{(date,group)->
                        item {Text(date?.let{if(it==today)"今天" else (if(it.year!=today.year)"${it.year}年" else "")+"${it.monthValue}月${it.dayOfMonth}日"} ?: "较早记录 · 未记录完成时间",style=MaterialTheme.typography.labelLarge,color=colors.onSurfaceVariant)}
                        items(group,key={it.id}){task->
                            Row(Modifier.fillMaxWidth().clickable{opened=false;onOpen(task)}.padding(vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                                Icon(Icons.Default.Check,null,Modifier.size(20.dp),tint=colors.primary)
                                Text(task.text,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge,maxLines=3,overflow=TextOverflow.Ellipsis)
                                Icon(Icons.Default.KeyboardArrowRight,null,Modifier.size(18.dp),tint=colors.onSurfaceVariant)
                            }
                        }
                    }
                    item {HorizontalDivider(color=colors.outlineVariant);TextButton(onClick={souvenirs=true},modifier=Modifier.fillMaxWidth().padding(top=12.dp)){Text("日常纪念");Spacer(Modifier.weight(1f));Icon(Icons.Default.KeyboardArrowRight,null)}}
                } else {
                    item {Text("小事也值得记住",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)}
                    val collected=badges.filter{it.id in earned}
                    if(collected.isEmpty())item {Text("写下第一篇笔记，或完成一件事，都会留下纪念。",color=colors.onSurfaceVariant)}
                    items(collected,key={it.id}){badge->
                        Row(Modifier.fillMaxWidth().clickable{selected=badge}.padding(vertical=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                            Icon(if(badge.id.startsWith("notes"))Icons.Default.Edit else Icons.Default.Check,null,Modifier.size(28.dp),tint=colors.primary)
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text(badge.title,style=MaterialTheme.typography.titleMedium)
                                Text(badge.detail,style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        selected?.let{badge->AlertDialog(onDismissRequest={selected=null},title={Text(badge.title)},text={Text("${badge.detail}。这份纪念已为你保留。")},confirmButton={TextButton(onClick={selected=null}){Text("知道了")}})}
    }
}

@Composable fun RecentTasksPreference() {
    val context=LocalContext.current;val store=remember{GrowthStore(context)}
    var hidden by remember{mutableStateOf(store.hidden)}
    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).semantics{contentDescription="从最近任务中隐藏"}.toggleable(value=hidden,role=Role.Switch,onValueChange={hidden=it;store.hideRecents(it)}).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)){Text("从最近任务中隐藏");Text("桌面仍能打开",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Switch(hidden,onCheckedChange=null)
    }
}
