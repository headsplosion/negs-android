package com.headsplosion.nugs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

/** Negs' controls, generated from web/controls.js by make-controls.mjs. */
class Controls(json: String) {
    private val root = JSONObject(json)
    private val titles = root.getJSONObject("titles")
    val prep = root.getJSONArray("prep").objects()
    private val lab = root.getJSONArray("lab").objects()
    val border = root.getJSONArray("output").objects().first().getJSONArray("controls").objects()
    fun lab(title: String) = lab.first { it.optString("tab", it.getString("title")) == title }
    fun title(option: String): String = titles.optString(
        option, option.replace('_', ' ').split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) },
    )
}

private fun JSONArray?.strings() = if (this == null) emptyList() else (0 until length()).map { getString(it) }

private fun shows(control: JSONObject, edit: Edit, catalog: Catalog): Boolean = when (val rule = control.opt("when")) {
    is String -> edit.flag(catalog, rule)
    is JSONArray -> edit.get(catalog, rule.getString(0)) == rule.get(1)
    else -> true
}

/** A labelled slider snapped to its step. Tap the label to go back to `reset`. */
@Composable
fun Dial(label: String, value: Double, min: Double, max: Double, step: Double, reset: (() -> Unit)?, change: (Double) -> Unit) {
    val decimals = max(0.0, floor(-log10(step) + 1e-9)).toInt()
    Column {
        Row(Modifier.fillMaxWidth().clickable(enabled = reset != null) { reset?.invoke() }) {
            Text(label, Modifier.weight(1f), fontWeight = if (reset != null) FontWeight.Bold else FontWeight.Normal)
            Text("%.${decimals}f".format(value) + if (reset != null) "  ↺" else "")
        }
        Slider(
            value.toFloat().coerceIn(min.toFloat(), max.toFloat()),
            {
                val scale = 10.0.pow(decimals + 1)
                val snapped = min + ((it - min) / step).roundToLong() * step
                change(((snapped * scale).roundToLong() / scale).coerceIn(min, max))
            },
            valueRange = min.toFloat()..max.toFloat(),
        )
    }
}

@Composable
fun Toggle(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, change)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(options: List<Pair<String, String>>, selected: Any?, choose: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, name) -> FilterChip(selected == id, { choose(id) }, { Text(name) }) }
    }
}

@Composable
fun Heading(title: String) {
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleMedium)
}

@Composable
fun ControlList(
    list: List<JSONObject>, controls: Controls, catalog: Catalog, edit: Edit,
    change: (Edit) -> Unit, balance: () -> Unit,
) {
    for (c in list) {
        if (!shows(c, edit, catalog)) continue
        val label = c.getString("label")
        val path = c.optString("path")
        val reset = { change(edit.copy(values = edit.values - path)) }.takeIf { path in edit.values }
        when (c.getString("kind")) {
            "slider", "offset" -> Dial(
                label, (edit.get(catalog, path) as? Number)?.toDouble() ?: 0.0,
                c.getDouble("min"), c.getDouble("max"), c.getDouble("step"), reset,
            ) { change(edit.set(path, it)) }
            "toggle" -> Toggle(label, edit.flag(catalog, path)) { change(edit.set(path, it)) }
            "choice" -> {
                Text(label)
                Chips(c.getJSONArray("options").strings().map { it to controls.title(it) }, edit.get(catalog, path)) {
                    change(edit.set(path, it))
                }
            }
            "stock" -> {
                // A black and white film has one emulsion: one slider per setting.
                val bw = catalog.film(edit.film).bw
                if (bw && c.isNull("bw")) continue
                val index = c.getInt("index")
                val lanes = edit.lanes(catalog, path)
                Dial(
                    if (bw) c.getString("bw") else label, lanes[index],
                    c.getDouble("min"), c.getDouble("max"), c.getDouble("step"), reset,
                ) { v -> change(edit.set(path, lanes.mapIndexed { i, old -> if (i == index || bw) v else old })) }
            }
            "action" -> OutlinedButton(balance, Modifier.fillMaxWidth()) { Text(label) }
        }
    }
}

/** A pane's loose controls, then its effect groups with their switches. */
@Composable
fun Section(
    section: JSONObject, controls: Controls, catalog: Catalog, edit: Edit,
    change: (Edit) -> Unit, balance: () -> Unit,
) {
    section.optJSONArray("controls")?.let { ControlList(it.objects(), controls, catalog, edit, change, balance) }
    for (group in section.optJSONArray("groups")?.objects().orEmpty()) {
        val enable = group.optString("enable")
        val on = enable.isEmpty() || edit.flag(catalog, enable)
        var open by remember(group.getString("title")) { mutableStateOf(group.optBoolean("open")) }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (open) "▾ " else "▸ ") + group.getString("title"),
                Modifier.weight(1f).clickable { open = !open }.padding(vertical = 12.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            if (enable.isNotEmpty()) Switch(on, { change(edit.set(enable, it)) })
        }
        if (open && on) ControlList(group.getJSONArray("controls").objects(), controls, catalog, edit, change, balance)
    }
}

private val styles = listOf("solid" to "Solid", "film" to "Film", "keyline" to "Keyline", "35mm" to "35mm")
private val swatches = listOf(
    "#ffffff" to "White", "#f4efe4" to "Cream", "#808080" to "Grey", "#000000" to "Black",
)

@Composable
fun BorderControls(controls: Controls, border: Border, change: (Border) -> Unit) {
    for (c in controls.border) {
        val kind = c.getString("kind")
        val key = c.optString("border")
        if (key != "enabled" && !border.enabled) continue
        val only = c.optJSONArray("styles").strings()
        if (only.isNotEmpty() && border.style !in only) continue
        val label = c.getString("label")
        when {
            kind == "toggle" && key == "enabled" -> Toggle(label, border.enabled) { change(border.copy(enabled = it)) }
            kind == "toggle" && key == "instagram" -> Toggle(label, border.instagram) { change(border.copy(instagram = it)) }
            kind == "toggle" -> Toggle(label, border.export) { change(border.copy(export = it)) }
            kind == "styles" -> Chips(styles, border.style) { change(border.copy(style = it)) }
            kind == "color" -> {
                val line = key == "lineColor"
                val value = if (line) border.lineColor else border.color
                fun pick(hex: String) = change(if (line) border.copy(lineColor = hex) else border.copy(color = hex))
                Text(label)
                Chips(swatches, value, ::pick)
                var typed by remember(value) { mutableStateOf(value) }
                OutlinedTextField(typed, {
                    typed = it
                    if (it.matches(Regex("#[0-9a-fA-F]{6}"))) pick(it.lowercase())
                }, label = { Text("Hex") }, singleLine = true)
            }
            else -> Dial("$label (%)", border.number(key) * 100, c.getDouble("min"), c.getDouble("max"), c.getDouble("step"), null) {
                change(border.with(key, it / 100))
            }
        }
    }
}

/** A button naming the chosen stock, opening a searchable list. */
@Composable
fun StockPicker(title: String, stocks: List<Profile>, selected: String, choose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    OutlinedButton({ open = true; query = "" }, Modifier.fillMaxWidth()) {
        Text("$title: ${stocks.firstOrNull { it.id == selected }?.name ?: selected}")
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        confirmButton = { TextButton({ open = false }) { Text("Close") } },
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("Search") }, singleLine = true)
                LazyColumn {
                    items(stocks.filter { it.name.contains(query, ignoreCase = true) }) {
                        Text(
                            it.name,
                            Modifier.fillMaxWidth().clickable { choose(it.id); open = false }.padding(vertical = 14.dp),
                            fontWeight = if (it.id == selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        },
    )
}
