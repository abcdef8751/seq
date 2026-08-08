pub mod eq_types;

#[cfg(target_os = "linux")]
pub mod pipewire_eq;

pub use eq_types::EqConfig;
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;
use tauri::Manager;

#[cfg(target_os = "linux")]
use pipewire_eq::PipeWireEq;

#[cfg(target_os = "linux")]
#[tauri::command]
fn apply_eq(state: tauri::State<PipeWireEq>, eq: EqConfig) -> Result<(), String> {
    state.apply(eq);
    Ok(())
}

#[cfg(target_os = "linux")]
#[tauri::command]
fn clear_eq(state: tauri::State<PipeWireEq>) -> Result<(), String> {
    state.clear();
    Ok(())
}

#[cfg(not(target_os = "linux"))]
#[tauri::command]
fn apply_eq(app: tauri::AppHandle, eq: EqConfig) -> Result<(), String> {
    use tauri_plugin_seq_eq::SeqEqExt;
    let json = serde_json::to_value(&eq).map_err(|e| e.to_string())?;
    let eq: tauri_plugin_seq_eq::EqConfig =
        serde_json::from_value(json).map_err(|e| e.to_string())?;
    app.seq_eq().apply_eq(eq)
}

#[cfg(not(target_os = "linux"))]
#[tauri::command]
fn clear_eq(app: tauri::AppHandle) -> Result<(), String> {
    use tauri_plugin_seq_eq::SeqEqExt;
    app.seq_eq().stop_engine()
}

#[tauri::command]
fn start_eq_engine(app: tauri::AppHandle) -> Result<(), String> {
    use tauri_plugin_seq_eq::SeqEqExt;
    app.seq_eq().start_engine()
}

#[tauri::command]
fn stop_eq_engine(app: tauri::AppHandle) -> Result<(), String> {
    use tauri_plugin_seq_eq::SeqEqExt;
    app.seq_eq().stop_engine()
}

#[tauri::command]
fn eq_engine_status(app: tauri::AppHandle) -> Result<tauri_plugin_seq_eq::EngineStatus, String> {
    use tauri_plugin_seq_eq::SeqEqExt;
    app.seq_eq().engine_status()
}

fn temp_path(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    app.path()
        .app_data_dir()
        .map(|dir| dir.join("temp.json"))
        .map_err(|e| e.to_string())
}

#[tauri::command]
fn save_temp_config(app: tauri::AppHandle, config: EqConfig) -> Result<(), String> {
    let path = temp_path(&app)?;
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    let data = serde_json::to_string_pretty(&config).map_err(|e| e.to_string())?;
    fs::write(path, data).map_err(|e| e.to_string())
}

#[tauri::command]
fn load_temp_config(app: tauri::AppHandle) -> Result<Option<EqConfig>, String> {
    let path = temp_path(&app)?;
    if !path.exists() {
        return Ok(None);
    }
    let data = fs::read_to_string(path).map_err(|e| e.to_string())?;
    serde_json::from_str(&data)
        .map(Some)
        .map_err(|e| e.to_string())
}

#[derive(Serialize, Deserialize, Clone)]
#[serde(rename_all = "snake_case")]
pub struct BandParams {
    kind: String,
    #[serde(rename = "Q")]
    q: f64,
    gain: f64,
    center_freq: f64,
}

#[derive(Serialize, Deserialize, Clone)]
#[serde(rename_all = "snake_case")]
pub struct Preset {
    preset_name: String,
    preamp: f64,
    parameters: Vec<BandParams>,
    #[serde(default)]
    auto_preamp: bool,
    #[serde(default)]
    bypassed: bool,
}

fn presets_path(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    app.path()
        .app_config_dir()
        .map(|dir| dir.join("presets.json"))
        .map_err(|e| e.to_string())
}

#[tauri::command]
fn load_presets(app: tauri::AppHandle) -> Result<Vec<Preset>, String> {
    let path = presets_path(&app)?;
    if !path.exists() {
        return Ok(vec![]);
    }
    let data = fs::read_to_string(path).map_err(|e| e.to_string())?;
    serde_json::from_str(&data).map_err(|e| e.to_string())
}

#[tauri::command]
fn save_presets(app: tauri::AppHandle, presets: Vec<Preset>) -> Result<(), String> {
    let path = presets_path(&app)?;
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    let data = serde_json::to_string_pretty(&presets).map_err(|e| e.to_string())?;
    fs::write(path, data).map_err(|e| e.to_string())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let builder = tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_fs::init())
        .plugin(tauri_plugin_seq_eq::init());

    #[cfg(target_os = "linux")]
    let builder = builder.manage(PipeWireEq::start());

    builder
        .invoke_handler(tauri::generate_handler![
            load_presets,
            save_presets,
            apply_eq,
            clear_eq,
            save_temp_config,
            load_temp_config,
            start_eq_engine,
            stop_eq_engine,
            eq_engine_status,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn preset_round_trip() {
        let presets = vec![Preset {
            preset_name: "Test".into(),
            preamp: -3.5,
            parameters: vec![BandParams {
                kind: "PK".into(),
                q: 1.2,
                gain: 4.0,
                center_freq: 1000.0,
            }],
            auto_preamp: true,
            bypassed: false,
        }];
        let json = serde_json::to_string_pretty(&presets).unwrap();
        let back: Vec<Preset> = serde_json::from_str(&json).unwrap();
        assert_eq!(back.len(), 1);
        assert_eq!(back[0].preset_name, "Test");
        assert_eq!(back[0].parameters[0].q, 1.2);
    }

    #[test]
    fn schema_shape() {
        let json = r#"[{"preset_name":"A","preamp":0.0,"parameters":[{"kind":"PK","Q":0.7,"gain":2.0,"center_freq":500.0}]}]"#;
        let parsed: Vec<Preset> = serde_json::from_str(json).unwrap();
        assert_eq!(parsed[0].parameters[0].q, 0.7);
    }
}
