import type { HTMLAttributes, ReactNode } from 'react';

/** 빈 상태 일러스트(ADR-LITE-049 Phase 5 · I-2). 정본은 opensamguk-images `assets/ui-illustrations/`, 여기엔 export 만 있다. */
export type EmptyIllustration = 'records' | 'posts' | 'map';

export const EMPTY_ILLUSTRATION_PATH = '/illustrations';
export const EMPTY_ILLUSTRATION_FILE: Record<EmptyIllustration, string> = {
  records: 'records-empty',
  posts: 'posts-empty',
  map: 'map-pending',
};

export type EmptyStateProps = HTMLAttributes<HTMLDivElement> & {
  readonly title: ReactNode;
  readonly hint?: ReactNode;
  /** 있으면 96×96 일러스트를 제목 위에 그린다(장식, alt 없음). */
  readonly illustration?: EmptyIllustration;
};

/** 데이터 없음. 없는 값은 지어내지 않고 이 상태로 둔다. */
export function EmptyState({ title, hint, illustration, className = '', ...props }: EmptyStateProps) {
  return (
    <div className={`os-empty ${className}`.trim()} role="status" {...props}>
      {illustration != null && (
        // eslint-disable-next-line @next/next/no-img-element -- 정적 SVG, 최적화 대상 아님
        <img
          className="os-empty__art"
          src={`${EMPTY_ILLUSTRATION_PATH}/${EMPTY_ILLUSTRATION_FILE[illustration]}.svg`}
          alt=""
          width={96}
          height={96}
          aria-hidden="true"
          data-illustration={illustration}
        />
      )}
      <span className="os-empty__title">{title}</span>
      {hint != null && <span>{hint}</span>}
    </div>
  );
}
