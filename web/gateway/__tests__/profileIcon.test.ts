import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MAX_ICON_BYTES, centerCrop, isCompliant, normalizeProfileIcon } from '@/lib/profileIcon';

type StubContext = { drawImage: ReturnType<typeof vi.fn>; fillRect: ReturnType<typeof vi.fn> };

function blob(bytes: number, type: string): Blob {
    return new Blob([new Uint8Array(bytes)], { type });
}

function file(bytes: number, name = 'photo.jpg', type = 'image/jpeg'): File {
    return new File([new Uint8Array(bytes)], name, { type });
}

function stubBitmap(width: number, height: number): { width: number; height: number; close: ReturnType<typeof vi.fn> } {
    const bitmap = { width, height, close: vi.fn() };
    vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue(bitmap));
    return bitmap;
}

// jsdom엔 canvas 인코더가 없다 — 2d 컨텍스트와 toBlob을 스텁해 크롭 좌표와 인코딩 재시도 정책만 검증한다.
function stubCanvas(encoder: (type: string, quality: number) => Blob | null): StubContext {
    const context: StubContext = { drawImage: vi.fn(), fillRect: vi.fn() };
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(context as never);
    vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation((callback, type, quality) => {
        callback(encoder(String(type), Number(quality)));
    });
    return context;
}

describe('centerCrop', () => {
    it.each([
        [100, 100, { sx: 0, sy: 0, size: 100 }],
        [200, 100, { sx: 50, sy: 0, size: 100 }],
        [100, 200, { sx: 0, sy: 50, size: 100 }],
        [4000, 3000, { sx: 500, sy: 0, size: 3000 }],
        // 홀수 여백은 내림 — 왼쪽/위를 한 픽셀 덜 버린다.
        [101, 100, { sx: 0, sy: 0, size: 100 }],
        [103, 100, { sx: 1, sy: 0, size: 100 }],
    ])('crops %ix%i to the centered square', (width, height, expected) => {
        expect(centerCrop(width, height)).toEqual(expected);
    });
});

describe('isCompliant', () => {
    it.each([
        ['image/png', 2048, 96, 96, true],
        ['image/jpeg', MAX_ICON_BYTES, 64, 64, true],
        ['image/webp', MAX_ICON_BYTES, 128, 128, true],
        ['image/avif', 2048, 128, 128, true],
        ['image/png', MAX_ICON_BYTES + 1, 96, 96, false],
        ['image/png', 2048, 63, 63, false],
        ['image/png', 2048, 129, 129, false],
        ['image/png', 2048, 64, 128, false],
        // gif는 애니메이션일 수 있어 서버가 거부한다 — 규격에 맞아도 통과시키지 않는다.
        ['image/gif', 2048, 96, 96, false],
        // 브라우저가 타입을 모르는 파일(bmp/ico/svg 등)도 그대로 보내지 않는다.
        ['image/bmp', 2048, 96, 96, false],
        ['', 2048, 96, 96, false],
    ])('%s at %i bytes %ix%i -> %s', (type, size, width, height, expected) => {
        expect(isCompliant(type, size, width, height)).toBe(expected);
    });
});

describe('normalizeProfileIcon', () => {
    beforeEach(() => {
        stubBitmap(96, 96);
        stubCanvas((type) => blob(8_000, type));
    });

    afterEach(() => {
        vi.restoreAllMocks();
        vi.unstubAllGlobals();
    });

    it('returns the original file untouched when it already meets the server spec', async () => {
        const original = file(2048, 'icon.png', 'image/png');

        await expect(normalizeProfileIcon(original)).resolves.toBe(original);
        expect(HTMLCanvasElement.prototype.toBlob).not.toHaveBeenCalled();
    });

    it('re-encodes a spec-sized gif instead of risking an animated one', async () => {
        await expect(normalizeProfileIcon(file(2048, 'anim.gif', 'image/gif')))
            .resolves.toHaveProperty('type', 'image/jpeg');
        expect(HTMLCanvasElement.prototype.toBlob).toHaveBeenCalled();
    });

    it('re-encodes an oversized photo to a jpeg under the byte cap', async () => {
        stubBitmap(4000, 3000);

        const result = await normalizeProfileIcon(file(3_000_000));

        expect(result.type).toBe('image/jpeg');
        expect(result.name).toBe('icon.jpg');
        expect(result.size).toBeLessThanOrEqual(MAX_ICON_BYTES);
    });

    it('draws the centered square onto the full 128x128 canvas', async () => {
        const bitmap = stubBitmap(4000, 3000);
        const context = stubCanvas((type) => blob(8_000, type));

        await normalizeProfileIcon(file(3_000_000));

        // 4000x3000 → 중앙 3000x3000을 잘라 128x128 전체에 그린다.
        expect(context.drawImage).toHaveBeenCalledWith(bitmap, 500, 0, 3000, 3000, 0, 0, 128, 128);
        // jpeg 알파 손실 방지용 흰 배경이 먼저 깔린다.
        expect(context.fillRect).toHaveBeenCalledWith(0, 0, 128, 128);
    });

    it('re-encodes an off-spec but small image (dimensions alone can fail the spec)', async () => {
        stubBitmap(32, 32);

        await expect(normalizeProfileIcon(file(2048))).resolves.toHaveProperty('type', 'image/jpeg');
        expect(HTMLCanvasElement.prototype.toBlob).toHaveBeenCalled();
    });

    it('steps quality down until the blob fits under 50KB', async () => {
        stubBitmap(4000, 3000);
        stubCanvas((type, quality) => blob(quality >= 0.75 ? 60_000 : 30_000, type));

        const result = await normalizeProfileIcon(file(3_000_000));

        expect(result.size).toBe(30_000);
        // 0.9 / 0.75 실패 후 0.6에서 통과 — 세 번째 시도.
        expect(HTMLCanvasElement.prototype.toBlob).toHaveBeenCalledTimes(3);
    });

    it('accepts the png a browser silently falls back to when it cannot encode the asked type', async () => {
        stubBitmap(4000, 3000);
        stubCanvas(() => blob(20_000, 'image/png'));

        const result = await normalizeProfileIcon(file(3_000_000));

        expect(result.type).toBe('image/png');
        expect(result.name).toBe('icon.png');
        // 요청과 다른 타입이 돌아온 순간 품질 재시도를 접고 바로 받아들인다.
        expect(HTMLCanvasElement.prototype.toBlob).toHaveBeenCalledTimes(1);
    });

    it('falls through to webp when the jpeg fallback is itself too large', async () => {
        stubBitmap(4000, 3000);
        stubCanvas((type) => (type === 'image/jpeg' ? blob(60_000, 'image/png') : blob(9_000, 'image/webp')));

        const result = await normalizeProfileIcon(file(3_000_000));

        expect(result.type).toBe('image/webp');
        expect(result.name).toBe('icon.webp');
    });

    it('reports a clear reason when nothing gets under the cap', async () => {
        stubBitmap(4000, 3000);
        stubCanvas((type) => blob(60_000, type));

        await expect(normalizeProfileIcon(file(3_000_000)))
            .rejects.toThrow('이미지를 50KB 이하로 줄이지 못했습니다. 더 단순한 이미지를 선택해 주세요.');
    });

    it('reports a clear reason when the browser has no createImageBitmap', async () => {
        vi.stubGlobal('createImageBitmap', undefined);

        await expect(normalizeProfileIcon(file(3_000_000)))
            .rejects.toThrow('이 브라우저는 이미지 자동 변환을 지원하지 않습니다.');
    });

    it('reports a clear reason when the file is not a decodable image', async () => {
        vi.stubGlobal('createImageBitmap', vi.fn().mockRejectedValue(new Error('decode failed')));

        await expect(normalizeProfileIcon(file(3_000_000, 'clip.heic', 'image/heic')))
            .rejects.toThrow('이미지를 읽을 수 없습니다.');
    });

    it('reports a clear reason when no 2d context is available', async () => {
        stubBitmap(4000, 3000);
        vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);

        await expect(normalizeProfileIcon(file(3_000_000)))
            .rejects.toThrow('이 브라우저에서 이미지를 변환할 수 없습니다.');
    });

    it('closes the decoded bitmap on every path', async () => {
        const bitmap = stubBitmap(4000, 3000);
        stubCanvas((type) => blob(60_000, type));

        await expect(normalizeProfileIcon(file(3_000_000))).rejects.toThrow();
        expect(bitmap.close).toHaveBeenCalled();
    });
});
