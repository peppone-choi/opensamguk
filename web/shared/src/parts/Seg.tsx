'use client';

import { useRef, type KeyboardEvent, type ReactNode } from 'react';

export interface SegOption<T extends string | number> {
  readonly value: T;
  readonly label: ReactNode;
  /** 수 붙임(예: 묶음 인원). null 은 「—」(아직 모름). 없으면 붙이지 않는다. */
  readonly count?: number | null;
}

export interface SegProps<T extends string | number> {
  /** 무리 이름(스크린리더). 예: 「후보 묶음」. */
  readonly label: string;
  readonly options: readonly SegOption<T>[];
  readonly value: T;
  readonly onChange: (value: T) => void;
  /** 좁은 폭에서 가로로 민다(넘치면 줄 안에서 스크롤). */
  readonly scroll?: boolean;
  readonly className?: string;
}

/**
 * 나눔 선택(보드 Segmented) — 하나만 고른다. 누를 영역 44, 고른 칸은 청동. radiogroup/radio 로 읽히고
 * 고른 칸만 Tab 순서에 든다. 좌우(위아래) 화살표 · Home · End 로 옮기면 바로 고른다.
 */
export function Seg<T extends string | number>({ label, options, value, onChange, scroll = false, className = '' }: SegProps<T>) {
  const refs = useRef<Array<HTMLButtonElement | null>>([]);
  const current = Math.max(0, options.findIndex((o) => o.value === value));

  const move = (event: KeyboardEvent<HTMLDivElement>) => {
    const last = options.length - 1;
    const next = {
      ArrowRight: current === last ? 0 : current + 1,
      ArrowDown: current === last ? 0 : current + 1,
      ArrowLeft: current === 0 ? last : current - 1,
      ArrowUp: current === 0 ? last : current - 1,
      Home: 0,
      End: last,
    }[event.key];
    if (next === undefined || options.length === 0) return;
    event.preventDefault();
    onChange(options[next].value);
    refs.current[next]?.focus();
  };

  return (
    <div
      className={['os-seg', scroll ? 'os-seg--scroll' : '', className].filter(Boolean).join(' ')}
      role="radiogroup"
      aria-label={label}
      onKeyDown={move}
    >
      {options.map((o, i) => {
        const on = i === current;
        return (
          <button
            key={String(o.value)}
            ref={(el) => { refs.current[i] = el; }}
            type="button"
            role="radio"
            aria-checked={on}
            tabIndex={on ? 0 : -1}
            className={['os-seg__item', on ? 'os-seg__item--on' : ''].filter(Boolean).join(' ')}
            onClick={() => onChange(o.value)}
          >
            {o.label}
            {o.count === undefined ? null : <span className="os-seg__n">{o.count === null ? '—' : o.count}</span>}
          </button>
        );
      })}
    </div>
  );
}
