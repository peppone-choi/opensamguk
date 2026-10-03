import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { LEGACY_ROUTES, legacyTarget, type LegacyRoute } from '../lib/legacyRoutes';

// 옛 경로 308 표의 규칙(ADR-LITE-066 허용 목록 방식). 줄을 켤 때(ready) 지켜야 할 것을 여기서 막는다.
const GAME_ROOT = join(__dirname, '..');
const middlewareSource = readFileSync(join(GAME_ROOT, 'middleware.ts'), 'utf8');

function setFromSource(name: string): Set<string> {
  const match = new RegExp(`const ${name} = new Set\\(\\[(.*?)\\]\\);`, 's').exec(middlewareSource);
  if (!match) throw new Error(`${name} missing in middleware.ts`);
  return new Set([...match[1].matchAll(/'([^']+)'/g)].map((m) => m[1]));
}

const RESERVED = setFromSource('RESERVED_PATH_SERVER_IDS');
const RETIRED = setFromSource('RETIRED_GAME_PATHS');

/** app/game 아래에서 route group `(…)` 를 걷어 낸 경로로 page.tsx 가 있는지. `[id]` 조각은 동적 조각 아무거나. */
function pageExists(route: string): boolean {
  const want = route.split('/').filter(Boolean);
  const walk = (dir: string, rest: string[]): boolean => {
    if (!existsSync(dir)) return false;
    const entries = readdirSync(dir).filter((name) => statSync(join(dir, name)).isDirectory());
    if (entries.some((name) => /^\(.+\)$/.test(name) && walk(join(dir, name), rest))) return true;
    if (rest.length === 0) return existsSync(join(dir, 'page.tsx'));
    const [head, ...tail] = rest;
    const next = head === '[id]' ? entries.find((name) => /^\[.+\]$/.test(name)) : entries.find((name) => name === head);
    return next !== undefined && walk(join(dir, next), tail);
  };
  return walk(join(GAME_ROOT, 'app', 'game'), want);
}

const ALL_READY: readonly LegacyRoute[] = LEGACY_ROUTES.map((route) => ({ ...route, ready: true }));
const q = (query = '') => new URLSearchParams(query);

describe('legacy route table', () => {
  it('names each old path once, in lowercase slug segments', () => {
    const froms = LEGACY_ROUTES.map((route) => route.from);
    expect(new Set(froms).size).toBe(froms.length);
    for (const from of froms) expect(from).toMatch(/^[a-z0-9-]+(\/[a-z0-9-]+)*$/);
  });

  it('never points at another old path (no redirect chains) and never shadows a retired 404 path', () => {
    const froms = new Set(LEGACY_ROUTES.map((route) => route.from));
    for (const route of LEGACY_ROUTES) {
      expect(froms.has(route.to), `${route.from} → ${route.to}`).toBe(false);
      expect(RETIRED.has(route.from.split('/')[0]), `${route.from} is already a retired 404 path`).toBe(false);
    }
  });

  it('finds real pages through route groups (the guard below is not vacuous)', () => {
    expect(pageExists('retinue')).toBe(true);
    expect(pageExists('battle-replay/[id]')).toBe(true);
    expect(pageExists('records/yearbook')).toBe(false);
  });

  it('turns a line on only when its new page exists and its first segment is a reserved route name', () => {
    for (const route of LEGACY_ROUTES.filter((r) => r.ready)) {
      const target = route.keepRest ? `${route.to}/[id]` : route.to;
      expect(pageExists(target), `new page for ${route.from} → /${route.to}`).toBe(true);
      if (route.idFromQuery) expect(pageExists(`${route.to}/[id]`), `${route.to}/[id]`).toBe(true);
      const head = (route.toWithoutId ?? route.to).split('/')[0];
      if (head) expect(RESERVED.has(head), `${head} must be in RESERVED_PATH_SERVER_IDS (all 8 lists)`).toBe(true);
    }
  });
});

describe('legacyTarget', () => {
  it('does nothing while a line is off', () => {
    expect(legacyTarget(['generals'], q())).toBeNull();
    expect(legacyTarget(['war-room'], q(), [{ from: 'war-room', to: '', ready: false }])).toBeNull();
  });

  it('sends the old war room to the new war room root', () => {
    expect(legacyTarget(['war-room'], q(), ALL_READY)).toEqual({ path: '' });
    expect(legacyTarget(['map'], q(), ALL_READY)).toEqual({ path: '', addQuery: 'view=ju' });
  });

  it('lets the longest old path win', () => {
    expect(legacyTarget(['rankings', 'kingdoms'], q(), ALL_READY)).toEqual({ path: 'records/yearbook' });
    expect(legacyTarget(['rankings'], q(), ALL_READY)).toEqual({ path: 'retinue/people' });
  });

  it('carries the rest only where the line says so', () => {
    expect(legacyTarget(['battle-replay', '12'], q(), ALL_READY)).toEqual({ path: 'records/replay/12' });
    expect(legacyTarget(['generals', '12'], q(), ALL_READY)).toBeNull();
  });

  it('moves a query id into the path and falls back when it is missing or unsafe', () => {
    expect(legacyTarget(['city'], q('id=3'), ALL_READY)).toEqual({ path: 'territory/county/3', dropQuery: 'id' });
    expect(legacyTarget(['city'], q(), ALL_READY)).toEqual({ path: 'territory' });
    expect(legacyTarget(['city'], q('id=../x'), ALL_READY)).toEqual({ path: 'territory' });
  });

  it('ignores paths that are not in the table', () => {
    expect(legacyTarget(['retinue'], q(), ALL_READY)).toBeNull();
    expect(legacyTarget([], q(), ALL_READY)).toBeNull();
  });
});

// 2026-10-01 셸 통합에서 켠 줄 — 옛 → 새 전부. 줄을 끄거나 목적지를 바꾸면 여기서 빨개진다(적색 확인).
describe('lines turned on by the shell integration', () => {
  const EXPECTED: ReadonlyArray<{ readonly from: string; readonly to: string; readonly query?: string }> = [
    { from: 'war-room', to: '' },
    { from: 'yuedan', to: 'retinue/yuedan' },
    { from: 'hand', to: 'stratagem' },
    { from: 'posts', to: 'territory' },
    // K4 — 옛 도시 상세는 현 상세(P-T02)로. id 가 없으면 영지(아래 「moves a query id」 시험이 id → 경로를 본다).
    { from: 'city', to: 'territory' },
    { from: 'supply', to: 'territory/supply' },
    { from: 'siege', to: 'corps/siege' },
    { from: 'orders', to: 'court', query: 'tab=orders' },
    // K5 기록 5분류(P-H01) — 옛 「전황」은 기록의 천하 정세로(설계서 §5.1 WL1).
    { from: 'world-log', to: 'records' },
    // K9 삼모 삭제 — 옛 감찰부는 전투 · 부재 대비(P-C04)로.
    { from: 'battle-center', to: 'corps/battle' },
    // K8 — 옛 세력 정보는 세력(P-K10)으로.
    { from: 'my-nation', to: 'court/realm' },
    // K6 — 옛 메일함은 서신(P-Q02)으로. 외교 서신은 외교 화면(P-K02)의 칸이다.
    { from: 'mailbox', to: 'mail' },
    // K6 — 옛 중원 정보는 외교(P-K02)로.
    { from: 'global-diplomacy', to: 'court/diplomacy' },
    // K2(K9 인계) — 옛 천하 지도(아이소)는 지우고 작전실 주 보기로(?view=ju, 새 지도만 듣는다).
    { from: 'map', to: '', query: 'view=ju' },
  ];

  it.each(EXPECTED)('/$from → /$to', ({ from, to, query }) => {
    expect(legacyTarget(from.split('/'), q())).toEqual(query ? { path: to, addQuery: query } : { path: to });
  });

  it('/city?id=<현> → /territory/county/<현> (켠 표 그대로, 위험한 id 는 영지로)', () => {
    expect(legacyTarget(['city'], q('id=3'))).toEqual({ path: 'territory/county/3', dropQuery: 'id' });
    expect(legacyTarget(['city'], q('id=../x'))).toEqual({ path: 'territory' });
  });

  it('turns on exactly these lines and no others yet', () => {
    expect(LEGACY_ROUTES.filter((route) => route.ready).map((route) => route.from).sort()).toEqual(EXPECTED.map(({ from }) => from).sort());
  });
});
