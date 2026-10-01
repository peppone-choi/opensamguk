// 월단평(P-R04) 스모크 — 합성 자료(e2e/support/campaignFixtures)로 백엔드 없이, 데스크톱 · 모바일 같은 흐름(@both).
// K7 · K0 지적(390 카드 겹침 · 오른쪽 잘림 · 「Not Found」 원문 · 좁게 접히는 경로 칸)을 화면 규칙으로 잰다.
import { expect, test, type Locator, type Page } from '@playwright/test';
import { frontInfo, retinue, serveCampaign } from '../support/campaignFixtures';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const reason = (kind: string, label: string, count: number, amount: number) => ({ kind, label, count, amount });
const row = (rank: number, generalId: number, name: string, nationName: string, renown: number, reasons: unknown[] = []) =>
  ({ rank, generalId, name, nationId: rank, nationName, nationColor: '#4f7fbf', renown, reasons });
const yuedan = {
  status: 'READY', stamp: '0200-03',
  self: { generalId: 7, renown: 30, retinueCost: 36, overCapacity: true },
  selfPendingEvents: [],
  ranking: [
    row(1, 1, '조조', '조조', 88, [reason('MERIT', '전공', 2, 6), reason('ADMIN', '치적', 1, 2)]),
    row(2, 7, '하후돈', '조조', 30, [reason('BOND', '결속 사건', 1, 1)]),
    row(3, 9, '사마의중달장군', '아주긴세력이름의연합', 12, [reason('DEFEAT', '패전', 3, -6), reason('REFUSE', '발령 거절', 1, -2), reason('MISRULE', '실정', 1, -1)]),
  ],
};
const table = { '/api/front-info': frontInfo(), '/api/yuedan': yuedan, '/api/retinue': retinue('full') };
/** 이 화면이 부르는 조회 — 셸 자신의 조회는 셸 스모크 몫. */
const MINE = /\/api\/(yuedan|retinue)/;

async function insetFromMain(page: Page, target: Locator): Promise<number> {
  const main = await page.getByRole('main', { name: '게임 콘텐츠' }).boundingBox();
  const box = await target.boundingBox();
  return Math.round((box?.x ?? 0) - (main?.x ?? 0));
}

/** 누를 것의 가운데를 다른 상자가 덮는지(K10 「덮임」 — elementFromPoint). 화면 밖은 세지 않는다. */
async function coveredIn(root: Locator): Promise<string[]> {
  return root.evaluate((r) => {
    const out: string[] = [];
    for (const el of Array.from(r.querySelectorAll<HTMLElement>('a, button, [role="radio"], [tabindex="0"]'))) {
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

/** 글이 상자 밖으로 넘쳐 잘린 것(넘친 쪽이 숨김 · 잘림) — 말줄임표 없이 오른쪽이 잘리는 K7 지적. */
async function clippedText(root: Locator): Promise<string[]> {
  return root.evaluate((r) => Array.from(r.querySelectorAll<HTMLElement>('*'))
    .filter((el) => {
      const cs = getComputedStyle(el);
      return (cs.overflowX === 'hidden' || cs.overflowX === 'clip') && el.scrollWidth > el.clientWidth + 1 && cs.textOverflow !== 'ellipsis';
    })
    .map((el) => `${el.tagName}.${el.className} 「${(el.textContent ?? '').trim().slice(0, 20)}」`));
}

/** 같은 목록의 카드끼리 상자가 겹치는지(390 카드 겹침 지적). */
async function overlappingSiblings(list: Locator): Promise<number> {
  return list.evaluate((l) => {
    const boxes = Array.from(l.children).map((c) => c.getBoundingClientRect());
    let n = 0;
    for (let i = 1; i < boxes.length; i++) if (boxes[i].top < boxes[i - 1].bottom - 0.5) n++;
    return n;
  });
}

test('순위 · 내 명망 · 경로 · 이탈 순서 — 겹침 · 잘림 · 넘침 0, 44 · title 전용 0, 영어 원문 0, 여백', { tag: [BOTH] }, async ({ page }, info) => {
  const served = await serveCampaign(page, table);
  await page.goto('/game/retinue/yuedan', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 2, name: '월단평' })).toBeVisible({ timeout: 60_000 });
  await expect(page.getByRole('heading', { level: 3, name: '200년 3월 월단평' })).toBeVisible();
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  await expect(main).not.toContainText('휘하');
  await expect(page.getByText('오르는 경로')).toBeVisible();
  await expect(page.getByText('떨어지는 경로')).toBeVisible();
  if (isMobile(info)) {
    const list = page.getByRole('list', { name: '월단평 순위' });
    await expect(list).toContainText('사마의중달장군');
    expect(await insetFromMain(page, list)).toBeGreaterThanOrEqual(12);
    expect(await overlappingSiblings(list)).toBe(0);
    expect(await coveredIn(main)).toEqual([]);
    await press(page.getByRole('radio', { name: '이탈 순서' }), info);
    await expect(page.getByRole('list', { name: '이탈 판정 순서' })).toContainText('무명 공조');
  } else {
    await expect(page.getByRole('region', { name: '이탈 판정 순서' })).toContainText('무명 공조');
    expect(await insetFromMain(page, page.getByRole('region', { name: '200년 3월 월단평' }))).toBeGreaterThanOrEqual(12);
    expect(await coveredIn(main)).toEqual([]);
  }
  expect(await clippedText(main)).toEqual([]);
  expect(served.unknown.filter((u) => MINE.test(u))).toEqual([]);
  expect(await main.innerText()).not.toMatch(/[A-Za-z]{3,}/);
  await expectNoHorizontalOverflow(page);
  expect(await smallTouchTargets(page, 'main')).toEqual([]);
  expect(await titleOnlyInfo(page, 'main')).toEqual([]);
});

test('읽기 실패 — 「Not Found」 원문 없이 한국어 오류 + 다시 시도, 이탈 순서는 「상한 안」으로 보이지 않는다', { tag: [BOTH] }, async ({ page }, info) => {
  // 부 조회를 표에서 빼면 404 — 이탈 판정 순서 칸이 빈 상태가 아니라 실패를 말해야 한다.
  const { '/api/retinue': _retinue, ...rest } = table;
  await serveCampaign(page, rest);
  await page.goto('/game/retinue/yuedan', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 3, name: '200년 3월 월단평' })).toBeVisible({ timeout: 60_000 });
  if (isMobile(info)) await press(page.getByRole('radio', { name: '이탈 순서' }), info);
  await expect(page.getByText('이탈 판정 순서를 불러오지 못했습니다')).toBeVisible();
  await expect(page.getByRole('main', { name: '게임 콘텐츠' })).not.toContainText('상한 안');
  expect(await page.locator('body').innerText()).not.toContain('Not Found');
});
