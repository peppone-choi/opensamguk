import { execFileSync } from 'node:child_process';
import { expect, test, type Page, type Response } from '@playwright/test';
import type { DispatchPendingResponse, EnlistmentOptionsResponse } from '../lib/types';

const gameUrl = process.env.E2E_GAME_URL ?? 'http://localhost:3001';
const gatewayUrl = process.env.E2E_GATEWAY_URL ?? 'http://localhost:3000';
const enabled = process.env.E2E_HWIHA_COURT === 'true';

type Terminal = { status: string; ok?: boolean; reason?: string };

async function read<T>(page: Page, path: string): Promise<T> {
  const response = await page.request.get(`${gameUrl}/api/game${path}`);
  expect(response.status(), path).toBe(200);
  return response.json() as Promise<T>;
}

async function terminal(page: Page, response: Response): Promise<string> {
  expect(response.status()).toBe(202);
  const intake = await response.json() as { requestId: string };
  expect(intake.requestId).toMatch(/^[A-Za-z0-9._:-]+$/);
  let result: Terminal | undefined;
  await expect.poll(async () => {
    result = await read<Terminal>(page, `/api/command/result/${intake.requestId}`);
    return result.status;
  }, { timeout: 180_000, intervals: [1000, 3000] }).toBe('RESOLVED');
  expect(result).toMatchObject({ status: 'RESOLVED', ok: true });
  return intake.requestId;
}

// Read only, scoped to the unique Compose project supplied by the isolated runner.
function storedGeneral(generalId: number): Record<string, unknown> {
  const project = process.env.E2E_COMPOSE_PROJECT_NAME ?? '';
  const world = process.env.E2E_OPENSAMGUK_WORLD_ID ?? '';
  expect(project).toMatch(/^v1-e2e-[a-z0-9-]+$/);
  expect(world).toMatch(/^[1-9][0-9]*$/);
  expect(Number.isSafeInteger(generalId) && generalId > 0).toBe(true);
  const sql = `SELECT json_build_object('nationId', g.nation_id, 'policy', g.meta->'personPolicy',
    'dispatch', g.meta->'dispatch', 'assignment', g.meta->'countyAssignment',
    'position', (SELECT row_to_json(p) FROM general_spatial_position p WHERE p.world_id=g.world_id AND p.general_id=g.id),
    'reservations', (SELECT count(*) FROM general_turn t WHERE t.world_id=g.world_id AND t.general_id=g.id))
    FROM general g WHERE g.world_id=${world} AND g.id=${generalId};`;
  const output = execFileSync('docker', ['compose', '--project-name', project, '--env-file', '/dev/null',
    'exec', '-T', 'postgres', 'sh', '-ceu',
    'psql -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"', 'sh', sql],
  { cwd: process.env.E2E_REPO_ROOT, encoding: 'utf8', timeout: 15_000 });
  return JSON.parse(output.trim()) as Record<string, unknown>;
}

test('HWIHA actual signup, player creation, NPC enlistment and dispatch reply persist', async ({ page, context }, testInfo) => {
  test.skip(!enabled, 'Requires the isolated HWIHA synthetic court fixture');
  expect(process.env.SCENARIO_QA_TURNTERM).toBe('1');
  const suffix = Date.now().toString();
  const username = `court_${suffix}`;
  const password = `Court!${suffix}a`;

  await page.goto(`${gatewayUrl}/join`);
  for (const [name, value] of Object.entries({ username, password, passwordConfirm: password, nickname: `court${suffix}` })) {
    await page.locator(`input[name="${name}"]`).fill(value);
  }
  const registration = page.waitForResponse(r => r.request().method() === 'POST' && r.url().includes('/api/auth/register'));
  await page.getByRole('button', { name: /가입/ }).click();
  expect((await registration).status()).toBe(200);
  expect((await context.cookies()).some(c => c.name === 'sam_access' && c.httpOnly)).toBe(true);
  await page.request.post(`${gatewayUrl}/api/auth/logout`);
  await page.goto(`${gatewayUrl}/login`);
  await page.locator('input[name="username"]').fill(username);
  await page.locator('input[name="password"]').fill(password);
  const login = page.waitForResponse(r => r.request().method() === 'POST' && r.url().includes('/api/auth/login'));
  await page.getByRole('button', { name: '로그인', exact: true }).click();
  expect((await login).status()).toBe(200);
  expect((await context.cookies()).some(c => c.name === 'sam_access' && c.httpOnly)).toBe(true);

  await page.goto(`${gameUrl}/game/join`);
  await page.locator('form input[type="text"]').first().fill(`QA${suffix}`);
  page.on('dialog', dialog => void dialog.accept());
  const creation = page.waitForResponse(r => r.request().method() === 'POST' && r.url().includes('/api/game/api/join'));
  await page.getByRole('button', { name: '장수 생성', exact: true }).click();
  const creationId = await terminal(page, await creation);
  type Front = { global: { ruleProfile: string }; general: { generalId: number; hasGeneral: boolean; nationId: number } };
  await expect.poll(async () => (await read<Front>(page, '/api/front-info')).general.hasGeneral).toBe(true);
  const initial = await read<Front>(page, '/api/front-info');
  expect(initial.global.ruleProfile).toBe('HWIHA');
  expect(initial.general.nationId).toBe(0);
  const generalId = initial.general.generalId;
  const created = storedGeneral(generalId);
  expect(created.policy).toMatchObject({ renownCapacity: 30, statSourceId: 'opensamguk:created-general' });
  expect(created.position).not.toBeNull();

  await page.goto(`${gameUrl}/game`);
  await page.getByRole('button', { name: '명령 추가 · 편집', exact: true }).click();
  const options = await read<EnlistmentOptionsResponse>(page, `/api/commands/enlistment-options?generalId=${generalId}`);
  expect(options.result).toBe(true);
  const lordIndex = options.options.findIndex(o => o.mode === 'GENERAL' && o.label.includes('QA 주공') && o.availability.status === 'AVAILABLE');
  expect(lordIndex, 'the seeded NPC lord must be an available real candidate').toBeGreaterThanOrEqual(0);
  await page.getByLabel('출사 대상', { exact: true }).selectOption(String(lordIndex));
  const enlistment = page.waitForResponse(r => r.request().method() === 'POST' && r.url().includes('/api/command/action.enlist'));
  await page.getByRole('button', { name: '출사 예약', exact: true }).click();
  const enlistmentId = await terminal(page, await enlistment);
  expect((await read<Front>(page, '/api/front-info')).general.nationId).toBe(1);

  const pendingPath = `/api/commands/dispatches?generalId=${generalId}`;
  await expect.poll(async () => (await read<DispatchPendingResponse>(page, pendingPath)).dispatches
    .some(d => d.targetId === generalId && d.status === 'PENDING'),
  { timeout: 180_000, intervals: [1000, 3000] }).toBe(true);
  const dispatch = (await read<DispatchPendingResponse>(page, pendingPath)).dispatches.find(d => d.targetId === generalId && d.status === 'PENDING')!;
  expect(dispatch.dispatchId).toMatch(/^npc-dispatch:/);
  expect(dispatch.issuerId).toBe(options.options[lordIndex].targetId);
  const beforeReply = storedGeneral(generalId);
  await page.getByRole('button', { name: '발령·응답', exact: true }).click();
  await expect(page.getByText('나에게 온 발령', { exact: false })).toBeVisible();
  const reply = page.waitForResponse(r => r.request().method() === 'POST' && r.url().includes('/api/commands/court/dispatchReply'));
  await page.getByRole('button', { name: '수락', exact: true }).click();
  const replyId = await terminal(page, await reply);
  await expect.poll(async () => (await read<DispatchPendingResponse>(page, pendingPath)).dispatches
    .find(d => d.dispatchId === dispatch.dispatchId)?.status).toBe('ACCEPTED');
  const accepted = storedGeneral(generalId);
  expect(accepted.assignment).toMatchObject({ dispatchId: dispatch.dispatchId, countyId: dispatch.countyId, issuerId: dispatch.issuerId, nationId: 1 });
  expect(accepted.dispatch).toMatchObject({ status: 'ACCEPTED' });
  expect(accepted.position).toEqual(beforeReply.position);
  expect(accepted.reservations).toEqual(beforeReply.reservations);
  expect(accepted.policy).toEqual(created.policy);
  await page.reload();
  await page.getByRole('button', { name: '발령·응답', exact: true }).click();
  await expect(page.getByText(/수락 · 응답 기한/)).toBeVisible();
  await testInfo.attach('persisted-court-flow', { body: JSON.stringify({ generalId, creationId, enlistmentId, replyId, dispatch, created, beforeReply, accepted }, null, 2), contentType: 'application/json' });
  await testInfo.attach('accepted-dispatch', { body: await page.screenshot(), contentType: 'image/png' });
});
