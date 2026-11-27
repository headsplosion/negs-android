package com.headsplosion.nugs

/** The Rust film engine (crates/nugs-android). Each call replies "OK …" or "ERR …". */
object Engine {
    init { System.loadLibrary("nugs_android") }
    external fun catalog(data: String): String
    external fun balance(data: String, film: String, paper: String, params: String): String
    external fun straighten(input: String, base: String, mode: String): String
    external fun develop(data: String, cache: String, input: String, output: String, request: String): String
}
