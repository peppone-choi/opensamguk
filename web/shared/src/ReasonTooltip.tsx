'use client';

import { useId, useState, type HTMLAttributes, type ReactNode } from 'react';

export type ReasonTooltipProps = Omit<HTMLAttributes<HTMLSpanElement>, 'children'> & {
  readonly reason: string;
  readonly children: ReactNode;
};

/** 비활성 항목의 「왜 못 쓰는지」 — 호버·포커스에 사유를 보여준다. 키보드로도 닿는다. */
export function ReasonTooltip({ reason, children, className = '', ...props }: ReasonTooltipProps) {
  const id = useId();
  const [open, setOpen] = useState(false);
  return (
    <span
      className={`os-reason ${className}`.trim()}
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
      onFocus={() => setOpen(true)}
      onBlur={() => setOpen(false)}
      aria-describedby={id}
      {...props}
    >
      {children}
      <span id={id} role="tooltip" className="os-reason__tip" hidden={!open}>{reason}</span>
    </span>
  );
}
