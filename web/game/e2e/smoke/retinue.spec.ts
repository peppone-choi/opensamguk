// 부 편성(P-R01) 스모크 — 합성 자료(e2e/support/campaignFixtures)로 백엔드 없이, 데스크톱 · 모바일 같은 흐름(@both).
// 화면 이름(부 이름) · 인물 목록 · 상세(데스크톱) / 세그먼트 · 인물 카드 시트(모바일) · 배치 시트 · 빈 부, 화면 규칙(44 · 덮임 · 넘침 · title 전용 · 여백).
import { expect, test, type Locator, type Page } from '@playwright/test';
import { retinueTable, serveCampaign } from '../support/campaignFixtures';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo, coveredTargets } from '../support/parity';

/** 이 화면이 부르는 조회 — 셸 자신의 조회는 셸 스모크 몫이라 여기서 세지 않는다. */
const MINE = /\/api\/(retinue|posts|yuedan|commands)/;

/** Left inset supplied by the shell body. */
async function insetFromMain(page: Page, target: Locator): Promise<number> {
  const main = await page.getByRole('main', { name: '게임 콘텐츠' }).boundingBox();
  const box = await target.boundingBox();
  return Math.round((box?.x ?? 0) - (main?.x ?? 0));
}

/** 덮임 — 공용 coveredTargets(support/parity, 한 화면씩 내려가며 · 붙박인 층은 스크롤해 다시)로 옮겼다(K10 10-02). */
const coveredIn = (root: Locator): Promise<string[]> => coveredTargets(root, 'a, button, select, input, [role="option"], [role="radio"]');

test('503 조회 실패 — 쉬운 안내와 오류 번호 표시 · 복사 · 재시도', { tag: [BOTH] }, async ({ page }, info) => {
  await serveCampaign(page, retinueTable('full'));
  let fail = true;
  await page.route((url) => url.pathname === '/api/game/api/retinue', (route) => {
    if (fail) return route.fulfill({ status: 503, json: {} });
    return route.fallback();
  });
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: {
      writeText: async (value: string) => { document.documentElement.dataset.copiedError = value; },
    } });
  });
  await page.goto('/game/retinue', { waitUntil: 'domcontentloaded' });
  const alert = page.getByRole('alert').filter({ hasText: '부를 불러오지 못했습니다' });
  await expect(alert).toContainText('서버가 잠시 응답하지 않습니다. 잠시 뒤 다시 해 보세요.');
  await expect(alert).not.toContainText('Service Unavailable');
  await press(alert.getByRole('button', { name: '오류 번호 503 복사', exact: true }), info);
  await expect(page.locator('html')).toHaveAttribute('data-copied-error', '503');
  fail = false;
  await press(alert.getByRole('button', { name: '다시 시도', exact: true }), info);
  await expect(page.getByRole('heading', { level: 2, name: '하후돈의 막부' })).toBeVisible();
  await expect(page.getByRole('button', { name: /오류 번호/ })).toHaveCount(0);
});

test('인물이 있는 부 — 부 이름 · 목록 · 상세 / 인물 카드 시트, 덮임 · 44 · 넘침 · title 전용 · 여백', { tag: [BOTH] }, async ({ page }, info) => {
  const served = await serveCampaign(page, retinueTable('full'));
  await page.goto('/game/retinue', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 2, name: '하후돈의 막부' })).toBeVisible({ timeout: 60_000 });
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  await expect(main).not.toContainText('휘하');
  const hierarchy = page.getByRole('region', { name: '부 조직도' });
  await expect(hierarchy.getByRole('list', { name: '내 부 계층' })).toBeVisible();
  await expect(hierarchy).toContainText('직속 장수');
  await expect(hierarchy).toContainText('전체 하위 장수');
  if (isMobile(info)) {
    const seg = page.getByRole('radiogroup', { name: '보기' });
    await expect(seg).toBeVisible();
    expect(await insetFromMain(page, seg)).toBeGreaterThanOrEqual(12);
    expect(await coveredIn(main)).toEqual([]);
    // 장수 아닌 인물(장수 id 없음)은 이 화면 안 카드로 연다.
    await press(page.getByRole('option', { name: /무명 공조/ }), info);
    const card = page.getByRole('dialog', { name: '무명 공조 인물 카드' });
    await expect(card.getByRole('button', { name: '닫기' })).toBeVisible();
    expect(await coveredIn(card)).toEqual([]);
    expect(await smallTouchTargets(page, '[role="dialog"]')).toEqual([]);
    expect(await titleOnlyInfo(page, '[role="dialog"]')).toEqual([]);
    await press(card.getByRole('button', { name: '닫기' }), info);
    await expect(card).toBeHidden();
    // 장수 카드는 인물 상세(P-R03) 전체 화면으로 간다(설계서 §3 P-R01 모바일).
    await press(page.getByRole('option', { name: /허저/ }), info);
    // 인물 상세(P-R03) 주소로 간다 — /game/<서버>/… 고리라 SERVER_ID 없는 스모크 서버에서는 404, 주소만 본다(화면은 person.spec).
    await page.waitForURL(/\/retinue\/people\/101$/);
    await page.goBack();
    await expect(page.getByRole('radiogroup', { name: '보기' })).toBeVisible({ timeout: 60_000 });
  } else {
    const detail = page.getByRole('region', { name: '고른 인물' });
    await expect(detail).toContainText('허저');
    // 좁은 상세 칸(1280)에서도 능력 칸 이름이 한 글자씩 꺾이지 않는다(한 줄 높이).
    expect(await detail.getByRole('group', { name: '능력' }).evaluate((g) =>
      Array.from(g.querySelectorAll<HTMLElement>(':scope > div > span:first-child')).filter((s) => s.offsetHeight > 18).map((s) => s.textContent))).toEqual([]);
    // 초상이 이름 · 능력 칸을 덮지 않는다(상자가 겹치지 않음).
    expect(await detail.evaluate((d) => {
      const pic = d.querySelector('.os-portrait')?.getBoundingClientRect();
      const boxes = [d.querySelector('h3'), d.querySelector('[role="group"]')].map((e) => e?.getBoundingClientRect());
      return boxes.filter((b) => pic && b && b.left < pic.right && pic.left < b.right && b.top < pic.bottom && pic.top < b.bottom).length;
    })).toBe(0);
    expect(await insetFromMain(page, page.getByRole('region', { name: '인물 카드' }))).toBeGreaterThanOrEqual(12);
    expect(await coveredIn(main)).toEqual([]);
  }
  expect(served.unknown.filter((u) => MINE.test(u))).toEqual([]);
  await expectNoHorizontalOverflow(page);
  expect(await smallTouchTargets(page, 'main')).toEqual([]);
  expect(await titleOnlyInfo(page, 'main')).toEqual([]);
});

test('작전실 장수 목록의 ?person= 로 들어오면 그 인물이 열린다', { tag: [BOTH] }, async ({ page }, info) => {
  await serveCampaign(page, retinueTable('full'));
  await page.goto('/game/retinue?person=2', { waitUntil: 'domcontentloaded' });
  if (isMobile(info)) await expect(page.getByRole('dialog', { name: '이전 인물 카드' })).toBeVisible({ timeout: 60_000 });
  else await expect(page.getByRole('region', { name: '고른 인물' })).toContainText('이전', { timeout: 60_000 });
});

test('인물 없는 부 — 빈 상태 + 인재탐색 · 등용(명령 흐름으로)', { tag: [BOTH] }, async ({ page }) => {
  await serveCampaign(page, retinueTable('empty'));
  await page.goto('/game/retinue', { waitUntil: 'domcontentloaded' });
  await expect(page.getByText('아직 거느린 인물이 없습니다')).toBeVisible({ timeout: 60_000 });
  await expect(page.getByRole('button', { name: '인재탐색' })).toBeVisible();
  await expect(page.getByRole('button', { name: '등용 — 명령 목록에 넣기' })).toBeVisible();
  // 옛 화면의 네이티브 disabled + title(호버 전용 사유)은 없다.
  expect(await titleOnlyInfo(page, 'main')).toEqual([]);
  expect(await page.locator('main button[disabled]').count()).toBe(0);
});

test('배치 시트 — 자리 종류 → 이 자리로 → 접수 한 줄', { tag: [BOTH] }, async ({ page }, info) => {
  await serveCampaign(page, { ...retinueTable('full'), '/api/commands/placement/assign': { status: 'AVAILABLE' } });
  await page.goto('/game/retinue?person=2', { waitUntil: 'domcontentloaded' });
  const scope = isMobile(info) ? page.getByRole('dialog', { name: '이전 인물 카드' }) : page.getByRole('region', { name: '고른 인물' });
  await press(scope.getByRole('button', { name: '자리에 배치' }), info);
  const sheet = page.getByRole('dialog', { name: '이전 배치' });
  await press(sheet.getByRole('option', { name: '자리에서 풀기' }), info);
  expect(await coveredIn(sheet)).toEqual([]);
  await press(sheet.getByRole('button', { name: '이 자리로' }), info);
  await expect(page.getByRole('status').filter({ hasText: '배치를' })).toContainText('배치를 접수했습니다');
});
