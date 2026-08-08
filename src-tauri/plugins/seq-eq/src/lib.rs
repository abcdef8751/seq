use serde::{Deserialize, Serialize};
use tauri::{
    plugin::{Builder, TauriPlugin},
    Manager, Runtime,
};

#[cfg(target_os = "android")]
const PLUGIN_IDENTIFIER: &str = "com.rp.seq.eq";

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "UPPERCASE")]
pub enum BandKind {
    Pk,
    Off,
    Lsq,
    Hsq,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct EqBand {
    pub kind: BandKind,
    pub center_freq: f64,
    pub q: f64,
    pub gain: f64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct EqConfig {
    pub preamp: f64,
    pub bands: Vec<EqBand>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct EngineStatus {
    pub running: bool,
    pub platform: String,
}

pub struct SeqEq<R: Runtime> {
    #[cfg(target_os = "android")]
    handle: tauri::plugin::PluginHandle<R>,
    #[cfg(not(target_os = "android"))]
    _marker: std::marker::PhantomData<fn() -> R>,
}

impl<R: Runtime> SeqEq<R> {
    pub fn start_engine(&self) -> Result<(), String> {
        #[cfg(target_os = "android")]
        {
            return self
                .handle
                .run_mobile_plugin::<()>("startEngine", ())
                .map_err(|e| e.to_string());
        }
        #[cfg(not(target_os = "android"))]
        {
            let _ = self;
            Ok(())
        }
    }

    pub fn stop_engine(&self) -> Result<(), String> {
        #[cfg(target_os = "android")]
        {
            return self
                .handle
                .run_mobile_plugin::<()>("stopEngine", ())
                .map_err(|e| e.to_string());
        }
        #[cfg(not(target_os = "android"))]
        {
            let _ = self;
            Ok(())
        }
    }

    pub fn apply_eq(&self, eq: EqConfig) -> Result<(), String> {
        #[cfg(target_os = "android")]
        {
            return self
                .handle
                .run_mobile_plugin::<()>("applyEq", eq)
                .map_err(|e| e.to_string());
        }
        #[cfg(not(target_os = "android"))]
        {
            let _ = (self, eq);
            Ok(())
        }
    }

    pub fn engine_status(&self) -> Result<EngineStatus, String> {
        #[cfg(target_os = "android")]
        {
            return self
                .handle
                .run_mobile_plugin::<EngineStatus>("engineStatus", ())
                .map_err(|e| e.to_string());
        }
        #[cfg(not(target_os = "android"))]
        {
            let _ = self;
            Ok(EngineStatus {
                running: true,
                platform: std::env::consts::OS.to_string(),
            })
        }
    }
}

pub trait SeqEqExt<R: Runtime> {
    fn seq_eq(&self) -> &SeqEq<R>;
}

impl<R: Runtime, T: Manager<R>> SeqEqExt<R> for T {
    fn seq_eq(&self) -> &SeqEq<R> {
        self.state::<SeqEq<R>>().inner()
    }
}

#[tauri::command]
async fn start_engine<R: Runtime>(app: tauri::AppHandle<R>) -> Result<(), String> {
    app.seq_eq().start_engine()
}

#[tauri::command]
async fn stop_engine<R: Runtime>(app: tauri::AppHandle<R>) -> Result<(), String> {
    app.seq_eq().stop_engine()
}

#[tauri::command]
async fn apply_eq_mobile<R: Runtime>(
    app: tauri::AppHandle<R>,
    eq: EqConfig,
) -> Result<(), String> {
    app.seq_eq().apply_eq(eq)
}

#[tauri::command]
async fn engine_status<R: Runtime>(app: tauri::AppHandle<R>) -> Result<EngineStatus, String> {
    app.seq_eq().engine_status()
}

pub fn init<R: Runtime>() -> TauriPlugin<R> {
    Builder::<R>::new("seq-eq")
        .invoke_handler(tauri::generate_handler![
            start_engine,
            stop_engine,
            apply_eq_mobile,
            engine_status,
        ])
        .setup(|app, api| {
            #[cfg(target_os = "android")]
            {
                let handle = api.register_android_plugin(PLUGIN_IDENTIFIER, "EqPlugin")?;
                app.manage(SeqEq { handle });
            }
            #[cfg(not(target_os = "android"))]
            {
                let _ = api;
                app.manage(SeqEq::<R> {
                    _marker: std::marker::PhantomData,
                });
            }
            Ok(())
        })
        .build()
}
