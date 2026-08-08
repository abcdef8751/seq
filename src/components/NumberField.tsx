import { createEffect, createSignal, JSXElement } from "solid-js";

interface NumberFieldProps {
  value: number;
  min?: number;
  max?: number;
  step?: number;
  class?: string;
  placeholder?: string;
  onCommit: (value: number) => void;
}

export default function NumberField(props: NumberFieldProps): JSXElement {
  const [text, setText] = createSignal(String(props.value));
  let focused = false;

  const clamp = (v: number) =>
    Math.min(props.max ?? Infinity, Math.max(props.min ?? -Infinity, v));

  const commit = () => {
    const v = Number(text());
    if (Number.isFinite(v)) {
      const clamped = clamp(v);
      props.onCommit(clamped);
      setText(String(clamped));
    } else {
      setText(String(props.value));
    }
  };

  createEffect(() => {
    if (!focused) setText(String(props.value));
  });

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        commit();
        // Blur the active element so the soft keyboard dismisses after committing.
        (document.activeElement as HTMLInputElement | null)?.blur();
      }}
      class="contents"
    >
      <input
        type="number"
        inputmode="decimal"
        enterkeyhint="done"
        min={props.min}
        max={props.max}
        step={props.step}
        value={text()}
        placeholder={props.placeholder}
        onInput={(e) => setText(e.currentTarget.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.preventDefault();
            commit();
            e.currentTarget.blur();
          }
        }}
        onFocus={() => (focused = true)}
        onBlur={() => {
          focused = false;
          setText(String(props.value));
        }}
        class={props.class}
      />
    </form>
  );
}
