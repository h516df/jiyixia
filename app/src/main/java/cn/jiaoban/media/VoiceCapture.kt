package cn.jiaoban.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VoiceCapturePhase { IDLE, RECORDING, PAUSED, STOPPED, INTERRUPTED, ERROR }
data class VoiceCaptureState(
    val phase: VoiceCapturePhase = VoiceCapturePhase.IDLE,
    val message: String? = null,
    val completedSegments: Int = 0,
    val attachmentSaveFailed: Boolean = false,
)

/** Foreground only. The owner must call interrupt on ON_STOP and close on disposal.
 * Callback success must mean the attachment reference has been saved durably. A thrown callback
 * leaves the original on disk and is retried by recover. No original is ever deleted here.
 */
class VoiceCapture(
    context: Context,
    sessionId: String,
    private val onCompleted: (File) -> Unit,
) : Closeable {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = this.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val journal = RecordingJournal(this.context.filesDir, sessionId)
    private val mutableState = MutableStateFlow(VoiceCaptureState())
    val state: StateFlow<VoiceCaptureState> = mutableState.asStateFlow()
    private var recorder: MediaRecorder? = null
    private var active: File? = null
    private var closed = false
    private val delivery = RecordingDelivery(journal, onCompleted)
    private var deliveryFailed = false
    private var pendingFinalization = false
    private val deliveryWarning = "录音原件已保留，附件保存失败；可重试恢复"
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null

    private val rotate = Runnable {
        if (recorder != null && !closed) {
            if (finishSegment()) beginSegment() else setState(VoiceCapturePhase.ERROR, "录音保存失败，已停止；原件仍保留")
        }
    }
    private val checkInterruption = object : Runnable {
        override fun run() {
            if (closed || recorder == null) return
            if (!hasPermission()) interruptNow("麦克风权限已关闭，录音已中断")
            else if (isCommunicationMode()) interruptNow("通话或其他音频占用，录音已中断；请手动继续")
            else handler.postDelayed(this, 500)
        }
    }

    fun start() = onMain {
        if (recorder == null && !closed) beginSegment()
    }
    fun resume() = start()
    fun pause() = onMain { finishWith(VoiceCapturePhase.PAUSED) }
    fun stop() = onMain { finishWith(VoiceCapturePhase.STOPPED) }
    fun interrupt(reason: String = "录音已中断，请手动继续") = onMain { interruptNow(reason) }
    fun recover() = onMain { if (!closed) deliverFinalized() }
    override fun close() = onMain {
        if (!closed) {
            finishWith(VoiceCapturePhase.STOPPED)
            closed = true
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }
    private fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun isCommunicationMode() = audioManager.mode == AudioManager.MODE_IN_CALL || audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
    private fun setState(phase: VoiceCapturePhase, message: String? = null) {
        mutableState.value = VoiceCaptureState(
            phase,
            listOfNotNull(message, deliveryWarning.takeIf { deliveryFailed }).joinToString("；").ifEmpty { null },
            journal.recoverFinalized().size,
            attachmentSaveFailed = deliveryFailed,
        )
    }
    private fun beginSegment() {
        if (pendingFinalization) { setState(VoiceCapturePhase.ERROR, "请先重试恢复尚未保存的录音"); return }
        if (!hasPermission()) { setState(VoiceCapturePhase.ERROR, "请先授予麦克风权限"); return }
        if (isCommunicationMode()) { setState(VoiceCapturePhase.INTERRUPTED, "通话或其他音频占用，请稍后手动继续"); return }
        var candidate: MediaRecorder? = null
        try {
            val file = journal.newSegment()
            @Suppress("DEPRECATION")
            val next = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            candidate = next
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioEncodingBitRate(128_000)
            next.setAudioSamplingRate(44_100)
            next.setOutputFile(file.absolutePath)
            next.setOnErrorListener { source, _, _ -> onMain {
                if (recorder === source) interruptNow("录音设备出错，已中断；已完成片段仍保留")
            } }
            next.prepare()
            next.start()
            recorder = next
            active = file
            registerSilenceCallback(next)
            setState(VoiceCapturePhase.RECORDING)
            handler.postDelayed(rotate, 30_000)
            handler.post(checkInterruption)
        } catch (_: Exception) {
            runCatching { candidate?.release() }
            unregisterSilenceCallback()
            recorder = null
            active = null
            setState(VoiceCapturePhase.ERROR, "无法启动录音，请检查麦克风权限或设备占用")
        }
    }
    private fun registerSilenceCallback(source: MediaRecorder) {
        if (Build.VERSION.SDK_INT < 29) return
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                onMain {
                    if (recorder === source && runCatching { source.activeRecordingConfiguration?.isClientSilenced == true }.getOrDefault(false)) {
                        interruptNow("麦克风被系统静音或占用，录音已中断；请手动继续")
                    }
                }
            }
        }
        recordingCallback = callback
        source.registerAudioRecordingCallback(context.mainExecutor, callback)
    }
    private fun unregisterSilenceCallback() {
        val callback = recordingCallback
        recordingCallback = null
        if (Build.VERSION.SDK_INT >= 29 && callback != null) runCatching { recorder?.unregisterAudioRecordingCallback(callback) }
    }
    private fun interruptNow(reason: String) {
        if (!closed && recorder != null) finishWith(VoiceCapturePhase.INTERRUPTED, reason)
    }
    private fun finishWith(phase: VoiceCapturePhase, message: String? = null) {
        if (closed) return
        val okay = finishSegment()
        setState(if (okay) phase else VoiceCapturePhase.ERROR,
            if (okay) message else "当前片段未能完成，原件仍保留；先前完成的录音可恢复")
    }
    private fun finishSegment(): Boolean {
        handler.removeCallbacks(rotate)
        handler.removeCallbacks(checkInterruption)
        val current = recorder ?: run {
            deliverFinalized()
            return !pendingFinalization
        }
        val file = active
        unregisterSilenceCallback()
        recorder = null
        active = null
        var stopped = false
        try { current.stop(); stopped = true } catch (_: Exception) { /* Too-short/invalid M4A remains .part. */ }
        finally { runCatching { current.release() } }
        var finalized = false
        if (stopped && file != null) finalized = runCatching { journal.finalizeSegment(file) }.isSuccess
        deliverFinalized()
        return finalized
    }
    private fun deliverFinalized() {
        pendingFinalization = !runCatching { journal.retryPendingFinalization() }.getOrDefault(false)
        var failed = pendingFinalization
        journal.recoverFinalized().forEach { file ->
            try {
                delivery.deliver(file)
            } catch (_: Exception) { failed = true }
        }
        deliveryFailed = failed
        mutableState.value = mutableState.value.copy(
            completedSegments = journal.recoverFinalized().size,
            attachmentSaveFailed = failed,
            message = if (failed) deliveryWarning
                else mutableState.value.message?.removeSuffix("；$deliveryWarning")?.takeUnless { it == deliveryWarning },
        )
    }
}
