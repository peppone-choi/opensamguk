/** Rectangles are normalized against the browser-oriented original raster. */
export type CropRect = { x: number; y: number; width: number; height: number };
export const PORTRAIT_FRAMES = {
    hero: { label: '히어로', width: 633, height: 900 },
    card: { label: '카드', width: 148, height: 210 },
    icon: { label: '아이콘', width: 96, height: 96 },
} as const;
export type CropKind = keyof typeof PORTRAIT_FRAMES;
export type PortraitCrops = Record<CropKind, CropRect>;
export const CROP_KINDS: CropKind[] = ['hero', 'card', 'icon'];
export const MAX_SOURCE_BYTES = 8 * 1024 * 1024;
export const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value));

export function cropAt(w: number, h: number, kind: CropKind, zoom = 1, cx = 0.5, cy = 0.5): CropRect {
    const ratio = PORTRAIT_FRAMES[kind].width / PORTRAIT_FRAMES[kind].height;
    const width = Math.min(1, h * ratio / w) / clamp(zoom, 1, 8);
    const height = Math.min(1, w / ratio / h) / clamp(zoom, 1, 8);
    return { x: clamp(cx - width / 2, 0, 1 - width), y: clamp(cy - height / 2, 0, 1 - height), width, height };
}
export function initialCrops(w: number, h: number): PortraitCrops {
    return { hero: cropAt(w, h, 'hero'), card: cropAt(w, h, 'card'), icon: cropAt(w, h, 'icon') };
}
export function moveCrop(rect: CropRect, dx: number, dy: number): CropRect {
    return { ...rect, x: clamp(rect.x + dx, 0, 1 - rect.width), y: clamp(rect.y + dy, 0, 1 - rect.height) };
}
export function zoomCrop(rect: CropRect, w: number, h: number, kind: CropKind, zoom: number): CropRect {
    return cropAt(w, h, kind, zoom, rect.x + rect.width / 2, rect.y + rect.height / 2);
}
export function cropZoom(rect: CropRect, w: number, h: number, kind: CropKind): number {
    return cropAt(w, h, kind).width / rect.width;
}
export function validateSource(file: File): void {
    if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
        throw new Error('jpg·png·webp 이미지를 선택하세요.');
    }
    if (!file.size || file.size > MAX_SOURCE_BYTES) throw new Error('원본은 8MB 이하로 선택하세요.');
}
export function validateSourceDimensions(w: number, h: number): void {
    if (w < 64 || h < 64 || w > 8192 || h > 8192 || w * h > 24_000_000) {
        throw new Error('원본은 가로·세로 64~8192px, 총 2,400만 화소 이하여야 합니다.');
    }
}
