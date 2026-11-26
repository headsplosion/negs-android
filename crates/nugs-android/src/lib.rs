//! Android entry point for the film engine: one call that develops a photo file.
use anyhow::{Context, Result};
use jni::{JNIEnv, objects::{JClass, JString}, sys::{jfloat, jint, jstring}};
use spektrafilm_core::{params::RuntimeParams, pipeline::Pipeline, preview, profile};
use spektrafilm_gpu::ComputeBackend;
use spektrafilm_io::encoding::Encoding;
use spektrafilm_math::image::ImageBuf;
use std::{path::Path, sync::OnceLock, time::Instant};

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

/// Develop `input` (JPEG, PNG, TIFF or RAW) on `film`/`paper` and write a JPEG
/// to `output`, at most `max_side` pixels long. Returns a short report.
pub fn develop(data: &Path, input: &Path, output: &Path, film: &str, paper: &str, max_side: u32, crop: Crop, grain: f64) -> Result<String> {
    let started = Instant::now();
    let loaded = spektrafilm_io::load_image_colour(
        input,
        spektrafilm_io::Formats::Any,
        Default::default(),
        Default::default(),
        &Default::default(),
    )?;
    let image = loaded.image;
    let image = cut(image, crop, max_side);

    let mut params = RuntimeParams::default();
    params.io.apply_input_colour(&loaded.colour);
    let film_profile = profile::load_profile_by_name(data, film)?;
    let paper_profile = profile::load_profile_by_name(data, paper)?;
    // Grain amount scales the stock's own granularity: 1 is the film as made.
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
    let (width, height, rgba) = preview::display_rgba(&developed, usize::MAX, encoding);
    let rgb: Vec<u8> = rgba.chunks_exact(4).flat_map(|p| [p[0], p[1], p[2]]).collect();
    let file = std::fs::File::create(output).context("Creating the output file")?;
    let mut writer = std::io::BufWriter::new(file);
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut writer, 95)
        .encode(&rgb, width as u32, height as u32, image::ExtendedColorType::Rgb8)?;
    std::io::Write::flush(&mut writer)?;
    Ok(format!("{width}×{height} · {:.1} s · {used}", started.elapsed().as_secs_f64()))
}

/// Kotlin: `Engine.develop(...)`. Returns "OK <report>" or "ERR <reason>".
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_headsplosion_nugs_Engine_develop<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    data: JString<'local>,
    cache: JString<'local>,
    input: JString<'local>,
    output: JString<'local>,
    film: JString<'local>,
    paper: JString<'local>,
    max_side: jint,
    ratio: jfloat,
    zoom: jfloat,
    angle: jfloat,
    x: jfloat,
    y: jfloat,
    grain: jfloat,
) -> jstring {
    let mut text = |s: &JString<'local>| env.get_string(s).map(String::from).unwrap_or_default();
    let (data, cache, input, output, film, paper) =
        (text(&data), text(&cache), text(&input), text(&output), text(&film), text(&paper));
    let crop = Crop { ratio: ratio as f64, zoom: zoom as f64, angle: angle as f64, x: x as f64, y: y as f64 };
    // The GPU pipeline cache needs a writable directory.
    static ENV: std::sync::Once = std::sync::Once::new();
    // SAFETY: set once, before the engine starts any thread of its own.
    ENV.call_once(|| unsafe { std::env::set_var("XDG_CACHE_HOME", &cache) });
    // A panic must not cross into Java.
    let result = std::panic::catch_unwind(|| {
        develop(Path::new(&data), Path::new(&input), Path::new(&output), &film, &paper, max_side.max(64) as u32, crop, grain as f64)
    });
    let reply = match result {
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

    #[test]
    fn grain_amount_changes_the_picture() {
        let data = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
        let input = std::env::temp_dir().join("nugs-grey.png");
        image::RgbImage::from_pixel(240, 160, image::Rgb([128; 3])).save(&input).unwrap();
        let noise = |grain: f64| {
            let output = std::env::temp_dir().join(format!("nugs-grain-{grain}.jpg"));
            super::develop(&data, &input, &output, "kodak_portra_400", "kodak_portra_endura", 240, Default::default(), grain).unwrap();
            let pixels: Vec<f64> = image::open(output).unwrap().to_luma8().pixels().map(|p| p.0[0] as f64).collect();
            let mean = pixels.iter().sum::<f64>() / pixels.len() as f64;
            (pixels.iter().map(|v| (v - mean).powi(2)).sum::<f64>() / pixels.len() as f64).sqrt()
        };
        let (none, stock, double) = (noise(0.0), noise(1.0), noise(2.0));
        println!("noise: {none:.2} {stock:.2} {double:.2}");
        assert!(none < stock && stock < double);
    }

    #[test]
    fn develops_each_stock() {
        let data = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
        let dir = std::env::temp_dir();
        let input = dir.join("nugs-in.png");
        image::RgbImage::from_fn(240, 160, |x, y| image::Rgb([x as u8, y as u8, 128])).save(&input).unwrap();
        for (film, paper) in [
            ("kodak_portra_400", "kodak_portra_endura"),
            ("kodak_gold_200", "kodak_portra_endura"),
            ("cinestill_800t", "kodak_portra_endura"),
            ("ilford_hp5_plus", "ilford_mgrc_deluxe_grade2"),
        ] {
            let output = dir.join(format!("nugs-{film}.jpg"));
            let report = super::develop(&data, &input, &output, film, paper, 200, Default::default(), 1.0).unwrap();
            let made = image::open(&output).unwrap();
            assert_eq!((made.width(), made.height()), (200, 133), "{report}");
            println!("{film}: {report}");
        }
    }
}
