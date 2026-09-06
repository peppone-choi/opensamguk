import type { HTMLAttributes } from 'react';

export type ChipTone = 'neutral' | 'bronze' | 'moss' | 'rust' | 'info';

export type ChipProps = HTMLAttributes<HTMLSpanElement> & {
  readonly tone?: ChipTone;
};

export function Chip({ tone = 'neutral', className = '', ...props }: ChipProps) {
  const classes = ['os-chip', tone === 'neutral' ? '' : `os-chip--${tone}`, className].filter(Boolean).join(' ');
  return <span className={classes} {...props} />;
}
