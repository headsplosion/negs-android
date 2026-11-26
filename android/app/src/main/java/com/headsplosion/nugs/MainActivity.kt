package com.headsplosion.nugs

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private data class Stock(val name: String, val film: String, val paper: String)

private val stocks = listOf(
    Stock("Portra 400", "kodak_portra_400", "kodak_portra_endura"),
    Stock("Gold 200", "kodak_gold_200", "kodak_portra_endura"),
    Stock("CineStill 800T", "cinestill_800t", "kodak_portra_endura"),
    Stock("HP5 Plus", "ilford_hp5_plus", "ilford_mgrc_deluxe_grade2"),
)

class MainActivity : ComponentActivity() {
    private var shared by mutableStateOf<Uri?>(null)

    @Suppress("DEPRECATION")
    private fun take(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND) shared = intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        take(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFD9482F), onPrimary = Color.White)) {
                Surface(Modifier.fillMaxSize()) { Screen(shared) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComponentActivity.Screen(shared: Uri?) {
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<File?>(null) }
    var result by remember { mutableStateOf<File?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var stock by remember { mutableStateOf(stocks[0]) }
    var large by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var crop by remember { mutableStateOf(Crop()) }
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var grain by remember { mutableFloatStateOf(1f) }
    var message by remember { mutableStateOf("Choose a photo to start.") }

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() } catch (e: Throwable) { message = "Failed: ${e.message}" }
            busy = false
        }
    }

    fun open(uri: Uri) = run {
        message = "Opening…"
        val file = withContext(Dispatchers.IO) { importPhoto(uri) }
        source = file
        result = null
        original = withContext(Dispatchers.IO) { load(file) }
        crop = Crop()
        message = "Frame it, pick a film, then Develop."
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(::open) }
    LaunchedEffect(shared) { shared?.let(::open) }

    fun adjust(new: Crop) {
        if (busy) return
        crop = new
        result = null
    }

    Column(Modifier.safeDrawingPadding().padding(horizontal = 16.dp)) {
    Box(Modifier.fillMaxWidth().fillMaxHeight(0.48f), contentAlignment = Alignment.Center) {
        val developed = preview.takeIf { result != null }
        if (developed != null) Image(developed.asImageBitmap(), null, Modifier.fillMaxSize())
        else original?.let { CropEditor(it, crop, ::adjust) }
    }
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("NUGS", style = MaterialTheme.typography.titleLarge)
        OutlinedButton(
            { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            Modifier.fillMaxWidth(), enabled = !busy,
        ) { Text("Choose photo") }
        if (original != null) {
            if (result != null) TextButton({ result = null }, enabled = !busy) { Text("Adjust crop") }
            else Text("Drag to move, pinch to zoom, twist to rotate.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ratios.forEachIndexed { i, r ->
                    FilterChip(crop.ratio == i, { adjust(crop.copy(ratio = i, x = 0f, y = 0f)) }, { Text(r.first) }, enabled = !busy)
                }
            }
            Text("Rotate ${"%.1f".format(crop.angle)}°")
            Slider(crop.angle, { adjust(crop.copy(angle = it)) }, valueRange = -180f..180f, enabled = !busy)
            Text("Zoom ${"%.2f".format(crop.zoom)}×")
            Slider(crop.zoom, { adjust(crop.copy(zoom = it)) }, valueRange = 1f..6f, enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ adjust(crop.copy(angle = wrap(crop.angle + 90f))) }, enabled = !busy) { Text("Rotate 90°") }
                OutlinedButton({ adjust(Crop()) }, enabled = !busy) { Text("Reset crop") }
            }
        }
        Text("Grain ${"%.1f".format(grain)}× (1.0 is the film's own)")
        Slider(grain, { grain = it; result = null }, valueRange = 0f..3f, enabled = !busy)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            stocks.forEach { FilterChip(stock == it, { stock = it; result = null }, { Text(it.name) }, enabled = !busy) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!large, { large = false; result = null }, { Text("2048 px") }, enabled = !busy)
            FilterChip(large, { large = true; result = null }, { Text("4096 px") }, enabled = !busy)
        }
        Button({
            run {
                val input = source ?: return@run
                message = "Developing…"
                val output = File(cacheDir, "developed.jpg")
                val reply = withContext(Dispatchers.Default) {
                    Engine.develop(
                        filmData(this@Screen).path, cacheDir.path, input.path, output.path,
                        stock.film, stock.paper, if (large) 4096 else 2048,
                        ratios[crop.ratio].second, crop.zoom, crop.angle, crop.x, crop.y, grain,
                    )
                }
                if (reply.startsWith("OK ")) {
                    result = output
                    preview = withContext(Dispatchers.IO) { load(output) }
                    message = reply.removePrefix("OK ")
                } else message = reply.removePrefix("ERR ")
            }
        }, Modifier.fillMaxWidth(), enabled = source != null && !busy) { Text("Develop") }
        OutlinedButton({
            run {
                val file = result ?: return@run
                withContext(Dispatchers.IO) { save(file) }
                message = "Saved to Pictures/Nugs."
            }
        }, Modifier.fillMaxWidth(), enabled = result != null && !busy) { Text("Save JPEG") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(message)
        Text(
            "Built on Negs and spektrafilm. Free software under GPL-3.0: github.com/headsplosion/negs-android",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    }
}

/** Where the frame sits; x and y are fractions of the photo's size, as in the engine's `Crop`. */
private data class Crop(val ratio: Int = 0, val zoom: Float = 1f, val angle: Float = 0f, val x: Float = 0f, val y: Float = 0f)

private val ratios = listOf(
    "Original" to 0f, "1:1" to 1f, "4:5" to 0.8f, "5:4" to 1.25f,
    "2:3" to 2f / 3f, "3:2" to 1.5f, "9:16" to 9f / 16f, "16:9" to 16f / 9f,
)

private fun wrap(angle: Float) = ((angle + 180f).mod(360f)) - 180f

/** The frame in photo pixels, and how far its centre may move: the same maths as `cut` in lib.rs. */
private class Frame(image: Bitmap, crop: Crop) {
    private val iw = image.width.toFloat()
    private val ih = image.height.toFloat()
    private val shape = ratios[crop.ratio].second.takeIf { it > 0f } ?: (iw / ih)
    private val c = abs(cos(Math.toRadians(crop.angle.toDouble()))).toFloat()
    private val s = abs(sin(Math.toRadians(crop.angle.toDouble()))).toFloat()
    val height = min(iw / (shape * c + s), ih / (shape * s + c)) / crop.zoom
    val width = height * shape
    val maxX = max(0f, (iw - width * c - height * s) / 2f) / iw
    val maxY = max(0f, (ih - width * s - height * c) / 2f) / ih
}

/** The photo moving, zooming and turning inside a frame of locked shape. */
@Composable
private fun CropEditor(image: Bitmap, crop: Crop, change: (Crop) -> Unit) {
    val current by rememberUpdatedState(crop)
    val send by rememberUpdatedState(change)
    val shape = Frame(image, Crop(ratio = crop.ratio)).let { it.width / it.height }
    Canvas(
        Modifier.aspectRatio(shape).clipToBounds().pointerInput(image) {
            detectTransformGestures { _, pan, zoom, turn ->
                val old = current
                val next = old.copy(zoom = (old.zoom * zoom).coerceIn(1f, 6f), angle = wrap(old.angle + turn))
                val frame = Frame(image, next)
                val k = frame.width / size.width
                val a = Math.toRadians(next.angle.toDouble())
                val dx = ((pan.x * cos(a) + pan.y * sin(a)) * k).toFloat() / image.width
                val dy = ((-pan.x * sin(a) + pan.y * cos(a)) * k).toFloat() / image.height
                send(next.copy(x = (old.x - dx).coerceIn(-frame.maxX, frame.maxX), y = (old.y - dy).coerceIn(-frame.maxY, frame.maxY)))
            }
        },
    ) {
        val frame = Frame(image, crop)
        val x = crop.x.coerceIn(-frame.maxX, frame.maxX)
        val y = crop.y.coerceIn(-frame.maxY, frame.maxY)
        val picture = image.asImageBitmap()
        withTransform({
            translate(size.width / 2f, size.height / 2f)
            rotate(crop.angle, Offset.Zero)
            scale(size.width / frame.width, size.width / frame.width, Offset.Zero)
            translate(-image.width * (0.5f + x), -image.height * (0.5f + y))
        }) { drawImage(picture) }
        // Rule of thirds.
        for (n in 1..2) {
            val line = Color.White.copy(alpha = 0.5f)
            drawLine(line, Offset(size.width * n / 3f, 0f), Offset(size.width * n / 3f, size.height))
            drawLine(line, Offset(0f, size.height * n / 3f), Offset(size.width, size.height * n / 3f))
        }
    }
}

/** Copy the picked photo to a file the engine can read, keeping its type. */
private fun Context.importPhoto(uri: Uri): File {
    val type = contentResolver.getType(uri)
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: "jpg"
    require(extension !in listOf("heic", "heif", "avif", "webp")) { "$extension photos aren't supported yet. Use JPEG." }
    cacheDir.listFiles { f -> f.name.startsWith("source.") }?.forEach { it.delete() }
    val file = File(cacheDir, "source.$extension")
    contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
    return file
}

/** A screen-sized bitmap, turned upright. */
private fun load(file: File): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
    val long = maxOf(info.size.width, info.size.height)
    if (long > 1600) decoder.setTargetSampleSize((long + 1599) / 1600)
}

/** The engine reads plain files, so unpack the film data once per version. */
private fun filmData(context: Context): File {
    val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    val root = File(context.filesDir, "film-$version")
    val done = File(root, ".done")
    if (!done.exists()) {
        fun copy(path: String) {
            val children = context.assets.list(path).orEmpty()
            val target = File(root, path)
            if (children.isEmpty()) {
                target.parentFile?.mkdirs()
                context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
            } else children.forEach { copy("$path/$it") }
        }
        copy("data")
        done.writeText("ok")
    }
    return File(root, "data")
}

private fun Context.save(file: File) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "nugs_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Nugs")
    }
    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
    contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
}
