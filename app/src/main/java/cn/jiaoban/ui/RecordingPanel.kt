package cn.jiaoban.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.jiaoban.media.VoiceCapturePhase
import cn.jiaoban.media.VoiceSessionState
import java.util.Locale
import kotlinx.coroutines.launch

private fun recordingElapsed(elapsedMs: Long): String {
    val seconds = (elapsedMs.coerceAtLeast(0) / 1_000)
    return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
}

/** An original lightweight microphone glyph; no extended icon pack needed. */
@Composable
internal fun MicGlyph(modifier: Modifier = Modifier.size(23.dp), color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier) {
        val unit = size.width / 24f
        val stroke = 1.9f * unit
        drawRoundRect(color, topLeft = Offset(9f * unit, 3f * unit), size = Size(6f * unit, 12f * unit), cornerRadius = CornerRadius(3f * unit))
        drawArc(color, startAngle = 0f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(5f * unit, 7f * unit), size = Size(14f * unit, 12f * unit),
            style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawLine(color, Offset(12f * unit, 19f * unit), Offset(12f * unit, 22f * unit), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(8f * unit, 22f * unit), Offset(16f * unit, 22f * unit), stroke, cap = StrokeCap.Round)
    }
}

/** Persistent state control after the recording sheet is minimized. */
@Composable internal fun MiniVoiceBar(state:VoiceSessionState,onExpand:()->Unit,onPause:()->Unit,onResume:()->Unit) {
    if(state.sessionId==null || state.phase==VoiceCapturePhase.IDLE) return
    val recording=state.phase==VoiceCapturePhase.RECORDING
    Surface(onClick=onExpand,modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=3.dp),
        shape=androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        color=MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.padding(start=14.dp,end=5.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            MicGlyph(Modifier.size(17.dp))
            Text(if(recording)"录音中" else if(state.phase==VoiceCapturePhase.STOPPED)"录音待保存" else "录音已暂停",
                style=MaterialTheme.typography.labelMedium,modifier=Modifier.weight(1f))
            Text(recordingElapsed(state.elapsedMs),style=MaterialTheme.typography.labelMedium)
            if(recording || state.phase==VoiceCapturePhase.PAUSED || state.phase==VoiceCapturePhase.INTERRUPTED)
                IconButton(onClick={if(recording)onPause() else onResume()},modifier=Modifier.semantics {
                    contentDescription=if(recording)"暂停录音" else "继续录音"
                }) {
                    Text(if(recording)"Ⅱ" else "▶",color=MaterialTheme.colorScheme.primary,fontSize=20.sp)
                }
            Icon(Icons.Default.KeyboardArrowUp,"展开录音",Modifier.size(20.dp))
        }
    }
}

/** One session, even if the recorder protects its bytes in several physical segments. */
@Composable
internal fun RecordingControlPanel(
    state: VoiceSessionState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onRecover: () -> Unit = {},
    notificationAllowed: Boolean = true,
) {
    val active = state.phase == VoiceCapturePhase.RECORDING
    val stopped = state.phase == VoiceCapturePhase.STOPPED
    val title = when (state.phase) {
        VoiceCapturePhase.RECORDING -> "正在录音"
        VoiceCapturePhase.PAUSED -> "录音已暂停"
        VoiceCapturePhase.INTERRUPTED -> "录音已中断"
        VoiceCapturePhase.STOPPED -> "录音已完成"
        VoiceCapturePhase.ERROR -> "录音需要处理"
        VoiceCapturePhase.IDLE -> "准备录音"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MicGlyph(modifier = Modifier.size(20.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                recordingElapsed(state.elapsedMs),
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 35.sp, lineHeight = 44.sp),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { contentDescription = "录音时长 ${recordingElapsed(state.elapsedMs)}" },
            )
            if (!notificationAllowed || active) {
                Text(if(notificationAllowed) "可以锁屏或切换应用，在通知栏暂停或结束。" else "未开启通知权限，锁屏时可能看不到暂停/结束按钮；请回到应用内操作。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!state.message.isNullOrBlank()) {
                Text(state.message, style = MaterialTheme.typography.bodyMedium, color = if (state.attachmentSaveFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.attachmentSaveFailed) {
                OutlinedButton(onClick = onRecover) { Text("恢复已完成的录音") }
            }
            if (!stopped) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (active) {
                        OutlinedButton(onClick = onPause, modifier = Modifier.fillMaxWidth()) {
                            Text("暂停")
                        }
                    } else if(state.phase==VoiceCapturePhase.PAUSED || state.phase==VoiceCapturePhase.INTERRUPTED) {
                        OutlinedButton(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
                            Text("继续")
                        }
                    }
                    Button(onClick = onStop, modifier = Modifier.fillMaxWidth(), enabled=state.sessionId!=null) {
                        Text("结束并保存")
                    }
                }
            } else if (!state.attachmentSaveFailed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("音频已暂存，等待写入记录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Full recording sheet content. Host owns permission, save callback and screen navigation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordingPanel(
    state: VoiceSessionState,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onRetrySave: () -> Unit,
    onRecover: () -> Unit,
    notificationAllowed: Boolean = true,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var dismissing by remember { mutableStateOf(false) }
    fun dismissAfterAnimation() {
        if (dismissing) return
        dismissing = true
        scope.launch {
            try {
                sheetState.hide()
            } finally {
                if (!sheetState.isVisible) onDismiss()
                else dismissing = false
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest=::dismissAfterAnimation,
        sheetState=sheetState,
        containerColor=MaterialTheme.colorScheme.background,
    ) {
      Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal=16.dp,vertical=10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("语音记录", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = ::dismissAfterAnimation, enabled = !dismissing) { Text("收起") }
        }
        RecordingControlPanel(state, onPause, onResume, onStop, onRecover, notificationAllowed)
        if (saving) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("正在保存录音…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        error?.takeIf { it.isNotBlank() }?.let { message ->
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onRetrySave, enabled = !saving) { Text("重试保存") }
        }
      }
    }
}
