// 작전실(P-W01) 배치 스모크 — 합성 자료로 백엔드 없이, 데스크톱 · 모바일 같은 흐름(@both). 지도 읽기는 404(지도 렌더는 K2 스모크 몫).
// 데스크톱: 지도 영역이 틀을 채우고 오른쪽 12순 열 336, 「내 위치」 → 선택 카드 320. 모바일(390): 지도 전면 · 떠 있는 위 줄 · 선택 알약 · 12순 엿보기 시트 — 두 열을 좁히지 않는다.
import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { frontInfo, serveCampaign } from '../support/campaignFixtures';
import { BOTH, MOBILE_ONLY, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

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
    // 맡겨 둔 일 6칸(보드 STANDING6) — 출병 · 배치 · 방침 · 공사 · 계책 · 발령, 칸마다 그 화면 링크
    await expect(aside.getByRole('link', { name: /^(출병|배치|방침|공사|계책|발령) / })).toHaveCount(6);
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

// D74(10-03, K10 대비 표): 12순 열의 다음 순 줄(청동 0.06 바탕) 위 흐린 글자(--muted)가 4.18:1이다 — 그 자리만 --text-2.
// 다음 순이 빈 순일 때 번호 · 「빈 순」 · 「+ 예약」이 모두 그 바탕 위에 있다. 글자는 aria-hidden 이지만 axe 대비 규칙은 숨김을 빼지 않는다.
test('12순 열 — 다음 순 줄(빈 순) 글자 대비 axe color-contrast 위반 0(D74)', { tag: [BOTH] }, async ({ page }, info) => {
  await serveCampaign(page, { ...table, '/api/reserved-commands': { result: true, generalId: 7, slots: [{ turnIdx: 1, action: 'action.train', brief: '', arg: {} }] } });
  await page.goto('/game', { waitUntil: 'domcontentloaded' });
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  await expect(main.getByRole('region', { name: '지도' })).toBeVisible({ timeout: 60_000 });
  if (isMobile(info)) await press(main.getByRole('region', { name: '명령 목록 12순 — 다음 순' }).getByRole('button', { name: '12순 · 맡겨 둔 일' }), info);
  const column = page.getByTestId('turn-slots-column');
  await expect(column.locator('[data-next="true"][data-state="empty"]')).toHaveCount(1);
  const result = await new AxeBuilder({ page }).include('[data-testid="turn-slots-column"]').withRules(['color-contrast']).analyze();
  expect(result.violations.flatMap((v) => v.nodes.map((n) => n.target.join(' ')))).toEqual([]);
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

/** 턴 루프 정상(RUNNING) — 알림 띠가 없는 평소 운영(serveCampaign 은 server-basic-info 를 404 로 줘서 「운영 상태 확인 중」 띠가 뜬다). */
async function turnLoopRunning(page: Page) {
  await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 200, contentType: 'application/json',
    body: JSON.stringify({ game: { turnLoop: { state: 'RUNNING', staleSeconds: 0 }, serverTime: '2026-10-03T12:00:00Z', month: 3, turnPhaseText: '중순' } }) }));
}

/** 서랍(머리 아래 56부터, 원장 D85)이 열린 채로 칩 줄의 서신 · 도움말이 서랍 위에 보이고 덮이지 않는다. */
async function chipsAboveDrawer(page: Page) {
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
}

test('모바일 작전실 셸 — 띠가 없으면 머리줄 · 제목 줄 없이 지도가 위 0 ~ 탭 위를 다 쓰고, 계절 · 지난 순 · 서신 · 도움말은 지도 위 첫 줄 44(보드 V31K4MWarRoom)', { tag: [MOBILE_ONLY] }, async ({ page }, info) => {
  await serveCampaign(page, table);
  await turnLoopRunning(page);
  await page.goto('/game', { waitUntil: 'domcontentloaded' });
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  const map = main.getByRole('region', { name: '지도' });
  await expect(map).toBeVisible({ timeout: 60_000 });
  await expect(page.locator('[data-band]')).toHaveCount(0);
  const header = page.getByRole('banner');
  // 제목 줄 없음 — 「작전실」 제목은 화면 읽기용으로만 남는다. 로고는 감춘다.
  await expect(main.getByRole('heading', { level: 2, name: '작전실' })).toHaveClass(/sr-only/);
  await expect(header.getByRole('link', { name: '작전실로' })).toBeHidden();
  // 지도 상자: 폭 390, 위 0, 아래는 탭 막대 위.
  const vp = page.viewportSize()!;
  const tabbar = await box(page.getByRole('navigation', { name: '게임 메뉴' }).last());
  const mapBox = await box(map);
  expect(Math.round(mapBox.width)).toBe(vp.width);
  expect(Math.round(mapBox.y)).toBe(0);
  expect(Math.abs(mapBox.y + mapBox.height - tabbar.y)).toBeLessThanOrEqual(1);
  // 첫 줄 칩 넷 — 지도 위(위쪽 60 안), 누를 영역 44 이상.
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
    expect(b.y + b.height).toBeLessThanOrEqual(60);
  }
  expect(await smallTouchTargets(page, 'header')).toEqual([]);
  expect(await coveredIn(header)).toEqual([]);
  // 칩 사이 빈 곳은 지도로 지나간다 — 칩 줄 상자가 그 띠의 지도 조작(끌기 · 성 누르기)을 막지 않는다(#1282 리뷰).
  const lastBox = await box(chips[1]);
  const mailBox = await box(chips[2]);
  expect(mailBox.x - (lastBox.x + lastBox.width)).toBeGreaterThan(8);
  const gap = { x: (lastBox.x + lastBox.width + mailBox.x) / 2, y: lastBox.y + lastBox.height / 2 };
  expect(await page.evaluate(({ x, y }) => {
    const hit = document.elementFromPoint(x, y);
    return Boolean(hit?.closest('main [aria-label="지도"]')) && !hit?.closest('header');
  }, gap), '칩 사이 빈 점을 받은 요소가 지도가 아니다').toBe(true);
  // 지도 상태 한 줄(합성 환경은 지도 읽기 404 → 「불러오지 못했습니다」)은 칩 줄 아래 가운데 — 가리지 않는다.
  const mapState = page.locator('[data-map-state] p');
  await expect(mapState).toBeVisible();
  const stateBox = await box(mapState);
  expect(stateBox.y).toBeGreaterThan(60);
  // 상태 상자는 누르기를 지도로 흘려보내므로(pointer-events none) elementFromPoint 대신 겹침으로 본다 — 칩 줄 · 알약 · 엿보기 시트와 겹치지 않는다.
  const overlapping = await mapState.evaluate((el) => {
    const s = el.getBoundingClientRect();
    const others = Array.from(document.querySelectorAll('header button, header a, main button, main a, [aria-label="명령 목록 12순 — 다음 순"]'));
    return others.filter((o) => {
      const r = o.getBoundingClientRect();
      return r.width > 0 && r.height > 0 && !(r.right <= s.left || r.left >= s.right || r.bottom <= s.top || r.top >= s.bottom);
    }).map((o) => (o.textContent ?? '').trim().slice(0, 20));
  });
  expect(overlapping).toEqual([]);
  // 같은 층 — 계절 칩은 같은 계절 시트를 연다.
  await press(chips[0], info);
  await expect(page.getByRole('dialog', { name: /^계절/ })).toBeVisible();
  // 도움말 서랍이 열려도 칩 줄은 그 위에 보인다.
  await page.goto('/game?help=home', { waitUntil: 'domcontentloaded' });
  await chipsAboveDrawer(page);
});

test('모바일 작전실 셸 — 알림 띠가 있으면 머리줄 56 줄로 남고 띠는 그 아래, 서랍이 열려도 칩은 덮이지 않는다 · 다른 화면 셸은 그대로', { tag: [MOBILE_ONLY] }, async ({ page }) => {
  await serveCampaign(page, table); // server-basic-info 404 → 「운영 상태 확인 중」 띠
  await page.goto('/game?help=home', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('[data-band]')).toBeVisible({ timeout: 60_000 });
  const head = await box(page.getByRole('banner'));
  const band = await box(page.locator('[data-band]'));
  expect(Math.round(head.height)).toBe(56);
  expect(Math.round(band.y)).toBe(Math.round(head.y + head.height));
  await expect(page.getByRole('banner').getByRole('button', { name: /^지난 순/ })).toBeVisible();
  await chipsAboveDrawer(page);

  // 다른 화면(부): 머리줄 56 그대로(로고 보임), 제목 줄 그대로.
  await page.goto('/game/retinue', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('main', { name: '게임 콘텐츠' }).getByRole('heading', { level: 2 }).first()).toBeVisible({ timeout: 60_000 });
  const other = await box(page.getByRole('banner'));
  expect(Math.round(other.height)).toBe(56);
  await expect(page.getByRole('banner').getByRole('link', { name: '작전실로' })).toBeVisible();
});
