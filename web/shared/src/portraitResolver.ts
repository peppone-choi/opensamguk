// 초상 URL 계약 — 두 앱(web/game·web/gateway)이 같은 규칙을 쓰도록 여기 한 곳에 둔다.
// 원본은 각 앱의 lib/portrait.ts(레거시 util/getIconPath.ts 포팅)였고, 계약은 그대로다:
//   imgsvr=0 → 공유 CDN icons/<picture>[.jpg]  (화이트리스트 밖 값은 기본 초상)
//   imgsvr=1 → gateway-api canonical managed 파일명만 같은 출처 /d_pic/<파일명>, 아니면 기본 초상(날조 금지)
//   RTK14 안정 장수 ID 10001..11000 → portraits/rtk14/serving/{original|portrait|icon} (ADR-LITE-048)
// 어떤 경로든 깨질 수 있으므로 <img onError> 로 default.jpg 폴백을 함께 건다.
import type { SyntheticEvent } from 'react';

/** opensamguk-images jsDelivr 미러. 앱은 NEXT_PUBLIC_IMAGE_CDN 으로 덮어쓴 값을 resolver 에 넣는다. */
export const DEFAULT_IMAGE_CDN_BASE = 'https://cdn.jsdelivr.net/gh/peppone-choi/opensamguk-images';

/** 초상 변형: original=원본(633×900, 히어로) / portrait=148×210(카드) / icon=96×96(표·피드·댓글). */
export type PortraitVariant = 'original' | 'portrait' | 'icon';

const HAS_EXT = /\.(jpg|jpeg|png|gif|webp)$/i;
/** 공유 CDN 초상 파일명: 영숫자/밑줄/하이픈 + 선택적 지원 확장자. 경로 구분자·상위 이동은 거부(OPENSAM-214). */
const SHARED_ICON = /^[A-Za-z0-9_-]+(\.(jpg|jpeg|png|gif|webp))?$/i;
const RTK14_PORTRAIT = /^(\d{5})(?:\.png)?$/;
/** gateway-api canonical managed 파일명(LocalProfileIconStorage MANAGED_FILE): 8자리 hex + 관리 확장자. */
const MANAGED_ICON = /^[0-9a-f]{8}\.(avif|webp|jpg|png|gif)$/;

export interface PortraitResolver {
  readonly PORTRAIT_CDN: string;
  readonly RTK14_PORTRAIT_CDN: string;
  readonly RTK14_SERVING_CDN: string;
  readonly DEFAULT_PORTRAIT: string;
  rtk14OfficerId(picture?: string | null): number | null;
  portraitUrl(picture?: string | null, imageServer?: number | null): string;
  portraitVariantUrl(picture: string | null | undefined, imageServer: number | null | undefined, variant: PortraitVariant): string;
  onPortraitError(event: SyntheticEvent<HTMLImageElement>): void;
}

export function createPortraitResolver(cdnBase: string): PortraitResolver {
  const PORTRAIT_CDN = `${cdnBase}/icons`;
  const RTK14_SERVING_CDN = `${cdnBase}/portraits/rtk14/serving`;
  const RTK14_PORTRAIT_CDN = `${RTK14_SERVING_CDN}/portrait`;
  const DEFAULT_PORTRAIT = `${PORTRAIT_CDN}/default.jpg`;
  const variantFile: Record<PortraitVariant, (officerId: number) => string> = {
    original: (id) => `${RTK14_SERVING_CDN}/original/${id}.jpg`,
    portrait: (id) => `${RTK14_SERVING_CDN}/portrait/${id}.png`,
    icon: (id) => `${RTK14_SERVING_CDN}/icon/${id}.png`,
  };

  function rtk14OfficerId(picture?: string | null): number | null {
    const normalizedPicture = picture?.trim();
    if (!normalizedPicture) return null;
    const match = RTK14_PORTRAIT.exec(normalizedPicture);
    if (!match) return null;
    const officerId = Number(match[1]);
    return officerId >= 10001 && officerId <= 11000 ? officerId : null;
  }

  function portraitUrl(picture?: string | null, imageServer?: number | null): string {
    // trim 은 화이트리스트보다 먼저 — " 1001 " 같은 값이 폴백으로 새지 않게.
    const normalizedPicture = picture?.trim();
    if (!normalizedPicture) return DEFAULT_PORTRAIT;
    if (imageServer) {
      return MANAGED_ICON.test(normalizedPicture) ? `/d_pic/${normalizedPicture}` : DEFAULT_PORTRAIT;
    }
    const officerId = rtk14OfficerId(normalizedPicture);
    if (officerId !== null) return `${RTK14_PORTRAIT_CDN}/${officerId}.png`;
    if (!SHARED_ICON.test(normalizedPicture)) return DEFAULT_PORTRAIT;
    const file = HAS_EXT.test(normalizedPicture) ? normalizedPicture : `${normalizedPicture}.jpg`;
    return `${PORTRAIT_CDN}/${file}`;
  }

  function portraitVariantUrl(
    picture: string | null | undefined,
    imageServer: number | null | undefined,
    variant: PortraitVariant,
  ): string {
    if (imageServer) return portraitUrl(picture, imageServer);
    const officerId = rtk14OfficerId(picture);
    if (officerId !== null) return variantFile[variant](officerId);
    return portraitUrl(picture, imageServer);
  }

  // 기본 초상 자체가 깨졌을 때 무한 루프를 막는다. 절대 URL 로 정확히 비교한다 — endsWith('/default.jpg')
  // 는 다른 호스트의 nested/default.jpg 를 기본으로 오인한다(web/gateway 회귀 테스트).
  function onPortraitError(event: SyntheticEvent<HTMLImageElement>): void {
    const image = event.currentTarget;
    const baseUri = image.ownerDocument?.baseURI ?? 'http://localhost/';
    const fallbackUrl = new URL(DEFAULT_PORTRAIT, baseUri).href;
    const currentUrl = new URL(image.currentSrc || image.src, baseUri).href;
    if (currentUrl === fallbackUrl) return;
    image.src = fallbackUrl;
  }

  return { PORTRAIT_CDN, RTK14_PORTRAIT_CDN, RTK14_SERVING_CDN, DEFAULT_PORTRAIT, rtk14OfficerId, portraitUrl, portraitVariantUrl, onPortraitError };
}

export const defaultPortraitResolver: PortraitResolver = createPortraitResolver(DEFAULT_IMAGE_CDN_BASE);
