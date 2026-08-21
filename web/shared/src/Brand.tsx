import type { ImgHTMLAttributes } from 'react';

const BRAND_SIZES = {
  small: { width: 64, height: 24 },
  large: { width: 86, height: 32 },
} as const;

export type BrandSize = keyof typeof BRAND_SIZES;

export type BrandProps = Omit<ImgHTMLAttributes<HTMLImageElement>, 'alt' | 'height' | 'src' | 'width'> & {
  readonly size?: BrandSize;
};

export function Brand({ className = '', size = 'small', ...props }: BrandProps) {
  const dimensions = BRAND_SIZES[size];

  return (
    <img
      className={`os-brand os-brand--${size} ${className}`.trim()}
      src="/logo-wordmark.png"
      alt="오픈삼국"
      width={dimensions.width}
      height={dimensions.height}
      decoding="async"
      fetchPriority="high"
      {...props}
    />
  );
}
