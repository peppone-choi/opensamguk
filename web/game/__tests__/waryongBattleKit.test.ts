// 와룡전 전장 조각 키트 export: 원천 커밋의 바이트 그대로인지, 게이트웨이 사본이 같은지, 그리고
// battleBoard.ts가 214판을 참고 렌더러(Python 조립 = probe/brender 화소)와 같은 색인으로 조립하는지 본다.
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { gunzipSync } from 'node:zlib';
import { describe, expect, it } from 'vitest';
import { composeBoard, parseBattleKit, type KitJson } from '../../shared/src/battle/battleBoard';

const KIT_ID = '3ef1ecd';
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
});
