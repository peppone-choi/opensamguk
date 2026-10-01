// 부 편성(P-R01) 스모크 — 합성 자료(e2e/support/campaignFixtures)로 백엔드 없이, 데스크톱 · 모바일 같은 흐름(@both).
// 화면 이름(부 이름) · 인물 목록 · 상세(데스크톱) / 세그먼트 · 인물 카드 시트(모바일) · 배치 시트 · 빈 부, 화면 규칙(44 · 덮임 · 넘침 · title 전용 · 여백).
import { expect, test, type Locator, type Page } from '@playwright/test';
import { retinueTable, serveCampaign } from '../support/campaignFixtures';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

/** 이 화면이 부르는 조회 — 셸 자신의 조회는 셸 스모크 몫이라 여기서 세지 않는다. */
const MINE = /\/api\/(retinue|posts|yuedan|commands)/;

/** 본문 왼쪽 여백 — 셸 본문은 여백이 없어 화면이 준다(보드 desk_main 12 · mob_main 12). */
async function insetFromMain(page: Page, target: Locator): Promise<number> {
  const main = await page.getByRole('main', { name: '게임 콘텐츠' }).boundingBox();
  const box = await target.boundingBox();
  return Math.round((box?.x ?? 0) - (main?.x ?? 0));
}

/** 누를 것의 가운데를 다른 상자가 덮는지(K10 「덮임」 · 셸 스모크와 같은 방법 — elementFromPoint). 화면 밖은 세지 않는다. */
async function coveredIn(root: Locator): Promise<string[]> {
  return root.evaluate((r) => {
    const out: string[] = [];
    for (const el of Array.from(r.querySelectorAll<HTMLElement>('a, button, select, input, [role="option"], [role="radio"]'))) {
      const b = el.getBoundingClientRect();
      if (b.width === 0 || b.height === 0) continue;
      const cx = b.x + b.width / 2;
      const cy = b.y + b.height / 2;
      if (cx < 0 || cy < 0 || cx > innerWidth || cy > innerHeight) continue;
      const hit = document.elementFromPoint(cx, cy);
      if (hit !== el && !el.contains(hit)) out.push(`${(el.textContent ?? '').trim()} ← ${hit?.tagName}.${hit?.className}`);
    }
    return out;
  });
}

test('인물이 있는 부 — 부 이름 · 목록 · 상세 / 인물 카드 시트, 덮임 · 44 · 넘침 · title 전용 · 여백', { tag: [BOTH] }, async ({ page }, info) => {
  const served = await serveCampaign(page, retinueTable('full'));
  await page.goto('/game/retinue', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 2, name: '하후돈의 막부' })).toBeVisible({ timeout: 60_000 });
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  await expect(main).not.toContainText('휘하');
  if (isMobile(info)) {
    const seg = page.getByRole('radiogroup', { name: '보기' });
    await expect(seg).toBeVisible();
    expect(await insetFromMain(page, seg)).toBeGreaterThanOrEqual(12);
    expect(await coveredIn(main)).toEqual([]);
    // 인물 상세 화면(P-R03) 전 — 장수도 이 화면 안 카드로 연다.
    await press(page.getByRole('option', { name: /허저/ }), info);
    const card = page.getByRole('dialog', { name: '허저 인물 카드' });
    await expect(card.getByRole('button', { name: '닫기' })).toBeVisible();
    expect(await coveredIn(card)).toEqual([]);
    expect(await smallTouchTargets(page, '[role="dialog"]')).toEqual([]);
    expect(await titleOnlyInfo(page, '[role="dialog"]')).toEqual([]);
    await press(card.getByRole('button', { name: '닫기' }), info);
    await expect(card).toBeHidden();
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
