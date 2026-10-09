// 부 · 영지 화면 스모크(K4)의 합성 자료 — 백엔드 없이 브라우저 요청을 page.route로 대 준다.
//
// 모양은 game-api DTO 그대로(web/game/lib/campaign-reads.ts · directory-reads.ts 타입과 같다).
// 예시 상황은 승인 보드와 같다: 하후돈(조조 소속) · 200년 3월 중순 · 내 부 = 허저 · 이전(NPC) + 무명 인물 1.
// 능력치 · 수치는 짓지 않는다 — 화면이 값을 그대로 옮기는지만 보므로 눈에 띄는 작은 정수를 쓴다.
import { test, type Page } from '@playwright/test';
import { hierarchyFixture } from '../../__tests__/fixtures/retinue-hierarchy';

export const GENERAL_ID = 7;

/** 셸 스모크(e2e/smoke/shell.spec.ts)와 같은 모양 — 셸이 읽는 칸을 채운다. */
export function frontInfo() {
  return {
    result: true,
    global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', catchUp: null, turnterm: 60,
      scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
    general: { hasGeneral: true, generalId: GENERAL_ID, name: '하후돈', nationId: 1, officerLevel: 1, permission: 0, showSecret: false,
      leadership: 0, strength: 0, intel: 0, injury: 0, gold: 0, rice: 0, crew: 0, cityId: 2, picture: null, imageServer: 0 },
    nation: { id: 1, name: '조조', color: '#4f7fbf', capitalCityId: 3 },
    city: { id: 2, name: '양적현' },
    recentRecord: {},
  };
}

const person = (retainerId: number, name: string, loyalty: number, over: Record<string, unknown> = {}) => ({
  retainerId, generalId: 100 + retainerId, name, picture: null, imageServer: 0, loyalty,
  roleLabel: '없음', taskLabel: '없음', stats: null, cost: null, aptitudes: null,
  bonds: [], departureOrder: null, locationCityId: 2, ...over,
});

export function retinue(kind: 'full' | 'empty' = 'full') {
  if (kind === 'empty') return { status: 'READY', renown: 30, costSum: 0, overCapacity: false, people: [], units: [] };
  return {
    status: 'READY', renown: 30, costSum: 36, overCapacity: true,
    people: [
      person(1, '허저', 90, { cost: 12, bonds: [{ kind: 'HYANGDANG', label: '향당', nativeCountyName: '패국 초현', sameAsLord: true }] }),
      person(2, '이전', 70, { cost: 11 }),
      person(3, '무명 공조', 40, { cost: 13, departureOrder: 1, generalId: null }),
    ],
    units: [
      { id: 10, name: '하후돈 부곡 1', troops: 300, crewTypeId: 1, crewTypeName: '보병', training: 50, morale: 60, fatigue: 5, provisions: 0, provisionMonths: 2, commanderRetainerId: 1 },
      { id: 11, name: '하후돈 부곡 2', troops: 200, crewTypeId: 2, crewTypeName: '궁병', training: 40, morale: 55, fatigue: 0, provisions: 0, provisionMonths: 1, commanderRetainerId: null },
    ],
  };
}

export function posts(kind: 'full' | 'empty' = 'full') {
  if (kind === 'empty') return { status: 'READY', cards: [], posts: [] };
  return {
    status: 'READY',
    cards: [
      { cardId: 1, generalId: 101, name: '허저', relation: 'LIEUTENANT', provinceId: null, placeable: true, blocked: null,
        active: { post: 'CORPS_COMMANDER', postLabel: '군단장', target: { label: '영천 군단' }, state: 'ARRIVED' }, pending: null },
      { cardId: 2, generalId: 102, name: '이전', relation: 'LIEUTENANT', provinceId: null, placeable: true, blocked: null,
        active: null, pending: { post: 'MAGISTRATE', postLabel: '현령', target: { label: '장사현' } } },
      { cardId: 3, generalId: null, name: '무명 공조', relation: 'LIEUTENANT', provinceId: null, placeable: true, blocked: null, active: null, pending: null },
    ],
    posts: [
      { post: 'MAGISTRATE', label: '현령', available: true, blocked: null, targets: [{ countyId: 129, name: '양성현', commanderyName: '영천군', occupied: false }] },
      { post: 'NONE', label: '해제', available: true, blocked: null, targets: null },
    ],
  };
}

export function yuedan() {
  return { status: 'READY', stamp: '0200-03', self: { generalId: GENERAL_ID, renown: 30, retinueCost: 36, overCapacity: true }, ranking: [] };
}

export type ApiTable = Record<string, unknown | ((url: URL) => unknown)>;

/**
 * 로그인(`/api/auth/me`)과 game-api 프록시(`/api/game/api/**`)를 가로챈다. 표에 없는 조회는 404 로 돌려주고 `unknown` 에 모은다 —
 * 화면이 모르는 API 를 부르면 스모크가 알 수 있게.
 */
export async function serveCampaign(page: Page, table: ApiTable): Promise<{ unknown: string[] }> {
  const unknown: string[] = [];
  // 셸이 서버를 알아야 턴 루프를 읽는다 — 쿠키로 서버를 주고, 턴 루프 조회는 404(「운영 상태 확인 중」, 셸 스모크와 같다).
  const baseURL = test.info().project.use.baseURL ?? 'http://localhost:3001';
  await page.context().addCookies([{ name: 'sam_server', value: 'pep', url: baseURL }]);
  await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
  await page.route('**/api/auth/me', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ user: { id: 1, username: 'k4', email: null, nickname: '하후돈', role: 'USER' } }) }));
  await page.route((url) => url.pathname.startsWith('/api/game/api/'), async (route) => {
    const url = new URL(route.request().url());
    const key = url.pathname.replace(/^\/api\/game/, '');
    if (!(key in table)) {
      unknown.push(`${route.request().method()} ${key}`);
      await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
      return;
    }
    const value = table[key];
    const body = typeof value === 'function' ? (value as (u: URL) => unknown)(url) : value;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  });
  return { unknown };
}

/** 부 화면 기본 표 — 인물 있음(full) · 인물 없는 월드(empty, 990002). */
export function retinueTable(kind: 'full' | 'empty' = 'full'): ApiTable {
  return {
    '/api/front-info': frontInfo(),
    '/api/retinue': retinue(kind),
    '/api/retinue/hierarchy': hierarchyFixture(kind === 'empty'),
    '/api/posts': posts(kind),
    '/api/yuedan': yuedan(),
  };
}
