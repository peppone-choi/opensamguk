import type { HTMLAttributes, ReactNode } from 'react';

export interface PillTab<K extends string = string> {
  readonly key: K;
  readonly label: ReactNode;
  readonly count?: number;
}

export type PillTabsProps<K extends string> = Omit<HTMLAttributes<HTMLDivElement>, 'onChange'> & {
  readonly tabs: readonly PillTab<K>[];
  readonly value: K;
  readonly onChange: (key: K) => void;
  readonly label: string;
};

/** 알약 탭(전체·표결·작전…). role=tablist, 선택은 aria-selected. */
export function PillTabs<K extends string>({ tabs, value, onChange, label, className = '', ...props }: PillTabsProps<K>) {
  return (
    <div className={`os-pill-tabs ${className}`.trim()} role="tablist" aria-label={label} {...props}>
      {tabs.map((tab) => {
        const on = tab.key === value;
        return (
          <button
            key={tab.key}
            type="button"
            role="tab"
            aria-selected={on}
            className={on ? 'os-pill-tabs__on' : undefined}
            onClick={() => onChange(tab.key)}
          >
            {tab.label}
            {tab.count != null && <span className="os-num" style={{ marginLeft: 4 }}>{tab.count}</span>}
          </button>
        );
      })}
    </div>
  );
}
