// 초상 URL 헬퍼 — 계약 본체는 @opensamguk/ui 의 createPortraitResolver 에 있다(두 앱 동일 계약).
// 이 파일은 앱의 IMAGE_CDN_BASE(NEXT_PUBLIC_IMAGE_CDN 덮어쓰기 가능)로 묶은 resolver 를 기존 이름으로 재수출한다.
//   imgsvr=0 → 공유 CDN icons/<picture>[.jpg] (화이트리스트 밖 값은 기본 초상, OPENSAM-214)
//   imgsvr=1 → gateway-api canonical managed 파일명만 /d_pic/, 아니면 기본 초상(날조 금지, OPENSAM-93)
//   RTK14 안정 장수 ID 10001..11000 → portraits/rtk14/serving/{original|portrait|icon} (ADR-LITE-048)
import { createPortraitResolver } from '@opensamguk/ui';
import { IMAGE_CDN_BASE } from './constants';

export type { PortraitResolver, PortraitVariant } from '@opensamguk/ui';

/** 이 앱의 초상 resolver. Portrait 컴포넌트에는 PortraitResolverProvider 로 넘긴다. */
export const portraitResolver = createPortraitResolver(IMAGE_CDN_BASE);

/** 공유 초상 CDN 디렉터리. */
export const PORTRAIT_CDN = portraitResolver.PORTRAIT_CDN;
/** RTK14 안정 장수 ID(10001-11000) 초상의 정본 서빙 디렉터리(portrait 변형). */
export const RTK14_PORTRAIT_CDN = portraitResolver.RTK14_PORTRAIT_CDN;
/** RTK14 초상 3종 서빙 루트. */
export const RTK14_SERVING_CDN = portraitResolver.RTK14_SERVING_CDN;
/** 초상이 없거나 경로가 깨졌을 때의 기본 이미지. */
export const DEFAULT_PORTRAIT = portraitResolver.DEFAULT_PORTRAIT;

export const rtk14OfficerId: typeof portraitResolver.rtk14OfficerId = (picture) => portraitResolver.rtk14OfficerId(picture);
export const portraitUrl: typeof portraitResolver.portraitUrl = (picture, imageServer) => portraitResolver.portraitUrl(picture, imageServer);
export const portraitVariantUrl: typeof portraitResolver.portraitVariantUrl = (picture, imageServer, variant) =>
    portraitResolver.portraitVariantUrl(picture, imageServer, variant);
export const onPortraitError: typeof portraitResolver.onPortraitError = (event) => portraitResolver.onPortraitError(event);
