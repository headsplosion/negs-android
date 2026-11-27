package com.headsplosion.nugs

import android.graphics.Bitmap
import android.graphics.Matrix
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** The perspective warp of geometry.rs, so the crop editor shows what the engine will cut. */
private class Warp(private val width: Double, private val height: Double, vertical: Float, horizontal: Float) {
    private val sp = sin(Math.toRadians(vertical.toDouble()))
    private val cp = cos(Math.toRadians(vertical.toDouble()))
    private val sy = sin(Math.toRadians(horizontal.toDouble()))
    private val cy = cos(Math.toRadians(horizontal.toDouble()))
    private val f = 0.75 * max(width, height)
    private val off = project(0.0, 0.0)
    private val scale: Double = run {
        var low = 0.0
        var high = 1.0
        repeat(40) {
            val mid = (low + high) / 2
            val inside = listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1).all { (x, y) ->
                val (a, b) = project(x * width / 2 * mid, y * height / 2 * mid)
                abs(a - off.first) <= width / 2 + 1e-6 && abs(b - off.second) <= height / 2 + 1e-6
            }
            if (inside) low = mid else high = mid
        }
        low
    }

    private fun project(x: Double, y: Double): Pair<Double, Double> {
        val y1 = y * cp - f * sp
        val z1 = y * sp + f * cp
        val x2 = x * cy + z1 * sy
        val z2 = -x * sy + z1 * cy
        return f * x2 / z2 to f * y1 / z2
    }

    /** Where the photo's (x, y), from its centre, lands. */
    fun place(x: Double, y: Double): Pair<Double, Double> {
        val x0 = x + off.first
        val y0 = y + off.second
        val x1 = x0 * cy - f * sy
        val z1 = x0 * sy + f * cy
        val y2 = y0 * cp + z1 * sp
        val z2 = -y0 * sp + z1 * cp
        return f * x1 / z2 / scale to f * y2 / z2 / scale
    }
}

/** The warp as a matrix over `image`'s own pixels. */
fun perspective(image: Bitmap, vertical: Float, horizontal: Float): Matrix {
    val matrix = Matrix()
    if (vertical == 0f && horizontal == 0f) return matrix
    val w = image.width.toFloat()
    val h = image.height.toFloat()
    val warp = Warp(w.toDouble(), h.toDouble(), vertical, horizontal)
    val from = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
    val to = FloatArray(8)
    for (i in 0 until 4) {
        val (x, y) = warp.place((from[2 * i] - w / 2).toDouble(), (from[2 * i + 1] - h / 2).toDouble())
        to[2 * i] = x.toFloat() + w / 2
        to[2 * i + 1] = y.toFloat() + h / 2
    }
    matrix.setPolyToPoly(from, 0, to, 0, 4)
    return matrix
}
