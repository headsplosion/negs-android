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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val tabs = listOf("Crop", "Presets", "Prep", "Stock", "Camera", "Film", "Print", "Scan", "Output")

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

/** What a preview shows; a change to any of it develops again. */
private data class Shot(val source: File, val edit: Edit, val crop: Crop)

/** The engine runs one call at a time. */
private val engine = Mutex()

private fun request(catalog: Catalog, shot: Shot, maxSide: Int, preview: Boolean): String {
    val (_, edit, crop) = shot
    val frame = JSONObject()
        .put("ratio", ratios[crop.ratio].second.toDouble()).put("zoom", crop.zoom.toDouble())
        .put("angle", crop.angle.toDouble()).put("x", crop.x.toDouble()).put("y", crop.y.toDouble())
    val border = edit.border.takeIf { it.enabled && (preview || it.export) }
    return JSONObject()
        .put("film", edit.film).put("paper", edit.paper).put("max_side", maxSide).put("preview", preview)
        .put("crop", frame).put("grain", edit.grain.toDouble()).put("params", edit.params(catalog))
        .put("border", border?.request() ?: JSONObject.NULL)
        .toString()
}

private suspend fun call(work: () -> String): String = engine.withLock {
    val reply = withContext(Dispatchers.Default) { work() }
    if (!reply.startsWith("OK ")) error(reply.removePrefix("ERR "))
    reply.removePrefix("OK ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComponentActivity.Screen(shared: Uri?) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<File?>(null) }
    var catalog by remember { mutableStateOf<Catalog?>(null) }
    val controls = remember { Controls(assets.open("controls.json").bufferedReader().use { it.readText() }) }
    var edit by remember { mutableStateOf(Edit("kodak_portra_400", "kodak_portra_endura")) }
    var source by remember { mutableStateOf<File?>(null) }
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var crop by remember { mutableStateOf(Crop()) }
    var tab by remember { mutableIntStateOf(0) }
    var large by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var rendering by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Loading film data…") }

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() } catch (e: Throwable) { message = "Failed: ${e.message}" }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        try {
            val dir = withContext(Dispatchers.IO) { filmData(this@Screen) }
            data = dir
            catalog = Catalog(call { Engine.catalog(dir.path) })
            if (source == null) message = "Choose a photo to start."
        } catch (e: Throwable) {
            message = "Failed: ${e.message}"
        }
    }

    fun open(uri: Uri) = run {
        message = "Opening…"
        val file = withContext(Dispatchers.IO) { importPhoto(uri) }
        original = withContext(Dispatchers.IO) { load(file) }
        source = file
        preview = null
        crop = Crop()
        tab = 0
        message = "Frame it on this tab; the others show the developed photo."
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(::open) }
    LaunchedEffect(shared) { shared?.let(::open) }

    // Live preview: after each change develop the newest settings, skipping
    // any that were passed over while the last one rendered.
    LaunchedEffect(Unit) {
        snapshotFlow {
            val file = source
            if (file == null || catalog == null || tab == 0) null else Shot(file, edit, crop)
        }.distinctUntilChanged().filterNotNull().conflate().collect { shot ->
            rendering = true
            try {
                val output = File(cacheDir, "preview.jpg")
                val made = request(catalog!!, shot, 1080, true)
                message = call { Engine.develop(data!!.path, cacheDir.path, shot.source.path, output.path, made) }
                preview = withContext(Dispatchers.IO) { load(output) }
            } catch (e: Throwable) {
                message = "Failed: ${e.message}"
            }
            rendering = false
        }
    }

    fun change(new: Edit) { edit = new }
    fun adjust(new: Crop) { crop = new; preview = null }

    fun balance() = run {
        val c = catalog ?: return@run
        val at = edit
        val reply = call { Engine.balance(data!!.path, at.film, at.paper, at.params(c).toString()) }
        val (m, y) = reply.split(" ").map { it.toDouble() }
        edit = edit.set("enlarger.m_filter_shift", m).set("enlarger.y_filter_shift", y)
        message = "Print balanced: magenta %.2f, yellow %.2f".format(m, y)
    }

    fun export() = run {
        val c = catalog ?: return@run
        val file = source ?: return@run
        message = "Developing full size…"
        val output = File(cacheDir, "developed.jpg")
        val made = request(c, Shot(file, edit, crop), if (large) 4096 else 2048, false)
        val report = call { Engine.develop(data!!.path, cacheDir.path, file.path, output.path, made) }
        withContext(Dispatchers.IO) { save(output) }
        message = "Saved to Pictures/Nugs · $report"
    }

    Column(Modifier.safeDrawingPadding()) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.45f).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            val developed = preview.takeIf { tab != 0 }
            if (developed != null) Image(developed.asImageBitmap(), null, Modifier.fillMaxSize())
            else original?.let { CropEditor(it, crop, ::adjust) }
        }
        if (rendering || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        else Spacer(Modifier.height(4.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                Modifier.weight(1f), enabled = !busy,
            ) { Text("Choose photo") }
            Button(::export, Modifier.weight(1f), enabled = source != null && catalog != null && !busy) { Text("Save JPEG") }
        }
        Text(message, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        ScrollableTabRow(tab, edgePadding = 8.dp) {
            tabs.forEachIndexed { i, name -> Tab(tab == i, { tab = i }, text = { Text(name) }) }
        }
        // A new scroll position for each tab.
        key(tab) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val c = catalog
                if (c != null) when (tabs[tab]) {
                    "Crop" -> if (original != null) {
                        Text("Drag to move, pinch to zoom, twist to rotate.", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ratios.forEachIndexed { i, r ->
                                FilterChip(crop.ratio == i, { adjust(crop.copy(ratio = i, x = 0f, y = 0f)) }, { Text(r.first) })
                            }
                        }
                        Text("Rotate ${"%.1f".format(crop.angle)}°")
                        Slider(crop.angle, { adjust(crop.copy(angle = it)) }, valueRange = -180f..180f)
                        Text("Zoom ${"%.2f".format(crop.zoom)}×")
                        Slider(crop.zoom, { adjust(crop.copy(zoom = it)) }, valueRange = 1f..6f)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ adjust(crop.copy(angle = wrap(crop.angle + 90f))) }) { Text("Rotate 90°") }
                            OutlinedButton({ adjust(Crop()) }) { Text("Reset crop") }
                        }
                    }
                    "Presets" -> Presets(c, edit, ::change) { message = it }
                    "Prep" -> for (section in controls.prep) {
                        // RAW white balance is for camera RAW files only.
                        if (section.optBoolean("raw") && source?.extension?.lowercase() !in listOf("dng", "raf", "nef", "cr2", "cr3", "arw", "rw2", "orf")) continue
                        Heading(section.getString("title"))
                        ControlList(section.getJSONArray("controls").objects(), controls, c, edit, ::change, ::balance)
                    }
                    "Stock" -> {
                        StockPicker("Film", c.films, edit.film) { change(edit.choose(c, it)) }
                        Toggle("Scan film directly", edit.flag(c, "io.scan_film")) { change(edit.set("io.scan_film", it)) }
                        if (!edit.flag(c, "io.scan_film")) {
                            StockPicker("Paper", c.papers, edit.paper) { change(edit.copy(paper = it)) }
                        }
                        OutlinedButton({ change(edit.reset(c)) }, Modifier.fillMaxWidth()) { Text("Reset adjustments") }
                    }
                    "Output" -> {
                        BorderControls(controls, edit.border) { change(edit.copy(border = it)) }
                        Heading("Export size")
                        Chips(listOf("2048" to "2048 px", "4096" to "4096 px"), if (large) "4096" else "2048") { large = it == "4096" }
                        Text(
                            "Built on Negs and spektrafilm. Free software under GPL-3.0: github.com/headsplosion/negs-android",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    "Print" -> if (edit.flag(c, "io.scan_film")) Text("Printing is skipped while scanning film directly.")
                    else Section(controls.lab("Print"), controls, c, edit, ::change, ::balance)
                    "Film" -> {
                        Dial("Grain amount (all channels)", edit.grain.toDouble(), 0.0, 3.0, 0.1, null) {
                            change(edit.copy(grain = it.toFloat()))
                        }
                        Section(controls.lab("Film"), controls, c, edit, ::change, ::balance)
                    }
                    else -> Section(controls.lab(tabs[tab]), controls, c, edit, ::change, ::balance)
                }
            }
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
    // Drop the copies earlier versions unpacked.
    context.filesDir.listFiles { f -> f.name.startsWith("film-") && f != root }?.forEach { it.deleteRecursively() }
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
