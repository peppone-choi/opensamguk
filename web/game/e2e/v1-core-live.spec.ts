import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type APIRequestContext, type APIResponse, type BrowserContext, type Locator, type Page, type Response } from '@playwright/test';

type Json = Record<string, unknown> | unknown[] | string | number | boolean | null;
type ResponseEvidence = { url: string; status: number; body: string };

const gatewayUrl = process.env.E2E_GATEWAY_URL ?? 'http://localhost:3000';
const gameUrl = process.env.E2E_GAME_URL ?? 'http://localhost:3001';
const engineHealthUrl = process.env.E2E_GAME_ENGINE_HEALTH_URL ?? 'http://localhost:8082/actuator/health';
const engineStatusUrl = process.env.E2E_GAME_ENGINE_STATUS_URL ?? new URL('/admin/turn-daemon/status', engineHealthUrl).toString();
const artifactRoot = process.env.E2E_ARTIFACT_DIR ?? join(process.cwd(), 'test-results');
const repoRoot = process.env.E2E_REPO_ROOT ?? join(process.cwd(), '../..');
const operationalSmokeEnabled = process.env.E2E_OPERATIONAL_SMOKE === 'true';

type JsonRecord = Record<string, unknown>;
type CommandResultObservation = {
  observedAt: string;
  status: number;
  body: string;
  data: Json;
};
type TickSnapshot = {
  observedAt: string;
  successfulTicks: number;
  failedTicks: number;
  consecutiveFailures: number;
  tickSeconds: number;
  lastTurnTime: string;
  nextRunTime: string;
};
type BrowserOperationalEvidence = {
  sseOpenedAt: number[];
  turnCompleted: Array<{ at: number; data: string }>;
  frontInfoFetches: Array<{ at: number; url: string }>;
};
type CommandInboxWakePublication = {
  rowCount: number;
  redisWakePublishedAt: string;
  rawSqlRow: string;
};
type RedisConsumerGroupState = {
  name: string;
  pending: number;
  lastDeliveredId: string;
};
type RedisConsumerState = {
  name: string;
  pending: number;
};
type RedisWakeIngressEvidence = {
  observedAt: string;
  requestId: string;
  streamKey: string;
  entryId: string;
  payloadSha256: string;
  commandInbox: CommandInboxWakePublication;
  group: RedisConsumerGroupState;
  consumer: RedisConsumerState;
};
type RedisWakeAcknowledgementEvidence = {
  observedAt: string;
  requestId: string;
  streamKey: string;
  entryId: string;
  payloadSha256: string;
  group: RedisConsumerGroupState;
  consumer: RedisConsumerState;
  pendingEntryCount: number;
  entryStillPresent: boolean;
};

function composeProjectNameFromEnv(): string {
  const value = process.env.E2E_COMPOSE_PROJECT_NAME;
  if (!value || !/^[a-z0-9][a-z0-9_-]*$/.test(value)) {
    throw new Error('E2E_COMPOSE_PROJECT_NAME must be a Docker-safe Compose project name');
  }
  return value;
}

const composeProjectName = composeProjectNameFromEnv();

function safeName(value: string): string {
  return value.replace(/[^a-zA-Z0-9._-]+/g, '-').replace(/^-+|-+$/g, '');
}

function json(value: unknown): string {
  return JSON.stringify(value, null, 2) ?? 'null';
}

function parseJson(text: string): Json {
  try {
    return JSON.parse(text) as Json;
  } catch {
    return text;
  }
}

function requireRecord(value: unknown, label: string): JsonRecord {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${label} must be a JSON object`);
  }
  return value as JsonRecord;
}

function requireNumber(record: JsonRecord, key: string, label: string): number {
  const value = record[key];
  const parsed = typeof value === 'number' ? value : typeof value === 'string' ? Number(value) : Number.NaN;
  if (!Number.isFinite(parsed)) {
    throw new Error(`${label}.${key} must be numeric`);
  }
  return parsed;
}

function requireString(record: JsonRecord, key: string, label: string): string {
  const value = record[key];
  if (typeof value !== 'string' || value.length === 0) {
    throw new Error(`${label}.${key} must be a non-empty string`);
  }
  return value;
}

function instantMillis(value: string, label: string): number {
  const parsed = Date.parse(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`${label} must be an ISO timestamp, got ${value}`);
  }
  return parsed;
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

function writeArtifact(name: string, value: unknown): string {
  mkdirSync(artifactRoot, { recursive: true });
  const path = join(artifactRoot, safeName(name));
  writeFileSync(path, typeof value === 'string' ? value : json(value), 'utf8');
  return path;
}

type ResponseLike = Pick<Response, 'status' | 'text'> | Pick<APIResponse, 'status' | 'text'>;

async function responseBody(response: ResponseLike): Promise<{ status: number; body: string; data: Json }> {
  const body = await response.text().catch(() => '');
  return { status: response.status(), body, data: parseJson(body) };
}

async function statusOrUnavailable(url: string): Promise<number> {
  try {
    return (await fetch(url)).status;
  } catch {
    return 0;
  }
}

async function apiGet(request: APIRequestContext, path: string): Promise<{ response: APIResponse; data: Json; body: string }> {
  const response = await request.get(`${gameUrl}${path}`);
  const result = await responseBody(response);
  return { response, data: result.data, body: result.body };
}

async function apiPost(
  request: APIRequestContext,
  path: string,
  payload: unknown,
): Promise<{ response: APIResponse; data: Json; body: string }> {
  const response = await request.post(`${gameUrl}${path}`, { data: payload });
  const result = await responseBody(response);
  return { response, data: result.data, body: result.body };
}

function requestIdOf(value: Json): string | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const id = (value as Record<string, unknown>).requestId;
  return typeof id === 'string' && id.length > 0 ? id : null;
}

function commandCodeOf(value: unknown): string | null {
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = commandCodeOf(item);
      if (found) return found;
    }
    return null;
  }
  if (!value || typeof value !== 'object') return null;
  const record = value as Record<string, unknown>;
  if (typeof record.value === 'string' && record.value.trim()) {
    const reqArg = record.reqArg;
    if ((reqArg === false || reqArg == null) && record.possible !== false) return record.value;
  }
  for (const child of Object.values(record)) {
    const found = commandCodeOf(child);
    if (found) return found;
  }
  return null;
}

async function pollCommandResult(
  request: APIRequestContext,
  requestId: string,
  timeoutMs = Number(process.env.E2E_COMMAND_TIMEOUT_MS ?? 30_000),
): Promise<{ status: number; body: string; data: Json }> {
  const deadline = Date.now() + timeoutMs;
  let latest = { status: 0, body: '', data: null as Json };
  while (Date.now() < deadline) {
    const result = await apiGet(request, `/api/game/api/command/result/${encodeURIComponent(requestId)}`);
    latest = { status: result.response.status(), body: result.body, data: result.data };
    if (result.data && typeof result.data === 'object' && !Array.isArray(result.data)) {
      if ((result.data as Record<string, unknown>).status === 'RESOLVED') return latest;
    }
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
  return latest;
}

async function pollCommandResultPhase(
  request: APIRequestContext,
  requestId: string,
  expectedType: string,
  timeoutMs: number,
): Promise<CommandResultObservation> {
  const deadline = Date.now() + timeoutMs;
  let latest: CommandResultObservation = {
    observedAt: new Date().toISOString(),
    status: 0,
    body: '',
    data: null,
  };
  while (Date.now() < deadline) {
    const result = await apiGet(request, `/api/game/api/command/result/${encodeURIComponent(requestId)}`);
    latest = {
      observedAt: new Date().toISOString(),
      status: result.response.status(),
      body: result.body,
      data: result.data,
    };
    if (result.data && typeof result.data === 'object' && !Array.isArray(result.data)) {
      const record = result.data as JsonRecord;
      if (expectedType === 'reservationAccepted') {
        if (record.status === 'PENDING' && record.phase === expectedType) return latest;
      } else if (record.status === 'RESOLVED' && record.type === expectedType) {
        return latest;
      }
    }
    await sleep(250);
  }
  throw new Error(`request ${requestId} did not reach ${expectedType}; latest=${latest.body}`);
}

function findCommandEntry(value: unknown, code: string): JsonRecord | null {
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = findCommandEntry(item, code);
      if (found) return found;
    }
    return null;
  }
  if (!value || typeof value !== 'object') return null;
  const record = value as JsonRecord;
  if (record.value === code) return record;
  for (const child of Object.values(record)) {
    const found = findCommandEntry(child, code);
    if (found) return found;
  }
  return null;
}

async function daemonStatus(): Promise<{ observedAt: string; status: number; body: string; data: Json }> {
  const response = await fetch(engineStatusUrl);
  const body = await response.text();
  const data = parseJson(body);
  if (!response.ok) {
    throw new Error(`daemon status returned ${response.status}: ${body}`);
  }
  return { observedAt: new Date().toISOString(), status: response.status, body, data };
}

function requireOperationalStreamContext(): { worldId: string; streamKey: string; group: string; consumer: string } {
  const worldId = process.env.E2E_OPENSAMGUK_WORLD_ID;
  if (!worldId || !/^[1-9][0-9]*$/.test(worldId)) {
    throw new Error('E2E_OPENSAMGUK_WORLD_ID must be a positive integer for operational Redis observation');
  }
  const profile = process.env.E2E_TURN_PROFILE_NAME;
  if (!profile || !/^[A-Za-z0-9:_-]+$/.test(profile)) {
    throw new Error('E2E_TURN_PROFILE_NAME must be a safe non-empty profile name for operational Redis observation');
  }
  return {
    worldId,
    streamKey: `sammo:${profile}:w${worldId}:turn-daemon:commands`,
    group: 'game-engine',
    consumer: `world-${worldId}`,
  };
}

function composeExec(service: string, command: string[], environment: Record<string, string> = {}): string {
  const args = ['compose', '--project-name', composeProjectName, '--env-file', '/dev/null', 'exec', '-T'];
  for (const [key, value] of Object.entries(environment)) {
    args.push('-e', `${key}=${value}`);
  }
  args.push(service, ...command);
  try {
    return String(execFileSync('docker', args, { cwd: repoRoot, encoding: 'utf8', stdio: 'pipe', timeout: 15_000 }));
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    throw new Error(`read-only Compose exec for ${service} failed: ${detail}`);
  }
}

function redisJson(command: string[]): Json {
  const output = composeExec('redis', ['redis-cli', '--json', ...command]).trim();
  const parsed = parseJson(output);
  if (typeof parsed === 'string') {
    throw new Error(`redis-cli ${command[0]} did not return JSON: ${parsed}`);
  }
  return parsed;
}

function requireArray(value: unknown, label: string): unknown[] {
  if (!Array.isArray(value)) {
    throw new Error(`${label} must be a JSON array`);
  }
  return value;
}

function redisKeyValueRecord(value: unknown, label: string): JsonRecord {
  if (!Array.isArray(value)) return requireRecord(value, label);
  if (value.length % 2 !== 0) {
    throw new Error(`${label} must contain an even number of Redis key/value items`);
  }
  const record: JsonRecord = {};
  for (let index = 0; index < value.length; index += 2) {
    const key = value[index];
    if (typeof key !== 'string' || key.length === 0) {
      throw new Error(`${label} key at index ${index} must be a non-empty string`);
    }
    record[key] = value[index + 1];
  }
  return record;
}

function redisInfoRecords(value: Json, label: string): JsonRecord[] {
  return requireArray(value, label).map((entry, index) => redisKeyValueRecord(entry, `${label}[${index}]`));
}

function readCommandInboxWakePublication(requestId: string, worldId: string): CommandInboxWakePublication {
  if (!/^[1-9][0-9]*$/.test(worldId)) {
    throw new Error('command_inbox observation requires a positive integer world id');
  }
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(requestId)) {
    throw new Error('command_inbox observation requires a canonical UUID request id');
  }
  const query = [
    'psql -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -F "|"',
    '-c "SELECT count(*), COALESCE(bool_or(redis_wake_published_at IS NOT NULL), false),',
    "COALESCE(max(redis_wake_published_at)::text, '') FROM command_inbox",
    `WHERE world_id = ${worldId} AND request_id = '${requestId}';"`,
  ].join(' ');
  const rows = composeExec('postgres', ['sh', '-ceu', query]).trim().split(/\r?\n/).filter(Boolean);
  if (rows.length !== 1) {
    throw new Error(`command_inbox wake query for ${requestId} returned ${rows.length} rows`);
  }
  const [rowCountText, publishedText, publishedAt] = rows[0].split('|');
  const rowCount = Number(rowCountText);
  if (!Number.isInteger(rowCount) || rowCount < 0 || (publishedText !== 't' && publishedText !== 'f') || publishedAt == null) {
    throw new Error(`command_inbox wake query for ${requestId} returned an invalid row`);
  }
  return {
    rowCount,
    redisWakePublishedAt: publishedText === 't' ? publishedAt : '',
    rawSqlRow: rows[0],
  };
}

function redisCommandEntries(streamKey: string, requestId: string): Array<{ entryId: string; payloadSha256: string }> {
  const rawEntries = requireArray(redisJson(['XRANGE', streamKey, '-', '+']), 'XRANGE');
  const matches: Array<{ entryId: string; payloadSha256: string }> = [];
  for (const [index, rawEntry] of rawEntries.entries()) {
    const entry = requireArray(rawEntry, `XRANGE[${index}]`);
    if (entry.length !== 2 || typeof entry[0] !== 'string') {
      throw new Error(`XRANGE[${index}] must be [entryId, fields]`);
    }
    const fields = redisKeyValueRecord(entry[1], `XRANGE[${index}].fields`);
    const payload = requireString(fields, 'payload', `XRANGE[${index}].fields`);
    const envelope = requireRecord(parseJson(payload), `XRANGE[${index}].payload`);
    if (requireString(envelope, 'requestId', `XRANGE[${index}].payload`) === requestId) {
      matches.push({
        entryId: entry[0],
        payloadSha256: createHash('sha256').update(payload).digest('hex'),
      });
    }
  }
  return matches;
}

function redisGroupAndConsumer(
  streamKey: string,
  groupName: string,
  consumerName: string,
): { group: RedisConsumerGroupState; consumer: RedisConsumerState } {
  const groups = redisInfoRecords(redisJson(['XINFO', 'GROUPS', streamKey]), 'XINFO GROUPS');
  const groupRecord = groups.find((entry) => entry.name === groupName);
  if (!groupRecord) {
    throw new Error(`XINFO GROUPS did not expose ${groupName} for ${streamKey}`);
  }
  const consumers = redisInfoRecords(redisJson(['XINFO', 'CONSUMERS', streamKey, groupName]), 'XINFO CONSUMERS');
  const consumerRecord = consumers.find((entry) => entry.name === consumerName);
  if (!consumerRecord) {
    throw new Error(`XINFO CONSUMERS did not expose ${consumerName} for ${streamKey}/${groupName}`);
  }
  return {
    group: {
      name: requireString(groupRecord, 'name', 'XINFO GROUPS group'),
      pending: requireNumber(groupRecord, 'pending', 'XINFO GROUPS group'),
      lastDeliveredId: requireString(groupRecord, 'last-delivered-id', 'XINFO GROUPS group'),
    },
    consumer: {
      name: requireString(consumerRecord, 'name', 'XINFO CONSUMERS consumer'),
      pending: requireNumber(consumerRecord, 'pending', 'XINFO CONSUMERS consumer'),
    },
  };
}

function pendingEntryCount(streamKey: string, groupName: string, entryId: string): number {
  const rows = requireArray(redisJson(['XPENDING', streamKey, groupName, entryId, entryId, '10']), 'XPENDING');
  return rows.filter((row, index) => {
    const fields = requireArray(row, `XPENDING[${index}]`);
    if (typeof fields[0] !== 'string') {
      throw new Error(`XPENDING[${index}] entry id must be a string`);
    }
    return fields[0] === entryId;
  }).length;
}

function compareRedisStreamIds(left: string, right: string): number {
  const parse = (value: string): [bigint, bigint] => {
    const match = /^([0-9]+)-([0-9]+)$/.exec(value);
    if (!match) throw new Error(`invalid Redis stream id ${value}`);
    return [BigInt(match[1]), BigInt(match[2])];
  };
  const [leftMillis, leftSequence] = parse(left);
  const [rightMillis, rightSequence] = parse(right);
  if (leftMillis !== rightMillis) return leftMillis > rightMillis ? 1 : -1;
  if (leftSequence === rightSequence) return 0;
  return leftSequence > rightSequence ? 1 : -1;
}

async function observeRedisWakeIngress(requestId: string): Promise<RedisWakeIngressEvidence> {
  const context = requireOperationalStreamContext();
  const deadline = Date.now() + Number(process.env.E2E_OPERATIONAL_REDIS_INGRESS_TIMEOUT_MS ?? 15_000);
  let latest = 'no observation attempted';
  let latestObservation: JsonRecord = {
    requestId,
    streamKey: context.streamKey,
    status: 'not-observed',
  };
  while (Date.now() < deadline) {
    try {
      const commandInbox = readCommandInboxWakePublication(requestId, context.worldId);
      const entries = redisCommandEntries(context.streamKey, requestId);
      latestObservation = {
        observedAt: new Date().toISOString(),
        requestId,
        streamKey: context.streamKey,
        commandInbox,
        matchingStreamEntries: entries,
      };
      const consumerState = redisGroupAndConsumer(context.streamKey, context.group, context.consumer);
      if (commandInbox.rowCount === 1 && commandInbox.redisWakePublishedAt && entries.length === 1) {
        return {
          observedAt: new Date().toISOString(),
          requestId,
          streamKey: context.streamKey,
          entryId: entries[0].entryId,
          payloadSha256: entries[0].payloadSha256,
          commandInbox,
          ...consumerState,
        };
      }
      latest = `commandInbox rows=${commandInbox.rowCount} published=${Boolean(commandInbox.redisWakePublishedAt)} streamEntries=${entries.length}`;
    } catch (error) {
      latest = error instanceof Error ? error.message : String(error);
      latestObservation = {
        observedAt: new Date().toISOString(),
        requestId,
        streamKey: context.streamKey,
        error: latest,
      };
    }
    await sleep(200);
  }
  writeArtifact('operational-redis-wake-ingress-observation.json', latestObservation);
  throw new Error(`Redis wake ingress was not observed for ${requestId}: ${latest}`);
}

async function observeRedisWakeAcknowledgement(
  ingress: RedisWakeIngressEvidence,
): Promise<RedisWakeAcknowledgementEvidence> {
  const context = requireOperationalStreamContext();
  const deadline = Date.now() + Number(process.env.E2E_OPERATIONAL_REDIS_ACK_TIMEOUT_MS ?? 15_000);
  let latest = 'no observation attempted';
  while (Date.now() < deadline) {
    try {
      const entries = redisCommandEntries(context.streamKey, ingress.requestId);
      const sameEntry = entries.length === 1 && entries[0].entryId === ingress.entryId && entries[0].payloadSha256 === ingress.payloadSha256;
      const consumerState = redisGroupAndConsumer(context.streamKey, context.group, context.consumer);
      const pendingCount = pendingEntryCount(context.streamKey, context.group, ingress.entryId);
      if (sameEntry && compareRedisStreamIds(consumerState.group.lastDeliveredId, ingress.entryId) >= 0 && pendingCount === 0) {
        return {
          observedAt: new Date().toISOString(),
          requestId: ingress.requestId,
          streamKey: context.streamKey,
          entryId: ingress.entryId,
          payloadSha256: ingress.payloadSha256,
          ...consumerState,
          pendingEntryCount: pendingCount,
          entryStillPresent: true,
        };
      }
      latest = `sameEntry=${sameEntry} lastDeliveredId=${consumerState.group.lastDeliveredId} pendingEntryCount=${pendingCount}`;
    } catch (error) {
      latest = error instanceof Error ? error.message : String(error);
    }
    await sleep(200);
  }
  throw new Error(`Redis wake acknowledgement was not observed for ${ingress.requestId}: ${latest}`);
}

function tickSnapshot(statusResponse: { observedAt: string; data: Json }): TickSnapshot {
  const status = requireRecord(statusResponse.data, 'daemonStatus');
  const clock = requireRecord(status.clock, 'daemonStatus.clock');
  return {
    observedAt: statusResponse.observedAt,
    successfulTicks: requireNumber(status, 'successfulTicks', 'daemonStatus'),
    failedTicks: requireNumber(status, 'failedTicks', 'daemonStatus'),
    consecutiveFailures: requireNumber(status, 'consecutiveFailures', 'daemonStatus'),
    tickSeconds: requireNumber(clock, 'tickSeconds', 'daemonStatus.clock'),
    lastTurnTime: requireString(clock, 'lastTurnTime', 'daemonStatus.clock'),
    nextRunTime: requireString(clock, 'nextRunTime', 'daemonStatus.clock'),
  };
}

function assertSixtySecondBoundary(snapshot: TickSnapshot): void {
  expect(snapshot.tickSeconds, 'operational cadence tickSeconds').toBe(60);
  const last = instantMillis(snapshot.lastTurnTime, 'daemon clock.lastTurnTime');
  const next = instantMillis(snapshot.nextRunTime, 'daemon clock.nextRunTime');
  expect(next - last, 'daemon next boundary must be exactly one cadence after persisted last boundary').toBe(60_000);
}

async function waitForSafeOperationalSubmissionWindow(): Promise<TickSnapshot> {
  const deadline = Date.now() + Number(process.env.E2E_OPERATIONAL_READY_TIMEOUT_MS ?? 90_000);
  while (Date.now() < deadline) {
    const snapshot = tickSnapshot(await daemonStatus());
    assertSixtySecondBoundary(snapshot);
    if (snapshot.failedTicks === 0 && snapshot.consecutiveFailures === 0) {
      const untilNextBoundary = instantMillis(snapshot.nextRunTime, 'daemon clock.nextRunTime') - Date.now();
      if (untilNextBoundary >= 15_000) return snapshot;
    }
    await sleep(250);
  }
  throw new Error('daemon never exposed a healthy 15-second operational submission window');
}

async function observeThreeSuccessiveTickBoundaries(initial: TickSnapshot): Promise<TickSnapshot[]> {
  assertSixtySecondBoundary(initial);
  expect(initial.failedTicks, 'daemon starts operational observation with no failures').toBe(0);
  expect(initial.consecutiveFailures, 'daemon starts operational observation without consecutive failures').toBe(0);

  const snapshots: TickSnapshot[] = [];
  let previous = initial;
  const deadline = Date.now() + Number(process.env.E2E_OPERATIONAL_TICK_TIMEOUT_MS ?? 240_000);
  while (Date.now() < deadline && snapshots.length < 3) {
    const current = tickSnapshot(await daemonStatus());
    assertSixtySecondBoundary(current);
    expect(current.failedTicks, 'failedTicks must remain zero during operational observation').toBe(0);
    expect(current.consecutiveFailures, 'consecutiveFailures must remain zero during operational observation').toBe(0);

    if (current.successfulTicks === previous.successfulTicks) {
      await sleep(250);
      continue;
    }

    expect(
      current.successfulTicks - previous.successfulTicks,
      'each observed durable tick boundary must advance successfulTicks exactly once',
    ).toBe(1);
    expect(
      instantMillis(current.lastTurnTime, 'current lastTurnTime') - instantMillis(previous.lastTurnTime, 'previous lastTurnTime'),
      'each persisted last boundary must advance by 60 seconds',
    ).toBe(60_000);
    expect(
      instantMillis(current.nextRunTime, 'current nextRunTime') - instantMillis(previous.nextRunTime, 'previous nextRunTime'),
      'each persisted next boundary must advance by 60 seconds',
    ).toBe(60_000);
    snapshots.push(current);
    previous = current;
  }

  if (snapshots.length !== 3) {
    throw new Error(`observed ${snapshots.length}/3 successive durable daemon tick boundaries`);
  }
  return snapshots;
}

async function captureSurface(page: Page, route: string, id: string): Promise<void> {
  const response = await page.goto(`${gameUrl}${route}`, { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => undefined);
  const status = response?.status() ?? 0;
  const bodyText = await page.locator('body').innerText().catch(() => '');
  const html = await page.content();
  writeArtifact(`${id}.dom.txt`, `${page.url()}\nHTTP ${status}\n\n${bodyText}`);
  writeArtifact(`${id}.dom.html`, html);
  expect(status, `${route} document status`).toBeGreaterThanOrEqual(200);
  expect(status, `${route} document status`).toBeLessThan(500);
}

async function attachResponseCapture(page: Page): Promise<{ all: ResponseEvidence[]; stop: () => void }> {
  const all: ResponseEvidence[] = [];
  const listener = async (response: Response) => {
    if (!response.url().includes('/api/')) return;
    all.push({ url: response.url(), status: response.status(), body: await response.text().catch(() => '') });
  };
  page.on('response', listener);
  return { all, stop: () => page.off('response', listener) };
}

async function installOperationalBrowserProbe(page: Page): Promise<void> {
  await page.addInitScript(() => {
    type Probe = {
      sseOpenedAt: number[];
      turnCompleted: Array<{ at: number; data: string }>;
      frontInfoFetches: Array<{ at: number; url: string }>;
    };
    const storageKey = '__opensamguk_e2e_operational_probe_v1__';
    const target = window as typeof window & {
      __e2eOperationalProbe?: Probe;
    };
    const emptyProbe = (): Probe => ({ sseOpenedAt: [], turnCompleted: [], frontInfoFetches: [] });
    let probe = emptyProbe();
    const resetUntrustedPersistedProbe = () => {
      probe = emptyProbe();
      sessionStorage.removeItem(storageKey);
    };
    try {
      const serialized = sessionStorage.getItem(storageKey);
      if (serialized) {
        const saved = JSON.parse(serialized) as Partial<Probe>;
        if (
          Array.isArray(saved.sseOpenedAt) &&
          Array.isArray(saved.turnCompleted) &&
          Array.isArray(saved.frontInfoFetches)
        ) {
          probe = saved as Probe;
        } else {
          resetUntrustedPersistedProbe();
        }
      }
    } catch {
      resetUntrustedPersistedProbe();
    }
    const persist = () => {
      sessionStorage.setItem(storageKey, JSON.stringify(probe));
    };
    persist();
    target.__e2eOperationalProbe = probe;
    const nativeFetch = window.fetch.bind(window);
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
      if (url.includes('/api/game/api/front-info')) {
        probe.frontInfoFetches.push({ at: Date.now(), url });
        persist();
      }
      return nativeFetch(input, init);
    }) as typeof window.fetch;
    const NativeEventSource = window.EventSource;
    window.EventSource = new Proxy(NativeEventSource, {
      construct(Target, args) {
        const source = Reflect.construct(Target, args) as EventSource;
        const sourceUrl = typeof args[0] === 'string' ? args[0] : args[0] instanceof URL ? args[0].toString() : '';
        if (!sourceUrl.includes('/api/game/sse/turn')) return source;
        source.addEventListener('open', () => {
          probe.sseOpenedAt.push(Date.now());
          persist();
        });
        source.addEventListener('turnCompleted', (event) => {
          probe.turnCompleted.push({ at: Date.now(), data: event instanceof MessageEvent ? event.data : '' });
          persist();
        });
        return source;
      }
    });
  });
}

async function waitForOperationalBrowserSse(page: Page): Promise<void> {
  await expect.poll(async () => (await readOperationalBrowserEvidence(page)).sseOpenedAt.length, { timeout: 30_000 })
    .toBeGreaterThan(0);
}

async function readOperationalBrowserEvidence(page: Page): Promise<BrowserOperationalEvidence> {
  let lastNavigationError: Error | undefined;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      return await page.evaluate(() => {
        const target = window as typeof window & { __e2eOperationalProbe?: BrowserOperationalEvidence };
        const probe = target.__e2eOperationalProbe ?? { sseOpenedAt: [], turnCompleted: [], frontInfoFetches: [] };
        return JSON.parse(JSON.stringify(probe)) as BrowserOperationalEvidence;
      });
    } catch (error) {
      if (!(error instanceof Error) || !error.message.includes('Execution context was destroyed')) throw error;
      lastNavigationError = error;
      await page.waitForLoadState('domcontentloaded', { timeout: 10_000 }).catch(() => undefined);
      await sleep(100);
    }
  }
  throw new Error(`operational browser evidence unavailable after navigation recovery: ${lastNavigationError?.message ?? 'unknown navigation error'}`);
}

async function rawExtraCardValues(page: Page): Promise<Record<string, string>> {
  return page.locator('section[aria-label="장수 정보"] details.basic-card-extra .basic-card-row').evaluateAll((rows) => {
    const values: Record<string, string> = {};
    for (const row of rows) {
      const label = row.querySelector('.basic-card-head')?.textContent?.trim();
      const value = row.querySelector('.basic-card-body')?.textContent?.trim();
      if (label && value) values[label] = value;
    }
    return values;
  });
}

type ControlledAuthField = {
  name: string;
  value: string;
};

async function fillControlledAuthForm(
  page: Page,
  stage: 'auth-register' | 'auth-login',
  fields: ControlledAuthField[],
): Promise<void> {
  const inputs: Array<{ field: ControlledAuthField; locator: Locator }> = fields.map((field) => ({
    field,
    locator: page.locator(`input[name="${field.name}"]`),
  }));

  try {
    await page.waitForLoadState('networkidle', { timeout: 15_000 });
    for (const { locator } of inputs) {
      await expect(locator).toBeVisible({ timeout: 15_000 });
      await expect(locator).toBeEditable({ timeout: 15_000 });
    }
  } catch (error) {
    writeArtifact(`${stage}-form-ready.json`, {
      networkIdleSettled: false,
      fields: fields.map(({ name }) => name),
      error: error instanceof Error ? error.message.split('\n')[0] : String(error),
    });
    throw error;
  }

  writeArtifact(`${stage}-form-ready.json`, {
    networkIdleSettled: true,
    fields: fields.map(({ name }) => name),
  });

  const fillAll = async (): Promise<void> => {
    for (const { field, locator } of inputs) await locator.fill(field.value);
  };
  const valueMatches = async (): Promise<Record<string, boolean>> => Object.fromEntries(
    await Promise.all(inputs.map(async ({ field, locator }) => [field.name, (await locator.inputValue()) === field.value])),
  );

  await fillAll();
  await sleep(350);
  let matches = await valueMatches();
  let refilledAfterHydration = false;
  if (Object.values(matches).some((matched) => !matched)) {
    refilledAfterHydration = true;
    await sleep(350);
    await fillAll();
    await sleep(350);
    matches = await valueMatches();
  }
  writeArtifact(`${stage}-form-fill.json`, {
    refilledAfterHydration,
    valueMatches: matches,
  });

  for (const { field, locator } of inputs) {
    await expect(locator).toHaveValue(field.value, { timeout: 1_000 });
  }
}

async function createAndLogin(context: BrowserContext): Promise<{ page: Page; username: string; password: string }> {
  const page = await context.newPage();
  const suffix = `${Date.now()}${Math.floor(Math.random() * 10_000)}`;
  const username = `e2e_${suffix}`;
  const password = `E2e!${suffix}a`;

  await page.goto(`${gatewayUrl}/join`, { waitUntil: 'domcontentloaded' });
  await fillControlledAuthForm(page, 'auth-register', [
    { name: 'username', value: username },
    { name: 'password', value: password },
    { name: 'passwordConfirm', value: password },
    { name: 'nickname', value: `e2e-${suffix}` },
  ]);
  const registerResponsePromise = page.waitForResponse((r) => r.url().includes('/api/auth/register'), { timeout: 30_000 });
  await page.getByRole('button', { name: /가입/ }).click();
  const registerResponse = await registerResponsePromise;
  const registration = await responseBody(registerResponse);
  writeArtifact('auth-register.json', { status: registration.status, body: registration.data });
  expect(registration.status, 'actual signup response').toBe(200);
  expect((await context.cookies()).some((cookie) => cookie.name === 'sam_access' && cookie.httpOnly), 'signup httpOnly access cookie').toBeTruthy();

  await page.request.post(`${gatewayUrl}/api/auth/logout`);
  await page.goto(`${gatewayUrl}/login`, { waitUntil: 'domcontentloaded' });
  await fillControlledAuthForm(page, 'auth-login', [
    { name: 'username', value: username },
    { name: 'password', value: password },
  ]);
  const loginResponsePromise = page.waitForResponse((r) => r.url().includes('/api/auth/login'), { timeout: 30_000 });
  await page.getByRole('button', { name: '로그인' }).click();
  const loginResponse = await loginResponsePromise;
  const login = await responseBody(loginResponse);
  writeArtifact('auth-login.json', { status: login.status, body: login.data });
  expect(login.status, 'actual login response').toBe(200);
  const accessCookie = (await context.cookies()).find((cookie) => cookie.name === 'sam_access');
  expect(accessCookie?.httpOnly, 'login sam_access cookie must be httpOnly').toBeTruthy();
  expect(await page.locator('body').innerText(), 'JWT must not be rendered into DOM').not.toContain(accessCookie?.value ?? '__missing_cookie__');
  return { page, username, password };
}

function frontGeneral(value: Json): JsonRecord {
  return requireRecord(requireRecord(value, 'front-info').general, 'front-info.general');
}

async function createOperationalNeutralGeneral(page: Page): Promise<{ generalId: number; joinRequestId: string }> {
  const suffix = `${Date.now().toString().slice(-10)}${Math.floor(Math.random() * 10_000).toString().padStart(4, '0')}`;
  const name = `op${suffix}`;
  expect(name, 'operational generated join name is ASCII').toMatch(/^[\x20-\x7e]+$/);
  expect(name.length, 'operational generated join name has deterministic safe length').toBe(16);
  const join = await apiPost(page.request, '/api/game/api/join', {
    name,
    leadership: 55,
    strength: 55,
    intel: 55,
    politics: 55,
    charm: 55,
    character: 'che_유지',
    pic: false,
  });
  const joinRecord = requireRecord(join.data, 'operational join');
  const joinStatus = typeof joinRecord.status === 'string' ? joinRecord.status : undefined;
  const joinReason = typeof joinRecord.reason === 'string' ? joinRecord.reason : undefined;
  const joinRequestIdValue = typeof joinRecord.requestId === 'string' ? joinRecord.requestId : undefined;
  writeArtifact('operational-join-intake.json', {
    status: join.response.status(),
    body: { status: joinStatus, reason: joinReason, requestId: joinRequestIdValue },
  });
  expect(join.response.status(), `operational normal join intake (reason: ${joinReason ?? 'missing'})`).toBe(202);
  expect(joinStatus, `operational normal join response status (reason: ${joinReason ?? 'missing'})`).toBe('AVAILABLE');
  const joinRequestId = requireString(joinRecord, 'requestId', 'operational join');
  const joinTerminal = await pollCommandResult(page.request, joinRequestId, 45_000);
  expect(joinTerminal.data, 'operational join terminal').toMatchObject({ status: 'RESOLVED', ok: true });

  await expect.poll(async () => {
    const front = await apiGet(page.request, '/api/game/api/front-info');
    return frontGeneral(front.data).hasGeneral;
  }, { timeout: 45_000 }).toBe(true);

  const front = await apiGet(page.request, '/api/game/api/front-info');
  expect(front.response.status(), 'operational joined front-info').toBe(200);
  const general = frontGeneral(front.data);
  const generalId = requireNumber(general, 'generalId', 'operational joined general');
  expect(general.nationId, 'operational general remains neutral').toBe(0);
  expect(general.personal, 'operational general personality is deterministic').toBe('che_유지');
  expect(requireNumber(general, 'experience', 'operational joined general')).toBe(0);
  expect(requireNumber(general, 'dedication', 'operational joined general')).toBe(0);
  return { generalId, joinRequestId };
}

test('v1 core live surfaces and durable engine restart', async ({ browser }, testInfo) => {
  test.skip(operationalSmokeEnabled, 'operational mode runs the dedicated correlated reserved-turn smoke');
  const context = await browser.newContext();
  const auth = await createAndLogin(context);
  const page = auth.page;
  const capture = await attachResponseCapture(page);
  const state: Record<string, unknown> = { username: auth.username, composeProjectName };
  try {
    const frontBefore = await apiGet(page.request, '/api/game/api/front-info');
    writeArtifact('front-info-before.json', { status: frontBefore.response.status(), body: frontBefore.data });
    expect(frontBefore.response.status()).toBe(200);
    let front = frontBefore.data as Record<string, unknown>;
    const generalBefore = (front.general ?? {}) as Record<string, unknown>;

    if (generalBefore.hasGeneral === false) {
      await page.goto(`${gameUrl}/game/join`, { waitUntil: 'domcontentloaded' });
      await page.locator('form input[type="text"]').first().fill(`장수${Date.now()}`);
      page.on('dialog', (dialog) => void dialog.accept());
      const joinResponsePromise = page.waitForResponse((r) => (
        r.request().method() === 'POST' && r.url().includes('/api/game/api/join')
      ));
      await page.getByRole('button', { name: '장수 생성' }).click();
      const joinResponse = await joinResponsePromise;
      const join = await responseBody(joinResponse);
      writeArtifact('scenario-join.json', { status: join.status, body: join.data });
      expect(join.status).toBeGreaterThanOrEqual(200);
      expect(join.status).toBeLessThan(300);
      const joinRequestId = requestIdOf(join.data);
      expect(joinRequestId, 'join command requestId').toBeTruthy();
      const terminal = await pollCommandResult(page.request, joinRequestId as string);
      writeArtifact('scenario-join-terminal.json', { requestId: joinRequestId, ...terminal, data: terminal.data });
      expect(terminal.data).toMatchObject({ status: 'RESOLVED', ok: true });
      await expect.poll(async () => {
        const current = await apiGet(page.request, '/api/game/api/front-info');
        return ((current.data as Record<string, unknown>).general as Record<string, unknown> | undefined)?.hasGeneral;
      }, { timeout: 30_000 }).toBe(true);
      front = (await apiGet(page.request, '/api/game/api/front-info')).data as Record<string, unknown>;
    }

    const general = (front.general ?? {}) as Record<string, unknown>;
    const generalId = Number(general.generalId);
    expect(Number.isInteger(generalId) && generalId > 0, 'live scenario must expose a playable general').toBeTruthy();
    state.generalId = generalId;

    const available = await apiGet(page.request, `/api/game/api/commands/available?generalId=${generalId}`);
    writeArtifact('general-commands-available.json', { status: available.response.status(), body: available.data });
    const commandCode = commandCodeOf(available.data);
    expect(commandCode, 'live available command catalog').toBeTruthy();

    const applied = await apiPost(page.request, `/api/game/api/command/${encodeURIComponent(commandCode as string)}?generalId=${generalId}&turnIdx=0`, {});
    const appliedRequestId = requestIdOf(applied.data);
    const appliedTerminal = appliedRequestId ? await pollCommandResult(page.request, appliedRequestId) : null;
    writeArtifact('command-general-applied.json', { intake: { status: applied.response.status(), body: applied.data }, requestId: appliedRequestId, terminal: appliedTerminal });
    expect(applied.response.status()).toBeLessThan(300);
    expect(appliedRequestId, 'applied command requestId').toBeTruthy();
    expect(appliedTerminal?.data).toMatchObject({ status: 'RESOLVED', ok: true });
    state.commandRequestId = appliedRequestId;

    const pending = await apiPost(page.request, `/api/game/api/command/${encodeURIComponent(commandCode as string)}?generalId=${generalId}&turnIdx=1`, {});
    const pendingRequestId = requestIdOf(pending.data);
    writeArtifact('command-general-pending.json', { status: pending.response.status(), body: pending.data, requestId: pendingRequestId });
    expect(pending.response.status()).toBeLessThan(300);
    expect(pendingRequestId, 'pending command requestId').toBeTruthy();
    const pendingTerminal = await pollCommandResult(page.request, pendingRequestId as string);
    writeArtifact('command-general-pending-terminal.json', { requestId: pendingRequestId, ...pendingTerminal, data: pendingTerminal.data });

    const rejected = await apiPost(page.request, '/api/game/api/command/__e2e_invalid__?generalId=0&turnIdx=0', {});
    writeArtifact('command-general-rejected.json', { status: rejected.response.status(), body: rejected.data });
    expect(rejected.response.status() >= 400 || (rejected.data && typeof rejected.data === 'object' && !Array.isArray(rejected.data) && (rejected.data as Record<string, unknown>).status === 'BLOCKED')).toBeTruthy();

    const nation = await apiPost(page.request, `/api/game/api/command/nation/bulk?generalId=${generalId}`, []);
    writeArtifact('command-nation.json', { status: nation.response.status(), body: nation.data, requestId: requestIdOf(nation.data) });
    expect(nation.response.status()).toBeLessThan(300);

    const routes: Array<[string, string]> = [
      ['/game', 'general'],
      ['/game/nation', 'nation'],
      ['/game/auction', 'auction-resource'],
      ['/game/auction?type=unique', 'auction-unique-deep-link'],
      ['/game/board', 'board'],
      ['/game/board?secret=1', 'board-secret-deep-link'],
      ['/game/diplomacy', 'diplomacy'],
      ['/game/mailbox', 'mailbox'],
      ['/game/betting', 'betting'],
      ['/game/select-pool', 'select-pool'],
      ['/game/my', 'settings-vacation'],
      ['/game/history', 'history'],
      ['/game/rankings/kingdoms', 'kingdom-roles'],
    ];
    for (const [route, id] of routes) await captureSurface(page, route, id);

    const emperorList = await apiGet(page.request, '/api/game/api/rankings/emperor');
    writeArtifact('emperor-list.json', { status: emperorList.response.status(), body: emperorList.data });
    let emperorId = '0';
    if (Array.isArray(emperorList.data) && emperorList.data.length > 0 && emperorList.data[0] && typeof emperorList.data[0] === 'object') {
      const record = emperorList.data[0] as Record<string, unknown>;
      emperorId = String(record.id ?? record.generalId ?? record.emperorId ?? '0');
    }
    await captureSurface(page, `/game/rankings/emperor/${encodeURIComponent(emperorId)}`, 'emperor-detail');

    const beforeRestart = await apiGet(page.request, '/api/game/api/front-info');
    state.beforeRestart = { status: beforeRestart.response.status(), generalId: ((beforeRestart.data as Record<string, unknown>).general as Record<string, unknown> | undefined)?.generalId };
    execFileSync('docker', ['compose', '--project-name', composeProjectName, '--env-file', '/dev/null', 'restart', 'game-engine'], { cwd: repoRoot, stdio: 'pipe', timeout: 180_000 });
    await expect.poll(() => statusOrUnavailable(engineHealthUrl), { timeout: 120_000 }).toBe(200);
    const afterRestart = await apiGet(page.request, '/api/game/api/front-info');
    writeArtifact('restart-persistence.json', { before: state.beforeRestart, after: { status: afterRestart.response.status(), body: afterRestart.data }, commandRequestId: state.commandRequestId });
    expect(afterRestart.response.status()).toBe(200);
    expect(((afterRestart.data as Record<string, unknown>).general as Record<string, unknown> | undefined)?.generalId).toBe((state.beforeRestart as Record<string, unknown>).generalId);
    const commandAfterRestart = await apiGet(page.request, `/api/game/api/command/result/${encodeURIComponent(String(state.commandRequestId))}`);
    writeArtifact('restart-command-result-check.json', { status: commandAfterRestart.response.status(), body: commandAfterRestart.data, requestId: state.commandRequestId });
    expect(commandAfterRestart.response.status()).toBe(200);
    expect(commandAfterRestart.data).toMatchObject({ status: 'RESOLVED', requestId: state.commandRequestId });
    const repositoryCheck = await apiGet(page.request, '/api/game/api/rankings/kingdoms');
    writeArtifact('restart-repository-check.json', { status: repositoryCheck.response.status(), body: repositoryCheck.data });
    expect(repositoryCheck.response.status()).toBe(200);
  } finally {
    capture.stop();
    writeArtifact('api-responses.json', capture.all);
    writeArtifact('v1-state.json', state);
    await context.close();
  }
});

test('operational smoke follows che_요양 from reservation through durable execution and browser refresh', async ({ browser }) => {
  test.skip(!operationalSmokeEnabled, 'set E2E_OPERATIONAL_SMOKE=true only with the one-minute local QA cadence');

  const context = await browser.newContext();
  let page: Page | undefined;
  const state: Record<string, unknown> = {
    mode: 'operational-smoke',
    actionCode: 'che_요양',
    expectedDelta: { injury: 0, experience: 10, dedication: 7 },
  };
  try {
    const auth = await createAndLogin(context);
    page = auth.page;
    await installOperationalBrowserProbe(page);

    const joined = await createOperationalNeutralGeneral(page);
    state.join = joined;

    await page.goto(`${gameUrl}/game`, { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => undefined);
    await expect(page.getByRole('region', { name: '장수 정보' })).toBeVisible({ timeout: 30_000 });
    await waitForOperationalBrowserSse(page);

    const daemonBefore = await daemonStatus();
    const daemonBeforeRecord = requireRecord(daemonBefore.data, 'daemon status before operational smoke');
    expect(daemonBeforeRecord.state, 'operational daemon state').toBe('running');
    expect(daemonBeforeRecord.running, 'operational daemon running flag').toBe(true);
    const initialTick = await waitForSafeOperationalSubmissionWindow();
    state.daemonInitial = initialTick;

    const frontBefore = await apiGet(page.request, '/api/game/api/front-info');
    expect(frontBefore.response.status(), 'operational front-info before reservation').toBe(200);
    const generalBefore = frontGeneral(frontBefore.data);
    const injuryBefore = requireNumber(generalBefore, 'injury', 'operational front-info before reservation');
    const experienceBefore = requireNumber(generalBefore, 'experience', 'operational front-info before reservation');
    const dedicationBefore = requireNumber(generalBefore, 'dedication', 'operational front-info before reservation');
    expect(requireNumber(generalBefore, 'generalId', 'operational front-info before reservation')).toBe(joined.generalId);
    expect(injuryBefore, 'fresh neutral general injury before che_요양').toBe(0);
    expect(experienceBefore, 'fresh neutral general experience before che_요양').toBe(0);
    expect(dedicationBefore, 'fresh neutral general dedication before che_요양').toBe(0);
    state.beforeAuthoritativeRead = {
      status: frontBefore.response.status(),
      injury: injuryBefore,
      experience: experienceBefore,
      dedication: dedicationBefore,
    };

    const available = await apiGet(page.request, `/api/game/api/commands/available?generalId=${joined.generalId}`);
    expect(available.response.status(), 'operational command catalog').toBe(200);
    const yoyang = findCommandEntry(available.data, 'che_요양');
    expect(yoyang, 'public command catalog contains the pinned che_요양 command').not.toBeNull();
    expect(yoyang?.possible, 'che_요양 full constraints remain available').toBe(true);
    expect(yoyang?.reqArg, 'che_요양 has no argument form').toBe(false);
    expect(yoyang?.form, 'che_요양 has no structured form').toBeFalsy();
    state.catalog = yoyang;

    const tickSnapshotsPromise = observeThreeSuccessiveTickBoundaries(initialTick);
    const submittedAt = Date.now();
    const intake = await apiPost(page.request, `/api/game/api/command/${encodeURIComponent('che_요양')}?generalId=${joined.generalId}&turnIdx=0`, {});
    expect(intake.response.status(), 'che_요양 reservation intake').toBe(202);
    const intakeRecord = requireRecord(intake.data, 'che_요양 reservation intake');
    const requestId = requireString(intakeRecord, 'requestId', 'che_요양 reservation intake');
    state.requestId = requestId;
    state.intake = { status: intake.response.status(), body: intake.data, submittedAt: new Date(submittedAt).toISOString() };

    const redisWakeIngress = await observeRedisWakeIngress(requestId);
    expect(redisWakeIngress.commandInbox.rowCount, 'command_inbox contains exactly the submitted request').toBe(1);
    expect(redisWakeIngress.commandInbox.redisWakePublishedAt, 'command_inbox records the Redis wake publication').not.toBe('');
    expect(redisWakeIngress.streamKey, 'Redis command stream key').toBe(requireOperationalStreamContext().streamKey);
    expect(redisWakeIngress.group.name, 'Redis consumer group').toBe('game-engine');
    expect(redisWakeIngress.consumer.name, 'Redis consumer').toBe(`world-${requireOperationalStreamContext().worldId}`);
    state.redisWake = { ingress: redisWakeIngress };
    writeArtifact('operational-redis-wake.json', state.redisWake);

    const reservationAccepted = await pollCommandResultPhase(page.request, requestId, 'reservationAccepted', 15_000);
    const reservationRecord = requireRecord(reservationAccepted.data, 'reservationAccepted');
    expect(reservationAccepted.status, 'reservationAccepted result HTTP status').toBe(200);
    expect(requireString(reservationRecord, 'requestId', 'reservationAccepted'), 'reservationAccepted requestId').toBe(requestId);
    expect(reservationRecord.status, 'reservationAccepted remains nonterminal').toBe('PENDING');
    expect(reservationRecord.phase, 'reservationAccepted phase').toBe('reservationAccepted');
    state.reservationAccepted = reservationAccepted;

    const executionApplied = await pollCommandResultPhase(
      page.request,
      requestId,
      'executionApplied',
      Number(process.env.E2E_OPERATIONAL_EXECUTION_TIMEOUT_MS ?? 180_000),
    );
    const executionRecord = requireRecord(executionApplied.data, 'executionApplied');
    expect(executionApplied.status, 'executionApplied result HTTP status').toBe(200);
    expect(requireString(executionRecord, 'requestId', 'executionApplied'), 'executionApplied requestId').toBe(requestId);
    expect(executionRecord.ok, 'executionApplied result').toBe(true);
    const committedWorldVersion = requireNumber(executionRecord, 'committedWorldVersion', 'executionApplied');
    expect(committedWorldVersion, 'executionApplied committed world version').toBeGreaterThan(0);
    state.executionApplied = executionApplied;
    state.committedWorldVersion = committedWorldVersion;

    const redisWakeAcknowledgement = await observeRedisWakeAcknowledgement(redisWakeIngress);
    expect(redisWakeAcknowledgement.pendingEntryCount, 'exact Redis wake is no longer pending after XACK').toBe(0);
    expect(redisWakeAcknowledgement.entryStillPresent, 'XACK retains the exact stream entry').toBe(true);
    state.redisWake = { ingress: redisWakeIngress, acknowledgement: redisWakeAcknowledgement };
    writeArtifact('operational-redis-wake.json', state.redisWake);

    const frontAfter = await apiGet(page.request, `/api/game/api/front-info?minVersion=${committedWorldVersion}`);
    expect(frontAfter.response.status(), 'authoritative front-info after executionApplied').toBe(200);
    const generalAfter = frontGeneral(frontAfter.data);
    const injuryAfter = requireNumber(generalAfter, 'injury', 'authoritative front-info after executionApplied');
    const experienceAfter = requireNumber(generalAfter, 'experience', 'authoritative front-info after executionApplied');
    const dedicationAfter = requireNumber(generalAfter, 'dedication', 'authoritative front-info after executionApplied');
    expect(requireNumber(generalAfter, 'generalId', 'authoritative front-info after executionApplied')).toBe(joined.generalId);
    expect(injuryAfter, 'authoritative injury after che_요양').toBe(0);
    expect(experienceAfter, 'authoritative experience after che_요양').toBe(10);
    expect(dedicationAfter, 'authoritative dedication after che_요양').toBe(7);
    expect(injuryAfter - injuryBefore, 'che_요양 injury delta').toBe(0);
    expect(experienceAfter - experienceBefore, 'che_요양 experience delta').toBe(10);
    expect(dedicationAfter - dedicationBefore, 'che_요양 dedication delta').toBe(7);
    state.afterAuthoritativeRead = {
      status: frontAfter.response.status(),
      minVersion: committedWorldVersion,
      injury: injuryAfter,
      experience: experienceAfter,
      dedication: dedicationAfter,
    };

    const tickSnapshots = await tickSnapshotsPromise;
    state.tickSnapshots = tickSnapshots;

    await expect.poll(async () => {
      const evidence = await readOperationalBrowserEvidence(page!);
      return evidence.turnCompleted.filter((event) => event.at >= submittedAt).length;
    }, { timeout: 30_000 }).toBeGreaterThan(0);
    const browserEvidence = await readOperationalBrowserEvidence(page);
    const firstTurnCompletedAfterSubmit = browserEvidence.turnCompleted.find((event) => event.at >= submittedAt);
    expect(firstTurnCompletedAfterSubmit, 'browser observed a real turnCompleted SSE event after submit').toBeTruthy();
    await expect.poll(async () => {
      const evidence = await readOperationalBrowserEvidence(page!);
      return evidence.frontInfoFetches.some((fetch) => fetch.at >= (firstTurnCompletedAfterSubmit?.at ?? Number.MAX_SAFE_INTEGER));
    }, { timeout: 30_000 }).toBe(true);

    const card = page.getByRole('region', { name: '장수 정보' });
    const extra = card.locator('details.basic-card-extra');
    await expect(extra, 'GeneralBasicCard additional information').toBeVisible({ timeout: 30_000 });
    if (!(await extra.evaluate((element) => (element as HTMLDetailsElement).open))) {
      await extra.locator('summary').click();
    }
    await expect.poll(async () => (await rawExtraCardValues(page!)).명성, { timeout: 30_000 }).toMatch(/\(\s*10\s*\)/);
    await expect.poll(async () => (await rawExtraCardValues(page!)).계급, { timeout: 30_000 }).toMatch(/\(\s*7\s*\)/);
    state.browser = {
      evidence: await readOperationalBrowserEvidence(page),
      refreshedAfterTurnCompleted: true,
      extraCardValues: await rawExtraCardValues(page),
    };
  } finally {
    if (page) {
      state.browserEvidenceAtFinish = await readOperationalBrowserEvidence(page).catch(() => null);
    }
    writeArtifact('operational-smoke-correlation.json', state);
    await context.close();
  }
});
