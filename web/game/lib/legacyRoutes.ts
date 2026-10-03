// 옛 경로 → 새 경로 308 표(ADR-LITE-066 허용 목록 방식: 옛 경로 이름은 리다이렉트 전용인 이 파일에서만 쓴다).
//
// 새 경로의 정본은 메뉴 한 벌 v3.1(docs/design/ui-v3/v31system.py NAV31)과 프론트 원장 §3 이다.
// 줄마다 `ready` 가 꺼져 있으면 아무 일도 하지 않는다 — 지금 화면이 그대로 뜬다.
// 새 화면이 main 에 들어간 줄만 켠다(K9 삭제 순서: 화면 대체 → 308 켜기 → 옛 화면 · API 삭제).
// 켠 줄은 __tests__/legacyRoutes.test.ts 가 새 화면 파일 · 예약된 경로 이름 · 고리 없음을 확인한다.

export interface LegacyRoute {
  /** 옛 경로 조각. `/game/<서버>/` 뒤를 '/' 로 이은 것. */
  readonly from: string;
  /** 새 경로 조각. '' = 작전실(`/game/<서버>`). */
  readonly to: string;
  /** 새 화면이 들어가 308 을 켰는지. */
  readonly ready: boolean;
  /** 옛 경로 뒤에 남은 조각(ID 등)을 새 경로 뒤에 그대로 붙인다. 없으면 남은 조각이 있을 때 넘기지 않는다. */
  readonly keepRest?: boolean;
  /** 옛 화면이 쿼리로 받던 ID 를 새 경로 조각으로 옮긴다(`/city?id=3` → `/territory/county/3`). */
  readonly idFromQuery?: string;
  /** idFromQuery 가 비었을 때 갈 곳(`/city` → `/territory`). */
  readonly toWithoutId?: string;
  /** 새 경로에 붙일 쿼리(`/orders` → `/court?tab=orders` — 조정 화면의 탭). 같은 이름이 이미 있으면 덮는다. */
  readonly query?: string;
}

export const LEGACY_ROUTES: readonly LegacyRoute[] = [
  // 작전실
  // 2026-10-01 셸 통합: 캠페인 화면을 새 경로로 옮겼다(이름만 바꾸는 이동) — 그 줄들을 켠다.
  { from: 'war-room', to: '', ready: true },
  { from: 'map', to: '', ready: false },
  // 부
  { from: 'generals', to: 'retinue/people', ready: false },
  { from: 'my-generals', to: 'retinue/people', ready: false },
  { from: 'rankings', to: 'retinue/people', ready: false },
  { from: 'rankings/generals', to: 'retinue/people', ready: false },
  { from: 'rankings/best-generals', to: 'retinue/people', ready: false },
  { from: 'yuedan', to: 'retinue/yuedan', ready: true },
  // 계책
  { from: 'hand', to: 'stratagem', ready: true },
  // 영지
  { from: 'posts', to: 'territory', ready: true },
  { from: 'my-cities', to: 'territory', ready: false },
  // 옛 도시 상세 — 현 상세(P-T02, #1222)가 들어와 켠다. id 가 없으면(옛 「현재 도시」) 영지로. 옛 화면은 지웠다(K4 10-03).
  { from: 'city', to: 'territory/county', ready: true, idFromQuery: 'id', toWithoutId: 'territory' },
  { from: 'supply', to: 'territory/supply', ready: true },
  // 군단
  { from: 'siege', to: 'corps/siege', ready: true },
  // 옛 감찰부 — 전투 · 부재 대비(P-C04, #1141)가 들어와 켠다. 장수 기록은 기록 5분류(P-H01)로 간다(K9).
  { from: 'battle-center', to: 'corps/battle', ready: true },
  // 조정
  // 조정 결정(발령 · 포상)은 조정 화면의 첫 탭이다(v3.1 보드 COURT_TABS 「발령 · 포상 · 조정 결정」).
  { from: 'orders', to: 'court', ready: true, query: 'tab=orders' },
  { from: 'global-diplomacy', to: 'court/diplomacy', ready: false },
  // 옛 세력 정보 — 세력(P-K10, #1173)이 들어와 켠다. 옛 화면과 작전 진행 칸(4X-B)은 지웠다(K0 10-01 22:4x).
  { from: 'my-nation', to: 'court/realm', ready: true },
  // 기록
  { from: 'world-log', to: 'records', ready: true },
  { from: 'history', to: 'records/yearbook', ready: false },
  { from: 'rankings/kingdoms', to: 'records/yearbook', ready: false },
  { from: 'battle-replay', to: 'records/replay', ready: false, keepRest: true },
  // 광장
  { from: 'board', to: 'council', ready: false },
  { from: 'mailbox', to: 'mail', ready: true },
];

/**
 * `/game/<서버>/` 뒤 조각과 쿼리를 받아 새 경로 조각을 돌려준다. 넘길 곳이 없으면 null.
 * 가장 긴 `from` 이 이긴다(`rankings/kingdoms` 가 `rankings` 보다 먼저).
 */
export function legacyTarget(
  rest: readonly string[],
  query: URLSearchParams,
  table: readonly LegacyRoute[] = LEGACY_ROUTES,
): { readonly path: string; readonly dropQuery?: string; readonly addQuery?: string } | null {
  const parts = rest.filter(Boolean);
  let best: LegacyRoute | null = null;
  let bestLength = 0;
  for (const route of table) {
    if (!route.ready) continue;
    const from = route.from.split('/');
    if (from.length > parts.length || from.length <= bestLength) continue;
    if (from.every((segment, index) => parts[index] === segment)) {
      best = route;
      bestLength = from.length;
    }
  }
  if (!best) return null;
  const tail = parts.slice(bestLength);
  if (tail.length > 0 && !best.keepRest) return null;
  if (best.idFromQuery) {
    const id = query.get(best.idFromQuery);
    if (id && /^[A-Za-z0-9_-]{1,64}$/.test(id)) {
      return { path: [best.to, id].join('/'), dropQuery: best.idFromQuery };
    }
    return { path: best.toWithoutId ?? best.to };
  }
  const path = [best.to, ...tail].filter(Boolean).join('/');
  return best.query ? { path, addQuery: best.query } : { path };
}
