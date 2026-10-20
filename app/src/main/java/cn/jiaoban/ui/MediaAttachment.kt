package cn.jiaoban.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.withContext

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
