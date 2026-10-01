// 셸 스모크 — 백엔드 없이 Next 서버만으로 도는 데스크톱 · 모바일 같은 흐름(@both).
// CI web (game) 잡이 `next start` 뒤 e2e/smoke 전체를 두 프로필로 돌린다. 화면 규칙(44 · title · 넘침) 도우미가 실제로 돈다는 것도 여기서 확인한다.
import { expect, test } from '@playwright/test';
import { BOTH, clippedWithoutEllipsis, expectNoHorizontalOverflow, isMobile, smallTouchTargets, titleOnlyInfo } from '../support/parity';

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
  // 잘림: flex 상자에 바로 넣은 글자는 「…」 없이 잘린다(잡힘), span 이 줄이면 「…」(안 잡힘), 넘치지 않으면 상관없다.
  await page.setContent(`<main style="width:200px">
    <span class="flexcut" style="display:flex;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">아주 긴 설명이 상자 폭을 한참 넘어 잘립니다</span>
    <span style="display:flex;overflow:hidden;white-space:nowrap"><span class="ok" style="min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">아주 긴 설명이 상자 폭을 한참 넘어 잘립니다</span></span>
    <span style="display:flex;overflow:hidden;white-space:nowrap">짧음</span>
  </main>`);
  expect(await clippedWithoutEllipsis(page, 'main')).toEqual([expect.stringMatching(/^span\.flexcut 「아주 긴 설명/)]);
});

// ---- v3.1 셸 하나(2026-10-01 셸 통합) ----------------------------------------------------------------
// 합성 로그인 · front-info 로 부 · 월단평을 연다(백엔드 없음 — 게임 읽기는 503, 턴 루프 읽기는 404 → 「운영 상태 확인 중」).
async function openShell(page: import('@playwright/test').Page, path = '/game/retinue/yuedan', heading = '월단평') {
  // 서버를 알아야 셸이 턴 루프를 읽는다(운영은 경로에 서버가 있다) — 쿠키로 서버를 준다.
  const baseURL = test.info().project.use.baseURL ?? 'http://localhost:3001';
  await page.context().addCookies([{ name: 'sam_server', value: 'pep', url: baseURL }]);
  await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
  await page.route((url) => url.pathname.endsWith('/front-info'), (r) => r.fulfill({ json: {
    result: true,
    global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
    general: { hasGeneral: true, generalId: 7, name: '하후돈', nationId: 1, officerLevel: 1, permission: 0, showSecret: false },
    nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
  } }));
  await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
  await page.route((url) => url.pathname.startsWith('/api/game/') && !url.pathname.endsWith('/front-info'), (r) => r.fulfill({ status: 503, json: {} }));
  await page.goto(path, { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 2, name: heading })).toBeVisible({ timeout: 60_000 });
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

test('셸: 모바일 하단 탭은 스크롤로 끌어온 요소를 덮지 않는다 — scrollIntoView · Tab 이동 뒤 가운데가 그 단추', { tag: ['@mobile-only'] }, async ({ page }) => {
  await openShell(page);
  // 문서가 스크롤되고 탭 막대는 화면 아래에 붙어 있다(sticky). 「보일 만큼만」 끌어온 요소의 아래끝이 탭 막대 밑에 놓이면 안 된다(K6 서신 「보내기」).
  await page.evaluate(() => {
    const main = document.querySelector('main[aria-label="게임 콘텐츠"]')!;
    const button = (label: string) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = label;
      b.style.cssText = 'display:block;width:100%;height:44px';
      return b;
    };
    const gap = () => {
      const d = document.createElement('div');
      d.style.height = '1500px';
      return d;
    };
    main.append(button('스크롤 대상'), gap(), button('탭 앞'), gap(), button('탭 대상'));
  });
  const centerHit = (name: string) => page.getByRole('button', { name, exact: true }).evaluate((el) => {
    const r = el.getBoundingClientRect();
    const hit = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
    return hit === el || el.contains(hit) ? '자신' : `${hit?.tagName}.${hit?.className}`;
  });
  // ① scrollIntoView(nearest) — 위에서 내려오면 단추 아래끝을 스크롤 영역 아래끝에 맞춘다.
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.getByRole('button', { name: '스크롤 대상', exact: true }).evaluate((el) => el.scrollIntoView({ block: 'nearest' }));
  expect(await centerHit('스크롤 대상')).toBe('자신');
  // ② 키보드 Tab — 브라우저가 다음 단추를 보일 만큼만 끌어온다.
  await page.getByRole('button', { name: '탭 앞', exact: true }).focus();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: '탭 대상', exact: true })).toBeFocused();
  expect(await centerHit('탭 대상')).toBe('자신');
});

test('셸: 본문 여백은 셸이 준다 — 데스크톱 12 · 모바일 10 · 12, 지도 화면(bleed)은 0', { tag: [BOTH] }, async ({ page }, testInfo) => {
  const mobile = isMobile(testInfo);
  const measure = () => page.locator('[data-shell-body]').evaluate((body) => {
    const cs = getComputedStyle(body);
    const first = body.firstElementChild?.getBoundingClientRect() ?? null;
    const rail = document.querySelector('nav[aria-label="게임 메뉴"]');
    const railRight = rail && getComputedStyle(rail).display !== 'none' ? rail.getBoundingClientRect().right : 0;
    return {
      kind: body.getAttribute('data-shell-body'),
      padding: [cs.paddingTop, cs.paddingRight, cs.paddingBottom, cs.paddingLeft].map((v) => Math.round(parseFloat(v))),
      // 레일(모바일은 화면 왼끝)과 본문 첫 상자 사이
      gapLeft: first ? Math.round(first.left - railRight) : null,
    };
  });
  await openShell(page);
  const padded = await measure();
  expect(padded.kind).toBe('padded');
  expect(padded.padding).toEqual(mobile ? [10, 12, 10, 12] : [12, 12, 12, 12]);
  expect(padded.gapLeft).toBe(12);
  // 작전실은 지도로 꽉 채운다(bleed) — 셸 여백 0. 안쪽 배치는 작전실 화면(K2) 몫이다.
  await page.goto('/game', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 2, name: '작전실' })).toBeVisible({ timeout: 60_000 });
  const bleed = await measure();
  expect(bleed.kind).toBe('bleed');
  expect(bleed.padding).toEqual([0, 0, 0, 0]);
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
