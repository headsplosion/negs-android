//! Android entry point for the film engine: one call that develops a photo file.
use anyhow::{Context, Result};
use jni::{JNIEnv, objects::{JClass, JString}, sys::{jint, jstring}};
use spektrafilm_core::{params::RuntimeParams, pipeline::Pipeline, preview, profile};
use spektrafilm_gpu::ComputeBackend;
use spektrafilm_io::encoding::Encoding;
use std::{path::Path, sync::OnceLock, time::Instant};

static BACKEND: OnceLock<Box<dyn ComputeBackend>> = OnceLock::new();

/// Develop `input` (JPEG, PNG, TIFF or RAW) on `film`/`paper` and write a JPEG
/// to `output`, at most `max_side` pixels long. Returns a short report.
pub fn develop(data: &Path, input: &Path, output: &Path, film: &str, paper: &str, max_side: u32) -> Result<String> {
    let started = Instant::now();
    let loaded = spektrafilm_io::load_image_colour(
        input,
        spektrafilm_io::Formats::Any,
        Default::default(),
        Default::default(),
        &Default::default(),
    )?;
    let image = loaded.image;
    let scale = (max_side as f64 / image.width.max(image.height) as f64).min(1.0);
    let size = (
        ((image.width as f64 * scale).round() as u32).max(1),
        ((image.height as f64 * scale).round() as u32).max(1),
    );
    let image = if scale < 1.0 { preview::downsample_area(&image, size) } else { image };

    let mut params = RuntimeParams::default();
    params.io.apply_input_colour(&loaded.colour);
    let film_profile = profile::load_profile_by_name(data, film)?;
    let paper_profile = profile::load_profile_by_name(data, paper)?;
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
) -> jstring {
    let mut text = |s: &JString<'local>| env.get_string(s).map(String::from).unwrap_or_default();
    let (data, cache, input, output, film, paper) =
        (text(&data), text(&cache), text(&input), text(&output), text(&film), text(&paper));
    // The GPU pipeline cache needs a writable directory.
    static ENV: std::sync::Once = std::sync::Once::new();
    // SAFETY: set once, before the engine starts any thread of its own.
    ENV.call_once(|| unsafe { std::env::set_var("XDG_CACHE_HOME", &cache) });
    // A panic must not cross into Java.
    let result = std::panic::catch_unwind(|| {
        develop(Path::new(&data), Path::new(&input), Path::new(&output), &film, &paper, max_side.max(64) as u32)
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
            let report = super::develop(&data, &input, &output, film, paper, 200).unwrap();
            let made = image::open(&output).unwrap();
            assert_eq!((made.width(), made.height()), (200, 133), "{report}");
            println!("{film}: {report}");
        }
    }
}
