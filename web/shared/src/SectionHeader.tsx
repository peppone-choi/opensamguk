import type { HTMLAttributes, ReactNode } from 'react';

export type SectionTone = 'bronze' | 'rust' | 'info';

export type SectionHeaderProps = Omit<HTMLAttributes<HTMLDivElement>, 'title'> & {
  readonly title: ReactNode;
  readonly sub?: ReactNode;
  readonly tone?: SectionTone;
  /** 우측 액션(버튼·칩). */
  readonly actions?: ReactNode;
  readonly as?: 'h2' | 'h3' | 'h4' | 'div';
};

export function SectionHeader({ title, sub, tone = 'bronze', actions, as: Tag = 'h3', className = '', ...props }: SectionHeaderProps) {
  return (
    <div className={`os-section-header ${className}`.trim()} {...props}>
      <span className={`os-section-header__bar${tone === 'bronze' ? '' : ` os-section-header__bar--${tone}`}`} aria-hidden="true" />
      <Tag className="os-section-header__title">{title}</Tag>
      {sub != null && <span className="os-section-header__sub">{sub}</span>}
      {actions != null && (
        <>
          <span className="os-section-header__spacer" />
          <span className="os-section-header__actions">{actions}</span>
        </>
      )}
    </div>
  );
}
