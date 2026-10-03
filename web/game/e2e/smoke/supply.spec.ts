// 영지 › 창고망 · 보급(P-T04) 스모크 — 합성 자료로 백엔드 없이, 데스크톱 · 모바일 같은 흐름(@both). 공용 픽스처는 고치지 않는다.
import { expect, test, type Locator, type Page } from '@playwright/test';
import { frontInfo, serveCampaign } from '../support/campaignFixtures';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const zero = { money: 0, grain: 0, iron: 0, timber: 0, horses: 0 };
const table = {
  '/api/front-info': frontInfo(),
  '/api/warehouses': { status: 'READY', invalidCount: 0, warehouses: [
    { cityId: 5, name: '양적현', commanderyName: '영천군', isCapital: false, supplied: true, stock: { ...zero, money: 300, grain: 40 } },
    { cityId: 3, name: '허현', commanderyName: '영천군', isCapital: true, supplied: true, stock: { ...zero, money: 1200, grain: 800 } },
    { cityId: 9, name: '윤씨현', commanderyName: '영천군', isCapital: false, supplied: false, stock: { ...zero, money: 50 } },
  ] },
};
/** 이 화면이 부르는 조회 — 셸 · 도움말 조회는 각자 스모크 몫. */
const MINE = /\/api\/warehouses/;

/** 본문 왼쪽 여백 — 셸(GameShell .body)이 주는 12와 정확히 같다(화면 루트가 또 주면 24로 겹친다, #1133). */
async function insetFromMain(page: Page, target: Locator): Promise<number> {
  const main = await page.getByRole('main', { name: '게임 콘텐츠' }).boundingBox();
  const box = await target.boundingBox();
  return Math.round((box?.x ?? 0) - (main?.x ?? 0));
}

/** 누를 것의 가운데를 다른 상자가 덮는지(K10 「덮임」 — elementFromPoint). 화면 밖은 세지 않는다. */
async function coveredIn(root: Locator): Promise<string[]> {
  return root.evaluate((r) => {
    const out: string[] = [];
    for (const el of Array.from(r.querySelectorAll<HTMLElement>('a, button, [role="radio"]'))) {
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

test('재고 · 끊긴 곳 · 물자조달(데) / 세그먼트(모) — 덮임 · 넘침 0, 44 · title 전용 · 영어 원문 0, 여백 12, 물자조달은 명령 흐름', { tag: [BOTH] }, async ({ page }, info) => {
  const served = await serveCampaign(page, table);
  await page.goto('/game/territory/supply', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 2, name: '영지' })).toBeVisible({ timeout: 60_000 });
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  await expect(main).toContainText('허현');
  expect(served.unknown.filter((u) => MINE.test(u))).toEqual([]);
  if (isMobile(info)) {
    const seg = main.getByRole('radiogroup', { name: '보기' });
    expect(await insetFromMain(page, seg)).toBe(12);
    await expect(main.getByTestId('connected-total')).toContainText('금 1,500');
    await press(seg.getByRole('radio', { name: /끊긴 곳/ }), info);
    await expect(main.getByRole('list', { name: '끊긴 곳' })).toContainText('윤씨현은 수도와 끊겨 제 창고만 씁니다.');
  } else {
    const stock = main.getByRole('region', { name: '창고별 재고' });
    expect(await insetFromMain(page, stock)).toBe(12);
    await expect(stock.getByRole('row').nth(1)).toContainText('허현');
    await expect(main.getByRole('region', { name: '끊긴 곳' })).toContainText('윤씨현은 수도와 끊겨 제 창고만 씁니다.');
  }
  expect(await coveredIn(main)).toEqual([]);
  expect(await main.innerText()).not.toMatch(/[A-Za-z]{3,}/);
  await expectNoHorizontalOverflow(page);
  expect(await smallTouchTargets(page, 'main')).toEqual([]);
  expect(await titleOnlyInfo(page, 'main')).toEqual([]);
  await press(main.getByRole('button', { name: '물자조달 — 명령 목록에 넣기' }), info);
  await expect(page).toHaveURL(/\/game(\/pep)?\?do=action\.transport$/);
});
