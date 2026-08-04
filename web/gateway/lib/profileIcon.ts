// 브라우저측 전콘 정규화. gateway-api ProfileIconDecoder(64~128px 정사각형) +
// profile-icon.max-bytes(51200)와 같은 경계를 목표로 크롭·축소할 뿐이며,
// 최종 판정은 서버가 한다 — 여기를 통과했다고 서버 거부를 성공으로 위장하지 않는다.
export const MAX_ICON_BYTES = 51200;
const ICON_DIMENSION = 128;
const MIN_DIMENSION = 64;

// 변환 없이 그대로 보내도 되는 원본 타입. LocalProfileIconStorage EXTENSIONS에 있어도
// gif는 뺀다 — 애니메이션 gif는 ProfileIconDecoder가 getNumImages != 1로 거부하는데
// 애니메이션 여부를 브라우저에서 싸게 알 수 없으므로 항상 첫 프레임으로 재인코딩한다.
const PASSTHROUGH_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/avif']);

// 128x128에서는 50KB 상한이 전혀 빡빡하지 않아 webp의 압축 이점이 의미가 없다.
// 그래서 서버 디코딩이 가장 확실한 jpeg를 먼저 쓰고, webp는 fallback으로만 둔다.
const ENCODE_TYPES = ['image/jpeg', 'image/webp'] as const;
const QUALITIES = [0.9, 0.75, 0.6, 0.45, 0.3];
const EXTENSION_BY_TYPE: Record<string, string> = {
    'image/jpeg': 'jpg',
    'image/webp': 'webp',
    // 미지원 타입을 요청하면 브라우저가 조용히 png로 떨어뜨린다. 서버가 받는 형식이라 그대로 쓴다.
    'image/png': 'png',
};

/** 이미 서버 규격 안이라 변환이 필요 없는가. */
export function isCompliant(type: string, size: number, width: number, height: number): boolean {
    return PASSTHROUGH_TYPES.has(type)
        && size <= MAX_ICON_BYTES
        && width === height
        && width >= MIN_DIMENSION
        && width <= ICON_DIMENSION;
}

/** 중앙 정사각형 크롭 영역. 짝수로 안 떨어지면 왼쪽/위를 덜 버린다. */
export function centerCrop(width: number, height: number): { sx: number; sy: number; size: number } {
    const size = Math.min(width, height);
    return { sx: Math.floor((width - size) / 2), sy: Math.floor((height - size) / 2), size };
}

function toBlob(canvas: HTMLCanvasElement, type: string, quality: number): Promise<Blob | null> {
    return new Promise((resolve) => canvas.toBlob(resolve, type, quality));
}

function encode(bitmap: ImageBitmap): Promise<File> {
    const canvas = document.createElement('canvas');
    canvas.width = ICON_DIMENSION;
    canvas.height = ICON_DIMENSION;
    const context = canvas.getContext('2d');
    if (!context) {
        throw new Error('이 브라우저에서 이미지를 변환할 수 없습니다. 50KB 이하 64~128px 정사각형 이미지를 직접 올려 주세요.');
    }
    // jpeg는 알파를 못 담는다 — 투명 배경이 검게 죽지 않도록 흰색을 먼저 깔아둔다.
    context.fillStyle = '#ffffff';
    context.fillRect(0, 0, ICON_DIMENSION, ICON_DIMENSION);
    context.imageSmoothingQuality = 'high';
    const { sx, sy, size } = centerCrop(bitmap.width, bitmap.height);
    context.drawImage(bitmap, sx, sy, size, size, 0, 0, ICON_DIMENSION, ICON_DIMENSION);
    return shrink(canvas);
}

async function shrink(canvas: HTMLCanvasElement): Promise<File> {
    for (const type of ENCODE_TYPES) {
        for (const quality of QUALITIES) {
            const blob = await toBlob(canvas, type, quality);
            if (!blob) break;
            const extension = EXTENSION_BY_TYPE[blob.type];
            if (extension && blob.size <= MAX_ICON_BYTES) {
                return new File([blob], `icon.${extension}`, { type: blob.type });
            }
            // 요청한 타입이 아니면 이 브라우저가 못 만드는 형식 — 품질을 더 낮춰봐야 소용없다.
            if (blob.type !== type) break;
        }
    }
    throw new Error('이미지를 50KB 이하로 줄이지 못했습니다. 더 단순한 이미지를 선택해 주세요.');
}

/**
 * 업로드용 파일을 만든다. 이미 규격에 맞으면 원본을 그대로 돌려주고,
 * 아니면 중앙 정사각형으로 잘라 128x128로 축소한 뒤 50KB 이하가 될 때까지 품질을 낮춘다.
 * 변환할 수 없으면 원인이 드러나는 메시지로 throw 한다 — 조용히 실패하지 않는다.
 */
export async function normalizeProfileIcon(file: File): Promise<File> {
    if (typeof globalThis.createImageBitmap !== 'function') {
        throw new Error('이 브라우저는 이미지 자동 변환을 지원하지 않습니다. 50KB 이하 64~128px 정사각형 이미지를 직접 올려 주세요.');
    }
    let bitmap: ImageBitmap;
    try {
        bitmap = await globalThis.createImageBitmap(file);
    } catch {
        throw new Error('이미지를 읽을 수 없습니다. jpg·png·gif·webp·avif 파일을 선택하세요.');
    }
    try {
        return isCompliant(file.type, file.size, bitmap.width, bitmap.height) ? file : await encode(bitmap);
    } finally {
        bitmap.close?.();
    }
}
