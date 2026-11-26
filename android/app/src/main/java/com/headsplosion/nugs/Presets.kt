package com.headsplosion.nugs

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** A look saved by name: everything in an [Edit]. The crop belongs to the photo, so it stays out. */
data class Preset(val name: String, val edit: Edit)

private fun Preset.json(): JSONObject {
    val values = JSONObject()
    for ((path, value) in edit.values) values.put(path, if (value is List<*>) JSONArray(value) else value)
    val b = edit.border
    val border = JSONObject()
        .put("enabled", b.enabled).put("style", b.style).put("export", b.export).put("instagram", b.instagram)
        .put("width", b.width).put("filmWidth", b.filmWidth).put("line", b.line)
        .put("color", b.color).put("lineColor", b.lineColor)
    return JSONObject().put("name", name).put("film", edit.film).put("paper", edit.paper)
        .put("grain", edit.grain.toDouble()).put("values", values).put("border", border)
}

private fun preset(o: JSONObject): Preset {
    val saved = o.getJSONObject("values")
    // Sliders and grain lanes expect doubles; JSON gives whole numbers back as integers.
    val values = saved.keys().asSequence().associateWith { path ->
        when (val v = saved.get(path)) {
            is JSONArray -> (0 until v.length()).map { v.getDouble(it) }
            is Number -> v.toDouble()
            else -> v
        }
    }
    val b = o.getJSONObject("border")
    val border = Border(
        b.getBoolean("enabled"), b.getString("style"), b.getBoolean("export"), b.getBoolean("instagram"),
        b.getDouble("width"), b.getDouble("filmWidth"), b.getDouble("line"),
        b.getString("color"), b.getString("lineColor"),
    )
    return Preset(
        o.getString("name"),
        Edit(o.getString("film"), o.getString("paper"), values, border, o.getDouble("grain").toFloat()),
    )
}

private fun read(text: String) = JSONArray(text).objects().map(::preset)
private fun write(presets: List<Preset>) = JSONArray(presets.map { it.json() }).toString(1)

/** Saved presets with `added` joining them, replacing any of the same name. */
private fun merge(presets: List<Preset>, added: List<Preset>) =
    (presets.filter { old -> added.none { it.name == old.name } } + added).sortedBy { it.name.lowercase() }

/** The Presets tab: save, apply, delete, and back up to or restore from a file. */
@Composable
fun Presets(catalog: Catalog, edit: Edit, apply: (Edit) -> Unit, report: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { context.getSharedPreferences("presets", Context.MODE_PRIVATE) }
    var presets by remember {
        mutableStateOf(runCatching { read(store.getString("all", "[]")!!) }.getOrDefault(emptyList()))
    }
    var name by remember { mutableStateOf("") }
    var doomed by remember { mutableStateOf<Preset?>(null) }

    fun keep(next: List<Preset>) {
        presets = next
        store.edit().putString("all", write(next)).apply()
    }

    fun attempt(what: String, block: () -> String) {
        report(runCatching(block).getOrElse { "$what failed: ${it.message}" })
    }

    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) attempt("Backup") {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(write(presets).toByteArray()) }
            "Backed up ${presets.size} presets."
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) attempt("Restore") {
            val found = read(context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
            // Leave out presets for films this version doesn't have.
            val usable = found.filter { p -> catalog.films.any { it.id == p.edit.film } }
            keep(merge(presets, usable))
            "Restored ${usable.size} presets."
        }
    }

    OutlinedTextField(
        name, { name = it }, Modifier.fillMaxWidth(),
        label = { Text("Preset name") }, singleLine = true,
    )
    val trimmed = name.trim()
    Button(
        {
            keep(merge(presets, listOf(Preset(trimmed, edit))))
            report("Saved preset “$trimmed”.")
            name = ""
        },
        Modifier.fillMaxWidth(), enabled = trimmed.isNotEmpty(),
    ) { Text(if (presets.any { it.name == trimmed }) "Replace preset" else "Save current settings") }

    if (presets.isEmpty()) Text("No presets yet. Set up a look, name it and save it.")
    for (p in presets) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                { apply(p.edit); report("Applied “${p.name}”.") },
                Modifier.weight(1f),
            ) { Text(if (p.edit == edit) "✓ ${p.name}" else p.name) }
            TextButton({ doomed = p }) { Text("Delete") }
        }
    }

    HorizontalDivider()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ backup.launch("nugs-presets.json") }, enabled = presets.isNotEmpty()) { Text("Back up") }
        OutlinedButton({ restore.launch(arrayOf("application/json", "application/octet-stream")) }) { Text("Restore") }
    }
    Text("Back up before uninstalling the app: uninstalling erases presets.", style = MaterialTheme.typography.bodySmall)

    doomed?.let { p ->
        AlertDialog(
            onDismissRequest = { doomed = null },
            title = { Text("Delete “${p.name}”?") },
            confirmButton = { TextButton({ keep(presets - p); doomed = null }) { Text("Delete") } },
            dismissButton = { TextButton({ doomed = null }) { Text("Cancel") } },
        )
    }
}
