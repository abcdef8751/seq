import { BandSettings } from "@/components/Slider.tsx";
import {
  BandKind,
  MAX_FREQ,
  MAX_GAIN,
  MAX_Q,
  MIN_FREQ,
  assertNever,
} from "@/eq.ts";

export interface ApoProfile {
  preamp: number;
  bands: BandSettings[];
}

const MAX_BANDS = 32;
// Q=0 is invalid in the DSP; APO import clamps to a sane minimum (the UI slider
// still lets users type 0, which the backend treats as ~0).
const IMPORT_MIN_Q = 0.1;

const PREAMP_RE = /^\s*preamp\s*:\s*(-?\d+(?:\.\d+)?)\s*dB/i;
// Equalizer APO spells our kinds PK / LS / HS (LSQ/HSQ map to APO's LS/HS).
const FILTER_RE =
  /^\s*filter\s+\d+\s*:\s*(ON|OFF)\s+(PK|LS|HS)\s+Fc\s+([\d.]+)\s*Hz\s+Gain\s*(-?\d+(?:\.\d+)?)\s*dB\s+Q\s*([\d.]+)/i;

const APO_TO_KIND: Record<string, BandKind> = { PK: "PK", LS: "LSQ", HS: "HSQ" };

function clamp(v: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, v));
}

function apoFilterType(kind: BandKind): string {
  switch (kind) {
    case "PK":
      return "PK";
    case "OFF":
      return "PK";
    case "LSQ":
      return "LS";
    case "HSQ":
      return "HS";
    default:
      return assertNever(kind);
  }
}

export function parseApo(content: string): ApoProfile {
  const bands: BandSettings[] = [];
  let preamp = 0;
  let found = false;

  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith(";") || line.startsWith("#")) continue;

    const pm = line.match(PREAMP_RE);
    if (pm) {
      preamp = parseFloat(pm[1]);
      found = true;
      continue;
    }

    if (/^\s*filter/i.test(line)) {
      if (/^\s*filter\s+\d+\s*:\s*OFF$/i.test(line)) continue;
      const fm = line.match(FILTER_RE);
      if (!fm) {
        throw new Error("Unsupported filter type or malformed filter line: " + line);
      }
      found = true;
      bands.push({
        kind: fm[1].toUpperCase() === "ON" ? APO_TO_KIND[fm[2].toUpperCase()] : "OFF",
        center_freq: clamp(parseFloat(fm[3]), MIN_FREQ, MAX_FREQ),
        gain: clamp(parseFloat(fm[4]), -MAX_GAIN, MAX_GAIN),
        Q: clamp(parseFloat(fm[5]), IMPORT_MIN_Q, MAX_Q),
      });
      continue;
    }
  }

  if (!found) {
    throw new Error("No Equalizer APO commands found in file");
  }
  if (bands.length > MAX_BANDS) {
    throw new Error(`Profile has ${bands.length} bands; seq supports at most ${MAX_BANDS}`);
  }
  return { preamp, bands };
}

function fmt(n: number): string {
  return String(Math.round(n * 100) / 100);
}

export function serializeApo(profile: { preamp: number; bands: BandSettings[] }): string {
  const lines = [
    "; seq — Equalizer APO profile",
    "; exported from seq",
    `Preamp: ${fmt(profile.preamp)} dB`,
  ];
  profile.bands.forEach((b, i) => {
    lines.push(
      `Filter ${i + 1}: ${b.kind === "OFF" ? "OFF" : "ON"} ${apoFilterType(b.kind)} Fc ${fmt(b.center_freq)} Hz Gain ${fmt(b.gain)} dB Q ${fmt(b.Q)}`,
    );
  });
  return lines.join("\n") + "\n";
}
