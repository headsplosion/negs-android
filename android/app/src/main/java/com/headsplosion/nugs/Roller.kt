package com.headsplosion.nugs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs

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

private val cartridge = 104.dp

/**
 * The films as cartridges to spin past, on a strip of film that runs with
 * them. The one that comes to rest in the middle is chosen.
 */
@Composable
fun FilmRoller(films: List<Profile>, selected: String, choose: (String) -> Unit) {
    val start = films.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    val state = rememberPagerState(start) { films.size }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val chosen by rememberUpdatedState(selected)
    val send by rememberUpdatedState(choose)

    // A preset or the search list changed the film: turn to it.
    LaunchedEffect(selected) {
        val at = films.indexOfFirst { it.id == selected }
        if (at >= 0 && at != state.settledPage) state.animateScrollToPage(at)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.settledPage }.drop(1).collect { if (films[it].id != chosen) send(films[it].id) }
    }
    // A click for each cartridge that passes.
    LaunchedEffect(state) {
        snapshotFlow { state.currentPage }.drop(1).collect { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }

    BoxWithConstraints(Modifier.fillMaxWidth().height(190.dp)) {
        val width = with(LocalDensity.current) { cartridge.toPx() }
        Strip(Modifier.fillMaxWidth().height(96.dp).align(Alignment.Center)) {
            (state.currentPage + state.currentPageOffsetFraction) * width
        }
        HorizontalPager(
            state,
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = ((maxWidth - cartridge) / 2).coerceAtLeast(0.dp)),
            pageSize = PageSize.Fixed(cartridge),
            // A hard flick spins past many.
            flingBehavior = PagerDefaults.flingBehavior(state, pagerSnapDistance = PagerSnapDistance.atMost(24)),
        ) { page ->
            // How far from the middle, in cartridges; read while drawing.
            val away = { (page - state.currentPage) - state.currentPageOffsetFraction }
            Cartridge(
                films[page], away,
                Modifier.fillMaxSize().clickable { scope.launch { state.animateScrollToPage(page) } }.graphicsLayer {
                    val d = away().coerceIn(-2f, 2f)
                    val size = 1f - 0.17f * abs(d).coerceAtMost(1.6f)
                    scaleX = size
                    scaleY = size
                    rotationY = -22f * d
                    alpha = 1f - 0.25f * abs(d)
                },
            )
        }
    }
    Text(
        films.getOrNull(state.currentPage)?.name.orEmpty(),
        Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium,
    )
}

/** Film running behind the cartridges, with its perforations. */
@Composable
private fun Strip(modifier: Modifier, offset: () -> Float) {
    Canvas(modifier) {
        drawRect(Color(0xFF3A2417))
        val pitch = 22.dp.toPx()
        val hole = Size(10.dp.toPx(), 12.dp.toPx())
        var x = -(offset() % pitch) - pitch
        while (x < size.width) {
            for (y in listOf(5.dp.toPx(), size.height - 5.dp.toPx() - hole.height)) {
                drawRoundRect(Color(0xFF0E0A08), Offset(x, y), hole, CornerRadius(3.dp.toPx()))
            }
            x += pitch
        }
    }
}

/** A 35mm cartridge whose label turns as it moves. */
@Composable
private fun Cartridge(film: Profile, away: () -> Float, modifier: Modifier) {
    val pack = packs.firstOrNull { film.id.startsWith(it.first) }?.second ?: Pack(0xFF6B6B6B, BLACK, WHITE)
    val ink = Color(pack.ink)
    val maker = makers.firstOrNull { film.name.startsWith(it, ignoreCase = true) }
    val words = film.name.removePrefix(maker.orEmpty()).trim().split(" ")
    val iso = words.lastOrNull { speed.matches(it) }
    val title = words.filter { it != iso }.joinToString(" ")
    val metal = Brush.horizontalGradient(listOf(Color(0xFF3C3C3C), Color(0xFFB8B8B8), Color(0xFF4A4A4A)))
    Column(modifier.padding(horizontal = 8.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(26.dp, 12.dp).background(metal, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
        Box(Modifier.fillMaxWidth().height(10.dp).background(metal, RoundedCornerShape(3.dp)))
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(2.dp)).background(Color(pack.body))) {
            Column(
                Modifier.fillMaxSize().graphicsLayer { translationX = away() * size.width * 0.45f },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    maker.orEmpty().uppercase(), Modifier.padding(top = 6.dp),
                    color = ink, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                )
                Text(
                    title, Modifier.padding(horizontal = 3.dp), color = ink, fontSize = 12.sp, lineHeight = 13.sp,
                    fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 3,
                )
                Box(Modifier.fillMaxWidth().height(40.dp).background(Color(pack.band)), contentAlignment = Alignment.Center) {
                    val onBand = if (pack.band == WHITE || pack.band == YELLOW) Color(BLACK) else Color.White
                    Text(iso ?: "35mm", color = onBand, fontSize = if (iso != null) 22.sp else 13.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(6.dp))
            }
            // The shine and shade of a cylinder, over the moving label.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.55f), 0.3f to Color.White.copy(alpha = 0.18f),
                        0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f),
                    ),
                ),
            )
        }
        Box(Modifier.fillMaxWidth().height(10.dp).background(metal, RoundedCornerShape(3.dp)))
    }
}
