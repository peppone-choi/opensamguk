// 와룡전 전장 조각 키트 export: 원천 커밋의 바이트 그대로인지, 게이트웨이 사본이 같은지, 그리고
// battleBoard.ts가 214판을 참고 렌더러(Python 조립 = probe/brender 화소)와 같은 색인으로 조립하는지 본다.
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { gunzipSync } from 'node:zlib';
import { describe, expect, it } from 'vitest';
import { boardClassification, composeBoard, parseBattleKit, type KitJson } from '../../shared/src/battle/battleBoard';
import { paletteRamp, parseUnitKit, UNITS_PER_SIDE, unitSpriteRgba } from '../../shared/src/battle/battleUnits';

const KIT_ID = '2c8a1a5';
const GAME = resolve(__dirname, '../public/battle/waryong', KIT_ID);
const GATEWAY = resolve(__dirname, '../../gateway/public/battle/waryong', KIT_ID);
const sha256 = (data: Uint8Array) => createHash('sha256').update(data).digest('hex');
const arrayBuffer = (b: Buffer) => b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength) as ArrayBuffer;

interface ExportManifest { kitId: string; source: { mergeCommit: string }; files: Record<string, { bytes: number; sha256: string }> }

describe('와룡전 전장 조각 키트', () => {
    const manifest = JSON.parse(readFileSync(join(GAME, 'export.json'), 'utf8')) as ExportManifest;

    it('원천 커밋의 바이트 그대로이고 게이트웨이 사본이 같다', () => {
        expect(manifest.kitId).toBe(KIT_ID);
        expect(manifest.source.mergeCommit.startsWith(KIT_ID)).toBe(true);
        for (const [name, meta] of Object.entries(manifest.files)) {
            const bytes = readFileSync(join(GAME, name));
            expect(bytes.length, name).toBe(meta.bytes);
            expect(sha256(bytes), name).toBe(meta.sha256);
        }
        const names = readdirSync(GAME).sort();
        expect(readdirSync(GATEWAY).sort()).toEqual(names);
        for (const name of names) expect(sha256(readFileSync(join(GATEWAY, name))), name).toBe(sha256(readFileSync(join(GAME, name))));
        expect(names).toEqual([...Object.keys(manifest.files), 'NOTICE.md', 'export.json'].sort());
    });

    it('214판을 참고 조립과 같은 색인으로 조립한다', () => {
        const json = JSON.parse(readFileSync(join(GAME, 'kit.json'), 'utf8')) as KitJson;
        const kit = parseBattleKit(json, {
            pieces: arrayBuffer(gunzipSync(readFileSync(join(GAME, 'pieces.bin.gz')))),
            records: arrayBuffer(readFileSync(join(GAME, 'records.bin'))),
            boards: arrayBuffer(gunzipSync(readFileSync(join(GAME, 'boards.bin.gz')))),
        });
        const mismatched = kit.boardInfo.filter((info) => sha256(composeBoard(kit, info.id).indices) !== info.composedSha256).map((info) => info.id);
        expect(mismatched).toEqual([]);
    }, 60_000);

    it('판 분류가 서버 카탈로그(terrainRows)와 214판 모두 같고 terrainSha256과 맞는다', () => {
        const json = JSON.parse(readFileSync(join(GAME, 'kit.json'), 'utf8')) as KitJson;
        const kit = parseBattleKit(json, {
            pieces: arrayBuffer(gunzipSync(readFileSync(join(GAME, 'pieces.bin.gz')))),
            records: arrayBuffer(readFileSync(join(GAME, 'records.bin'))),
            boards: arrayBuffer(gunzipSync(readFileSync(join(GAME, 'boards.bin.gz')))),
        });
        const catalog = JSON.parse(readFileSync(resolve(__dirname, '../../../data/battle/waryong/catalog-v1.json'), 'utf8')) as {
            boards: { id: number; terrainRows: string[] }[];
        };
        const different = catalog.boards.filter((board) => boardClassification(kit, board.id) !== board.terrainRows.join('')).map((b) => b.id);
        expect(different).toEqual([]);
        const hashMismatch = kit.boardInfo.filter((info) => sha256(Buffer.from(boardClassification(kit, info.id), 'ascii')) !== info.terrainSha256).map((i) => i.id);
        expect(hashMismatch).toEqual([]);
    });

    it('유닛: 빨강 틀에 원작 파랑 네 색(3 · 8 · 1 · 12)을 칠하면 원작 파랑 편과 99% 이상 같다', () => {
        const json = JSON.parse(readFileSync(join(GAME, 'kit.json'), 'utf8')) as KitJson;
        const units = parseUnitKit(json.palette.rgb, {
            units: arrayBuffer(gunzipSync(readFileSync(join(GAME, 'units.bin.gz')))),
            roles: arrayBuffer(gunzipSync(readFileSync(join(GAME, 'unit-roles.bin.gz')))),
        });
        const blue = paletteRamp(units.palette, 3, 8, 1, 12);
        let opaque = 0;
        let different = 0;
        for (let u = 0; u < UNITS_PER_SIDE; u += 1) {
            if (u >= 76 && u < 84) continue; // 한 조각짜리(작은 깃발 · 화살 · 잔해)는 두 편 그림이 다르다
            const painted = unitSpriteRgba(units, u, blue);
            const original = unitSpriteRgba(units, u, null, 'blue');
            for (let i = 0; i < painted.length; i += 4) {
                if (!painted[i + 3] && !original[i + 3]) continue;
                opaque += 1;
                if (painted[i] !== original[i] || painted[i + 1] !== original[i + 1] || painted[i + 2] !== original[i + 2] || painted[i + 3] !== original[i + 3]) different += 1;
            }
        }
        expect(opaque).toBeGreaterThan(10_000);
        expect(different / opaque).toBeLessThan(0.01);
    });
});
