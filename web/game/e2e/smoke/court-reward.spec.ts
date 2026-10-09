// 조정 상사 칸 스모크 — 서버 상사 선택지(`GET /api/court/reward-options`)를 page.route 로 대 주고 데스크톱 · 모바일 같은 흐름(@both).
// 서버 카드 · 규칙 · 창고 금(알려진 0 · 확인할 수 없음 · 2^53 넘는 값) · 스냅샷 추정치 · 확인하지 않은 네 가지 · 정규화 금액 접수 ·
// 읽기 실패 · READY 아님 · 다시 시도. 화면 규칙(44 · title 전용 · 넘침 · 서버 원문 0)도 본다.
import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { card, closedBody, fakeRewardServer, network, noFunding, unavailableFunding } from '../../__tests__/fixtures/court-reward';
import { frontInfo, serveCampaign } from '../support/campaignFixtures';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const CARDS = [
  card(31, 60, { name: '문관', funding: network('9007199254740993123', 2) }),
  card(32, 40, { funding: unavailableFunding('RECIPIENT_MISSING') }),
  card(33, 95, { name: '무관', funding: unavailableFunding('WAREHOUSE_MALFORMED') }),
  card(34, 40, { name: '사관', funding: noFunding('LOCATION_FOREIGN') }),
];
const table = {
  '/api/front-info': frontInfo(),
  '/api/commands/dispatches': { result: true, dispatches: [] },
  '/api/commands/political-consent-options': [],
  '/api/commands/dispatch-options': { result: false, code: 'NOT_LORD', reason: '발령은 주공만 할 수 있습니다.', targets: [], counties: [] },
  '/api/commands/legacy-court-options': { inputId: 'court.moveCapital', available: false, reason: '군주만 할 수 있습니다.', choices: [] },
  // 부 인물 카드의 이름(「되살리면 안 됨」)은 서버가 이름을 주지 않은 카드에 쓰지 않는다.
  '/api/retinue': { status: 'READY', renown: 30, costSum: 0, overCapacity: false, units: [], people: [
    { retainerId: 32, generalId: 132, name: '되살리면 안 됨', picture: null, imageServer: 0, loyalty: 40 },
  ] },
  '/api/map/preview': { mapCode: 'x', width: 1, height: 1, cities: [{ id: 3, name: '허현' }], nations: [] },
  '/api/court/reward-options': fakeRewardServer({ cards: CARDS }),
  '/api/commands/court/reward': { status: 'AVAILABLE' },
};

async function openReward(page: Page, info: TestInfo) {
  await page.goto('/game/court', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 2, name: '조정' })).toBeVisible({ timeout: 60_000 });
  if (isMobile(info)) {
    await press(page.getByRole('list', { name: '조정 결정' }).getByRole('listitem').filter({ hasText: '직속 인물에게' }).getByRole('button'), info);
  }
  return page.getByRole('region', { name: '상사' });
}

const layoutRoot = (info: TestInfo) => (isMobile(info) ? '[role="dialog"]' : 'main');

test('서버 카드 · 창고 금 · 스냅샷 추정치 — 막지 않는 경고와 정규화 금액 접수', { tag: [BOTH] }, async ({ page }, info) => {
  await serveCampaign(page, table);
  const reads: string[] = [];
  page.on('request', (r) => { if (r.url().includes('/court/reward-options')) reads.push(new URL(r.url()).search); });
  const reward = await openReward(page, info);
  await expect(reward).toContainText('금 100당 충성 +1 · 한 번에 최대 +10 · 충성은 100까지');
  await expect(reward).toContainText('200년 3월 중순에 저장된 값으로 낸 추정치입니다 — 접수나 지급이 확정된 것이 아닙니다.');
  const list = reward.getByRole('listbox', { name: '상사할 인물' });
  await expect(list.getByRole('option')).toHaveCount(4);
  await expect(list.getByRole('option', { name: /이름 모를 인물/ })).toBeVisible();
  await expect(list).not.toContainText('되살리면 안 됨');

  // 알려진 큰 값 — 문자열 그대로 자릿수만 묶는다.
  await press(list.getByRole('option', { name: /문관/ }), info);
  await expect(reward.locator('[data-reward-usable="known"]')).toContainText('금 9,007,199,254,740,993,123');
  await reward.getByRole('textbox', { name: '상사 금액' }).fill('0150');
  const preview = reward.getByRole('status', { name: '상사 미리 보기' });
  await expect(preview).toContainText('충성 +1 — 충성 없이 나가는 금 50');
  await expect(preview).toContainText('조회 시점 창고로 지급 가능 · 실행 때 다시 확인');
  await expect(preview).toContainText('접수 가능 여부 · 상사 이력 · 동시 차감 · 조회 이후 상태');
  expect(reads.some((s) => s.includes('money=150'))).toBe(true);
  expect(reads.some((s) => s.includes('money=0'))).toBe(false);

  // 확인할 수 없음 — 0 으로 보이지 않고 접수는 막지 않는다.
  await press(list.getByRole('option', { name: /무관/ }), info);
  await expect(reward.locator('[data-reward-usable="unavailable"]')).toContainText('확인할 수 없음');
  await expect(reward.locator('[data-reward-usable="unavailable"]')).not.toContainText('금 0');
  await expect(preview).toContainText('창고 금을 확인할 수 없습니다 — 접수는 할 수 있고, 실행 때 다시 확인합니다.');
  await expect(reward.getByRole('button', { name: '상사 — 접수' })).not.toHaveAttribute('aria-disabled', 'true');

  // 알려진 0 — 모자라 보여도 막지 않는다.
  await press(list.getByRole('option', { name: /사관/ }), info);
  await expect(reward.locator('[data-reward-usable="known"]')).toContainText('금 0');
  await expect(preview).toContainText('저장된 창고 금 0으로는 모자라 보입니다');
  await expect(reward.getByRole('button', { name: '상사 — 접수' })).not.toHaveAttribute('aria-disabled', 'true');

  // 서버 상한 — 넘으면 사유로 막는다.
  await reward.getByRole('textbox', { name: '상사 금액' }).fill('5000');
  await expect(reward).toContainText('이번에 충성을 올릴 수 있는 금은 최대 1,000입니다.');
  await expect(reward.getByRole('button', { name: /상사 — 접수/ })).toHaveAttribute('aria-disabled', 'true');

  await expectNoHorizontalOverflow(page);
  expect(await titleOnlyInfo(page, layoutRoot(info))).toEqual([]);
  expect(await smallTouchTargets(page, layoutRoot(info))).toEqual([]);
  expect(await reward.innerText()).not.toMatch(/[A-Za-z]{3,}/);

  await press(list.getByRole('option', { name: /문관/ }), info);
  await reward.getByRole('textbox', { name: '상사 금액' }).fill(' 0150 ');
  const submit = reward.getByRole('button', { name: '상사 — 접수' });
  await expect(submit).not.toHaveAttribute('aria-disabled', 'true');
  const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().includes('/commands/court/reward'));
  await press(submit, info);
  expect((await request).postDataJSON()).toEqual({ retainerId: 31, money: 150 });
  // 접수되면 다른 접수처럼 모바일 시트가 닫히고 알림은 화면 위에 남는다(데스크톱은 상사 칸 안에 한 번만).
  await expect(page.getByText('상사를 접수했습니다 — 다음 개인 턴에 처리합니다.')).toHaveCount(1);
  await expect(page.getByText('상사를 접수했습니다 — 다음 개인 턴에 처리합니다.')).toBeVisible();
  if (isMobile(info)) await expect(page.getByRole('dialog')).toHaveCount(0);
});

test('읽기 실패 · 다시 시도 — 오류 번호만, 원문 없이 한국어 안내', { tag: [BOTH] }, async ({ page }, info) => {
  await serveCampaign(page, table);
  let attempts = 0;
  await page.route((url) => url.pathname === '/api/game/api/court/reward-options', async (route) => {
    attempts += 1;
    if (attempts === 1) await route.fulfill({ status: 503, json: { error: { code: 'SOURCE_UNAVAILABLE', message: 'Service Unavailable' } } });
    else await route.fulfill({ json: fakeRewardServer({ cards: CARDS })(new URL(route.request().url())) });
  });
  const reward = await openReward(page, info);
  await expect(reward).toContainText('상사 선택지를 불러오지 못했습니다');
  await expect(reward.getByRole('button', { name: '오류 번호 503 복사' })).toBeVisible();
  await expect(reward).not.toContainText('Service Unavailable');
  await expect(reward.getByRole('listbox')).toHaveCount(0);
  await expect(reward).not.toContainText('상사할 직속 인물 카드가 없습니다.');
  expect(await smallTouchTargets(page, layoutRoot(info))).toEqual([]);
  await press(reward.getByRole('button', { name: '다시 시도' }), info);
  await expect(reward.getByRole('listbox', { name: '상사할 인물' }).getByRole('option')).toHaveCount(4);
});

for (const [status, text] of [
  ['WRONG_RULE_PROFILE', '이 서버는 지금 게임 규칙과 맞지 않습니다.'],
  ['UNAVAILABLE', '저장된 값을 읽을 수 없습니다.'],
] as const) {
  test(`READY 아님 ${status} — 빈 목록이 아니라 서버 상태 한 줄`, { tag: [BOTH] }, async ({ page }, info) => {
    await serveCampaign(page, { ...table, '/api/court/reward-options': closedBody(7, status, status === 'UNAVAILABLE' ? 'WORLD_UNAVAILABLE' : status) });
    const reward = await openReward(page, info);
    await expect(reward).toContainText(text);
    await expect(reward.getByRole('listbox')).toHaveCount(0);
    await expect(reward).not.toContainText('상사할 직속 인물 카드가 없습니다.');
    await expectNoHorizontalOverflow(page);
  });
}
