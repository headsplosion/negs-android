//! Perspective: the warp the sliders apply, and finding the sliders that stand
//! a photo's straight lines upright (Straighten.kt mirrors the warp).
use spektrafilm_math::image::ImageBuf;

/// The lens assumed, as a focal length over the photo's long side (a phone's
/// main camera is near 26 mm on a 36 mm frame).
const FOCAL: f64 = 0.75;

/// The photo as a camera tilted by `vertical` and turned by `horizontal`
/// degrees would see it, enlarged until no empty corner shows. Positions are
/// in pixels from the photo's centre.
#[derive(Clone, Copy)]
pub struct Warp {
    pitch: (f64, f64),
    yaw: (f64, f64),
    f: f64,
    off: (f64, f64),
    /// What the enlargement divides positions by; 1 when flat.
    pub scale: f64,
}

impl Warp {
    pub fn new(width: f64, height: f64, vertical: f64, horizontal: f64) -> Warp {
        let mut w = Warp {
            pitch: vertical.to_radians().sin_cos(),
            yaw: horizontal.to_radians().sin_cos(),
            f: FOCAL * width.max(height),
            off: (0.0, 0.0),
            scale: 1.0,
        };
        w.off = w.project(0.0, 0.0);
        if vertical != 0.0 || horizontal != 0.0 {
            let inside = |scale: f64| {
                [(-1.0, -1.0), (1.0, -1.0), (1.0, 1.0), (-1.0, 1.0)].iter().all(|(sx, sy)| {
                    let (x, y) = Warp { scale, ..w }.source(sx * width / 2.0, sy * height / 2.0);
                    x.abs() <= width / 2.0 + 1e-6 && y.abs() <= height / 2.0 + 1e-6
                })
            };
            let (mut low, mut high) = (0.0, 1.0);
            for _ in 0..40 {
                let mid = (low + high) / 2.0;
                if inside(mid) { low = mid } else { high = mid }
            }
            w.scale = low;
        }
        w
    }

    fn project(&self, x: f64, y: f64) -> (f64, f64) {
        let ((sp, cp), (sy, cy), f) = (self.pitch, self.yaw, self.f);
        let (y1, z1) = (y * cp - f * sp, y * sp + f * cp);
        let (x2, z2) = (x * cy + z1 * sy, -x * sy + z1 * cy);
        (f * x2 / z2, f * y1 / z2)
    }

    /// Where in the photo the warped picture's (x, y) comes from.
    pub fn source(&self, x: f64, y: f64) -> (f64, f64) {
        let (px, py) = self.project(x * self.scale, y * self.scale);
        (px - self.off.0, py - self.off.1)
    }

    /// Where the photo's (x, y) lands in the warped picture.
    pub fn place(&self, x: f64, y: f64) -> (f64, f64) {
        let ((sp, cp), (sy, cy), f) = (self.pitch, self.yaw, self.f);
        let (x, y, z) = (x + self.off.0, y + self.off.1, f);
        let (x1, z1) = (x * cy - z * sy, x * sy + z * cy);
        let (y2, z2) = (y * cp + z1 * sp, -y * sp + z1 * cp);
        (f * x1 / z2 / self.scale, f * y2 / z2 / self.scale)
    }
}

/// A straight edge, its ends in pixels from the centre of a `width` × `height` photo.
#[derive(Clone, Copy)]
pub struct Segment {
    pub a: (f64, f64),
    pub b: (f64, f64),
}

impl Segment {
    fn length(&self) -> f64 {
        (self.b.0 - self.a.0).hypot(self.b.1 - self.a.1)
    }
}

/// The straight edges of `image`: neighbouring pixels whose gradients agree,
/// kept when together they are long and thin.
pub fn segments(image: &ImageBuf) -> Vec<Segment> {
    let (w, h) = (image.width as usize, image.height as usize);
    if w < 8 || h < 8 {
        return Vec::new();
    }
    let grey: Vec<f32> = image.data.chunks_exact(3)
        .map(|p| {
            let l = 0.2 * spektrafilm_math::precision::to_f32(p[0]) + 0.7 * spektrafilm_math::precision::to_f32(p[1])
                + 0.1 * spektrafilm_math::precision::to_f32(p[2]);
            l.max(0.0).powf(1.0 / 2.2)
        })
        .collect();
    // A light blur, so that grain and stair steps don't break edges up.
    let mut soft = grey.clone();
    for y in 1..h - 1 {
        for x in 1..w - 1 {
            let at = |dx: usize, dy: usize| grey[(y + dy - 1) * w + x + dx - 1];
            soft[y * w + x] = (4.0 * at(1, 1) + 2.0 * (at(0, 1) + at(2, 1) + at(1, 0) + at(1, 2))
                + at(0, 0) + at(2, 0) + at(0, 2) + at(2, 2)) / 16.0;
        }
    }
    let mut gradient = vec![(0.0f32, 0.0f32); w * h];
    for y in 1..h - 1 {
        for x in 1..w - 1 {
            let at = |dx: usize, dy: usize| soft[(y + dy - 1) * w + x + dx - 1];
            let gx = at(2, 0) + 2.0 * at(2, 1) + at(2, 2) - at(0, 0) - 2.0 * at(0, 1) - at(0, 2);
            let gy = at(0, 2) + 2.0 * at(1, 2) + at(2, 2) - at(0, 0) - 2.0 * at(1, 0) - at(2, 0);
            gradient[y * w + x] = (gx, gy);
        }
    }
    let strength: Vec<f32> = gradient.iter().map(|g| g.0.hypot(g.1)).collect();
    // Thin each edge to its ridge.
    let ridge: Vec<bool> = (0..w * h)
        .map(|i| {
            let (x, y) = (i % w, i / w);
            if x < 2 || y < 2 || x >= w - 2 || y >= h - 2 || strength[i] < 0.12 {
                return false;
            }
            let (gx, gy) = gradient[i];
            let step = if gx.abs() >= gy.abs() { 1 } else { w };
            strength[i] >= strength[i - step] && strength[i] > strength[i + step]
        })
        .collect();
    let mut used = vec![false; w * h];
    let long = w.max(h) as f64;
    let mut found = Vec::new();
    let mut stack = Vec::new();
    let mut points = Vec::new();
    for seed in 0..w * h {
        if !ridge[seed] || used[seed] {
            continue;
        }
        let (gx, gy) = gradient[seed];
        let (dx, dy) = (gx / strength[seed], gy / strength[seed]);
        used[seed] = true;
        stack.push(seed);
        points.clear();
        while let Some(i) = stack.pop() {
            let (x, y) = ((i % w) as isize, (i / w) as isize);
            points.push((x as f64, y as f64));
            // Reach two pixels, to step over small breaks.
            for ny in y - 2..=y + 2 {
                for nx in x - 2..=x + 2 {
                    if nx < 0 || ny < 0 || nx >= w as isize || ny >= h as isize {
                        continue;
                    }
                    let j = ny as usize * w + nx as usize;
                    // Within 12 degrees of the seed's direction.
                    if ridge[j] && !used[j] && (gradient[j].0 * dx + gradient[j].1 * dy) / strength[j] > 0.978 {
                        used[j] = true;
                        stack.push(j);
                    }
                }
            }
        }
        if (points.len() as f64) < long * 0.04 {
            continue;
        }
        let n = points.len() as f64;
        let (mx, my) = points.iter().fold((0.0, 0.0), |s, p| (s.0 + p.0 / n, s.1 + p.1 / n));
        let (xx, xy, yy) = points.iter().fold((0.0, 0.0, 0.0), |s, p| {
            let (x, y) = (p.0 - mx, p.1 - my);
            (s.0 + x * x / n, s.1 + x * y / n, s.2 + y * y / n)
        });
        let angle = 0.5 * (2.0 * xy).atan2(xx - yy);
        let (sin, cos) = angle.sin_cos();
        let across = xx * sin * sin - 2.0 * xy * sin * cos + yy * cos * cos;
        let (low, high) = points.iter().fold((f64::MAX, f64::MIN), |s, p| {
            let t = (p.0 - mx) * cos + (p.1 - my) * sin;
            (s.0.min(t), s.1.max(t))
        });
        if high - low < long * 0.045 || across.sqrt() > 1.5 {
            continue;
        }
        let end = |t: f64| (mx + t * cos - (w as f64 - 1.0) / 2.0, my + t * sin - (h as f64 - 1.0) / 2.0);
        found.push(Segment { a: end(low), b: end(high) });
    }
    found
}

/// What straightening may change.
#[derive(Clone, Copy, PartialEq)]
pub enum Mode {
    /// Rotation only.
    Level,
    /// Rotation and vertical perspective.
    Vertical,
    /// Rotation and both perspectives.
    Full,
}

/// The rotation (clockwise degrees, near the quarter turn `base`) and the
/// vertical and horizontal perspective that stand the most line length
/// upright or flat. None without enough straight lines.
pub fn solve(lines: &[Segment], width: f64, height: f64, base: f64, mode: Mode) -> Option<(f64, f64, f64)> {
    let turned = |p: (f64, f64), degrees: f64| {
        let (sin, cos) = degrees.to_radians().sin_cos();
        (p.0 * cos - p.1 * sin, p.0 * sin + p.1 * cos)
    };
    // Which lines are meant to be upright, and which flat, going by how they
    // lie now. Flat ones often recede into the distance, so they count less.
    let aimed: Vec<(Segment, bool, f64)> = lines.iter()
        .filter_map(|&s| {
            let (a, b) = (turned(s.a, base), turned(s.b, base));
            let slope = (b.1 - a.1).abs().atan2((b.0 - a.0).abs()).to_degrees();
            if slope > 65.0 { Some((s, true, s.length())) }
            else if slope < 20.0 { Some((s, false, s.length() * 0.5)) }
            else { None }
        })
        .collect();
    let total: f64 = aimed.iter().map(|a| a.2).sum();
    if aimed.len() < 3 || total < width.max(height) * 0.3 {
        return None;
    }
    let score = |v: [f64; 3], sigma: f64| {
        let [roll, vertical, horizontal] = v;
        let warp = Warp::new(width, height, 0.0, 0.0);
        let warp = Warp {
            pitch: vertical.to_radians().sin_cos(),
            yaw: horizontal.to_radians().sin_cos(),
            ..warp
        };
        let off = warp.project(0.0, 0.0);
        let warp = Warp { off, ..warp };
        let fit: f64 = aimed.iter()
            .map(|(s, upright, weight)| {
                let a = turned(warp.place(s.a.0, s.a.1), base + roll);
                let b = turned(warp.place(s.b.0, s.b.1), base + roll);
                let (dx, dy) = ((b.0 - a.0).abs(), (b.1 - a.1).abs());
                let error = if *upright { dx.atan2(dy) } else { dy.atan2(dx) }.to_degrees();
                weight * (-(error / sigma).powi(2)).exp()
            })
            .sum();
        // Prefer the gentler correction when the lines don't say.
        fit / total - 0.00004 * (vertical * vertical + horizontal * horizontal) - 0.00002 * roll * roll
    };
    let limits = [
        15.0,
        if mode == Mode::Level { 0.0 } else { 30.0 },
        if mode == Mode::Full { 30.0 } else { 0.0 },
    ];
    // A coarse look everywhere, then closer and sharper.
    let steps = |limit: f64| {
        let n = (limit / 2.5) as i32;
        (-n..=n).map(move |i| i as f64 * 2.5)
    };
    let mut best = ([0.0; 3], score([0.0; 3], 3.0));
    for roll in steps(limits[0]) {
        for vertical in steps(limits[1]) {
            for horizontal in steps(limits[2]) {
                let v = [roll, vertical, horizontal];
                let s = score(v, 3.0);
                if s > best.1 {
                    best = (v, s);
                }
            }
        }
    }
    let mut v = best.0;
    for (mut step, sigma) in [(1.25, 2.0), (0.6, 1.0), (0.3, 0.6)] {
        let mut now = score(v, sigma);
        while step > 0.04 {
            let mut moved = false;
            for axis in 0..3 {
                for sign in [-1.0, 1.0] {
                    let mut next = v;
                    next[axis] = (next[axis] + sign * step).clamp(-limits[axis], limits[axis]);
                    let s = score(next, sigma);
                    if s > now + 1e-9 {
                        (v, now, moved) = (next, s, true);
                    }
                }
            }
            if !moved {
                step /= 2.0;
            }
        }
    }
    Some((base + v[0], v[1], v[2]))
}
