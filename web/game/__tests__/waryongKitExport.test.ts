// 와룡전 지도 키트 export: opensamguk-images 병합 커밋의 exact-byte 사본인지, 게이트웨이 사본이 같은지 본다.
// 게이트웨이 사본이 빠지면 web/game public 절대경로가 프로덕션에서만 404가 난다.
import { createHash } from 'node:crypto';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const ROOT = resolve(__dirname, '../../..');
const KIT_ID = '273d596';
const EXPORT = join(ROOT, 'data/map/waryong', KIT_ID, 'export.json');

interface ExportManifest {
    kitId: string;
    source: { repository: string; mergeCommit: string };
    mirrors: string[];
    files: Record<string, { bytes: number; sha256: string; source: string }>;
}

const manifest = JSON.parse(readFileSync(EXPORT, 'utf8')) as ExportManifest;
const sha256 = (path: string) => createHash('sha256').update(readFileSync(path)).digest('hex');

describe('와룡전 지도 키트 export', () => {
    it('기록한 해시와 바이트가 같다', () => {
        expect(manifest.kitId).toBe(KIT_ID);
        expect(manifest.source.mergeCommit.startsWith(KIT_ID)).toBe(true);
        for (const [path, meta] of Object.entries(manifest.files)) {
            const absolute = join(ROOT, path);
            expect(readFileSync(absolute).length, path).toBe(meta.bytes);
            expect(sha256(absolute), path).toBe(meta.sha256);
        }
    });

    it('게이트웨이 사본이 게임 사본과 파일 목록 · 바이트가 같다', () => {
        const [game, gateway] = manifest.mirrors.map((dir) => join(ROOT, dir));
        const names = readdirSync(game).sort();
        expect(readdirSync(gateway).sort()).toEqual(names);
        for (const name of names) expect(sha256(join(gateway, name)), name).toBe(sha256(join(game, name)));
        const listed = Object.keys(manifest.files).filter((p) => p.startsWith(manifest.mirrors[0])).map((p) => p.split('/').pop());
        expect([...listed, 'NOTICE.md'].sort()).toEqual(names);
    });

    it('내보낸 곳마다 고지가 있다', () => {
        for (const dir of [...manifest.mirrors, `data/map/waryong/${KIT_ID}`]) {
            expect(existsSync(join(ROOT, dir, 'NOTICE.md')), dir).toBe(true);
        }
    });
});
