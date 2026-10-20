package cn.jiaoban.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.jiaoban.*
import java.time.LocalDate

@Composable internal fun WritingSettings(legacyCount:Int,onLegacy:()->Unit,onExport:()->Unit,onUpdate:()->Unit,onImport:()->Unit) {
    val context=LocalContext.current
    val version=remember{context.packageManager.getPackageInfo(context.packageName,0).versionName}
    val c=MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {Text("安心写，慢慢积累",fontSize=24.sp,lineHeight=34.sp,fontWeight=FontWeight.Medium,modifier=Modifier.padding(vertical=16.dp))}
        item {SettingsAction("导入记录","Obsidian、Thino DAILY、Diarium与墨记原件",onImport)}
        item {SettingsAction("导出完整资料","记录、日记、旧版资料与附件 · ZIP 备份",onExport)}
        if(legacyCount>0)item{SettingsAction("旧版资料","$legacyCount 条完整保留 · 只读查看",onLegacy)}
        item {SettingsAction("检查更新","记一下 $version",onUpdate)}
        item {HorizontalDivider(color=c.outlineVariant)}
        item {Text("关于资料",style=MaterialTheme.typography.titleSmall)}
        item {Text("内容保存在这台设备。本应用不启用系统云备份或自动换机传输；卸载、清数据或换手机前，请主动导出完整 ZIP 并确认文件已保存。归档只收起页面，不删除文字。导出文件包含全部内容，请保存到你信任的位置。当前完整备份上限为 5000 条、单附件 1 GiB、解压总计 4 GiB（需足够本机空间）；超限会报错，不会生成声称可恢复的备份。",fontSize=14.sp,lineHeight=24.sp,color=c.onSurfaceVariant)}
        item {Text("旧版事项的原文、来源、日期和办理记录仍完整保留。完整ZIP可在“导入记录 → 记一下完整备份”恢复缺少的内容，也兼容旧版拾页备份。请勿卸载清除资料，也不要降级覆盖。",fontSize=14.sp,lineHeight=24.sp,color=c.onSurfaceVariant)}
        item {Text("记一下\n留住想法，也留住日子。",modifier=Modifier.padding(top=24.dp,bottom=24.dp),fontSize=13.sp,lineHeight=23.sp,color=c.onSurfaceVariant)}
    }
}
@Composable private fun SettingsAction(title:String,subtitle:String,action:()->Unit) {
    Surface(onClick=action,shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {Text(title,fontSize=17.sp,fontWeight=FontWeight.Medium);Text(subtitle,fontSize=13.sp,lineHeight=21.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}
@Composable internal fun LegacyPage(tasks:List<Task>,open:(Task)->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item{Text("以前的记录，仍在这里",fontSize=23.sp,fontWeight=FontWeight.Medium)}
        item{Text("只读保留全部状态与内容。可在设置导出完整资料。",fontSize=14.sp,lineHeight=24.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(bottom=12.dp))}
        items(tasks.sortedByDescending{it.createdAt},key={it.id}){PaperEntry(it,open)}
    }
}
@Composable internal fun LegacyDetail(task:Task,today:LocalDate) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(26.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item{Text("旧版资料 · 只读",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)}
        item{SelectionContainer{Text(task.text,fontSize=20.sp,lineHeight=32.sp)}}
        item{Text("原始状态：${task.state.name} · ${task.workStage.name}",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(task.from.isNotBlank())item{SelectionContainer{Text("来源：${task.from}")}}
        item{Text("记录时间：${task.createdAt}",fontSize=13.sp)}
        task.due?.let{item{Text("原日期：$it · ${task.expiryPolicy.name}",fontSize=13.sp)}}
        task.closedAt?.let{item{Text("处理时间：$it",fontSize=13.sp)}}
        if(task.important)item{Text("原标记：重要",fontSize=13.sp)}
        if(task.steps.isNotEmpty())item{SelectionContainer{Text("原步骤\n"+task.steps.joinToString("\n"){"${if(it.done) "已完成" else "未完成"} · ${it.text}"},lineHeight=26.sp)}}
        items(task.followUps,key={it.id}){SelectionContainer{Text("${it.createdAt} · ${it.kind.name}\n${it.text}",lineHeight=26.sp)}}
    }
}

