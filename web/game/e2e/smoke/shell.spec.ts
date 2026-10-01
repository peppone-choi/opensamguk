// 셸 스모크 — 백엔드 없이 Next 서버만으로 도는 데스크톱 · 모바일 같은 흐름(@both).
// CI web (game) 잡이 `next start` 뒤 e2e/smoke 전체를 두 프로필로 돌린다. 화면 규칙(44 · title · 넘침) 도우미가 실제로 돈다는 것도 여기서 확인한다.
import { expect, test } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

test('옛 휘하 주소는 도메인 경로로 308', { tag: [BOTH] }, async ({ page }) => {
  const res = await page.request.get('/game/hwiha/retinue?tab=bonds', { maxRedirects: 0 });
  expect(res.status()).toBe(308);
  const location = new URL(res.headers()['location'] ?? '', 'http://x');
  expect(location.pathname).toBe('/game/retinue');
  expect(location.searchParams.get('tab')).toBe('bonds');
});

test('삼모 은퇴 경로는 셸을 그리기 전에 404', { tag: [BOTH] }, async ({ page }) => {
  for (const path of ['/game/auction', '/game/rankings/hall-of-fame']) {
    const res = await page.request.get(path, { maxRedirects: 0 });
    expect(res.status(), path).toBe(404);
  }
});

test('없는 화면은 넘침 없이 그린다 — 두 프로필의 창 크기가 실제로 다르다', { tag: [BOTH] }, async ({ page }, testInfo) => {
  const res = await page.goto('/game/does-not-exist-k3', { waitUntil: 'domcontentloaded' });
  expect(res?.status()).toBe(404);
  const width = await page.evaluate(() => window.innerWidth);
  if (isMobile(testInfo)) expect(width).toBe(390);
  else expect(width).toBeGreaterThanOrEqual(1200);
  expect(await page.evaluate(() => navigator.maxTouchPoints > 0)).toBe(isMobile(testInfo));
  await expectNoHorizontalOverflow(page);
});

test('화면 규칙 도우미가 어긴 것을 실제로 찾는다', { tag: [BOTH] }, async ({ page }) => {
  await page.setContent(`<main>
    <button style="width:44px;height:44px">큰</button>
    <button style="width:30px;height:20px">작은</button>
    <span title="이유는 호버로만">비활성</span>
  </main>`);
  expect(await smallTouchTargets(page, 'main')).toEqual(['button "작은" 30×20']);
  expect(await titleOnlyInfo(page, 'main')).toEqual(['span title="이유는 호버로만"']);
});

// ---- v3.1 셸 하나(2026-10-01 셸 통합) ----------------------------------------------------------------
// 합성 로그인 · front-info 로 부 · 월단평을 연다(백엔드 없음 — 게임 읽기는 503, 턴 루프 읽기는 404 → 「운영 상태 확인 중」).
async function openShell(page: import('@playwright/test').Page, path = '/game/retinue/yuedan') {
  // 서버를 알아야 셸이 턴 루프를 읽는다(운영은 경로에 서버가 있다) — 쿠키로 서버를 준다.
  const baseURL = test.info().project.use.baseURL ?? 'http://localhost:3001';
  await page.context().addCookies([{ name: 'sam_server', value: 'pep', url: baseURL }]);
  await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
  await page.route((url) => url.pathname.endsWith('/front-info'), (r) => r.fulfill({ json: {
    result: true,
    global: { year: 200, month: 3, turnPhase: 2, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
    general: { hasGeneral: true, generalId: 7, name: '하후돈', nationId: 1, officerLevel: 1, permission: 0, showSecret: false },
    nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
  } }));
  await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
  await page.route((url) => url.pathname.startsWith('/api/game/') && !url.pathname.endsWith('/front-info'), (r) => r.fulfill({ status: 503, json: {} }));
  await page.goto(path, { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 2, name: '월단평' })).toBeVisible({ timeout: 60_000 });
}

/**
 * 누를 것의 가운데를 다른 상자가 덮는지(K10 「덮임」과 같은 방법 — elementFromPoint).
 * 로컬은 `next start`(운영 빌드)로 돌린다 — `next dev` 의 개발 표시기(NEXTJS-PORTAL)가 레일 「도움말」 · 탭 「작전실」 자리를
 * 덮어 빨개진다(devIndicators 를 끄면 초록, K6 확인). CI 는 next start 라 해당 없다.
 */
async function coveredIn(page: import('@playwright/test').Page, selector: string): Promise<string[]> {
  return page.locator(selector).first().evaluate((root) => {
    const out: string[] = [];
    for (const el of Array.from(root.querySelectorAll<HTMLElement>('a, button'))) {
      const r = el.getBoundingClientRect();
      if (r.width === 0 || r.height === 0) continue;
      const cx = r.x + r.width / 2;
      const cy = r.y + r.height / 2;
      if (cx < 0 || cy < 0 || cx > innerWidth || cy > innerHeight) continue;
      const hit = document.elementFromPoint(cx, cy);
      if (hit !== el && !el.contains(hit)) out.push(`${(el.textContent ?? '').trim()} ← ${hit?.tagName}.${hit?.className}`);
    }
    return out;
  });
}

test('셸: 데스크톱은 레일, 모바일은 하단 탭 — 누를 것 44 · 덮임 0 · 넘침 0', { tag: [BOTH] }, async ({ page }, testInfo) => {
  await openShell(page);
  const menus = page.getByRole('navigation', { name: '게임 메뉴' });
  await expect(menus).toHaveCount(1); // 보이는 것 하나(다른 하나는 display:none)
  const visible = menus.first();
  if (isMobile(testInfo)) {
    await expect(visible.getByRole('link')).toHaveText(['작전실', '부', '계책', '기록']);
    await expect(visible.getByRole('button', { name: '전체' })).toBeVisible();
  } else {
    await expect(visible.getByRole('link')).toHaveText(['작전실', '부', '계책', '영지', '군단', '조정', '기록', '광장', '도움말']);
  }
  await expect(visible.getByRole('link', { name: '부' })).toHaveAttribute('aria-current', 'page');
  expect(await coveredIn(page, 'body')).toEqual([]);
  expect(await smallTouchTargets(page, 'header')).toEqual([]);
  expect(await smallTouchTargets(page, 'nav[aria-label="게임 메뉴"]:visible')).toEqual([]);
  expect(await smallTouchTargets(page, 'nav[aria-label="하위 화면"]')).toEqual([]);
  expect(await titleOnlyInfo(page, 'header')).toEqual([]);
  await expectNoHorizontalOverflow(page);
  await expect(page.locator('[data-band="unknown"]')).toContainText('운영 상태 확인 중');
});

test('셸: 모바일 「전체」 시트는 모든 묶음을 연다', { tag: ['@mobile-only'] }, async ({ page }) => {
  await openShell(page);
  await page.getByRole('button', { name: '전체' }).tap();
  const sheet = page.getByRole('dialog', { name: '전체 메뉴' });
  await expect(sheet).toBeVisible();
  for (const group of ['작전실', '부', '계책', '영지', '군단', '조정', '기록', '광장']) await expect(sheet.getByText(group, { exact: true }).first()).toBeVisible();
  expect(await coveredIn(page, '[role="dialog"]')).toEqual([]);
  await sheet.getByRole('button', { name: '메뉴 닫기' }).tap();
  await expect(sheet).toBeHidden();
});

test('셸: 모바일 하단 탭은 시트(--z-sheet) 아래 층 — 시트 아래쪽 제출 단추 가운데가 단추 자신', { tag: ['@mobile-only'] }, async ({ page }) => {
  await openShell(page);
  // 레인 화면이 쓰는 하단 시트와 같은 층 · 자리(K6 명령 흐름이 잡은 경우) — 탭 막대 위에 제출 단추가 온다.
  await page.evaluate(() => {
    const sheet = document.createElement('section');
    sheet.setAttribute('role', 'dialog');
    sheet.setAttribute('aria-label', '층 확인 시트');
    sheet.style.cssText = 'position:fixed;left:0;right:0;bottom:0;height:240px;z-index:var(--z-sheet);background:var(--panel);display:flex;align-items:flex-end;padding:8px';
    const submit = document.createElement('button');
    submit.type = 'button';
    submit.textContent = '예약';
    submit.style.cssText = 'width:100%;height:44px';
    sheet.append(submit);
    // 화면 본문 안(DOM 에서 탭 막대보다 앞) — 같은 z 면 뒤에 오는 탭 막대가 이긴다. 층 토큰으로만 풀려야 한다.
    document.querySelector('main[aria-label="게임 콘텐츠"]')!.append(sheet);
  });
  expect(await coveredIn(page, '[aria-label="층 확인 시트"]')).toEqual([]);
});

// ---- 계절 칩(K8 · 셸, 보드 V31SystemSeason · MSeason) --------------------------------------------------------
// 데스크톱 · 태블릿은 머리줄 아래 떠 있는 패널(--z-float, 투명 덮개 없음), 모바일은 하단 시트(--z-sheet, 탭 막대를 가린다).
// 「그려짐」(열린 패널 · 44 · 덮임 · 넘침 · 층)과 「조작됨」(닫기 · Esc · 바깥 누름 → 닫힘 · 초점 칩)을 따로 본다.
const SEASON_CHIP = /^봄 · 200년 3월/;

async function hitInside(page: import('@playwright/test').Page, box: { x: number; y: number; width: number; height: number }, selector: string): Promise<boolean> {
  return page.evaluate(([x, y, sel]) => {
    const hit = document.elementFromPoint(x as number, y as number);
    return hit !== null && hit.closest(sel as string) !== null;
  }, [box.x + box.width / 2, box.y + box.height / 2, selector] as const);
}

test('셸: 계절 칩 → 패널이 그려진다 — 누를 것 44 · 덮임 0 · 넘침 0 · 층', { tag: [BOTH] }, async ({ page }, testInfo) => {
  await openShell(page);
  const chip = page.getByRole('button', { name: SEASON_CHIP });
  await expect(chip).toHaveAttribute('aria-expanded', 'false');
  expect((await chip.boundingBox())!.height).toBeGreaterThanOrEqual(44);
  await press(chip, testInfo);
  const dialog = page.getByRole('dialog', { name: '계절 — 봄' });
  await expect(dialog).toBeVisible();
  await expect(chip).toHaveAttribute('aria-expanded', 'true');
  await expect(dialog.getByRole('img', { name: '1년 36순 달력 — 지금 3월 중순' })).toBeVisible();
  await expect(dialog.locator('[data-cell="now"]')).toHaveCount(1);
  await expect(dialog.getByText('계절 소식은 아직 없습니다')).toBeVisible();
  expect(await smallTouchTargets(page, '[role="dialog"]')).toEqual([]);
  expect(await coveredIn(page, '[role="dialog"]')).toEqual([]);
  await expectNoHorizontalOverflow(page);
  if (isMobile(testInfo)) {
    // 시트 층이 하단 탭 막대를 가린다 — 탭 막대 가운데 맨 위가 탭 막대가 아니다.
    const tabbar = await page.locator('nav[aria-label="게임 메뉴"]:visible').boundingBox();
    expect(tabbar).not.toBeNull();
    expect(await hitInside(page, tabbar!, 'nav[aria-label="게임 메뉴"]'), '탭 막대가 시트 위로 올라왔다').toBe(false);
    const sheet = (await dialog.boundingBox())!;
    expect(Math.round(sheet.y + sheet.height)).toBe(844); // 하단 시트
  } else {
    // 데스크톱은 투명 덮개가 없다 — 패널 밖 본문 제목 가운데 맨 위는 본문이다(지도 휠 · 끌기를 먹지 않는다).
    const heading = (await page.getByRole('heading', { level: 2, name: '월단평' }).boundingBox())!;
    expect(await hitInside(page, heading, 'main[aria-label="게임 콘텐츠"]')).toBe(true);
    expect((await dialog.boundingBox())!.width).toBe(400);
    // 층: 지도 위 단추(--z-map-ctrl)가 패널 자리에 와도 패널(--z-float)이 위다.
    const pop = (await dialog.boundingBox())!;
    await page.evaluate(([x, y, w, h]) => {
      const float = document.createElement('div');
      float.setAttribute('data-probe', 'float');
      float.style.cssText = `position:fixed;left:${x}px;top:${y}px;width:${w}px;height:${h}px;z-index:var(--z-map-ctrl);background:rgba(255,0,0,.3)`;
      document.querySelector('main[aria-label="게임 콘텐츠"]')!.append(float);
    }, [pop.x, pop.y, pop.width, pop.height] as const);
    expect(await coveredIn(page, '[role="dialog"]')).toEqual([]);
  }
});

test('셸: 계절 패널이 조작된다 — 닫기 · Esc · 바깥 누름으로 닫히고 초점은 칩으로', { tag: [BOTH] }, async ({ page }, testInfo) => {
  await openShell(page);
  const chip = page.getByRole('button', { name: SEASON_CHIP });
  const dialog = page.getByRole('dialog', { name: '계절 — 봄' });

  await press(chip, testInfo);
  await expect(dialog.getByRole('button', { name: '계절 닫기' })).toBeFocused(); // 열면 초점은 닫기
  await press(dialog.getByRole('button', { name: '계절 닫기' }), testInfo);
  await expect(dialog).toBeHidden();
  await expect(chip).toBeFocused();

  await press(chip, testInfo);
  await expect(dialog).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(chip).toBeFocused();

  // 패널 안 초점 못 받는 곳(달력)을 눌러도 닫히지 않고, 그 뒤 Esc 가 듣는다.
  await press(chip, testInfo);
  await expect(dialog).toBeVisible();
  await press(dialog.getByRole('img', { name: /^1년 36순 달력/ }), testInfo);
  await expect(dialog).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(chip).toBeFocused();

  await press(chip, testInfo);
  await expect(dialog).toBeVisible();
  if (isMobile(testInfo)) {
    await page.touchscreen.tap(195, 120); // 시트 위 덮개(모달) — 닫고 초점은 칩으로
    await expect(dialog).toBeHidden();
    await expect(chip).toBeFocused();
  } else {
    // 바깥 누름(비모달) — 닫고, 누른 입력칸이 초점을 지킨다(칩으로 빼앗지 않는다).
    await page.evaluate(() => {
      const input = document.createElement('input');
      input.setAttribute('aria-label', '시험 입력칸');
      input.style.cssText = 'width:200px;height:44px';
      document.querySelector('main[aria-label="게임 콘텐츠"]')!.prepend(input);
    });
    const input = page.getByRole('textbox', { name: '시험 입력칸' });
    await input.click();
    await expect(dialog).toBeHidden();
    await page.waitForTimeout(100); // 닫힌 뒤 예약된 초점 이동이 있으면 여기서 돈다
    await expect(input).toBeFocused();
    await page.keyboard.type('가');
    await expect(input).toHaveValue('가');
    // 키보드로 밖의 입력칸에 간 뒤 Esc — 닫기만 하고 초점은 입력칸에 둔다.
    await press(chip, testInfo);
    await expect(dialog).toBeVisible();
    await input.focus();
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
    await page.waitForTimeout(100);
    await expect(input).toBeFocused();
  }
  await expect(chip).toHaveAttribute('aria-expanded', 'false');
});

test('셸: 계절 패널 · 도움말 서랍 · 「전체」 시트는 한 번에 하나(같은 여닫기)', { tag: [BOTH] }, async ({ page }, testInfo) => {
  await openShell(page);
  const chip = page.getByRole('button', { name: SEASON_CHIP });
  const season = page.getByRole('dialog', { name: '계절 — 봄' });
  const menu = page.getByRole('dialog', { name: '전체 메뉴' });
  const drawer = page.getByRole('complementary', { name: '도움말' });
  const helpLink = page.getByRole('link', { name: '이 화면 도움말' });
  // 열린 층의 가운데를 누르면 그 층이 받는다(elementFromPoint) — 다른 층 · 탭 막대 · 지도가 위에 있지 않다.
  const topIs = async (layer: typeof season, selector: string) => hitInside(page, (await layer.boundingBox())!, selector);
  const SEASON = '[role="dialog"][aria-labelledby="season-dialog-title"]';
  const DRAWER = 'aside[aria-label="도움말"]';

  // 서랍 → 계절: 서랍이 열린 채 계절 칩을 누르면 서랍이 닫히고(?help= 만 빠진다) 패널이 열린다.
  await press(helpLink, testInfo);
  await expect(drawer).toBeVisible();
  expect(await topIs(drawer, DRAWER), '서랍 가운데가 서랍이 아니다').toBe(true);
  await press(chip, testInfo);
  await expect(season).toBeVisible();
  await expect(drawer).toHaveCount(0);
  await expect(page).not.toHaveURL(/[?&]help=/);
  await expect(page).toHaveURL(/\/game\/retinue\/yuedan$/);
  expect(await topIs(season, SEASON), '계절 가운데가 계절이 아니다').toBe(true);

  if (isMobile(testInfo)) {
    // 계절 → 서랍: 시트 덮개가 머리줄 「도움말」을 가려 계절이 열린 채로는 서랍을 열 수 없다. 닫고 열면 서랍만 있다.
    expect(await hitInside(page, (await helpLink.boundingBox())!, 'header'), '계절 시트 위로 도움말이 눌린다').toBe(false);
    await page.keyboard.press('Escape');
    await expect(season).toBeHidden();
    // 「전체」: 시트 덮개가 머리줄(도움말 · 계절 칩)을 가린다.
    await press(page.getByRole('button', { name: '전체' }), testInfo);
    await expect(menu).toBeVisible();
    expect(await topIs(menu, '[role="dialog"]'), '전체 시트 가운데가 시트가 아니다').toBe(true);
    expect(await hitInside(page, (await helpLink.boundingBox())!, 'header'), '전체 시트 위로 도움말이 눌린다').toBe(false);
    expect(await hitInside(page, (await chip.boundingBox())!, 'header'), '전체 시트 위로 계절 칩이 눌린다').toBe(false);
    await page.keyboard.press('Escape');
    await expect(menu).toHaveCount(0);
    await press(helpLink, testInfo);
    await expect(drawer).toBeVisible();
    await expect(season).toHaveCount(0);
    expect(await topIs(drawer, DRAWER), '서랍 가운데가 서랍이 아니다').toBe(true);
    // 서랍은 탭 막대를 가린다 — 서랍이 열린 채 「전체」를 누를 수 없다.
    const tab = (await page.getByRole('button', { name: '전체' }).boundingBox())!;
    expect(await hitInside(page, tab, 'nav[aria-label="게임 메뉴"]'), '서랍 위로 「전체」가 눌린다').toBe(false);
  } else {
    // 계절 → 서랍: 계절이 열린 채 도움말을 누르면 계절이 닫히고 서랍이 열린다.
    await press(helpLink, testInfo);
    await expect(drawer).toBeVisible();
    await expect(season).toHaveCount(0);
    await expect(chip).toHaveAttribute('aria-expanded', 'false');
    expect(await topIs(drawer, DRAWER), '서랍 가운데가 서랍이 아니다').toBe(true);
  }
});

test('옮긴 캠페인 화면의 옛 주소는 새 주소로 한 번에 308', { tag: [BOTH] }, async ({ page }) => {
  const cases: Array<[string, string]> = [
    ['/game/yuedan', '/game/retinue/yuedan'], ['/game/hand', '/game/stratagem'], ['/game/posts', '/game/territory'],
    ['/game/supply', '/game/territory/supply'], ['/game/siege?county=7', '/game/corps/siege?county=7'],
    ['/game/orders', '/game/court?tab=orders'], ['/game/war-room', '/game'], ['/game/hwiha/war-room', '/game'],
  ];
  for (const [from, to] of cases) {
    const res = await page.request.get(from, { maxRedirects: 0 });
    expect(res.status(), from).toBe(308);
    const location = new URL(res.headers()['location'] ?? '', 'http://x');
    expect(`${location.pathname}${location.search}`, from).toBe(to);
  }
});
