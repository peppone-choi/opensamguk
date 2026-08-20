import Image from 'next/image';

const BRAND_SIZES = {
  small: { width: 64, height: 24 },
  large: { width: 86, height: 32 },
} as const;

export type BrandSize = keyof typeof BRAND_SIZES;

export type BrandProps = {
  readonly size?: BrandSize;
};

export function Brand({ size = 'small' }: BrandProps) {
  const dimensions = BRAND_SIZES[size];

  return (
    <Image
      className={`os-brand os-brand--${size}`}
      src="/logo-wordmark.png"
      alt="오픈삼국"
      width={dimensions.width}
      height={dimensions.height}
      priority
    />
  );
}
