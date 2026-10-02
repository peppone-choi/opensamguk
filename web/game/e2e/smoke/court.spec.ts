// 조정(P-K01) 스모크 — 합성 자료(e2e/support/campaignFixtures)로 백엔드 없이, 데스크톱 · 모바일 같은 흐름(@both).
// 받은 요청 띠(정치 동의 · 발령 응답의 새 길) · 막힌 조정 결정의 서버 사유 · 화면 규칙(44 · title 전용 · 넘침).
import { expect, test, type Locator, type Page } from '@playwright/test';
import { frontInfo, serveCampaign } from '../support/campaignFixtures';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const phase = { year: 200, month: 3, phase: 2 };
const table = {
  '/api/front-info': frontInfo(),
  '/api/commands/dispatches': { result: true, dispatches: [
    { dispatchId: 'd1', issuerId: 1, targetId: 7, countyId: 2, issuerLabel: '조조', countyLabel: '양적현', issuedAt: phase, dueAt: phase, status: 'PENDING' },
    // 내가 내린 발령인데 지금 막힘 — 서버 문장(currentFailureReason)이 없으면 옛 문구(K6-20).
    { dispatchId: 'd2', issuerId: 7, targetId: 21, countyId: 3, targetLabel: '순욱', countyLabel: '허현', issuedAt: phase, dueAt: phase, status: 'PENDING',
      currentFailure: 'NOT_DIRECT_RETAINER', currentFailureReason: null },
  ] },
  '/api/commands/political-consent-options': [],
  '/api/commands/dispatch-options': { result: false, code: 'NOT_LORD', reason: '발령은 주공만 할 수 있습니다.', targets: [], counties: [] },
  '/api/commands/legacy-court-options': { inputId: 'court.moveCapital', available: false, reason: '군주만 할 수 있습니다.', choices: [] },
  '/api/retinue': { status: 'READY', renown: 30, costSum: 0, overCapacity: false, people: [], units: [] },
  '/api/map/preview': { mapCode: 'x', width: 1, height: 1, cities: [{ id: 3, name: '허현' }], nations: [] },
};
/** 이 화면이 부르는 조회 — 셸 자신의 조회(서신 배지 등)는 셸 스모크 몫이라 여기서 세지 않는다. */
const MINE = /\/api\/(commands|retinue|map\/preview)/;

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
    for (const el of Array.from(r.querySelectorAll<HTMLElement>('a, button, select, input'))) {
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

test('받은 요청 · 막힌 결정 사유 · 44 · title 전용 · 넘침', { tag: [BOTH] }, async ({ page }, info) => {
  const served = await serveCampaign(page, table);
  await page.goto('/game/court', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 2, name: '조정' })).toBeVisible({ timeout: 60_000 });
  if (isMobile(info)) {
    const list = page.getByRole('list', { name: '조정 결정' });
    await expect(list.getByRole('listitem').first()).toContainText('응답 대기 1');
    // 본문 가장자리 여백은 셸이 준다 — 정확히 12(화면이 또 주면 24 로 겹친다, #1133).
    expect(await insetFromMain(page, list)).toBe(12);
    // 설명 글이 오른쪽에서 잘리지 않는다(넘친 글 = scrollWidth > clientWidth).
    expect(await list.locator('.os-opt__sub').evaluateAll((els) => els.filter((e) => e.scrollWidth > e.clientWidth + 1).map((e) => e.textContent))).toEqual([]);
    // 목록 끝까지 밀어도 마지막 결정이 하단 탭 아래에 깔리지 않는다(K10: 옛 조정 select 를 셸 탭이 덮음).
    await list.getByRole('button').last().scrollIntoViewIfNeeded();
    expect(await coveredIn(list)).toEqual([]);
    await press(list.getByRole('button').first(), info);
    const sheet = page.getByRole('dialog', { name: '받은 요청' });
    expect(await coveredIn(sheet)).toEqual([]);
    expect(await smallTouchTargets(page, '[role="dialog"]')).toEqual([]);
    await expect(sheet.getByRole('button', { name: '닫기' })).toBeVisible();
    // 시트 머리 글이 시트 테두리에 붙지 않는다.
    const sheetBox = await sheet.boundingBox();
    const headBox = await sheet.getByRole('heading', { name: '받은 요청' }).boundingBox();
    expect(Math.round((headBox?.x ?? 0) - (sheetBox?.x ?? 0))).toBeGreaterThanOrEqual(8);
  } else {
    expect(await coveredIn(page.getByRole('main', { name: '게임 콘텐츠' }))).toEqual([]);
  }
  await expect(page.getByRole('button', { name: '수락' })).toBeVisible();
  await expect(page.getByRole('button', { name: '거절' })).toBeVisible();
  if (!isMobile(info)) {
    expect(await insetFromMain(page, page.getByRole('region', { name: '받은 요청' }))).toBe(12);
    const decisions = page.getByRole('region', { name: '조정 결정' });
    await expect(decisions).toContainText('군주만 할 수 있습니다.');
    await expect(page.getByRole('region', { name: '천도' })).toContainText('지금 수도 — 허현');
    await expect(page.getByRole('button', { name: /새 발령/ })).toHaveAttribute('aria-disabled', 'true');
    await expect(page.getByRole('list', { name: '내린 발령' })).toContainText('현재 관계나 목적지 조건으로 응답할 수 없습니다.');
  }
  expect(served.unknown.filter((u) => MINE.test(u))).toEqual([]);
  // 서버 원문(영어)은 화면에 두지 않는다(K3 공용 규칙 — 코드는 StatusView 오류 번호에만).
  expect(await page.getByRole('main', { name: '게임 콘텐츠' }).innerText()).not.toMatch(/[A-Za-z]{3,}/);
  await expectNoHorizontalOverflow(page);
  expect(await smallTouchTargets(page, 'main')).toEqual([]);
  expect(await titleOnlyInfo(page, 'main')).toEqual([]);
});

test('읽기 실패 — 빈 목록 · 「없습니다」 대신 한국어 오류, 서버 원문 0', { tag: [BOTH] }, async ({ page }, info) => {
  // 부 인물 · 내린 발령 조회를 표에서 빼면 404 — 화면은 「없습니다」가 아니라 실패를 말해야 한다(리뷰 #1128).
  const { '/api/retinue': _retinue, '/api/commands/dispatches': _pending, ...rest } = table;
  await serveCampaign(page, rest);
  await page.goto('/game/court', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 2, name: '조정' })).toBeVisible({ timeout: 60_000 });
  if (isMobile(info)) {
    const list = page.getByRole('list', { name: '조정 결정' });
    await press(list.getByRole('listitem').filter({ hasText: '직속 인물에게' }).getByRole('button'), info);
    await expect(page.getByRole('dialog', { name: '포상' })).toContainText('부 인물을 불러오지 못했습니다');
  } else {
    await expect(page.getByRole('region', { name: '상사' })).toContainText('부 인물을 불러오지 못했습니다');
    const dispatch = page.getByRole('region', { name: '발령' });
    await expect(dispatch).toContainText('내린 발령을 불러오지 못했습니다');
    await expect(dispatch).not.toContainText('내린 발령이 없습니다.');
  }
  expect(await page.locator('body').innerText()).not.toContain('Not Found');
});

test('충성 100 인물의 상사 — 100을 넘으면 사유로 막고, 100은 기록만 남는다고 알린 뒤 접수(사용자 결정 D16)', { tag: [BOTH] }, async ({ page }, info) => {
  await serveCampaign(page, {
    ...table,
    '/api/retinue': { status: 'READY', renown: 30, costSum: 0, overCapacity: false, units: [], people: [
      { retainerId: 31, generalId: 55, name: '문관', picture: null, imageServer: 0, loyalty: 100 },
    ] },
    '/api/commands/court/reward': { status: 'AVAILABLE' },
  });
  await page.goto('/game/court', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 2, name: '조정' })).toBeVisible();
  if (isMobile(info)) {
    await press(page.getByRole('list', { name: '조정 결정' }).getByRole('listitem').filter({ hasText: '직속 인물에게' }).getByRole('button'), info);
  }
  const reward = page.getByRole('region', { name: '상사' });
  await press(reward.getByRole('option', { name: /문관/ }), info);
  const amount = reward.getByRole('textbox', { name: '상사 금액' });
  await amount.fill('300');
  await expect(reward.getByRole('button', { name: /상사 — 접수/ })).toHaveAttribute('aria-disabled', 'true');
  await expect(reward).toContainText('충성은 이미 100입니다 — 금 100으로 상을 내린 기록만 남길 수 있습니다.');
  await amount.fill('100');
  await expect(reward.getByRole('status', { name: '상사 미리 보기' })).toHaveText('충성은 이미 100입니다 — 상을 내린 기록 · 결속 사건만 남습니다');
  const submit = reward.getByRole('button', { name: '상사 — 접수' });
  await expect(submit).not.toHaveAttribute('aria-disabled', 'true');
  const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().includes('/commands/court/reward'));
  await press(submit, info);
  expect((await request).postDataJSON()).toEqual({ retainerId: 31, money: 100 });
  await expect(page.getByText('상사를 접수했습니다 — 다음 개인 턴에 처리합니다.')).toBeVisible();
});

test('네트워크 실패 — 원문 없이 한국어 안내와 다시 시도', { tag: [BOTH] }, async ({ page }, info) => {
  await serveCampaign(page, table);
  await page.route((url) => url.pathname === '/api/game/api/retinue', (route) => route.abort('failed'));
  await page.goto('/game/court', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 2, name: '조정' })).toBeVisible();
  if (isMobile(info)) {
    await press(page.getByRole('list', { name: '조정 결정' }).getByRole('listitem').filter({ hasText: '직속 인물에게' }).getByRole('button'), info);
  }
  const reward = page.getByRole('region', { name: '상사' });
  await expect(reward).toContainText('부 인물을 불러오지 못했습니다');
  await expect(reward.getByRole('button', { name: '다시 시도' })).toBeVisible();
  await expect(reward.getByRole('button', { name: /오류 번호/ })).toHaveCount(0);
  await expect(reward).not.toContainText('Failed to fetch');
  await expect(reward).not.toContainText('상사할 직속 인물 카드가 없습니다.');
});

for (const failure of ['http', 'network', 'denied'] as const) {
  test(`사람별 현 조회 ${failure} — 빈 후보 대신 안내, 같은 사람으로 재시도와 접수`, { tag: [BOTH] }, async ({ page }, info) => {
    const targets = [{ generalId: 21, label: '순욱' }];
    await serveCampaign(page, { ...table,
      '/api/commands/dispatch-options': { result: true, targets, counties: [] },
      '/api/commands/court/dispatch': { status: 'AVAILABLE' },
    });
    let attempts = 0;
    await page.route((url) => url.pathname === '/api/game/api/commands/dispatch-options' && url.searchParams.has('targetGeneralId'), async (route) => {
      expect(new URL(route.request().url()).searchParams.get('targetGeneralId')).toBe('21');
      attempts += 1;
      if (attempts === 1) {
        if (failure === 'network') await route.abort('failed');
        else if (failure === 'http') await route.fulfill({ status: 503, json: { reason: 'Service Unavailable' } });
        else await route.fulfill({ json: { result: false, code: 'NOT_DIRECT_RETAINER', reason: '직접 거느린 장수가 아닙니다.', targets: [], counties: [] } });
      } else await route.fulfill({ json: { result: true, targets, counties: [{ countyId: 129, label: '양성현', available: true }] } });
    });
    await page.goto('/game/court', { waitUntil: 'domcontentloaded' });
    if (isMobile(info)) {
      await press(page.getByRole('list', { name: '조정 결정' }).getByRole('listitem').filter({ hasText: '내 부 사람 장수를' }).getByRole('button'), info);
    }
    await press(page.getByRole('button', { name: '새 발령' }), info);
    const sheet = page.getByRole('dialog', { name: '새 발령' });
    await press(sheet.getByRole('option', { name: /순욱/ }), info);
    await expect(sheet).toContainText(failure === 'denied' ? '직접 거느린 장수가 아닙니다.' : '현 후보를 불러오지 못했습니다');
    await expect(sheet).not.toContainText('이 묶음에 후보가 없습니다');
    await expect(sheet).not.toContainText('Service Unavailable');
    await expect(sheet.getByRole('listbox', { name: '발령할 현' })).toHaveCount(0);
    await expect(sheet.getByRole('button', { name: '이 현으로 발령' })).toHaveAttribute('aria-disabled', 'true');
    await expectNoHorizontalOverflow(page);
    expect(await titleOnlyInfo(page, '[role="dialog"]')).toEqual([]);
    if (isMobile(info)) expect(await smallTouchTargets(page, '[role="dialog"]')).toEqual([]);
    await press(sheet.getByRole('button', { name: '다시 시도' }), info);
    await expect(sheet.getByRole('option', { name: /순욱/ })).toHaveAttribute('aria-selected', 'true');
    await press(sheet.getByRole('option', { name: /양성현/ }), info);
    expect(attempts).toBe(2);
    const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().includes('/commands/court/dispatch'));
    await press(sheet.getByRole('button', { name: '이 현으로 발령' }), info);
    expect((await request).postDataJSON()).toEqual({ targetGeneralId: 21, countyId: 129 });
    await expect(page.getByText('발령을 접수했습니다 — 주공의 다음 개인 턴에 처리합니다.')).toBeVisible();
  });
}
