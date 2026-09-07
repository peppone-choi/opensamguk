// 부서 메뉴 모델 — ADR-LITE-049 · S1 「20개 기능 → 부서 메뉴 매핑」.
// MainControlBar 20버튼(control-bar-config)과 GlobalMenu 8항목(GlobalMenuController = global-menu-fixture)을
// 라벨·href·게이팅 그대로 6그룹(작전실 / 국가 운영 / 군사 / 정보 / 광장 / 기록)에 접어 넣는다.
// 새 라벨을 만들지 않는다. 비활성은 숨기지 않고 점선 + 사유로 남긴다(OPENSAM-113 표시 원칙).
import { CONTROL_BUTTONS, type ControlButton, type GateBucket } from './control-bar-config';
import { GLOBAL_MENU_V2 } from './global-menu-fixture';
import type { MenuFlagSource, MenuItem, MenuNode } from './menu-types';

export type DeptKey = 'ops' | 'nation' | 'military' | 'info' | 'plaza' | 'records';

export interface DeptRouteEntry {
    readonly kind: 'route';
    readonly label: string;
    readonly href: string;
}
export interface DeptControlEntry {
    readonly kind: 'control';
    readonly button: ControlButton;
}
export interface DeptMenuEntry {
    readonly kind: 'menu';
    readonly item: MenuItem;
    /** 원래 있던 상위 메뉴 이름(게임정보 · 기타 정보 · 공식 오픈 톡). 라벨 자체는 item.name 그대로. */
    readonly parent?: string;
}
export type DeptEntry = DeptRouteEntry | DeptControlEntry | DeptMenuEntry;

export interface DeptGroup {
    readonly key: DeptKey;
    readonly label: string;
    readonly entries: readonly DeptEntry[];
}

/** 게이팅 사유 — bucket 별 고정 문자열. always 는 사유 없음(null). */
export const GATE_REASON: Record<GateBucket, string | null> = {
    always: null,
    myLevel: '장수 직위 이상 필요',
    myLevelAndNation: '국가 소속 + 직위 필요',
    permission2: '수뇌부 권한 필요',
    showSecret: '기밀 열람 권한 필요',
};

export interface ControlGating {
    showSecret: boolean;
    permission: number;
    myLevel: number; // officer_level proxy
    nationLevel: number;
    isTournamentApplicationOpen: boolean;
    isBettingActive: boolean;
}

/** MainControlBar 와 같은 판정(단일 출처). */
export function gateAllows(bucket: GateBucket, g: ControlGating): boolean {
    switch (bucket) {
        case 'always':
            return true;
        case 'myLevel':
            return g.myLevel >= 1;
        case 'myLevelAndNation':
            return g.myLevel >= 1 && g.nationLevel >= 1;
        case 'permission2':
            return g.permission >= 2;
        case 'showSecret':
            return g.showSecret === true;
    }
}

function control(id: number): DeptControlEntry {
    const button = CONTROL_BUTTONS.find((b) => b.id === id);
    if (!button) throw new Error(`control button ${id} missing`);
    return { kind: 'control', button };
}

/** GlobalMenu 트리를 잎(item)으로 편다. split 의 main 도 잎이다. line 은 버린다. */
export function flattenMenuItems(menu: readonly MenuNode[], parent?: string): DeptMenuEntry[] {
    const out: DeptMenuEntry[] = [];
    for (const node of menu) {
        if (node.type === 'item') out.push({ kind: 'menu', item: node, parent });
        else if (node.type === 'multi') out.push(...flattenMenuItems(node.subMenu, node.name));
        else if (node.type === 'split') {
            out.push({ kind: 'menu', item: node.main, parent });
            out.push(...flattenMenuItems(node.subMenu, node.main.name));
        }
    }
    return out;
}

/**
 * 전역 메뉴 잎을 이름으로 찾는다. 서버(GetGlobalMenu)가 준 메뉴를 우선 쓰고, 이름이 없으면 v2 픽스처로
 * 폴백한다 — 라벨은 두 원천이 같고 url/newTab 은 서버가 정본이다(교차 비평 #4).
 */
function menuFinder(source: readonly MenuNode[]) {
    const leaves = flattenMenuItems(source);
    const fallback = flattenMenuItems(GLOBAL_MENU_V2);
    return (name: string): DeptMenuEntry => {
        const entry = leaves.find((e) => e.item.name === name) ?? fallback.find((e) => e.item.name === name);
        if (!entry) throw new Error(`global menu item ${name} missing`);
        return entry;
    };
}

/** 작전실 메인은 라우트 하나. 천하 지도는 기존 /game/map 라우트(20버튼·8메뉴 밖의 화면). */
export const OPS_ROUTE: DeptRouteEntry = { kind: 'route', label: '작전실', href: '/game' };
export const MAP_ROUTE: DeptRouteEntry = { kind: 'route', label: '천하 지도', href: '/game/map' };

/** 부서 그룹을 만든다. `menuSource` 는 서버 전역 메뉴(없으면 픽스처). */
export function buildDeptGroups(menuSource: readonly MenuNode[] = GLOBAL_MENU_V2): readonly DeptGroup[] {
    const menu = menuFinder(menuSource);
    return [
    { key: 'ops', label: '작전실', entries: [OPS_ROUTE] },
    {
        key: 'nation',
        label: '국가 운영',
        entries: [
            control(11), // 세력 정보
            control(5), // 인 사 부
            control(13), // 세력 장수
            control(1), // 회 의 실
            menu('게시판'),
            control(4), // 외 교 부
            control(6), // 내 무 부
            control(7), // 사 령 부
            control(8), // NPC 정책
            control(9), // 암 행 부
            control(16), // 감 찰 부
            control(2), // 기 밀 실
        ],
    },
    { key: 'military', label: '군사', entries: [control(3), control(12)] },
    { key: 'info', label: '정보', entries: [control(14), control(15), MAP_ROUTE, menu('전투 시뮬레이터')] },
    {
        key: 'plaza',
        label: '광장',
        entries: [
            control(10), // 토 너 먼 트
            control(19), // 경 매 장 (split: 금/쌀 · 유니크)
            control(20), // 베 팅 장
            menu('천통국 베팅'),
            control(17), // 유산 관리
            control(18), // 내 정보&설정
            menu('설문조사'),
            menu('공식 오픈 톡'),
            menu('잡담 오픈 톡'),
        ],
    },
    {
        key: 'records',
        label: '기록',
        entries: [
            menu('연감'),
            menu('세력일람'),
            menu('장수일람'),
            menu('명장일람'),
            menu('명예의전당'),
            menu('왕조일람'),
            menu('접속량정보'),
            menu('빙의일람'),
        ],
    },
    ];
}

/** 픽스처 기준 기본 그룹(서버 메뉴가 아직 없을 때). */
export const DEPT_GROUPS: readonly DeptGroup[] = buildDeptGroups();

/** 모바일 5탭(S1): 작전실 · 지도 · 명령 · 국가 · 더보기. 「명령」은 작전실의 명령 목록 앵커, 「더보기」는 부서 시트. */
export const MOBILE_TABS = [
    { key: 'ops', label: '작전실', href: '/game', controlId: null },
    { key: 'map', label: '지도', href: '/game/map', controlId: null },
    // 「명령」은 작전실 우측 명령 목록(PartialReservedCommand, id=reservedCommandPanel) 앵커.
    { key: 'commands', label: '명령', href: '/game#reservedCommandPanel', controlId: null },
    // 「국가」는 세력 정보(#11, myLevel 게이팅)와 같은 규칙을 탄다.
    { key: 'nation', label: '국가', href: '/game/my-nation', controlId: 11 },
    { key: 'more', label: '더보기', href: '#dept-more', controlId: null },
] as const;

/** 게이팅 상태: 아직 모름(loading) / 서버 정보 없음(error) / 계산됨(ready). 모르는 동안 권한 사유를 지어내지 않는다. */
export type GatingState = 'loading' | 'error' | 'ready';
export const GATING_UNKNOWN_REASON = '서버 정보 없음';

export interface DeptEntryView {
    readonly entry: DeptEntry;
    readonly label: string;
    readonly href: string;
    readonly enabled: boolean;
    readonly reason: string | null;
    readonly highlight: boolean;
    readonly newTab: boolean;
    /** condShowVar 로 아예 안 보이는 항목(빙의일람 npcMode=0). 숨김은 서버 규칙일 때만. */
    readonly hidden: boolean;
}

function menuVisible(item: MenuItem, global: MenuFlagSource): boolean {
    const cond = item.condShowVar;
    if (!cond) return true;
    const negate = cond.startsWith('!');
    const key = negate ? cond.slice(1) : cond;
    if (!(key in global)) return false;
    return negate ? !global[key] : Boolean(global[key]);
}

/**
 * 그룹 항목을 현재 주체 기준으로 평가한다. href 는 상대 경로 그대로 — 서버 경로 해석은 호출자가 한다.
 * gating 이 null 이면 state 로 구분한다: loading = 중립(비활성 아님, 사유 없음), error = 「서버 정보 없음」.
 */
export function evaluateEntry(entry: DeptEntry, gating: ControlGating | null, global: MenuFlagSource, state: GatingState = gating ? 'ready' : 'loading'): DeptEntryView {
    if (entry.kind === 'route') {
        return { entry, label: entry.label, href: entry.href, enabled: true, reason: null, highlight: false, newTab: false, hidden: false };
    }
    if (entry.kind === 'control') {
        const b = entry.button;
        const gated = b.bucket !== 'always';
        const enabled = !gated || (gating ? gateAllows(b.bucket, gating) : state === 'loading');
        const reason = enabled ? null : gating ? GATE_REASON[b.bucket] : GATING_UNKNOWN_REASON;
        return {
            entry,
            label: b.label,
            href: b.href,
            enabled,
            reason,
            highlight: Boolean(b.highlightVar && gating && gating[b.highlightVar]),
            newTab: Boolean(b.newTab),
            hidden: false,
        };
    }
    const item = entry.item;
    return {
        entry,
        label: item.name,
        href: item.url,
        enabled: true,
        reason: null,
        highlight: Boolean(item.condHighlightVar && global[item.condHighlightVar]),
        newTab: Boolean(item.newTab),
        hidden: !menuVisible(item, global),
    };
}

/** 그룹에 하이라이트 항목이 있으면 그룹 칩(광장 「베팅 진행」 등)을 켠다. */
export function groupHighlight(group: DeptGroup, gating: ControlGating | null, global: MenuFlagSource): boolean {
    return group.entries.some((e) => evaluateEntry(e, gating, global).highlight);
}

/** 모바일 탭 하나를 평가한다(국가 탭은 세력 정보 #11 게이팅). */
export function evaluateMobileTab(tab: (typeof MOBILE_TABS)[number], gating: ControlGating | null, global: MenuFlagSource, state: GatingState): DeptEntryView {
    if (tab.controlId == null) {
        return evaluateEntry({ kind: 'route', label: tab.label, href: tab.href }, gating, global, state);
    }
    const view = evaluateEntry(control(tab.controlId), gating, global, state);
    return { ...view, label: tab.label, href: tab.href };
}
