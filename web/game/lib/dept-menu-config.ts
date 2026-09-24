import type { MenuFlagSource, MenuNode } from './menu-types';


export type DeptKey = 'ops' | 'nation' | 'military' | 'info' | 'plaza' | 'records';

export interface DeptRouteEntry {
  readonly kind: 'route';
  readonly label: string;
  readonly href: string;
}
export type DeptEntry = DeptRouteEntry;

export interface DeptGroup {
  readonly key: DeptKey;
  readonly label: string;
  readonly entries: readonly DeptEntry[];
}

export interface ControlGating {
  myLevel: number;
}

const NATION_REASON = '장수 직위 이상 필요';

const route = (label: string, href: string): DeptRouteEntry => ({ kind: 'route', label, href });
export const OPS_ROUTE = route('작전실', '/game/hwiha/war-room');
export const MAP_ROUTE = route('천하 지도', '/game/map');

/** The product menu is local and fixed. The old server GlobalMenu describes SAMMO actions. */
export function buildDeptGroups(_menuSource?: readonly MenuNode[]): readonly DeptGroup[] {
  return [
    { key: 'ops', label: '작전실', entries: [OPS_ROUTE] },
    {
      key: 'nation', label: '국가 운영', entries: [
        route('배치 · 방침 · 공사', '/game/hwiha/posts'),
        route('조정 결정', '/game/hwiha/orders'),
        route('관직 · 외교 · 천도', '/game/hwiha/court'),
        route('보급망 · 창고', '/game/hwiha/supply'),
        route('세력 정보', '/game/my-nation'),
        route('세력 도시', '/game/my-cities'),
        route('세력 장수', '/game/my-generals'),
      ],
    },
    {
      key: 'military', label: '군사', entries: [
        route('휘하 편성', '/game/hwiha/retinue'),
        route('공성', '/game/hwiha/siege'),
        route('계책 덱', '/game/hwiha/hand'),
      ],
    },
    {
      key: 'info', label: '정보', entries: [
        MAP_ROUTE,
        route('현재 도시', '/game/city'),
        route('장수 일람', '/game/generals'),
        route('중원 정보', '/game/global-diplomacy'),
        route('전투 기록', '/game/battle-center'),
      ],
    },
    {
      key: 'plaza', label: '광장', entries: [
        route('게시판', '/game/board'),
        route('서신', '/game/mailbox'),
        route('내 정보', '/game/my'),
      ],
    },
    {
      key: 'records', label: '기록', entries: [
        route('월단평', '/game/hwiha/yuedan'),
        route('연감', '/game/history'),
        route('월드 기록', '/game/world-log'),
        route('랭킹', '/game/rankings'),
      ],
    },
  ];
}

export const DEPT_GROUPS = buildDeptGroups();

export const MOBILE_TABS = [
  { key: 'ops', label: '작전실', href: '/game/hwiha/war-room', controlId: null },
  { key: 'map', label: '지도', href: '/game/map', controlId: null },
  { key: 'commands', label: '명령', href: '/game/hwiha/war-room#reservedCommandPanel', controlId: null },
  { key: 'nation', label: '국가', href: '/game/my-nation', controlId: 11 },
  { key: 'more', label: '더보기', href: '#dept-more', controlId: null },
] as const;

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
  readonly hidden: boolean;
}

export function evaluateEntry(
  entry: DeptEntry,
  _gating: ControlGating | null,
  _global: MenuFlagSource,
  _state: GatingState = 'ready',
): DeptEntryView {
  return {
    entry,
    label: entry.label,
    href: entry.href,
    enabled: true,
    reason: null,
    highlight: false,
    newTab: false,
    hidden: false,
  };
}

export function groupHighlight(_group: DeptGroup, _gating: ControlGating | null, _global: MenuFlagSource): boolean {
  return false;
}

export function evaluateMobileTab(
  tab: (typeof MOBILE_TABS)[number],
  gating: ControlGating | null,
  global: MenuFlagSource,
  state: GatingState,
): DeptEntryView {
  const view = evaluateEntry(route(tab.label, tab.href), gating, global, state);
  if (tab.controlId !== 11) return view;
  const enabled = gating ? gating.myLevel >= 1 : state === 'loading';
  return {
    ...view,
    enabled,
    reason: enabled ? null : gating ? NATION_REASON : GATING_UNKNOWN_REASON,
  };
}
