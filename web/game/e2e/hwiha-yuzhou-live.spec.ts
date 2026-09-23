import { execFileSync } from 'node:child_process';
import { expect, test, type Page, type Response } from '@playwright/test';
import type { DispatchPendingResponse, EnlistmentOptionsResponse } from '../lib/types';

const gameUrl = process.env.E2E_GAME_URL ?? 'http://localhost:3001';
const gatewayUrl = process.env.E2E_GATEWAY_URL ?? 'http://localhost:3000';
const enabled = process.env.E2E_HWIHA_YUZHOU === 'true';

// Live map animation can stall Playwright's automatic failure screenshot.
// The test attaches its own screenshots through CDP below.
test.use({ screenshot: 'off', video: 'off', trace: 'off' });

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
  const intake = await response.json() as { requestId: string };
  expect(response.status(), JSON.stringify(intake)).toBe(202);
  expect(intake.requestId).toMatch(/^[A-Za-z0-9._:-]+$/);
  await expect.poll(async () => (await read<{ status: string; ok?: boolean }>(page,
    `/api/command/result/${intake.requestId}`)).status, { timeout: 1_200_000, intervals: [1000, 3000] }).toBe('RESOLVED');
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
  const generalName = `예주${suffix.slice(-6)}`;
  await page.locator('form input[type="text"]').first().fill(generalName);
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

  // Stop the snapshot writer before the only post-seed SQL write. Restarting
  // after the update races with the departing engine's final snapshot flush.
  compose(['stop', 'game-engine']);
  // The only post-seed SQL write in this test keeps the newly created human alive during the long QA run.
  expect(sql(`UPDATE general SET meta=jsonb_set(meta, '{killturn}', '96'::jsonb) WHERE world_id=${worldId} AND id=${generalId} RETURNING id;`)).toBe(String(generalId));
  compose(['start', 'game-engine']);
  const engineHealthUrl = process.env.E2E_GAME_ENGINE_HEALTH_URL ?? '';
  expect(engineHealthUrl).toMatch(/^http:\/\/localhost:\d+\/actuator\/health$/);
  await expect.poll(async () => {
    try { return (await page.request.get(engineHealthUrl, { timeout: 5_000 })).status(); }
    catch { return 0; }
  }, { timeout: 300_000, intervals: [3000, 5000] }).toBe(200);
  expect(sql(`SELECT meta->>'killturn' FROM general WHERE world_id=${worldId} AND id=${generalId};`)).toBe('96');

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
  { timeout: 1_200_000, intervals: [3000, 5000] }).toBe(true);
  const dispatch = (await read<DispatchPendingResponse>(page, dispatchPath)).dispatches
    .find(d => d.targetId === generalId && d.status === 'PENDING')!;
  await page.getByRole('button', { name: '발령·응답', exact: true }).click();
  const reply = page.waitForResponse(r => r.request().method() === 'POST' && r.url().includes('/api/commands/court/dispatchReply'));
  await page.getByRole('button', { name: '수락', exact: true }).click();
  await terminal(page, await reply);
  await expect.poll(async () => (await read<DispatchPendingResponse>(page, dispatchPath)).dispatches
    .find(d => d.dispatchId === dispatch.dispatchId)?.status).toBe('ACCEPTED');
  const marchState = () => JSON.parse(sql(`SELECT json_build_object(
    'stop',meta->'hwihaMarch'->>'stop',
    'edgeIndex',coalesce((meta->'hwihaMarch'->>'edgeIndex')::integer,0),
    'paidMm',coalesce((meta->'hwihaMarch'->>'paidMm')::bigint,0),
    'routeCostMm',(meta->'hwihaMarch'->'path'->>'totalCostMm')::bigint)
    FROM general WHERE world_id=${worldId} AND id=${generalId};`)) as
    { stop: string | null; edgeIndex: number; paidMm: number; routeCostMm: number | null };
  // New characters are born in a random neutral city anywhere in the world.
  // Appointment travel must advance, while arrival time depends on that draw.
  await expect.poll(() => {
    const march = marchState();
    return march.edgeIndex > 0 || march.paidMm > 0 || march.stop === 'ARRIVED';
  }, { timeout: 600_000, intervals: [10_000] }).toBe(true);
  const activeWarAfterFirstBoundary = Number(sql(`SELECT count(*) FROM diplomacy WHERE world_id=${worldId} AND state_code=0 AND term>0;`));
  expect(activeWarAfterFirstBoundary, 'war relations survive the first monthly settlement').toBe(30);

  const siegeSummary = () => JSON.parse(sql(`SELECT json_build_object('active', count(*) FILTER (WHERE status='ACTIVE'),
    'fallen', count(*) FILTER (WHERE status='FALLEN'), 'rows', count(*)) FROM hwiha_siege WHERE world_id=${worldId};`)) as
    { active: number; fallen: number; rows: number };
  await expect.poll(() => siegeSummary().fallen, { timeout: 2_400_000, intervals: [10_000] }).toBeGreaterThan(0);
  const npcBattles = () => Number(sql(`SELECT count(*) FROM general WHERE world_id=${worldId} AND id BETWEEN 1001 AND 1006
    AND meta ? 'hwihaLastBattle';`));
  await expect.poll(npcBattles, { timeout: 4_800_000, intervals: [10_000] }).toBeGreaterThan(0);
  // Observe the same 36-phase horizon as the in-memory simulation. The old
  // isolation bug only recaptured the same neutralized counties months later.
  await expect.poll(() => Number(sql(`SELECT current_year FROM world_state WHERE id=${worldId};`)),
    { timeout: 3_000_000, intervals: [10_000] }).toBeGreaterThanOrEqual(191);
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
    'war-room': [`/api/hwiha/visibility?generalId=${generalId}`, `/api/hwiha/corps?generalId=${generalId}`, `/api/hwiha/sieges?generalId=${generalId}`],
    yuedan: [`/api/hwiha/yuedan?generalId=${generalId}`],
  };
  const cdp = await context.newCDPSession(page);
  for (const screen of screens) {
    await page.goto(`${gameUrl}/game/hwiha/${screen}`);
    await expect(page.locator('nav[aria-label="입력 여섯 가지"]')).toBeVisible();
    await expect(page.locator('body')).toContainText(generalName, { timeout: 120_000 });
    await expect(page.locator('body')).not.toContainText('불러오는 중입니다', { timeout: 120_000 });
    // Playwright's screenshot stability wait can stall on the live map's continuous rendering.
    const size = await page.evaluate(() => ({ width: document.documentElement.scrollWidth,
      height: document.documentElement.scrollHeight }));
    const capture = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true,
      clip: { x: 0, y: 0, width: size.width, height: size.height, scale: 1 } });
    await testInfo.attach(`screen-${screen}`, { body: Buffer.from(capture.data, 'base64'), contentType: 'image/png' });
    for (const path of paths[screen]) {
      const response = await page.request.get(`${gameUrl}/api/game${path}`);
      expect(response.status(), `${screen}: ${path}`).toBe(200);
      await testInfo.attach(`api-${screen}-${paths[screen].indexOf(path)}`, { body: await response.body(), contentType: 'application/json' });
    }
  }
  await cdp.detach();
  const db = sql(`SELECT json_build_object('sieges', (SELECT json_agg(json_build_object('countyId',county_id,'status',status,'turns',turns,'endReason',end_reason)) FROM hwiha_siege WHERE world_id=${worldId}),
    'player', (SELECT json_build_object('nationId',g.nation_id,'assignment',g.meta->'hwihaCountyAssignment',
      'position',(SELECT row_to_json(p) FROM general_spatial_position p WHERE p.world_id=g.world_id AND p.general_id=g.id))
      FROM general g WHERE g.world_id=${worldId} AND g.id=${generalId}),
    'warehouses', (SELECT json_agg(json_build_object('id',id,'stock',meta->'hwihaCountyWarehouse')) FROM city WHERE world_id=${worldId} AND nation_id=${nationId}),
    'monthly', (SELECT json_object_agg(key,value) FROM game_kv WHERE world_id=${worldId} AND "table"='game_env' AND namespace='game_env'
      AND key IN ('hwihaCountyIncomeMonth','hwihaSalaryMonth','hwihaRenownAssessmentStamp','hwihaRenownRanking')));`);
  await testInfo.attach('db-hwiha-slice', { body: db, contentType: 'application/json' });
  const monthly = (JSON.parse(db) as { monthly: Record<string, unknown> }).monthly;
  expect(monthly.hwihaCountyIncomeMonth).toBeTruthy();
  expect(monthly.hwihaSalaryMonth).toBeTruthy();
  expect(monthly.hwihaRenownAssessmentStamp).toBeTruthy();
  expect(Array.isArray(monthly.hwihaRenownRanking) && monthly.hwihaRenownRanking.length > 0).toBe(true);
  const phaseEvents = sql(`SELECT coalesce(json_agg(json_build_object('year',year,'month',month,'phase',phase,
    'kind',event_kind,'generalId',general_id,'nationId',nation_id,'text',text,'refs',meta->'refs') ORDER BY year,month,phase,id),'[]'::json)
    FROM log_entry WHERE world_id=${worldId} AND event_kind IS NOT NULL;`);
  await testInfo.attach('phase-events', { body: phaseEvents, contentType: 'application/json' });
  const events = JSON.parse(phaseEvents) as { kind: string; refs?: {
    money?: number; grain?: number; countyId?: number; fromNationId?: number; encounterId?: string;
  } }[];
  expect(events.some(e => e.kind === 'income.monthly' && ((e.refs?.money ?? 0) > 0 || (e.refs?.grain ?? 0) > 0))).toBe(true);
  const encounterIds = new Set(events.filter(e => e.kind === 'march.corps')
    .map(e => e.refs?.encounterId).filter((id): id is string => typeof id === 'string'));
  expect(encounterIds.size, 'live NPC encounters').toBeGreaterThan(0);
  const seenCapture = new Set<number>();
  let repeatedNeutralCaptures = 0;
  for (const event of events.filter(e => e.kind === 'county.captured')) {
    const countyId = event.refs?.countyId;
    if (countyId === undefined) continue;
    if (seenCapture.has(countyId) && event.refs?.fromNationId === 0) repeatedNeutralCaptures += 1;
    seenCapture.add(countyId);
  }
  expect(repeatedNeutralCaptures, 'a previously captured county became neutral and was captured again').toBe(0);
  const abandonedWithGarrison = Number(sql(`SELECT count(*) FROM city c WHERE c.world_id=${worldId}
    AND c.nation_id=0 AND c.def>0 AND EXISTS (
      SELECT 1 FROM log_entry l WHERE l.world_id=c.world_id AND l.event_kind='county.captured'
        AND (l.meta->'refs'->>'countyId')::integer=c.id);`));
  expect(abandonedWithGarrison, 'a captured county with occupying troops became neutral').toBe(0);
  const activeWarRelations = Number(sql(`SELECT count(*) FROM diplomacy WHERE world_id=${worldId} AND state_code=0 AND term>0;`));
  await testInfo.attach('phase-evidence', { body: JSON.stringify({ generalId, nationId, dispatch, march: marchState(),
    siege: siegeSummary(), npcBattles: npcBattles(), liveEncounterCount: encounterIds.size,
    repeatedNeutralCaptures, abandonedWithGarrison, activeWarAfterFirstBoundary, activeWarRelations,
    yuedan: await read<Yuedan>(page, `/api/hwiha/yuedan?generalId=${generalId}`) }, null, 2), contentType: 'application/json' });
  const logs = compose(['logs', '--no-color', 'game-engine']);
  expect((logs.match(/tick failed/gi) ?? []).length, 'engine tick failed').toBe(0);
});
