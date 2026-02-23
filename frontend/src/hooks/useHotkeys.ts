"use client";

import { useEffect, useCallback, useRef } from "react";

export interface HotkeyBinding {
  key: string;
  ctrl?: boolean;
  alt?: boolean;
  shift?: boolean;
  handler: () => void;
  /** If true, prevent default browser behavior */
  preventDefault?: boolean;
  /** Description for help display */
  description?: string;
}

/**
 * Global keyboard shortcut hook.
 * Ignores keypresses when the user is typing in an input/textarea/select.
 */
export function useHotkeys(bindings: HotkeyBinding[], enabled = true) {
  const bindingsRef = useRef(bindings);
  bindingsRef.current = bindings;

  useEffect(() => {
    if (!enabled) return;

    const handler = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement;
      const tagName = target.tagName.toLowerCase();
      if (
        tagName === "input" ||
        tagName === "textarea" ||
        tagName === "select" ||
        target.isContentEditable
      ) {
        // Only allow Escape in input fields
        if (e.key !== "Escape") return;
      }

      for (const binding of bindingsRef.current) {
        const keyMatch =
          e.key === binding.key ||
          e.key.toLowerCase() === binding.key.toLowerCase() ||
          e.code === binding.key;

        if (!keyMatch) continue;
        if (binding.ctrl && !e.ctrlKey && !e.metaKey) continue;
        if (binding.alt && !e.altKey) continue;
        if (binding.shift && !e.shiftKey) continue;
        if (!binding.ctrl && (e.ctrlKey || e.metaKey)) continue;
        if (!binding.alt && e.altKey) continue;

        if (binding.preventDefault !== false) {
          e.preventDefault();
          e.stopPropagation();
        }
        binding.handler();
        return;
      }
    };

    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [enabled]);
}

/**
 * Hook for number key selection (1-9) with Enter confirm and Escape cancel.
 */
export function useNumberKeySelection(options: {
  items: { key: string; action: () => void }[];
  onConfirm?: () => void;
  onCancel?: () => void;
  enabled?: boolean;
}) {
  const { items, onConfirm, onCancel, enabled = true } = options;

  const bindings: HotkeyBinding[] = [];

  for (let i = 0; i < Math.min(items.length, 9); i++) {
    const item = items[i];
    bindings.push({
      key: String(i + 1),
      handler: item.action,
      description: `Select ${item.key}`,
    });
  }

  if (onConfirm) {
    bindings.push({
      key: "Enter",
      handler: onConfirm,
      description: "Confirm",
    });
  }

  if (onCancel) {
    bindings.push({
      key: "Escape",
      handler: onCancel,
      description: "Cancel",
    });
  }

  useHotkeys(bindings, enabled);
}
