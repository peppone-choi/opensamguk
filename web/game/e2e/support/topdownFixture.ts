// 합성 bake · 키트(e2e/fixtures/topdown, 원작 그림 없음)를 bake 주소(/api/game/api/map/topdown/<id>/…)와
// 승인 키트 주소(/map/waryong/273d596/…)에 대 준다. 지도는 서버가 topdownBakeId 를 줄 때만 그리므로(옛 지도는 지웠다, M2-9)
// 지도를 조작하는 smoke 시험은 미리보기에 FIXTURE_BAKE_ID 를 싣고 이것을 건다.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { Page, Route } from '@playwright/test';

export const TOPDOWN_FIXTURE = join(__dirname, '..', 'fixtures', 'topdown');
export const FIXTURE_BAKE_ID = 'a'.repeat(64);
/** 합성 bake 의 城 1(발자국 1399,899 · 3칸) — 미리보기 · front-info 가 같은 id 를 쓴다. 구역은 2개(provinceCount). */
export const FIXTURE_CITY = { id: 1, name: '시험현', provinceCount: 2 } as const;

const contentType = (path: string) => (path.endsWith('.png') ? 'image/png' : path.endsWith('.json') ? 'application/json' : 'application/octet-stream');

async function fulfillFile(route: Route, dir: string, file: string) {
  try {
    await route.fulfill({ status: 200, body: readFileSync(join(TOPDOWN_FIXTURE, dir, file)), contentType: contentType(file) });
  } catch {
    await route.fulfill({ status: 404, body: '' });
  }
}

/** 키트 주소를 대 준다(따로 건 route — `**\/api/**` route 보다 먼저 걸어도 겹치지 않는다). */
export async function serveTopdownKit(page: Page): Promise<void> {
  await page.route((url) => url.pathname.startsWith('/map/waryong/273d596/'), (route) =>
    fulfillFile(route, 'kit', new URL(route.request().url()).pathname.replace('/map/waryong/273d596/', '')));
}

/** `**\/api/**` route 안에서 부른다 — bake 주소면 대 주고 true. */
export async function fulfillTopdownBake(route: Route, url: URL): Promise<boolean> {
  const at = url.pathname.indexOf(`/api/map/topdown/${FIXTURE_BAKE_ID}/`);
  if (at < 0) return false;
  await fulfillFile(route, 'bake', url.pathname.slice(at + `/api/map/topdown/${FIXTURE_BAKE_ID}/`.length));
  return true;
}
