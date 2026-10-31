package cn.jiaoban.media

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import cn.jiaoban.MainActivity

/**
 * Only a visible Activity may initiate a fresh microphone service. Once started, its persistent
 * user-visible notification supports pausing/resuming/stopping even with the screen locked.
 * No START_STICKY or boot receiver: an OS kill never silently reopens the microphone.
 */
class VoiceRecordingService : Service() {
    companion object {
        const val ACTION_START = "cn.jiaoban.voice.START"
        const val ACTION_RESUME = "cn.jiaoban.voice.RESUME"
        const val ACTION_PAUSE = "cn.jiaoban.voice.PAUSE"
        const val ACTION_STOP = "cn.jiaoban.voice.STOP"
        const val EXTRA_SESSION = "recording_session"
        private const val CHANNEL_ID = "jiyixia_voice_recording"
        private const val NOTIFICATION_ID = 2401
        @Volatile internal var running = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: VoiceSessionStore
    private var capture: VoiceCapture? = null
    private var sessionId: String? = null
    private var accruedMs: Long = 0L
    private var startedElapsedMs: Long? = null
    private var lastDurableTick = 0L
    private var finished = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val heartbeat = object : Runnable {
        override fun run() {
            if (finished) return
            syncState(durable = SystemClock.elapsedRealtime() - lastDurableTick >= 5_000)
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = VoiceSessionStore(applicationContext)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "正在录音", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "查看录音状态并暂停或结束录音"; setShowBadge(false) })
        running = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent.getStringExtra(EXTRA_SESSION))
            ACTION_RESUME -> resumeSession()
            ACTION_PAUSE -> pauseSession()
            ACTION_STOP -> stopSession()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startSession(requestedSession: String?) {
        if (capture != null) return
        val id = requestedSession?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
            ?: java.util.UUID.randomUUID().toString()
        val old = store.read()
        if (old.sessionId != null && old.sessionId != id &&
            (old.attachments.isNotEmpty() || old.attachmentSaveFailed)) {
            VoiceSessionController.publish(old.copy(message = "请先保存上一条录音"))
            stopSelf(); return
        }
        val prepared = if (old.sessionId == id) old else VoiceSessionState(sessionId = id)
        // The journal must exist before MediaRecorder can finalize its first segment. Otherwise
        // callback delivery cannot append, and a process death would forget the session ID.
        if (old.sessionId != id) {
            try { store.save(prepared) }
            catch (_: Exception) {
                VoiceSessionController.publish(old.copy(phase = VoiceCapturePhase.ERROR,
                    message = "录音草稿无法持久保存，未启动麦克风；原资料仍保留"))
                stopSelf(); return
            }
        }
        begin(prepared)
    }

    private fun resumeSession() {
        if (capture != null && capture?.state?.value?.phase == VoiceCapturePhase.RECORDING) return
        if (capture != null) {
            capture?.resume()
            if (capture?.state?.value?.phase == VoiceCapturePhase.RECORDING)
                startedElapsedMs = SystemClock.elapsedRealtime()
            syncState(durable = true)
            return
        }
        val previous = store.read()
        if (previous.sessionId == null) { stopSelf(); return }
        begin(previous)
    }

    private fun begin(previous: VoiceSessionState) {
        val id = requireNotNull(previous.sessionId)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val denied = previous.copy(phase = VoiceCapturePhase.ERROR, message = "没有麦克风权限，录音未开始")
            persist(denied); stopSelf(); return
        }
        sessionId = id
        accruedMs = previous.elapsedMs
        try {
            // Required by Android 14+ while-in-use microphone/FGS permissions. Called only from
            // a visible Activity start, or a user-driven action on the existing notification.
            startMicrophoneForeground(previous.phase)
            capture = VoiceCapture(applicationContext, id) { file ->
                VoiceSessionController.publishCompleted(applicationContext, id, file)
            }.also { it.recover(); it.start() }
            val phase = capture?.state?.value?.phase ?: VoiceCapturePhase.ERROR
            if (phase == VoiceCapturePhase.RECORDING) startedElapsedMs = SystemClock.elapsedRealtime()
            syncState(durable = true)
            handler.removeCallbacks(heartbeat)
            handler.postDelayed(heartbeat, 1_000)
        } catch (_: Exception) {
            runCatching { capture?.close() }
            capture = null
            val failure = store.read().copy(phase = VoiceCapturePhase.ERROR,
                message = "系统无法启动录音，请检查麦克风权限或设备占用")
            persist(failure)
            stopSelf()
        }
    }

    private fun pauseSession() {
        if (capture == null) { stopSelf(); return }
        accumulateElapsed()
        capture?.pause()
        syncState(durable = true)
    }

    private fun stopSession() {
        accumulateElapsed()
        val beforeStop = capture?.state?.value?.phase
        var recoveredSegmentsFailed = false
        if (capture == null) {
            // A notification action may recreate this non-sticky service after process death.
            // No recorder can be stopped then, but finalized segments can still be imported.
            val unfinished = store.read()
            unfinished.sessionId?.let { id ->
                sessionId = id
                accruedMs = unfinished.elapsedMs
                val recovery = runCatching {
                    VoiceCapture(applicationContext, id) { file ->
                        VoiceSessionController.publishCompleted(applicationContext, id, file)
                    }.also { it.recover(); it.close() }
                }
                recoveredSegmentsFailed = recovery.isFailure ||
                    recovery.getOrNull()?.state?.value?.attachmentSaveFailed == true
            }
        } else capture?.stop()
        val phase = capture?.state?.value?.phase
        val current = store.read()
        if (current.sessionId == null) {
            finished = true
            handler.removeCallbacks(heartbeat)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val finishState = current.copy(phase = if (beforeStop == VoiceCapturePhase.ERROR ||
            phase == VoiceCapturePhase.ERROR || current.phase == VoiceCapturePhase.ERROR) VoiceCapturePhase.ERROR
            else VoiceCapturePhase.STOPPED,
            elapsedMs = accruedMs.coerceAtLeast(current.elapsedMs),
            attachmentSaveFailed = recoveredSegmentsFailed || current.attachmentSaveFailed ||
                capture?.state?.value?.attachmentSaveFailed == true,
            message = if (recoveredSegmentsFailed) "录音原件仍保留，部分片段恢复失败；请稍后重试"
                else capture?.state?.value?.message ?: current.message)
        persist(finishState)
        finished = true
        handler.removeCallbacks(heartbeat)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun accumulateElapsed() {
        startedElapsedMs?.let { accruedMs += (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) }
        startedElapsedMs = null
    }

    private fun syncState(durable: Boolean) {
        val id = sessionId ?: return
        val voiceState = capture?.state?.value ?: return
        val now = SystemClock.elapsedRealtime()
        val actualPhase = voiceState.phase
        if (actualPhase == VoiceCapturePhase.RECORDING) holdCpuDuringRecording()
        else releaseCpuAfterRecording()
        if (actualPhase != VoiceCapturePhase.RECORDING) accumulateElapsed()
        else if (startedElapsedMs == null) startedElapsedMs = now
        val elapsed = accruedMs + (startedElapsedMs?.let { (now - it).coerceAtLeast(0L) } ?: 0L)
        val base = store.read()
        if (base.sessionId != id) return
        val current = base.copy(phase = actualPhase, elapsedMs = elapsed,
            // A successful recorder replay can clear a previous transient import failure;
            // a malformed persisted index must remain quarantined instead.
            attachmentSaveFailed = voiceState.attachmentSaveFailed || store.hasCorruptAttachmentIndex(),
            message = voiceState.message ?: base.message)
        if (durable || base.phase != actualPhase || base.attachments.size != current.attachments.size) {
            persist(current)
            lastDurableTick = now
        } else VoiceSessionController.publish(current)
        if (actualPhase != VoiceCapturePhase.RECORDING) updateNotification(current)
        else if (durable) updateNotification(current)
    }

    private fun persist(value: VoiceSessionState) {
        try {
            store.save(value)
            VoiceSessionController.publish(value)
        } catch (_: Exception) {
            VoiceSessionController.publish(value.copy(attachmentSaveFailed = true,
                message = "录音状态保存失败，音频原件仍保留；请停止后恢复"))
        }
    }

    /** The UI can be locked for hours; release immediately on pause, call or stop. */
    private fun holdCpuDuringRecording() {
        try {
            val lock = wakeLock ?: getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jiyixia:VoiceRecording")
                .apply { setReferenceCounted(false); wakeLock = this }
            if (!lock.isHeld) lock.acquire()
        } catch (_: SecurityException) { /* Native recorder may still keep the device awake. */ }
    }

    private fun releaseCpuAfterRecording() {
        wakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
    }

    private fun startMicrophoneForeground(phase: VoiceCapturePhase) {
        val notification = notification(VoiceSessionState(sessionId = sessionId, phase = phase))
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(current: VoiceSessionState) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(current))
    }

    private fun notification(state: VoiceSessionState): Notification {
        val recording = state.phase == VoiceCapturePhase.RECORDING
        val millis = state.elapsedMs.coerceAtLeast(0L) / 1_000
        val duration = "%02d:%02d:%02d".format(millis / 3_600, (millis / 60) % 60, millis % 60)
        val launch = PendingIntent.getActivity(this, 19,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val action = if (recording) ACTION_PAUSE else ACTION_RESUME
        val actionLabel = if (recording) "暂停" else "继续"
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (recording) "记一下 · 正在录音" else "记一下 · 录音已暂停")
            .setContentText("$duration · 结束后保留已完成录音")
            .setContentIntent(launch)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(
                if (recording) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                actionLabel, serviceAction(action, 20)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_save,
                "结束", serviceAction(ACTION_STOP, 21)).build())
            .build()
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(this, requestCode, Intent(this, VoiceRecordingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        releaseCpuAfterRecording()
        if (!finished) {
            accumulateElapsed()
            runCatching { capture?.close() }
            val previous = store.read()
            if (sessionId != null && previous.sessionId == sessionId) {
                val interrupted = previous.copy(
                    phase = if (previous.phase == VoiceCapturePhase.ERROR) VoiceCapturePhase.ERROR
                        else VoiceCapturePhase.INTERRUPTED,
                    elapsedMs = accruedMs.coerceAtLeast(previous.elapsedMs),
                    message = if (previous.phase == VoiceCapturePhase.ERROR) previous.message
                        else "录音已中断；已完成片段仍可恢复",
                    attachmentSaveFailed = previous.attachmentSaveFailed || capture?.state?.value?.attachmentSaveFailed == true)
                persist(interrupted)
            }
        }
        capture = null
        running = false
        super.onDestroy()
    }
}
