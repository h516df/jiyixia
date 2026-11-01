package cn.jiaoban.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.jiaoban.ImportedAttachment
import cn.jiaoban.media.MediaStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Full-screen, offline gallery. Only the visible page decodes a bounded bitmap. */
@Composable
internal fun PhotoViewer(photos:List<ImportedAttachment>,selectedPath:String,onClose:()->Unit) {
    if(photos.isEmpty()) return
    val initial=photos.indexOfFirst { it.relativePath==selectedPath }.coerceAtLeast(0)
    val pager=rememberPagerState(initialPage=initial,pageCount={photos.size})
    FullPage(onClose) {
        Box(Modifier.fillMaxSize().background(Color(0xFF111113))) {
            HorizontalPager(state=pager,modifier=Modifier.fillMaxSize()) {index->
                ZoomablePhoto(photos[index])
            }
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=onClose) {Icon(Icons.Default.Close,"关闭图片",tint=Color.White)}
                Spacer(Modifier.weight(1f))
                Text("${pager.currentPage+1} / ${photos.size}",color=Color.White,style=MaterialTheme.typography.labelMedium,
                    modifier=Modifier.padding(end=12.dp))
            }
        }
    }
}

@Composable
private fun ZoomablePhoto(photo:ImportedAttachment) {
    val context=LocalContext.current
    val bitmap by produceState<Bitmap?>(null,photo.relativePath) {
        value=withContext(Dispatchers.IO) {
            runCatching {
                val file=MediaStorage.file(context.filesDir,photo.relativePath)
                if(!file.isFile)return@runCatching null
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                BitmapFactory.decodeFile(file.path,bounds)
                if(bounds.outWidth<=0 || bounds.outHeight<=0)return@runCatching null
                var sample=1
                while(bounds.outWidth/sample>2048 || bounds.outHeight/sample>2048)sample*=2
                BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply{inSampleSize=sample})
            }.getOrNull()
        }
    }
    var scale by remember(photo.relativePath) {mutableFloatStateOf(1f)}
    var offset by remember(photo.relativePath) {mutableStateOf(Offset.Zero)}
    val transform=rememberTransformableState { zoom,pan,_ ->
        scale=(scale*zoom).coerceIn(1f,4f)
        offset=if(scale==1f)Offset.Zero else offset+pan
    }
    Box(Modifier.fillMaxSize().transformable(transform).pointerInput(scale) {
        if(scale>1f) detectDragGestures {change,drag->change.consume();offset+=drag}
    },contentAlignment=Alignment.Center) {
        if(bitmap!=null) Image(bitmap!!.asImageBitmap(),photo.name,
            Modifier.fillMaxSize().graphicsLayer(scaleX=scale,scaleY=scale,translationX=offset.x,translationY=offset.y),
            contentScale=ContentScale.Fit)
        else Text("图片暂时无法显示",color=Color.White,style=MaterialTheme.typography.bodyMedium)
    }
}
