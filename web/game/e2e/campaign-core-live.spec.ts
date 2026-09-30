import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type APIRequestContext, type APIResponse, type BrowserContext, type Locator, type Page, type Response } from '@playwright/test';

type Json = Record<string, unknown> | unknown[] | string | number | boolean | null;
type ResponseEvidence = { url: string; status: number; body: string };

const gatewayUrl = process.env.E2E_GATEWAY_URL ?? 'http://localhost:3000';
const gameUrl = process.env.E2E_GAME_URL ?? 'http://localhost:3001';
const engineHealthUrl = process.env.E2E_GAME_ENGINE_HEALTH_URL ?? 'http://localhost:8082/actuator/health';
const artifactRoot = process.env.E2E_ARTIFACT_DIR ?? join(process.cwd(), 'test-results');
const repoRoot = process.env.E2E_REPO_ROOT ?? join(process.cwd(), '../..');

function composeProjectNameFromEnv(): string {
  const value = process.env.E2E_COMPOSE_PROJECT_NAME;
  if (!value || !/^[a-z0-9][a-z0-9_-]*$/.test(value)) {
    throw new Error('E2E_COMPOSE_PROJECT_NAME must be a Docker-safe Compose project name');
  }
  return value;
}

const composeProjectName = composeProjectNameFromEnv();

function safeName(value: string): string {
  return value.replace(/[^a-zA-Z0-9._-]+/g, '-').replace(/^-+|-+$/g, '');
}

function json(value: unknown): string {
  return JSON.stringify(value, null, 2) ?? 'null';
}

function parseJson(text: string): Json {
  try {
    return JSON.parse(text) as Json;
  } catch {
    return text;
  }
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

function writeArtifact(name: string, value: unknown): string {
  mkdirSync(artifactRoot, { recursive: true });
  const path = join(artifactRoot, safeName(name));
  writeFileSync(path, typeof value === 'string' ? value : json(value), 'utf8');
  return path;
}

type ResponseLike = Pick<Response, 'status' | 'text'> | Pick<APIResponse, 'status' | 'text'>;

async function responseBody(response: ResponseLike): Promise<{ status: number; body: string; data: Json }> {
  const body = await response.text().catch(() => '');
  return { status: response.status(), body, data: parseJson(body) };
}

async function statusOrUnavailable(url: string): Promise<number> {
  try {
    return (await fetch(url)).status;
  } catch {
    return 0;
  }
}

async function apiGet(request: APIRequestContext, path: string): Promise<{ response: APIResponse; data: Json; body: string }> {
  const response = await request.get(`${gameUrl}${path}`);
  const result = await responseBody(response);
  return { response, data: result.data, body: result.body };
}

async function apiPost(
  request: APIRequestContext,
  path: string,
  payload: unknown,
): Promise<{ response: APIResponse; data: Json; body: string }> {
  const response = await request.post(`${gameUrl}${path}`, { data: payload });
  const result = await responseBody(response);
  return { response, data: result.data, body: result.body };
}

function requestIdOf(value: Json): string | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const id = (value as Record<string, unknown>).requestId;
  return typeof id === 'string' && id.length > 0 ? id : null;
}

async function pollCommandResult(
  request: APIRequestContext,
  requestId: string,
  timeoutMs = Number(process.env.E2E_COMMAND_TIMEOUT_MS ?? 30_000),
): Promise<{ status: number; body: string; data: Json }> {
  const deadline = Date.now() + timeoutMs;
  let latest = { status: 0, body: '', data: null as Json };
  while (Date.now() < deadline) {
    const result = await apiGet(request, `/api/game/api/command/result/${encodeURIComponent(requestId)}`);
    latest = { status: result.response.status(), body: result.body, data: result.data };
    if (result.data && typeof result.data === 'object' && !Array.isArray(result.data)) {
      if ((result.data as Record<string, unknown>).status === 'RESOLVED') return latest;
    }
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
  return latest;
}

async function captureSurface(page: Page, route: string, id: string): Promise<void> {
  const response = await page.goto(`${gameUrl}${route}`, { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => undefined);
  const status = response?.status() ?? 0;
  const bodyText = await page.locator('body').innerText().catch(() => '');
  const html = await page.content();
  writeArtifact(`${id}.dom.txt`, `${page.url()}\nHTTP ${status}\n\n${bodyText}`);
  writeArtifact(`${id}.dom.html`, html);
  expect(status, `${route} document status`).toBeGreaterThanOrEqual(200);
  expect(status, `${route} document status`).toBeLessThan(500);
}

async function attachResponseCapture(page: Page): Promise<{ all: ResponseEvidence[]; stop: () => void }> {
  const all: ResponseEvidence[] = [];
  const listener = async (response: Response) => {
    if (!response.url().includes('/api/')) return;
    all.push({ url: response.url(), status: response.status(), body: await response.text().catch(() => '') });
  };
  page.on('response', listener);
  return { all, stop: () => page.off('response', listener) };
}

type ControlledAuthField = {
  name: string;
  value: string;
};

async function fillControlledAuthForm(
  page: Page,
  stage: 'auth-register' | 'auth-login',
  fields: ControlledAuthField[],
): Promise<void> {
  const inputs: Array<{ field: ControlledAuthField; locator: Locator }> = fields.map((field) => ({
    field,
    locator: page.locator(`input[name="${field.name}"]`),
  }));

  try {
    await page.waitForLoadState('networkidle', { timeout: 15_000 });
    for (const { locator } of inputs) {
      await expect(locator).toBeVisible({ timeout: 15_000 });
      await expect(locator).toBeEditable({ timeout: 15_000 });
    }
  } catch (error) {
    writeArtifact(`${stage}-form-ready.json`, {
      networkIdleSettled: false,
      fields: fields.map(({ name }) => name),
      error: error instanceof Error ? error.message.split('\n')[0] : String(error),
    });
    throw error;
  }

  writeArtifact(`${stage}-form-ready.json`, {
    networkIdleSettled: true,
    fields: fields.map(({ name }) => name),
  });

  const fillAll = async (): Promise<void> => {
    for (const { field, locator } of inputs) await locator.fill(field.value);
  };
  const valueMatches = async (): Promise<Record<string, boolean>> => Object.fromEntries(
    await Promise.all(inputs.map(async ({ field, locator }) => [field.name, (await locator.inputValue()) === field.value])),
  );

  await fillAll();
  await sleep(350);
  let matches = await valueMatches();
  let refilledAfterHydration = false;
  if (Object.values(matches).some((matched) => !matched)) {
    refilledAfterHydration = true;
    await sleep(350);
    await fillAll();
    await sleep(350);
    matches = await valueMatches();
  }
  writeArtifact(`${stage}-form-fill.json`, {
    refilledAfterHydration,
    valueMatches: matches,
  });

  for (const { field, locator } of inputs) {
    await expect(locator).toHaveValue(field.value, { timeout: 1_000 });
  }
}

async function createAndLogin(context: BrowserContext): Promise<{ page: Page; username: string; password: string }> {
  const page = await context.newPage();
  const suffix = `${Date.now()}${Math.floor(Math.random() * 10_000)}`;
  const username = `e2e_${suffix}`;
  const password = `E2e!${suffix}a`;

  await page.goto(`${gatewayUrl}/join`, { waitUntil: 'domcontentloaded' });
  await fillControlledAuthForm(page, 'auth-register', [
    { name: 'username', value: username },
    { name: 'password', value: password },
    { name: 'passwordConfirm', value: password },
    // 닉네임은 gateway-api 규칙(2~20자)에 맞춘다 — Date.now()+난수 접미가 21자를 만들면 가입이 거부되거나(구 폼) 잘린다(신 폼 maxLength).
    { name: 'nickname', value: `e2e-${suffix}`.slice(0, 20) },
  ]);
  const registerResponsePromise = page.waitForResponse((r) => r.url().includes('/api/auth/register'), { timeout: 30_000 });
  await page.getByRole('button', { name: /가입/ }).click();
  const registerResponse = await registerResponsePromise;
  const registration = await responseBody(registerResponse);
  writeArtifact('auth-register.json', { status: registration.status, body: registration.data });
  expect(registration.status, 'actual signup response').toBe(200);
  expect((await context.cookies()).some((cookie) => cookie.name === 'sam_access' && cookie.httpOnly), 'signup httpOnly access cookie').toBeTruthy();

  await page.request.post(`${gatewayUrl}/api/auth/logout`);
  await page.goto(`${gatewayUrl}/login`, { waitUntil: 'domcontentloaded' });
  await fillControlledAuthForm(page, 'auth-login', [
    { name: 'username', value: username },
    { name: 'password', value: password },
  ]);
  const loginResponsePromise = page.waitForResponse((r) => r.url().includes('/api/auth/login'), { timeout: 30_000 });
  await page.getByRole('button', { name: '로그인' }).click();
  const loginResponse = await loginResponsePromise;
  const login = await responseBody(loginResponse);
  writeArtifact('auth-login.json', { status: login.status, body: login.data });
  expect(login.status, 'actual login response').toBe(200);
  const accessCookie = (await context.cookies()).find((cookie) => cookie.name === 'sam_access');
  expect(accessCookie?.httpOnly, 'login sam_access cookie must be httpOnly').toBeTruthy();
  expect(await page.locator('body').innerText(), 'JWT must not be rendered into DOM').not.toContain(accessCookie?.value ?? '__missing_cookie__');
  return { page, username, password };
}

test('campaign core live surfaces and durable engine restart', async ({ browser }, testInfo) => {
  const context = await browser.newContext();
  const auth = await createAndLogin(context);
  const page = auth.page;
  const capture = await attachResponseCapture(page);
  const state: Record<string, unknown> = { username: auth.username, composeProjectName };
  try {
    const frontBefore = await apiGet(page.request, '/api/game/api/front-info');
    writeArtifact('front-info-before.json', { status: frontBefore.response.status(), body: frontBefore.data });
    expect(frontBefore.response.status()).toBe(200);
    let front = frontBefore.data as Record<string, unknown>;
    const generalBefore = (front.general ?? {}) as Record<string, unknown>;

    if (generalBefore.hasGeneral === false) {
      await page.goto(`${gameUrl}/game/join`, { waitUntil: 'domcontentloaded' });
      await page.locator('form input[type="text"]').first().fill(`장수${Date.now()}`);
      page.on('dialog', (dialog) => void dialog.accept());
      const joinResponsePromise = page.waitForResponse((r) => (
        r.request().method() === 'POST' && r.url().includes('/api/game/api/join')
      ));
      await page.getByRole('button', { name: '장수 생성' }).click();
      const joinResponse = await joinResponsePromise;
      const join = await responseBody(joinResponse);
      writeArtifact('scenario-join.json', { status: join.status, body: join.data });
      expect(join.status).toBeGreaterThanOrEqual(200);
      expect(join.status).toBeLessThan(300);
      const joinRequestId = requestIdOf(join.data);
      expect(joinRequestId, 'join command requestId').toBeTruthy();
      const terminal = await pollCommandResult(page.request, joinRequestId as string);
      writeArtifact('scenario-join-terminal.json', { requestId: joinRequestId, ...terminal, data: terminal.data });
      expect(terminal.data).toMatchObject({ status: 'RESOLVED', ok: true });
      await expect.poll(async () => {
        const current = await apiGet(page.request, '/api/game/api/front-info');
        return ((current.data as Record<string, unknown>).general as Record<string, unknown> | undefined)?.hasGeneral;
      }, { timeout: 30_000 }).toBe(true);
      front = (await apiGet(page.request, '/api/game/api/front-info')).data as Record<string, unknown>;
    }

    const general = (front.general ?? {}) as Record<string, unknown>;
    const generalId = Number(general.generalId);
    expect(Number.isInteger(generalId) && generalId > 0, 'live scenario must expose a playable general').toBeTruthy();
    state.generalId = generalId;

    const ruleProfile = ((front.global ?? {}) as Record<string, unknown>).ruleProfile;
    expect(ruleProfile, 'campaign rule profile').toBe('HWIHA');
    const options = await apiGet(page.request, `/api/game/api/commands/enlistment-options?generalId=${generalId}`);
    writeArtifact('general-enlistment-options.json', { status: options.response.status(), body: options.data });
    expect(options.response.status()).toBe(200);
    const choice = ((options.data as Record<string, unknown>).options as Array<Record<string, unknown>>)
      .find(option => (option.availability as Record<string, unknown>)?.status === 'AVAILABLE');
    expect(choice, 'enlistment option').toBeTruthy();
    const commandCode = 'action.enlist';
    const commandArgs = { mode: choice?.mode, ...(choice?.targetId == null ? {} : { targetId: choice.targetId }) };

    const applied = await apiPost(page.request, `/api/game/api/command/${encodeURIComponent(commandCode)}?generalId=${generalId}&turnIdx=0`, commandArgs);
    const appliedRequestId = requestIdOf(applied.data);
    const appliedTerminal = appliedRequestId ? await pollCommandResult(page.request, appliedRequestId, 1_200_000) : null;
    writeArtifact('command-general-applied.json', { intake: { status: applied.response.status(), body: applied.data }, requestId: appliedRequestId, terminal: appliedTerminal });
    expect(applied.response.status()).toBeLessThan(300);
    expect(appliedRequestId, 'applied command requestId').toBeTruthy();
    expect(appliedTerminal?.data).toMatchObject({ status: 'RESOLVED', ok: true });
    state.commandRequestId = appliedRequestId;

    const rejected = await apiPost(page.request, '/api/game/api/command/__e2e_invalid__?generalId=0&turnIdx=0', {});
    writeArtifact('command-general-rejected.json', { status: rejected.response.status(), body: rejected.data });
    expect(rejected.response.status() >= 400 || (rejected.data && typeof rejected.data === 'object' && !Array.isArray(rejected.data) && (rejected.data as Record<string, unknown>).status === 'BLOCKED')).toBeTruthy();

    const routes: Array<[string, string]> = [
      ['/game', 'general'],
      ['/game/war-room', 'war-room'],
      ['/game/retinue', 'retinue'],
      ['/game/court', 'court'],
      ['/game/board', 'board'],
      ['/game/board?secret=1', 'board-secret-deep-link'],
      ['/game/mailbox', 'mailbox'],
      ['/game/my', 'my-info'],
      ['/game/history', 'history'],
      ['/game/rankings/kingdoms', 'kingdom-roles'],
    ];
    for (const [route, id] of routes) await captureSurface(page, route, id);

    const beforeRestart = await apiGet(page.request, '/api/game/api/front-info');
    state.beforeRestart = { status: beforeRestart.response.status(), generalId: ((beforeRestart.data as Record<string, unknown>).general as Record<string, unknown> | undefined)?.generalId };
    execFileSync('docker', ['compose', '--project-name', composeProjectName, '--env-file', '/dev/null', 'restart', 'game-engine'], { cwd: repoRoot, stdio: 'pipe', timeout: 180_000 });
    await expect.poll(() => statusOrUnavailable(engineHealthUrl), { timeout: 120_000 }).toBe(200);
    const afterRestart = await apiGet(page.request, '/api/game/api/front-info');
    writeArtifact('restart-persistence.json', { before: state.beforeRestart, after: { status: afterRestart.response.status(), body: afterRestart.data }, commandRequestId: state.commandRequestId });
    expect(afterRestart.response.status()).toBe(200);
    expect(((afterRestart.data as Record<string, unknown>).general as Record<string, unknown> | undefined)?.generalId).toBe((state.beforeRestart as Record<string, unknown>).generalId);
    const commandAfterRestart = await apiGet(page.request, `/api/game/api/command/result/${encodeURIComponent(String(state.commandRequestId))}`);
    writeArtifact('restart-command-result-check.json', { status: commandAfterRestart.response.status(), body: commandAfterRestart.data, requestId: state.commandRequestId });
    expect(commandAfterRestart.response.status()).toBe(200);
    expect(commandAfterRestart.data).toMatchObject({ status: 'RESOLVED', requestId: state.commandRequestId });
    const repositoryCheck = await apiGet(page.request, '/api/game/api/rankings/kingdoms');
    writeArtifact('restart-repository-check.json', { status: repositoryCheck.response.status(), body: repositoryCheck.data });
    expect(repositoryCheck.response.status()).toBe(200);
  } finally {
    capture.stop();
    writeArtifact('api-responses.json', capture.all);
    writeArtifact('campaign-state.json', state);
    await context.close();
  }
});
