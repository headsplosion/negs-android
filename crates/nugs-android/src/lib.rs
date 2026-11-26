//! Android entry point for the film engine: one call that develops a photo file.
use anyhow::{Context, Result};
use jni::{JNIEnv, objects::{JClass, JString}, sys::jstring};
use serde_json::Value;
use spektrafilm_core::{params::RuntimeParams, pipeline::Pipeline, preview, profile};
use spektrafilm_gpu::ComputeBackend;
use spektrafilm_io::{InputColour, border, encoding::Encoding};
use spektrafilm_math::image::ImageBuf;
use std::{path::Path, sync::{Mutex, OnceLock}, time::Instant};

/// A frame of fixed shape placed on the photo. `x` and `y` move its centre, as
/// fractions of the photo's width and height; `angle` turns the photo clockwise.
#[derive(Clone, Copy)]
pub struct Crop {
    /// Width / height; 0 keeps the photo's own.
    pub ratio: f64,
    pub zoom: f64,
    pub angle: f64,
    pub x: f64,
    pub y: f64,
}

impl Default for Crop {
    fn default() -> Self {
        Crop { ratio: 0.0, zoom: 1.0, angle: 0.0, x: 0.0, y: 0.0 }
    }
}

/// Cut the frame out of the photo, at most `max_side` pixels long. The largest
/// frame that fits the turned photo is divided by the zoom, and its centre is
/// clamped so no empty corners appear (the editor in MainActivity.kt matches).
fn cut(image: ImageBuf, crop: Crop, max_side: u32) -> ImageBuf {
    let (iw, ih) = (image.width as f64, image.height as f64);
    let ratio = if crop.ratio > 0.0 { crop.ratio } else { iw / ih };
    let (sin, cos) = crop.angle.to_radians().sin_cos();
    let (s, c) = (sin.abs(), cos.abs());
    let h = (iw / (ratio * c + s)).min(ih / (ratio * s + c)) / crop.zoom.max(1.0);
    let w = h * ratio;
    let mx = ((iw - w * c - h * s) / 2.0).max(0.0);
    let my = ((ih - w * s - h * c) / 2.0).max(0.0);
    let (dx, dy) = ((crop.x * iw).clamp(-mx, mx), (crop.y * ih).clamp(-my, my));
    let scale = (max_side as f64 / w.max(h)).min(1.0);
    let (ow, oh) = (((w * scale).round() as u32).max(1), ((h * scale).round() as u32).max(1));
    if crop.angle == 0.0 && dx == 0.0 && dy == 0.0 && (w - iw).abs() < 0.5 && (h - ih).abs() < 0.5 {
        return if scale < 1.0 { preview::downsample_area(&image, (ow, oh)) } else { image };
    }
    // Shrink first (area filter), then turn and cut with bilinear sampling.
    let small = ((iw * scale).round().max(1.0) as u32, (ih * scale).round().max(1.0) as u32);
    let image = if scale < 1.0 { preview::downsample_area(&image, small) } else { image };
    let (sw, sh) = (image.width as usize, image.height as usize);
    let (kx, ky) = (sw as f64 / iw, sh as f64 / ih);
    let mut data = Vec::with_capacity(ow as usize * oh as usize * 3);
    for row in 0..oh {
        let v = ((row as f64 + 0.5) / oh as f64 - 0.5) * h;
        for col in 0..ow {
            let u = ((col as f64 + 0.5) / ow as f64 - 0.5) * w;
            let px = ((iw / 2.0 + dx + u * cos + v * sin) * kx - 0.5).clamp(0.0, (sw - 1) as f64);
            let py = ((ih / 2.0 + dy - u * sin + v * cos) * ky - 0.5).clamp(0.0, (sh - 1) as f64);
            let (x0, y0) = (px as usize, py as usize);
            let (x1, y1) = ((x0 + 1).min(sw - 1), (y0 + 1).min(sh - 1));
            let (fx, fy) = (px - x0 as f64, py - y0 as f64);
            for ch in 0..3 {
                let at = |x: usize, y: usize| image.data[(y * sw + x) * 3 + ch];
                let top = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * fx as f32;
                let bottom = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * fx as f32;
                data.push(top + (bottom - top) * fy as f32);
            }
        }
    }
    ImageBuf::from_data(ow, oh, data)
}

static BACKEND: OnceLock<Box<dyn ComputeBackend>> = OnceLock::new();

/// The last photo decoded for previews, kept small so that moving a slider
/// doesn't decode the file again: (file and white balance, pixels, colour).
static PREVIEW: Mutex<Option<(String, ImageBuf, InputColour)>> = Mutex::new(None);
const PREVIEW_SOURCE: u32 = 2400;

/// The stocks under `data/profiles`, the engine's defaults and each stock's
/// own presets, as the web app's `catalog.json`.
pub fn catalog(data: &Path) -> Result<String> {
    let stocks: Vec<String> = std::fs::read_dir(data.join("profiles"))?
        .filter_map(|e| e.ok()?.file_name().to_str()?.strip_suffix(".json").map(String::from))
        .collect();
    Ok(spektrafilm_core::catalog::catalog(data, stocks)?.to_string())
}

/// Complete or partial `RuntimeParams` JSON, as the web build reads it.
fn parse_params(value: &Value) -> Result<RuntimeParams> {
    if value.is_null() {
        return Ok(RuntimeParams::default());
    }
    let mut value = value.clone();
    spektrafilm_core::params::infer_input_interpretation(&mut value);
    serde_json::from_value(value).context("Reading the settings")
}

/// The enlarger filter shifts that print a mid-grey neutral: (magenta, yellow).
pub fn balance(data: &Path, film: &str, paper: &str, params: &str) -> Result<(f64, f64)> {
    spektrafilm_core::print_balance::solve_neutral_filter_shifts(
        profile::load_profile_by_name(data, film)?,
        profile::load_profile_by_name(data, paper)?,
        &parse_params(&serde_json::from_str(params)?)?,
        data,
        spektrafilm_core::print_balance::MIDGRAY,
    )
    .map_err(anyhow::Error::msg)
}

fn load(input: &Path, params: &RuntimeParams) -> Result<(ImageBuf, InputColour)> {
    let loaded = spektrafilm_io::load_image_colour(
        input,
        spektrafilm_io::Formats::Any,
        params.io.raw_white_balance.clone(),
        params.io.lens_correction.clone(),
        &params.io.input_interpretation,
    )?;
    Ok((loaded.image, loaded.colour))
}

/// Develop `input` (JPEG, PNG, TIFF or RAW) and write a JPEG to `output`.
/// `request` is JSON: `film`, `paper`, `max_side`, `preview`, `crop` (see
/// [`Crop`]), `grain` (a multiple of the granularity), `params`
/// (`RuntimeParams`) and `border` (`style`, `instagram`, `options`) or null.
/// Returns a short report.
pub fn develop(data: &Path, input: &Path, output: &Path, request: &str) -> Result<String> {
    let started = Instant::now();
    let request: Value = serde_json::from_str(request)?;
    let film = request["film"].as_str().context("No film")?;
    let max_side = (request["max_side"].as_u64().unwrap_or(2048) as u32).max(64);
    let mut params = parse_params(&request["params"])?;
    let paper = if params.io.scan_film { film } else { request["paper"].as_str().context("No paper")? };

    let number = |key: &str, default: f64| request["crop"][key].as_f64().unwrap_or(default);
    let crop = Crop {
        ratio: number("ratio", 0.0),
        zoom: number("zoom", 1.0),
        angle: number("angle", 0.0),
        x: number("x", 0.0),
        y: number("y", 0.0),
    };

    let (image, colour) = if request["preview"].as_bool() == Some(true) {
        let key = format!(
            "{}|{:?}|{:?}|{:?}|{:?}",
            input.display(),
            std::fs::metadata(input).and_then(|m| m.modified()).ok(),
            params.io.raw_white_balance,
            params.io.lens_correction,
            params.io.input_interpretation,
        );
        let mut cache = PREVIEW.lock().unwrap_or_else(|e| e.into_inner());
        if cache.as_ref().is_none_or(|(k, _, _)| *k != key) {
            *cache = None;
            let (image, colour) = load(input, &params)?;
            let long = image.width.max(image.height);
            let image = if long > PREVIEW_SOURCE {
                let size = |v: u32| (v as u64 * PREVIEW_SOURCE as u64 / long as u64).max(1) as u32;
                let small = (size(image.width), size(image.height));
                preview::downsample_area(&image, small)
            } else {
                image
            };
            *cache = Some((key, image, colour));
        }
        let (_, image, colour) = cache.as_ref().context("No preview")?;
        (ImageBuf::from_data(image.width, image.height, image.data.clone()), colour.clone())
    } else {
        *PREVIEW.lock().unwrap_or_else(|e| e.into_inner()) = None;
        load(input, &params)?
    };
    let image = cut(image, crop, max_side);
    params.io.apply_input_colour(&colour);

    let film_profile = profile::load_profile_by_name(data, film)?;
    let paper_profile = profile::load_profile_by_name(data, paper)?;
    // Grain amount scales the granularity: 1 is the film (or its sliders) as is.
    let grain = request["grain"].as_f64().unwrap_or(1.0);
    if grain <= 0.0 {
        params.film_render.grain.active = false;
    } else if grain != 1.0 {
        let own = spektrafilm_core::grain::grain_settings(&film_profile, &params.film_render.grain).rms_granularity;
        params.film_render.grain.rms_granularity = Some(own.map(|v| v * grain));
    }
    let pipeline = Pipeline::new_with_spectral(film_profile, paper_profile, params.clone(), data)
        .map_err(anyhow::Error::msg)?;

    let backend = BACKEND.get_or_init(spektrafilm_gpu::select_backend);
    let (developed, used) = pipeline
        .try_process_with_backend(image, backend.as_ref())
        .map_err(|e| anyhow::anyhow!(e.to_string()))?;

    let encoding = Encoding::new(&params.io.output_color_space, params.io.output_cctf_encoding);
    let (mut width, mut height, rgba) = preview::display_rgba(&developed, usize::MAX, encoding);
    drop(developed);
    let mut rgb: Vec<u8> = rgba.chunks_exact(4).flat_map(|p| [p[0], p[1], p[2]]).collect();
    drop(rgba);
    let mut note = "";
    if let Some(style) = request["border"]["style"].as_str() {
        let style: border::BorderStyle = style.parse().map_err(|e| anyhow::anyhow!("{e}"))?;
        let options: border::BorderOptions = serde_json::from_value(request["border"]["options"].clone())?;
        let pixels = rgb.iter().map(|&v| spektrafilm_math::precision::from_f32(v as f32 / 255.0)).collect();
        let photo = ImageBuf::from_data(width as u32, height as u32, pixels);
        let load = |path: &str| image::open(data.join(path)).context("Loading the border");
        match border::add_border_with(&photo, style, &options, film, encoding, &load) {
            Ok(mut framed) => {
                if request["border"]["instagram"].as_bool() == Some(true) {
                    framed = border::extend_for_instagram(&framed, style, &options, encoding);
                }
                (width, height) = (framed.width as _, framed.height as _);
                rgb = framed.data.iter()
                    .map(|&v| (spektrafilm_math::precision::to_f32(v).clamp(0.0, 1.0) * 255.0).round() as u8)
                    .collect();
            }
            // 35mm only fits 3:2 and 3:4 frames: keep the photo, say why.
            Err(_) if matches!(style, border::BorderStyle::Film35) => note = " · 35mm border needs a 3:2 or 3:4 frame",
            Err(e) => return Err(e),
        }
    }
    let file = std::fs::File::create(output).context("Creating the output file")?;
    let mut writer = std::io::BufWriter::new(file);
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut writer, 95)
        .encode(&rgb, width as u32, height as u32, image::ExtendedColorType::Rgb8)?;
    std::io::Write::flush(&mut writer)?;
    Ok(format!("{width}×{height} · {:.1} s · {used}{note}", started.elapsed().as_secs_f64()))
}

/// Run `work` for Kotlin: "OK <reply>" or "ERR <reason>".
fn answer(env: &mut JNIEnv, work: impl FnOnce() -> Result<String>) -> jstring {
    // A panic must not cross into Java.
    let reply = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(work)) {
        Ok(Ok(report)) => format!("OK {report}"),
        Ok(Err(e)) => format!("ERR {e:#}"),
        Err(panic) => format!(
            "ERR engine stopped: {}",
            panic.downcast_ref::<String>().map(String::as_str)
                .or_else(|| panic.downcast_ref::<&str>().copied())
                .unwrap_or("unknown")
        ),
    };
    env.new_string(reply).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn text(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(String::from).unwrap_or_default()
}

/// Kotlin: `Engine.catalog(data)`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_headsplosion_nugs_Engine_catalog<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    data: JString<'local>,
) -> jstring {
    let data = text(&mut env, &data);
    answer(&mut env, || catalog(Path::new(&data)))
}

/// Kotlin: `Engine.balance(...)`, replying "OK <magenta> <yellow>".
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_headsplosion_nugs_Engine_balance<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    data: JString<'local>,
    film: JString<'local>,
    paper: JString<'local>,
    params: JString<'local>,
) -> jstring {
    let [data, film, paper, params] = [&data, &film, &paper, &params].map(|s| text(&mut env, s));
    answer(&mut env, || balance(Path::new(&data), &film, &paper, &params).map(|(m, y)| format!("{m} {y}")))
}

/// Kotlin: `Engine.develop(...)`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_headsplosion_nugs_Engine_develop<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    data: JString<'local>,
    cache: JString<'local>,
    input: JString<'local>,
    output: JString<'local>,
    request: JString<'local>,
) -> jstring {
    let [data, cache, input, output, request] = [&data, &cache, &input, &output, &request].map(|s| text(&mut env, s));
    // The GPU pipeline cache needs a writable directory.
    static ENV: std::sync::Once = std::sync::Once::new();
    // SAFETY: set once, before the engine starts any thread of its own.
    ENV.call_once(|| unsafe { std::env::set_var("XDG_CACHE_HOME", &cache) });
    answer(&mut env, || develop(Path::new(&data), Path::new(&input), Path::new(&output), &request))
}
#[cfg(test)]
mod tests {
    use super::{Crop, ImageBuf, cut};

    /// A 40×20 photo whose red is the column and green the row.
    fn ramp() -> ImageBuf {
        let data = (0..20).flat_map(|y| (0..40).flat_map(move |x| [x as _, y as _, 0.0])).collect();
        ImageBuf::from_data(40, 20, data)
    }

    #[test]
    fn crop_keeps_the_ratio_and_stays_inside() {
        let all = cut(ramp(), Crop::default(), 4096);
        assert_eq!((all.width, all.height), (40, 20));
        for angle in [-135.0, -30.0, 0.0, 12.0, 90.0, 180.0] {
            for ratio in [1.0, 0.8, 16.0 / 9.0] {
                let out = cut(ramp(), Crop { ratio, zoom: 1.0, angle, x: 9.0, y: -9.0 }, 4096);
                assert!((out.width as f64 / out.height as f64 - ratio).abs() < 0.2, "{ratio} {angle}");
                assert!(out.data.iter().all(|v| (0.0..=39.0).contains(&(*v as f64))));
            }
        }
        // Moving right takes pixels further right.
        let right = cut(ramp(), Crop { ratio: 1.0, x: 9.0, ..Default::default() }, 4096);
        assert_eq!((right.width, right.data[0] as f64), (20, 20.0));
        // A clockwise quarter turn puts the bottom-left corner at the top left.
        let turned = cut(ramp(), Crop { ratio: 0.5, angle: 90.0, ..Default::default() }, 4096);
        assert_eq!((turned.width, turned.height), (20, 40));
        assert!((turned.data[0] as f64).abs() < 1e-3 && (turned.data[1] as f64 - 19.0).abs() < 1e-3);
    }

    fn data() -> std::path::PathBuf {
        std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data")
    }

    fn grey(name: &str) -> std::path::PathBuf {
        let input = std::env::temp_dir().join(name);
        image::RgbImage::from_pixel(240, 160, image::Rgb([128; 3])).save(&input).unwrap();
        input
    }

    fn request(extra: serde_json::Value) -> String {
        let mut request = serde_json::json!({"film": "kodak_portra_400", "paper": "kodak_portra_endura", "max_side": 240});
        request.as_object_mut().unwrap().extend(extra.as_object().unwrap().clone());
        request.to_string()
    }

    #[test]
    fn grain_amount_changes_the_picture() {
        let input = grey("nugs-grey.png");
        let noise = |grain: f64| {
            let output = std::env::temp_dir().join(format!("nugs-grain-{grain}.jpg"));
            super::develop(&data(), &input, &output, &request(serde_json::json!({"grain": grain}))).unwrap();
            let pixels: Vec<f64> = image::open(output).unwrap().to_luma8().pixels().map(|p| p.0[0] as f64).collect();
            let mean = pixels.iter().sum::<f64>() / pixels.len() as f64;
            (pixels.iter().map(|v| (v - mean).powi(2)).sum::<f64>() / pixels.len() as f64).sqrt()
        };
        let (none, stock, double) = (noise(0.0), noise(1.0), noise(2.0));
        println!("noise: {none:.2} {stock:.2} {double:.2}");
        assert!(none < stock && stock < double);
    }

    /// Every film in the catalog develops, on its own paper or scanned.
    #[test]
    fn develops_every_film() {
        let catalog: serde_json::Value = serde_json::from_str(&super::catalog(&data()).unwrap()).unwrap();
        let input = grey("nugs-all.png");
        let output = std::env::temp_dir().join("nugs-all.jpg");
        let mut failed = Vec::new();
        for p in catalog["profiles"].as_array().unwrap().iter().filter(|p| p["paper"] == false) {
            let mut params = catalog["defaults"].clone();
            params["io"]["scan_film"] = p["positive"].clone();
            let paper = p["target_print"].as_str().unwrap_or("kodak_portra_endura");
            let r = request(serde_json::json!({"film": p["id"], "paper": paper, "params": params, "max_side": 96, "preview": true}));
            if let Err(e) = super::develop(&data(), &input, &output, &r) {
                failed.push(format!("{}: {e:#}", p["id"]));
            }
        }
        assert!(failed.is_empty(), "{failed:#?}");
    }

    #[test]
    fn borders_frame_the_photo_and_balance_solves() {
        let input = grey("nugs-border.png");
        let output = std::env::temp_dir().join("nugs-border.jpg");
        for style in ["solid", "keyline", "film", "35mm"] {
            let border = serde_json::json!({"style": style, "instagram": style == "solid",
                "options": {"width": 0.05, "line": 0.004, "color": "#ffffff", "line_color": "#000000"}});
            let report = super::develop(&data(), &input, &output, &request(serde_json::json!({"border": border}))).unwrap();
            let made = image::open(&output).unwrap();
            println!("{style}: {report}");
            assert!(made.width() > 240 && made.height() > 160, "{style}: {report}");
        }
        let (m, y) = super::balance(&data(), "kodak_portra_400", "kodak_portra_endura", "null").unwrap();
        assert!(m.is_finite() && y.is_finite());
    }

    /// Every control the app shows changes a setting the engine accepts.
    #[test]
    fn every_control_is_accepted() {
        let assets = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../android/app/src/main/assets");
        let controls: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(assets.join("controls.json")).unwrap()).unwrap();
        let defaults = serde_json::to_value(spektrafilm_core::params::RuntimeParams::default()).unwrap();
        let mut all = Vec::new();
        fn walk<'a>(v: &'a serde_json::Value, all: &mut Vec<&'a serde_json::Value>) {
            match v {
                serde_json::Value::Array(a) => a.iter().for_each(|v| walk(v, all)),
                serde_json::Value::Object(o) => {
                    if o.contains_key("path") || o.contains_key("enable") { all.push(v) }
                    o.values().for_each(|v| walk(v, all))
                }
                _ => {}
            }
        }
        walk(&controls, &mut all);
        assert!(all.len() > 80, "{}", all.len());
        for control in all {
            if let Some(path) = control["enable"].as_str() {
                let pointer = format!("/{}", path.replace('.', "/"));
                assert!(defaults.pointer(&pointer).is_some_and(|v| v.is_boolean()), "{path}");
            }
            let Some(path) = control["path"].as_str() else { continue };
            let pointer = format!("/{}", path.replace('.', "/"));
            let mut params = defaults.clone();
            let slot = params.pointer_mut(&pointer).unwrap_or_else(|| panic!("missing {path}"));
            let step = control["step"].as_f64().unwrap_or(1.0);
            let max = control["max"].as_f64().unwrap_or(0.0);
            let value = if step.fract() == 0.0 { serde_json::json!(max as i64) } else { serde_json::json!(max) };
            match control["kind"].as_str().unwrap() {
                "toggle" => *slot = serde_json::json!(true),
                "choice" => {
                    for option in control["options"].as_array().unwrap() {
                        let mut each = defaults.clone();
                        let slot = each.pointer_mut(&pointer).unwrap();
                        // `io.raw_white_balance.mode` and friends are plain strings.
                        *slot = option.clone();
                        super::parse_params(&each).unwrap_or_else(|e| panic!("{path} {option}: {e:#}"));
                    }
                    continue;
                }
                "stock" => {
                    let mut lanes = [10.0, 10.0, 10.0];
                    lanes[control["index"].as_u64().unwrap() as usize] = max;
                    *slot = serde_json::json!(lanes);
                }
                _ => *slot = value,
            }
            super::parse_params(&params).unwrap_or_else(|e| panic!("{path}: {e:#}"));
        }
    }
}
