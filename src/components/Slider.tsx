import { For, JSXElement, Show } from "solid-js";
import {
  Root as PopoverRoot,
  Trigger as PopoverTrigger,
  Portal as PopoverPortal,
  Content as PopoverContent,
} from "@kobalte/core/popover";
import { Root as SliderRoot, Fill, Input, Thumb, Track } from "@kobalte/core/slider";
const Popover = {
  Root: PopoverRoot,
  Trigger: PopoverTrigger,
  Portal: PopoverPortal,
  Content: PopoverContent,
};
const KSlider = { Root: SliderRoot, Track, Fill, Thumb, Input };
import Settings from "lucide-solid/icons/settings";
import NumberField from "@/components/NumberField.tsx";
import {
  BandKind,
  MAX_FREQ,
  MAX_GAIN,
  MAX_Q,
  MIN_FREQ,
  MIN_GAIN,
  MIN_Q,
  assertNever,
  isBandActive,
} from "@/eq.ts";

export interface BandSettings {
  kind: BandKind;
  Q: number;
  gain: number;
  center_freq: number;
}

export const DEFAULT_BAND: BandSettings = {
  kind: "PK",
  Q: 1,
  gain: 0,
  center_freq: 1000,
};

interface BandSliderProps {
  settings: BandSettings;
  onChange?: (settings: BandSettings) => void;
  onChangeEnd?: (settings: BandSettings) => void;
}

const GAIN_STEP = 0.5;
const Q_STEP = 0.1;

function formatFrequency(freq: number): string {
  if (!Number.isFinite(freq)) return "—";
  if (freq >= 1000) return `${Number((freq / 1000).toFixed(1))} kHz`;
  return `${freq} Hz`;
}

function formatQ(q: number): string {
  if (!Number.isFinite(q)) return "Q —";
  return `Q ${q.toFixed(2)}`;
}
function formatGain(gain: number): string {
  if (!Number.isFinite(gain)) return "—";
  return `${gain > 0 ? "+" : ""}${gain.toFixed(1)}`;
}

const inputClass =
  "rounded-md border border-border bg-background px-2 py-1 text-foreground outline-none focus:border-foreground";

const FILTER_TYPES = [
  { kind: "PK", label: "PEQ" },
  { kind: "LSQ", label: "LS" },
  { kind: "HSQ", label: "HS" },
] as const;

function filterLabel(kind: BandKind): string {
  switch (kind) {
    case "PK":
      return "PEQ";
    case "OFF":
      return "OFF";
    case "LSQ":
      return "LS";
    case "HSQ":
      return "HS";
    default:
      return assertNever(kind);
  }
}

export default function BandSlider(props: BandSliderProps): JSXElement {
  const disabled = () => !isBandActive(props.settings.kind);

  return (
    <div
      class={
        "flex w-16 flex-col items-center gap-2 rounded-xl border border-border bg-surface p-2 py-3 text-center transition-opacity" +
        (disabled() ? " opacity-50" : "")
      }
    >
      <Popover.Root>
        <Popover.Trigger class="grid size-8 place-items-center rounded-md text-muted transition-colors hover:bg-muted/10 hover:text-foreground data-[expanded]:text-foreground">
          <Settings class="size-4" />
        </Popover.Trigger>
        <Popover.Portal>
          <Popover.Content class="z-50 w-56 rounded-lg border border-border bg-surface p-4 shadow-xl outline-none data-[expanded]:animate-in data-[closed]:animate-out data-[closed]:fade-out-0 data-[expanded]:fade-in-0 data-[expanded]:zoom-in-95">
            <div class="flex flex-col gap-3">
              <label class="flex items-center justify-between text-xs">
                <span class="text-muted">Enabled</span>
                <input
                  type="checkbox"
                  checked={!disabled()}
                  onChange={(e) => {
                    props.onChange?.({
                      ...props.settings,
                      kind: e.currentTarget.checked
                        ? props.settings.kind === "OFF"
                          ? "PK"
                          : props.settings.kind
                        : "OFF",
                    });
                  }}
                  class="size-4 accent-foreground"
                />
              </label>
              <Show when={!disabled()}>
                <div class="flex flex-col gap-1 text-xs">
                  <span class="text-muted">Type</span>
                  <div class="flex gap-1">
                    <For each={FILTER_TYPES}>
                      {(opt) => (
                        <button
                          onClick={() =>
                            props.onChange?.({
                              ...props.settings,
                              kind: opt.kind,
                            })
                          }
                          class={
                            "flex-1 rounded-md border px-1 py-0.5 text-[10px] transition-colors " +
                            (props.settings.kind === opt.kind
                              ? "border-foreground text-foreground"
                              : "border-border text-muted hover:text-foreground")
                          }
                        >
                          {opt.label}
                        </button>
                      )}
                    </For>
                  </div>
                </div>
              </Show>
              <label class="flex flex-col gap-1 text-xs">
                <span class="text-muted">Frequency</span>
                <NumberField
                  value={props.settings.center_freq}
                  min={MIN_FREQ}
                  max={MAX_FREQ}
                  onCommit={(v) =>
                    props.onChange?.({ ...props.settings, center_freq: v })
                  }
                  class={inputClass}
                />
              </label>
              <label class="flex flex-col gap-1 text-xs">
                <span class="text-muted">Q value</span>
                <NumberField
                  value={props.settings.Q}
                  min={MIN_Q}
                  max={MAX_Q}
                  step={Q_STEP}
                  onCommit={(v) =>
                    props.onChange?.({ ...props.settings, Q: v })
                  }
                  class={inputClass}
                />
              </label>
              <label class="flex flex-col gap-1 text-xs">
                <span class="text-muted">Gain</span>
                <NumberField
                  value={props.settings.gain}
                  min={MIN_GAIN}
                  max={MAX_GAIN}
                  step={GAIN_STEP}
                  onCommit={(v) =>
                    props.onChange?.({ ...props.settings, gain: v })
                  }
                  class={inputClass}
                />
              </label>
            </div>
          </Popover.Content>
        </Popover.Portal>
      </Popover.Root>

      <span class="text-xs text-foreground">
        {formatFrequency(props.settings.center_freq)}
      </span>
      <span class="flex flex-col text-[10px] text-muted">
        <span>{disabled() ? "OFF" : filterLabel(props.settings.kind)}</span>
        <Show when={!disabled()}>
          <span>{formatQ(props.settings.Q)}</span>
        </Show>
      </span>

      <KSlider.Root
        class="flex flex-col items-center"
        value={[props.settings.gain]}
        minValue={MIN_GAIN}
        maxValue={MAX_GAIN}
        step={GAIN_STEP}
        orientation="vertical"
        disabled={disabled()}
        onChange={(value) => {
          props.onChange?.({ ...props.settings, gain: value[0] });
        }}
        onChangeEnd={(value) => {
          props.onChange?.({ ...props.settings, gain: value[0] });
          props.onChangeEnd?.({ ...props.settings, gain: value[0] });
        }}
      >
        <KSlider.Track class="relative h-40 w-3 rounded-full bg-muted/20">
          <KSlider.Fill class="absolute left-0 w-full rounded-full bg-foreground" />
          <KSlider.Thumb class="absolute left-1/2 -ml-2 size-4 rounded-full border border-border bg-background shadow focus:outline-none">
            <KSlider.Input />
          </KSlider.Thumb>
        </KSlider.Track>
      </KSlider.Root>

      <span class="text-xs text-foreground">
        {disabled() ? "OFF" : formatGain(props.settings.gain)}
      </span>
    </div>
  );
}
