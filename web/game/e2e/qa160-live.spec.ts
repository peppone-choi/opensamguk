// Real loopback backend only. No intercepted/synthetic API or SSE responses.
import { execFileSync } from 'node:child_process';
import { lstatSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { expect, test, type Browser, type BrowserContext, type Page, type TestInfo } from '@playwright/test';

import { sanitizeEvidence, SNAPSHOT_CODES, SnapshotFailure, TurnDiagnostics } from './qa160-evidence';

const ORIGIN = 'http://127.0.0.1:18300';
const custody = resolve(process.env.E2E_QA_CUSTODY ?? '/missing-custody');
const temp = resolve(process.env.RUNNER_TEMP!);
const publicDir = resolve(process.env.E2E_QA_PUBLIC ?? '/missing-public');
if (custody !== join(temp, 'opensamguk-qa160-owner/custody') || publicDir !== join(temp, 'qa-public')) {
  throw new Error('Only this job private custody and sanitized evidence directory are accepted');
}
type Account = { nation?: string; role?: string; username: string; password: string; userId: number; generalId?: number };
type Front = { general: { generalId: number }; global: { serverId: string; generation: number; year: number; month: number; turnPhase: number } };
type Snapshot = {
  generalId: number; experience: number; dedication: number; turnTime: string;
  world: { year: number; month: number; phase: number; lastTurnTime: string };
  personal: { requestId: string | null; inputId: string | null };
  reservation?: { slot: number; action: string; revision: string } | null;
  logCount?: number;
  terminal?: { status: string; ok: boolean; version: number } | null;
};
type Ring = { result: boolean; generalId: number; slots: { turnIdx: number; action: string; revision: string }[] };
type RoleMap = { users: { generalId: number; role: string; secondaryNpcGeneralIds?: number[] }[] };

function privateJson<T>(name: string): T {
  const path = join(custody, name);
  for (const file of [custody, path]) {
    const stat = lstatSync(file);
    if (stat.isSymbolicLink() || (stat.mode & 0o077) !== 0) throw new Error('Custody must remain private');
  }
  return JSON.parse(readFileSync(path, 'utf8')) as T;
}
const accounts = privateJson<Account[]>('accounts.json');
const admin = privateJson<Account>('admin-account.json');
if (accounts.length !== 16 || new Set(accounts.map(a => a.userId)).size !== 16 ||
    accounts.some(a => a.userId === admin.userId || !Number.isInteger(a.generalId))) {
  throw new Error('Sixteen normal owned accounts and a separate ADMIN are required');
}
const roleMap = JSON.parse(readFileSync(join(publicDir, 'role-map.json'), 'utf8')) as RoleMap;

function evidence(name: string, value: unknown) {
  if (!/^(desktop|mobile)-(roles|turn-reservation)\.json$/.test(name)) throw new Error('Evidence allowlist mismatch');
  writeFileSync(join(publicDir, name), JSON.stringify(sanitizeEvidence(value, name.endsWith('-roles.json') ? 'roles' : 'turn-reservation'), null, 2) + '\n');
}

function snapshot(generalId: number, requestId?: string, diagnostic?: TurnDiagnostics): Snapshot {
  const args = ['tools/qa/hosted-candidate.py', 'snapshot', '--custody', custody, '--general-id', String(generalId)];
  if (requestId) args.push('--request-id', requestId);
  let raw: string;
  try {
    raw = execFileSync('python3', args, { cwd: resolve('../..'), encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  } catch (error) {
    if (diagnostic) {
      diagnostic.probe.snapshotExit = 'EXEC_FAIL';
      // Only this fixed error-code envelope is accepted; never publish stdout/stderr.
      try {
        const output = (error as { stdout?: string | Buffer }).stdout;
        const value = JSON.parse(String(output)) as { error?: unknown };
        if (Object.keys(value).length === 1 && SNAPSHOT_CODES.some(code => code === value.error)) {
          diagnostic.probe.snapshotExit = 'ERROR_CODE';
          diagnostic.probe.snapshotErrorCode = value.error;
        }
      } catch { /* Untrusted subprocess output is discarded. */ }
    }
    throw new SnapshotFailure();
  }
  try {
    const value = JSON.parse(raw) as Snapshot;
    if (diagnostic) diagnostic.probe.snapshotExit = 'OK';
    return value;
  } catch {
    if (diagnostic) diagnostic.probe.snapshotExit = 'PARSE_FAIL';
    throw new SnapshotFailure();
  }
}

async function login(browser: Browser, account: Account, info: TestInfo): Promise<{ context: BrowserContext; page: Page }> {
  const profile = info.project.use;
  const context = await browser.newContext({ viewport: profile.viewport, userAgent: profile.userAgent,
    deviceScaleFactor: profile.deviceScaleFactor, isMobile: profile.isMobile, hasTouch: profile.hasTouch,
    serviceWorkers: 'block' });
  const page = await context.newPage();
  // Values are never assertion operands, reporter output, screenshots, trace or storageState.
  try {
    await page.goto(ORIGIN + '/login?next=/game/qa160', { waitUntil: 'domcontentloaded' });
    const button = page.getByRole('button', { name: '로그인', exact: true });
    await expect(button).toBeEnabled();
    await page.locator('input[name="username"]').fill(account.username);
    await page.locator('input[name="password"]').fill(account.password);
    const response = page.waitForResponse(r => new URL(r.url()).pathname === '/api/auth/login' && r.request().method() === 'POST');
    await button.click();
    const status = (await response).status();
    if (status !== 200) throw new Error('Normal UI login failed');
    const cookie = (await context.cookies(ORIGIN)).find(c => c.name === 'sam_access');
    if (!cookie?.httpOnly) throw new Error('Normal httpOnly auth cookie missing');
    const meResponse = await context.request.get(ORIGIN + '/api/auth/me');
    if (meResponse.status() !== 200) throw new Error('Normal me failed');
    const me = (await meResponse.json()).user as { id: number; role: string };
    if (me.id !== account.userId || me.role !== (account === admin ? 'ADMIN' : 'USER')) throw new Error('Account identity or role mismatch');
    await page.waitForURL(url => url.origin === ORIGIN && url.pathname === '/game/qa160');
    return { context, page };
  } catch {
    await context.close();
    throw new Error('Normal UI login/me/cookie failed; private values withheld');
  }
}

async function read<T>(context: BrowserContext, path: string, diagnostic?: TurnDiagnostics): Promise<T> {
  const response = await context.request.get(ORIGIN + path);
  if (diagnostic) diagnostic.httpStatus = response.status();
  expect(response.status(), 'real backend read').toBe(200);
  return await response.json() as T;
}
const game = (path: string) => '/api/game/api/' + path + (path.includes('?') ? '&' : '?') + 'server=qa160';
const front = (context: BrowserContext, diagnostic?: TurnDiagnostics) => read<Front>(context, game('front-info'), diagnostic);
const publicClock = (f: Front) => ({ serverId: f.global.serverId, generation: f.global.generation, year: f.global.year, month: f.global.month, phase: f.global.turnPhase });
const clock = (f: Front) => JSON.stringify([f.global.year, f.global.month, f.global.turnPhase]);
const isReservation = (method: string, url: string) => method === 'POST' &&
  new URL(url).pathname.startsWith('/api/game/api/command/') && !new URL(url).pathname.endsWith('/presence');

test('실제 정상 로그인 16 USER / 별도 ADMIN / 실제 역할 read 허용과 거절', { tag: '@both' }, async ({ browser }, info) => {
  const rows: unknown[] = [];
  try {
    const anonymous = await browser.newContext();
    try {
      expect((await anonymous.request.get(ORIGIN + '/api/proxy/admin/users')).status()).toBe(401);
      expect((await anonymous.request.get(ORIGIN + game('reserved-commands?generalId=' + accounts[0].generalId))).status()).toBe(401);
    } finally { await anonymous.close(); }
    const session = await login(browser, admin, info);
    try {
      expect((await session.context.request.get(ORIGIN + '/api/proxy/admin/users')).status()).toBe(200);
    } finally { await session.context.close(); }
    for (const account of accounts) {
      const { context } = await login(browser, account, info);
      try {
        const current = await front(context);
        expect(current.general.generalId).toBe(account.generalId);
        expect(current.global.serverId).toBe('qa160');
        expect(current.global.generation).toBe(0);
        expect((await context.request.get(ORIGIN + '/api/proxy/admin/users')).status()).toBe(403);
        const own = await read<Ring>(context, game('reserved-commands?generalId=' + account.generalId));
        expect(own.result).toBe(true);
        expect(own.generalId).toBe(account.generalId);
        expect(own.slots.every(s => s.turnIdx >= 0 && s.turnIdx <= 11)).toBe(true);
        const foreign = accounts.find(a => a.userId !== account.userId)!;
        expect((await context.request.get(ORIGIN + game('reserved-commands?generalId=' + foreign.generalId))).status()).toBe(403);
        const retinue = await read<{ status: string; people: { generalId: number | null }[] }>(context, game('retinue?generalId=' + account.generalId));
        expect(retinue.status).toBe('READY');
        const graph = roleMap.users.find(g => g.generalId === account.generalId)!;
        if (account.role === 'manager') {
          const actualIds = new Set(retinue.people.map(p => p.generalId));
          expect((graph.secondaryNpcGeneralIds ?? []).length).toBeGreaterThanOrEqual(2);
          expect(graph.secondaryNpcGeneralIds!.every(id => actualIds.has(id))).toBe(true);
        }
        // Other humans and subordinate NPCs do not become this account's actor.
        const unauthorized = graph.secondaryNpcGeneralIds?.[0] ?? foreign.generalId!;
        expect((await context.request.get(ORIGIN + game('commands/travel-options?generalId=' + unauthorized))).status()).toBe(403);
        rows.push({ generalId: account.generalId, userId: account.userId, nation: account.nation,
          role: account.role, accountRole: 'USER', ownRing: 200, foreignRing: 403, adminRead: 403,
          secondaryNpcCount: graph.secondaryNpcGeneralIds?.length ?? 0, secondaryNpcActorControl: 403 });
      } finally { await context.close(); }
    }
    evidence(info.project.name + '-roles.json', { status: 'PASS', adminRead: 200, anonymousRead: 401, users: rows });
  } catch {
    evidence(info.project.name + '-roles.json', { status: 'FAIL', checkedUsers: rows });
    throw new Error('Actual role/authentication QA failed; sanitized evidence only');
  }
});

test('실제 턴 SSE 지도 무효화 / UI 예약 1회 / 엔진 처리·영속 readback', { tag: '@both' }, async ({ browser }, info) => {
  // Desktop and mobile use disjoint actors in the same owned world; retries are disabled.
  const account = accounts.find(a => a.nation === (info.project.name === 'desktop' ? 'A' : 'B') && a.role === 'general1')!;
  const { context, page } = await login(browser, account, info);
  const result: Record<string, unknown> = { status: 'FAIL', actor: account.generalId, server: 'qa160', generation: 0 };
  const diagnostic = new TurnDiagnostics();
  let submittedAt: number | undefined;
  let sseAtSubmit = 0;
  let sseTurns = 0;
  let lastSseAt: number | undefined;
  const sseIntervals: number[] = [];
  const posts: { generalId: string | null; turnIdx: string | null; action: string }[] = [];
  try {
    let optionReads = 0;
    const cdp = await context.newCDPSession(page);
    await cdp.send('Network.enable');
    cdp.on('Network.eventSourceMessageReceived', event => {
      if (event.eventName !== 'turnCompleted') return;
      sseTurns++;
      const now = Date.now();
      if (submittedAt !== undefined && lastSseAt !== undefined) {
        sseIntervals.push(Math.max(0, now - lastSseAt));
        if (sseIntervals.length > 5) sseIntervals.shift();
      }
      lastSseAt = now;
    });
    page.on('response', response => {
      if (new URL(response.url()).pathname === '/api/game/api/commands/move-options' && response.status() === 200) optionReads++;
    });
    page.on('request', request => {
      if (!isReservation(request.method(), request.url())) return;
      if (diagnostic.stage === 'S16_SUBMIT_RESPONSE' && submittedAt === undefined) {
        submittedAt = Date.now();
        sseAtSubmit = sseTurns;
      }
      const url = new URL(request.url());
      posts.push({ generalId: url.searchParams.get('generalId'), turnIdx: url.searchParams.get('turnIdx'), action: url.pathname.split('/').at(-1)! });
    });
    diagnostic.enter('S02_MOVE_FLOW');
    await page.goto(ORIGIN + '/game/qa160?do=action.move&slot=0', { waitUntil: 'domcontentloaded' });
    const flow = page.getByTestId('command-flow');
    const mapButton = flow.getByRole('button', { name: '지도에서 고르기', exact: true });
    await expect(mapButton).toBeVisible({ timeout: 60_000 });
    diagnostic.enter('S03_FRONT_IDENTITY', 'front-info');
    const before = await front(context, diagnostic);
    expect(before.general.generalId).toBe(account.generalId);
    expect(before.global.serverId).toBe('qa160');
    const oldClock = clock(before), oldReads = optionReads, oldTurns = sseTurns;
    await mapButton.click();
    const dialog = page.getByRole('dialog', { name: '목적지 지도에서 고르기', exact: true });
    await expect(dialog).toBeVisible();
    diagnostic.enter('S04_TURN_SSE');
    await expect.poll(() => diagnostic.poll(() => sseTurns), { timeout: 260_000, intervals: [1000] }).toBeGreaterThan(oldTurns);
    diagnostic.enter('S05_CLOCK_CHANGE', 'front-info');
    await expect.poll(() => diagnostic.poll(async () => clock(await front(context, diagnostic))), { timeout: 30_000 }).not.toBe(oldClock);
    diagnostic.enter('S06_MAP_INVALIDATION');
    await expect(dialog).toHaveCount(0);
    await expect.poll(() => diagnostic.poll(() => optionReads)).toBeGreaterThan(oldReads);
    await expect(mapButton).toBeVisible();
    diagnostic.enter('S07_NO_POSTS', 'front-info');
    expect(posts.length).toBe(0);
    const turn = await front(context, diagnostic);
    result.turn = { before: publicClock(before), after: publicClock(turn), realSseCount: sseTurns, optionReads, reservationPosts: 0 };
    diagnostic.enter('S10_TRAVEL_OPTIONS', 'travel-options');
    const available = await read<{ inputId: string; available: boolean }>(context, game('commands/travel-options?generalId=' + account.generalId), diagnostic);
    diagnostic.probe.travelAvailable = available.available;
    expect(available.inputId).toBe('action.travel');
    diagnostic.enter('S11_TRAVEL_AVAILABLE', 'travel-options');
    expect(available.available).toBe(true);
    diagnostic.enter('S12_RING_READ', 'reserved-commands');
    const ring = await read<Ring>(context, game('reserved-commands?generalId=' + account.generalId), diagnostic);
    expect(ring.generalId).toBe(account.generalId);
    diagnostic.probe.slot0Occupied = ring.slots.some(s => s.turnIdx === 0);
    diagnostic.probe.slot0Action = ring.slots.find(s => s.turnIdx === 0)?.action;
    diagnostic.enter('S13_SLOT0_EMPTY', 'reserved-commands');
    // Current slot only: do not reserve twelve turns ahead or overwrite progress.
    expect(ring.slots.some(s => s.turnIdx === 0)).toBe(false);
    diagnostic.enter('S14_TRAVEL_FLOW_BUTTON');
    await page.goto(ORIGIN + '/game/qa160?do=action.travel&slot=0', { waitUntil: 'domcontentloaded' });
    const button = page.getByTestId('command-flow').getByRole('button', { name: '01순에 예약', exact: true });
    diagnostic.probe.submitButtonVisible = await button.isVisible();
    await expect(button).toBeVisible();
    diagnostic.probe.submitButtonVisible = true;
    diagnostic.probe.submitButtonEnabled = await button.isEnabled();
    diagnostic.enter('S15_SNAPSHOT_BEFORE');
    const beforeAction = snapshot(account.generalId!, undefined, diagnostic);
    diagnostic.enter('S16_SUBMIT_RESPONSE', 'command-post');
    diagnostic.probe.clockAtSubmit = { serverId: 'qa160', generation: 0, year: beforeAction.world.year, month: beforeAction.world.month, phase: beforeAction.world.phase };
    const submitted = page.waitForResponse(r => isReservation(r.request().method(), r.url()));
    await button.click();
    const intake = await submitted;
    diagnostic.enter('S17_SUBMIT_202', 'command-post');
    diagnostic.httpStatus = intake.status();
    diagnostic.probe.postStatus = intake.status();
    // Best-effort code projection must not mask the original status assertion.
    try {
      const body = await intake.json() as { status?: unknown; code?: unknown };
      diagnostic.probe.postBodyStatus = body.status;
      diagnostic.probe.postBodyCode = body.code;
    } catch { /* No body text or parse error is published. */ }
    expect(intake.status()).toBe(202);
    diagnostic.enter('S18_REQUEST_ID', 'command-post');
    const accepted = await intake.json() as { requestId?: string };
    diagnostic.probe.requestIdPresent = typeof accepted.requestId === 'string' && accepted.requestId.length > 0;
    if (!accepted.requestId || !/^[A-Za-z0-9_-]{1,128}$/.test(accepted.requestId)) throw new Error('Missing real request identity');
    const requestId = accepted.requestId;
    diagnostic.enter('S19_QUEUED_SNAPSHOT');
    const queued = snapshot(account.generalId!, requestId, diagnostic);
    diagnostic.probe.queuedReservationPresent = queued.reservation != null;
    diagnostic.probe.queuedSlot = queued.reservation?.slot;
    diagnostic.probe.queuedActionMatch = queued.reservation?.action === 'action.travel';
    expect(queued.reservation?.slot).toBe(0);
    expect(queued.reservation?.action).toBe('action.travel');
    expect(typeof queued.reservation?.revision).toBe('string');
    diagnostic.enter('S20_RING_REVISION', 'reserved-commands');
    const persistedRing = await read<Ring>(context, game('reserved-commands?generalId=' + account.generalId), diagnostic);
    diagnostic.probe.ringRevisionMatch = persistedRing.slots.find(s => s.turnIdx === 0)?.revision === queued.reservation?.revision;
    expect(persistedRing.slots.find(s => s.turnIdx === 0)?.revision).toBe(queued.reservation!.revision);
    diagnostic.enter('S21_RESULT_RESOLVED', 'command-result');
    diagnostic.probe.resultPolls = 0;
    await expect.poll(() => diagnostic.poll(async () => {
      diagnostic.probe.resultPolls = Number(diagnostic.probe.resultPolls) + 1;
      const value = await read<{ status: string; ok?: boolean; phase?: string; type?: string }>(context,
        game('command/result/' + requestId), diagnostic);
      diagnostic.probe.lastResultStatus = value.status;
      diagnostic.probe.lastResultPhase = value.phase;
      diagnostic.probe.resultOk = value.ok;
      diagnostic.probe.resultType = value.type;
      return value.status;
    }), { timeout: 260_000, intervals: [500] }).toBe('RESOLVED');
    diagnostic.enter('S22_RESULT_OK', 'command-result');
    const terminal = await read<{ status: string; ok: boolean }>(context, game('command/result/' + requestId), diagnostic);
    diagnostic.probe.resultOk = terminal.ok;
    expect(terminal.ok).toBe(true);
    diagnostic.enter('S23_PERSONAL_STAMP');
    await expect.poll(() => diagnostic.poll(() => {
      const value = snapshot(account.generalId!, requestId, diagnostic).personal.requestId;
      diagnostic.probe.persisted = { personalStampMatch: value === requestId };
      return value;
    }),
      { timeout: 30_000, intervals: [1000] }).toBe(requestId);
    diagnostic.enter('S24_PERSISTED_FIELDS');
    const afterAction = snapshot(account.generalId!, requestId, diagnostic);
    diagnostic.probe.persisted = {
      personalStampMatch: afterAction.personal.requestId === requestId,
      inputIdMatch: afterAction.personal.inputId === 'action.travel',
      experienceIncreased: afterAction.experience > beforeAction.experience,
      dedicationIncreased: afterAction.dedication > beforeAction.dedication,
      turnTimeChanged: afterAction.turnTime !== beforeAction.turnTime,
      worldLastTurnChanged: afterAction.world.lastTurnTime !== beforeAction.world.lastTurnTime,
      reservationCleared: afterAction.reservation === null, logCountPositive: (afterAction.logCount ?? 0) > 0,
      terminalStatus: afterAction.terminal?.status, terminalOk: afterAction.terminal?.ok,
    };
    expect(afterAction.personal.inputId).toBe('action.travel');
    expect(afterAction.experience).toBeGreaterThan(beforeAction.experience);
    expect(afterAction.dedication).toBeGreaterThan(beforeAction.dedication);
    expect(afterAction.turnTime).not.toBe(beforeAction.turnTime);
    expect(afterAction.world.lastTurnTime).not.toBe(beforeAction.world.lastTurnTime);
    expect(afterAction.reservation).toBeNull();
    expect(afterAction.logCount).toBeGreaterThan(0);
    expect(afterAction.terminal?.status).toBe('APPLIED');
    expect(afterAction.terminal?.ok).toBe(true);
    diagnostic.enter('S25_RELOAD_READBACK', 'front-info');
    await page.reload({ waitUntil: 'domcontentloaded' });
    const reloaded = await front(context, diagnostic);
    expect(reloaded.general.generalId).toBe(account.generalId);
    expect(reloaded.global.serverId).toBe('qa160');
    const reloadedRing = await read<Ring>(context, game('reserved-commands?generalId=' + account.generalId), diagnostic);
    expect(reloadedRing.slots.some(s => s.revision === queued.reservation!.revision)).toBe(false);
    diagnostic.enter('S26_POSTS_EXACT');
    expect(posts).toEqual([{ generalId: String(account.generalId), turnIdx: '0', action: 'action.travel' }]);
    result.status = 'PASS';
    result.reservation = { requestId, before: beforeAction, queued, after: afterAction, posts, readbackAfterReload: true };
    await cdp.detach();
  } catch (error) {
    result.status = 'FAIL';
    delete result.reservation;
    // Capture the original stage/error before any optional diagnostic read.
    result.failure = diagnostic.failure(error);
    diagnostic.probe.postCount = posts.length;
    diagnostic.probe.sseTurnsAfterSubmit = submittedAt === undefined ? 0 : sseTurns - sseAtSubmit;
    diagnostic.probe.sseIntervalsMs = [...sseIntervals];
    // Optional read-only clock probe is bounded and cannot replace the original failure.
    try {
      const response = await context.request.get(ORIGIN + game('front-info'), { timeout: 5000 });
      if (response.status() === 200) diagnostic.probe.clockAtFailure = publicClock(await response.json() as Front);
    } catch { /* A missing clock is preferable to fabricated/stale evidence. */ }
    result.probe = diagnostic.probe;
    throw new Error('Actual turn/reservation/engine QA failed; sanitized evidence only');
  } finally {
    evidence(info.project.name + '-turn-reservation.json', result);
    await context.close();
  }
});
