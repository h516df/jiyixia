package cn.jiaoban.media

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import cn.jiaoban.ImportedAttachment
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A recording is one user-facing note, even though its recoverable audio is segmented. */
data class VoiceSessionState(
    val sessionId: String? = null,
    val phase: VoiceCapturePhase = VoiceCapturePhase.IDLE,
    val elapsedMs: Long = 0L,
    val attachments: List<ImportedAttachment> = emptyList(),
    val completedSegments: Int = 0,
    val message: String? = null,
    val attachmentSaveFailed: Boolean = false,
)

/**
 * App/UI-facing controller. The recorder itself lives in a microphone foreground service.
 * Only the app's visible Activity should initiate [start]; service notification actions can
 * subsequently pause, resume or stop it after the Activity has gone away.
 *
 * STOPPED is a durable *draft*, not a saved Task. Only call [acknowledgeSaved] after the
 * corresponding TaskStore callback reports success. This prevents lost voice notes when the
 * app process dies between stopping the microphone and saving the note.
 */
object VoiceSessionController {
    private val mutableState = MutableStateFlow(VoiceSessionState())
    val state: StateFlow<VoiceSessionState> = mutableState.asStateFlow()

    @Synchronized
    fun recover(context: Context) {
        // Never replace a live service's in-memory state or interrupt an active microphone.
        if (VoiceRecordingService.running) return
        val app = context.applicationContext
        val store = VoiceSessionStore(app)
        val snapshot = store.read()
        val session = snapshot.sessionId ?: run { mutableState.value = snapshot; return }
        val restored = runCatching {
            // Re-deliver only completed M4A files. An interrupted .part must not be advertised
            // as playable; RecordingJournal retains it for possible manual investigation.
            VoiceCapture(app, session) { file -> publishCompleted(app, session, file) }.also {
                it.recover()
                it.close()
            }
        }
        val next = store.read()
        val recoveryFailed = restored.isFailure ||
            restored.getOrNull()?.state?.value?.attachmentSaveFailed == true
        val saveFailed = recoveryFailed || store.hasCorruptAttachmentIndex() ||
            (next.attachments.isEmpty() && next.attachmentSaveFailed)
        val phase = if (next.phase == VoiceCapturePhase.RECORDING) VoiceCapturePhase.INTERRUPTED else next.phase
        val message = if (recoveryFailed) "已完成录音恢复失败，原文件仍保留；请重试恢复"
            else if (snapshot.phase == VoiceCapturePhase.RECORDING) "上次录音意外中断；已完成片段可保存"
            else if (!saveFailed && snapshot.attachmentSaveFailed) "已完成片段恢复成功，可保存录音"
            else next.message
        val updated = next.copy(phase = phase, message = message,
            attachmentSaveFailed = saveFailed)
        // A malformed preferences value is quarantined by VoiceSessionStore. Never overwrite
        // those raw bytes with an empty attachment list during automatic recovery.
        runCatching { store.save(updated) }
        mutableState.value = updated
    }

    fun start(context: Context, sessionId: String = UUID.randomUUID().toString()) {
        val app = context.applicationContext
        recover(app)
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mutableState.value = mutableState.value.copy(message = "请先允许麦克风权限")
            return
        }
        val previous = mutableState.value
        if (previous.sessionId != null && previous.sessionId != sessionId &&
            (previous.attachments.isNotEmpty() || previous.attachmentSaveFailed)) {
            mutableState.value = previous.copy(message = "请先保存上一条录音，再开始下一条")
            return
        }
        val intent = Intent(app, VoiceRecordingService::class.java)
            .setAction(VoiceRecordingService.ACTION_START)
            .putExtra(VoiceRecordingService.EXTRA_SESSION, sessionId)
        dispatchForeground(app, intent)
    }

    fun pause(context: Context) = dispatch(context.applicationContext, VoiceRecordingService.ACTION_PAUSE)
    fun resume(context: Context) = dispatch(context.applicationContext, VoiceRecordingService.ACTION_RESUME)
    fun stop(context: Context) = dispatch(context.applicationContext, VoiceRecordingService.ACTION_STOP)

    /** Idempotent for the matching session; never deletes audio files or a different draft. */
    @Synchronized
    fun acknowledgeSaved(context: Context, sessionId: String) {
        val store = VoiceSessionStore(context.applicationContext)
        val current = store.read()
        if (current.sessionId != sessionId || current.phase != VoiceCapturePhase.STOPPED ||
            current.attachmentSaveFailed) return
        store.clear(sessionId)
        if (mutableState.value.sessionId == sessionId) mutableState.value = VoiceSessionState()
    }

    internal fun publish(snapshot: VoiceSessionState) { mutableState.value = snapshot }

    internal fun publishCompleted(context: Context, sessionId: String, file: File) {
        val attachment = file.inputStream().use { input ->
            MediaStorage.copy(context.filesDir, input, file.name, "audio/mp4")
        }
        VoiceSessionStore(context).append(sessionId, attachment)
    }

    private fun dispatch(context: Context, action: String) {
        val intent = Intent(context, VoiceRecordingService::class.java).setAction(action)
        // A pause/resume/stop action only has meaning for an existing, started service.
        if (VoiceRecordingService.running) context.startService(intent)
        else if (action == VoiceRecordingService.ACTION_STOP) {
            recover(context)
            val current = mutableState.value
            if (current.sessionId != null && current.phase in setOf(
                    VoiceCapturePhase.INTERRUPTED, VoiceCapturePhase.PAUSED, VoiceCapturePhase.RECORDING)) {
                val stopped = current.copy(phase = VoiceCapturePhase.STOPPED)
                VoiceSessionStore(context).save(stopped)
                mutableState.value = stopped
            }
        } else if (action == VoiceRecordingService.ACTION_RESUME && mutableState.value.sessionId != null) {
            dispatchForeground(context, intent)
        }
    }

    private fun dispatchForeground(context: Context, intent: Intent) {
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (_: SecurityException) {
            mutableState.value = mutableState.value.copy(message = "系统拒绝启动录音，请检查麦克风权限")
        } catch (_: IllegalStateException) {
            mutableState.value = mutableState.value.copy(message = "请打开记一下后再开始录音")
        }
    }
}

/** Small, synchronous, fail-closed journal for an unfinished voice note. */
internal class VoiceSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("voice_session_v1", Context.MODE_PRIVATE)

    private fun attachmentsAreCorrupt(): Boolean = preferences.getString("session", null) != null &&
        runCatching { AttachmentDraft.decode(preferences.getString("attachments", null)) }.isFailure

    fun hasCorruptAttachmentIndex(): Boolean = synchronized(lock) { attachmentsAreCorrupt() }

    fun read(): VoiceSessionState = synchronized(lock) {
        val sessionId = preferences.getString("session", null)
        if (sessionId == null) return@synchronized VoiceSessionState()
        val phase = runCatching { VoiceCapturePhase.valueOf(preferences.getString("phase", "IDLE")!!) }
            .getOrDefault(VoiceCapturePhase.INTERRUPTED)
        val decoded = runCatching { AttachmentDraft.decode(preferences.getString("attachments", null)) }
        val attachments = decoded.getOrDefault(emptyList())
        val corrupt = decoded.isFailure
        VoiceSessionState(
            sessionId = sessionId,
            phase = phase,
            elapsedMs = preferences.getLong("elapsedMs", 0L).coerceAtLeast(0L),
            attachments = attachments,
            completedSegments = attachments.size,
            message = if (corrupt) "录音草稿索引损坏，原音频仍保留；请先恢复，勿覆盖草稿"
                else preferences.getString("message", null),
            attachmentSaveFailed = corrupt || preferences.getBoolean("attachmentSaveFailed", false),
        )
    }

    fun save(value: VoiceSessionState) = synchronized(lock) {
        requireNotNull(value.sessionId)
        check(!attachmentsAreCorrupt()) { "录音草稿索引损坏；不能覆盖原始内容" }
        check(preferences.edit()
            .putString("session", value.sessionId)
            .putString("phase", value.phase.name)
            .putLong("elapsedMs", value.elapsedMs.coerceAtLeast(0L))
            .putString("attachments", AttachmentDraft.encode(value.attachments))
            .putString("message", value.message)
            .putBoolean("attachmentSaveFailed", value.attachmentSaveFailed)
            .commit()) { "录音状态保存失败，原音频文件仍保留" }
    }

    fun append(sessionId: String, item: ImportedAttachment): VoiceSessionState = synchronized(lock) {
        val current = read()
        check(current.sessionId == sessionId) { "录音会话已改变，原音频仍保留" }
        check(!attachmentsAreCorrupt()) { "录音草稿索引损坏；原音频仍保留" }
        val updated = current.copy(attachments = (current.attachments + item).distinctBy { it.relativePath })
        save(updated)
        updated
    }

    fun clear(sessionId: String) = synchronized(lock) {
        if (preferences.getString("session", null) == sessionId)
            check(preferences.edit().clear().commit()) { "录音草稿暂未清除，保存结果仍保留" }
    }

    companion object { private val lock = Any() }
}
