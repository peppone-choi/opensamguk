// Public evidence is data, never an exception message, HTTP body, URL or log text.
export const STAGES = [
  'S01_NETWORK_SETUP', 'S02_MOVE_FLOW', 'S03_FRONT_IDENTITY', 'S04_TURN_SSE',
  'S05_CLOCK_CHANGE', 'S05_WORLD_TICK_PERSISTED', 'S06_MAP_INVALIDATION', 'S07_NO_POSTS',
  'S10_TRAVEL_OPTIONS', 'S11_TRAVEL_AVAILABLE', 'S12_RING_READ', 'S13_SLOT0_EMPTY',
  'S14_TRAVEL_FLOW_BUTTON', 'S15_SNAPSHOT_BEFORE', 'S16_SUBMIT_RESPONSE', 'S17_SUBMIT_202',
  'S18_REQUEST_ID', 'S19_QUEUED_SNAPSHOT', 'S20_RING_REVISION', 'S21_RESULT_RESOLVED',
  'S22_RESULT_OK', 'S23_PERSONAL_STAMP', 'S24_PERSISTED_FIELDS', 'S24_WORLD_CLOCK_CONTRACT', 'S25_RELOAD_READBACK', 'S26_POSTS_EXACT',
] as const;
export type Stage = typeof STAGES[number];
const ERROR_CLASSES = ['POLL_TIMEOUT', 'LOCATOR_TIMEOUT', 'EXPECT_MISMATCH', 'HTTP_STATUS', 'SNAPSHOT_EXEC', 'NAVIGATION', 'INTERNAL'] as const;
type ErrorClass = typeof ERROR_CLASSES[number];
const MATCHERS = ['toBe', 'toBeVisible', 'toBeGreaterThan', 'toBeNull', 'toEqual', 'toHaveCount'] as const;
const ENDPOINTS = ['front-info', 'travel-options', 'reserved-commands', 'command-post', 'command-result'] as const;
type Endpoint = typeof ENDPOINTS[number];
export const SNAPSHOT_CODES = ['CUSTODY', 'OWNER', 'INVALID_ARG', 'SQL_FAILED'] as const;
export class SnapshotFailure extends Error {
  constructor() { super('Snapshot failed; private values withheld'); }
}

type ErrorMetadata = { errorClass: ErrorClass; matcher: string | null };
class PollCallbackFailure extends Error {
  constructor(readonly metadata: ErrorMetadata) { super('Poll callback failed; private values withheld'); }
}
const POLL_MATCHERS: Partial<Record<Stage, string>> = {
  S04_TURN_SSE: 'toBeGreaterThan', S05_CLOCK_CHANGE: 'toBe', S06_MAP_INVALIDATION: 'toBeGreaterThan',
  S21_RESULT_RESOLVED: 'toBe', S23_PERSONAL_STAMP: 'toBe',
};

export function classifyError(error: unknown, stage: Stage, fromPollCallback = false): ErrorMetadata {
  // Never inspect message/stack, matcher operands, response bodies or stdout here.
  if (error instanceof PollCallbackFailure) return error.metadata;
  if (error instanceof SnapshotFailure) return { errorClass: 'SNAPSHOT_EXEC', matcher: null };
  const row = error as { constructor?: { name?: string }; matcherResult?: { name?: unknown } } | null;
  const name = row?.matcherResult?.name;
  const matcher = MATCHERS.find(m => m === name) ?? null;
  if (matcher === 'toBeVisible' || matcher === 'toHaveCount') return { errorClass: 'LOCATOR_TIMEOUT', matcher };
  if (matcher) {
    const poll = !fromPollCallback && POLL_MATCHERS[stage] === matcher;
    return { errorClass: poll ? 'POLL_TIMEOUT' : 'EXPECT_MISMATCH', matcher };
  }
  // Playwright 1.55.1 pollMatcher throws a plain Error without matcherResult on timeout.
  // Callback errors are tagged at the callback boundary, never inferred from private text.
  if (!fromPollCallback && error instanceof Error && row?.constructor?.name === 'Error' && POLL_MATCHERS[stage]) {
    return { errorClass: 'POLL_TIMEOUT', matcher: POLL_MATCHERS[stage]! };
  }
  if (!fromPollCallback && row?.constructor?.name === 'TimeoutError') return { errorClass: stage === 'S16_SUBMIT_RESPONSE'
    ? 'POLL_TIMEOUT' : 'NAVIGATION', matcher: null };
  return { errorClass: 'INTERNAL', matcher: null };
}

export class TurnDiagnostics {
  stage: Stage = 'S01_NETWORK_SETUP';
  endpoint: Endpoint | null = null;
  httpStatus: number | null = null;
  started = Date.now();
  probe: Record<string, unknown> = {};
  enter(stage: Stage, endpoint: Endpoint | null = null) {
    this.stage = stage; this.endpoint = endpoint; this.httpStatus = null; this.started = Date.now();
  }
  async poll<T>(callback: () => T | Promise<T>): Promise<T> {
    try { return await callback(); } catch (error) {
      // Preserve immediate callback failure/exit semantics without retaining its message/cause.
      throw new PollCallbackFailure(classifyError(error, this.stage, true));
    }
  }
  failure(error: unknown) {
    const classified = classifyError(error, this.stage);
    let errorClass: ErrorClass = classified.errorClass;
    if (this.stage === 'S23_PERSONAL_STAMP' && ['ERROR_CODE', 'EXEC_FAIL', 'PARSE_FAIL'].some(exit => exit === this.probe.snapshotExit)) {
      errorClass = 'SNAPSHOT_EXEC';
    }
    if (this.httpStatus !== null && (this.httpStatus < 200 || this.httpStatus >= 300
        || (this.stage === 'S17_SUBMIT_202' && this.httpStatus !== 202))) errorClass = 'HTTP_STATUS';
    return { stage: this.stage, ...classified, errorClass,
      endpoint: this.endpoint, httpStatus: this.httpStatus, stageElapsedMs: Math.max(0, Date.now() - this.started) };
  }
}

/** Observe S16 without changing click/response deadlines, matching, retries or failure identity. */
export async function observeSubmit<T>(
  diagnostic: TurnDiagnostics,
  waitForResponse: () => Promise<T>,
  click: () => Promise<unknown>,
): Promise<T> {
  diagnostic.probe.submitPhase = 'REGISTER_RESPONSE';
  diagnostic.probe.submitClickCompleted = false;
  diagnostic.probe.submitResponseObserved = false;
  // Observe rejection immediately while the click is pending. No raw error is put in a probe.
  // The click still has precedence, exactly as the sequential click -> response boundary.
  const submitted = waitForResponse().then(
    value => { diagnostic.probe.submitResponseObserved = true; return { ok: true as const, value }; },
    (error: unknown) => ({ ok: false as const, error }),
  );
  diagnostic.probe.submitPhase = 'CLICK_PENDING';
  await click();
  diagnostic.probe.submitClickCompleted = true;
  diagnostic.probe.submitPhase = 'RESPONSE_PENDING';
  const outcome = await submitted;
  if (outcome.ok === false) throw outcome.error;
  diagnostic.probe.submitPhase = 'RESPONSE_RECEIVED';
  return outcome.value;
}

type Rule = ((value: unknown) => boolean) | { [key: string]: Rule } | readonly [Rule, number];
const integer: Rule = v => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0;
const boolean: Rule = v => typeof v === 'boolean';
const enumeration = (...values: readonly unknown[]): Rule => v => values.includes(v);
const pattern = (regex: RegExp): Rule => v => typeof v === 'string' && regex.test(v);
const nullable = (rule: Rule): Rule => ({ $nullable: rule });
const action = enumeration('action.travel', 'action.move');
// Closed vocabularies intentionally stricter than a free-form identifier regex.
const phase = enumeration('reservationAccepted', 'reservationCancelled', 'queueMutation');
const resultType = enumeration('executionApplied', 'executionRejected', 'reservationAccepted', 'reservationCancelled', 'queueMutation');
const terminalStatus = enumeration('APPLIED', 'REJECTED', 'FAILED');
const clock: Rule = { serverId: enumeration('qa160'), generation: integer, year: integer, month: integer, phase: integer };
const failure: Rule = { stage: enumeration(...STAGES), errorClass: enumeration(...ERROR_CLASSES),
  matcher: nullable(enumeration(...MATCHERS)), endpoint: nullable(enumeration(...ENDPOINTS)),
  httpStatus: nullable(v => typeof v === 'number' && Number.isInteger(v) && v >= 100 && v <= 599), stageElapsedMs: integer };
const persisted: Rule = { personalStampMatch: boolean, inputIdMatch: boolean, experienceIncreased: boolean,
  dedicationIncreased: boolean, turnTimeChanged: boolean, worldLastTurnChanged: boolean, reservationCleared: boolean,
  logCountPositive: boolean, terminalStatus, terminalOk: boolean,
  worldClockBranch: enumeration('UNCHANGED', 'ADVANCED', 'INVALID'), worldClockOk: boolean };
const probe: Rule = { worldTick: { clockAdvanced: boolean, lastTurnChanged: boolean }, travelAvailable: boolean, slot0Occupied: boolean, slot0Action: action,
  submitPhase: enumeration('REGISTER_RESPONSE', 'CLICK_PENDING', 'RESPONSE_PENDING', 'RESPONSE_RECEIVED'),
  submitClickCompleted: boolean, submitResponseObserved: boolean,
  submitButtonVisible: boolean, submitButtonEnabled: boolean, postCount: integer, postStatus: integer,
  postBodyStatus: enumeration('AVAILABLE', 'BLOCKED', 'UNKNOWN'),
  postBodyCode: enumeration('BLOCKED', 'UNAUTHORIZED', 'FORBIDDEN', 'INVALID_COMMAND', 'INVALID_ARGUMENT', 'NOT_FOUND', 'INTERNAL_ERROR', 'POLICY_UNAVAILABLE', 'STATE_UNAVAILABLE', 'WRONG_RULE_PROFILE', 'NOT_DELIVERED', 'INVALID_TURN_SLOT', 'INVALID_REQUEST',
    'MALFORMED_INPUT_ID', 'UNKNOWN_INPUT', 'INVALID_INPUT_CHANNEL', 'INVALID_INPUT', 'ACTOR_NOT_FOUND',
    'POSITION_UNAVAILABLE', 'BATTLE_PENDING', 'TRAINING_MAXED', 'ALREADY_HEALTHY', 'ALREADY_PROCESSED'),
  requestIdPresent: boolean, queuedReservationPresent: boolean, queuedSlot: integer, queuedActionMatch: boolean,
  ringRevisionMatch: boolean, resultPolls: integer, lastResultStatus: enumeration('PENDING', 'RESOLVED'),
  lastResultPhase: phase, resultOk: boolean, resultType, sseTurnsAfterSubmit: integer,
  sseIntervalsMs: [integer, 5], clockAtSubmit: clock, clockAtFailure: clock,
  snapshotExit: enumeration('OK', 'EXEC_FAIL', 'PARSE_FAIL', 'ERROR_CODE'), snapshotErrorCode: enumeration(...SNAPSHOT_CODES), persisted };
const role: Rule = { generalId: integer, userId: integer, nation: enumeration('A', 'B', 'C', 'free'),
  role: enumeration('lord', 'manager', 'general1', 'general2', 'wanderer1', 'wanderer2', 'wanderer3', 'wanderer4'), accountRole: enumeration('USER'),
  ownRing: enumeration(200), foreignRing: enumeration(403), adminRead: enumeration(403),
  secondaryNpcCount: integer, secondaryNpcActorControl: enumeration(403) };
const turn: Rule = { before: clock, after: clock, realSseCount: integer, optionReads: integer, reservationPosts: enumeration(0) };
// Existing PASS witnesses remain unchanged for valid input. They are never admitted on FAIL.
// The real API request_id and V77 reservation_revision are UUIDs, not arbitrary identifiers.
const identity = pattern(/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/);
const timestamp = pattern(/^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:?\d{2})?$/);
const snapshot: Rule = { generalId: integer, cityId: integer, experience: integer, dedication: integer, turnTime: timestamp,
  world: { year: integer, month: integer, phase: integer, lastTurnTime: timestamp },
  personal: { requestId: nullable(identity), inputId: nullable(action) },
  reservation: v => v === null, logCount: integer,
  terminal: nullable({ status: terminalStatus, ok: boolean, version: integer }) };
const queuedSnapshot: Rule = { ...(snapshot as Record<string, Rule>), reservation: { slot: integer, action, revision: identity } };
const reservation: Rule = { requestId: identity, before: snapshot, queued: queuedSnapshot, after: snapshot,
  posts: [{ generalId: pattern(/^[1-9][0-9]*$/), turnIdx: enumeration('0'), action }, 1], readbackAfterReload: enumeration(true) };

export function sanitizeEvidence(value: unknown, kind: 'roles' | 'turn-reservation'): Record<string, unknown> {
  let dropped = 0;
  const clean = (input: unknown, rule: Rule): unknown => {
    if (typeof rule === 'function') { if (rule(input)) return input; dropped++; return undefined; }
    if (Array.isArray(rule)) {
      if (!Array.isArray(input)) { dropped++; return undefined; }
      const [item, max] = rule as readonly [Rule, number];
      dropped += Math.max(0, input.length - max);
      return input.slice(0, max).map(v => clean(v, item)).filter(v => v !== undefined);
    }
    if (Object.hasOwn(rule, '$nullable')) return input === null ? null : clean(input, (rule as Record<string, Rule>).$nullable);
    if (input === null || typeof input !== 'object' || Array.isArray(input)) { dropped++; return undefined; }
    const out: Record<string, unknown> = {};
    for (const [key, entry] of Object.entries(input)) {
      if (entry === undefined) continue;
      if (!Object.hasOwn(rule, key)) { dropped++; continue; }
      const result = clean(entry, (rule as Record<string, Rule>)[key]);
      if (result !== undefined) out[key] = result;
    }
    return out;
  };
  const pass = (value as { status?: unknown } | null)?.status === 'PASS';
  const schema: Rule = kind === 'roles'
    ? { status: enumeration('PASS', 'FAIL'), adminRead: enumeration(200), anonymousRead: enumeration(401), users: [role, 16], checkedUsers: [role, 16] }
    : { status: enumeration('PASS', 'FAIL'), actor: integer, server: enumeration('qa160'), generation: integer, turn,
      ...(pass ? { reservation } : { failure, probe }) };
  const out = clean(value, schema) as Record<string, unknown> | undefined;
  const result = out ?? {};
  if (dropped) result.sanitizerDropped = dropped;
  return result;
}

// Persisted SQL clocks and bracketing front reads, not SSE counts, decide this contract.
type WorldDate = { year: number; month: number; phase: number };
type PersistedWorldClock = WorldDate & { lastTurnTime: unknown };
type FrontWorldClock = WorldDate & { serverId: string; generation: number };
const compareWorldDate = (a: WorldDate, b: WorldDate) => a.year - b.year || a.month - b.month || a.phase - b.phase;
const validWorldDate = (value: WorldDate) => [value.year, value.month, value.phase].every(Number.isSafeInteger);
const hasLastTurnTime = (value: PersistedWorldClock) => typeof value.lastTurnTime === 'string' && value.lastTurnTime.trim().length > 0;

export function checkWorldTickPersisted(before: PersistedWorldClock, after: PersistedWorldClock, frontAfter: FrontWorldClock) {
  const clockAdvanced = validWorldDate(before) && validWorldDate(after) && compareWorldDate(after, before) > 0;
  const lastTurnChanged = hasLastTurnTime(before) && hasLastTurnTime(after) && after.lastTurnTime !== before.lastTurnTime;
  return { clockAdvanced, lastTurnChanged,
    ok: clockAdvanced && lastTurnChanged && validWorldDate(frontAfter) && compareWorldDate(after, frontAfter) >= 0 };
}

export function checkWorldClockContract(before: PersistedWorldClock, after: PersistedWorldClock,
  frontBefore: FrontWorldClock, frontAfter: FrontWorldClock, identity: FrontWorldClock) {
  if (!validWorldDate(before) || !validWorldDate(after) || !hasLastTurnTime(before) || !hasLastTurnTime(after)
      || compareWorldDate(after, before) < 0) return { worldClockBranch: 'INVALID', worldClockOk: false } as const;
  const advanced = compareWorldDate(after, before) > 0;
  const sameWorld = [identity, frontBefore, frontAfter].every(f => f.serverId === 'qa160'
    && Number.isSafeInteger(f.generation) && f.generation >= 0 && f.generation === identity.generation);
  // One SQL read must match an endpoint and lie between them; do not retry away a mismatch.
  const frontMatches = validWorldDate(frontBefore) && validWorldDate(frontAfter)
    && compareWorldDate(frontBefore, after) <= 0 && compareWorldDate(after, frontAfter) <= 0
    && (compareWorldDate(after, frontBefore) === 0 || compareWorldDate(after, frontAfter) === 0);
  return { worldClockBranch: advanced ? 'ADVANCED' : 'UNCHANGED',
    worldClockOk: sameWorld && frontMatches && (advanced
      ? after.lastTurnTime !== before.lastTurnTime : after.lastTurnTime === before.lastTurnTime) };
}
