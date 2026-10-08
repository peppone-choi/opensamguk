// CI runs the serverless game app. Scoped server links are covered by unit tests; real cross-app USER QA remains separate.
import { expect, test, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, press, smallTouchTargets } from '../support/parity';
import { frontInfo } from '../support/campaignFixtures';
import { HISTORICAL_PAGE_1, OPTIONS } from '../../__tests__/fixtures/creation';
import { serveHelpApi } from './help-api';

type Reply = { status: number; body: unknown };

async function serve(page: Page, hasGeneral = false) {
  const info = frontInfo();
  info.general.hasGeneral = hasGeneral;
  if (!hasGeneral) { info.general.generalId = 0; info.general.name = ''; }
  const control = {
    front: { status: 200, body: info } as Reply,
    options: { status: 200, body: { ...OPTIONS, playerCap: { used: 0, max: 50 } } } as Reply,
    reads: [] as URL[],
    creationPosts: [] as unknown[],
  };
  await page.route('**/api/auth/me', (r) => r.fulfill({
    json: { user: { id: 1, username: 'entry-fixture', nickname: '장수', role: 'USER' } },
  }));
  await page.route('**/api/server-basic-info/**', (r) => r.fulfill({ status: 404, json: {} }));
  await page.route('**/api/game/**', (r) => {
    const url = new URL(r.request().url());
    const path = url.pathname.replace(/^\/api\/game/, '');
    if (path === '/api/generals/creation' && r.request().method() === 'POST') {
      control.creationPosts.push(r.request().postDataJSON());
      return r.fulfill({ status: 503, json: {} });
    }
    if (path === '/api/front-info' || path === '/api/generals/creation/options') {
      control.reads.push(url);
      const reply = path === '/api/front-info' ? control.front : control.options;
      return r.fulfill({ status: reply.status, json: reply.body });
    }
    if (path === '/api/generals/creation/historical') return r.fulfill({ json: HISTORICAL_PAGE_1 });
    return r.fulfill({ status: 503, json: {} });
  });
  await serveHelpApi(page, { onlyHelp: true });
  return control;
}

const choice = (page: Page) => page.getByRole('region', { name: '시작 방식 선택' });

async function expectChoice(page: Page) {
  await expect(choice(page)).toBeVisible();
  await expect(choice(page).getByRole('heading', { name: '시작 방식 선택' })).toHaveCount(1);
  await expect(choice(page).getByRole('link')).toHaveCount(2);
  await expect(choice(page).getByLabel('서버 요약')).toHaveCount(0);
  await expect(choice(page).getByText(/입구 지도|입구 세력 목록|이 서버에서 시작한다/)).toHaveCount(0);
  await expect(page.getByRole('navigation', { name: '게임 메뉴' })).toHaveCount(0);
}

test('entry single flow: direct URL, reload and both form back paths need no second lobby', { tag: BOTH }, async ({ page }, info) => {
  const api = await serve(page);
  await page.goto('/game', { waitUntil: 'domcontentloaded' });
  await expectChoice(page);
  await page.reload({ waitUntil: 'domcontentloaded' });
  await expectChoice(page);
  await page.evaluate(() => document.fonts.ready);
  await info.attach('entry-single-choice', { body: await choice(page).screenshot(), contentType: 'image/png' });
  const viewport = page.viewportSize();
  for (const width of [320, 375, 414, 768]) {
    await page.setViewportSize({ width, height: 844 });
    await expectNoHorizontalOverflow(page);
    expect(await smallTouchTargets(page, '[data-testid="game-entry-screen"]')).toEqual([]);
  }
  if (viewport) await page.setViewportSize(viewport);
  await press(choice(page).getByRole('link', { name: '생성 화면 보기' }), info);
  await expect(page).toHaveURL(/\/game\/create$/);
  await expect(page.getByRole('listbox', { name: '시작할 역할' })).toBeVisible();
  await page.goBack({ waitUntil: 'domcontentloaded' });
  await expectChoice(page);
  await press(choice(page).getByRole('link', { name: '역사 인물 화면 보기' }), info);
  await expect(page).toHaveURL(/\/game\/create\/historical$/);
  await expect(page.getByRole('heading', { name: '역사 인물 고르기' })).toBeVisible();
  await page.goBack({ waitUntil: 'domcontentloaded' });
  await expectChoice(page);
  expect(api.creationPosts).toEqual([]);
  expect(api.reads.some((u) => u.pathname.endsWith('/creation/options'))).toBe(true);
});

test('entry single flow: a general opens the war room without a creation choice', { tag: BOTH }, async ({ page }) => {
  const api = await serve(page, true);
  await page.goto('/game', { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('war-room-layout')).toBeVisible();
  await expect(page.getByTestId('game-entry-screen')).toHaveCount(0);
  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('war-room-layout')).toBeVisible();
  expect(api.reads.filter((u) => u.pathname.endsWith('/creation/options'))).toEqual([]);
  expect(api.creationPosts).toEqual([]);
});

test('entry single flow: a permission read failure retries without assuming no general', { tag: BOTH }, async ({ page }, info) => {
  const api = await serve(page);
  const ready = api.front;
  api.front = { status: 403, body: { error: { code: 'PERMISSION_DENIED', message: '조회 권한 없음' } } };
  await page.goto('/game', { waitUntil: 'domcontentloaded' });
  const error = page.getByRole('alert').filter({ hasText: '장수 정보를 불러오지 못했습니다.' });
  await expect(error).toBeVisible();
  await expect(choice(page)).toHaveCount(0);
  expect(api.reads.filter((u) => u.pathname.endsWith('/creation/options'))).toEqual([]);
  api.front = ready;
  await press(error.getByRole('button', { name: '다시 시도' }), info);
  await expectChoice(page);
  expect(api.creationPosts).toEqual([]);
});

for (const [status, code, title] of [
  [403, 'SERVER_NOT_PUBLIC', '이 서버는 지금 공개되지 않았습니다'],
  [503, 'SERVER_ADMISSION_UNAVAILABLE', '서버 공개 상태를 확인하지 못했습니다'],
] as const) {
  test(`entry single flow: admission ${status} stays outside creation`, { tag: BOTH }, async ({ page }) => {
    const api = await serve(page);
    api.front = { status, body: { error: { code, message: title } } };
    await page.goto('/game', { waitUntil: 'domcontentloaded' });
    await expect(page.getByText(title, { exact: true })).toBeVisible();
    await expect(choice(page)).toHaveCount(0);
    expect(api.reads.filter((u) => u.pathname.endsWith('/creation/options'))).toEqual([]);
    expect(api.creationPosts).toEqual([]);
  });
}

test('entry single flow: options failure, individual policy, full and closed remain distinct', { tag: BOTH }, async ({ page }, info) => {
  const api = await serve(page);
  api.options = { status: 500, body: {} };
  await page.goto('/game', { waitUntil: 'domcontentloaded' });
  await expect(choice(page).getByText('생성 조건을 불러오지 못했습니다')).toBeVisible();
  await expect(choice(page).getByText('내 장수를 만들 수 있습니다.')).toHaveCount(0);
  api.options = { status: 200, body: { ...OPTIONS, policy: { customAllowed: false, historicalAllowed: true, reason: 'ROLE_UNAVAILABLE' } } };
  await press(choice(page).getByRole('button', { name: '다시 시도' }), info);
  await expect(choice(page).getByText('이 시작 역할은 현재 세계에서 선택할 수 없습니다.')).toBeVisible();
  await expect(choice(page).getByText('역사 인물로 시작할 수 있습니다.')).toBeVisible();
  api.options = { status: 200, body: { ...OPTIONS, playerCap: { used: 50, max: 50 } } };
  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(choice(page).getByText('사람 장수 자리가 다 찼습니다.')).toBeVisible();
  await expect(choice(page).getByText('내 장수를 만들 수 있습니다.')).toHaveCount(0);
  api.options = { status: 503, body: { error: { code: 'CREATION_POLICY_UNAVAILABLE', message: '지금은 생성할 수 없습니다.' } } };
  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(choice(page).getByText('지금은 생성할 수 없습니다.', { exact: true })).toBeVisible();
  await expect(choice(page).getByText('역사 인물로 시작할 수 있습니다.')).toHaveCount(0);
  expect(api.creationPosts).toEqual([]);
});

test('entry single flow: unauthenticated entry retains the selected return URL', { tag: BOTH }, async ({ page }) => {
  const api = await serve(page);
  await page.route('**/api/auth/me', (r) => r.fulfill({ status: 401, json: {} }));
  await page.route((url) => url.pathname === '/login', (r) => r.fulfill({
    contentType: 'text/html', body: '<main>로그인 경로 확인</main>',
  }));
  await page.goto('/game', { waitUntil: 'domcontentloaded' });
  await expect(page.getByText('로그인 경로 확인')).toBeVisible();
  expect(new URL(page.url()).searchParams.get('next')).toBe('/game');
  expect(api.reads).toEqual([]);
  expect(api.creationPosts).toEqual([]);
});
