// Real backend only (no page.route, no synthetic API/SSE). US-WAND-10 through the current screens:
//   new USER → lobby 「장수 만들기」 → entry 「시작 방식 선택」 → CreateScreen → one creation POST → result CREATED
//   → front-info hasGeneral (the 202 is intake, never completion) → enlist → 「재야로 시작」 → war room, nationId 0.
// US-WAND-01/02 (lobby 「입장」, travel turn, reconnect) belong to tools/qa/localhost-user-journey.mjs; not repeated here.
// Stories: docs/qa/user-stories/재야.md. Env: e2e/support/live.ts.
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import type { CreationResult, GeneralCreationOptions, HistoricalCreationPage } from '../lib/creation-contract';
import type { FrontInfoResponse } from '../lib/types';
import { evidence, fillControlled, gameApi, gamePage, liveEnv, readJson, type LiveEnv } from './support/live';

type Account = { username: string; password: string };

const NOT_FOUND = '찾는 화면이 없습니다';
// Current NAV31 screens (lib/campaign-screens.ts); old /game/board · my · history · rankings/kingdoms are retired or 308.
const SURFACES = ['', 'retinue', 'retinue/people', 'territory', 'corps', 'stratagem', 'court', 'court/realm', 'council', 'mail', 'records', 'records/yearbook'];
const HANGUL = '가강건경고공관광구국군권규근기길남단담덕도동두라량려련령로룡류륜림마만명무문민박방백범보복봉부빈사산상서석선성세소송수순승시신안양언엄여연영오온완요용우운원위유윤을의이인자장재전정조종주준중지진찬창천철청초충치탁태택평포표풍하학한해허현형호홍화환황효후훈휘흥희';
const shared: { env?: LiveEnv; account?: Account; generalId?: number; creationRequestId?: string } = {};

test.describe.configure({ mode: 'serial' });

function newName(): string {
  return Array.from({ length: 4 }, () => HANGUL[Math.floor(Math.random() * HANGUL.length)]).join('');
}

const front = (env: LiveEnv, page: Page) => readJson<FrontInfoResponse>(page.request, gameApi(env, 'front-info'), 'front-info');

async function expectUserSession(env: LiveEnv, context: BrowserContext, page: Page): Promise<void> {
  const cookie = (await context.cookies(env.gatewayUrl)).find((c) => c.name === 'sam_access');
  expect(cookie?.httpOnly, 'sam_access must be httpOnly').toBe(true);
  expect(await page.locator('body').innerText(), 'access JWT must not be rendered').not.toContain(cookie!.value);
  const me = await readJson<{ user: { role: string } }>(page.request, `${env.gatewayUrl}/api/auth/me`, 'auth/me');
  expect(me.user.role).toBe('USER');
}

/** A normal login without `next` lands on the lobby. */
async function login(env: LiveEnv, page: Page, account: Account): Promise<void> {
  await page.goto(`${env.gatewayUrl}/login`, { waitUntil: 'domcontentloaded' });
  await fillControlled(page, [{ name: 'username', value: account.username }, { name: 'password', value: account.password }]);
  const response = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/auth/login' && r.request().method() === 'POST');
  await page.getByRole('button', { name: '로그인', exact: true }).click();
  expect((await response).status(), 'login').toBe(200);
  await page.waitForURL((url) => url.pathname === '/lobby');
}

/** US-WAND-10 ①: /join → logged in → lobby. With E2E_LIVE_USERNAME the account already exists and logs in instead. */
async function enterAsNewUser(env: LiveEnv, context: BrowserContext, page: Page): Promise<Account> {
  if (env.account) {
    await login(env, page, env.account);
    await expectUserSession(env, context, page);
    return env.account;
  }
  const suffix = `${Date.now().toString(36)}${Math.floor(Math.random() * 1e4)}`;
  const account = { username: `e2e_${suffix}`, password: `E2e!${suffix}a` };
  await page.goto(`${env.gatewayUrl}/join`, { waitUntil: 'domcontentloaded' });
  await fillControlled(page, [
    { name: 'username', value: account.username }, { name: 'password', value: account.password },
    { name: 'passwordConfirm', value: account.password }, { name: 'nickname', value: `e2e-${suffix}`.slice(0, 20) },
  ]);
  const registered = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/auth/register' && r.request().method() === 'POST');
  await page.getByRole('button', { name: '회원가입', exact: true }).click();
  expect((await registered).status(), 'signup').toBe(200);
  await page.waitForURL((url) => url.pathname === '/lobby');
  await expectUserSession(env, context, page);
  return account;
}

/** US-WAND-10 ②: the lobby 「서버」 region → this server's 「장수 만들기」 (a USER without a general here). */
async function createFromLobby(env: LiveEnv, page: Page): Promise<void> {
  const servers = page.getByRole('region', { name: '서버', exact: true });
  await expect(servers).toBeVisible({ timeout: 30_000 });
  const links = servers.getByRole('link', { name: '장수 만들기', exact: true });
  const link = env.serverId ? links.and(page.locator(`a[href$="/game/${env.serverId}"]`)) : links;
  await expect(link).toHaveCount(1, { timeout: 30_000 });
  await link.click();
  await page.waitForURL((url) => url.pathname === new URL(gamePage(env)).pathname);
}

/**
 * US-WAND-10 And: an unavailable historical person is refused 422 with the server sentence (real list, real POST, before any
 * general exists so ownership cannot mask the code). A fresh world may hold no such person; that precondition is reported
 * BLOCKED (annotation + evidence), never passed.
 */
async function expectHistoricalRefusal(env: LiveEnv, page: Page, worldId: number): Promise<'PASS' | 'BLOCKED'> {
  let cursor: string | null = null;
  let target: HistoricalCreationPage['people'][number] | undefined;
  for (let pageNo = 0; pageNo < 30 && !target; pageNo++) {
    const query: string = `generals/creation/historical?sort=ID_ASC&limit=100${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`;
    const list: HistoricalCreationPage = await readJson<HistoricalCreationPage>(page.request, gameApi(env, query), 'historical');
    target = list.people.find((p) => p.appeared && !p.available && p.unavailableCode === 'HISTORICAL_PERSON_UNAVAILABLE');
    cursor = list.nextCursor;
    if (!cursor) break;
  }
  if (!target) {
    test.info().annotations.push({ type: 'BLOCKED', description: 'US-WAND-10 historical 422: no appeared unavailable person in this world' });
    return 'BLOCKED';
  }
  const response = await page.request.post(gameApi(env, 'generals/creation'), {
    data: { expectedWorldId: worldId, clientRequestId: crypto.randomUUID(), choice: { kind: 'HISTORICAL', historicalGeneralId: target.historicalGeneralId } },
  });
  expect(response.status()).toBe(422);
  expect((await response.json()).error).toEqual({ code: 'HISTORICAL_PERSON_UNAVAILABLE', message: '이 인물은 지금 선택할 수 없습니다. 목록을 다시 읽어 주세요.' });
  return 'PASS';
}

/** US-WAND-10 ③④: entry → CreateScreen (desktop layout) → one POST → CREATED → session shows the general → enlist. */
async function createGeneral(env: LiveEnv, page: Page): Promise<{ generalId: number; requestId: string }> {
  const entry = page.getByTestId('game-entry-screen');
  await expect(entry.getByRole('heading', { name: '시작 방식 선택' })).toBeVisible({ timeout: 60_000 });
  const options = await readJson<GeneralCreationOptions>(page.request, gameApi(env, 'generals/creation/options'), 'creation options');
  const historicalRefusal = await expectHistoricalRefusal(env, page, options.worldId);
  const names = options.nativeCounties.map((c) => c.name);
  const county = options.nativeCounties.find((c) => c.available && names.indexOf(c.name) === names.lastIndexOf(c.name));
  expect(county, 'an available native county').toBeTruthy();
  const posts: unknown[] = [];
  page.on('request', (r) => { if (r.method() === 'POST' && new URL(r.url()).pathname === '/api/game/api/generals/creation') posts.push(r.postDataJSON()); });

  await entry.getByRole('link', { name: '생성 화면 보기' }).click();
  await expect(page.getByRole('listbox', { name: '시작할 역할' }).getByRole('option').first()).toHaveAttribute('aria-selected', 'true', { timeout: 60_000 });
  await page.getByPlaceholder('현 이름').fill(county!.name);
  await page.getByRole('listbox', { name: '본관 현 후보' }).getByRole('option', { name: new RegExp(county!.name) }).first().click();
  const name = newName();
  await page.getByRole('textbox', { name: '이름' }).fill(name);
  await page.getByRole('group', { name: '주의' }).getByRole('button', { name: options.ideologies[0].label, exact: true }).click();
  await page.getByRole('group', { name: '개성' }).getByRole('button', { name: options.traits[0].label, exact: true }).click();
  await expect(page.getByRole('region', { name: '미리보기' })).toContainText(`향당 · ${county!.name}`);
  const accepted = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/game/api/generals/creation' && r.request().method() === 'POST');
  await page.getByRole('button', { name: '만들고 섬길 주공 고르기' }).click();
  const intake = await accepted;
  expect(intake.status()).toBe(202);
  const { requestId } = await intake.json() as { requestId: string };
  // 202 is intake only: wait for the creation terminal, then for the session itself to own the general.
  let result: CreationResult | null = null;
  await expect.poll(async () => {
    result = await readJson<CreationResult>(page.request, gameApi(env, `generals/creation/${encodeURIComponent(requestId)}`), 'creation result');
    return result.status;
  }, { timeout: 120_000, intervals: [1000] }).toBe('CREATED');
  await expect.poll(async () => (await front(env, page)).general.hasGeneral, { timeout: 60_000 }).toBe(true);
  const info = await front(env, page);
  expect(info.general.generalId).toBe(result!.generalId);
  expect(info.general.nationId).toBe(0);
  expect(posts).toHaveLength(1);
  expect(posts[0]).toMatchObject({ choice: { kind: 'CUSTOM', name, nativeCountyId: county!.cityId, role: 'RETAINER' } });
  await expect(page.getByTestId('enlist-screen')).toBeVisible({ timeout: 60_000 });
  evidence(env, 'us-wand-10', { creationRequestId: requestId, creationStatus: 'CREATED', generalId: info.general.generalId, nationId: 0, creationPosts: posts.length, historicalRefusal });
  return { generalId: info.general.generalId!, requestId };
}

async function expectWarRoom(env: LiveEnv, page: Page, generalId: number): Promise<void> {
  await expect(page.getByTestId('war-room-layout')).toBeVisible({ timeout: 60_000 });
  await expect(page.getByTestId('game-entry-screen')).toHaveCount(0);
  const info = await front(env, page);
  expect(info.general.hasGeneral).toBe(true);
  expect(info.general.generalId).toBe(generalId);
  expect(info.general.nationId).toBe(0);
}

test('US-WAND-10: a new USER creates a wanderer through the current screens and opens the war room', async ({ browser }) => {
  const env = liveEnv();
  test.setTimeout(600_000);
  shared.env = env;
  const context = await browser.newContext({ serviceWorkers: 'block' });
  try {
    const page = await context.newPage();
    shared.account = await enterAsNewUser(env, context, page);
    expect((await front(env, page)).general.hasGeneral, 'Given: the new USER owns no general').toBe(false);
    await createFromLobby(env, page);
    const { generalId, requestId } = await createGeneral(env, page);
    shared.generalId = generalId;
    shared.creationRequestId = requestId;
    // US-WAND-10 ⑤ Then: 「재야로 시작」 → war room with hasGeneral and nationId 0, and it survives a reload.
    await page.getByRole('link', { name: '재야로 시작', exact: true }).click();
    await expectWarRoom(env, page, generalId);
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expectWarRoom(env, page, generalId);

    // Unknown inputs are refused, never accepted as a reservation.
    const rejected = await page.request.post(gameApi(env, `command/__e2e_invalid__?generalId=${generalId}&turnIdx=0`), { data: {} });
    expect(rejected.status() >= 400 || (await rejected.json()).status === 'BLOCKED').toBe(true);

    // Current screens render for the new wanderer (the game shell's notFound() is HTTP 200, so read the body too).
    for (const slug of SURFACES) {
      const response = await page.goto(gamePage(env, slug), { waitUntil: 'domcontentloaded' });
      expect(response?.status() ?? 0, `/${slug} status`).toBeLessThan(400);
      await expect(page.getByTestId('game-entry-screen'), `/${slug} is not the entry`).toHaveCount(0);
      await expect(page.getByText(NOT_FOUND), `/${slug} is a real screen`).toHaveCount(0);
    }
  } finally {
    await context.close();
  }
});

// Only where the caller owns a repo docker-compose project (tools/e2e/local_v1_gate.sh). The harness stack restarts are its own runner's.
test('engine restart keeps the created general and its creation terminal', async ({ browser }) => {
  const project = process.env.E2E_COMPOSE_PROJECT_NAME;
  test.skip(!project, 'E2E_COMPOSE_PROJECT_NAME not set: restart durability is NOT_RUN');
  const { env, account, generalId, creationRequestId } = shared;
  expect(env && account && generalId && creationRequestId, 'creation test must pass first').toBeTruthy();
  expect(project).toMatch(/^[a-z0-9][a-z0-9_-]*$/);
  const healthUrl = process.env.E2E_GAME_ENGINE_HEALTH_URL;
  expect(healthUrl, 'E2E_GAME_ENGINE_HEALTH_URL').toBeTruthy();
  test.setTimeout(420_000);
  const context = await browser.newContext({ serviceWorkers: 'block' });
  try {
    const page = await context.newPage();
    await login(env!, page, account!);
    execFileSync('docker', ['compose', '--project-name', project!, '--env-file', '/dev/null', 'restart', 'game-engine'],
      { cwd: process.env.E2E_REPO_ROOT ?? join(process.cwd(), '../..'), stdio: 'pipe', timeout: 180_000 });
    await expect.poll(async () => (await fetch(healthUrl!).catch(() => null))?.status ?? 0, { timeout: 120_000 }).toBe(200);
    const info = await front(env!, page);
    expect([info.general.generalId, info.general.nationId]).toEqual([generalId, 0]);
    const result = await readJson<CreationResult>(page.request, gameApi(env!, `generals/creation/${encodeURIComponent(creationRequestId!)}`), 'creation result');
    expect(result).toMatchObject({ status: 'CREATED', generalId });
  } finally {
    await context.close();
  }
});
