// 장수/계정 초상(전콘) URL 헬퍼 — 레거시 util/getIconPath.ts 충실 포팅.
//
// 레거시 getIconPath(imgsvr, picture):
//   imgsvr=0 → `${sharedIcon}/${picture}`  (공유 아이콘 — opensamguk-images CDN icons/)
//   imgsvr=1 → `${root}/d_pic/${picture}`  (서버 로컬 업로드 — 같은 출처 nginx가 제공)
//
// opensamguk 매핑: 공유 초상은 CDN `${IMAGE_CDN_BASE}/icons/<picture>.jpg`에 있다(검증: icons/1001.jpg 200).
// 시드 데이터는 image_server=0(전부 공유) + picture=숫자코드("1001", 확장자 없음)이므로 ".jpg"를 붙인다.
// picture에 이미 확장자가 있으면(회원 업로드 등) 그대로 둔다.
// 서버 로컬 업로드(imageServer truthy)는 gateway-api가 발급한 canonical managed 파일명이면 같은 출처
// nginx /d_pic/<파일명>으로 해석한다(OPENSAM-93). managed 형식이 아니면 기본 초상으로 폴백(날조 금지).
// 공유(imageServer=0) 쪽도 같은 화이트리스트 원칙을 쓴다 — DB picture 값이 그대로 URL에 박히면
// 경로 구분자·상위 이동으로 CDN 밖을 가리킬 수 있다(OPENSAM-214).
// 어떤 경로든 깨질 수 있으므로 <img onError>로 default.jpg 폴백을 함께 건다(엑박 방지).

import { IMAGE_CDN_BASE } from './constants';

/** 공유 초상 CDN 디렉터리. */
export const PORTRAIT_CDN = `${IMAGE_CDN_BASE}/icons`;

/** RTK14 안정 장수 ID(10001-11000) 초상의 정본 서빙 디렉터리. */
export const RTK14_PORTRAIT_CDN = `${IMAGE_CDN_BASE}/portraits/rtk14/serving/portrait`;

/** 초상이 없거나 경로가 깨졌을 때의 기본 이미지(검증: icons/default.jpg 200). */
export const DEFAULT_PORTRAIT = `${PORTRAIT_CDN}/default.jpg`;

const HAS_EXT = /\.(jpg|jpeg|png|gif|webp)$/i;

/** 공유 CDN 초상 파일명: 영숫자/밑줄/하이픈 + 선택적 지원 확장자. 경로 구분자·상위 이동은 거부. */
const SHARED_ICON = /^[A-Za-z0-9_-]+(\.(jpg|jpeg|png|gif|webp))?$/i;

const RTK14_PORTRAIT = /^(\d{5})(?:\.png)?$/;

/** gateway-api canonical managed 파일명(LocalProfileIconStorage MANAGED_FILE): 8자리 hex + 관리 확장자. */
const MANAGED_ICON = /^[0-9a-f]{8}\.(avif|webp|jpg|png|gif)$/;

/** RTK14 초상 3종 서빙 루트(opensamguk-images `portraits/rtk14/serving/*`). ADR-LITE-048. */
export const RTK14_SERVING_CDN = `${IMAGE_CDN_BASE}/portraits/rtk14/serving`;

/** 초상 변형: original=원본(633×900, 히어로) / portrait=148×210(카드) / icon=96×96(표·피드·댓글). */
export type PortraitVariant = 'original' | 'portrait' | 'icon';

const RTK14_VARIANT_FILE: Record<PortraitVariant, (officerId: number) => string> = {
    original: (id) => `${RTK14_SERVING_CDN}/original/${id}.jpg`,
    portrait: (id) => `${RTK14_SERVING_CDN}/portrait/${id}.png`,
    icon: (id) => `${RTK14_SERVING_CDN}/icon/${id}.png`,
};

/** picture 값이 안정 RTK14 장수 ID(10001-11000)이면 그 ID, 아니면 null. trim 후 판정한다. */
export function rtk14OfficerId(picture?: string | null): number | null {
    const normalizedPicture = picture?.trim();
    if (!normalizedPicture) return null;
    const match = RTK14_PORTRAIT.exec(normalizedPicture);
    if (!match) return null;
    const officerId = Number(match[1]);
    return officerId >= 10001 && officerId <= 11000 ? officerId : null;
}

/**
 * 변형을 지정해 초상 URL을 만든다. RTK14 ID이면 세 변형 중 하나를, 그 외(공유 아이콘·서버 로컬·기본)는
 * 한 크기만 있으므로 portraitUrl 과 같은 값을 돌려준다. 서버 로컬(imageServer) 분기는 변형과 무관하다.
 */
export function portraitVariantUrl(
    picture: string | null | undefined,
    imageServer: number | null | undefined,
    variant: PortraitVariant,
): string {
    if (imageServer) return portraitUrl(picture, imageServer);
    const officerId = rtk14OfficerId(picture);
    if (officerId !== null) return RTK14_VARIANT_FILE[variant](officerId);
    return portraitUrl(picture, imageServer);
}

/**
 * 초상 URL을 만든다. picture 없음 또는 서버-로컬(imageServer) → 기본 초상.
 * @param picture     DB picture 컬럼(숫자코드 "1001" 또는 파일명).
 * @param imageServer 0=공유(CDN) / 1=서버로컬(미호스트 → 기본).
 *
 * 양쪽 분기 모두 화이트리스트를 통과하지 못한 값은 기본 초상으로 폴백한다.
 */
export function portraitUrl(picture?: string | null, imageServer?: number | null): string {
    // trim 은 web/gateway 사본과 동일 계약을 유지하기 위한 것이다. 화이트리스트보다 먼저
    // 돌려야 " 1001 " 같은 값이 폴백으로 새지 않는다(두 앱이 갈라지면 같은 계정이 앱마다
    // 다른 초상을 받는다).
    const normalizedPicture = picture?.trim();
    if (!normalizedPicture) return DEFAULT_PORTRAIT;
    if (imageServer) {
        // 서버 로컬 업로드 — canonical managed 파일명이면 같은 출처 /d_pic/, 아니면 폴백(날조 금지).
        return MANAGED_ICON.test(normalizedPicture) ? `/d_pic/${normalizedPicture}` : DEFAULT_PORTRAIT;
    }
    const rtk14Match = RTK14_PORTRAIT.exec(normalizedPicture);
    if (rtk14Match) {
        const officerId = Number(rtk14Match[1]);
        if (officerId >= 10001 && officerId <= 11000) {
            return `${RTK14_PORTRAIT_CDN}/${officerId}.png`;
        }
    }
    // 공유 CDN — 화이트리스트 밖 값(경로 주입·traversal 포함)은 폴백.
    if (!SHARED_ICON.test(normalizedPicture)) return DEFAULT_PORTRAIT;
    const file = HAS_EXT.test(normalizedPicture) ? normalizedPicture : `${normalizedPicture}.jpg`;
    return `${PORTRAIT_CDN}/${file}`;
}

/** <img onError>에 거는 기본-초상 폴백 핸들러(중복 호출 방지). */
export function onPortraitError(e: React.SyntheticEvent<HTMLImageElement>): void {
    const img = e.currentTarget;
    if (img.src.endsWith('/default.jpg')) return; // 이미 기본 — 무한 루프 방지
    img.src = DEFAULT_PORTRAIT;
}
