import type { HTMLAttributes } from 'react';

export type PanelProps = HTMLAttributes<HTMLElement> & {
  readonly as?: 'section' | 'div' | 'article' | 'aside';
  readonly frame?: 'none' | 'bronze' | 'rust';
};

/** 철판 패널(#1b201d + 1px 경계). 호버 강조 없음 — 카드가 아니라 구역이다. */
export function Panel({ as: Tag = 'section', frame = 'none', className = '', ...props }: PanelProps) {
  const classes = ['os-panel', 'os-panel--static', frame === 'none' ? '' : `os-frame--${frame}`, className].filter(Boolean).join(' ');
  return <Tag className={classes} {...props} />;
}

export type InsetProps = HTMLAttributes<HTMLDivElement>;

/** 패널 안쪽 움푹한 영역(#141816). */
export function Inset({ className = '', ...props }: InsetProps) {
  return <div className={`os-inset ${className}`.trim()} {...props} />;
}

export function Divider({ className = '', ...props }: HTMLAttributes<HTMLHRElement>) {
  return <hr className={`os-divider ${className}`.trim()} {...props} />;
}
