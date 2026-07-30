import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type APIRequestContext, type APIResponse, type BrowserContext, type Page, type Response } from '@playwright/test';

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

function commandCodeOf(value: unknown): string | null {
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = commandCodeOf(item);
      if (found) return found;
    }
    return null;
  }
  if (!value || typeof value !== 'object') return null;
  const record = value as Record<string, unknown>;
  if (typeof record.value === 'string' && record.value.trim()) {
    const reqArg = record.reqArg;
    if ((reqArg === false || reqArg == null) && record.possible !== false) return record.value;
  }
  for (const child of Object.values(record)) {
    const found = commandCodeOf(child);
    if (found) return found;
  }
  return null;
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

async function createAndLogin(context: BrowserContext): Promise<{ page: Page; username: string; password: string }> {
  const page = await context.newPage();
  const suffix = `${Date.now()}${Math.floor(Math.random() * 10_000)}`;
  const username = `e2e_${suffix}`;
  const password = `E2e!${suffix}a`;

  await page.goto(`${gatewayUrl}/join`, { waitUntil: 'domcontentloaded' });
  await page.locator('input[name="username"]').fill(username);
  await page.locator('input[name="password"]').fill(password);
  await page.locator('input[name="passwordConfirm"]').fill(password);
  await page.locator('input[name="nickname"]').fill(`e2e-${suffix}`);
  const registerResponsePromise = page.waitForResponse((r) => r.url().includes('/api/auth/register'));
  await page.getByRole('button', { name: /가입/ }).click();
  const registerResponse = await registerResponsePromise;
  const registration = await responseBody(registerResponse);
  writeArtifact('auth-register.json', { status: registration.status, body: registration.data });
  expect(registration.status, 'actual signup response').toBe(200);
  expect((await context.cookies()).some((cookie) => cookie.name === 'sam_access' && cookie.httpOnly), 'signup httpOnly access cookie').toBeTruthy();

  await page.request.post(`${gatewayUrl}/api/auth/logout`);
  await page.goto(`${gatewayUrl}/login`, { waitUntil: 'domcontentloaded' });
  await page.locator('input[name="username"]').fill(username);
  await page.locator('input[name="password"]').fill(password);
  const loginResponsePromise = page.waitForResponse((r) => r.url().includes('/api/auth/login'));
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

test('v1 core live surfaces and durable engine restart', async ({ browser }, testInfo) => {
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

    const available = await apiGet(page.request, `/api/game/api/commands/available?generalId=${generalId}`);
    writeArtifact('general-commands-available.json', { status: available.response.status(), body: available.data });
    const commandCode = commandCodeOf(available.data);
    expect(commandCode, 'live available command catalog').toBeTruthy();

    const applied = await apiPost(page.request, `/api/game/api/command/${encodeURIComponent(commandCode as string)}?generalId=${generalId}&turnIdx=0`, {});
    const appliedRequestId = requestIdOf(applied.data);
    const appliedTerminal = appliedRequestId ? await pollCommandResult(page.request, appliedRequestId) : null;
    writeArtifact('command-general-applied.json', { intake: { status: applied.response.status(), body: applied.data }, requestId: appliedRequestId, terminal: appliedTerminal });
    expect(applied.response.status()).toBeLessThan(300);
    expect(appliedRequestId, 'applied command requestId').toBeTruthy();
    expect(appliedTerminal?.data).toMatchObject({ status: 'RESOLVED', ok: true });
    state.commandRequestId = appliedRequestId;

    const pending = await apiPost(page.request, `/api/game/api/command/${encodeURIComponent(commandCode as string)}?generalId=${generalId}&turnIdx=1`, {});
    const pendingRequestId = requestIdOf(pending.data);
    writeArtifact('command-general-pending.json', { status: pending.response.status(), body: pending.data, requestId: pendingRequestId });
    expect(pending.response.status()).toBeLessThan(300);
    expect(pendingRequestId, 'pending command requestId').toBeTruthy();
    const pendingTerminal = await pollCommandResult(page.request, pendingRequestId as string);
    writeArtifact('command-general-pending-terminal.json', { requestId: pendingRequestId, ...pendingTerminal, data: pendingTerminal.data });

    const rejected = await apiPost(page.request, '/api/game/api/command/__e2e_invalid__?generalId=0&turnIdx=0', {});
    writeArtifact('command-general-rejected.json', { status: rejected.response.status(), body: rejected.data });
    expect(rejected.response.status() >= 400 || (rejected.data && typeof rejected.data === 'object' && !Array.isArray(rejected.data) && (rejected.data as Record<string, unknown>).status === 'BLOCKED')).toBeTruthy();

    const nation = await apiPost(page.request, `/api/game/api/command/nation/bulk?generalId=${generalId}`, []);
    writeArtifact('command-nation.json', { status: nation.response.status(), body: nation.data, requestId: requestIdOf(nation.data) });
    expect(nation.response.status()).toBeLessThan(300);

    const routes: Array<[string, string]> = [
      ['/game', 'general'],
      ['/game/nation', 'nation'],
      ['/game/auction', 'auction-resource'],
      ['/game/auction?type=unique', 'auction-unique-deep-link'],
      ['/game/board', 'board'],
      ['/game/board?secret=1', 'board-secret-deep-link'],
      ['/game/diplomacy', 'diplomacy'],
      ['/game/mailbox', 'mailbox'],
      ['/game/betting', 'betting'],
      ['/game/select-pool', 'select-pool'],
      ['/game/my', 'settings-vacation'],
      ['/game/history', 'history'],
      ['/game/rankings/kingdoms', 'kingdom-roles'],
    ];
    for (const [route, id] of routes) await captureSurface(page, route, id);

    const emperorList = await apiGet(page.request, '/api/game/api/rankings/emperor');
    writeArtifact('emperor-list.json', { status: emperorList.response.status(), body: emperorList.data });
    let emperorId = '0';
    if (Array.isArray(emperorList.data) && emperorList.data.length > 0 && emperorList.data[0] && typeof emperorList.data[0] === 'object') {
      const record = emperorList.data[0] as Record<string, unknown>;
      emperorId = String(record.id ?? record.generalId ?? record.emperorId ?? '0');
    }
    await captureSurface(page, `/game/rankings/emperor/${encodeURIComponent(emperorId)}`, 'emperor-detail');

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
    writeArtifact('v1-state.json', state);
    await context.close();
  }
});
