use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, PartialEq)]
#[serde(rename_all = "UPPERCASE")]
pub enum BandKind {
    Pk,
    Off,
    Lsq,
    Hsq,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct EqBand {
    pub kind: BandKind,
    pub center_freq: f64,
    pub q: f64,
    pub gain: f64,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct EqConfig {
    pub preamp: f64,
    pub bands: Vec<EqBand>,
    #[serde(default)]
    pub auto_preamp: bool,
    #[serde(default)]
    pub bypassed: bool,
}
