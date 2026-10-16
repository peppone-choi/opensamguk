// 전술 판 그림 v2(AOE2풍 단기 전환 A2) — 기능 플래그 해석, 플래그를 켜도 칸 ↔ 화면 대응(data-cells)이 같은지,
// 팩에 병종 그림이 없으면 지금과 똑같이 마름모로 그리는지, 있으면 발 기준점에 맞춰 그리는지.
import { readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { gunzipSync } from 'node:zlib';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
    boardTerrain,
    composeBoard,
    groundBoard,
    parseBattleKit,
    planBoard,
    proceduralArtpack,
    type ArtpackManifest,
    type BoardPicture,
    type KitJson,
    type LoadedArtpack,
} from '@opensamguk/ui/battle';
import { drawBattleBoard, type BoardDrawInput, type BoardScale, type BoardUnitMark } from '../components/battle/board-draw';
import { battleArtpackSource } from '../components/battle/BattleBoardCanvas';

const SIDE = 8;
const TOP = 2 * 16 + 8;
const W = 2 * SIDE * 16 + 32;
const H = 2 * SIDE * 8 + TOP + 16;

function kit() {
    const pieces = new Uint8Array(4 * 512).fill(255);
    pieces.fill(1, 512, 1024);
    const records = new Uint8Array(4 * 8);
    records.set([1, 1, 0, 0, 0, 0, 0, 0], 8);
    records.set([2, 1, 1, 0, 0, 0, 0, 0], 16);
    const boards = new Uint8Array(SIDE * SIDE).fill(1);
    boards[3 * SIDE + 4] = 2;
    const json: KitJson = {
        schemaVersion: 1,
        artifactId: 'waryong-battle-kit',
        layout: { boards: 1, side: SIDE, layers: 2, tilesets: 1, piecesPerTileset: 4, piece: [32, 16], step: 16, top: TOP, canvas: [W, H], empty: 255 },
        palette: { rgb: Array.from({ length: 16 }, (_, i) => [i, i, i] as [number, number, number]) },
        boards: [{ id: 0, tileset: 0, layoutSha256: '', composedSha256: '' }],
        recordClass: ['PPWR'],
    };
    return parseBattleKit(json, { pieces: pieces.buffer, records: records.buffer, boards: boards.buffer });
}

type Call = [string, ...unknown[]];

/** Records every method call and property write, in order. */
function recordingContext(calls: Call[] = []): CanvasRenderingContext2D {
    const values = new Map<PropertyKey, unknown>([['imageSmoothingEnabled', true]]);
    const target: CanvasRenderingContext2D = Object.create(null);
    return new Proxy(target, {
        get(_target, key) {
            if (values.has(key)) return values.get(key);
            if (key === 'getImageData') return (_x: number, _y: number, w: number, h: number) => ({ data: new Uint8ClampedArray(w * h * 4).fill(128), width: w, height: h });
            return (...args: unknown[]) => {
                calls.push([String(key), ...args]);
            };
        },
        set(_target, key, value) {
            calls.push([`=${String(key)}`, value]);
            values.set(key, value);
            return true;
        },
    });
}

const k = kit();
const canvas = {} as OffscreenCanvas;
const v1: BoardPicture = { board: composeBoard(k, 0), canvas };
const v2: BoardPicture = { board: groundBoard(k, 0, boardTerrain(k, 0).grid), canvas };
const units: BoardUnitMark[] = [
    { id: 'a', cell: { row: 2, col: 2 }, index: 1, group: 1 },
    { id: 'b', cell: { row: 5, col: 3 }, index: 2, group: 1, ai: true },
];
const allowedCells = [{ row: 1, col: 1 }, { row: 1, col: 2 }, { row: 6, col: 6 }];

function input(picture: BoardPicture, scale: BoardScale, art?: LoadedArtpack, list = units): BoardDrawInput {
    return {
        assets: { kit: k, picture, art },
        width: 390,
        height: 640,
        units: list,
        allowedCells,
        selectedIds: new Set(['a']),
        scale,
        isLive: false,
        center: null,
        clusterBelow: 0,
        band: null,
        anchor: null,
    };
}

const procedural: LoadedArtpack = { manifest: proceduralArtpack().manifest, atlases: [canvas] };

/** 보병 칸 하나(sw 그림 48 × 48, 발 (20, 44)) — 배율 2. */
function packWithInfantry(): LoadedArtpack {
    const manifest: ArtpackManifest = structuredClone(proceduralArtpack().manifest);
    manifest.units.infantry = {
        actions: { idle: { frames: 1, directions: { sw: { frames: [{ atlas: 0, x: 0, y: 0, w: 48, h: 48, ax: 20, ay: 44 }] }, nw: { frames: [{ atlas: 0, x: 48, y: 0, w: 48, h: 48, ax: 24, ay: 44 }] } } } },
    };
    return { manifest, atlases: [canvas] };
}

afterEach(() => {
    vi.unstubAllGlobals();
});

describe('판 그림 v2 기능 플래그', () => {
    it('비우면 끔(원작 키트), 1 은 임시 절차 타일, 그 밖은 팩 URL', () => {
        expect(battleArtpackSource(undefined)).toBeNull();
        expect(battleArtpackSource('')).toBeNull();
        expect(battleArtpackSource(' 0 ')).toBeNull();
        expect(battleArtpackSource('1')).toBe('procedural');
        expect(battleArtpackSource('procedural')).toBe('procedural');
        expect(battleArtpackSource('/battle/artpack/v1/')).toBe('/battle/artpack/v1/');
    });
});

describe('플래그를 켜도 칸 대응은 같다', () => {
    const scales: BoardScale[] = [{ kind: 'fit' }, { kind: 'renderer', value: 2 }, { kind: 'board', value: 1.5 }];
    it.each(scales)('data-cells · view · 표지 좌표 (%o)', (scale) => {
        const off = drawBattleBoard(recordingContext(), input(v1, scale));
        const on = drawBattleBoard(recordingContext(), input(v2, scale, procedural));
        expect(on.cells).toEqual(off.cells);
        expect(on.view).toEqual(off.view);
        expect(on.point).toEqual(off.point);
        expect(on.marks).toEqual(off.marks);
        expect(Object.keys(on.cells)).toHaveLength(units.length + allowedCells.length);
    });

    it('판 그림은 판 px 크기로 그린다(그림 해상도와 무관)', () => {
        const calls: Call[] = [];
        const r = drawBattleBoard(recordingContext(calls), input(v2, { kind: 'renderer', value: 2 }, procedural));
        const draw = calls.find((c) => c[0] === 'drawImage')!;
        expect(draw.slice(2)).toEqual([r.view.offsetX, r.view.offsetY, W * 2, H * 2]);
    });
});

describe('정본 키트 214판(2c8a1a5)을 임시 절차 타일로 조립', () => {
    const GAME = resolve(__dirname, '../public/battle/waryong/2c8a1a5');
    const buffer = (b: Buffer) => b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength) as ArrayBuffer;

    it('모든 판의 칸이 P/F/M/R/W 이고, 바닥은 칸마다 하나, 서 있는 그림은 숲 · 산 · 성벽 칸마다 하나', () => {
        const real = parseBattleKit(JSON.parse(readFileSync(join(GAME, 'kit.json'), 'utf8')) as KitJson, {
            pieces: buffer(gunzipSync(readFileSync(join(GAME, 'pieces.bin.gz')))),
            records: buffer(readFileSync(join(GAME, 'records.bin'))),
            boards: buffer(gunzipSync(readFileSync(join(GAME, 'boards.bin.gz')))),
        });
        const { manifest } = proceduralArtpack();
        const cells = real.layout.side * real.layout.side;
        const timings: number[] = [];
        for (const info of real.boardInfo) {
            const started = performance.now();
            const terrain = boardTerrain(real, info.id);
            const items = planBoard(real, info.id, terrain, manifest);
            timings.push(performance.now() - started);
            expect(items.filter((i) => i.kind === 'ground')).toHaveLength(cells);
            expect(items.filter((i) => i.kind === 'tall')).toHaveLength(terrain.letters.filter((l) => l === 'F' || l === 'M' || l === 'W').length);
        }
        timings.sort((a, b) => a - b);
        // 조립 계획(노드, 그리기 제외) — 판 하나 p95 기록용. 브라우저 그리기 시간은 이 시험이 재지 않는다.
        console.info(`artpack plan per board: p50 ${timings[107].toFixed(2)} ms, p95 ${timings[203].toFixed(2)} ms, max ${timings[213].toFixed(2)} ms`);
        expect(real.boardInfo).toHaveLength(214);
    }, 60_000);
});

describe('부곡 그림', () => {
    it('팩에 병종 그림이 없으면 지금과 똑같이 마름모(그리는 호출이 같다)', () => {
        const off: Call[] = [];
        const on: Call[] = [];
        drawBattleBoard(recordingContext(off), input(v1, { kind: 'renderer', value: 2 }));
        drawBattleBoard(recordingContext(on), input(v1, { kind: 'renderer', value: 2 }, procedural, units.map((u) => ({ ...u, slot: 'infantry' }))));
        expect(on).toEqual(off);
    });

    it('병종 그림이 있으면 칠하지 않고 테두리 + 발 기준점에 그림', () => {
        vi.stubGlobal(
            'OffscreenCanvas',
            class {
                constructor(readonly width: number, readonly height: number) {}
                getContext() {
                    return recordingContext();
                }
            },
        );
        const calls: Call[] = [];
        const list: BoardUnitMark[] = [{ ...units[0], slot: 'infantry' }, units[1]];
        const r = drawBattleBoard(recordingContext(calls), input(v2, { kind: 'renderer', value: 2 }, packWithInfantry(), list));
        const draws = calls.filter((c) => c[0] === 'drawImage');
        expect(draws).toHaveLength(2); // 판 + 보병 하나
        const foot = r.cells['2:2'];
        const k2 = r.view.scale / 2;
        expect(draws[1].slice(2)).toEqual([foot.x - 20 * k2, foot.y - 44 * k2, 48 * k2, 48 * k2]);
        expect(calls.filter((c) => c[0] === 'fill')).toHaveLength(allowedCells.length + 1); // 배치 구역 + 그림 없는 부곡 b
    });
});
