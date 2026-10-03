// 작전실(P-W01) 배치 스모크 — 합성 자료로 백엔드 없이, 데스크톱 · 모바일 같은 흐름(@both). 지도 읽기는 404(지도 렌더는 K2 스모크 몫).
// 데스크톱: 지도 영역이 틀을 채우고 오른쪽 12순 열 336, 「내 위치」 → 선택 카드 320. 모바일(390): 지도 전면 · 떠 있는 위 줄 · 선택 알약 · 12순 엿보기 시트 — 두 열을 좁히지 않는다.
import { expect, test, type Locator, type Page } from '@playwright/test';
import { frontInfo, serveCampaign } from '../support/campaignFixtures';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const base = frontInfo();
const table = {
  // 장수 카드(옛 작전실 명부)가 「병력 NaN」을 그리던 고정 자료 — front-info 에 crew 가 없다.
  '/api/front-info': base,
  '/api/reserved-commands': { result: true, generalId: 7, slots: [{ turnIdx: 0, action: 'action.train', brief: '', arg: {} }] },
};

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

async function box(locator: Locator) {
  const b = await locator.boundingBox();
  if (!b) throw new Error('상자가 없습니다');
  return b;
}

async function checkQuality(page: Page) {
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  expect(await coveredIn(main)).toEqual([]);
  await expectNoHorizontalOverflow(page);
  expect(await smallTouchTargets(page, 'main')).toEqual([]);
  expect(await titleOnlyInfo(page, 'main')).toEqual([]);
  await expect(main).not.toContainText('NaN');
}

test('데스크톱 · 모바일 배치 — 지도가 틀을 채우고, 12순은 오른쪽 열(데) · 엿보기 시트(모), 덮임 · 넘침 0, 44 · title 전용 0, NaN 0', { tag: [BOTH] }, async ({ page }, info) => {
  await serveCampaign(page, table);
  await page.goto('/game', { waitUntil: 'domcontentloaded' });
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  const map = main.getByRole('region', { name: '지도' });
  await expect(map).toBeVisible({ timeout: 60_000 });
  const mainBox = await box(main);
  const mapBox = await box(map);
  if (isMobile(info)) {
    // 지도 전면 — 화면 폭을 다 쓰고(옛 배치는 151px로 좁혔다), 12순 열은 없다.
    expect(Math.round(mapBox.width)).toBe(Math.round(mainBox.width));
    await expect(page.getByRole('complementary', { name: '명령 목록 12순' })).toHaveCount(0);
    const peek = main.getByRole('region', { name: '명령 목록 12순 — 다음 순' });
    await expect(peek).toBeVisible();
    // 엿보기 시트는 지도 바닥에 붙는다.
    const peekBox = await box(peek);
    expect(Math.round(peekBox.y + peekBox.height)).toBe(Math.round(mapBox.y + mapBox.height));
    await expect(main.getByRole('button', { name: '내 위치 — 양적현' })).toBeVisible();
    // 「지난 순」 칩은 셸 머리줄 칩 줄(지도 위 첫 줄)에 꽂힌다(보드 V31K4MWarRoom).
    await expect(page.getByRole('banner').getByRole('button', { name: /^지난 순/ })).toBeVisible();
    await checkQuality(page);
    await press(peek.getByRole('button', { name: '12순 · 맡겨 둔 일' }), info);
    const sheet = page.getByRole('dialog', { name: '명령 목록 12순 · 맡겨 둔 일' });
    await expect(sheet.getByRole('group', { name: '명령 목록 12순' })).toBeVisible();
    expect(await smallTouchTargets(page, '[role="dialog"]')).toEqual([]);
  } else {
    const aside = page.getByRole('complementary', { name: '명령 목록 12순' });
    await expect(aside).toBeVisible();
    expect(Math.round((await box(aside)).width)).toBe(336);
    // 지도 영역이 작전실 틀의 높이를 채우고(옛 배치는 패널 + 높이 560 고정), 틀은 셸 본문 바닥까지 간다.
    const layoutBox = await box(page.getByTestId('war-room-layout'));
    expect(Math.round(mapBox.height)).toBe(Math.round(layoutBox.height));
    expect(Math.round(layoutBox.y + layoutBox.height)).toBe(Math.round(mainBox.y + mainBox.height));
    await expect(aside.getByRole('heading', { name: '맡겨 둔 일' })).toBeVisible();
    await expect(aside.getByRole('button', { name: /^이번 순에 할 일/ })).toBeVisible();
    await checkQuality(page);
    // 「내 위치」 알약은 내 城을 고른다 → 지도 오른쪽 위 선택 카드(보드 sel_card 320). 카드 안 단추도 44 · title 전용 0.
    await press(main.getByRole('button', { name: '내 위치 — 양적현' }), info);
    const card = main.getByRole('region', { name: '고른 현 — 양적현' });
    await expect(card).toBeVisible();
    expect(Math.round((await box(card)).width)).toBe(320);
    await expect(card.getByRole('link', { name: '현 상세' })).toBeVisible();
    await expect(card.getByRole('button', { name: '여기로 명령' })).toBeVisible();
    await checkQuality(page);
  }
});

test('「이번 순에 할 일」 → 명령 흐름(순을 정하지 않고 연다)', { tag: [BOTH] }, async ({ page }, info) => {
  await serveCampaign(page, table);
  await page.goto('/game', { waitUntil: 'domcontentloaded' });
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  const start = isMobile(info)
    ? main.getByRole('region', { name: '명령 목록 12순 — 다음 순' }).getByRole('button', { name: '이번 순에 할 일' })
    : page.getByRole('complementary', { name: '명령 목록 12순' }).getByRole('button', { name: /^이번 순에 할 일/ });
  await expect(start).toBeVisible({ timeout: 60_000 });
  await press(start, info);
  await page.waitForURL((u) => u.searchParams.has('do'));
  await expect(page.getByTestId('command-flow')).toBeVisible();
});

test('모바일 작전실 셸 — 머리줄 · 제목 줄 없이 지도가 띠 아래 ~ 탭 위를 다 쓰고, 계절 · 지난 순 · 서신 · 도움말은 지도 위 첫 줄 44, 다른 화면 셸은 그대로(보드 V31K4MWarRoom)', { tag: [BOTH] }, async ({ page }, info) => {
  test.skip(!isMobile(info), '모바일 배치');
  await serveCampaign(page, table);
  await page.goto('/game', { waitUntil: 'domcontentloaded' });
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  const map = main.getByRole('region', { name: '지도' });
  await expect(map).toBeVisible({ timeout: 60_000 });
  const header = page.getByRole('banner');
  // 제목 줄 없음 — 「작전실」 제목은 화면 읽기용으로만 남는다.
  await expect(main.getByRole('heading', { level: 2, name: '작전실' })).toHaveClass(/sr-only/);
  await expect(header.getByRole('link', { name: '작전실로' })).toBeHidden();
  // 지도 상자: 폭 390, 위는 알림 띠 바닥(합성 환경은 「운영 상태 확인 중」 띠, 없으면 0), 아래는 탭 막대 위.
  const vp = page.viewportSize()!;
  const band = page.locator('[data-band]');
  const bandBottom = (await band.count()) ? ((await box(band.first())).y + (await box(band.first())).height) : 0;
  const tabbar = await box(page.getByRole('navigation', { name: '게임 메뉴' }).last());
  const mapBox = await box(map);
  expect(Math.round(mapBox.width)).toBe(vp.width);
  expect(Math.abs(mapBox.y - bandBottom)).toBeLessThanOrEqual(1);
  expect(Math.abs(mapBox.y + mapBox.height - tabbar.y)).toBeLessThanOrEqual(1);
  // 첫 줄 칩 넷 — 지도 위(지도 상자 위쪽 60 안), 누를 영역 44 이상.
  const chips = [
    header.getByRole('button', { name: /^(봄|여름|가을|겨울) · / }),
    header.getByRole('button', { name: /^지난 순/ }),
    header.getByRole('link', { name: '서신' }),
    header.getByRole('link', { name: '이 화면 도움말' }),
  ];
  for (const chip of chips) {
    const b = await box(chip);
    expect(b.height).toBeGreaterThanOrEqual(44);
    expect(b.width).toBeGreaterThanOrEqual(44);
    expect(b.y).toBeGreaterThanOrEqual(mapBox.y);
    expect(b.y + b.height).toBeLessThanOrEqual(mapBox.y + 60);
  }
  expect(await smallTouchTargets(page, 'header')).toEqual([]);
  expect(await coveredIn(header)).toEqual([]);
  // 같은 층 — 계절 칩은 같은 계절 시트를 연다.
  await press(chips[0], info);
  await expect(page.getByRole('dialog', { name: /^계절/ })).toBeVisible();

  // 도움말 서랍은 칩 줄 아래(56)부터(원장 D85) — 열린 채로 칩 줄의 서신 · 도움말이 보이고 덮이지 않는다(elementFromPoint).
  await page.goto('/game?help=home', { waitUntil: 'domcontentloaded' });
  const drawer = page.getByRole('complementary', { name: '도움말' });
  await expect(drawer).toBeVisible({ timeout: 60_000 });
  const drawerTop = (await box(drawer)).y;
  for (const name of ['서신', '이 화면 도움말']) {
    const link = page.getByRole('banner').getByRole('link', { name });
    await expect(link).toBeVisible();
    const b = await box(link);
    expect(b.y + b.height).toBeLessThanOrEqual(drawerTop + 1);
  }
  expect(await coveredIn(page.getByRole('banner'))).toEqual([]);

  // 다른 화면(부): 머리줄 56 그대로(로고 보임), 제목 줄 그대로.
  await page.goto('/game/retinue', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('main', { name: '게임 콘텐츠' }).getByRole('heading', { level: 2 }).first()).toBeVisible({ timeout: 60_000 });
  const other = await box(page.getByRole('banner'));
  expect(Math.round(other.height)).toBe(56);
  await expect(page.getByRole('banner').getByRole('link', { name: '작전실로' })).toBeVisible();
});
