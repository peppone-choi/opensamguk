// 판 그림 v2 컴포넌트(BattleBoardCanvas) — 플래그를 켠 실제 포인터 경로.
// - 성벽의 보이는 윗면을 누르면 그 성벽 칸(정본 키트 2c8a1a5 판 0, 임시 절차 타일 팩).
// - 외부 팩을 못 받거나 조립하지 못하면 원작 키트 그림 + 알림 줄(data-artpack-status="fallback"), 「다시 시도」로 회복.
// - 플래그를 끄면 팩 상태 표시가 없다.
import { readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { gunzipSync } from 'node:zlib';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { parseBattleKit, proceduralArtpack, type BattleKit, type KitJson, type LoadedArtpack } from '@opensamguk/ui/battle';

const mocks = vi.hoisted(() => ({ loadArtpack: vi.fn<(source: string) => Promise<LoadedArtpack>>() }));

const GAME = resolve(__dirname, '../public/battle/waryong/2c8a1a5');
let realKit: BattleKit | null = null;
function kit(): BattleKit {
    const buffer = (b: Buffer) => b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength) as ArrayBuffer;
    realKit ??= parseBattleKit(JSON.parse(readFileSync(join(GAME, 'kit.json'), 'utf8')) as KitJson, {
        pieces: buffer(gunzipSync(readFileSync(join(GAME, 'pieces.bin.gz')))),
        records: buffer(readFileSync(join(GAME, 'records.bin'))),
        boards: buffer(gunzipSync(readFileSync(join(GAME, 'boards.bin.gz')))),
    });
    return realKit;
}

vi.mock('@opensamguk/ui/battle', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@opensamguk/ui/battle')>()),
    loadBattleKit: () => Promise.resolve(kit()),
    loadArtpack: (source: string) => mocks.loadArtpack(source),
}));

/** Frame-sized canvases keep RGBA (drawImage 1:1 from an RGBA source, getImageData); board-sized ones only take calls. */
class SoftCanvas {
    readonly rgba: Uint8ClampedArray | null;
    constructor(
        readonly width: number,
        readonly height: number,
    ) {
        this.rgba = width * height <= 1 << 16 ? new Uint8ClampedArray(width * height * 4) : null;
    }
    getContext() {
        const own = this.rgba;
        const width = this.width;
        return {
            setTransform() {},
            putImageData() {},
            getImageData: (x: number, y: number, w: number, h: number) => {
                const data = new Uint8ClampedArray(w * h * 4);
                for (let row = 0; own && row < h; row += 1) data.set(own.subarray(((y + row) * width + x) * 4, ((y + row) * width + x + w) * 4), row * w * 4);
                return { data, width: w, height: h };
            },
            drawImage: (source: { rgba?: Uint8ClampedArray | null; width: number }, sx: number, sy: number, sw: number, sh: number, dx = 0, dy = 0) => {
                if (!own || !source.rgba || sw !== width || dx !== 0 || dy !== 0) return;
                for (let row = 0; row < sh; row += 1) {
                    own.set(source.rgba.subarray(((sy + row) * source.width + sx) * 4, ((sy + row) * source.width + sx + sw) * 4), row * width * 4);
                }
            },
        };
    }
}

/** The procedural pack with atlases carrying their RGBA (as loadArtpack('procedural') builds, without a real canvas). */
function proceduralPack(): LoadedArtpack {
    const { manifest, atlases } = proceduralArtpack();
    return { manifest, atlases: atlases.map((a) => Object.assign(Object.create(null), { width: a.width, height: a.height, rgba: a.rgba })) };
}

/** A main-canvas 2D context that accepts every call (drawing is not inspected here). */
function quietContext(): CanvasRenderingContext2D {
    const target: CanvasRenderingContext2D = Object.create(null);
    return new Proxy(target, {
        get: (_target, key) => (key === 'imageSmoothingEnabled' ? false : () => undefined),
        set: () => true,
    });
}

async function renderBoard(flag: string, onPickCell = vi.fn()) {
    vi.stubEnv('NEXT_PUBLIC_BATTLE_ARTPACK', flag);
    vi.resetModules();
    const { BattleBoardCanvas } = await import('../components/battle/BattleBoardCanvas');
    render(
        <BattleBoardCanvas boardId={0} terrainInputSha256={null} units={[]} allowedCells={[]} selectedIds={new Set()}
            scale={{ kind: 'renderer', value: 2.5 }} onPickUnit={vi.fn()} onPickCell={onPickCell} label="전투 판" />,
    );
    const canvas = screen.getByTestId('battle-board');
    const box = canvas.parentElement!;
    await waitFor(() => expect(box.dataset.battleStatus).toBe('ready'));
    return { canvas, box, onPickCell };
}

beforeEach(() => {
    mocks.loadArtpack.mockReset();
    vi.stubGlobal('OffscreenCanvas', SoftCanvas);
    vi.stubGlobal('ImageData', class {
        constructor(readonly data: Uint8ClampedArray, readonly width: number, readonly height: number) {}
    });
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(() => quietContext());
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
});

afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
});

describe('성벽 윗면 누르기(실제 포인터 경로)', () => {
    it('보이는 성벽 픽셀은 그 성벽 칸 — 뒤 평지 칸이 아니다', async () => {
        mocks.loadArtpack.mockResolvedValue(proceduralPack());
        const { canvas, box, onPickCell } = await renderBoard('1');
        await waitFor(() => expect(box.dataset.artpackStatus).toBe('ready'));
        expect(mocks.loadArtpack).toHaveBeenCalledWith('procedural');
        await waitFor(() => expect(canvas.dataset.view).toBeDefined());
        const scale = Number(canvas.dataset.scale);
        const [offsetX, offsetY] = canvas.dataset.view!.split(',').map(Number);
        // 리뷰 재현(판 px): 성벽 (1,38)·(1,37)·(2,38) 윗면의 불투명 픽셀 — 바닥 마름모만 보면 (0,39)·(0,38)·(1,39) 평지였다.
        const tops = [
            { x: 640, y: 332, cell: { row: 1, col: 38 } },
            { x: 624, y: 340, cell: { row: 1, col: 37 } },
            { x: 656, y: 340, cell: { row: 2, col: 38 } },
        ];
        for (const top of tops) {
            onPickCell.mockClear();
            fireEvent.pointerUp(canvas, { pointerId: 1, clientX: (top.x + 0.5) * scale + offsetX, clientY: (top.y + 0.5) * scale + offsetY });
            expect(onPickCell).toHaveBeenCalledWith(top.cell);
        }
    }, 60_000);
});

describe('외부 팩 실패는 드러나고 다시 받을 수 있다', () => {
    it('못 받으면 원작 키트 그림 + 알림 줄, 「다시 시도」로 팩 그림', async () => {
        mocks.loadArtpack.mockRejectedValueOnce(new Error('/battle/artpack/v1/manifest.json: HTTP 404')).mockResolvedValueOnce(proceduralPack());
        const { canvas, box } = await renderBoard('/battle/artpack/v1');
        await waitFor(() => expect(box.dataset.artpackStatus).toBe('fallback'));
        expect(screen.getByRole('status')).toHaveTextContent('새 전투 판 그림을 불러오지 못해 원작 그림으로 표시합니다.');
        expect(canvas.dataset.artpack).toBeUndefined();
        fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
        await waitFor(() => expect(box.dataset.artpackStatus).toBe('ready'));
        expect(screen.queryByRole('status')).toBeNull();
        await waitFor(() => expect(canvas.dataset.artpack).toBe(proceduralArtpack().manifest.packId));
        expect(mocks.loadArtpack.mock.calls).toEqual([['/battle/artpack/v1'], ['/battle/artpack/v1']]);
    }, 60_000);

    it('받았어도 조립하지 못하면 같은 fallback', async () => {
        const broken = proceduralPack();
        delete (broken.manifest.terrain as Partial<typeof broken.manifest.terrain>).W;
        mocks.loadArtpack.mockResolvedValue(broken);
        const { box } = await renderBoard('/battle/artpack/v1');
        await waitFor(() => expect(box.dataset.artpackStatus).toBe('fallback'));
        expect(screen.getByRole('status')).toBeInTheDocument();
    }, 60_000);

    it('플래그를 끄면 팩을 받지 않고 상태 표시도 없다', async () => {
        const { canvas, box } = await renderBoard('');
        expect(box.dataset.artpackStatus).toBeUndefined();
        expect(screen.queryByRole('status')).toBeNull();
        expect(canvas.dataset.artpack).toBeUndefined();
        expect(mocks.loadArtpack).not.toHaveBeenCalled();
    }, 60_000);
});
