package cn.jiaoban.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.jiaoban.Task
import cn.jiaoban.TaskState
import cn.jiaoban.bucketOf
import cn.jiaoban.data.TasksHolder
import cn.jiaoban.peopleStats
import cn.jiaoban.personLabel
import cn.jiaoban.sortForPerson
import java.time.LocalDate

/**
 * 按人看：记录本的主线是「谁交代的」。
 * 先列出所有人（还在办几件、过期几件），点一个人看他交代过的事，办完作废的也在里面。
 */
@Composable
fun PeopleScreen(
    holder: TasksHolder,
    today: LocalDate,
    contentPadding: PaddingValues,
    selected: String?,
    onSelect: (String?) -> Unit,
    onOpen: (Task) -> Unit,
) {
    if (selected == null) {
        PersonList(holder, contentPadding, today, onSelect)
    } else {
        PersonDetail(holder, contentPadding, today, selected, onBack = { onSelect(null) }, onOpen = onOpen)
    }
}

@Composable
private fun PersonList(
    holder: TasksHolder,
    contentPadding: PaddingValues,
    today: LocalDate,
    onSelect: (String) -> Unit,
) {
    val stats = peopleStats(holder.tasks, today)
    if (stats.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("还没记过事", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "hint") {
            Text(
                text = "点一个人，看他交代过哪些事（办完和过期的也在里面）",
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(stats, key = { "person-" + it.name }) { stat ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(stat.name) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = personLabel(stat.name),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = buildString {
                            append("共 ${stat.total} 件")
                            if (stat.open > 0) append(" · 手上 ${stat.open} 件")
                            if (stat.overdue > 0) append(" · 过期 ${stat.overdue} 件")
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "›",
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item(key = "tail") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun PersonDetail(
    holder: TasksHolder,
    contentPadding: PaddingValues,
    today: LocalDate,
    name: String,
    onBack: () -> Unit,
    onOpen: (Task) -> Unit,
) {
    val mine = sortForPerson(holder.tasks, name)
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "head") {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = personLabel(name),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onBack) { Text("换个人") }
                }
                val open = mine.count { it.state == TaskState.OPEN }
                val overdue = mine.count { bucketOf(it, today) == cn.jiaoban.Bucket.OVERDUE }
                Text(
                    text = buildString {
                        append("共 ${mine.size} 件")
                        if (open > 0) append(" · 手上 $open 件")
                        if (overdue > 0) append(" · 过期 $overdue 件")
                    },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(mine, key = { "mine-" + it.id }) { task ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(task) }
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.text,
                        fontSize = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (task.state == TaskState.OPEN) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = listOfNotNull(
                            cn.jiaoban.metaLine(task, today),
                            when (task.state) {
                                TaskState.DONE -> "已办完"
                                TaskState.VOID -> "已作废"
                                TaskState.OPEN -> null
                            },
                        ).joinToString(" · "),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item(key = "tail") { Spacer(Modifier.height(24.dp)) }
    }
}
