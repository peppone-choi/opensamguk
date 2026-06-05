// 순수 로직 단위 테스트 — lib/flagTint.ts 깃발 동적 착색.
// 라이브 백엔드/네트워크 불필요(headless). Image/canvas 2D 컨텍스트를 결정적 fake 로 모킹해
// tintFlag() 의 색 변환 계약을 검증한다:
//   1) 색마다 정확히 FLAG_FRAMES(4) 프레임 dataURL 을 만든다.
//   2) 무채색 천 픽셀(grayscale)을 nation 색의 색상(hue)으로 교체한다 — 회색 입력이 유채색 출력으로.
//   3) 명도 앵커: 천 명도(질감)는 nation 색의 명도 주변으로 압축되되, 같은 색이면 같은 결과(캐시 일관).
//   4) 알파 0 픽셀은 건드리지 않는다(투명 보존).
//   5) 깃대(pole) 레이어가 천 위에 합성된다(drawImage 2회: cloth 후 pole).
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { tintFlag, FLAG_FRAMES } from '@/lib/flagTint';

// ── 결정적 fake 캔버스/이미지 환경 ───────────────────────────────────────────
// 천 1픽셀(중간 회색 128,128,128, 불투명) + 1픽셀(완전 투명) = 2px 이미지를 가정.
// putImageData 시 tintFrame 이 기록한 RGB 를 가로채 마지막 합성 결과를 노출한다.

const CLOTH_W = 2;
const CLOTH_H = 1;

// 각 tintFrame 호출이 putImageData 한 픽셀 데이터를 모은다.
let lastPutData: Uint8ClampedArray | null = null;
// drawImage 호출 인자(어떤 이미지가 어떤 순서로 그려졌는지) 기록.
let drawCalls: string[] = [];

function makeImageData(): { data: Uint8ClampedArray; width: number; height: number } {
    // 천: 중간 회색 불투명 + 투명 픽셀.
    const data = new Uint8ClampedArray([128, 128, 128, 255, 10, 20, 30, 0]);
    return { data, width: CLOTH_W, height: CLOTH_H };
}

function makeFakeCtx() {
    return {
        drawImage: (img: { _tag?: string }) => {
            drawCalls.push(img?._tag ?? 'unknown');
        },
        getImageData: () => makeImageData(),
        putImageData: (id: { data: Uint8ClampedArray }) => {
            lastPutData = id.data;
        },
    };
}

beforeEach(() => {
    drawCalls = [];
    lastPutData = null;

    // canvas + 2D 컨텍스트 모킹.
    vi.spyOn(document, 'createElement').mockImplementation(((tag: string) => {
        if (tag === 'canvas') {
            const ctx = makeFakeCtx();
            return {
                width: 0,
                height: 0,
                getContext: () => ctx,
                // dataURL 은 색 검증 대상 아님 — 안정적 placeholder.
                toDataURL: () => 'data:image/png;base64,STUB',
            } as unknown as HTMLElement;
        }
        // 그 외 태그는 실제 구현으로 위임.
        return document.createElementNS('http://www.w3.org/1999/xhtml', tag) as unknown as HTMLElement;
    }) as typeof document.createElement);

    // Image 모킹 — src 지정 즉시 onload(동기). cloth/pole 을 _tag 로 구분.
    class FakeImage {
        _tag = 'cloth';
        width = CLOTH_W;
        height = CLOTH_H;
        onload: (() => void) | null = null;
        onerror: (() => void) | null = null;
        set src(v: string) {
            this._tag = v.includes('pole') ? 'pole' : 'cloth';
            // 마이크로태스크로 onload 호출(실제 디코드 비동기 모사).
            queueMicrotask(() => this.onload?.());
        }
    }
    vi.stubGlobal('Image', FakeImage as unknown as typeof Image);
});

describe('flagTint.tintFlag — 색 변환 계약', () => {
    it('색마다 FLAG_FRAMES(4) 프레임 dataURL 배열을 만든다', async () => {
        // 캐시 미적중 보장 위해 테스트마다 고유 색 사용(전역 cache 는 모듈 수명 동안 유지).
        const frames = await tintFlag('#010203');
        expect(frames).toHaveLength(FLAG_FRAMES);
        expect(frames.every((f) => typeof f === 'string' && f.startsWith('data:image/png'))).toBe(true);
    });

    it('회색 천 픽셀을 nation 색(파랑)의 hue 로 교체한다 — 무채색→유채색', async () => {
        await tintFlag('#0000fe');
        // 마지막 프레임의 putImageData 픽셀: 첫 픽셀(불투명 회색)이 파랑 계열로 바뀌어야.
        expect(lastPutData).not.toBeNull();
        const [r, g, b, a] = lastPutData!;
        // 파랑 앵커 → B 가 R/G 보다 우세(유채색).
        expect(b).toBeGreaterThan(r);
        expect(b).toBeGreaterThan(g);
        expect(a).toBe(255);
    });

    it('빨강 nation 색은 R 우세로 틴트된다(색상 분리 확인)', async () => {
        await tintFlag('#ff0000');
        const [r, g, b] = lastPutData!;
        expect(r).toBeGreaterThan(g);
        expect(r).toBeGreaterThan(b);
    });

    it('알파 0(투명) 픽셀은 RGB 를 변경하지 않는다', async () => {
        await tintFlag('#00ff00');
        // 두 번째 픽셀(투명)의 원본 RGB(10,20,30) 보존.
        const a2 = lastPutData![7];
        expect(a2).toBe(0);
        expect([lastPutData![4], lastPutData![5], lastPutData![6]]).toEqual([10, 20, 30]);
    });

    it('깃대(pole)를 천(cloth) 위에 합성한다 — 프레임당 drawImage cloth→pole 순서', async () => {
        drawCalls = [];
        await tintFlag('#123456');
        // 프레임 4개 각각 cloth 1회 + pole 1회 = 8회, cloth 가 항상 pole 앞.
        expect(drawCalls).toHaveLength(FLAG_FRAMES * 2);
        for (let i = 0; i < FLAG_FRAMES; i++) {
            expect(drawCalls[i * 2]).toBe('cloth');
            expect(drawCalls[i * 2 + 1]).toBe('pole');
        }
    });

    it('3자리 hex(#0f0)도 6자리로 확장해 처리한다', async () => {
        const frames = await tintFlag('#0f0');
        expect(frames).toHaveLength(FLAG_FRAMES);
        const [r, g, b] = lastPutData!;
        // 초록 우세.
        expect(g).toBeGreaterThan(r);
        expect(g).toBeGreaterThan(b);
    });

    it('같은 색은 캐시되어 동일 배열을 반환한다(색당 1회 계산)', async () => {
        const a = await tintFlag('#abcdef');
        const b = await tintFlag('#abcdef');
        // 캐시 적중 → 같은 참조.
        expect(a).toBe(b);
    });
});
