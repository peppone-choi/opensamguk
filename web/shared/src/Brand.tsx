import type { ImgHTMLAttributes } from 'react';

const BRAND_SIZES = {
  small: { width: 64, height: 24 },
  large: { width: 86, height: 32 },
} as const;

export type BrandSize = keyof typeof BRAND_SIZES;

export type BrandProps = Omit<ImgHTMLAttributes<HTMLImageElement>, 'alt' | 'height' | 'src' | 'width'> & {
  readonly size?: BrandSize;
};

/**
 * 머리줄 워드마크(86×32 · 64×24). 표시의 2배인 172×64 · 256색 PNG(6.5 KB)를 쓴다 — 큰 워드마크(840×314)는 로그인 · 가입 화면용이다.
 * 정본 · 빌더는 assets/brand · tools/assets/build_brand_assets.py.
 */
export function Brand({ className = '', size = 'small', ...props }: BrandProps) {
  const dimensions = BRAND_SIZES[size];

  return (
    <img
      className={`os-brand os-brand--${size} ${className}`.trim()}
      src="/logo-wordmark-sm.png"
      alt="오픈삼국"
      width={dimensions.width}
      height={dimensions.height}
      decoding="async"
      fetchPriority="high"
      {...props}
    />
  );
}
