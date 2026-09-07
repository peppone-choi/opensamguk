import type { HTMLAttributes, ReactNode } from 'react';

export interface KVItem {
  readonly k: ReactNode;
  readonly v: ReactNode;
}

export type KVProps = Omit<HTMLAttributes<HTMLDListElement>, 'children'> & {
  readonly items: readonly KVItem[];
};

/** 키-값 격자(라벨 회색 · 값 mono tabular). 라벨 문자열은 부르는 쪽이 그대로 넘긴다 — 여기서 바꾸지 않는다. */
export function KV({ items, className = '', ...props }: KVProps) {
  return (
    <dl className={`os-kv ${className}`.trim()} {...props}>
      {items.map((item, index) => (
        <div key={index} style={{ display: 'contents' }}>
          <dt>{item.k}</dt>
          <dd>{item.v}</dd>
        </div>
      ))}
    </dl>
  );
}
