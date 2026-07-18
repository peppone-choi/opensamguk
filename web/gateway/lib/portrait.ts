import { IMAGE_CDN_BASE } from './constants';

export const PORTRAIT_CDN = `${IMAGE_CDN_BASE}/icons`;
export const DEFAULT_PORTRAIT = `${PORTRAIT_CDN}/default.jpg`;

const HAS_EXT = /\.(jpg|jpeg|png|gif|webp)$/i;

// gateway-api가 발급하는 canonical managed 파일명(LocalProfileIconStorage MANAGED_FILE와 동일):
// 8자리 lowercase hex stem + 관리 확장자. imgsvr=1일 때만 같은 출처 nginx /d_pic/에서 제공된다.
const MANAGED_ICON = /^[0-9a-f]{8}\.(avif|webp|jpg|png|gif)$/;

export function portraitUrl(picture?: string | null, imageServer?: number | null): string {
    const normalizedPicture = picture?.trim();
    if (!normalizedPicture) return DEFAULT_PORTRAIT;
    if (imageServer) {
        return MANAGED_ICON.test(normalizedPicture) ? `/d_pic/${normalizedPicture}` : DEFAULT_PORTRAIT;
    }
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
