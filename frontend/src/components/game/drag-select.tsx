"use client";

import {
  useRef,
  useState,
  useCallback,
  useEffect,
  type ReactNode,
} from "react";

interface DragSelectProps {
  /** data attribute name used on children to identify selectable items */
  attribute: string;
  /** Selection overlay color */
  color?: string;
  /** Selection overlay opacity */
  opacity?: number;
  /** Currently selected keys */
  selected: Set<string>;
  /** Called when selection changes */
  onSelectionChange: (selected: Set<string>) => void;
  /** Called when drag completes */
  onDragDone?: (selected: Set<string>) => void;
  /** Called when drag starts */
  onDragStart?: () => void;
  /** Disable drag selection */
  disabled?: boolean;
  children: ReactNode;
  className?: string;
}

/**
 * Drag-to-select container. Children should have `data-{attribute}` attributes
 * on selectable elements. Ported from legacy DragSelect.vue.
 */
export function DragSelect({
  attribute,
  color = "#4299E1",
  opacity = 0.7,
  selected,
  onSelectionChange,
  onDragDone,
  onDragStart,
  disabled = false,
  children,
  className,
}: DragSelectProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const boxRef = useRef<HTMLDivElement | null>(null);
  const startRef = useRef({ x: 0, y: 0 });
  const isDragging = useRef(false);

  const getCoords = useCallback(
    (e: MouseEvent | Touch, rect: DOMRect) => ({
      x: e.clientX - rect.left,
      y: e.clientY - rect.top,
    }),
    [],
  );

  const intersect = useCallback(
    (box: HTMLDivElement, container: HTMLDivElement) => {
      const boxRect = box.getBoundingClientRect();
      const result = new Set<string>();
      const children = container.children;
      for (let i = 0; i < children.length; i++) {
        const el = children[i] as HTMLElement;
        const attr = el.getAttribute(`data-${attribute}`);
        if (attr === null) continue;
        const elRect = el.getBoundingClientRect();
        if (
          boxRect.left < elRect.right &&
          boxRect.right > elRect.left &&
          boxRect.top < elRect.bottom &&
          boxRect.bottom > elRect.top
        ) {
          result.add(attr);
        }
      }
      return result;
    },
    [attribute],
  );

  useEffect(() => {
    const container = containerRef.current;
    if (!container || disabled) return;

    const box = document.createElement("div");
    box.style.position = "absolute";
    box.style.backgroundColor = color;
    box.style.opacity = String(opacity);
    box.style.pointerEvents = "none";
    boxRef.current = box;

    const onStart = (e: MouseEvent | Touch) => {
      const rect = container.getBoundingClientRect();
      const start = getCoords(e, rect);
      startRef.current = start;
      isDragging.current = true;
      box.style.left = start.x + "px";
      box.style.top = start.y + "px";
      box.style.width = "0";
      box.style.height = "0";
      container.appendChild(box);
      onDragStart?.();
    };

    const onMove = (e: MouseEvent | Touch) => {
      if (!isDragging.current) return;
      const rect = container.getBoundingClientRect();
      const end = getCoords(e, rect);
      const start = startRef.current;
      box.style.left = Math.min(start.x, end.x) + "px";
      box.style.top = Math.min(start.y, end.y) + "px";
      box.style.width = Math.abs(end.x - start.x) + "px";
      box.style.height = Math.abs(end.y - start.y) + "px";
      onSelectionChange(intersect(box, container));
    };

    const onEnd = () => {
      if (!isDragging.current) return;
      isDragging.current = false;
      box.style.width = "0";
      box.style.height = "0";
      box.remove();
      onDragDone?.(selected);
    };

    const mouseDown = (e: MouseEvent) => onStart(e);
    const mouseMove = (e: MouseEvent) => onMove(e);
    const touchStart = (e: TouchEvent) => {
      e.preventDefault();
      onStart(e.touches[0]);
    };
    const touchMove = (e: TouchEvent) => {
      e.preventDefault();
      onMove(e.touches[0]);
    };

    container.addEventListener("mousedown", mouseDown);
    document.addEventListener("mousemove", mouseMove);
    document.addEventListener("mouseup", onEnd);
    container.addEventListener("touchstart", touchStart, { passive: false });
    document.addEventListener("touchmove", touchMove, { passive: false });
    document.addEventListener("touchend", onEnd);

    return () => {
      container.removeEventListener("mousedown", mouseDown);
      document.removeEventListener("mousemove", mouseMove);
      document.removeEventListener("mouseup", onEnd);
      container.removeEventListener("touchstart", touchStart);
      document.removeEventListener("touchmove", touchMove);
      document.removeEventListener("touchend", onEnd);
      box.remove();
    };
  }, [
    disabled,
    color,
    opacity,
    getCoords,
    intersect,
    onSelectionChange,
    onDragDone,
    onDragStart,
    selected,
  ]);

  return (
    <div
      ref={containerRef}
      className={className}
      style={{
        position: "relative",
        userSelect: disabled ? undefined : "none",
        overflow: "hidden",
        touchAction: disabled ? undefined : "none",
      }}
    >
      {children}
    </div>
  );
}
