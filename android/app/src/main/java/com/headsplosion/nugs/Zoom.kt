package com.headsplosion.nugs

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/** How far into the preview you are; `pan` is in screen pixels. */
data class View(val zoom: Float = 1f, val pan: Offset = Offset.Zero)

/** `view` zoomed to `zoom` about `at` (from the box's centre), never leaving the picture. */
private fun View.to(zoom: Float, at: Offset, drag: Offset, box: IntSize, picture: Bitmap): View {
    val z = zoom.coerceIn(1f, 10f)
    val moved = (pan - at) * (z / this.zoom) + at + drag
    val fit = min(box.width.toFloat() / picture.width, box.height.toFloat() / picture.height)
    val x = max(0f, (picture.width * fit * z - box.width) / 2f)
    val y = max(0f, (picture.height * fit * z - box.height) / 2f)
    return View(z, Offset(moved.x.coerceIn(-x, x), moved.y.coerceIn(-y, y)))
}

/** The developed photo: pinch to zoom, drag to look around, double tap to jump in and out. */
@Composable
fun ZoomImage(picture: Bitmap, view: View, change: (View) -> Unit) {
    // The preview is replaced as settings change; the gestures read the newest.
    val image by rememberUpdatedState(picture)
    val current by rememberUpdatedState(view)
    val send by rememberUpdatedState(change)
    Box(
        Modifier.fillMaxSize().clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { centroid, drag, zoom, _ ->
                    val at = centroid - Offset(size.width / 2f, size.height / 2f)
                    send(current.to(current.zoom * zoom, at, drag, size, image))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    val at = tap - Offset(size.width / 2f, size.height / 2f)
                    send(if (current.zoom > 1.2f) View() else current.to(4f, at, Offset.Zero, size, image))
                })
            },
    ) {
        Image(
            picture.asImageBitmap(), null,
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = view.zoom
                scaleY = view.zoom
                translationX = view.pan.x
                translationY = view.pan.y
            },
        )
        if (view.zoom > 1.01f) Surface(
            Modifier.align(Alignment.TopEnd),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        ) { Text("%.1f×".format(view.zoom), Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) }
    }
}
