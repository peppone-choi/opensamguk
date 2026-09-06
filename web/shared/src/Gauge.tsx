import type { HTMLAttributes, ReactNode } from 'react';

export type GaugeTone = 'moss' | 'bronze' | 'rust';

export type GaugeProps = HTMLAttributes<HTMLDivElement> & {
  readonly label: ReactNode;
  readonly value: number;
  readonly max: number;
  readonly display?: ReactNode;
  readonly tone?: GaugeTone;
};

/** 도시 8게이지 등: 상단 라벨/값, 하단 막대. */
export function Gauge({ label, value, max, display, tone = 'moss', className = '', ...props }: GaugeProps) {
  const ratio = max > 0 ? Math.max(0, Math.min(1, value / max)) : 0;
  const classes = ['os-gauge', tone === 'moss' ? '' : `os-gauge--${tone}`, className].filter(Boolean).join(' ');
  return (
    <div className={classes} role="meter" aria-label={typeof label === 'string' ? label : undefined} aria-valuenow={value} aria-valuemin={0} aria-valuemax={max} {...props}>
      <div className="os-gauge__top">
        <span>{label}</span>
        <span className="os-num">{display ?? `${value} / ${max}`}</span>
      </div>
      <div className="os-gauge__bar"><i style={{ width: `${ratio * 100}%` }} /></div>
    </div>
  );
}
