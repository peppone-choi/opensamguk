import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, symlinkSync, chmodSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { validateContract, privateJson, gamePath, checkIdentity, checkCreation, checkTurn, poll,
  validateResumeAccounts, createUser,
  JourneyFailure } from './localhost-user-journey.mjs';

const nonce = '0123456789abcdef';
const contract = { nonce, origin: 'https://localhost', project: 'osglha' + nonce,
  serverId: 'lha' + nonce, worldId: 160, generation: 0, appSha: 'a'.repeat(40), dockerSha: 'b'.repeat(40),
  status: 'SMOKE_PASSED' };
const requestId = '12345678-1234-1234-1234-123456789abc';
const before = { generalId: 5, userId: '7', nationId: 0, npcState: 0, experience: 10,
  dedication: 20, turnTime: 'old', world: { tickSeconds: 120 } };
const queued = { reservation: { slot: 0, action: 'action.travel', revision: requestId } };
const after = { ...before, experience: 11, dedication: 21, turnTime: 'new', reservation: null,
  logCount: 1, personal: { requestId, inputId: 'action.travel' }, terminal: { status: 'APPLIED', ok: true } };
const fails = (fn, code) => assert.throws(fn, error => error instanceof JourneyFailure && error.code === code);

test('a public origin, foreign project, wrong world or changed source never reaches authentication', () => {
  assert.equal(validateContract(contract), contract);
  for (const change of [{ origin: 'https://sam.peppone.dev' }, { origin: 'https://localhost.attacker.test' },
    { origin: 'https://localhost:444' }, { project: 'opensamguk-qa160' }, { serverId: 'qa160' },
    { nonce: 'x' }, { worldId: 1 }, { generation: 1 }, { appSha: 'main' }, { dockerSha: 'HEAD' },
    { status: 'PREPARED' }, { status: 'UP' }]) {
    assert.throws(() => validateContract({ ...contract, ...change }), JourneyFailure);
  }
});
test('credentials require a private directory and regular private files, including on error paths', () => {
  const directory = mkdtempSync(join(tmpdir(), 'lha-journey-test-'));
  try {
    writeFileSync(join(directory, 'fixture.json'), JSON.stringify({ private: 'not-public' }), { mode: 0o600 });
    assert.deepEqual(privateJson(directory, 'fixture.json'), { private: 'not-public' });
    chmodSync(join(directory, 'fixture.json'), 0o644);
    fails(() => privateJson(directory, 'fixture.json'), 'CUSTODY_PRIVATE');
    chmodSync(join(directory, 'fixture.json'), 0o600);
    symlinkSync(join(directory, 'fixture.json'), join(directory, 'contract.json'));
    fails(() => privateJson(directory, 'contract.json'), 'CUSTODY_PRIVATE');
    fails(() => privateJson(directory, '../fixture.json'), 'CUSTODY_NAME');
    chmodSync(directory, 0o755);
    fails(() => privateJson(directory, 'fixture.json'), 'CUSTODY_PRIVATE');
  } finally { rmSync(directory, { recursive: true, force: true }); }
});
test('game reads use the negotiated server and reject absolute or header-injection paths', () => {
  assert.equal(gamePath(contract, 'front-info'), `/api/game/api/front-info?server=${contract.serverId}`);
  assert.equal(gamePath(contract, 'reserved-commands?generalId=5'),
    `/api/game/api/reserved-commands?generalId=5&server=${contract.serverId}`);
  for (const path of ['//attacker.test', 'https://attacker.test', '../auth', 'front-info\r\nCookie:x']) {
    fails(() => gamePath(contract, path), 'LOCAL_GAME_PATH');
  }
});
test('an anonymous 200 or the wrong actor, affiliation, server or generation is not a logged-in character', () => {
  const front = { result: true, general: { generalId: 5, nationId: 0 },
    global: { serverId: contract.serverId, generation: 0 } };
  checkIdentity(front, contract, 5);
  for (const value of [{ ...front, result: false }, { ...front, general: { generalId: null, nationId: 0 } },
    { ...front, general: { generalId: 6, nationId: 0 } }, { ...front, general: { generalId: 5, nationId: 1 } },
    { ...front, global: { serverId: 'qa160', generation: 0 } },
    { ...front, global: { serverId: contract.serverId, generation: 1 } }]) {
    fails(() => checkIdentity(value, contract, 5), 'FRONT_IDENTITY');
  }
});
test('HTTP acceptance cannot replace persisted normal USER ownership and the real 120-second world', () => {
  checkCreation(before, 7, 5);
  for (const value of [{ ...before, userId: '8' }, { ...before, userId: '07' }, { ...before, userId: 7 },
    { ...before, npcState: 2 },
    { ...before, nationId: 1 }, { ...before, world: { tickSeconds: 3600 } }]) {
    fails(() => checkCreation(value, 7, 5), 'PERSISTED_CREATION');
  }
  for (const userId of ['7', null, true, 0, Number.MAX_SAFE_INTEGER + 1]) {
    fails(() => checkCreation(before, userId, 5), 'PERSISTED_CREATION');
  }
});

test('resume is bound to the private fixture pair and never accepts replacement credentials', () => {
  const fixture = { user: { username: 'fixture-user', password: 'private-fixture' } };
  const accounts = [{ ...fixture.user, name: '로컬재야데스크톱' },
    { username: 'lhm0123456789', password: 'x'.repeat(32), name: '로컬재야모바일' }];
  assert.equal(validateResumeAccounts(accounts, fixture), accounts);
  for (const changed of [[], accounts.slice(0, 1),
    [{ ...accounts[0], username: 'other' }, accounts[1]],
    [{ ...accounts[0], password: 'other' }, accounts[1]],
    [accounts[0], { ...accounts[1], username: 'arbitrary-existing-user' }],
    [accounts[0], { ...accounts[1], password: '' }]]) {
    fails(() => validateResumeAccounts(changed, fixture), 'RESUME_ACCOUNTS');
  }
});

test('normal USER resume never registers or joins an already owned character', async () => {
  const writes = [];
  const front = { result: true, general: { generalId: 5, nationId: 0 },
    global: { serverId: contract.serverId, generation: 0 } };
  const response = (body, status = 200) => ({ status: () => status, json: async () => body });
  const browser = { newContext: async () => ({ close: async () => {}, request: {
    post: async url => {
      writes.push(new URL(url).pathname);
      return response({ user: { id: 7, role: 'USER' } });
    },
    get: async url => response(new URL(url).pathname === '/api/auth/me'
      ? { user: { id: 7, role: 'USER' } } : front),
  } }) };
  const account = { username: 'fixture-user', password: 'private-fixture', name: 'fixture' };
  await createUser(browser, contract, account, 'unused', 'unused', true, () => before);
  assert.deepEqual(writes, ['/api/auth/login']);
  assert.equal(account.generalId, 5);
  assert.equal(account.userId, 7);
  await assert.rejects(createUser(browser, contract, { ...account }, 'unused', 'unused', true,
    () => ({ ...before, userId: '8' })), error => error.code === 'PERSISTED_CREATION');
});

test('resume can prepare its not-yet-created second fixture through normal APIs exactly once', async () => {
  const writes = [];
  let loggedIn = false;
  let joined = false;
  const response = (body, status = 200) => ({ status: () => status, json: async () => body });
  const browser = { newContext: async () => ({ close: async () => {}, request: {
    post: async url => {
      const path = new URL(url).pathname;
      writes.push(path);
      if (path === '/api/auth/login' && !loggedIn) { loggedIn = true; return response({}, 401); }
      if (path.endsWith('/join')) { joined = true; return response({ status: 'AVAILABLE' }, 202); }
      return response({ user: { id: 7, role: 'USER' } });
    },
    get: async url => response(new URL(url).pathname === '/api/auth/me'
      ? { user: { id: 7, role: 'USER' } }
      : { result: true, general: { generalId: joined ? 5 : 0, nationId: 0 },
        global: { serverId: contract.serverId, generation: 0 } }),
  } }) };
  await createUser(browser, contract, { username: 'lhm0123456789', password: 'fixture', name: 'fixture' },
    'unused', 'unused', true, () => before);
  assert.deepEqual(writes, ['/api/auth/login', '/api/gateway/auth/register', '/api/auth/login', '/api/game/api/join']);
});

test('an unavailable login or ADMIN identity cannot trigger resume registration or join', async () => {
  for (const [status, role, code] of [[503, 'USER', 'RESUME_LOGIN'], [200, 'ADMIN', 'NORMAL_LOGIN']]) {
    const writes = [];
    const browser = { newContext: async () => ({ close: async () => {}, request: {
      post: async url => {
        writes.push(new URL(url).pathname);
        return { status: () => status, json: async () => ({ user: { id: 7, role } }) };
      },
    } }) };
    await assert.rejects(createUser(browser, contract, { username: 'fixture', password: 'fixture' },
      'unused', 'unused', true, () => before), error => error.code === code);
    assert.deepEqual(writes, ['/api/auth/login']);
  }
});
test('reservation acknowledgement alone is never execution, durable effects or idempotency evidence', () => {
  checkTurn(before, queued, after, requestId);
  for (const change of [{ experience: 10 }, { dedication: 20 }, { turnTime: 'old' }, { logCount: 0 },
    { reservation: queued.reservation }, { personal: { requestId: 'different', inputId: 'action.travel' } },
    { terminal: { status: 'PENDING', ok: true } }, { terminal: { status: 'APPLIED', ok: false } },
    { userId: 8 }, { generalId: 6 }, { nationId: 1 }]) {
    assert.throws(() => checkTurn(before, queued, { ...after, ...change }, requestId), JourneyFailure);
  }
  fails(() => checkTurn(before, { reservation: null }, after, requestId), 'PERSISTED_RESERVATION');
  fails(() => checkTurn(before, queued, after, 'header-injection'), 'REQUEST_ID');
});
test('poll failures retain failure and callback rejection rather than being silently retried', async () => {
  await assert.rejects(poll(async () => ({ status: 'PENDING' }), v => v.status === 'RESOLVED', 0),
    error => error.code === 'POLL_TIMEOUT');
  const broken = new JourneyFailure('READ_HTTP');
  await assert.rejects(poll(async () => { throw broken; }, () => true), error => error === broken);
});
