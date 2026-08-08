import {
  createEffect,
  createSignal,
  For,
  Index,
  JSXElement,
  Match,
  Show,
  Switch,
  onMount,
} from "solid-js";
import {
  Root as DialogRoot,
  Trigger as DialogTrigger,
  Portal as DialogPortal,
  Overlay as DialogOverlay,
  Content as DialogContent,
  Title as DialogTitle,
  Description as DialogDescription,
} from "@kobalte/core/dialog";
const Dialog = {
  Root: DialogRoot,
  Trigger: DialogTrigger,
  Portal: DialogPortal,
  Overlay: DialogOverlay,
  Content: DialogContent,
  Title: DialogTitle,
  Description: DialogDescription,
};
import { invoke } from "@tauri-apps/api/core";
import {
  open as openDialog,
  save as saveDialog,
} from "@tauri-apps/plugin-dialog";
import { readTextFile, writeTextFile } from "@tauri-apps/plugin-fs";
import Check from "lucide-solid/icons/check";
import Menu from "lucide-solid/icons/menu";
import Pencil from "lucide-solid/icons/pencil";
import Plus from "lucide-solid/icons/plus";
import Save from "lucide-solid/icons/save";
import Trash2 from "lucide-solid/icons/trash-2";
import X from "lucide-solid/icons/x";
import BandSlider, {
  BandSettings,
  DEFAULT_BAND,
} from "@/components/Slider.tsx";
import NumberField from "@/components/NumberField.tsx";
import {
  BandKind,
  MAX_PREAMP,
  MAX_SLIDERS,
  MIN_PREAMP,
  MIN_SLIDERS,
  computeCorrectPreamp,
} from "@/eq.ts";
import { parseApo, serializeApo } from "@/apo.ts";
interface Preset {
  preset_name: string;
  preamp: number;
  parameters: BandSettings[];
  auto_preamp: boolean;
  bypassed: boolean;
}

interface EqBand {
  kind: BandKind;
  center_freq: number;
  q: number;
  gain: number;
}

interface EqConfig {
  preamp: number;
  bands: EqBand[];
  auto_preamp: boolean;
  bypassed: boolean;
}

type ConfirmState = { kind: "save" | "delete"; index: number } | null;

function freqFor(index: number, total: number): number {
  return Math.round(1000 * Math.pow(2, (index - (total - 1) / 2) / 2));
}

export default function EQ(): JSXElement {
  const [preamp, setPreamp] = createSignal(0);
  const [bands, setBands] = createSignal<BandSettings[]>(
    Array.from({ length: 10 }, (_, i) => ({
      ...DEFAULT_BAND,
      center_freq: freqFor(i, 10),
    })),
  );
  const [presets, setPresets] = createSignal<Preset[]>([]);
  const [open, setOpen] = createSignal(false);
  const [presetName, setPresetName] = createSignal("");
  const [confirm, setConfirm] = createSignal<ConfirmState>(null);
  const [editing, setEditing] = createSignal<number | null>(null);
  const [editValue, setEditValue] = createSignal("");
  const [engineRunning, setEngineRunning] = createSignal(true);
  const [restarting, setRestarting] = createSignal(false);
  const [autoPreamp, setAutoPreamp] = createSignal(false);
  const [bypassed, setBypassed] = createSignal(false);
  const [apoError, setApoError] = createSignal("");

  const isAndroid = (() => {
    try {
      return (
        typeof window !== "undefined" &&
        "__TAURI_INTERNALS__" in window &&
        (navigator.userAgent.includes("Android") ||
          navigator.userAgent.includes("wry"))
      );
    } catch {
      return false;
    }
  })();

  const checkEngine = async () => {
    try {
      const status = await invoke<{ running: boolean }>("eq_engine_status");
      setEngineRunning(status.running);
    } catch {
      setEngineRunning(true);
    }
  };

  const reEnableEngine = async () => {
    setRestarting(true);
    await invokeLog("start_eq_engine", {}, "Failed to re-enable EQ engine");
    setRestarting(false);
    setTimeout(checkEngine, 2500);
  };

  const inputClass =
    "w-20 rounded-md border border-border bg-background px-2 py-1 text-xs text-foreground outline-none focus:border-foreground";
  const headerButtonClass =
    "rounded-md border border-border px-2 py-0.5 text-[10px] text-muted transition-colors hover:text-foreground";

  const refreshPresets = async () => {
    const data = await invokeLog<Preset[]>(
      "load_presets",
      {},
      "Failed to load presets",
    );
    if (Array.isArray(data)) setPresets(data);
  };

  const buildEq = (): EqConfig => ({
    preamp: preamp(),
    auto_preamp: autoPreamp(),
    bypassed: bypassed(),
    bands: bands().map((b) => ({
      kind: b.kind,
      center_freq: b.center_freq,
      q: b.Q,
      gain: b.gain,
    })),
  });

  const correctPreamp = () => computeCorrectPreamp(buildEq().bands);

  const invokeLog = <T,>(
    cmd: string,
    args: Record<string, unknown>,
    label: string,
  ): Promise<T | undefined> =>
    invoke<T>(cmd, args).catch((e) => {
      console.error(`${label}:`, e);
      return undefined;
    });

  const applyEq = () => {
    const eq = buildEq();
    invokeLog("apply_eq", { eq }, "Failed to apply EQ");
    invokeLog("save_temp_config", { config: eq }, "Failed to save temp config");
  };

  const correctOnce = () => onPreampInput(correctPreamp());

  let preampTimer: number | undefined;
  const onPreampInput = (value: number) => {
    setPreamp(value);
    window.clearTimeout(preampTimer);
    preampTimer = window.setTimeout(applyEq, 400);
  };

  createEffect(() => {
    if (autoPreamp()) onPreampInput(correctPreamp());
  });

  onMount(async () => {
    const saved = await invokeLog<EqConfig | null>(
      "load_temp_config",
      {},
      "Failed to load temp config",
    );
    if (saved && Array.isArray(saved.bands)) {
      setPreamp(saved.preamp ?? 0);
      setAutoPreamp(saved.auto_preamp ?? false);
      setBypassed(saved.bypassed ?? false);
      setBands(
        saved.bands.map((b) => ({
          ...DEFAULT_BAND,
          kind: b.kind ?? "PK",
          center_freq: b.center_freq,
          Q: b.q,
          gain: b.gain,
        })),
      );
    }
    // Android: request MediaProjection + start capture pipeline (no-op on desktop)
    await invokeLog("start_eq_engine", {}, "Failed to start EQ engine");
    applyEq();

    // Poll engine status so we can surface a "Re-enable EQ" affordance if the
    // MediaProjection token was invalidated (e.g. after a force-stop / battery kill).
    if (isAndroid) {
      await checkEngine();
      setInterval(checkEngine, 3000);
    }
  });

  const handleOpenChange = (isOpen: boolean) => {
    setOpen(isOpen);
    if (isOpen) refreshPresets();
  };

  const persist = (next: Preset[]) => {
    invokeLog("save_presets", { presets: next }, "Failed to save presets");
  };

  const updateBand = (index: number, settings: BandSettings) => {
    setBands((b) => b.map((x, i) => (i === index ? settings : x)));
  };

  const resizeCount = (n: number) => {
    setBands((b) => {
      const next = [...b];
      while (next.length < n) {
        next.push({ ...DEFAULT_BAND, center_freq: freqFor(next.length, n) });
      }
      if (next.length > n) next.length = n;
      return next;
    });
  };

  const sortBands = () => {
    setBands((b) => [...b].sort((a, c) => a.center_freq - c.center_freq));
    applyEq();
  };

  const toggleBypass = () => {
    setBypassed((v) => !v);
    applyEq();
  };

  const importApo = async () => {
    const path = await openDialog({
      multiple: false,
      filters: [
        { name: "Equalizer APO", extensions: ["txt", "config", "apo"] },
      ],
    });
    if (typeof path !== "string") return;
    try {
      const profile = parseApo(await readTextFile(path));
      setPreamp(profile.preamp);
      setAutoPreamp(false);
      setBands(profile.bands);
      setApoError("");
      applyEq();
    } catch (e) {
      setApoError(e instanceof Error ? e.message : String(e));
    }
  };

  const exportApo = async () => {
    const path = await saveDialog({
      defaultPath: "seq-apo.txt",
      filters: [{ name: "Equalizer APO", extensions: ["txt"] }],
    });
    if (typeof path !== "string") return;
    try {
      await writeTextFile(
        path,
        serializeApo({ preamp: preamp(), bands: bands() }),
      );
      setApoError("");
    } catch (e) {
      setApoError(e instanceof Error ? e.message : String(e));
    }
  };

  const createPreset = () => {
    const name = presetName().trim() || `Preset ${presets().length + 1}`;
    const next = [
      ...presets(),
      {
        preset_name: name,
        preamp: preamp(),
        auto_preamp: autoPreamp(),
        bypassed: bypassed(),
        parameters: bands(),
      },
    ];
    setPresets(next);
    persist(next);
    setPresetName("");
  };

  const loadPreset = (preset: Preset) => {
    setPreamp(preset.preamp ?? 0);
    setAutoPreamp(preset.auto_preamp ?? false);
    setBypassed(preset.bypassed ?? false);
    setBands(preset.parameters.map((p) => ({ ...DEFAULT_BAND, ...p })));
    setOpen(false);
    applyEq();
  };

  const confirmYes = () => {
    const c = confirm();
    if (!c) return;
    if (c.kind === "delete") {
      const next = presets().filter((_, i) => i !== c.index);
      setPresets(next);
      persist(next);
    } else {
      const next = presets().map((p, i) =>
        i === c.index
          ? {
              ...p,
              preamp: preamp(),
              auto_preamp: autoPreamp(),
              bypassed: bypassed(),
              parameters: bands(),
            }
          : p,
      );
      setPresets(next);
      persist(next);
    }
    setConfirm(null);
  };

  const startRename = (index: number, name: string) => {
    setEditing(index);
    setEditValue(name);
  };

  const commitRename = () => {
    const index = editing();
    if (index == null) return;
    const name = editValue().trim() || presets()[index].preset_name;
    const next = presets().map((p, i) =>
      i === index ? { ...p, preset_name: name } : p,
    );
    setPresets(next);
    persist(next);
    setEditing(null);
  };

  const cancelRename = () => setEditing(null);

  return (
    <div class="flex h-screen flex-col bg-background text-foreground">
      <div class="relative flex flex-wrap items-center justify-center gap-x-8 gap-y-2 border-b border-border px-4 py-3">
        <button
          onClick={() => handleOpenChange(true)}
          title="Presets"
          class="absolute left-3 top-1/2 grid size-8 -translate-y-1/2 place-items-center rounded-md text-muted transition-colors hover:bg-muted/10 hover:text-foreground"
        >
          <Menu class="size-5" />
        </button>

        <label class="flex flex-col gap-1">
          <span class="text-[10px] text-muted">Preamp (dB)</span>
          <NumberField
            value={preamp()}
            min={MIN_PREAMP}
            max={MAX_PREAMP}
            step={0.5}
            onCommit={onPreampInput}
            class={inputClass}
          />
        </label>
        <label class="flex flex-col gap-1">
          <span class="text-[10px] text-muted">Sliders</span>
          <NumberField
            value={bands().length}
            min={MIN_SLIDERS}
            max={MAX_SLIDERS}
            onCommit={(v) => {
              resizeCount(
                Math.min(MAX_SLIDERS, Math.max(MIN_SLIDERS, Math.round(v))),
              );
              window.clearTimeout(preampTimer);
              preampTimer = window.setTimeout(applyEq, 400);
            }}
            class={inputClass}
          />
        </label>

        <div class="flex flex-col items-center gap-1">
          <label class="flex items-center gap-1.5 text-[10px] text-muted">
            <input
              type="checkbox"
              checked={autoPreamp()}
              onChange={(e) => setAutoPreamp(e.currentTarget.checked)}
              class="size-3 accent-foreground"
            />
            Auto preamp
          </label>
          <button
            onClick={correctOnce}
            title="Set preamp so the peak of the EQ curve sits at 0 dB"
            class={headerButtonClass}
          >
            Correct once
          </button>
        </div>

        <div class="flex flex-col items-center gap-1">
          <button
            onClick={sortBands}
            title="Sort bands by frequency (ascending)"
            class={headerButtonClass}
          >
            Sort
          </button>
          <button
            onClick={toggleBypass}
            title={bypassed() ? "Enable EQ" : "Bypass EQ"}
            class={
              "rounded-md border px-2 py-0.5 text-[10px] transition-colors " +
              (bypassed()
                ? "border-red-400/40 text-red-400 hover:text-red-300"
                : "border-border text-muted hover:text-foreground")
            }
          >
            {bypassed() ? "EQ Off" : "EQ On"}
          </button>
        </div>

        <div class="flex flex-col items-center gap-1">
          <button
            onClick={importApo}
            title="Import an Equalizer APO profile"
            class={headerButtonClass}
          >
            Import
          </button>
          <button
            onClick={exportApo}
            title="Export the current profile as Equalizer APO"
            class={headerButtonClass}
          >
            Export
          </button>
        </div>
      </div>

      <Show when={apoError()}>
        <div class="flex items-center justify-between gap-3 border-b border-border bg-surface px-4 py-2">
          <span class="text-xs text-red-400">{apoError()}</span>
          <button
            onClick={() => setApoError("")}
            class="shrink-0 rounded-md border border-border px-2 py-0.5 text-[10px] text-muted hover:text-foreground"
          >
            Dismiss
          </button>
        </div>
      </Show>

      <Show when={isAndroid && !engineRunning()}>
        <div class="flex items-center justify-between gap-3 border-b border-border bg-surface px-4 py-2">
          <span class="text-xs text-muted">
            EQ engine is not running (permission may have been revoked).
          </span>
          <button
            onClick={reEnableEngine}
            disabled={restarting()}
            class="shrink-0 rounded-md bg-foreground/10 px-3 py-1 text-xs font-medium text-foreground transition-colors hover:bg-foreground/20 disabled:opacity-50"
          >
            {restarting() ? "Re-enabling…" : "Re-enable EQ"}
          </button>
        </div>
      </Show>

      <div
        class={
          "flex-1 overflow-x-auto bg-sunken" + (bypassed() ? " opacity-60" : "")
        }
      >
        <div class="mx-auto flex h-full w-max items-center gap-3 p-4">
          <Index each={bands()}>
            {(band, i) => (
              <BandSlider
                settings={band()}
                onChange={(s) => updateBand(i, s)}
                onChangeEnd={applyEq}
              />
            )}
          </Index>
        </div>
      </div>

      <Dialog.Root open={open()} onOpenChange={handleOpenChange}>
        <Dialog.Portal>
          <Dialog.Overlay class="fixed inset-0 z-40 bg-black/60" />
          <Dialog.Content class="fixed left-1/2 top-1/2 z-50 flex max-h-[70vh] w-[340px] -translate-x-1/2 -translate-y-1/2 flex-col gap-3 rounded-lg border border-border bg-surface p-4 shadow-xl outline-none">
            <Dialog.Title class="sr-only">Presets</Dialog.Title>
            <Dialog.Description class="sr-only">
              Manage saved EQ presets
            </Dialog.Description>

            <div class="flex items-center gap-2">
              <input
                type="text"
                placeholder="Preset name"
                value={presetName()}
                onInput={(e) => setPresetName(e.currentTarget.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") createPreset();
                }}
                class="flex-1 rounded-md border border-border bg-background px-2 py-1.5 text-xs text-foreground outline-none focus:border-foreground"
              />
              <button
                onClick={createPreset}
                title="Create preset"
                class="grid size-8 place-items-center rounded-md text-muted transition-colors hover:bg-muted/10 hover:text-foreground"
              >
                <Plus class="size-4" />
              </button>
            </div>

            <div class="flex-1 space-y-1 overflow-y-auto">
              <For each={presets()}>
                {(p, i) => (
                  <Switch>
                    <Match when={confirm()?.index === i()}>
                      <div class="flex items-center gap-2 rounded-md bg-background px-2 py-2">
                        <span class="flex-1 text-xs text-foreground">
                          {confirm()?.kind === "save"
                            ? "Save changes"
                            : "Delete"}{" "}
                          "{p.preset_name}"?
                        </span>
                        <button
                          onClick={confirmYes}
                          class="rounded-md bg-foreground px-2 py-1 text-[10px] font-medium text-background hover:opacity-80"
                        >
                          Yes
                        </button>
                        <button
                          onClick={() => setConfirm(null)}
                          class="rounded-md border border-border px-2 py-1 text-[10px] text-muted hover:text-foreground"
                        >
                          No
                        </button>
                      </div>
                    </Match>

                    <Match when={editing() === i()}>
                      <div class="flex items-center gap-2 rounded-md bg-background px-2 py-1.5">
                        <input
                          type="text"
                          value={editValue()}
                          onInput={(e) => setEditValue(e.currentTarget.value)}
                          onKeyDown={(e) => {
                            if (e.key === "Enter") commitRename();
                            if (e.key === "Escape") cancelRename();
                          }}
                          onBlur={commitRename}
                          class="flex-1 rounded border border-border bg-background px-1.5 py-0.5 text-xs text-foreground outline-none"
                        />
                        <button
                          onClick={commitRename}
                          title="Confirm rename"
                          class="grid size-6 place-items-center text-muted hover:text-foreground"
                        >
                          <Check class="size-3.5" />
                        </button>
                        <button
                          onClick={cancelRename}
                          title="Cancel"
                          class="grid size-6 place-items-center text-muted hover:text-foreground"
                        >
                          <X class="size-3.5" />
                        </button>
                      </div>
                    </Match>

                    <Match when={true}>
                      <div class="flex items-center gap-1 rounded-md px-2 py-1.5 hover:bg-muted/10">
                        <button
                          onClick={() => loadPreset(p)}
                          title="Load preset"
                          class="flex-1 truncate text-left text-xs text-foreground"
                        >
                          {p.preset_name}
                        </button>
                        <button
                          onClick={() =>
                            setConfirm({ kind: "save", index: i() })
                          }
                          title="Save current settings to this preset"
                          class="grid size-6 place-items-center rounded text-muted hover:text-foreground"
                        >
                          <Save class="size-3.5" />
                        </button>
                        <button
                          onClick={() =>
                            setConfirm({ kind: "delete", index: i() })
                          }
                          title="Delete preset"
                          class="grid size-6 place-items-center rounded text-muted hover:text-red-400"
                        >
                          <Trash2 class="size-3.5" />
                        </button>
                        <button
                          onClick={() => startRename(i(), p.preset_name)}
                          title="Rename preset"
                          class="grid size-6 place-items-center rounded text-muted hover:text-foreground"
                        >
                          <Pencil class="size-3.5" />
                        </button>
                      </div>
                    </Match>
                  </Switch>
                )}
              </For>
            </div>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </div>
  );
}
