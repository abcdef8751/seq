use crate::eq_types::{BandKind, EqConfig};
use pipewire as pw;
use pw::context::ContextRc;
use pw::core::CoreRc;
use pw::main_loop::MainLoopRc;
use pw::loop_::Timeout;
use pw::properties::properties;
use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::ffi::{c_void, CString};
use std::process::{Command, Stdio};
use std::rc::Rc;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{mpsc, Mutex};
use std::time::Duration;

enum Msg {
    Apply(EqConfig),
    Clear,
}

pub struct PipeWireEq {
    tx: mpsc::Sender<Msg>,
}

impl PipeWireEq {
    pub fn start() -> Self {
        let (tx, rx) = mpsc::channel();
        std::thread::spawn(move || run_loop(rx));
        Self { tx }
    }

    pub fn apply(&self, cfg: EqConfig) {
        let _ = self.tx.send(Msg::Apply(cfg));
    }

    pub fn clear(&self) {
        let _ = self.tx.send(Msg::Clear);
    }
}

const DEFAULT_RATE: f32 = 48000.0;

// ---- DSP (RBJ peaking biquads) ----

#[derive(Clone, Copy)]
struct Biquad {
    b0: f32,
    b1: f32,
    b2: f32,
    a1: f32,
    a2: f32,
}

#[derive(Clone)]
struct Band {
    coeff: Biquad,
    active: bool,
    lx1: f32,
    lx2: f32,
    ly1: f32,
    ly2: f32,
    rx1: f32,
    rx2: f32,
    ry1: f32,
    ry2: f32,
}

#[derive(Clone)]
struct EqState {
    preamp: f32,
    bands: Vec<Band>,
}

fn band_coeffs(kind: BandKind, freq: f64, q: f64, gain_db: f64, rate: f32) -> Biquad {
    let a = 10f64.powf(gain_db / 40.0);
    let w0 = 2.0 * std::f64::consts::PI * freq / f64::from(rate);
    let cosw0 = w0.cos();
    match kind {
        BandKind::Pk => {
            let alpha = w0.sin() / (2.0 * q.max(1e-3));
            let a0 = 1.0 + alpha / a;
            Biquad {
                b0: ((1.0 + alpha * a) / a0) as f32,
                b1: ((-2.0 * cosw0) / a0) as f32,
                b2: ((1.0 - alpha * a) / a0) as f32,
                a1: ((-2.0 * cosw0) / a0) as f32,
                a2: ((1.0 - alpha / a) / a0) as f32,
            }
        }
        // Q-based shelves (RBJ "LSQ/HSQ"): alpha derived from Q instead of a
        // shelf-slope. The sqrt argument must stay >= 0, so clamp it (very high
        // Q at large gains would otherwise produce NaN).
        BandKind::Lsq | BandKind::Hsq => {
            let inner = ((a + 1.0 / a) * (1.0 / q.max(1e-3) - 1.0) + 2.0).max(0.0);
            let alpha = w0.sin() / 2.0 * inner.sqrt();
            let sa = a.sqrt();
            let (b0, b1, b2, a0, a1, a2) = if kind == BandKind::Lsq {
                (
                    a * ((a + 1.0) - (a - 1.0) * cosw0 + 2.0 * sa * alpha),
                    2.0 * a * ((a - 1.0) - (a + 1.0) * cosw0),
                    a * ((a + 1.0) - (a - 1.0) * cosw0 - 2.0 * sa * alpha),
                    (a + 1.0) + (a - 1.0) * cosw0 + 2.0 * sa * alpha,
                    -2.0 * ((a - 1.0) + (a + 1.0) * cosw0),
                    (a + 1.0) + (a - 1.0) * cosw0 - 2.0 * sa * alpha,
                )
            } else {
                (
                    a * ((a + 1.0) + (a - 1.0) * cosw0 + 2.0 * sa * alpha),
                    -2.0 * a * ((a - 1.0) + (a + 1.0) * cosw0),
                    a * ((a + 1.0) + (a - 1.0) * cosw0 - 2.0 * sa * alpha),
                    (a + 1.0) - (a - 1.0) * cosw0 + 2.0 * sa * alpha,
                    2.0 * ((a - 1.0) - (a + 1.0) * cosw0),
                    (a + 1.0) - (a - 1.0) * cosw0 - 2.0 * sa * alpha,
                )
            };
            Biquad {
                b0: (b0 / a0) as f32,
                b1: (b1 / a0) as f32,
                b2: (b2 / a0) as f32,
                a1: (a1 / a0) as f32,
                a2: (a2 / a0) as f32,
            }
        }
        BandKind::Off => Biquad {
            b0: 1.0,
            b1: 0.0,
            b2: 0.0,
            a1: 0.0,
            a2: 0.0,
        },
    }
}

fn eq_state_from_config(cfg: &EqConfig, rate: f32) -> EqState {
    let bands = cfg
        .bands
        .iter()
        .map(|b| Band {
            coeff: band_coeffs(b.kind.clone(), b.center_freq, b.q, b.gain, rate),
            active: !cfg.bypassed && b.kind != BandKind::Off && b.gain.abs() > 0.01,
            lx1: 0.0,
            lx2: 0.0,
            ly1: 0.0,
            ly2: 0.0,
            rx1: 0.0,
            rx2: 0.0,
            ry1: 0.0,
            ry2: 0.0,
        })
        .collect();
    EqState {
        preamp: if cfg.bypassed {
            1.0
        } else {
            10f32.powf(cfg.preamp as f32 / 20.0)
        },
        bands,
    }
}

fn process_channel(buf: &mut [f32], bands: &mut [Band], right: bool) {
    for s in buf.iter_mut() {
        let mut x = *s;
        for band in bands.iter_mut() {
            if !band.active {
                continue;
            }
            let c = band.coeff;
            let (x1, x2, y1, y2) = if right {
                (band.rx1, band.rx2, band.ry1, band.ry2)
            } else {
                (band.lx1, band.lx2, band.ly1, band.ly2)
            };
            let y = c.b0 * x + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2;
            if right {
                band.rx2 = band.rx1;
                band.rx1 = x;
                band.ry2 = band.ry1;
                band.ry1 = y;
            } else {
                band.lx2 = band.lx1;
                band.lx1 = x;
                band.ly2 = band.ly1;
                band.ly1 = y;
            }
            x = y;
        }
        *s = x;
    }
}

struct FilterData {
    in_l: *mut c_void,
    in_r: *mut c_void,
    out_l: *mut c_void,
    out_r: *mut c_void,
    state: Mutex<EqState>,
    config: Mutex<Option<EqConfig>>,
    rate: AtomicU32,
}

unsafe extern "C" fn process_cb(data: *mut c_void, position: *mut pw::spa::sys::spa_io_position) {
    let pos = unsafe { &*position };
    let n = pos.clock.duration;
    let rate = pos.clock.rate.denom;
    if n == 0 || rate == 0 {
        return;
    }
    let d = unsafe { &mut *(data as *mut FilterData) };

    if d.rate.load(Ordering::Relaxed) != rate {
        let cfg = d.config.lock().map(|g| g.clone()).unwrap_or(None);
        if let Some(cfg) = cfg {
            if let Ok(mut st) = d.state.lock() {
                *st = eq_state_from_config(&cfg, rate as f32);
            }
        }
        d.rate.store(rate, Ordering::Relaxed);
    }

    let mut st = match d.state.lock() {
        Ok(g) => g,
        Err(_) => return,
    };

    let in_l = pw::sys::pw_filter_get_dsp_buffer(d.in_l, n as u32) as *mut f32;
    let in_r = pw::sys::pw_filter_get_dsp_buffer(d.in_r, n as u32) as *mut f32;
    let out_l = pw::sys::pw_filter_get_dsp_buffer(d.out_l, n as u32) as *mut f32;
    let out_r = pw::sys::pw_filter_get_dsp_buffer(d.out_r, n as u32) as *mut f32;
    if in_l.is_null() || in_r.is_null() {
        return;
    }

    // If output buffers are unavailable, fall back to processing the input
    // buffers in place (the graph may share the buffers).
    let (bl, br) = if !out_l.is_null() && !out_r.is_null() {
        if out_l != in_l {
            std::ptr::copy_nonoverlapping(in_l, out_l, n as usize);
        }
        if out_r != in_r {
            std::ptr::copy_nonoverlapping(in_r, out_r, n as usize);
        }
        (out_l, out_r)
    } else {
        (in_l, in_r)
    };

    let len = n as usize;
    process_channel(std::slice::from_raw_parts_mut(bl, len), &mut st.bands, false);
    process_channel(std::slice::from_raw_parts_mut(br, len), &mut st.bands, true);

    let p = st.preamp;
    for i in 0..len {
        *bl.add(i) *= p;
        *br.add(i) *= p;
    }
}

/// Create the invisible gapless DSP filter node (`seq-eq-dsp`).
unsafe fn create_dsp_filter(
    core: &CoreRc,
) -> Result<
    (
        *mut pw::sys::pw_filter,
        *mut FilterData,
        Box<pw::spa::sys::spa_hook>,
        Box<pw::sys::pw_filter_events>,
    ),
    String,
> {
    let props = CString::new(
        "media.type=Audio media.category=Duplex media.role=DSP node.name=seq-eq-dsp node.description=\"seq EQ DSP\" node.group=seq-eq audio.channels=2 audio.position=\"[ FL FR ]\"",
    )
    .map_err(|e| e.to_string())?;
    let props = pw::sys::pw_properties_new_string(props.as_ptr());
    if props.is_null() {
        return Err("failed to create dsp props".into());
    }

    let name = CString::new("seq-eq-dsp").map_err(|e| e.to_string())?;
    let filter = pw::sys::pw_filter_new(core.as_raw_ptr(), name.as_ptr(), props);
    if filter.is_null() {
        return Err("pw_filter_new failed".into());
    }

    let data = Box::into_raw(Box::new(FilterData {
        in_l: std::ptr::null_mut(),
        in_r: std::ptr::null_mut(),
        out_l: std::ptr::null_mut(),
        out_r: std::ptr::null_mut(),
        state: Mutex::new(eq_state_from_config(
            &EqConfig {
                preamp: 0.0,
                bands: vec![],
                auto_preamp: false,
                bypassed: false,
            },
            DEFAULT_RATE,
        )),
        config: Mutex::new(None),
        rate: AtomicU32::new(0),
    }));

    let mut events = Box::new(std::mem::zeroed::<pw::sys::pw_filter_events>());
    events.version = pw::sys::PW_VERSION_FILTER_EVENTS;
    events.process = Some(process_cb);

    let mut hook = Box::new(std::mem::zeroed::<pw::spa::sys::spa_hook>());
    pw::sys::pw_filter_add_listener(filter, &mut *hook, events.as_ref(), data as *mut c_void);

    let add_port = |direction: pw::spa::sys::spa_direction, name: &str, channel: &str| -> Result<*mut c_void, String> {
        let props_str = format!(
            "format.dsp=\"32 bit float mono audio\" port.name={name} audio.channel={channel}"
        );
        let props_c = CString::new(props_str).map_err(|e| e.to_string())?;
        let props = pw::sys::pw_properties_new_string(props_c.as_ptr());
        if props.is_null() {
            return Err("failed to create port props".into());
        }
        let port = pw::sys::pw_filter_add_port(
            filter,
            direction,
            pw::sys::pw_filter_port_flags_PW_FILTER_PORT_FLAG_MAP_BUFFERS,
            0,
            props,
            std::ptr::null_mut::<*const pw::spa::sys::spa_pod>(),
            0,
        );
        if port.is_null() {
            return Err(format!("failed to add port {name}"));
        }
        Ok(port)
    };

    let in_l = add_port(pw::spa::sys::SPA_DIRECTION_INPUT, "input_FL", "FL")?;
    let in_r = add_port(pw::spa::sys::SPA_DIRECTION_INPUT, "input_FR", "FR")?;
    let out_l = add_port(pw::spa::sys::SPA_DIRECTION_OUTPUT, "output_FL", "FL")?;
    let out_r = add_port(pw::spa::sys::SPA_DIRECTION_OUTPUT, "output_FR", "FR")?;

    {
        let d = unsafe { &mut *data };
        d.in_l = in_l;
        d.in_r = in_r;
        d.out_l = out_l;
        d.out_r = out_r;
    }

    let res = pw::sys::pw_filter_connect(
        filter,
        pw::sys::pw_filter_flags_PW_FILTER_FLAG_RT_PROCESS,
        std::ptr::null_mut::<*const pw::spa::sys::spa_pod>(),
        0,
    );
    if res < 0 {
        return Err(format!("pw_filter_connect failed: {res}"));
    }
    pw::sys::pw_filter_set_active(filter, true);

    Ok((filter, data, hook, events))
}

/// Create the visible virtual sink (`seq-eq`) via `support.null-audio-sink`.
unsafe fn create_sink(core: &CoreRc) -> Result<pw::node::Node, String> {
    let props = properties! {
        "factory.name" => "support.null-audio-sink",
        "node.name" => "seq-eq",
        "node.description" => "seq EQ",
        "media.class" => "Audio/Sink",
        "audio.channels" => "2",
        "audio.position" => "[ FL FR ]",
        "priority.session" => "0",
        "priority.driver" => "0",
        "node.virtual" => "true",
        "node.group" => "seq-eq-group",
        "node.passive" => "false",
    };
    core.create_object::<pw::node::Node>("adapter", &props)
        .map_err(|e| e.to_string())
}

fn run_loop(rx: mpsc::Receiver<Msg>) {
    pw::init();
    let Ok(mainloop) = MainLoopRc::new(None) else {
        eprintln!("[seq-eq] failed to create mainloop");
        return;
    };
    let Ok(context) = ContextRc::new(&mainloop, None) else {
        eprintln!("[seq-eq] failed to create context");
        return;
    };
    let Ok(core) = context.connect_rc(None) else {
        eprintln!("[seq-eq] failed to connect to pipewire");
        return;
    };

    let mut sink: Option<pw::node::Node> = None;
    let mut dsp_filter: Option<*mut pw::sys::pw_filter> = None;
    let mut dsp_data: Option<*mut FilterData> = None;
    let mut _hook: Option<Box<pw::spa::sys::spa_hook>> = None;
    let mut _events: Option<Box<pw::sys::pw_filter_events>> = None;
    let mut sink_id: Option<u32> = None;
    let mut sink_serial: Option<u64> = None;
    let mut dsp_id: Option<u32> = None;
    let mut user_device: Option<(u32, String)> = None;
    let mut linked_device: Option<u32> = None;
    let mut setup_done = false;
    let mut tick: u32 = 0;

    loop {
        let _ = mainloop
            .loop_()
            .iterate(Timeout::Finite(Duration::from_millis(100)));

        match rx.try_recv() {
            Ok(Msg::Apply(cfg)) => {
                if !setup_done {
                    match setup(&mainloop, &core, &mut sink, &mut dsp_filter, &mut dsp_data, &mut _hook, &mut _events, &mut sink_id, &mut sink_serial, &mut dsp_id, &mut user_device) {
                        Ok(()) => setup_done = true,
                        Err(e) => {
                            eprintln!("[seq-eq] setup failed: {e}");
                            continue;
                        }
                    }
                }
                if let Some(d) = dsp_data {
                    unsafe {
                        (*d).config.lock().unwrap().replace(cfg.clone());
                        *(*d).state.lock().unwrap() = eq_state_from_config(&cfg, DEFAULT_RATE);
                    }
                }
                eprintln!("[seq-eq] applied EQ ({} bands)", cfg.bands.len());
            }
            Ok(Msg::Clear) => {
                if let Some(d) = dsp_data.take() {
                    unsafe { drop(Box::from_raw(d)) };
                }
                if let Some(f) = dsp_filter.take() {
                    unsafe { pw::sys::pw_filter_destroy(f) };
                }
                if let Some(s) = sink.take() {
                    let _ = core.destroy_object(s);
                }
                _hook = None;
                _events = None;
                setup_done = false;
                sink_id = None;
                sink_serial = None;
                dsp_id = None;
                linked_device = None;
                if let Some(nodes) = collect_nodes(&mainloop, &core) {
                    // Clear the target metadata we set on output streams.
                    for (name, (id, class)) in &nodes {
                        if class == "Stream/Output/Audio" && !name.starts_with("seq") {
                            run_cmd("pw-metadata", &["-d", "-n", "default", &id.to_string(), "target.object"]);
                            run_cmd("pw-metadata", &["-d", "-n", "default", &id.to_string(), "target.node"]);
                        }
                    }
                    for name in ["seq-eq", "seq-eq-dsp"] {
                        if let Some(id) = nodes.get(name).map(|n| n.0) {
                            run_cmd("pw-cli", &["destroy", &id.to_string()]);
                        }
                    }
                }
                do_roundtrip(&mainloop, &core);
                eprintln!("[seq-eq] cleared EQ");
            }
            Err(_) => {}
        }

        tick = tick.wrapping_add(1);
        if tick % 10 == 0 && setup_done {
            monitor(
                &mainloop,
                &core,
                sink_id,
                sink_serial,
                dsp_id,
                user_device.as_ref().map(|(id, _)| *id),
                &mut linked_device,
            );
        }
    }
}

fn setup(
    mainloop: &MainLoopRc,
    core: &CoreRc,
    sink: &mut Option<pw::node::Node>,
    dsp_filter: &mut Option<*mut pw::sys::pw_filter>,
    dsp_data: &mut Option<*mut FilterData>,
    hook: &mut Option<Box<pw::spa::sys::spa_hook>>,
    events: &mut Option<Box<pw::sys::pw_filter_events>>,
    sink_id: &mut Option<u32>,
    sink_serial: &mut Option<u64>,
    dsp_id: &mut Option<u32>,
    user_device: &mut Option<(u32, String)>,
) -> Result<(), String> {
    unsafe {
        if sink.is_none() {
            *sink = Some(create_sink(core)?);
        }
        if dsp_filter.is_none() {
            let (f, d, h, ev) = create_dsp_filter(core)?;
            *dsp_filter = Some(f);
            *dsp_data = Some(d);
            *hook = Some(h);
            *events = Some(ev);
        }
    }

    do_roundtrip(mainloop, core);

    let nodes = wait_for_nodes(mainloop, core, &["seq-eq", "seq-eq-dsp"]).ok_or("nodes not found")?;

    // Capture the user's current playback device so the DSP output can follow
    // it (the user's default output is left untouched).
    if user_device.is_none() {
        *user_device = find_physical_sink(&nodes);
    }

    // sink monitor -> dsp input (static; the dsp->device link is managed by
    // the monitor, which follows the user's current playback device).
    let dsp = nodes.get("seq-eq-dsp").map(|n| n.0).ok_or("dsp node missing")?;
    if sink_id.is_none() {
        *sink_id = nodes.get("seq-eq").map(|n| n.0);
        *dsp_id = Some(dsp);
    }
    if sink_serial.is_none() {
        if let Some(id) = *sink_id {
            *sink_serial = node_serial(mainloop, core, id);
        }
    }
    link_channels(mainloop, core, "seq-eq", "monitor_", dsp, "input_");

    Ok(())
}

fn link_channels(
    mainloop: &MainLoopRc,
    core: &CoreRc,
    from_name: &str,
    from_prefix: &str,
    to_id: u32,
    to_prefix: &str,
) {
    let Some(nodes) = collect_nodes(mainloop, core) else {
        return;
    };
    let Some(from) = nodes.get(from_name).map(|n| n.0) else {
        return;
    };
    let Some(ports) = collect_ports(mainloop, core) else {
        return;
    };
    let outputs: Vec<(String, u32)> = ports
        .iter()
        .filter(|(n, _, dir, name)| {
            *n == from && dir == "out" && name.starts_with(from_prefix)
        })
        .map(|(_, port, _, name)| (name.trim_start_matches(from_prefix).to_string(), *port))
        .collect();
    let inputs: HashMap<String, u32> = ports
        .iter()
        .filter(|(n, _, dir, name)| {
            *n == to_id && dir == "in" && name.starts_with(to_prefix)
        })
        .map(|(_, port, _, name)| (name.trim_start_matches(to_prefix).to_string(), *port))
        .collect();

    for (channel, out_port) in &outputs {
        if let Some(in_port) = inputs.get(channel) {
            run_cmd(
                "pw-cli",
                &[
                    "create-link",
                    &from.to_string(),
                    &out_port.to_string(),
                    &to_id.to_string(),
                    &in_port.to_string(),
                    "object.linger=1",
                ],
            );
        }
    }
}


/// Query a node's object serial via pw-cli.
fn node_serial(_mainloop: &MainLoopRc, _core: &CoreRc, id: u32) -> Option<u64> {
    run_cmd("pw-cli", &["info", &id.to_string()])?
        .lines()
        .find(|l| l.contains("object.serial"))
        .and_then(|l| l.split('=').nth(1))
        .and_then(|v| v.trim().trim_matches('"').parse().ok())
}

/// The user's current playback device (their default, untouched by us).
fn current_device_id(
    nodes: &HashMap<String, (u32, String)>,
    fallback: Option<u32>,
) -> Option<u32> {
    if let Some(out) = run_cmd("pw-metadata", &["-n", "default", "0", "default.audio.sink"]) {
        if let Some(start) = out.find("value:'") {
            let rest = &out[start + 7..];
            if let Some(ns) = rest.find("\"name\":\"") {
                let name = &rest[ns + 8..];
                if let Some(end) = name.find('"') {
                    let node_name = &name[..end];
                    if let Some((id, class)) = nodes.get(node_name) {
                        if class == "Audio/Sink" && !node_name.contains("seq") {
                            return Some(*id);
                        }
                    }
                }
            }
        }
    }
    fallback.or_else(|| find_physical_sink(nodes).map(|(id, _)| id))
}

/// Destroy all outgoing links of a node (used when re-routing the DSP output).
fn unlink_node_outputs(_mainloop: &MainLoopRc, _core: &CoreRc, node: u32) {
    if let Some(text) = run_cmd("pw-cli", &["ls", "Link"]) {
        for block in text.split("\tid ").skip(1) {
            let mut lid = String::new();
            let mut out = String::new();
            for line in block.lines() {
                if let Some(rest) = line.trim_start().strip_prefix("link.output.node = ") {
                    out = rest.trim_matches('"').to_string();
                }
            }
            if let Some(id_part) = block.split(',').next() {
                lid = id_part.trim().to_string();
            }
            if out == node.to_string() {
                run_cmd("pw-cli", &["destroy", &lid]);
            }
        }
    }
}

/// Run every ~1s: route all output streams to the seq-eq sink (EE model, via
/// `target.object`/`target.node` metadata) and keep the DSP output following
/// the user's current playback device. The user's default output is untouched.
fn monitor(
    mainloop: &MainLoopRc,
    core: &CoreRc,
    sink_id: Option<u32>,
    sink_serial: Option<u64>,
    dsp_id: Option<u32>,
    user_device: Option<u32>,
    linked_device: &mut Option<u32>,
) {
    let Some(nodes) = collect_nodes(mainloop, core) else {
        return;
    };
    let (Some(sink_id), Some(sink_serial)) = (sink_id, sink_serial) else {
        return;
    };

    // Route the DSP output to the user's current playback device.
    if let Some(dev) = current_device_id(&nodes, user_device) {
        if *linked_device != Some(dev) {
            if let Some(dsp) = dsp_id {
                unlink_node_outputs(mainloop, core, dsp);
            }
            link_channels(mainloop, core, "seq-eq-dsp", "output_", dev, "playback_");
            *linked_device = Some(dev);
            eprintln!("[seq-eq] routed DSP to device {dev}");
        }
    }

    // Force every output stream through the seq-eq sink. WP moves streams when
    // target metadata changes; once a stream is on seq-eq WP remembers it, so
    // we only need to (re)set the target for streams that are not on seq-eq.
    let links_text = run_cmd("pw-cli", &["ls", "Link"]).unwrap_or_default();
    for (name, (id, class)) in &nodes {
        if class != "Stream/Output/Audio" || name.starts_with("seq") {
            continue;
        }
        if !node_links_to(&links_text, *id, sink_id) {
            run_cmd(
                "pw-metadata",
                &[
                    "-n",
                    "default",
                    &id.to_string(),
                    "target.object",
                    &sink_serial.to_string(),
                    "Spa:Id",
                ],
            );
            run_cmd(
                "pw-metadata",
                &[
                    "-n",
                    "default",
                    &id.to_string(),
                    "target.node",
                    &sink_id.to_string(),
                    "Spa:Id",
                ],
            );
        }
    }
}

/// True if any link has `output.node == from` and `input.node == to`.
fn node_links_to(links_text: &str, from: u32, to: u32) -> bool {
    let mut out: Option<u32> = None;
    for line in links_text.lines() {
        let t = line.trim();
        if let Some(rest) = t.strip_prefix("link.output.node = ") {
            out = rest.trim_matches('"').parse().ok();
        } else if let Some(rest) = t.strip_prefix("link.input.node = ") {
            if let Some(in_node) = rest.trim_matches('"').parse::<u32>().ok() {
                if out == Some(from) && in_node == to {
                    return true;
                }
            }
        }
    }
    false
}

fn do_roundtrip(mainloop: &MainLoopRc, core: &CoreRc) {
    let done = Rc::new(Cell::new(false));
    let done_clone = done.clone();
    let loop_clone = mainloop.clone();

    let Ok(pending) = core.sync(0) else {
        return;
    };
    let _listener = core
        .add_listener_local()
        .done(move |id, seq| {
            if id == pw::core::PW_ID_CORE && seq == pending {
                done_clone.set(true);
                loop_clone.quit();
            }
        })
        .register();

    while !done.get() {
        mainloop.run();
    }
}

fn collect_nodes(mainloop: &MainLoopRc, core: &CoreRc) -> Option<HashMap<String, (u32, String)>> {
    let registry = core.get_registry().ok()?;
    let nodes = Rc::new(RefCell::new(HashMap::new()));
    let nodes_clone = nodes.clone();
    let done = Rc::new(Cell::new(false));
    let done_clone = done.clone();
    let loop_clone = mainloop.clone();

    let _listener = registry
        .add_listener_local()
        .global(move |global| {
            if global.type_.to_str() == "PipeWire:Interface:Node" {
                if let Some(props) = global.props {
                    let name = props
                        .get("node.name")
                        .map(|s| s.to_string())
                        .unwrap_or_default();
                    let class = props
                        .get("media.class")
                        .map(|s| s.to_string())
                        .unwrap_or_default();
                    nodes_clone.borrow_mut().insert(name, (global.id, class));
                }
            }
        })
        .register();

    let pending = core.sync(0).ok()?;
    let _core_listener = core
        .add_listener_local()
        .done(move |id, seq| {
            if id == pw::core::PW_ID_CORE && seq == pending {
                done_clone.set(true);
                loop_clone.quit();
            }
        })
        .register();

    while !done.get() {
        mainloop.run();
    }

    Some(nodes.take())
}

fn collect_ports(
    mainloop: &MainLoopRc,
    core: &CoreRc,
) -> Option<Vec<(u32, u32, String, String)>> {
    let registry = core.get_registry().ok()?;
    let ports = Rc::new(RefCell::new(Vec::new()));
    let ports_clone = ports.clone();
    let done = Rc::new(Cell::new(false));
    let done_clone = done.clone();
    let loop_clone = mainloop.clone();

    let _listener = registry
        .add_listener_local()
        .global(move |global| {
            if global.type_.to_str() == "PipeWire:Interface:Port" {
                if let Some(props) = global.props {
                    let node = props.get("node.id").and_then(|s| s.parse().ok()).unwrap_or(0);
                    let port = props.get("port.id").and_then(|s| s.parse().ok()).unwrap_or(0);
                    let dir = props
                        .get("port.direction")
                        .map(|s| s.to_string())
                        .unwrap_or_default();
                    let name = props
                        .get("port.name")
                        .map(|s| s.to_string())
                        .unwrap_or_default();
                    ports_clone.borrow_mut().push((node, port, dir, name));
                }
            }
        })
        .register();

    let pending = core.sync(0).ok()?;
    let _core_listener = core
        .add_listener_local()
        .done(move |id, seq| {
            if id == pw::core::PW_ID_CORE && seq == pending {
                done_clone.set(true);
                loop_clone.quit();
            }
        })
        .register();

    while !done.get() {
        mainloop.run();
    }

    Some(ports.take())
}

fn wait_for_nodes(
    mainloop: &MainLoopRc,
    core: &CoreRc,
    names: &[&str],
) -> Option<HashMap<String, (u32, String)>> {
    let deadline = std::time::Instant::now() + Duration::from_secs(4);
    loop {
        if let Some(nodes) = collect_nodes(mainloop, core) {
            if names.iter().all(|n| nodes.contains_key(*n)) {
                return Some(nodes);
            }
        }
        if std::time::Instant::now() >= deadline {
            return collect_nodes(mainloop, core);
        }
        let _ = mainloop
            .loop_()
            .iterate(Timeout::Finite(Duration::from_millis(50)));
    }
}

fn find_physical_sink(nodes: &HashMap<String, (u32, String)>) -> Option<(u32, String)> {
    read_default_sink()
        .as_deref()
        .and_then(parse_sink_id)
        .and_then(|id| {
            nodes
                .iter()
                .find(|(name, (nid, class))| {
                    *nid == id && class == "Audio/Sink" && !name.contains("seq")
                })
                .map(|(name, (id, _))| (*id, name.clone()))
        })
        .or_else(|| {
            nodes
                .iter()
                .filter(|(name, (_, class))| {
                    class == "Audio/Sink"
                        && name.starts_with("alsa_output")
                        && !name.contains("seq")
                })
                .min_by_key(|(name, _)| {
                    if name.contains("Speaker") {
                        0
                    } else if name.contains("HDMI") {
                        1
                    } else {
                        2
                    }
                })
                .map(|(name, (id, _))| (*id, name.clone()))
        })
}

fn read_default_sink() -> Option<String> {
    run_cmd("pw-metadata", &["-n", "settings", "0", "default.audio.sink"])?
        .split("value:'")
        .nth(1)?
        .split("'")
        .next()
        .filter(|v| !v.is_empty())
        .map(|v| v.to_string())
}

fn parse_sink_id(value: &str) -> Option<u32> {
    value
        .strip_prefix("SND:")
        .and_then(|s| s.parse::<u32>().ok())
        .map(|v| v / 1000)
}

fn run_cmd(program: &str, args: &[&str]) -> Option<String> {
    let Ok(mut child) = Command::new(program)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
    else {
        return None;
    };
    let deadline = std::time::Instant::now() + Duration::from_secs(4);
    let mut out = String::new();
    loop {
        if let Some(status) = child.try_wait().ok().flatten() {
            if status.success() {
                if let Ok(o) = child.wait_with_output() {
                    out = String::from_utf8_lossy(&o.stdout).to_string();
                }
            }
            break;
        }
        if std::time::Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            break;
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    Some(out)
}
