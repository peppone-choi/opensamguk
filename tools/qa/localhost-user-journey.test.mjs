import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, symlinkSync, chmodSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { validateContract, privateJson, gamePath, checkIdentity, checkCreation, checkTurn, poll,
  JourneyFailure } from './localhost-user-journey.mjs';

const nonce = '0123456789abcdef';
const contract = { nonce, origin: 'https://localhost', project: 'osglha' + nonce,
  serverId: 'lha' + nonce, worldId: 160, generation: 0, appSha: 'a'.repeat(40), dockerSha: 'b'.repeat(40),
  status: 'SMOKE_PASSED' };
const requestId = '12345678-1234-1234-1234-123456789abc';
const before = { generalId: 5, userId: 7, nationId: 0, npcState: 0, experience: 10,
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
  for (const value of [{ ...before, userId: 8 }, { ...before, npcState: 2 },
    { ...before, nationId: 1 }, { ...before, world: { tickSeconds: 3600 } }]) {
    fails(() => checkCreation(value, 7, 5), 'PERSISTED_CREATION');
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
