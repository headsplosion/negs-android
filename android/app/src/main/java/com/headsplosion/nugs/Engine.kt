package com.headsplosion.nugs

/** The Rust film engine (crates/nugs-android). */
object Engine {
    init { System.loadLibrary("nugs_android") }
    external fun develop(
        data: String, cache: String, input: String, output: String, film: String, paper: String, maxSide: Int,
        ratio: Float, zoom: Float, angle: Float, x: Float, y: Float, grain: Float,
    ): String
}
