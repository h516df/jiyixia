package cn.jiaoban.ui

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import cn.jiaoban.ImportedAttachment
import cn.jiaoban.media.MediaStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

internal fun audioTime(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) / 1_000)
    return if (seconds >= 3_600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

internal fun audioDuration(file: File): Long = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } finally { retriever.release() }
}.getOrDefault(0L)

/** Small, bounded images for the timeline; never decode a full camera photo on the UI thread. */
@Composable internal fun TimelineMediaPreviews(attachments:List<ImportedAttachment>) {
    val media=attachments.filter { it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/") }.take(3)
    if(media.isEmpty()) return
    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        media.forEach { entry ->
            val context=LocalContext.current
            val bitmap by produceState<android.graphics.Bitmap?>(null,entry.relativePath) {
                value=withContext(Dispatchers.IO) {
                    runCatching {
                        val file=MediaStorage.file(context.filesDir,entry.relativePath)
                        if(!file.isFile) return@runCatching null
                        if(entry.mimeType.startsWith("image/")) {
                            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                            BitmapFactory.decodeFile(file.path,bounds)
                            var sample=1
                            while(bounds.outWidth/sample>300 || bounds.outHeight/sample>300) sample*=2
                            BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply{inSampleSize=sample})
                        } else {
                            val retriever=MediaMetadataRetriever()
                            try {retriever.setDataSource(file.path);retriever.getFrameAtTime(0L,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)}
                            finally {retriever.release()}
                        }
                    }.getOrNull()
                }
            }
            Box(Modifier.size(88.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment=Alignment.Center) {
                if(bitmap!=null) Image(bitmap!!.asImageBitmap(),entry.name,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                else Text(if(entry.mimeType.startsWith("video/"))"视频" else "照片",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(entry.mimeType.startsWith("video/")) Surface(shape=RoundedCornerShape(50),color=Color.Black.copy(alpha=.5f)) {
                    Text("▶",Modifier.padding(horizontal=10.dp,vertical=5.dp),color=Color.White)
                }
            }
        }
    }
}

@Composable internal fun TimelineAudioSummary(attachments:List<ImportedAttachment>) {
    val context=LocalContext.current
    val durations by produceState(0L,attachments) {
        value=withContext(Dispatchers.IO) { attachments.sumOf {
            runCatching { audioDuration(MediaStorage.file(context.filesDir,it.relativePath)) }.getOrDefault(0L)
        } }
    }
    Text("▶  语音记录  ·  "+if(durations>0)audioTime(durations) else "时长待读取",
        style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
}

/** Multiple protected physical segments are one playback surface, not hundreds of file buttons. */
@Composable
internal fun AudioRecordingGroup(
    attachments: List<ImportedAttachment>,
    playing: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
) {
    if (attachments.isEmpty()) return
    val context = LocalContext.current
    val files = remember(attachments) {
        attachments.mapNotNull { runCatching { MediaStorage.file(context.filesDir, it.relativePath) }.getOrNull() }
    }
    val durations by produceState<List<Long>>(emptyList(), files) {
        value = withContext(Dispatchers.IO) { files.map { file -> if (file.isFile) audioDuration(file) else 0L } }
    }
    val totalDuration = durations.sum()
    val allAvailable = files.size == attachments.size && files.all { it.isFile }
    val playbackKey = remember(attachments) { "audio-${attachments.joinToString(";") { it.sha256 }.hashCode()}" }
    val prefs = remember { context.getSharedPreferences("audio_playback_v1", android.content.Context.MODE_PRIVATE) }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .68f)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MicGlyph(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("语音记录", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(if (totalDuration > 0) audioTime(totalDuration) else "—", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!allAvailable) {
                Text("部分录音暂时无法读取，原记录没有被修改。", color = MaterialTheme.colorScheme.error)
            } else if (!playing) {
                OutlinedButton(onClick = onPlay, modifier = Modifier.fillMaxWidth()) { Text("播放录音") }
            } else {
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                var failed by remember(playbackKey) { mutableStateOf(false) }
                var paused by remember(playbackKey) { mutableStateOf(false) }
                var currentMs by remember(playbackKey) { mutableLongStateOf(0L) }
                val player = remember(files) {
                    ExoPlayer.Builder(context).build().apply {
                        setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
                        setMediaItems(files.map { MediaItem.fromUri(Uri.fromFile(it)) })
                        addListener(object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) { failed = true }
                        })
                        prepare()
                        val index = prefs.getInt("$playbackKey.index", 0).coerceIn(0, files.lastIndex)
                        seekTo(index, prefs.getLong("$playbackKey.position", 0L).coerceAtLeast(0L))
                        playWhenReady = true
                    }
                }
                LaunchedEffect(player, durations) {
                    while (true) {
                        val index = player.currentMediaItemIndex.coerceIn(0, durations.lastIndex.coerceAtLeast(0))
                        currentMs = durations.take(index).sum() + player.currentPosition.coerceAtLeast(0L)
                        paused = !player.isPlaying && player.playbackState != Player.STATE_ENDED
                        if (player.playbackState == Player.STATE_ENDED) { onStop(); break }
                        delay(250L)
                    }
                }
                DisposableEffect(player, lifecycle) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_STOP) { player.pause(); onStop() }
                    }
                    lifecycle.addObserver(observer)
                    onDispose {
                        lifecycle.removeObserver(observer)
                        if (player.playbackState == Player.STATE_ENDED) {
                            prefs.edit().remove("$playbackKey.index").remove("$playbackKey.position").apply()
                        } else {
                            prefs.edit().putInt("$playbackKey.index", player.currentMediaItemIndex)
                                .putLong("$playbackKey.position", player.currentPosition.coerceAtLeast(0L)).apply()
                        }
                        player.release()
                    }
                }
                if (totalDuration > 0L) Slider(
                    value = (currentMs.toFloat() / totalDuration).coerceIn(0f, 1f),
                    onValueChange = { fraction ->
                        var target = (fraction * totalDuration).toLong()
                        for (index in durations.indices) {
                            val duration = durations[index]
                            if (target < duration || index == durations.lastIndex) {
                                player.seekTo(index, target.coerceAtLeast(0L))
                                break
                            }
                            target -= duration
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(audioTime(currentMs) + " / " + if (totalDuration > 0) audioTime(totalDuration) else "—", style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) { Text(if (paused) "继续" else "暂停") }
                    TextButton(onClick = onStop) { Text("关闭播放") }
                }
                if (failed) Text("此设备暂时无法播放这段录音，原文件仍保留。", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Local bounded preview. No URL fetching, automatic playback, or background audio. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable internal fun MediaAttachment(attachment: ImportedAttachment, playing: Boolean, onPlay: () -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    val file = remember(attachment.relativePath) { runCatching { MediaStorage.file(context.filesDir, attachment.relativePath) }.getOrNull() }
    val image = attachment.mimeType.startsWith("image/")
    val playable = attachment.mimeType.startsWith("video/") || attachment.mimeType.startsWith("audio/")
    val bitmap by produceState<android.graphics.Bitmap?>(null, file, image) {
        if (image && file?.isFile == true) value = withContext(Dispatchers.IO) {
            runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) return@runCatching null
                var sample = 1
                while (options.outWidth / sample > 1024 || options.outHeight / sample > 1024) sample *= 2
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text((if (attachment.mimeType.startsWith("audio/")) "录音 · " else if (image) "照片 · " else if (playable) "视频 · " else "附件 · ") + attachment.name,
            style = MaterialTheme.typography.labelMedium)
        if (file?.isFile != true) Text("附件暂时无法读取，原记录未改动", color = MaterialTheme.colorScheme.error)
        else if (image) {
            bitmap?.let { Image(it.asImageBitmap(), "照片预览：${attachment.name}", Modifier.fillMaxWidth().heightIn(max = 240.dp), contentScale = ContentScale.Fit) }
                ?: Text("预览不可用；原文件仍保留", style = MaterialTheme.typography.bodySmall)
        } else if (playable) {
            if (playing) {
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                var error by remember(attachment.relativePath) { mutableStateOf(false) }
                val player = remember(file) { ExoPlayer.Builder(context).build().apply {
                    setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
                    addListener(object : Player.Listener { override fun onPlayerError(e: PlaybackException) { error = true } })
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(file))); prepare(); playWhenReady = true
                } }
                DisposableEffect(player, lifecycle) {
                    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { player.pause(); onStop() } }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer); player.release() }
                }
                AndroidView(factory = { PlayerView(it).apply { this.player = player; controllerShowTimeoutMs = 0; useController = true } },
                    modifier = Modifier.fillMaxWidth().height(if (attachment.mimeType.startsWith("video/")) 220.dp else 90.dp))
                if (error) Text("此设备无法播放该格式；原文件仍保留", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onStop) { Text("关闭播放") }
            } else OutlinedButton(onClick = onPlay) { Text(if (attachment.mimeType.startsWith("audio/")) "播放录音" else "播放视频") }
        }
    }
}
