"use client";

import { useState, useRef, useCallback, useEffect } from "react";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";

interface NumberInputWithInfoProps {
  value: number;
  onChange: (value: number) => void;
  min?: number;
  max?: number;
  step?: number;
  title?: string;
  titleRight?: boolean;
  readonly?: boolean;
  integer?: boolean;
  className?: string;
  children?: React.ReactNode;
}

/**
 * Number input that shows formatted (locale) value when not focused,
 * and raw numeric input when editing. Ported from legacy NumberInputWithInfo.vue.
 */
export function NumberInputWithInfo({
  value,
  onChange,
  min = 0,
  max,
  step,
  title,
  titleRight = false,
  readonly: isReadonly = false,
  integer = true,
  className,
  children,
}: NumberInputWithInfoProps) {
  const [editMode, setEditMode] = useState(false);
  const [rawValue, setRawValue] = useState(value);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    setRawValue(value);
  }, [value]);

  const clampAndEmit = useCallback(
    (v: number) => {
      let clamped = integer ? Math.round(v) : v;
      if (min !== undefined) clamped = Math.max(min, clamped);
      if (max !== undefined) clamped = Math.min(max, clamped);
      setRawValue(clamped);
      onChange(clamped);
    },
    [min, max, integer, onChange],
  );

  const handleBlur = () => {
    setEditMode(false);
    clampAndEmit(rawValue);
  };

  const handleFocusText = () => {
    if (isReadonly) return;
    setEditMode(true);
    setTimeout(() => inputRef.current?.focus(), 0);
  };

  const label = title ? (
    <label className="text-sm text-muted-foreground whitespace-nowrap">
      {title}
    </label>
  ) : null;

  return (
    <div className={cn("space-y-0.5", className)}>
      <div className="flex items-center gap-2">
        {!titleRight && label}
        <div className="flex-1">
          {editMode ? (
            <Input
              ref={inputRef}
              type="number"
              value={rawValue}
              step={step}
              min={min}
              max={max}
              onChange={(e) => {
                const v = Number(e.target.value);
                setRawValue(v);
                onChange(integer ? Math.round(v) : v);
              }}
              onBlur={handleBlur}
              className="text-xs h-8 tabular-nums"
            />
          ) : (
            <Input
              type="text"
              readOnly={isReadonly}
              value={rawValue.toLocaleString()}
              onFocus={handleFocusText}
              className="text-xs h-8 tabular-nums cursor-pointer"
            />
          )}
        </div>
        {titleRight && label}
      </div>
      {children && (
        <p className="text-xs text-muted-foreground text-right">{children}</p>
      )}
    </div>
  );
}
