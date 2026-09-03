import { IMAGE_CDN_BASE } from './constants';

export const PORTRAIT_CDN = `${IMAGE_CDN_BASE}/icons`;
export const RTK14_PORTRAIT_CDN = `${IMAGE_CDN_BASE}/portraits/rtk14/serving/portrait`;
export const DEFAULT_PORTRAIT = `${PORTRAIT_CDN}/default.jpg`;

const HAS_EXT = /\.(jpg|jpeg|png|gif|webp)$/i;

// gateway-api가 발급하는 canonical managed 파일명(LocalProfileIconStorage MANAGED_FILE와 동일):
// 8자리 lowercase hex stem + 관리 확장자. imgsvr=1일 때만 같은 출처 nginx /d_pic/에서 제공된다.
const MANAGED_ICON = /^[0-9a-f]{8}\.(avif|webp|jpg|png|gif)$/;

// 공유 CDN 초상 파일명: 영숫자/밑줄/하이픈 + 선택적 지원 확장자. 경로 구분자·상위 이동은 거부.
// OPENSAM-214: 이 분기는 DB users.picture 를 URL 에 그대로 이어붙였다. web/game 쪽과 데이터
// 출처가 같으므로(동일 users.picture) 같은 화이트리스트를 건다. 두 앱의 계약은 동일해야 한다.
const SHARED_ICON = /^[A-Za-z0-9_-]+(\.(jpg|jpeg|png|gif|webp))?$/i;
const RTK14_PORTRAIT = /^(\d{5})(?:\.png)?$/;

export function portraitUrl(picture?: string | null, imageServer?: number | null): string {
    const normalizedPicture = picture?.trim();
    if (!normalizedPicture) return DEFAULT_PORTRAIT;
    if (imageServer) {
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

export function onPortraitError(event: React.SyntheticEvent<HTMLImageElement>): void {
    const image = event.currentTarget;
    const baseUri = image.ownerDocument.baseURI;
    const fallbackUrl = new URL(DEFAULT_PORTRAIT, baseUri).href;
    const currentUrl = new URL(image.currentSrc || image.src, baseUri).href;
    if (currentUrl === fallbackUrl) return;
    image.src = fallbackUrl;
}
