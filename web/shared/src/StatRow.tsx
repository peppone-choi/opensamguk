import type { HTMLAttributes } from 'react';

export type StatRowProps = HTMLAttributes<HTMLDivElement> & {
  readonly label: string;
  readonly value: number;
  readonly max: number;
  readonly display?: string;
};

/** 통·무·지·정·매 한 줄: 라벨 · 막대 · 수치. value/max 로 비율을 그린다. */
export function StatRow({ label, value, max, display, className = '', ...props }: StatRowProps) {
  const ratio = max > 0 ? Math.max(0, Math.min(1, value / max)) : 0;
  return (
    <div
      className={`os-stat-row ${className}`.trim()}
      role="meter"
      aria-label={label}
      aria-valuenow={value}
      aria-valuemin={0}
      aria-valuemax={max}
      {...props}
    >
      <span className="os-stat-row__label">{label}</span>
      <span className="os-stat-row__bar"><i style={{ width: `${ratio * 100}%` }} /></span>
      <span className="os-stat-row__value">{display ?? value}</span>
    </div>
  );
}
