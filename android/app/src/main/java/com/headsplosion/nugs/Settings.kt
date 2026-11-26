package com.headsplosion.nugs

import org.json.JSONArray
import org.json.JSONObject

/** A film or paper, with the grain it has until a slider changes it. */
data class Profile(
    val id: String, val name: String, val paper: Boolean, val positive: Boolean, val bw: Boolean,
    val targetPrint: String, val grain: JSONObject,
)

/** The engine's stocks and default settings. */
class Catalog(json: String) {
    private val root = JSONObject(json)
    val defaults: JSONObject = root.getJSONObject("defaults")
    private val profiles = root.getJSONArray("profiles").objects().map {
        Profile(
            it.getString("id"), it.getString("name"), it.getBoolean("paper"), it.getBoolean("positive"),
            it.getBoolean("bw"), it.optString("target_print"), it.getJSONObject("grain"),
        )
    }
    val films = profiles.filter { !it.paper }
    val papers = profiles.filter { it.paper }
    fun film(id: String) = films.first { it.id == id }
    fun paperName(id: String) = papers.firstOrNull { it.id == id }?.name ?: id
}

fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

data class Border(
    val enabled: Boolean = false, val style: String = "solid", val export: Boolean = true,
    val instagram: Boolean = false, val width: Double = 0.03, val filmWidth: Double = 0.0,
    val line: Double = 0.004, val color: String = "#ffffff", val lineColor: String = "#000000",
) {
    fun number(key: String) = when (key) { "width" -> width; "filmWidth" -> filmWidth; else -> line }
    fun with(key: String, value: Double) = when (key) {
        "width" -> copy(width = value)
        "filmWidth" -> copy(filmWidth = value)
        else -> copy(line = value)
    }

    fun request(): JSONObject {
        val options = JSONObject().put("line", line).put("color", color).put("line_color", lineColor)
        if (style == "solid" || style == "keyline") options.put("width", width)
        if (style == "film") options.put("width", filmWidth)
        return JSONObject().put("style", style).put("instagram", instagram).put("options", options)
    }
}

/** The film, paper and every setting changed from the engine's default, by path. */
data class Edit(
    val film: String, val paper: String, val values: Map<String, Any> = emptyMap(),
    val border: Border = Border(), val grain: Float = 1f,
) {
    /** The value at `path`: yours, else the engine's default. */
    fun get(catalog: Catalog, path: String): Any? =
        values[path] ?: path.split(".").fold(catalog.defaults as Any?) { node, key ->
            when (node) {
                is JSONObject -> node.opt(key)
                is JSONArray -> node.opt(key.toInt())
                else -> null
            }
        }

    fun flag(catalog: Catalog, path: String) = get(catalog, path) == true
    fun set(path: String, value: Any) = copy(values = values + (path to value))

    /** One lane of a grain setting; unset ones are the film's own. */
    fun lanes(catalog: Catalog, path: String): List<Double> =
        (values[path] as? List<*>)?.map { it as Double } ?: catalog.film(film).grain
            .getJSONArray(path.substringAfterLast('.')).let { a -> (0 until a.length()).map { a.getDouble(it) } }

    /** A new film brings its own grain, paper and scan setting. */
    fun choose(catalog: Catalog, id: String): Edit {
        val next = catalog.film(id)
        val kept = values.filterKeys { it !in presetPaths }
        val target = next.targetPrint.takeIf { t -> catalog.papers.any { it.id == t } }
        return copy(film = id, paper = target ?: paper, values = kept + ("io.scan_film" to next.positive))
    }

    fun reset(catalog: Catalog) = Edit(film, paper, mapOf("io.scan_film" to catalog.film(film).positive), border)

    /** The engine's complete `RuntimeParams`. */
    fun params(catalog: Catalog): JSONObject {
        val root = JSONObject(catalog.defaults.toString())
        for ((path, value) in values) {
            val keys = path.split(".")
            var node: Any = root
            for (key in keys.dropLast(1)) {
                node = if (node is JSONArray) node.get(key.toInt())
                else (node as JSONObject).optJSONObject(key) ?: node.optJSONArray(key)
                    ?: JSONObject().also { (node as JSONObject).put(key, it) }
            }
            val json = if (value is List<*>) JSONArray(value.map { plain(it!!) }) else plain(value)
            if (node is JSONArray) node.put(keys.last().toInt(), json) else (node as JSONObject).put(keys.last(), json)
        }
        return root
    }

    /** Whole numbers go as integers: some settings accept nothing else. */
    private fun plain(value: Any): Any = if (value is Double && value % 1.0 == 0.0) value.toLong() else value

    private companion object {
        val presetPaths = listOf("rms_granularity", "density_min", "uniformity", "particle_scale_sublayers")
            .map { "film_render.grain.$it" }.toSet()
    }
}
