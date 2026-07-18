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
// 어떤 경로든 깨질 수 있으므로 <img onError>로 default.jpg 폴백을 함께 건다(엑박 방지).

import { IMAGE_CDN_BASE } from './constants';

/** 공유 초상 CDN 디렉터리. */
export const PORTRAIT_CDN = `${IMAGE_CDN_BASE}/icons`;

/** 초상이 없거나 경로가 깨졌을 때의 기본 이미지(검증: icons/default.jpg 200). */
export const DEFAULT_PORTRAIT = `${PORTRAIT_CDN}/default.jpg`;

const HAS_EXT = /\.(jpg|jpeg|png|gif|webp)$/i;

/** gateway-api canonical managed 파일명(LocalProfileIconStorage MANAGED_FILE): 8자리 hex + 관리 확장자. */
const MANAGED_ICON = /^[0-9a-f]{8}\.(avif|webp|jpg|png|gif)$/;

/**
 * 초상 URL을 만든다. picture 없음 또는 서버-로컬(imageServer) → 기본 초상.
 * @param picture     DB picture 컬럼(숫자코드 "1001" 또는 파일명).
 * @param imageServer 0=공유(CDN) / 1=서버로컬(미호스트 → 기본).
 */
export function portraitUrl(picture?: string | null, imageServer?: number | null): string {
    if (!picture) return DEFAULT_PORTRAIT;
    if (imageServer) {
        // 서버 로컬 업로드 — canonical managed 파일명이면 같은 출처 /d_pic/, 아니면 폴백(날조 금지).
        return MANAGED_ICON.test(picture) ? `/d_pic/${picture}` : DEFAULT_PORTRAIT;
    }
    const file = HAS_EXT.test(picture) ? picture : `${picture}.jpg`;
    return `${PORTRAIT_CDN}/${file}`;
}

/** <img onError>에 거는 기본-초상 폴백 핸들러(중복 호출 방지). */
export function onPortraitError(e: React.SyntheticEvent<HTMLImageElement>): void {
    const img = e.currentTarget;
    if (img.src.endsWith('/default.jpg')) return; // 이미 기본 — 무한 루프 방지
    img.src = DEFAULT_PORTRAIT;
}
