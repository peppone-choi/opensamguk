import type { AnchorHTMLAttributes, ReactNode } from 'react';

export type NavItemProps = AnchorHTMLAttributes<HTMLAnchorElement> & {
  readonly on?: boolean;
  readonly icon?: ReactNode;
};

/** 부서 나브 항목(44px). 활성은 청동 밑줄. */
export function NavItem({ on = false, icon, className = '', children, ...props }: NavItemProps) {
  return (
    <a className={`os-nav-item${on ? ' os-nav-item--on' : ''} ${className}`.trim()} aria-current={on ? 'page' : undefined} {...props}>
      {icon != null && <span className="os-nav-item__icon" aria-hidden="true">{icon}</span>}
      {children}
    </a>
  );
}
