export type BandKind = "PK" | "OFF" | "LSQ" | "HSQ";

export function isBandActive(kind: BandKind): boolean {
  switch (kind) {
    case "PK":
      return true;
    case "OFF":
      return false;
    case "LSQ":
      return true;
    case "HSQ":
      return true;
    default:
      return assertNever(kind);
  }
}

export function assertNever(value: never): never {
  throw new Error(`Unhandled band kind: ${String(value)}`);
}

export interface EqBandInput {
  kind: BandKind;
  center_freq: number;
  q: number;
  gain: number;
}

interface BiquadCoeffs {
  b0: number;
  b1: number;
  b2: number;
  a1: number;
  a2: number;
}

const SAMPLE_RATE = 48000;

function bandCoeffs(kind: BandKind, freq: number, q: number, gainDb: number): BiquadCoeffs {
  const a = Math.pow(10, gainDb / 40);
  const w0 = (2 * Math.PI * freq) / SAMPLE_RATE;
  const cosw0 = Math.cos(w0);
  switch (kind) {
    case "PK":
      return peaking(a, w0, cosw0, q);
    case "LSQ":
    case "HSQ":
      return shelf(a, w0, cosw0, q, kind === "LSQ");
    case "OFF":
      return { b0: 1, b1: 0, b2: 0, a1: 0, a2: 0 };
    default:
      return assertNever(kind);
  }
}

function peaking(a: number, w0: number, cosw0: number, q: number): BiquadCoeffs {
  const alpha = Math.sin(w0) / (2 * Math.max(q, 1e-3));
  const a0 = 1 + alpha / a;
  return {
    b0: (1 + alpha * a) / a0,
    b1: (-2 * cosw0) / a0,
    b2: (1 - alpha * a) / a0,
    a1: (-2 * cosw0) / a0,
    a2: (1 - alpha / a) / a0,
  };
}

function shelf(a: number, w0: number, cosw0: number, q: number, low: boolean): BiquadCoeffs {
  const inner = Math.max(0, (a + 1 / a) * (1 / Math.max(q, 1e-3) - 1) + 2);
  const alpha = (Math.sin(w0) / 2) * Math.sqrt(inner);
  const sa = Math.sqrt(a);
  const b0 = low
    ? a * ((a + 1) - (a - 1) * cosw0 + 2 * sa * alpha)
    : a * ((a + 1) + (a - 1) * cosw0 + 2 * sa * alpha);
  const b1 = low
    ? 2 * a * ((a - 1) - (a + 1) * cosw0)
    : -2 * a * ((a - 1) + (a + 1) * cosw0);
  const b2 = low
    ? a * ((a + 1) - (a - 1) * cosw0 - 2 * sa * alpha)
    : a * ((a + 1) + (a - 1) * cosw0 - 2 * sa * alpha);
  const a0 = low
    ? (a + 1) + (a - 1) * cosw0 + 2 * sa * alpha
    : (a + 1) - (a - 1) * cosw0 + 2 * sa * alpha;
  const a1 = low
    ? -2 * ((a - 1) + (a + 1) * cosw0)
    : 2 * ((a - 1) - (a + 1) * cosw0);
  const a2 = low
    ? (a + 1) + (a - 1) * cosw0 - 2 * sa * alpha
    : (a + 1) - (a - 1) * cosw0 - 2 * sa * alpha;
  return { b0: b0 / a0, b1: b1 / a0, b2: b2 / a0, a1: a1 / a0, a2: a2 / a0 };
}

function biquadMagnitude(c: BiquadCoeffs, w: number): number {
  const cos2w = Math.cos(2 * w);
  const sin2w = Math.sin(2 * w);
  const cosw = Math.cos(w);
  const sinw = Math.sin(w);
  const numRe = c.b0 + c.b1 * cosw + c.b2 * cos2w;
  const numIm = c.b1 * sinw + c.b2 * sin2w;
  const denRe = 1 + c.a1 * cosw + c.a2 * cos2w;
  const denIm = c.a1 * sinw + c.a2 * sin2w;
  return Math.hypot(numRe, numIm) / Math.hypot(denRe, denIm);
}

export const MIN_FREQ = 10;
export const MAX_FREQ = 24000;
export const MIN_GAIN = -20;
export const MAX_GAIN = 20;
export const MIN_Q = 0;
export const MAX_Q = 10;
export const MIN_PREAMP = -20;
export const MAX_PREAMP = 20;
export const MIN_SLIDERS = 1;
export const MAX_SLIDERS = 32;

const SAMPLE_COUNT = 512;

export function computeCorrectPreamp(bands: EqBandInput[]): number {
  const coeffs = bands
    .filter((b) => isBandActive(b.kind) && Math.abs(b.gain) > 0.01)
    .map((b) => bandCoeffs(b.kind, b.center_freq, b.q, b.gain));

  let maxDb = 0;
  for (let i = 0; i < SAMPLE_COUNT; i++) {
    const f = MIN_FREQ * Math.pow(MAX_FREQ / MIN_FREQ, i / (SAMPLE_COUNT - 1));
    const w = (2 * Math.PI * f) / SAMPLE_RATE;
    let mag = 1;
    for (const c of coeffs) mag *= biquadMagnitude(c, w);
    const db = 20 * Math.log10(mag);
    if (db > maxDb) maxDb = db;
  }
  return Math.min(0, Math.floor(-maxDb * 10) / 10);
}
