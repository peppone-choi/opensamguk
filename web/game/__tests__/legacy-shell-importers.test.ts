// 옛 <Shell> 통과 틀(components/Shell.tsx)을 새로 들여오지 못하게 막는 가드(K3, 2026-10-02).
//
// 머리줄 · 메뉴 · 하단 탭은 /game 레이아웃의 GameFrame 하나가 그린다(v3.1 셸 통합). 옛 <Shell> 은 이름만 남은 통과 틀이다.
// 걷는 순서(K9 와 맞춤, 메타 원장 §7-1):
//  1. 지금 — 아래 목록 밖에서 새로 들여오면 빨갛다. 목록에서 빠지는 쪽(페이지 · 시험 삭제)은 이 파일을 고치지 않아도 통과한다.
//  2. 옛 페이지를 지우거나(K9 · K8) 같은 경로에 다시 쓰는(K5 admin · board) PR 이 import 와 시험의 vi.mock 을 같이 걷는다.
//  3. 들여오는 곳이 0 이 되면 K3 가 components/Shell.tsx 와 이 가드를 지운다.
import { readdirSync, readFileSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const ROOT = resolve(__dirname, '..');
const SCAN_DIRS = ['app', 'components', 'hooks', 'lib', '__tests__'];
const SKIP = new Set(['node_modules', '.next']);
const SELF = 'components/Shell.tsx';
const GUARD = '__tests__/legacy-shell-importers.test.ts'; // 아래 표본 글자 때문에 자기 자신은 뺀다

/** import · vi.mock 이 components/Shell 을 가리키는가(@/ 별칭 · 상대 경로 · 같은 폴더). */
const SHELL_REF = /['"](?:@\/components\/|(?:\.\.?\/)+components\/|\.\/)Shell['"]/;

/** 2026-10-02 origin/main 실측 — 페이지 15 + 그 페이지를 시험하는 vi.mock 12. 늘리지 않는다. */
const ALLOWED = new Set([
    'app/game/admin/page.tsx',
    'app/game/battle-replay/[id]/page.tsx',
    'app/game/board/page.tsx',
    'app/game/city/page.tsx',
    'app/game/generals/page.tsx',
    'app/game/history/page.tsx',
    'app/game/map/page.tsx',
    'app/game/my-cities/page.tsx',
    'app/game/my-generals/page.tsx',
    'app/game/my-nation/page.tsx',
    'app/game/my/page.tsx',
    'app/game/rankings/best-generals/page.tsx',
    'app/game/rankings/generals/page.tsx',
    'app/game/rankings/kingdoms/page.tsx',
    'app/game/rankings/page.tsx',
    '__tests__/access-score-routes.test.tsx',
    '__tests__/admin-hub-route.test.tsx',
    '__tests__/board-auction-deep-links.test.tsx',
    '__tests__/board-council.test.tsx',
    '__tests__/board-rich-text-lifecycle.test.tsx',
    '__tests__/board-rich-text.test.tsx',
    '__tests__/city-page.test.tsx',
    '__tests__/finance-routes.test.tsx',
    '__tests__/game-map-page.test.tsx',
    '__tests__/history-page.test.tsx',
    '__tests__/my-page-route.test.tsx',
    '__tests__/rankings-lobby-route.test.tsx',
]);

function sourceFiles(dir: string, out: string[] = []): string[] {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
        if (SKIP.has(entry.name)) continue;
        const path = join(dir, entry.name);
        if (entry.isDirectory()) sourceFiles(path, out);
        else if (/\.tsx?$/.test(entry.name)) out.push(relative(ROOT, path).split('\\').join('/'));
    }
    return out;
}

function shellImporters(): { readonly scanned: readonly string[]; readonly found: readonly string[] } {
    const scanned = SCAN_DIRS.flatMap((d) => sourceFiles(join(ROOT, d)));
    const found = scanned.filter((f) => f !== SELF && f !== GUARD && SHELL_REF.test(readFileSync(join(ROOT, f), 'utf8')));
    return { scanned, found };
}

describe('옛 <Shell> 통과 틀 — 새로 들여오기 금지', () => {
    it('찾는 식이 별칭 · 상대 경로 · vi.mock 을 모두 잡고, 비슷한 이름은 잡지 않는다', () => {
        expect(SHELL_REF.test("import Shell from '@/components/Shell';")).toBe(true);
        expect(SHELL_REF.test("import Shell from '../../../components/Shell';")).toBe(true);
        expect(SHELL_REF.test("vi.mock('@/components/Shell', () => ({}))")).toBe(true);
        expect(SHELL_REF.test("import Shell from './Shell';")).toBe(true);
        expect(SHELL_REF.test("import GameShell from '@/components/GameShell';")).toBe(false);
        expect(SHELL_REF.test("import { ShellIcon } from '@/components/shell/ShellIcon';")).toBe(false);
    });

    it('목록 밖 파일은 components/Shell 을 들여오지 않는다(셸은 GameFrame 이 그린다)', () => {
        const { scanned, found } = shellImporters();
        // 훑기가 살아 있는지: 이 파일 자신과 앱 페이지를 지나가야 한다.
        expect(scanned).toContain(GUARD);
        expect(scanned.some((f) => f.startsWith('app/game/'))).toBe(true);
        expect(found.filter((f) => !ALLOWED.has(f))).toEqual([]);
    });
});
