// 메뉴 한 벌 v3.1(docs/design/ui-v3/v31system.py NAV31 · MTABS31, ADR-LITE-049 2026-09-30 「v3.1 전체 승인」).
//
// 묶음 8개(작전실 + 6묶음 + 광장)와 그 아래 화면. 화면마다 새 경로(`path`)와, 새 화면이 아직 없을 때 대신 여는
// 지금 화면(`current`)을 둔다. 새 화면이 들어오면 `built` 를 켜고 `current` 를 지운다(옛 주소 308 은 lib/legacyRoutes.ts).
// 둘 다 없으면 「준비 중」으로 그린다 — 숨기지 않는다(표시 원칙).

import { LEGACY_ROUTES } from './legacyRoutes';

export type NavGroupKey = 'war' | 'retinue' | 'stratagem' | 'territory' | 'corps' | 'court' | 'records' | 'plaza';

export interface NavScreen {
  /** 하위 탭 · 메뉴에 보이는 이름(보드 그대로). */
  readonly label: string;
  /** 새 경로 조각(`/game/<서버>/` 뒤). '' = 작전실. 쿼리를 달 수 있다(`court?tab=orders`). */
  readonly path: string;
  /** 새 경로에 페이지가 있는지. */
  readonly built: boolean;
  /** 새 화면이 없을 때 대신 여는 지금 화면(옛 경로 조각). */
  readonly current?: string;
}

export interface NavGroup {
  readonly key: NavGroupKey;
  readonly label: string;
  readonly screens: readonly NavScreen[];
}

export const NAV31: readonly NavGroup[] = [
  { key: 'war', label: '작전실', screens: [{ label: '작전실', path: '', built: true }] },
  {
    key: 'retinue', label: '부', screens: [
      { label: '편성 · 결속 · 명망', path: 'retinue', built: true },
      { label: '인물 일람', path: 'retinue/people', built: false, current: 'generals' },
      { label: '월단평', path: 'retinue/yuedan', built: true },
      { label: '포로 · 등용', path: 'retinue/captives', built: false },
    ],
  },
  {
    key: 'stratagem', label: '계책', screens: [
      { label: '계책 덱', path: 'stratagem', built: true },
      { label: '역정보', path: 'stratagem/counter-intel', built: false },
    ],
  },
  {
    key: 'territory', label: '영지', screens: [
      { label: '배치 · 방침 · 공사', path: 'territory', built: true },
      { label: '현 상세', path: 'territory/county', built: false, current: 'city' },
      { label: '군 내정 현황', path: 'territory/commandery', built: false },
      { label: '창고망 · 보급', path: 'territory/supply', built: true },
    ],
  },
  {
    key: 'corps', label: '군단', screens: [
      { label: '군단 · 세력 작전', path: 'corps', built: false },
      { label: '공성', path: 'corps/siege', built: true },
      { label: '전투', path: 'corps/battle', built: false, current: 'battle-center' },
      { label: '시야 · 첩보', path: 'corps/intel', built: false },
    ],
  },
  {
    key: 'court', label: '조정', screens: [
      { label: '발령 · 포상 · 조정 결정', path: 'court?tab=orders', built: true },
      { label: '관직 · 봉신', path: 'court/offices', built: false },
      { label: '외교', path: 'court/diplomacy', built: false, current: 'global-diplomacy' },
      { label: '참모 제안', path: 'court/proposals', built: false },
      { label: '황실', path: 'court/imperial', built: false },
      { label: '세력', path: 'court/realm', built: false, current: 'my-nation' },
    ],
  },
  {
    key: 'records', label: '기록', screens: [
      { label: '기록 5분류', path: 'records', built: false, current: 'world-log' },
      { label: '연감', path: 'records/yearbook', built: false, current: 'history' },
      { label: '천하 형세', path: 'records/unification', built: false },
      { label: '시즌 결산', path: 'records/season', built: false },
    ],
  },
  {
    key: 'plaza', label: '광장', screens: [
      { label: '회의실 · 기밀실', path: 'council', built: false, current: 'board' },
      { label: '서신', path: 'mail', built: false, current: 'mailbox' },
    ],
  },
];

/** 모바일 하단 탭(MTABS31). 「전체」는 모든 묶음을 여는 시트다. */
export const MOBILE_TAB_KEYS: readonly NavGroupKey[] = ['war', 'retinue', 'stratagem', 'records'];

/** 화면을 열 주소 조각 — 새 화면이 있으면 새 경로, 없으면 지금 화면, 둘 다 없으면 null(「준비 중」). */
export function screenHref(screen: NavScreen): string | null {
  if (screen.built) return screen.path;
  return screen.current ?? null;
}

/** 레일 · 하단 탭이 여는 곳 — 묶음에서 열 수 있는 첫 화면. */
export function groupHref(group: NavGroup): string | null {
  for (const screen of group.screens) {
    const href = screenHref(screen);
    if (href !== null) return href;
  }
  return null;
}

function pathOnly(fragment: string): string {
  return fragment.split('?')[0];
}

function startsWithSegments(path: readonly string[], prefix: readonly string[]): boolean {
  return prefix.length <= path.length && prefix.every((segment, i) => path[i] === segment);
}

/**
 * 지금 경로 조각(`/game/<서버>/` 뒤, 쿼리 빼고)이 속한 묶음과 화면. 옛 경로는 308 표로 새 경로를 찾아 맞춘다.
 * 가장 길게 맞는 화면이 이긴다. 어디에도 없으면 null(join · admin 등).
 */
export function locateScreen(rest: string, query = ''): { readonly group: NavGroup; readonly screen: NavScreen | null } | null {
  const parts = rest.split('/').filter(Boolean);
  const params = new URLSearchParams(query);
  let best: { group: NavGroup; screen: NavScreen; length: number; queryHit: boolean } | null = null;
  const consider = (group: NavGroup, screen: NavScreen, candidate: string) => {
    const [candidatePath, candidateQuery = ''] = candidate.split('?');
    const segments = candidatePath.split('/').filter(Boolean);
    if (segments.length === 0 && parts.length > 0) return;
    if (!startsWithSegments(parts, segments)) return;
    const queryHit = candidateQuery !== '' && [...new URLSearchParams(candidateQuery)].every(([k, v]) => params.get(k) === v);
    if (candidateQuery !== '' && !queryHit) return;
    if (!best || segments.length > best.length || (segments.length === best.length && queryHit && !best.queryHit)) {
      best = { group, screen, length: segments.length, queryHit };
    }
  };
  for (const group of NAV31) {
    for (const screen of group.screens) {
      consider(group, screen, screen.path);
      if (screen.current) consider(group, screen, screen.current);
    }
  }
  // 308 표의 옛 경로(my-cities → territory 등)도 새 경로 쪽 묶음에 맞춘다.
  if (!best && parts.length > 0) {
    const legacy = LEGACY_ROUTES.find((route) => startsWithSegments(parts, route.from.split('/')));
    if (legacy) return locateScreen(pathOnly(legacy.to), legacy.query ?? '');
  }
  const found = best as { group: NavGroup; screen: NavScreen } | null;
  if (found) return { group: found.group, screen: found.screen };
  // 하위 탭에 없는 같은 묶음 화면(탭 없는 /court 등) — 묶음만 켠다.
  const head = parts[0];
  if (head) {
    const group = NAV31.find((g) => g.screens.some((screen) => pathOnly(screen.path).split('/')[0] === head));
    if (group) return { group, screen: null };
  }
  return null;
}
