#!/usr/bin/env node
// Real loopback UI and persisted witnesses. Never write auth responses or traces.
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { lstatSync, readFileSync, writeFileSync, mkdirSync, realpathSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const SHA = /^[a-f0-9]{40}$/;
const UUID = /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/;
export class JourneyFailure extends Error {
  constructor(code) { super(code); this.code = code; }
}
export function requireThat(value, code) {
  if (!value) throw new JourneyFailure(code);
}
export function validateContract(row) {
  requireThat(/^[a-f0-9]{16}$/.test(row.nonce), 'CONTRACT_NONCE');
  requireThat(row.origin === 'https://localhost' && row.project === `osglha${row.nonce}`
    && row.serverId === `lha${row.nonce}`, 'CONTRACT_ISOLATION');
  requireThat(row.worldId === 160 && row.generation === 0 && SHA.test(row.appSha)
    && SHA.test(row.dockerSha), 'CONTRACT_SOURCE');
  requireThat(row.status === 'SMOKE_PASSED', 'STACK_NOT_READY');
  return row;
}
export function privateJson(directory, name) {
  requireThat(/^[a-z-]+\.json$/.test(name), 'CUSTODY_NAME');
  for (const path of [directory, join(directory, name)]) {
    const stat = lstatSync(path);
    requireThat(!stat.isSymbolicLink() && stat.uid === process.getuid()
      && (stat.mode & 0o077) === 0, 'CUSTODY_PRIVATE');
  }
  requireThat(lstatSync(directory).isDirectory() && lstatSync(join(directory, name)).isFile(), 'CUSTODY_TYPE');
  return JSON.parse(readFileSync(join(directory, name), 'utf8'));
}
export function gamePath(contract, path) {
  requireThat(/^[a-z][a-z0-9/?=&_-]*$/i.test(path), 'LOCAL_GAME_PATH');
  return `/api/game/api/${path}${path.includes('?') ? '&' : '?'}server=${contract.serverId}`;
}
export function checkIdentity(front, contract, generalId) {
  requireThat(front.result === true && front.general.generalId === generalId
    && front.general.nationId === 0 && front.global.serverId === contract.serverId
    && front.global.generation === 0, 'FRONT_IDENTITY');
}
export function checkCreation(snapshot, userId, generalId) {
  requireThat(Number.isSafeInteger(userId) && userId > 0 && typeof snapshot.userId === 'string'
    && snapshot.generalId === generalId && snapshot.userId === String(userId)
    && snapshot.nationId === 0 && snapshot.npcState === 0
    && snapshot.world.tickSeconds === 120, 'PERSISTED_CREATION');
}
export function checkTurn(before, queued, after, requestId) {
  requireThat(UUID.test(requestId), 'REQUEST_ID');
  requireThat(queued.reservation?.slot === 0 && queued.reservation.action === 'action.travel'
    && UUID.test(queued.reservation.revision), 'PERSISTED_RESERVATION');
  requireThat(after.generalId === before.generalId && after.userId === before.userId
    && after.nationId === 0 && after.npcState === 0, 'TURN_OWNER');
  requireThat(after.personal?.requestId === requestId && after.personal.inputId === 'action.travel'
    && after.terminal?.status === 'APPLIED' && after.terminal.ok === true, 'PERSISTED_TERMINAL');
  requireThat(after.experience > before.experience && after.dedication > before.dedication
    && after.turnTime !== before.turnTime && after.reservation === null
    && after.logCount > 0, 'PERSISTED_EFFECTS');
}
export async function poll(callback, accept, timeout = 60_000) {
  const end = Date.now() + timeout;
  while (true) {
    const value = await callback();
    if (accept(value)) return value;
    requireThat(Date.now() < end, 'POLL_TIMEOUT');
    await new Promise(done => setTimeout(done, 500));
  }
}
function snapshot(harness, custody, generalId, requestId) {
  requireThat(Number.isSafeInteger(generalId) && generalId > 0
    && (!requestId || UUID.test(requestId)), 'SNAPSHOT_ARGUMENT');
  const args = [harness, 'snapshot', '--custody', custody, '--general-id', String(generalId)];
  if (requestId) args.push('--request-id', requestId);
  try {
    return JSON.parse(execFileSync('python3', args, { encoding: 'utf8', timeout: 30_000,
      stdio: ['ignore', 'pipe', 'ignore'], maxBuffer: 64 * 1024 }));
  } catch { throw new JourneyFailure('SNAPSHOT_FAILED'); }
}
async function get(context, origin, path) {
  const response = await context.request.get(origin + path, { maxRedirects: 0 });
  requireThat(response.status() === 200, 'READ_HTTP');
  return response.json();
}
async function post(context, origin, path, data, status = 200) {
  const response = await context.request.post(origin + path, { data, maxRedirects: 0 });
  requireThat(response.status() === status, 'WRITE_HTTP');
  return response.json();
}
async function bootstrap(browser, contract, fixture) {
  const context = await browser.newContext({ ignoreHTTPSErrors: true, serviceWorkers: 'block' });
  try {
    const auth = await post(context, contract.origin, '/api/gateway/auth/login', fixture.admin);
    requireThat(auth.user?.role === 'ADMIN' && typeof auth.accessToken === 'string', 'BOOTSTRAP_LOGIN');
    const headers = { Authorization: `Bearer ${auth.accessToken}` };
    const me = await context.request.get(contract.origin + '/api/gateway/auth/me', { headers, maxRedirects: 0 });
    requireThat(me.status() === 200 && (await me.json()).role === 'ADMIN', 'BOOTSTRAP_ME');
    for (const scope of ['allow_join', 'allow_login']) {
      const response = await context.request.post(contract.origin + '/api/gateway/admin/system/' + scope,
        { headers, data: { value: true }, maxRedirects: 0 });
      requireThat(response.status() === 200, 'BOOTSTRAP_POLICY');
    }
    const policy = await context.request.get(contract.origin + '/api/gateway/admin/users', { headers, maxRedirects: 0 });
    requireThat(policy.status() === 200, 'BOOTSTRAP_READBACK');
    const body = await policy.json();
    requireThat(body.allowJoin === true && body.allowLogin === true, 'BOOTSTRAP_READBACK');
  } finally { await context.close(); }
}
export function validateResumeAccounts(accounts, fixture) {
  requireThat(Array.isArray(accounts) && accounts.length === 2
    && accounts[0]?.username === fixture.user.username && accounts[0]?.password === fixture.user.password
    && accounts[0]?.name === '로컬재야데스크톱' && accounts[1]?.name === '로컬재야모바일'
    && /^lhm[a-f0-9]{10}$/.test(accounts[1]?.username)
    && accounts[1].username !== accounts[0].username
    && typeof accounts[1]?.password === 'string' && accounts[1].password.length >= 24, 'RESUME_ACCOUNTS');
  return accounts;
}
export async function createUser(browser, contract, account, harness, custody, resume = false, readSnapshot = snapshot) {
  const context = await browser.newContext({ ignoreHTTPSErrors: true, serviceWorkers: 'block' });
  try {
    const credentials = { username: account.username, password: account.password };
    let login;
    let expectedUserId;
    if (resume) {
      const response = await context.request.post(contract.origin + '/api/auth/login',
        { data: credentials, maxRedirects: 0 });
      requireThat([200, 401].includes(response.status()), 'RESUME_LOGIN');
      if (response.status() === 200) login = await response.json();
    }
    if (!login) {
      const auth = await post(context, contract.origin, '/api/gateway/auth/register',
        { ...credentials, nickname: account.name });
      requireThat(auth.user?.role === 'USER', 'REGISTER_USER');
      expectedUserId = auth.user.id;
      login = await post(context, contract.origin, '/api/auth/login', credentials);
    } else { expectedUserId = login.user?.id; }
    requireThat(login.user?.role === 'USER', 'NORMAL_LOGIN');
    const me = await get(context, contract.origin, '/api/auth/me');
    requireThat(me.user?.role === 'USER' && me.user.id === expectedUserId, 'NORMAL_USER_ME');
    account.userId = me.user.id;
    let front = resume ? await get(context, contract.origin, gamePath(contract, 'front-info')) : null;
    if (!(Number.isInteger(front?.general?.generalId) && front.general.generalId > 0)) {
      const joined = await post(context, contract.origin, gamePath(contract, 'join'),
        { name: account.name, leadership: 50, strength: 50, intel: 50, politics: 50, charm: 50,
          character: 'Random', pic: false }, 202);
      requireThat(['AVAILABLE', 'ACCEPTED'].includes(joined.status), 'JOIN_INTAKE');
      front = await poll(() => get(context, contract.origin, gamePath(contract, 'front-info')),
        value => Number.isInteger(value.general?.generalId) && value.general.generalId > 0);
    }
    account.generalId = front.general.generalId;
    checkIdentity(front, contract, account.generalId);
    checkCreation(readSnapshot(harness, custody, account.generalId), account.userId, account.generalId);
  } finally { await context.close(); }
}
async function loginPage(browser, contract, account, profile, expect) {
  const context = await browser.newContext({ ignoreHTTPSErrors: true, serviceWorkers: 'block', ...profile });
  try {
    const page = await context.newPage();
    await page.goto(contract.origin + '/login', { waitUntil: 'domcontentloaded' });
    await page.locator('input[name="username"]').fill(account.username);
    await page.locator('input[name="password"]').fill(account.password);
    const accepted = page.waitForResponse(r => new URL(r.url()).origin === contract.origin
      && new URL(r.url()).pathname === '/api/auth/login' && r.request().method() === 'POST');
    await page.getByRole('button', { name: '로그인', exact: true }).click();
    requireThat((await accepted).status() === 200, 'UI_LOGIN');
    await page.waitForURL(contract.origin + '/lobby');
    const cookie = (await context.cookies(contract.origin)).find(c => c.name === 'sam_access');
    requireThat(cookie?.httpOnly === true && cookie.secure === true, 'HTTPONLY_COOKIE');
    const me = await get(context, contract.origin, '/api/auth/me');
    requireThat(me.user?.id === account.userId && me.user.role === 'USER', 'UI_USER_ME');
    await expect(page.getByRole('region', { name: '서버', exact: true })).toBeVisible();
    return { context, page };
  } catch {
    await context.close();
    throw new JourneyFailure('UI_LOGIN_LOBBY');
  }
}
async function entry(page, context, contract, account, expect, output, label) {
  const destination = `/game/${contract.serverId}`;
  const link = page.getByRole('link', { name: '입장', exact: true });
  await expect(link).toHaveCount(1, { timeout: 60_000 });
  const target = new URL(await link.getAttribute('href'), contract.origin);
  requireThat(target.origin === contract.origin
    && target.pathname.replace(/\/$/, '') === destination, 'LOBBY_DESTINATION');
  await page.screenshot({ path: join(output, `${label}-lobby.png`) });
  await link.click();
  await expect(page.getByTestId('war-room-layout')).toBeVisible({ timeout: 60_000 });
  await expect(page.getByTestId('game-entry-screen')).toHaveCount(0);
  checkIdentity(await get(context, contract.origin, gamePath(contract, 'front-info')), contract, account.generalId);
  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('war-room-layout')).toBeVisible();
  checkIdentity(await get(context, contract.origin, gamePath(contract, 'front-info')), contract, account.generalId);
  await page.screenshot({ path: join(output, `${label}-warroom.png`) });
  await page.goto(contract.origin + destination + '/court/realm', { waitUntil: 'domcontentloaded' });
  await expect(page.getByText('소속 세력이 없습니다', { exact: false })).toBeVisible();
}
async function travel(page, context, contract, account, harness, custody, expect) {
  const path = value => gamePath(contract, value);
  const gid = account.generalId;
  const available = await get(context, contract.origin, path(`commands/travel-options?generalId=${gid}`));
  requireThat(available.inputId === 'action.travel' && available.available === true, 'TRAVEL_OPTIONS');
  const ring = await get(context, contract.origin, path(`reserved-commands?generalId=${gid}`));
  requireThat(ring.generalId === gid && !ring.slots.some(s => s.turnIdx === 0), 'EMPTY_SLOT');
  const posts = [];
  const observe = request => {
    const url = new URL(request.url());
    if (url.origin === contract.origin && request.method() === 'POST'
      && /\/api\/game\/api\/command\//.test(url.pathname)) posts.push({
      action: url.pathname.split('/').at(-1), generalId: url.searchParams.get('generalId'),
      turnIdx: url.searchParams.get('turnIdx'), emptyBody: request.postData() === '{}' });
  };
  page.on('request', observe);
  try {
    await page.goto(contract.origin + `/game/${contract.serverId}?do=action.travel&slot=1`,
      { waitUntil: 'domcontentloaded' });
    const button = page.getByTestId('command-flow').getByRole('button', { name: '01순에 예약', exact: true });
    await expect(button).toBeVisible({ timeout: 60_000 });
    const before = snapshot(harness, custody, gid);
    const pending = page.waitForResponse(r => r.request().method() === 'POST'
      && new URL(r.url()).origin === contract.origin
      && new URL(r.url()).pathname.endsWith('/command/action.travel'));
    await button.click();
    const response = await pending;
    requireThat(response.status() === 202, 'TRAVEL_202');
    const accepted = await response.json();
    requireThat(UUID.test(accepted.requestId), 'REQUEST_ID');
    const requestId = accepted.requestId;
    const queued = snapshot(harness, custody, gid, requestId);
    requireThat(queued.reservation?.slot === 0 && queued.reservation.action === 'action.travel'
      && UUID.test(queued.reservation.revision), 'PERSISTED_RESERVATION');
    const persisted = await get(context, contract.origin, path(`reserved-commands?generalId=${gid}`));
    requireThat(persisted.slots.find(s => s.turnIdx === 0)?.revision === queued.reservation.revision, 'RING_REVISION');
    const terminal = await poll(() => get(context, contract.origin, path('command/result/' + requestId)),
      value => value.status === 'RESOLVED', 260_000);
    requireThat(terminal.ok === true, 'EXECUTION_OK');
    await poll(() => snapshot(harness, custody, gid, requestId),
      value => value.personal?.requestId === requestId, 30_000);
    const after = snapshot(harness, custody, gid, requestId);
    checkTurn(before, queued, after, requestId);
    requireThat(posts.length === 1 && posts[0].action === 'action.travel'
      && posts[0].generalId === String(gid) && posts[0].turnIdx === '0' && posts[0].emptyBody, 'SINGLE_RESERVATION');
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('war-room-layout')).toBeVisible();
    checkIdentity(await get(context, contract.origin, path('front-info')), contract, gid);
    const reloaded = await get(context, contract.origin, path(`reserved-commands?generalId=${gid}`));
    requireThat(!reloaded.slots.some(s => s.turnIdx === 0), 'RELOAD_RING');
    const reconnected = snapshot(harness, custody, gid, requestId);
    checkTurn(before, queued, reconnected, requestId);
    // Project only fixed public witnesses. Auth data and arbitrary server JSON never enter evidence.
    return { requestId, reservationRevision: queued.reservation.revision, postCount: 1, intakeStatus: 202,
      terminalStatus: 'RESOLVED', terminalOk: true, experienceBefore: before.experience,
      experienceAfter: after.experience, dedicationBefore: before.dedication, dedicationAfter: after.dedication,
      personalStampMatch: true, slotConsumed: true, logCount: after.logCount, reloadMatches: true, nationId: 0 };
  } finally { page.off('request', observe); }
}
function args(argv) {
  const values = {};
  for (let i = 0; i < argv.length; i += 2) {
    requireThat(['--custody', '--harness', '--output', '--playwright-root', '--account-mode'].includes(argv[i])
      && argv[i + 1] && !values[argv[i]], 'ARGUMENTS');
    if (argv[i] === '--account-mode') {
      requireThat(['fresh', 'resume'].includes(argv[i + 1]), 'ACCOUNT_MODE');
      values[argv[i]] = argv[i + 1];
    } else { values[argv[i]] = resolve(argv[i + 1]); }
  }
  requireThat(values['--custody'] && values['--harness'] && values['--output'], 'ARGUMENTS');
  return values;
}
export async function main(argv) {
  const options = args(argv);
  const custody = options['--custody'];
  const resume = options['--account-mode'] === 'resume';
  const contract = validateContract(privateJson(custody, 'contract.json'));
  const fixture = privateJson(custody, 'fixture.json');
  requireThat(fixture.admin?.username && fixture.admin.password && fixture.user?.username
    && fixture.user.password && fixture.admin.username !== fixture.user.username, 'FIXTURE_ACCOUNTS');
  const harness = realpathSync(options['--harness']);
  const harnessStat = lstatSync(options['--harness']);
  requireThat(!harnessStat.isSymbolicLink() && harnessStat.isFile(), 'HARNESS_FILE');
  const output = options['--output'];
  requireThat(output !== custody && !output.startsWith(custody + '/'), 'EVIDENCE_SEPARATE');
  mkdirSync(output, { mode: 0o700 });
  const require = createRequire(join(options['--playwright-root'] ?? join(ROOT, 'web/game'), 'package.json'));
  const { chromium, expect, devices } = require('@playwright/test');
  requireThat(require('@playwright/test/package.json').version === '1.55.1', 'BROWSER_DEPENDENCY_VERSION');
  const evidence = { status: 'FAIL', appSha: contract.appSha, dockerSha: contract.dockerSha,
    testerSha: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: ROOT, encoding: 'utf8' }).trim(),
    runnerSha256: createHash('sha256').update(readFileSync(fileURLToPath(import.meta.url))).digest('hex'),
    harnessSha256: createHash('sha256').update(readFileSync(harness)).digest('hex'),
    project: contract.project, serverId: contract.serverId, worldId: 160, generation: 0,
    accountMode: resume ? 'resume' : 'fresh',
    startedUtc: new Date().toISOString(), bootstrap: 'NOT_RUN', profiles: [] };
  let browser;
  try {
    // The owning Docker chat runs smoke. Every snapshot rechecks the resource ownership read-only.
    browser = await chromium.launch({ headless: true });
    await bootstrap(browser, contract, fixture);
    evidence.bootstrap = 'PASS';
    const accounts = resume ? validateResumeAccounts(privateJson(custody, 'journey-accounts.json'), fixture)
      : [{ ...fixture.user, name: '로컬재야데스크톱' }, {
      username: 'lhm' + randomBytes(5).toString('hex'), password: randomBytes(24).toString('base64url'),
      name: '로컬재야모바일' }];
    if (!resume) writeFileSync(join(custody, 'journey-accounts.json'), JSON.stringify(accounts), { flag: 'wx', mode: 0o600 });
    for (const [index, account] of accounts.entries()) {
      const label = index ? 'mobile' : 'desktop';
      const result = { profile: label, creation: 'NOT_RUN', 'US-WAND-01': 'NOT_RUN', 'US-WAND-02': 'NOT_RUN' };
      evidence.profiles.push(result);
      result.creation = 'FAIL';
      await createUser(browser, contract, account, harness, custody, resume);
      result.creation = 'API_SETUP_PASS';
      const profile = index ? devices['iPhone 13'] : { viewport: { width: 1440, height: 1000 } };
      const { context, page } = await loginPage(browser, contract, account, profile, expect);
      try {
        result['US-WAND-01'] = 'FAIL';
        await entry(page, context, contract, account, expect, output, label);
        const reconnect = await loginPage(browser, contract, account,
          index ? { viewport: { width: 1440, height: 1000 } } : devices['iPhone 13'], expect);
        try {
          await entry(reconnect.page, reconnect.context, contract, account, expect, output, label + '-reconnect');
        } finally { await reconnect.context.close(); }
        // Reconnect the same actor using a new context and normal login, never storageState.
        result['US-WAND-01'] = 'PASS';
        result['US-WAND-02'] = 'FAIL';
        result.turn = await travel(page, context, contract, account, harness, custody, expect);
        result['US-WAND-02'] = 'PASS';
        await page.screenshot({ path: join(output, `${label}-after-turn.png`) });
      } finally { await context.close(); }
    }
    requireThat(evidence.profiles.length === 2 && evidence.profiles.every(row => row['US-WAND-01'] === 'PASS'
      && row['US-WAND-02'] === 'PASS'), 'COMPLETE_PROFILES');
    evidence.status = 'PASS';
  } catch (error) {
    evidence.failureCode = error instanceof JourneyFailure ? error.code : 'RUNTIME_FAILURE';
  } finally {
    await browser?.close();
    evidence.finishedUtc = new Date().toISOString();
    writeFileSync(join(output, 'result.json'), JSON.stringify(evidence, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
  }
  process.stdout.write(JSON.stringify({ status: evidence.status, failureCode: evidence.failureCode ?? null,
    result: join(output, 'result.json') }) + '\n');
  return evidence.status === 'PASS' ? 0 : 1;
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).then(code => { process.exitCode = code; }, () => {
    process.stderr.write('Local journey refused; private values withheld.\n'); process.exitCode = 1;
  });
}
