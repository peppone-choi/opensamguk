// Real-backend helpers for live journeys (campaign-core-live). No page.route, no synthetic API or SSE bodies.
// Credentials stay in memory: never written to artifacts, assertion messages, traces or screenshots.
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, type APIRequestContext, type Locator, type Page } from '@playwright/test';

export interface LiveEnv {
  readonly gatewayUrl: string;
  readonly gameUrl: string;
  /** null = serverless single game (`/game`), as local_v1_gate runs it. */
  readonly serverId: string | null;
  /** Both set = log in as this USER (must own no general here). Both unset = register a new USER through /join. */
  readonly account: { readonly username: string; readonly password: string } | null;
  readonly artifactDir: string;
}

function origin(name: string, value: string): string {
  const url = new URL(value);
  if (url.pathname !== '/' || url.search || url.hash) throw new Error(`${name} must be an origin without path`);
  return url.origin;
}

/** Read lazily inside the test so `playwright test --list` works without a stack and a missing env fails (never skips). */
export function liveEnv(): LiveEnv {
  const env = process.env;
  if (!env.E2E_GATEWAY_URL) throw new Error('E2E_GATEWAY_URL is required (real gateway origin, e.g. https://localhost)');
  const gatewayUrl = origin('E2E_GATEWAY_URL', env.E2E_GATEWAY_URL);
  const serverId = env.E2E_SERVER_ID || null;
  if (serverId !== null && !/^[a-z0-9][a-z0-9-]{0,62}$/.test(serverId)) throw new Error('E2E_SERVER_ID must be a public server id');
  if (Boolean(env.E2E_LIVE_USERNAME) !== Boolean(env.E2E_LIVE_PASSWORD)) {
    throw new Error('E2E_LIVE_USERNAME and E2E_LIVE_PASSWORD must be set together');
  }
  return {
    gatewayUrl,
    gameUrl: env.E2E_GAME_URL ? origin('E2E_GAME_URL', env.E2E_GAME_URL) : gatewayUrl,
    serverId,
    account: env.E2E_LIVE_USERNAME ? { username: env.E2E_LIVE_USERNAME, password: env.E2E_LIVE_PASSWORD! } : null,
    artifactDir: env.E2E_ARTIFACT_DIR ?? join(process.cwd(), 'test-results', 'campaign-core-live'),
  };
}

/** `/game/<server>/<slug>` (or `/game/<slug>` serverless) on the game origin. '' = war room. */
export function gamePage(env: LiveEnv, slug = '', query = ''): string {
  const parts = ['game', env.serverId, slug].filter(Boolean).join('/');
  return `${env.gameUrl}/${parts}${query ? `?${query}` : ''}`;
}

/** Browser game BFF: `/api/game/api/<path>?server=<id>` (contract §2). */
export function gameApi(env: LiveEnv, path: string): string {
  const url = `${env.gameUrl}/api/game/api/${path}`;
  return env.serverId ? `${url}${path.includes('?') ? '&' : '?'}server=${encodeURIComponent(env.serverId)}` : url;
}

export async function readJson<T>(request: APIRequestContext, url: string, what: string): Promise<T> {
  const response = await request.get(url);
  expect(response.status(), `${what} read`).toBe(200);
  return await response.json() as T;
}

/** Controlled inputs can drop a fill made before hydration; refill once if a value did not stick. */
export async function fillControlled(page: Page, fields: readonly { readonly name: string; readonly value: string }[]): Promise<void> {
  const inputs: { value: string; locator: Locator }[] = fields.map((f) => ({ value: f.value, locator: page.locator(`input[name="${f.name}"]`) }));
  await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => undefined);
  for (const { locator } of inputs) await expect(locator).toBeEditable({ timeout: 15_000 });
  for (let attempt = 0; attempt < 2; attempt++) {
    for (const { value, locator } of inputs) await locator.fill(value);
    await page.waitForTimeout(350);
    const stuck = await Promise.all(inputs.map(async ({ value, locator }) => (await locator.inputValue()) === value));
    if (stuck.every(Boolean)) return;
  }
  // Values are not echoed: a mismatch message names no field value.
  throw new Error('Auth form inputs did not keep the typed values');
}

/** Sanitized evidence only: ids, statuses, counts. Never cookies, tokens, passwords or raw auth bodies. */
export function evidence(env: LiveEnv, name: string, value: unknown): void {
  mkdirSync(env.artifactDir, { recursive: true });
  writeFileSync(join(env.artifactDir, `${name.replace(/[^a-zA-Z0-9._-]+/g, '-')}.json`), `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}
