// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { classifyError, sanitizeEvidence, SnapshotFailure, STAGES, TurnDiagnostics } from '../e2e/qa160-evidence';

const clock = { serverId: 'qa160', generation: 0, year: 190, month: 1, phase: 3 };
const failure = { stage: 'S21_RESULT_RESOLVED', errorClass: 'POLL_TIMEOUT', matcher: 'toBe',
  endpoint: 'command-result', httpStatus: 200, stageElapsedMs: 260_000 };
const base = { status: 'FAIL', actor: 123, server: 'qa160', generation: 0, failure };
const secretValues = ['username-private', 'password-private', 'sam_access=secret', 'Bearer private',
  'eyJhbGciOiJIUzI1NiJ9.fake.signature', '한글 서버 reason 비밀', 'http://host/?token=private',
  'SELECT private FROM users', '<html>private</html>', 'PASSWORD', 'private.secret', 'token'];

it.each(STAGES)('records exactly the entered enum stage %s', stage => {
  const diagnostic = new TurnDiagnostics();
  diagnostic.enter(stage);
  const value = sanitizeEvidence({ ...base, failure: diagnostic.failure(new Error('private')) }, 'turn-reservation');
  expect((value.failure as { stage: string }).stage).toBe(stage);
});

describe('closed allowlist boundary', () => {
  it.each(secretValues)('never releases hostile free text (sample %#)', secret => {
    const value = sanitizeEvidence({ ...base, username: secret, password: secret, message: secret, stack: secret,
      authorization: secret, token: secret, requestId: secret, reservation: { requestId: secret },
      failure: { stage: secret, errorClass: secret, matcher: secret, endpoint: secret,
        httpStatus: secret, stageElapsedMs: secret, message: secret },
      probe: { travelAvailable: secret, slot0Action: secret, postBodyStatus: secret, postBodyCode: secret,
        lastResultPhase: secret, resultType: secret, requestIdPresent: secret, snapshotErrorCode: secret,
        snapshotExit: secret, clockAtFailure: { ...clock, serverId: secret, year: secret, token: secret },
        persisted: { terminalStatus: secret, terminalOk: secret, requestId: secret },
        body: secret, raw: secret, error: secret, sql: secret, requestId: secret } }, 'turn-reservation');
    expect(JSON.stringify(value)).not.toContain(secret);
    expect(value.sanitizerDropped).toBeGreaterThan(20);
  });

  it('counts dropped unknown keys and invalid enum values without echoing them', () => {
    expect(sanitizeEvidence({ status: 'FAIL', unexpected: 'secret', failure: { ...failure, stage: 'unknown' },
      probe: { resultOk: true, snapshotExit: 'OTHER', postCount: -1 } }, 'turn-reservation'))
      .toEqual({ status: 'FAIL', failure: { ...failure, stage: undefined }, probe: { resultOk: true }, sanitizerDropped: 4 });
  });

  it('keeps only bounded integer SSE intervals, not NaN/infinity/fractions or oversized arrays', () => {
    const value = sanitizeEvidence({ ...base, probe: { sseIntervalsMs: [10, NaN, Infinity, -1, 1.5, 99],
      postCount: Number.MAX_SAFE_INTEGER + 1, resultPolls: 1 } }, 'turn-reservation');
    expect(value.probe).toEqual({ sseIntervalsMs: [10], resultPolls: 1 });
    expect(value.sanitizerDropped).toBe(6);
  });

  it('cannot leak a request identity on FAIL even inside a valid reservation witness', () => {
    const value = sanitizeEvidence({ ...base, reservation: { requestId: 'private-request-identity' },
      probe: { requestId: 'private-request-identity', requestIdPresent: true } }, 'turn-reservation');
    expect(value.probe).toEqual({ requestIdPresent: true });
    expect(JSON.stringify(value)).not.toContain('private-request-identity');
    expect(value.sanitizerDropped).toBe(2);
  });

  it('does not serialize prototype keys or user-supplied toJSON hooks', () => {
    const value = sanitizeEvidence(JSON.parse('{"status":"FAIL","__proto__":{"message":"private"},"constructor":"private","toJSON":"private"}'), 'turn-reservation');
    expect(value).toEqual({ status: 'FAIL', sanitizerDropped: 3 });
    expect(sanitizeEvidence(null, 'turn-reservation')).toEqual({ sanitizerDropped: 1 });
  });
});

it.each(['INVALID_INPUT', 'ACTOR_NOT_FOUND', 'POSITION_UNAVAILABLE', 'BATTLE_PENDING', 'ALREADY_PROCESSED'])
  ('retains known PersonalAdmission rejection code %s without free-form reason', code => {
    const value = sanitizeEvidence({ ...base, probe: { postBodyStatus: 'BLOCKED', postBodyCode: code,
      reason: 'private server reason' } }, 'turn-reservation');
    expect(value.probe).toEqual({ postBodyStatus: 'BLOCKED', postBodyCode: code });
    expect(value.sanitizerDropped).toBe(1);
  });

it.each(secretValues)('rejects free-text secrets in PASS identity positions (sample %#)', secret => {
  const value = sanitizeEvidence({ status: 'PASS', reservation: { requestId: secret,
    before: { personal: { requestId: secret } }, queued: { reservation: { revision: secret } },
    after: { personal: { requestId: secret } } } }, 'turn-reservation');
  expect(JSON.stringify(value)).not.toContain(secret);
  expect(value.sanitizerDropped).toBe(4);
});

it('preserves all allowed diagnostic probes, including fixed lifecycle enums', () => {
  const probe = { travelAvailable: true, slot0Occupied: false, slot0Action: 'action.travel',
    submitButtonVisible: true, submitButtonEnabled: true, postCount: 1, postStatus: 202,
    postBodyStatus: 'AVAILABLE', postBodyCode: 'INVALID_TURN_SLOT', requestIdPresent: true,
    queuedReservationPresent: true, queuedSlot: 0, queuedActionMatch: true, ringRevisionMatch: true,
    resultPolls: 520, lastResultStatus: 'PENDING', lastResultPhase: 'reservationAccepted',
    resultOk: false, resultType: 'executionRejected', sseTurnsAfterSubmit: 0, sseIntervalsMs: [100, 200],
    clockAtSubmit: clock, clockAtFailure: clock, snapshotExit: 'ERROR_CODE', snapshotErrorCode: 'OWNER',
    persisted: { personalStampMatch: true, inputIdMatch: true, experienceIncreased: true, dedicationIncreased: true,
      turnTimeChanged: true, worldLastTurnChanged: true, reservationCleared: true, logCountPositive: true,
      terminalStatus: 'APPLIED', terminalOk: true } };
  expect(sanitizeEvidence({ ...base, probe }, 'turn-reservation')).toEqual({ ...base, probe });
});

it('preserves the original complete PASS witness and emits no diagnostic fields', () => {
  const snapshot = { generalId: 123, cityId: 2, experience: 10, dedication: 2,
    turnTime: '2026-10-16T01:02:03.123+00:00',
    world: { year: 190, month: 1, phase: 3, lastTurnTime: '2026-10-16T01:02:03Z' },
    personal: { requestId: null, inputId: null } };
  const requestId = '00000000-0000-0000-0000-000000000001';
  const pass = { status: 'PASS', actor: 123, server: 'qa160', generation: 0,
    turn: { before: clock, after: clock, realSseCount: 1, optionReads: 2, reservationPosts: 0 },
    reservation: { requestId, before: snapshot,
      queued: { ...snapshot, reservation: { slot: 0, action: 'action.travel', revision: '00000000-0000-0000-0000-000000000002' }, logCount: 0, terminal: null },
      after: { ...snapshot, personal: { requestId, inputId: 'action.travel' }, reservation: null, logCount: 1,
        terminal: { status: 'APPLIED', ok: true, version: 1 } },
      posts: [{ generalId: '123', turnIdx: '0', action: 'action.travel' }], readbackAfterReload: true } };
  expect(sanitizeEvidence(pass, 'turn-reservation')).toEqual(pass);
  expect(sanitizeEvidence({ ...pass, failure, probe: {} }, 'turn-reservation')).not.toHaveProperty('failure');
  expect(sanitizeEvidence({ ...pass, failure, probe: {} }, 'turn-reservation')).not.toHaveProperty('probe');
});

it('preserves the sixteen existing public role row forms, including all four wanderers', () => {
  const users = [...['A', 'B', 'C'].flatMap(nation => ['lord', 'manager', 'general1', 'general2'].map(role => ({ nation, role }))),
    ...[1, 2, 3, 4].map(n => ({ nation: 'free', role: 'wanderer' + n }))].map((row, n) => ({ ...row,
    generalId: n + 1, userId: n + 1, accountRole: 'USER', ownRing: 200, foreignRing: 403, adminRead: 403,
    secondaryNpcCount: 0, secondaryNpcActorControl: 403 }));
  const pass = { status: 'PASS', adminRead: 200, anonymousRead: 401, users };
  expect(sanitizeEvidence(pass, 'roles')).toEqual(pass);
  const fail = { status: 'FAIL', checkedUsers: users };
  expect(sanitizeEvidence(fail, 'roles')).toEqual(fail);
});

it('classifies from fixed matcher/constructor metadata without reading private error text', () => {
  const error = { matcherResult: { name: 'toBe', actual: 'private', expected: 'private' },
    get message(): never { throw new Error('must not inspect'); }, get stack(): never { throw new Error('must not inspect'); } };
  expect(classifyError(error, 'S21_RESULT_RESOLVED')).toEqual({ errorClass: 'POLL_TIMEOUT', matcher: 'toBe' });
  expect(classifyError(error, 'S11_TRAVEL_AVAILABLE')).toEqual({ errorClass: 'EXPECT_MISMATCH', matcher: 'toBe' });
  class TimeoutError extends Error {}
  expect(classifyError({ matcherResult: { name: 'toBeVisible' } }, 'S14_TRAVEL_FLOW_BUTTON').errorClass).toBe('LOCATOR_TIMEOUT');
  expect(classifyError(new TimeoutError(), 'S16_SUBMIT_RESPONSE').errorClass).toBe('POLL_TIMEOUT');
  expect(classifyError(new TimeoutError(), 'S25_RELOAD_READBACK').errorClass).toBe('NAVIGATION');
  expect(classifyError(new SnapshotFailure(), 'S19_QUEUED_SNAPSHOT').errorClass).toBe('SNAPSHOT_EXEC');
  expect(classifyError({ matcherResult: { name: 'private' } }, 'S18_REQUEST_ID')).toEqual({ errorClass: 'INTERNAL', matcher: null });
  const diagnostic = new TurnDiagnostics();
  diagnostic.enter('S17_SUBMIT_202', 'command-post'); diagnostic.httpStatus = 200;
  expect(diagnostic.failure(error).errorClass).toBe('HTTP_STATUS');
  diagnostic.enter('S23_PERSONAL_STAMP'); diagnostic.probe.snapshotExit = 'ERROR_CODE';
  expect(diagnostic.failure(error).errorClass).toBe('SNAPSHOT_EXEC');
  diagnostic.enter('S10_TRAVEL_OPTIONS', 'travel-options'); diagnostic.httpStatus = 403;
  expect(diagnostic.failure(error).errorClass).toBe('HTTP_STATUS');
});

it('keeps stage order, sanitized writes and unchanged failure exit wired into the live harness', () => {
  const source = readFileSync(new URL('../e2e/qa160-live.spec.ts', import.meta.url), 'utf8');
  const stages = [...source.matchAll(/diagnostic\.enter\('(S\d+_[A-Z0-9_]+)'/g)].map(match => match[1]);
  expect(stages).toEqual(STAGES.slice(1));
  expect(source).toContain('JSON.stringify(sanitizeEvidence(value,');
  expect(source).toContain("throw new Error('Actual turn/reservation/engine QA failed; sanitized evidence only')");
  expect(source).not.toMatch(/error\.(message|stack)|console\.(log|error)/);
});
