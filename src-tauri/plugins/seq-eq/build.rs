const COMMANDS: &[&str] = &["start_engine", "stop_engine", "apply_eq", "engine_status"];

fn main() {
    tauri_plugin::Builder::new(COMMANDS)
        .android_path("android")
        .build();
}
