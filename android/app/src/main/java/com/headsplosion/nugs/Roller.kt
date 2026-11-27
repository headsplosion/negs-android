package com.headsplosion.nugs

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** A film box's colours: the body, its band and the lettering on the body. */
private class Pack(val body: Long, val band: Long, val ink: Long = 0xFF111111)

private const val YELLOW = 0xFFFFC20E
private const val RED = 0xFFD7141A
private const val BLACK = 0xFF141414
private const val WHITE = 0xFFF2F2EE
private const val GREEN = 0xFF00875A

/** Colours after each film's packaging; the more specific ids come first. */
private val packs = listOf(
    "kodak_portra_160" to Pack(YELLOW, 0xFFB7A6D6),
    "kodak_portra_400" to Pack(YELLOW, 0xFF7B4FA3),
    "kodak_portra_800" to Pack(YELLOW, 0xFF4A2370),
    "kodak_gold" to Pack(YELLOW, 0xFFB8860B),
    "kodak_ultramax" to Pack(YELLOW, 0xFF1F4FA0),
    "kodak_kodacolor" to Pack(YELLOW, RED),
    "kodak_ektar" to Pack(YELLOW, RED),
    "kodak_trix" to Pack(YELLOW, 0xFF007A53),
    "kodak_tmax" to Pack(YELLOW, 0xFF1C8C9E),
    "kodak_doublex" to Pack(BLACK, YELLOW, WHITE),
    "kodak_vision3" to Pack(BLACK, YELLOW, WHITE),
    "kodak_verita" to Pack(BLACK, 0xFFE8641B, WHITE),
    "kodak_ektachrome" to Pack(YELLOW, 0xFF0066B3),
    "kodak_kodachrome" to Pack(YELLOW, RED),
    "kodak_aero" to Pack(0xFFB9BCC0, RED),
    "kodak" to Pack(YELLOW, RED),
    "fujifilm_velvia" to Pack(0xFF3B1F5E, GREEN, WHITE),
    "fujifilm_provia" to Pack(0xFF1D3F8F, GREEN, WHITE),
    "fujifilm_astia" to Pack(0xFF9A2B5C, GREEN, WHITE),
    "fujifilm_pro" to Pack(WHITE, 0xFF5B3A8C),
    "fujifilm_acros" to Pack(BLACK, 0xFFB01E28, WHITE),
    "fujifilm_superia" to Pack(GREEN, 0xFF1E5AA8, WHITE),
    "fujifilm_xtra" to Pack(GREEN, 0xFF6A3D9A, WHITE),
    "fujifilm" to Pack(GREEN, WHITE, WHITE),
    "ilford_delta" to Pack(BLACK, WHITE, WHITE),
    "ilford_fp4" to Pack(WHITE, 0xFF2E7D4F),
    "ilford_hp5" to Pack(WHITE, 0xFF2F9E6E),
    "ilford" to Pack(WHITE, BLACK),
    "cinestill_800t" to Pack(BLACK, RED, WHITE),
    "cinestill" to Pack(BLACK, 0xFF2A9DD6, WHITE),
    "harman_phoenix" to Pack(0xFF1A1030, 0xFFFF6A1F, WHITE),
    "agfa" to Pack(WHITE, 0xFFE3001B),
    "lomography_lomochrome_turquoise" to Pack(0xFF13A8A8, BLACK),
    "lomography_lomochrome" to Pack(0xFF6A2C91, 0xFFE94E9C, WHITE),
    "lomography" to Pack(WHITE, 0xFFE94E1B),
)

private val makers = listOf("Kodak", "Fujifilm", "Ilford", "CineStill", "Harman", "Agfa", "Lomography", "Fotufilm")

private val speed = Regex("\\d{2,4}[DT]?")

/** The cartridge's radius, label height and how far its round edges dip. */
private val radius = 74.dp
private val tall = 150.dp
private val dip = 11.dp

/**
 * One 35mm cartridge that turns on its axis. Half a turn brings the next
 * film's label to the front, and the film it comes to rest on is chosen.
 */
@Composable
fun FilmRoller(films: List<Profile>, selected: String, choose: (String) -> Unit) {
    val last = (films.size - 1).toFloat()
    // Which film faces front, as a fractional index.
    val turn = remember {
        Animatable(films.indexOfFirst { it.id == selected }.coerceAtLeast(0).toFloat()).apply { updateBounds(0f, last) }
    }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val chosen by rememberUpdatedState(selected)
    val send by rememberUpdatedState(choose)
    val facing by remember { derivedStateOf { turn.value.roundToInt().coerceIn(0, films.size - 1) } }

    suspend fun rest(at: Int, velocity: Float = 0f) {
        val target = at.coerceIn(0, films.size - 1)
        turn.animateTo(target.toFloat(), spring(dampingRatio = 0.9f, stiffness = 90f), velocity)
        if (films[target].id != chosen) send(films[target].id)
    }

    // A preset or the search list changed the film: turn to it.
    LaunchedEffect(selected) {
        val at = films.indexOfFirst { it.id == selected }
        if (at >= 0 && at != facing) turn.animateTo(at.toFloat(), spring(stiffness = 90f))
    }
    // A click for each label that comes round.
    LaunchedEffect(Unit) {
        snapshotFlow { facing }.drop(1).collect { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }

    val density = LocalDensity.current
    // How far a finger travels for one film.
    val travel = with(density) { radius.toPx() } * 1.9f
    val labels = remember {
        val w = with(density) { (radius.toPx() * PI.toFloat()).roundToInt() }
        val h = with(density) { tall.roundToPx() }
        object : LruCache<String, Bitmap>(6) {
            override fun create(id: String) = label(films.first { it.id == id }, w, h)
        }
    }

    Canvas(
        Modifier.fillMaxWidth().height(230.dp).clipToBounds()
            .draggable(
                rememberDraggableState { dx -> scope.launch { turn.snapTo(turn.value - dx / travel) } },
                Orientation.Horizontal,
                onDragStopped = { v ->
                    val velocity = -v / travel
                    // A flick spins on for a while before settling on a label.
                    val end = exponentialDecay<Float>(frictionMultiplier = 0.8f).calculateTargetValue(turn.value, velocity)
                    scope.launch { rest(end.roundToInt(), velocity) }
                },
            )
            // Tap either side to turn one film.
            .pointerInput(Unit) {
                detectTapGestures { at -> scope.launch { rest(facing + if (at.x > size.width / 2) 1 else -1) } }
            },
    ) {
        cartridge(turn.value, films, labels)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton({ scope.launch { rest(facing - 1) } }, enabled = facing > 0) { Text("‹") }
        Text(
            films[facing].name, Modifier.weight(1f), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
        )
        TextButton({ scope.launch { rest(facing + 1) } }, enabled = facing < films.size - 1) { Text("›") }
    }
}

private fun pack(film: Profile) = packs.firstOrNull { film.id.startsWith(it.first) }?.second ?: Pack(0xFF6B6B6B, BLACK, WHITE)

/** A film's label, flat: half the way round the cartridge. */
private fun label(film: Profile, w: Int, h: Int): Bitmap {
    val pack = pack(film)
    val maker = makers.firstOrNull { film.name.startsWith(it, ignoreCase = true) }
    val words = film.name.removePrefix(maker.orEmpty()).trim().split(" ")
    val iso = words.lastOrNull { speed.matches(it) }
    val title = words.filter { it != iso }.joinToString(" ")
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawColor(pack.body.toInt())
    paint.color = pack.band.toInt()
    canvas.drawRect(0f, h * 0.56f, w.toFloat(), h * 0.88f, paint)
    canvas.drawRect(0f, h * 0.05f, w.toFloat(), h * 0.08f, paint)

    /** Centred text, shrunk to stay on the part facing front. */
    fun write(text: String, y: Float, size: Float, color: Int, spacing: Float = 0f) {
        paint.color = color
        paint.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        paint.letterSpacing = spacing
        paint.textSize = h * size
        val room = w * 0.62f
        if (paint.measureText(text) > room) paint.textSize *= room / paint.measureText(text)
        canvas.drawText(text, w / 2f, h * y, paint)
    }
    val ink = pack.ink.toInt()
    val onBand = if (pack.band == WHITE || pack.band == YELLOW) BLACK.toInt() else Color.White.toArgb()
    write(maker.orEmpty().uppercase(), 0.22f, 0.11f, ink, 0.08f)
    // Long names go over two lines.
    val parts = title.split(" ")
    if (title.length > 11 && parts.size > 1) {
        val half = (parts.size + 1) / 2
        write(parts.take(half).joinToString(" "), 0.37f, 0.13f, ink)
        write(parts.drop(half).joinToString(" "), 0.50f, 0.13f, ink)
    } else write(title, 0.45f, 0.17f, ink)
    write(iso ?: "35mm", if (iso != null) 0.815f else 0.775f, if (iso != null) 0.26f else 0.15f, onBand)
    write("35mm", 0.955f, 0.06f, ink, 0.2f)
    return bitmap
}

private val metal = listOf(Color(0xFF111111), Color(0xFF8E8E8E), Color(0xFF3A3A3A), Color(0xFF101010))

private fun DrawScope.cartridge(turn: Float, films: List<Profile>, labels: LruCache<String, Bitmap>) {
    val r = radius.toPx()
    val h = tall.toPx()
    val e = dip.toPx()
    val cx = size.width / 2f
    val top = (size.height - h) / 2f

    /** A band of the cylinder from `y`, `height` tall, with round edges. */
    fun band(y: Float, height: Float, wide: Float) = Path().apply {
        moveTo(cx - wide, y)
        cubicTo(cx - wide, y + e * 1.33f, cx + wide, y + e * 1.33f, cx + wide, y)
        lineTo(cx + wide, y + height)
        cubicTo(cx + wide, y + height + e * 1.33f, cx - wide, y + height + e * 1.33f, cx - wide, y + height)
        close()
    }

    // The leader coming out of the side runs as the cartridge turns.
    val film = Size(size.width - cx - r * 0.8f, h * 0.62f)
    val at = Offset(cx + r * 0.8f, top + h * 0.2f)
    drawRect(Color(0xFF4A2C1A), at, film)
    val pitch = 20.dp.toPx()
    var x = at.x - (turn * PI.toFloat() * r) % pitch
    while (x < size.width) {
        for (y in listOf(at.y + 6.dp.toPx(), at.y + film.height - 16.dp.toPx())) {
            drawRoundRect(Color(0xFF120C08), Offset(x, y), Size(9.dp.toPx(), 10.dp.toPx()), CornerRadius(2.dp.toPx()))
        }
        x += pitch
    }
    // The felt lip it comes through.
    drawRoundRect(Color(0xFF0C0C0C), Offset(cx + r - 4.dp.toPx(), top + h * 0.14f), Size(14.dp.toPx(), h * 0.76f), CornerRadius(4.dp.toPx()))

    // The label, wrapped round the cylinder in thin upright slices.
    drawIntoCanvas { canvas ->
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val slices = 120
        val from = Rect()
        val to = RectF()
        for (i in 0 until slices) {
            val a0 = asin(-1f + 2f * i / slices)
            val a1 = asin(-1f + 2f * (i + 1) / slices)
            val middle = (a0 + a1) / 2f
            val u = (turn + middle / PI.toFloat() + 0.5f).coerceIn(0f, films.size - 0.001f)
            val index = u.toInt()
            val bitmap = labels.get(films[index].id)
            val span = ((a1 - a0) / PI.toFloat() * bitmap.width).toInt().coerceAtLeast(1)
            val left = ((u - index) * bitmap.width - span / 2f).toInt().coerceIn(0, bitmap.width - span)
            from.set(left, 0, left + span, bitmap.height)
            val y = top + e * cos(middle)
            to.set(cx + r * sin(a0), y, cx + r * sin(a1) + 1f, y + h)
            canvas.nativeCanvas.drawBitmap(bitmap, from, to, paint)
        }
    }
    // Light from the upper left.
    drawPath(
        band(top, h, r),
        Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = 0.72f), 0.18f to Color.Black.copy(alpha = 0.15f),
            0.31f to Color.White.copy(alpha = 0.28f), 0.45f to Color.Transparent,
            0.8f to Color.Black.copy(alpha = 0.25f), 1f to Color.Black.copy(alpha = 0.75f),
            startX = cx - r, endX = cx + r,
        ),
    )

    // The metal ends, a little wider than the label, with ribs that turn.
    val cap = 17.dp.toPx()
    val wide = r + 3.dp.toPx()
    val shine = Brush.horizontalGradient(metal, startX = cx - wide, endX = cx + wide)
    for (y in listOf(top - cap, top + h)) {
        drawPath(band(y, cap, wide), shine)
        for (n in 0 until 36) {
            val a = ((n / 36f * 2f - turn) * PI.toFloat()).mod(2f * PI.toFloat())
            if (cos(a) <= 0.05f) continue
            val rx = cx + wide * sin(a)
            val ry = y + e * cos(a)
            drawLine(Color.Black.copy(alpha = 0.45f), Offset(rx, ry + 3f), Offset(rx, ry + cap - 3f), 1.5.dp.toPx() * cos(a))
        }
    }
    // The top face and the spool through it.
    val lid = top - cap
    drawOval(Color(0xFF5C5C5C), Offset(cx - wide, lid - e), Size(wide * 2, e * 2))
    drawOval(Color(0xFF2A2A2A), Offset(cx - wide * 0.82f, lid - e * 0.82f), Size(wide * 1.64f, e * 1.64f))
    val knob = 17.dp.toPx()
    val stem = 15.dp.toPx()
    drawRect(
        Brush.horizontalGradient(metal, startX = cx - knob, endX = cx + knob),
        Offset(cx - knob, lid - stem), Size(knob * 2, stem),
    )
    drawOval(Color(0xFF6A6A6A), Offset(cx - knob, lid - stem - e * 0.35f), Size(knob * 2, e * 0.7f))
    // The spool's slot goes round with it.
    val a = turn * PI.toFloat()
    val slot = Offset(knob * 0.75f * cos(a), e * 0.25f * sin(a))
    val hub = Offset(cx, lid - stem)
    drawLine(Color(0xFF161616), hub - slot, hub + slot, 3.dp.toPx())
}
