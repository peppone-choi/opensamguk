import { describe, expect, it } from 'vitest';
import { CONTROL_BUTTONS } from '@/lib/control-bar-config';
import { GLOBAL_MENU_V2 } from '@/lib/global-menu-fixture';
import {
    DEPT_GROUPS,
    GATE_REASON,
    GATING_UNKNOWN_REASON,
    MOBILE_TABS,
    buildDeptGroups,
    evaluateEntry,
    evaluateMobileTab,
    flattenMenuItems,
    gateAllows,
    groupHighlight,
    type ControlGating,
} from '@/lib/dept-menu-config';

const NONE: ControlGating = { showSecret: false, permission: 0, myLevel: 0, nationLevel: 0, isTournamentApplicationOpen: false, isBettingActive: false };
const CHIEF: ControlGating = { showSecret: true, permission: 4, myLevel: 5, nationLevel: 3, isTournamentApplicationOpen: false, isBettingActive: true };

describe('dept-menu-config (S1 매핑)', () => {
    const entries = DEPT_GROUPS.flatMap((g) => g.entries);

    it('places every one of the 20 control buttons exactly once, labels verbatim', () => {
        const ids = entries.filter((e) => e.kind === 'control').map((e) => e.button.id).sort((a, b) => a - b);
        expect(ids).toEqual(CONTROL_BUTTONS.map((b) => b.id).sort((a, b) => a - b));
        for (const e of entries) {
            if (e.kind === 'control') expect(CONTROL_BUTTONS.find((b) => b.id === e.button.id)?.label).toBe(e.button.label);
        }
    });

    it('absorbs every GlobalMenu leaf exactly once (multi/split flattened), names verbatim', () => {
        const leaves = flattenMenuItems(GLOBAL_MENU_V2).map((e) => e.item.name).sort();
        const placed = entries.filter((e) => e.kind === 'menu').map((e) => e.item.name).sort();
        expect(placed).toEqual(leaves);
        expect(leaves).toHaveLength(14);
    });

    it('keeps six groups in S1 order with 작전실 first', () => {
        expect(DEPT_GROUPS.map((g) => g.label)).toEqual(['작전실', '국가 운영', '군사', '정보', '광장', '기록']);
        expect(DEPT_GROUPS[0].entries[0]).toMatchObject({ kind: 'route', href: '/game' });
    });

    it('gates exactly like MainControlBar and attaches a reason to every non-always bucket', () => {
        expect(gateAllows('always', NONE)).toBe(true);
        expect(gateAllows('myLevel', NONE)).toBe(false);
        expect(gateAllows('myLevel', { ...NONE, myLevel: 1 })).toBe(true);
        expect(gateAllows('myLevelAndNation', { ...NONE, myLevel: 1 })).toBe(false);
        expect(gateAllows('myLevelAndNation', { ...NONE, myLevel: 1, nationLevel: 1 })).toBe(true);
        expect(gateAllows('permission2', { ...NONE, permission: 1 })).toBe(false);
        expect(gateAllows('permission2', { ...NONE, permission: 2 })).toBe(true);
        expect(gateAllows('showSecret', { ...NONE, showSecret: true })).toBe(true);
        for (const bucket of ['myLevel', 'myLevelAndNation', 'permission2', 'showSecret'] as const) {
            expect(GATE_REASON[bucket]).toBeTruthy();
        }
        expect(GATE_REASON.always).toBeNull();
    });

    it('evaluates a blocked control as visible + disabled + reason (never hidden)', () => {
        const secret = entries.find((e) => e.kind === 'control' && e.button.id === 2)!;
        const view = evaluateEntry(secret, NONE, {});
        expect(view.label).toBe('기 밀 실');
        expect(view.enabled).toBe(false);
        expect(view.hidden).toBe(false);
        expect(view.reason).toBe('수뇌부 권한 필요');
        expect(evaluateEntry(secret, CHIEF, {}).enabled).toBe(true);
    });

    it('hides only server-conditioned menu items (condShowVar) and highlights flagged ones', () => {
        const npcList = entries.find((e) => e.kind === 'menu' && e.item.name === '빙의일람')!;
        expect(evaluateEntry(npcList, NONE, {}).hidden).toBe(true);
        expect(evaluateEntry(npcList, NONE, { npcMode: 1 }).hidden).toBe(false);
        const betting = entries.find((e) => e.kind === 'control' && e.button.id === 20)!;
        expect(evaluateEntry(betting, CHIEF, {}).highlight).toBe(true);
        expect(groupHighlight(DEPT_GROUPS.find((g) => g.key === 'plaza')!, CHIEF, {})).toBe(true);
        expect(groupHighlight(DEPT_GROUPS.find((g) => g.key === 'records')!, CHIEF, {})).toBe(false);
    });

    it('defines the five mobile tabs of S1 with real targets and gates 국가 like 세력 정보', () => {
        expect(MOBILE_TABS.map((t) => t.label)).toEqual(['작전실', '지도', '명령', '국가', '더보기']);
        expect(MOBILE_TABS.map((t) => t.href)).toEqual(['/game', '/game/map', '/game#reservedCommandPanel', '/game/my-nation', '#dept-more']);
        const nationTab = MOBILE_TABS.find((t) => t.key === 'nation')!;
        expect(evaluateMobileTab(nationTab, NONE, {}, 'ready')).toMatchObject({ enabled: false, reason: '장수 직위 이상 필요', label: '국가' });
        expect(evaluateMobileTab(nationTab, CHIEF, {}, 'ready')).toMatchObject({ enabled: true, reason: null });
    });

    it('never fabricates a permission reason while gating is unknown', () => {
        const secret = entries.find((e) => e.kind === 'control' && e.button.id === 2)!;
        expect(evaluateEntry(secret, null, {}, 'loading')).toMatchObject({ enabled: true, reason: null });
        expect(evaluateEntry(secret, null, {}, 'error')).toMatchObject({ enabled: false, reason: GATING_UNKNOWN_REASON });
    });

    it('prefers the server global menu (url/newTab) over the fixture, falling back by label', () => {
        const serverMenu = [
            { type: 'item' as const, name: '연감', url: '/game/history', newTab: false },
            { type: 'multi' as const, name: '게임정보', subMenu: [{ type: 'item' as const, name: '세력일람', url: '/game/rankings/kingdoms', newTab: true }] },
        ];
        const groups = buildDeptGroups(serverMenu);
        const records = groups.find((g) => g.key === 'records')!;
        const yearbook = records.entries.find((e) => e.kind === 'menu' && e.item.name === '연감')!;
        expect(yearbook.kind === 'menu' && yearbook.item.url).toBe('/game/history');
        const kingdoms = records.entries.find((e) => e.kind === 'menu' && e.item.name === '세력일람')!;
        expect(kingdoms.kind === 'menu' && kingdoms.item.newTab).toBe(true);
        // 서버 메뉴에 없는 잎(명장일람 등)은 픽스처로 폴백해 라벨이 사라지지 않는다.
        expect(records.entries.some((e) => e.kind === 'menu' && e.item.name === '명장일람')).toBe(true);
    });
});
