# seq — EQ Platform for PipeWire (Handoff Document)

A Tauri 2 desktop app that applies a parametric EQ to **system-wide audio** via PipeWire.
Written from scratch. This document is a complete handoff so an LLM (or human) can continue
working without re-deriving everything.

---

## 1. TL;DR Architecture

```
SolidJS UI (EQ page)
   │  invoke() via Tauri IPC
   ▼
Rust backend (lib.rs commands)
   │  mpsc channel
   ▼
PipeWire thread (pipewire_eq.rs)
   ├─ "seq-eq"     = visible virtual sink  (support.null-audio-sink)  — apps route here
   ├─ "seq-eq-dsp" = invisible DSP filter node (pw_filter, custom RBJ biquads) — gapless
   └─ monitor loop = routes apps→sink (metadata target.object/target.node),
                     links DSP output → user's current playback device
```

- Apps output → `seq-eq` sink → `seq-eq-dsp` (EQ applied) → user's actual device.
- The user's **default output device is never changed**. The EQ follows whichever device
  the user selects (speaker/HDMI/bluetooth).
- Filter changes are **gapless**: coefficients are swapped atomically inside the DSP thread;
  the node is never destroyed/recreated on parameter change.

---

## 2. Stack & Versions

| Concern            | Choice |
|--------------------|--------|
| Shell               | Tauri 2 (Rust), identifier `com.rp.seq` |
| UI                  | SolidJS 1.9 (JSX, signals, `<For>`/`<Index>`) |
| Styling             | Tailwind CSS v4 via `@tailwindcss/vite` + `@config "/tailwind.config.ts"` (legacy config path still required) |
| Components          | Kobalte (`@kobalte/core`) — Popover, Slider, Dialog |
| Icons               | `lucide-solid` — **must use deep imports** (see §10 perf) |
| Font                | Inter via `@fontsource/inter` — **latin-only** weights 400–700 (`latin-400.css` etc.),
  8 woff2 assets instead of the full 28 (all subsets × weights cost ~6 s of dev CSS processing;
  see §10). |
| PipeWire bindings   | `pipewire = "0.10"` (pipewire-rs) |
| File dialogs        | `tauri-plugin-dialog` + `@tauri-apps/plugin-dialog` (native picker on Linux; Android document picker, returns content URIs) |
| File IO             | `tauri-plugin-fs` + `@tauri-apps/plugin-fs` (reads/writes text files incl. Android content URIs) |
| Rust toolchain      | **nightly**, pinned via `src-tauri/rust-toolchain.toml` |
| Package manager     | `bun` (scripts reference `bun run`); npm also works |
| TypeScript          | TS 7 — `baseUrl` is **removed**; use bare `paths` (see tsconfig.json) |

### Paths / alias
- `@/*` → `./src/*` (both `tsconfig.json` and `vite.config.ts` resolve.alias).

---

## 3. Commands

```bash
# Dev (runs Vite on :1420 + cargo run). This is what you normally use:
bun run tauri dev

# Frontend typecheck / build
npx tsc --noEmit
bun run build            # = vite build → dist/

# Rust check / test / build (run from src-tauri/)
cargo check
cargo test               # 2 serde round-trip tests pass
cargo build

# NOTE on killing stray dev processes:
# pkill -f can match the invoking shell's own cmdline and hang the tool.
# Use:  pkill -x seq   or   pkill -f "[e]qtest"   (bracket trick)
```

### Git workflow
- **Remote**: `https://github.com/abcdef8751/seq.git`, single branch `main`, pushed with `-u`.
- **Auth is HTTPS + PAT only** — GitHub rejects passwords. Use a **classic** token with the `repo`
  scope; a fine-grained token returned a confusing `403 Write access to repository not granted`
  unless the exact repo + *Contents: Read and write* were selected. No SSH keys configured.
- **Commit style**: write descriptive messages (early history has bare `git commit -m "commit"`
  — don't repeat that). `git rm --cached` is how junk gets removed from the index while keeping
  files on disk (see §10 repo hygiene).
- **Convention for agent collaborators**: stage/commit/push **only when explicitly asked**.

---

## 4. File Map

### Frontend (`src/`)
- `index.html` — has inline dark `html,body{background:#0f0f0f}` to prevent white flash.
- `src/index.tsx` — Solid entry, `render(<App/>)`.
- `src/App.tsx` — just renders `<EQ/>`.
- `src/App.css` — Tailwind v4 entry + theme tokens:
  ```css
  --color-background: oklch(0.18 0 0);
  --color-sunken:    oklch(0.13 0 0);   /* EQ canvas background */
  --color-surface:   oklch(0.205 0 0);  /* cards/popovers */
  --color-foreground: oklch(0.985 0 0);
  --color-muted:     oklch(0.708 0 0);
  --color-border:    oklch(0.269 0 0);
  ```
- `src/pages/EQ.tsx` — the whole EQ page: header (preamp, slider count, presets hamburger,
  preamp auto-correct, sort, bypass, APO import/export), horizontally-scrollable row of
  `BandSlider`s, presets dialog (create/load/save/delete/rename), dismissible APO error banner.
- `src/eq.ts` — DSP helpers shared with the UI **and the single source of truth for all EQ
  bounds/constants** (`MIN/MAX_FREQ`, `MIN/MAX_GAIN`, `MIN/MAX_Q`, `MIN/MAX_PREAMP`,
  `MIN/MAX_SLIDERS`, `SAMPLE_RATE`): `BandKind` (+ exhaustive `isBandActive`/`assertNever`),
  RBJ `bandCoeffs` (PK/LSQ/HSQ) / `biquadMagnitude`, and `computeCorrectPreamp` (samples the
  profile 10 Hz–24 kHz and returns the peak-limiting preamp). See §5.
- `src/apo.ts` — Equalizer APO import/export: `parseApo` (strict; `Preamp` + `Filter`
  PK/LS/HS lines only — **GraphicEQ is intentionally unsupported**) and `serializeApo`. See §5.
- `src/components/Slider.tsx` — `BandSlider` + types (`BandSettings`, `DEFAULT_BAND`;
  `BandKind` lives in `src/eq.ts`).
- `src/components/NumberField.tsx` — number input that **commits only on Enter** (blur reverts).
  Used for freq/Q/gain/preamp/slider-count.
  - **Android Enter fix**: `keydown` Enter doesn't fire reliably on Android soft keyboards for
    `type="number"`. Wrapped in a `<form onSubmit>` (mobile keyboards trigger submit via the
    action/Done key) + `enterkeyhint="done"` + `inputmode="decimal"`; `onSubmit` also blurs to
    dismiss the keyboard. Desktop `keydown` path kept.

### Backend (`src-tauri/`)
- `src/main.rs` — thin, calls `seq_lib::run()`.
- `src/lib.rs` — Tauri commands + preset/temp-config file IO + `Preset`/`BandParams` types.
- `src/pipewire_eq.rs` — the whole PipeWire integration (see §6).
- `tauri.conf.json` — window `800x600`, `backgroundColor:#000000`, CSP null, devUrl :1420.
- `capabilities/default.json` — permissions: `core:default`, `opener:default`, `seq-eq:default`,
  `dialog:default`, and scoped `fs:allow-read-text-file`/`fs:allow-write-text-file` with
  `path: "**"` (APO import/export reads/writes arbitrary user-picked paths).
- `rust-toolchain.toml` — `channel = "nightly"`.
- `Cargo.toml` — `default-run = "seq"` (do NOT leave extra `src/bin/*.rs` around; it breaks
  `cargo run` with "could not determine which binary").

---

## 5. Frontend Details

### Band model
```ts
// BandKind lives in src/eq.ts, alongside the exhaustive isBandActive() switch.
type BandKind = "PK" | "OFF" | "LSQ" | "HSQ"; // peaking / disabled / low-shelf Q / high-shelf Q
interface BandSettings { kind: BandKind; Q: number; gain: number; center_freq: number; }
```
- Default band: `{ kind:"PK", Q:1, gain:0, center_freq:1000 }`.
- Default setup: 10 bands, log-spaced:
  ```ts
  freqFor(index, total) = Math.round(1000 * 2 ** ((index - (total-1)/2) / 2))
  ```
  2 bands/octave, centered at exactly 1 kHz. e.g. 10 bands span ≈210 Hz–4.8 kHz.
- Bounds: all defined once in `src/eq.ts` and imported everywhere (`MIN_SLIDERS=1,
  MAX_SLIDERS=32`, `MIN_FREQ=10, MAX_FREQ=24000`, `MIN_Q=0, MAX_Q=10` (step 0.1),
  gain `±20` (step 0.5), preamp `MIN/MAX_PREAMP = ±20`). `apo.ts` keeps its own
  `IMPORT_MIN_Q = 0.1` (Q=0 is invalid in the DSP, so APO import clamps higher than the
  slider's floor of 0).

### BandSlider (per band)
- Vertical card: gear (Settings icon) opens a Popover with **Enabled** checkbox (toggles
  `kind` OFF↔last type), **Type** selector (PEQ / LS / HS), Frequency, Q, Gain. All text fields
  are `NumberField` (Enter-to-commit). The card stacks the filter type over Q on separate lines
  (`PEQ` / `Q 1.00`).
- When `kind==="OFF"`: card dims (`opacity-50`), slider disabled, gain reads `"OFF"`.
- Kobalte vertical `<Slider>` with `value={[gain]}`, `minValue=-20`, `maxValue=20`, step 0.5.
  `onChange` updates the band live; `onChangeEnd` triggers `applyEq()` (debounced).

### EQ page flow
- `preamp` and `Sliders` inputs in the header are also `NumberField`s.
- `applyEq()` builds `EqConfig { preamp, bands: [{kind,center_freq,q,gain}] }` and
  calls `invoke("apply_eq")` + `invoke("save_temp_config")` (fire-and-forget, errors logged).
  **Reactive**: `setPreamp` flows through `onPreampInput` (debounced `applyEq`), band changes
  apply on slider release (`onChangeEnd`), and `loadPreset`/`resizeCount` call `applyEq()`
  directly. Do not add a global watch-effect to push config — the signals already drive it.
- `invokeLog<T>(cmd, args, label)` is the shared wrapper: `invoke<T>(cmd, args)` with
  `.catch(e => console.error(label, e))` returning `T | undefined`. Use it for fire-and-forget
  or read calls; keep a raw `invoke` only when the error needs distinct handling
  (e.g. `checkEngine` falls back to `engineRunning=true` on failure).
- `onMount`: `invokeLog("load_temp_config")`; if present, restore preamp/bands then apply.
- Header buttons share the `headerButtonClass` const; inline `NumberField` styles share
  `inputClass`.

### Preamp correction (`src/eq.ts`)
- `computeCorrectPreamp(bands)` builds each active band's `bandCoeffs` (RBJ **PK**, **LSQ**,
  **HSQ** — mirroring `pipewire_eq.rs`), then samples the **product** of the biquad magnitude
  responses (H(z)) at 512 log-spaced points from 10 Hz to 24 kHz. Returns the preamp that pins
  the curve peak at 0 dB, rounded **down** to the next 0.1 dB step (more headroom), i.e.
  `min(0, floor(-peakDb*10)/10)`. `SAMPLE_RATE = 48000`.
- **Auto preamp** checkbox: a `createEffect` re-pushes the computed value through
  `onPreampInput` whenever bands change while checked — no manual `invoke` needed.
- **Correct once** button: `onPreampInput(correctPreamp())` — one-shot, same path.

### Sort & bypass
- **Sort** button reorders bands by `center_freq` ascending and applies (`sortBands`).
- **EQ On/Off** button toggles `bypassed`. The DSP honors it (see §6) — flat, gapless
  pass-through. When bypassed, the band area dims (`opacity-60`) and the button turns red.

### APO import/export (`src/apo.ts`)
- **Import**: `openDialog()` (native picker; Android returns content URIs read via `readTextFile`),
  `parseApo(content)` → sets preamp + bands (auto-preamp off) + `applyEq()`. Invalid files
  throw descriptive errors surfaced in a dismissible red banner below the header.
- **Export**: `saveDialog()` + `writeTextFile(path, serializeApo(...))` — `Preamp:` + one
  `Filter N: ON/OFF PK|LS|HS Fc … Hz Gain … dB Q …` line per band (LSQ→`LS`, HSQ→`HS`).
- **Strictness**: `Preamp`, `Filter … PK …`, `Filter … LS …`, and `Filter … HS …` are parsed
  (LS/HS import as LSQ/HSQ). `GraphicEQ:` tables and other APO types (LP/HP/NO/…) are
  **rejected** with an error (Q is undefined there, and mapping to PK@Q=1 was deemed
  lossy/misleading). >32 bands errors out. Imported values clamp to the shared bounds, with
  Q clamped to `IMPORT_MIN_Q=0.1` rather than the UI's 0.
- **APO comment caveat**: lines are only treated as comments if they *start* with `;` or `#`;
  inline `;` is NOT stripped (it separates `GraphicEQ` pairs and would truncate data).

### Persisted flags
- `auto_preamp` and `bypassed` are stored in `temp.json` (via `EqConfig`) and in presets
  (`Preset`), both `#[serde(default)]` so older files load. Restored on mount / preset load;
  written on every `applyEq()` and preset create/save.

### Presets (hamburger menu → Dialog)
- Saved via `invoke("save_presets")` → `<app_config_dir>/presets.json`.
- List items: click to load, save (with confirm), delete (with confirm), rename (inline input).
- Reactive UI uses `<Switch>/<Match>` inside `<For>` — do NOT use eager `if/return` inside the
  `<For>` callback; Kobalte/Solid `<For>` calls the map fn once per item and `confirm()`/`editing()`
  branches never re-run. That was a real bug (see §10).

---

## 6. PipeWire Backend (`pipewire_eq.rs`) — the important part

### State/threading
- `PipeWireEq` holds `mpsc::Sender<Msg>`. `Msg::{Apply(EqConfig), Clear}`.
- `start()` spawns one thread running a PipeWire main loop (`MainLoopRc`). The thread owns
  the sink node, the DSP `pw_filter`, and a ~1s monitor tick.

### Nodes created
1. **`seq-eq`** — visible virtual sink so apps/Pulse can target it:
   ```rust
   factory.name = support.null-audio-sink
   media.class = Audio/Sink, node.virtual = true
   priority.session = 0, priority.driver = 0   // NEVER auto-selected as default
   node.group = seq-eq-group
   ```
2. **`seq-eq-dsp`** — invisible `pw_filter` (media.category=Duplex, role=DSP, node.group=seq-eq),
   with 4 DSP ports (input/output FL/FR) using `pw_filter_get_dsp_buffer`. It hosts a custom
   DSP written in Rust (not libspa's filter-graph — see §10).

### DSP (custom, in Rust)
- `band_coeffs(kind, f, q, gain, rate)` produces RBJ biquads for all active kinds:
  ```rust
  // peaking (PK)
  A = 10^(gain_db/40); w0 = 2π·f/rate; alpha = sin(w0)/(2q)
  // shelves (LSQ/HSQ) — Q-parameterized (cookbook "1/Q = sqrt((A+1/A)(1/S-1)+2)"):
  alpha = sin(w0)/2 * sqrt((A + 1/A)*(1/Q - 1) + 2)
  ```
  The shelf `sqrt` argument is **clamped ≥ 0** (very high Q at large gains would go NaN);
  clamped it degenerates to the steepest monotonic shelf. Shelf coefficients use the cookbook
  lowShelf/highShelf forms (DC gain = A² = dBgain linear). Android's `ParametricEq.kt` mirrors
  this exactly.
- `EqState { preamp: 10^(preamp_db/20), bands: Vec<Band> }` swapped atomically:
  `apply_eq` writes `config` + recomputes state under a `Mutex<EqState>`; `process_cb` locks it
  per cycle and runs the biquads (zero-crossing-free coefficient swap = gapless).
- **Bypass**: `EqConfig.bypassed` (serde-default `false`) → `eq_state_from_config` sets every
  band `active=false` and `preamp=1.0`, i.e. flat pass-through. Same atomic-swap path, so
  toggling the "EQ On/Off" button is gapless too.
- Sample rate changes (`pos.clock.rate.denom`) trigger state rebuild from stored config.

### Routing (EasyEffects model — the hard-won part)
- **User's default device is left alone.** WP's `default.audio.sink` stays on the user's pick.
- **Apps → sink:** the monitor (every ~1s) finds every `Stream/Output/Audio` node NOT linked to
  `seq-eq` and sets routing metadata:
  ```bash
  pw-metadata -n default <streamId> target.object <seq-eq serial> Spa:Id
  pw-metadata -n default <streamId> target.node  <seq-eq id>      Spa:Id
  ```
  WirePlumber's `linking/find-defined-target.lua` + `prepare-link.lua` then move the stream.
  - **CRITICAL arg order**: `pw-metadata` CLI is `id key value [type]` — value **before** type.
    The wrong order (`target.object Spa:Id 12345`) silently does nothing.
  - `target.object` needs the **object.serial** of the sink; `target.node` takes the node id.
- **Sink → DSP → device:** static link `seq-eq:monitor_* → seq-eq-dsp:input_*`. The monitor
  links `seq-eq-dsp:output_* → <user device>:playback_*`, and **re-links** when the user's
  default device changes (unlink old outputs, link new).
- Helper `node_serial(id)` reads `object.serial` from `pw-cli info`.
- `clear()`: destroy DSP + sink, clear `target.object`/`target.node` on all streams
  (`pw-metadata -d`), streams revert to the user's default.

### Filesystem
- `presets.json` → `app_config_dir` = `~/.config/com.rp.seq/presets.json`
- `temp.json`   → `app_data_dir`      = `~/.local/share/com.rp.seq/temp.json`

### Tauri commands (`lib.rs`)
`load_presets`, `save_presets`, `apply_eq`, `clear_eq`, `save_temp_config`, `load_temp_config`,
plus the Android engine bridge: `start_eq_engine`, `stop_eq_engine`, `eq_engine_status`
(`start_eq_engine` is a no-op on desktop; see §12).

---

## 7. Serde schemas

```rust
// presets.json
#[serde(rename_all="snake_case")]
struct Preset { preset_name: String, preamp: f64, parameters: Vec<BandParams>,
                #[serde(default)] auto_preamp: bool, #[serde(default)] bypassed: bool }
struct BandParams { kind: String, #[serde(rename="Q")] q: f64, gain: f64, center_freq: f64 }

// temp.json / apply_eq args
struct EqBand   { kind: BandKind, center_freq: f64, q: f64, gain: f64 }
enum BandKind   { #[serde(rename="PK")] Pk, #[serde(rename="OFF")] Off,
                  #[serde(rename="LSQ")] Lsq, #[serde(rename="HSQ")] Hsq }
struct EqConfig { preamp: f64, bands: Vec<EqBand>,
                  #[serde(default)] auto_preamp: bool, #[serde(default)] bypassed: bool }
```
Frontend mirrors these: `Preset`, `EqBand {kind,center_freq,q,gain}`, `EqConfig {preamp,bands}`
plus the same `auto_preamp`/`bypassed` flags (both `#[serde(default)]`, so old files round-trip
as `false`).

---

## 8. Known-good behaviors (verified)

- Gapless re-apply: DSP node id stable across applies; only coefficients change.
- Clean teardown: `clear()` removes both nodes; `SIGKILL` leaves no `seq-eq` nodes behind
  (no `object.linger`).
- Device following: switching default Speaker→HDMI→Bluetooth re-routes DSP output each time.
- Apps (existing + new) move to `seq-eq` via metadata targeting; on clear/kill they revert.
- `default.audio.sink` (both `settings` and `default` stores) stays on the user's device.

---

## 9. Working theories / dead ends (avoid re-treading)

1. **`monitor.stream.rules` / `stream.rules` / WP lua hooks do NOT route streams on this
   system's WirePlumber 0.5** — tried `main.lua.d/*.conf`, `main.lua.d/*.lua` ("old config"
   rejection), `wireplumber.conf.d` `stream.rules`, custom components script. None moved
   streams. **Metadata targeting is what actually works** (it triggers WP's
   `rescan-on-target-metadata-changed` → `find-defined-target` → `prepare-link`).
2. **Destroying streams' links to force re-selection is destructive** — when re-selection
   fails it wipes the sink association (apps got "wiped" off). Avoid `pw-cli destroy` of links
   except for the DSP output during device switches.
3. **`libspa` filter-graph / bq_peaking**: the `filter.graph` factory can't be created via the
   `adapter` factory's `filter.graph` property in all cases, and live control updates only hit
   the first node. The custom Rust biquads replaced it for gaplessness.
4. **pw_filter DSP output buffers were null** early on due to format negotiation (DSP F32 vs
   device S32) — fixed by using `pw_filter_get_dsp_buffer` on MAP_BUFFERS ports and falling back
   to in-place processing when output buffers are null.
5. **Hidden-window (`visible:false`) + `show()` breaks GTK titlebar buttons** (minimize/maximize/
   close) until a maximize cycle — a known tao/GTK quirk. Fix: create the window visible and
   prevent the white flash at the source (dark `html/body`, `backgroundColor`, fast load).

---

## 10. Hard-won gotchas (check before you "fix" something)

- **lucide-solid barrel import = dev-perf disaster.** `import {X} from "lucide-solid"` loads all
  ~1200 icons in dev (~380ms each; 3.8s page load). ALWAYS deep-import:
  `import Settings from "lucide-solid/icons/settings"` (note `trash-2`, not `trash`). After the
  fix, DOMContentLoaded dropped 3838ms → 182ms.
- **`@kobalte/core` barrel import = the same trap, worse.** `import {Dialog} from "@kobalte/core"`
  pulls the whole ~30-component package into Vite's dev prebundle (~**6.9 s** startup measured).
  Deep-import the submodules instead — parts are separate named exports:
  `@kobalte/core/dialog`, `@kobalte/core/popover`, `@kobalte/core/slider`. The namespace parts
  (`Root`/`Trigger`/`Portal`/…) are NOT attached to the component object, so keep `X.Root`
  syntax by building a tiny const namespace:
  ```ts
  import { Root as DialogRoot, Trigger as DialogTrigger, ... } from "@kobalte/core/dialog";
  const Dialog = { Root: DialogRoot, Trigger: DialogTrigger, ... };
  ```
  **Do NOT use `import * as`** — namespace imports defeat Rollup tree-shaking and pull the
  entire package into the production bundle (verified: Accordion/Combobox/Toast all appear).
  After the fix the barrel import line dropped from ~6.9 s to a few ms per submodule.
- **Solid `<For>` callback must not contain eager `if/return` on signals** — the map fn runs once
  per item; reactive branching must use `<Switch>/<Match>`/`<Show>`. Without it, confirm/rename
  UIs only appeared after reopening the dialog.
- **`pw-metadata` value-before-type order** (see §6) — wrong order fails silently.
- **Dialog plugin `open` collides with the `open` signal** — EQ.tsx has `const [open, setOpen] = createSignal(false)`, which shadows `import { open } from "@tauri-apps/plugin-dialog"`. Always alias it: `import { open as openDialog, save as saveDialog }`. Same for any import that could collide with a signal name.
- **`pkill -f` footgun** — matches the tool shell's own cmdline; use `pkill -x` or `[e]qtest`.
- **TS 7 removed `baseUrl`** — only `paths` (relative to tsconfig) is valid.
- **`default-run`** — don't leave stray `src-tauri/src/bin/*.rs`; makes `cargo run` ambiguous.
- **Tailwind v4 `@config` must end with a semicolon** and `@theme` tokens define the custom
  colors used everywhere (`bg-background`, `bg-sunken`, `text-foreground`, etc.).
- **Vite dev startup ~6 s is a one-time per-graph-change cost, not a real module cost.**
  Profiling imports in dev (see §10 kobalte entry) showed a single ~6 s spike that *migrates*
  between runs — it lands on whichever import is first after a file edit / server start. It's
  Vite's `optimizeDeps` esbuild pass + Tailwind v4's first CSS compile, both dev-only. Release
  builds tree-shake and load instantly. Don't chase it as a per-import bug; reload after the
  first load and it's milliseconds. (Temporary fix during profiling: route `console` to the
  terminal via a throwaway Rust `#[tauri::command] fn log()` + `println!`, since the webkit
  WebView console isn't visible in dev.)
- **Repo hygiene / `.gitignore`** — the repo once tracked ~3461 junk files (`.playwright-mcp`
  console logs, `target/` Rust artifacts incl. `src-tauri/plugins/seq-eq/target/`, and Android
  Gradle outputs under `src-tauri/plugins/seq-eq/android/{build,.tauri}`). Use a bare `target`
  rule (matches all depths) and **do NOT ignore `src-tauri/gen`** — `gen/android` contains real
  code (MainActivity.kt, gradle configs, the ABI-pinned `gradle.properties`). `git rm --cached`
  removes files from the index while keeping them on disk.

---

## 11. Suggested next steps (not started)

- Limiter / more filter types (LP/HP/notch) — `BandKind` can grow (shelves LSQ/HSQ are done;
  when kinds are added, update every kind switch: TS `isBandActive`/`bandCoeffs`/`filterLabel`/
  `apoFilterType`, Rust `band_coeffs`, and Android `ParametricEq.kt`).
- Spectrum / RTA view (would need to read DSP output or use `pw-cli` metering).
- A toggle so users can opt into "leave default alone" vs "seq as default" if desired.
- Preset import/export as files (APO import/export is done via `src/apo.ts`; this would be seq's
  own JSON preset format).
- CI for `cargo test` + `tsc`.

---

## 12. Android (Waydroid / Tauri)

Parametric EQ on Android uses the **RootlessJamesDSP rootless capture method**, integrated as
plugin `src-tauri/plugins/seq-eq` (Android-only; desktop still uses PipeWire).

### Pipeline (from RootlessJamesDSP)
1. **MediaProjection** token (screen-capture permission dialog)
2. **AudioPlaybackCapture** → `AudioRecord` (USAGE_MEDIA / GAME / UNKNOWN)
3. **RBJ biquads** (PK / LSQ / HSQ — same `coeffs()` math as `pipewire_eq.rs`) in Kotlin
   `ParametricEq`
4. **AudioTrack** playback with `ALLOW_CAPTURE_BY_NONE` (no feedback loop)
5. **Mute originals** via `DynamicsProcessing(sessionId)` + `-200 dB` input gain
6. **Session discovery** via dumpsys `audio` binder dump (needs `DUMP`) +
   `AudioPlaybackCallback` / `OPEN_AUDIO_EFFECT_CONTROL_SESSION` as poll triggers

### Commands
```bash
# Build debug APK (x86_64 for Waydroid)
export JAVA_HOME=…  # JDK 21
export ANDROID_HOME=/home/rp/android-sdk
export NDK_HOME=$ANDROID_HOME/ndk/28.1.13356709
bun run tauri android build --debug --target x86_64

# Install + grant DUMP (userdebug/waydroid)
adb install -r src-tauri/gen/android/app/build/outputs/apk/universal/debug/app-universal-debug.apk
adb shell pm grant com.rp.seq android.permission.DUMP
adb shell pm grant com.rp.seq android.permission.RECORD_AUDIO

# Dev
bun run tauri android dev --target x86_64
```

### Frontend
On mount, UI calls `start_eq_engine` (MediaProjection + FGS) then `apply_eq` as usual.
Desktop `start_eq_engine` is a no-op; `apply_eq` still goes to PipeWire.

### Limitations (same as RJDSP)
- Apps blocking internal capture (Spotify unpatched, Chrome, …) stay unprocessed
- Needs MediaProjection consent each session (unless persisted by OS)
- DUMP required for reliable session mute on Waydroid (`pm grant … DUMP`)
### Gotchas (Android / Waydroid)
- **Build env**: system default JDK is 25, which Gradle/AGP 8 reject. Use JDK 21
  (`gradle.properties` has `org.gradle.java.home=/home/rp/android-sdk/jdk-21`).
  Run `bun run android:dev` / `android:build` (scripts set JAVA_HOME/ANDROID_HOME/NDK_HOME).
- **`gen/android` ABIs**: pinned to `x86_64` only (Waydroid) via
  `abiList/archList/targetList` in `gen/android/gradle.properties`.
- **Black screen on first install** = installing a stale *dev* APK whose dev server is
  dead. Rebuild with `bun run android:build` (embedded assets) or run the dev server.
- **CRASH `RemoteServiceException: ...did not then call Service.startForeground()`**:
  `EqAudioProcessorService` must call `startForeground()` FIRST in `onCreate`/`onStartCommand`
  (before any MediaProjection work), else Android kills it within 5s. See
  `plugins/seq-eq/android/.../EqAudioProcessorService.kt`.
- **Session mute needs DUMP — and missing it looks like "audio duplicating".** Muting originals
  is gated on `hasDumpPermission()` in `refreshMutes`; when DUMP is missing it clears all mutes,
  so the original plays **unmuted** next to our replayed copy = doubling. Symptom diagnostics:
  `adb logcat -s seq-eq-svc` shows `mute SKIPPED: no DUMP permission`. Fix: the app now
  auto-grants it — `ShizukuPermissions.grantRuntimePermission` runs **`pm grant` via the Shizuku
  shell first** (the exact `adb shell pm grant com.rp.seq android.permission.DUMP` command, exit
  checked), and `refreshMutes` **self-heals** by re-running `applyAll()` (throttled 10s) whenever
  DUMP is missing and Shizuku is authorized. `pm grant` for DUMP works even on the stock Samsung
  `user` build (verified) when run via adb/Shizuku shell; the binder `IPermissionManager`
  reflection path silently no-ops on `user` builds, which is why exec comes first.
- **SessionDumper fallback regex bug**: `parseAudioFlinger` used `^\s*(\d+)\s+\d+…` which matches
  sample-rate/format numeric lines (48000, 192000, 384000, …) as bogus session IDs, polluting the
  mute list (attaches/fails on nonexistent sids, churns the real session). The `dumpsys audio`
  path is preferred and correct; only trust `parseAudioFlinger` for the real "Global session refs"
  block, not arbitrary numbers. **Fixed**: `parseAudioFlinger` now scopes to the
  "Global session refs:" table only (skips the column header, stops at the table's end), and
  `SessionDumper.dump` logs which path produced sessions (`audio-service dump: N` /
  `audio_flinger dump: N`).
- **Mute flicker symptom**: rapid `released mute session X` / `muted session X` every poll cycle
  indicates the recorder isn't holding the session (capture dead) OR bogus IDs are churning it.
  A stable single `muted session` + monotonic `diag reads` = healthy.
- **EQ live-apply depends on the recorder thread**, not the main-thread poll. The main-thread
  `sessionPollRunnable` proved unreliable (stopped cycling); `applyConfigIfChanged` is now called
  every 50 reads inside the recorder thread (which provably runs, since `diag` increments).
- **Stale `running` pref bug**: an earlier cross-process `"running"` pref left `true` after a
  restart made `startEngine` skip starting the engine (so capture never started but the mute ran
  = silence). Fixed by using the in-memory `running` (same-process) / `ActivityManager` (cross-
  process) instead of a persisted boolean.
- **Real device (Samsung SM-A235F, arm64)**: audio pipeline fully works there (capture non-zero,
  mute, replay, live EQ). Waydroid remains capture-silent (see limitation above) — use the phone
  for real verification.
### Dev vs release build (Waydroid)
- **`bun run android:dev`** = `tauri android dev --host 192.168.240.1`.
  Inside Waydroid, `localhost` is the container, NOT the host. The host Vite dev server is
  reachable at the Waydroid bridge gateway **`192.168.240.1`** (from `ip -4 addr show waydroid0`).
  `--host` rewrites `devUrl` to that and sets `TAURI_DEV_HOST`, which `vite.config.ts` already
  uses to bind `0.0.0.0`. **`android dev` does NOT accept `--target`** (only `android build` does).
- **`bun run android:build`** = embedded release-style APK; no dev server needed.
- **Build speed**: `tauri android dev` (via `dev:phone`) is incremental & fast. Direct
  `cargo tauri android build --debug --target aarch64` is a full production frontend build +
  complete gradle assemble + cargo compile — slow every time. Prefer the `dev:phone` script.
- **Gradle `UP-TO-DATE` trap**: `tauri android dev` DOES rebuild the `:tauri-plugin-seq-eq` Kotlin
  module, but gradle's incremental check can mark it `UP-TO-DATE` (missed rebuilds) → stale Kotlin
  on device. If behavior doesn't match source, force with
  `./gradlew :tauri-plugin-seq-eq:compileDebugKotlin --rerun-tasks` before building.
### Shizuku / permissions (RootlessJamesDSP parity)
- Plugin now integrates **Shizuku** (`dev.rikka.shizuku:api/provider:12.2.0`) to request the
  privileged perms MediaProjection capture needs: `ShizukuPermissions` mirrors RJDSP's
  `ShizukuSystemServerApi`:
  - `grantRuntimePermission(DUMP, RECORD_AUDIO, POST_NOTIFICATIONS)` — **exec-first**: runs
    `pm grant <pkg> <perm>` via `Shizuku.newProcess("sh")` (the proven on-device path, exit-code
    checked) and only falls back to the binder `IPermissionManager` reflection if exec fails.
    The binder path silently no-ops on `user` ROMs — never rely on it as the primary path.
  - `setProjectMediaOp(PROJECT_MEDIA)` appop
  - `setAllowedCapturePolicy(uid, allow)` per session — this is what makes restricted apps
    actually capturable.
- `EqPlugin.startEngine` requests Shizuku first; on grant it applies all perms before capture.
- **Critical safety** (`EqAudioProcessorService.refreshMutes`): originals are only muted when
  `DUMP` is granted (session discovery works) AND capture is live. If DUMP is missing, we skip
  muting entirely so the original audio keeps playing — this prevents "audio dies completely"
  (muting the source while replaying silence). Missing DUMP therefore surfaces as **duplication**
  (original unmuted + processed replay), not silence; `refreshMutes` self-heals by re-running
  `applyAll()` via Shizuku when it detects DUMP is missing (throttled to every 10s).

### Known limitation: Waydroid capture returns silence
- On Waydroid the `AudioRecord` on `AUDIO_DEVICE_IN_REMOTE_SUBMIX` reads **0.0000 RMS** even with
  real media playing — the MediaProjection playback-capture backend doesn't deliver audio there
  (confirmed via `avgRms` diagnostics). This is a Waydroid/emulator audio limitation, not a code
  bug. On real hardware (where RJDSP works), the Shizuku capture-policy path delivers audio.
- The safety gate above means on Waydroid the app will simply leave originals unmuted (no EQ,
  no audio death) rather than silencing everything.
### Dev on Android (auto-detect) — `scripts/android-dev.sh`
Replaces the old `--host`-baked scripts with a smart runner (`bun run android:dev`):

- **Phone over USB**: detects `usb:` device + its ABI (`arm64-v8a`/`x86_64`/`armeabi-v7a`),
  writes the matching `abiList/archList/targetList` into `gen/android/gradle.properties`,
  then runs `adb reverse tcp:1420 tcp:1420` so the app reaches the host Vite dev server at
  `localhost:1420` (no `--host` override — default `devUrl`). Vite must bind `0.0.0.0`
  (the `TAURI_DEV_HOST` path in `vite.config.ts` handles this; the script starts Vite via
  tauri's `beforeDevCommand`).
- **Waydroid**: detects `192.168.*` device, sets x86_64 ABI, uses `--host` = waydroid0
  bridge gateway (`ip -4 addr show waydroid0`, fallback `192.168.240.1`).
- **Manual override**: `bun run android:dev:phone` / `android:dev:waydroid`.

Key insight: a *debug* build bakes `devUrl` into the `.so`. Building for the phone without
the Waydroid `--host` produced a stale `192.168.240.1` URL → the app showed
`Failed to request http://192.168.240.1:1420/`. `adb reverse` makes `localhost:1420` work on
any device and avoids host-IP coupling.
### Surviving swipe-away / background
- `EqAudioProcessorService` runs in a **separate process** (`android:process=":audio"`) — the key
  to surviving swipe-away without restart. The Tauri WebView host lives in the main process and
  gets killed when the app is swiped from recents; running the audio pipeline in its own process
  keeps the EQ alive continuously (no restart, no gap, no re-consent). This matches RJDSP, whose
  process survives because it has no WebView. Verified: after `am stack remove`, `com.rp.seq`
  (WebView) is killed but `com.rp.seq:audio` survives, `isForeground=true`, capture continues
  (`avgRms` non-zero), output AudioTrack keeps playing.
- Cross-process state (plugin main proc ↔ service `:audio`):
  - EQ config → `persistConfigFor` (SharedPreferences) + applied in the recorder thread
    (see "Cross-process config visibility").
  - MediaProjection token → passed via the START intent extra (cross-process safe).
  - Running state → `isRunning(context)` via `ActivityManager.getRunningServices` (reflects the
    real `:audio` state; avoids the stale-pref bug that made `startEngine` skip starting).
- `onStartCommand` returns `START_REDELIVER_INTENT` as a fallback if the `:audio` process is ever
  killed; if no projection data arrives it stays foreground and waits for the UI to re-trigger
  (never `stopSelf()` with mute running = would be silence).
- `onTaskRemoved` is a no-op keep-alive (must not stop on task removal).
- **How RJDSP does it (verified in source):** RJDSP stores the MediaProjection result Intent on
  the **Application singleton** (`app.mediaProjectionStartIntent`) and reuses it to restart the
  service without re-consent. Critically, its own comment confirms **"Android >= 14 doesn't allow
  token reuse"** — so on Android 14+ it forces a fresh consent dialog on process death, exactly
  like us. It "applies when you close the app" because backgrounding/swipe-away keeps the
  foreground-service **process alive**, so the in-memory token stays valid. We mirror this:
  `EqAudioProcessorService.projectionIntent` (in-memory) is reused when the process is alive on
  Android < 14; a hard kill still requires re-consent on 14+ (a platform security boundary, not
  bypassable even by RJDSP).
- The MediaProjection token itself contains a Binder and **cannot** be marshalled to storage —
  there is no legitimate persistence across process death.
### Re-enable EQ affordance (Android)
- The EQ page polls `eq_engine_status` every 3s (Android only).
- If `running` is false (e.g. MediaProjection token invalidated by a force-stop or OEM
  battery killer on Android 14+), a banner appears: *"EQ engine is not running (permission
  may have been revoked)"* with a **Re-enable EQ** button that calls `start_eq_engine`
  (re-launches the consent dialog + capture pipeline).
- Frontend: `src/pages/EQ.tsx` (`checkEngine` / `reEnableEngine`), gated on `isAndroid`.
- **Root cause of recurring `Failed to request http://172.16.0.2:1420/`**: on `tauri android dev`,
  the CLI **auto-detects a "public" network address** and replaces the devUrl host with it,
  baking it into the `.so`. On this machine it picks `172.16.0.2` (a `warp` VPN interface),
  which the phone can't reach. It overrides whatever `devUrl`/`fix_generated_devurl` set.
- **Fix**: `scripts/android-dev.sh` passes **`--host 127.0.0.1`** for the phone path. Tauri then
  bakes `127.0.0.1:1420`, and since the phone reaches the host via `adb reverse tcp:1420`, that
  works. (Waydroid path keeps its bridge-gateway `--host`.) No full recompile needed.
### EQ config in separate process (applyEq reliability) — CRITICAL
- With the service in `:audio`, the plugin's `applyEq` (main process) can't touch the engine
  statics. `persistConfigFor` writes the config + bumps a `config_revision` counter in
  SharedPreferences; the service's **recorder thread** calls `applyConfigIfChanged()` every 50
  reads (~1s) and re-applies when the revision changes. (More reliable than the `ACTION_APPLY`
  intent, which can be dropped for an already-running cross-process service.)
- **Cross-process visibility**: **without `MODE_MULTI_PROCESS`, each process caches
  SharedPreferences in memory, so the `:audio` service never sees the main process's writes →
  EQ changes silently never apply.** Use `MODE_MULTI_PROCESS` on both `prefs()` (read) and
  `persistConfigFor` (write), and `commit()` (not `apply()`) so the write is synchronously
  flushed to disk before the `:audio` thread reads it.
- Verified: each slider change bumps the revision and the `:audio` service re-applies within
  ~0.2s (`re-applied EQ config (revision=…)`).
