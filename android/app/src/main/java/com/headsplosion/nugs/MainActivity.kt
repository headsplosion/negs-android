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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
        preview = withContext(Dispatchers.IO) { load(file) }
        message = "Pick a film, then Develop."
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(::open) }
    LaunchedEffect(shared) { shared?.let(::open) }

    Column(
        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("NUGS", style = MaterialTheme.typography.titleLarge)
        OutlinedButton(
            { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            Modifier.fillMaxWidth(), enabled = !busy,
        ) { Text("Choose photo") }
        preview?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 420.dp)) }
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
