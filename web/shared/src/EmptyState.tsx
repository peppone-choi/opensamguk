import type { HTMLAttributes, ReactNode } from 'react';

export type EmptyStateProps = HTMLAttributes<HTMLDivElement> & {
  readonly title: ReactNode;
  readonly hint?: ReactNode;
};

/** 데이터 없음. 없는 값은 지어내지 않고 이 상태로 둔다. */
export function EmptyState({ title, hint, className = '', ...props }: EmptyStateProps) {
  return (
    <div className={`os-empty ${className}`.trim()} role="status" {...props}>
      <span className="os-empty__title">{title}</span>
      {hint != null && <span>{hint}</span>}
    </div>
  );
}
