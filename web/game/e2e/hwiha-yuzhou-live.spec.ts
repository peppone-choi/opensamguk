import { execFileSync } from 'node:child_process';
import { expect, test, type Page, type Response } from '@playwright/test';
import type { DispatchPendingResponse, EnlistmentOptionsResponse } from '../lib/types';

const gameUrl = process.env.E2E_GAME_URL ?? 'http://localhost:3001';
const gatewayUrl = process.env.E2E_GATEWAY_URL ?? 'http://localhost:3000';
const enabled = process.env.E2E_HWIHA_YUZHOU === 'true';

function compose(args: string[]): string {
  const project = process.env.E2E_COMPOSE_PROJECT_NAME ?? '';
  expect(project).toMatch(/^v1-e2e-[a-z0-9-]+$/);
  return execFileSync('docker', ['compose', '--project-name', project, '--env-file', '/dev/null', ...args],
    { cwd: process.env.E2E_REPO_ROOT, encoding: 'utf8', timeout: 20_000 });
}

function sql(query: string): string {
  return compose(['exec', '-T', 'postgres', 'sh', '-ceu',
    'psql -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"', 'sh', query]).trim();
}

async function read<T>(page: Page, path: string): Promise<T> {
  const response = await page.request.get(`${gameUrl}/api/game${path}`);
  expect(response.status(), path).toBe(200);
  return response.json() as Promise<T>;
}

async function terminal(page: Page, response: Response): Promise<void> {
  expect(response.status()).toBe(202);
  const intake = await response.json() as { requestId: string };
  expect(intake.requestId).toMatch(/^[A-Za-z0-9._:-]+$/);
  await expect.poll(async () => (await read<{ status: string; ok?: boolean }>(page,
    `/api/command/result/${intake.requestId}`)).status, { timeout: 180_000, intervals: [1000, 3000] }).toBe('RESOLVED');
  expect((await read<{ ok: boolean }>(page, `/api/command/result/${intake.requestId}`)).ok).toBe(true);
}

test('HWIHA 豫州 player flow, NPC war, monthly boundary and nine live screens', async ({ page, context }, testInfo) => {
  test.skip(!enabled, 'Requires an isolated 豫州 HWIHA world');
  expect(process.env.SCENARIO_QA_TURNTERM).toBe('1');
  const worldId = process.env.E2E_OPENSAMGUK_WORLD_ID ?? '';
  expect(worldId).toBe('990002');
  const suffix = Date.now().toString();
  const username = `yuzhou_${suffix}`;
  const password = `Yuzhou!${suffix}a`;

  await page.goto(`${gatewayUrl}/join`);
  for (const [name, value] of Object.entries({ username, password, passwordConfirm: password, nickname: `yuzhou${suffix}` }))
    await page.locator(`input[name="${name}"]`).fill(value);
  const registration = page.waitForResponse(r => r.request().method() === 'POST' && r.url().includes('/api/auth/register'));
  await page.getByRole('button', { name: /가입/ }).click();
  expect((await registration).status()).toBe(200);
  expect((await context.cookies()).some(c => c.name === 'sam_access' && c.httpOnly)).toBe(true);

  await page.goto(`${gameUrl}/game/join`);
  await page.locator('form input[type="text"]').first().fill(`豫州QA${suffix}`);
  page.on('dialog', dialog => void dialog.accept());
  const creation = page.waitForResponse(r => r.request().method() === 'POST' && r.url().includes('/api/game/api/join'));
  await page.getByRole('button', { name: '장수 생성', exact: true }).click();
  await terminal(page, await creation);
  type Front = { global: { ruleProfile: string }; general: { generalId: number; hasGeneral: boolean; nationId: number } };
  await expect.poll(async () => (await read<Front>(page, '/api/front-info')).general.hasGeneral).toBe(true);
  const front = await read<Front>(page, '/api/front-info');
  expect(front.global.ruleProfile).toBe('HWIHA');
  const generalId = front.general.generalId;
  expect(Number.isSafeInteger(generalId) && generalId > 0).toBe(true);

  // The only post-seed SQL write in this test keeps the newly created human alive during the long QA run.
  expect(sql(`UPDATE general SET meta=jsonb_set(meta, '{killturn}', '96'::jsonb) WHERE world_id=${worldId} AND id=${generalId} RETURNING id;`)).toBe(String(generalId));

  await page.goto(`${gameUrl}/game`);
  await page.getByRole('button', { name: '명령 추가 · 편집', exact: true }).click();
  const options = await read<EnlistmentOptionsResponse>(page, `/api/commands/enlistment-options?generalId=${generalId}`);
  const lordIndex = options.options.findIndex(o => o.mode === 'GENERAL' && o.availability.status === 'AVAILABLE');
  expect(lordIndex).toBeGreaterThanOrEqual(0);
  await page.getByLabel('출사 대상', { exact: true }).selectOption(String(lordIndex));
  const enlistment = page.waitForResponse(r => r.request().method() === 'POST' && r.url().includes('/api/command/action.enlist'));
  await page.getByRole('button', { name: '출사 예약', exact: true }).click();
  await terminal(page, await enlistment);
  const nationId = (await read<Front>(page, '/api/front-info')).general.nationId;
  expect(nationId).toBeGreaterThan(0);

  const dispatchPath = `/api/commands/dispatches?generalId=${generalId}`;
  await expect.poll(async () => (await read<DispatchPendingResponse>(page, dispatchPath)).dispatches
    .some(d => d.targetId === generalId && d.status === 'PENDING'),
  { timeout: 240_000, intervals: [3000, 5000] }).toBe(true);
  const dispatch = (await read<DispatchPendingResponse>(page, dispatchPath)).dispatches
    .find(d => d.targetId === generalId && d.status === 'PENDING')!;
  await page.getByRole('button', { name: '발령·응답', exact: true }).click();
  const reply = page.waitForResponse(r => r.request().method() === 'POST' && r.url().includes('/api/commands/court/dispatchReply'));
  await page.getByRole('button', { name: '수락', exact: true }).click();
  await terminal(page, await reply);
  await expect.poll(async () => (await read<DispatchPendingResponse>(page, dispatchPath)).dispatches
    .find(d => d.dispatchId === dispatch.dispatchId)?.status).toBe('ACCEPTED');

  const siegeSummary = () => JSON.parse(sql(`SELECT json_build_object('active', count(*) FILTER (WHERE status='ACTIVE'),
    'fallen', count(*) FILTER (WHERE status='FALLEN'), 'rows', count(*)) FROM hwiha_siege WHERE world_id=${worldId};`)) as
    { active: number; fallen: number; rows: number };
  await expect.poll(() => siegeSummary().fallen, { timeout: 2_400_000, intervals: [10_000] }).toBeGreaterThan(0);
  type Yuedan = { status: string; stamp: string | null; ranking: unknown[] };
  await expect.poll(async () => (await read<Yuedan>(page, `/api/hwiha/yuedan?generalId=${generalId}`)).status,
    { timeout: 600_000, intervals: [5000] }).toBe('READY');

  const screens = ['court', 'hand', 'orders', 'posts', 'retinue', 'siege', 'supply', 'war-room', 'yuedan'];
  const paths: Record<string, string[]> = {
    court: ['/api/map/preview', dispatchPath],
    hand: [`/api/commands/stratagem-hand?generalId=${generalId}`],
    orders: [`/api/hwiha/retinue?generalId=${generalId}`, `/api/hwiha/warehouses?generalId=${generalId}`],
    posts: [`/api/hwiha/posts?generalId=${generalId}`],
    retinue: [`/api/hwiha/retinue?generalId=${generalId}`],
    siege: [`/api/hwiha/sieges?generalId=${generalId}`],
    supply: [`/api/hwiha/warehouses?generalId=${generalId}`],
    'war-room': [`/api/hwiha/visibility?generalId=${generalId}`, `/api/hwiha/corps?generalId=${generalId}`],
    yuedan: [`/api/hwiha/yuedan?generalId=${generalId}`],
  };
  for (const screen of screens) {
    await page.goto(`${gameUrl}/game/hwiha/${screen}`);
    await expect(page.locator('main')).toBeVisible();
    await testInfo.attach(`screen-${screen}`, { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });
    for (const path of paths[screen]) {
      const response = await page.request.get(`${gameUrl}/api/game${path}`);
      expect(response.status(), `${screen}: ${path}`).toBe(200);
      await testInfo.attach(`api-${screen}-${paths[screen].indexOf(path)}`, { body: await response.body(), contentType: 'application/json' });
    }
  }
  const db = sql(`SELECT json_build_object('sieges', (SELECT json_agg(json_build_object('countyId',county_id,'status',status,'turns',turns,'endReason',end_reason)) FROM hwiha_siege WHERE world_id=${worldId}),
    'player', (SELECT json_build_object('nationId',nation_id,'assignment',meta->'hwihaCountyAssignment','position',meta->'hwihaPosition') FROM general WHERE world_id=${worldId} AND id=${generalId}),
    'warehouses', (SELECT json_agg(json_build_object('id',id,'stock',meta->'hwihaCountyWarehouse')) FROM city WHERE world_id=${worldId} AND nation_id=${nationId}));`);
  await testInfo.attach('db-hwiha-slice', { body: db, contentType: 'application/json' });
  await testInfo.attach('phase-evidence', { body: JSON.stringify({ generalId, nationId, dispatch, siege: siegeSummary(), yuedan: await read<Yuedan>(page, `/api/hwiha/yuedan?generalId=${generalId}`) }, null, 2), contentType: 'application/json' });
  const logs = compose(['logs', '--no-color', 'game-engine']);
  expect((logs.match(/tick failed/gi) ?? []).length, 'engine tick failed').toBe(0);
});
