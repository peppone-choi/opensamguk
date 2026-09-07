'use client';

// 초상 3종 규칙(ADR-LITE-049 · S1):
//   hero  = 원본 633×900 (장수 상세 좌측·리플레이 對·모달 배경). 항상 하단 그라데이션 마스크. 잘라 쓰지 않고 위치만 잡는다.
//   card  = 148×210 (장수 기본 카드 126×178 · 휘하 44×62 · 조정 48×68 · 랭킹 상위 56×80 · 전투 부대 36×50)
//   icon  = 96×96  (48 게시글 작성자 · 40 목록 · 32 피드·표 · 28 댓글 · 24 서신·입찰자 · 20 표결자 스택)
// 정사각·각진 모서리(원형 금지). 국가색 링 2px 은 「내 장수·군주·현재 문맥 국가」에만 — reason 없이 링을 켤 수 없다.
// 비활성·사망은 grayscale + 60%. object-fit: contain(잘림 0, OPENSAM-100).
import { createContext, useContext, type CSSProperties, type ImgHTMLAttributes, type ReactNode } from 'react';
import { defaultPortraitResolver, type PortraitResolver, type PortraitVariant } from './portraitResolver';

const PortraitResolverContext = createContext<PortraitResolver>(defaultPortraitResolver);

export function PortraitResolverProvider({ resolver, children }: { readonly resolver: PortraitResolver; readonly children: ReactNode }) {
  return <PortraitResolverContext.Provider value={resolver}>{children}</PortraitResolverContext.Provider>;
}

export function usePortraitResolver(): PortraitResolver {
  return useContext(PortraitResolverContext);
}

export type PortraitSize =
  | 'hero'
  | 'card' | 'card-126' | 'card-56' | 'card-48' | 'card-44' | 'card-36'
  | 'icon' | 'icon-48' | 'icon-40' | 'icon-32' | 'icon-28' | 'icon-24' | 'icon-20';

export const PORTRAIT_SIZES: Record<PortraitSize, { readonly w: number | null; readonly h: number | null; readonly variant: PortraitVariant }> = {
  hero: { w: null, h: null, variant: 'original' },
  card: { w: 148, h: 210, variant: 'portrait' },
  'card-126': { w: 126, h: 178, variant: 'portrait' },
  'card-56': { w: 56, h: 80, variant: 'portrait' },
  'card-48': { w: 48, h: 68, variant: 'portrait' },
  'card-44': { w: 44, h: 62, variant: 'portrait' },
  'card-36': { w: 36, h: 50, variant: 'portrait' },
  icon: { w: 96, h: 96, variant: 'icon' },
  'icon-48': { w: 48, h: 48, variant: 'icon' },
  'icon-40': { w: 40, h: 40, variant: 'icon' },
  'icon-32': { w: 32, h: 32, variant: 'icon' },
  'icon-28': { w: 28, h: 28, variant: 'icon' },
  'icon-24': { w: 24, h: 24, variant: 'icon' },
  'icon-20': { w: 20, h: 20, variant: 'icon' },
};

/** 크기 프리셋이 요구하는 변형. 카드는 portrait, 아이콘은 icon, 히어로는 original. */
export function portraitVariantForSize(size: PortraitSize): PortraitVariant {
  return PORTRAIT_SIZES[size].variant;
}

export type PortraitRingReason = 'self' | 'ruler' | 'context';

export type PortraitProps = Omit<ImgHTMLAttributes<HTMLImageElement>, 'src' | 'alt' | 'width' | 'height'> & {
  readonly picture?: string | null;
  readonly imageServer?: number | null;
  readonly size: PortraitSize;
  readonly alt: string;
  /** 국가색 링. reason 이 없으면 링을 그리지 않는다(규칙 강제). */
  readonly ring?: { readonly color: string; readonly reason: PortraitRingReason } | null;
  readonly inactive?: boolean;
  readonly frameClassName?: string;
};

export function Portrait({ picture, imageServer, size, alt, ring, inactive = false, className = '', frameClassName = '', style, ...img }: PortraitProps) {
  const resolver = usePortraitResolver();
  const preset = PORTRAIT_SIZES[size];
  const src = resolver.portraitVariantUrl(picture, imageServer, preset.variant);
  const frameStyle: CSSProperties & Record<'--nation', string | undefined> = {
    width: preset.w ?? '100%',
    height: preset.h ?? '100%',
    '--nation': ring ? ring.color : undefined,
  };
  const frameClasses = [
    'os-portrait',
    `os-portrait--${size === 'hero' ? 'hero' : preset.variant}`,
    ring ? 'os-portrait--ring' : '',
    inactive ? 'os-portrait--inactive' : '',
    frameClassName,
  ].filter(Boolean).join(' ');

  return (
    <span className={frameClasses} style={frameStyle} data-size={size} data-variant={preset.variant} data-ring={ring?.reason}>
      <img
        {...img}
        className={className}
        src={src}
        alt={alt}
        width={preset.w ?? undefined}
        height={preset.h ?? undefined}
        loading={img.loading ?? 'lazy'}
        decoding="async"
        style={style}
        onError={(e) => { resolver.onPortraitError(e); img.onError?.(e); }}
      />
    </span>
  );
}

/** 열람자·표결자 스택 — 겹친 아이콘 20~24px. */
export function PortraitStack({ children, className = '', label }: { readonly children: ReactNode; readonly className?: string; readonly label?: string }) {
  return <span className={`os-portrait-stack ${className}`.trim()} role={label ? 'group' : undefined} aria-label={label}>{children}</span>;
}
