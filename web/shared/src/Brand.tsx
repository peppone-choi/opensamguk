import type { HTMLAttributes } from 'react';

export type BrandProps = HTMLAttributes<HTMLSpanElement> & {
  readonly label?: string;
};

export function Brand({ className = '', label = '오픈삼국', ...props }: BrandProps) {
  return (
    <span className={`os-brand ${className}`.trim()} {...props}>
      {label}
    </span>
  );
}
