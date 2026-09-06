import type { HTMLAttributes, KeyboardEvent, ReactNode } from 'react';

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

/** 알약 탭(전체·표결·작전…). role=tablist, 선택은 aria-selected, 화살표/Home/End 로 이동(roving tabindex). */
export function PillTabs<K extends string>({ tabs, value, onChange, label, className = '', ...props }: PillTabsProps<K>) {
  const onKeyDown = (e: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const last = tabs.length - 1;
    let next: number | null = null;
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') next = index === last ? 0 : index + 1;
    else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') next = index === 0 ? last : index - 1;
    else if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = last;
    if (next == null) return;
    e.preventDefault();
    onChange(tabs[next].key);
    const el = (e.currentTarget.parentElement?.children[next] as HTMLElement | undefined);
    el?.focus();
  };
  return (
    <div className={`os-pill-tabs ${className}`.trim()} role="tablist" aria-label={label} {...props}>
      {tabs.map((tab, index) => {
        const on = tab.key === value;
        return (
          <button
            key={tab.key}
            type="button"
            role="tab"
            aria-selected={on}
            tabIndex={on ? 0 : -1}
            className={on ? 'os-pill-tabs__on' : undefined}
            onClick={() => onChange(tab.key)}
            onKeyDown={(e) => onKeyDown(e, index)}
          >
            {tab.label}
            {tab.count != null && <span className="os-num" style={{ marginLeft: 4 }}>{tab.count}</span>}
          </button>
        );
      })}
    </div>
  );
}
