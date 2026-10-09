// Actual browser cancellation with page.route B1-shaped fixtures, on desktop and 390x844 touch profiles.
// This proves DOM interaction and request shape; routes intercept before the BFF and provide no real JWT or PostgreSQL evidence.
import { expect, test, type Page, type Route } from '@playwright/test';
import type { ReservedSlot } from '../../lib/types';
import { BOTH, expectNoHorizontalOverflow, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

test.describe.configure({ retries: 0 });

const API = '/api/game/api';
const GENERAL_ID = 7;
const revision = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const farm = (turnIdx: number, rev = revision(turnIdx)): ReservedSlot => ({ turnIdx, action: 'action.farm', brief: '', arg: {}, revision: rev });
const DONE = '이 예약의 취소를 확인했습니다 — 01순은 지금 비어 있습니다.';

interface Backend {
  slots: ReservedSlot[];
  readonly receipts: Map<string, { turnIdx: number; revision: string }>;
  readonly deletes: { key: string | null; query: Record<string, string>; body: string | null; contentType: string | null }[];
  readonly resultReads: string[];
  deleteMode: 'commit' | 'worldExecuting' | 'lostAfterCommit';
  /** Runs right after a commit whose response is lost (e.g. someone reserves the slot again). */
  afterCommit: (() => void) | null;
}

const backend = (): Backend => ({ slots: [farm(0), farm(1)], receipts: new Map(), deletes: [], resultReads: [], deleteMode: 'commit', afterCommit: null });

const receipt = (requestId: string, c: { turnIdx: number; revision: string }) => ({
  requestId, status: 'RESOLVED', type: 'reservationCancelled', ok: true, accepted: true, receiptRecorded: true, committedWorldVersion: 42,
  result: { type: 'reservationCancelled', ok: true, commandKind: 'QUEUE_MUTATION', actionCode: 'cancelReservedTurn', generalId: GENERAL_ID, turnIdx: c.turnIdx, reservationRevision: c.revision, slotEmpty: true },
});

/** B1: an existing receipt for the UUID is replayed (never deleting a replacement), otherwise the revision CAS decides. */
function commit(b: Backend, key: string, url: URL): { status: number; body: unknown } {
  const turnIdx = Number(url.searchParams.get('turnIdx'));
  const rev = url.searchParams.get('revision') ?? '';
  const prior = b.receipts.get(key);
  if (prior) return { status: 200, body: receipt(key, prior) };
  const row = b.slots.find(slot => slot.turnIdx === turnIdx);
  if (!row || row.revision !== rev) return { status: 409, body: { status: 'BLOCKED', code: 'REVISION_MISMATCH', accepted: false, receiptRecorded: false, retryable: false } };
  b.slots = b.slots.filter(slot => slot !== row);
  b.receipts.set(key, { turnIdx, revision: rev });
  return { status: 200, body: receipt(key, { turnIdx, revision: rev }) };
}

async function serve(page: Page, b: Backend) {
  const baseURL = test.info().project.use.baseURL ?? 'http://localhost:3001';
  await page.context().addCookies([{ name: 'sam_server', value: 'pep', url: baseURL }]);
  const json = (route: Route, status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
  await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
  await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
  await page.route((url) => url.pathname.startsWith('/api/game/'), async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.slice(API.length);
    if (path === '/front-info') {
      return json(route, 200, {
        result: true,
        global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
        general: { hasGeneral: true, generalId: GENERAL_ID, name: '하후돈', nationId: 1, officerLevel: 1, permission: 0, showSecret: false },
        nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
      });
    }
    if (path === '/reserved-commands' && request.method() === 'GET') return json(route, 200, { result: true, generalId: GENERAL_ID, slots: b.slots });
    if (path === '/reserved-commands' && request.method() === 'DELETE') {
      const key = await request.headerValue('idempotency-key');
      b.deletes.push({ key, query: Object.fromEntries(url.searchParams), body: request.postData(), contentType: await request.headerValue('content-type') });
      if (b.deleteMode === 'worldExecuting') {
        return json(route, 409, { status: 'BLOCKED', code: 'WORLD_EXECUTING', accepted: false, receiptRecorded: false, retryable: true });
      }
      const answer = commit(b, key ?? '', url);
      if (b.deleteMode === 'lostAfterCommit') {
        b.afterCommit?.();
        return route.abort('connectionreset');
      }
      return json(route, answer.status, answer.body);
    }
    const result = /^\/command\/result\/(.+)$/.exec(path);
    if (result) {
      const id = decodeURIComponent(result[1]);
      b.resultReads.push(id);
      const committed = b.receipts.get(id);
      return json(route, 200, committed
        ? { status: 'RESOLVED', requestId: id, ok: true, type: 'reservationCancelled', result: { type: 'reservationCancelled', ok: true, generalId: GENERAL_ID, turnIdx: committed.turnIdx, reservationRevision: committed.revision, slotEmpty: true } }
        : { status: 'PENDING', requestId: id });
    }
    return json(route, 503, {});
  });
}

const flow = (page: Page) => page.getByTestId('command-flow');
const band = (page: Page) => flow(page).getByTestId('reservation-band');
const cancelButton = (page: Page) => band(page).getByRole('button', { name: '예약 취소' });
const dialog = (page: Page) => page.getByRole('dialog', { name: '01순 예약을 취소합니다' });
const status = (page: Page) => flow(page).getByTestId('reservation-cancel-status');

async function openSlot01(page: Page, b: Backend) {
  await serve(page, b);
  await page.goto('/game?do=&slot=1', { waitUntil: 'domcontentloaded' });
  await expect(flow(page)).toBeVisible({ timeout: 60_000 });
  await expect(flow(page).getByRole('button', { name: '01순 — 농지개간' })).toHaveAttribute('aria-pressed', 'true');
  await expect(cancelButton(page)).toBeVisible();
}

async function confirmCancel(page: Page, testInfo: Parameters<typeof press>[1]) {
  await press(cancelButton(page), testInfo);
  await expect(dialog(page)).toBeVisible();
  await press(dialog(page).getByRole('button', { name: '예약 취소' }), testInfo);
}

test.describe('지금 예약 하나 취소', () => {
  test('취소하면 본문 없는 DELETE 한 번 — 띠와 순 칸이 함께 비고, 새로고침해도 빈 순이다', { tag: [BOTH] }, async ({ page }, testInfo) => {
    const b = backend();
    await openSlot01(page, b);
    await expect(band(page)).toContainText('01순 지금 예약: 농지개간');
    expect(await smallTouchTargets(page, '[data-testid="reservation-band"]')).toEqual([]);
    expect(await titleOnlyInfo(page, '[data-testid="reservation-band"]')).toEqual([]);
    await expectNoHorizontalOverflow(page);
    await press(cancelButton(page), testInfo);
    await expect(dialog(page)).toContainText('01순의 「농지개간」 예약을 지웁니다.');
    expect(await smallTouchTargets(page, '[role="dialog"]')).toEqual([]);
    await press(dialog(page).getByRole('button', { name: '예약 취소' }), testInfo);
    await expect(status(page)).toHaveText(new RegExp(DONE));
    await expect(flow(page).getByRole('button', { name: '01순 — 빈 순' })).toHaveAttribute('aria-pressed', 'true');
    await expect(flow(page).getByRole('button', { name: '02순 — 농지개간' })).toBeVisible();
    await expect(flow(page).getByTestId('slot-reserved')).toHaveCount(0);
    expect(b.deletes).toHaveLength(1);
    expect(b.deletes[0].key).toMatch(UUID);
    expect(b.deletes[0]).toMatchObject({ body: null, contentType: null, query: { generalId: '7', turnIdx: '0', revision: revision(0), server: 'pep' } });
    expect(b.slots).toEqual([farm(1)]);
    await expectNoHorizontalOverflow(page);
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(flow(page).getByRole('button', { name: '01순 — 빈 순' })).toBeVisible({ timeout: 60_000 });
    await expect(flow(page).getByTestId('reservation-band')).toHaveCount(0);
    expect(b.deletes).toHaveLength(1);
  });

  test('확인 창을 「그대로 두기」 · Esc 로 닫으면 아무것도 보내지 않는다', { tag: [BOTH] }, async ({ page }, testInfo) => {
    const b = backend();
    await openSlot01(page, b);
    await press(cancelButton(page), testInfo);
    await press(dialog(page).getByRole('button', { name: '그대로 두기' }), testInfo);
    await expect(dialog(page)).toHaveCount(0);
    await press(cancelButton(page), testInfo);
    await expect(dialog(page)).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dialog(page)).toHaveCount(0);
    await expect(flow(page)).toBeVisible();
    expect(b.deletes).toEqual([]);
    expect(b.slots).toEqual([farm(0), farm(1)]);
  });

  test('확인을 한 이벤트 루프에서 두 번 눌러도 처음 요청 하나만 나간다', { tag: [BOTH] }, async ({ page }, testInfo) => {
    const b = backend();
    await openSlot01(page, b);
    await press(cancelButton(page), testInfo);
    await dialog(page).getByRole('button', { name: '예약 취소' }).evaluate((button: HTMLElement) => { button.click(); button.click(); });
    await expect(status(page)).toHaveText(new RegExp(DONE));
    expect(b.deletes).toHaveLength(1);
  });

  test('턴 처리 중(WORLD_EXECUTING)이면 저절로 다시 보내지 않고, 같은 취소를 손으로 다시 보낸다', { tag: [BOTH] }, async ({ page }, testInfo) => {
    const b = backend();
    b.deleteMode = 'worldExecuting';
    await openSlot01(page, b);
    await confirmCancel(page, testInfo);
    await expect(status(page)).toContainText('턴을 처리하는 중이라 취소를 받지 않았습니다');
    await expect(cancelButton(page)).toHaveCount(0);
    await page.waitForTimeout(1_000);
    expect(b.deletes).toHaveLength(1);
    b.deleteMode = 'commit';
    await press(status(page).getByRole('button', { name: '같은 취소 다시 보내기' }), testInfo);
    await expect(status(page)).toHaveText(new RegExp(DONE));
    expect(b.deletes.map(d => d.key)).toEqual([b.deletes[0].key, b.deletes[0].key]);
    expect(b.deletes.map(d => d.query.revision)).toEqual([revision(0), revision(0)]);
  });

  test('응답을 잃으면 결과 불명 — 같은 UUID 결과 조회로 확인하고 새 취소는 만들지 않는다', { tag: [BOTH] }, async ({ page }, testInfo) => {
    const b = backend();
    b.deleteMode = 'lostAfterCommit';
    await openSlot01(page, b);
    await confirmCancel(page, testInfo);
    await expect(status(page)).toContainText('취소 결과를 아직 확인하지 못했습니다');
    await expect(cancelButton(page)).toHaveCount(0);
    await expect(flow(page).getByRole('button', { name: '닫기' }).first()).not.toHaveAttribute('aria-disabled', 'true');
    await press(status(page).getByRole('button', { name: '취소 결과 확인' }), testInfo);
    await expect(status(page)).toHaveText(new RegExp(DONE));
    expect(b.resultReads).toEqual([b.deletes[0].key]);
    expect(b.deletes).toHaveLength(1);
  });

  test('지난 취소 성공 뒤 새로 들어온 예약은 남고 그대로 보인다', { tag: [BOTH] }, async ({ page }, testInfo) => {
    const b = backend();
    b.deleteMode = 'lostAfterCommit';
    b.afterCommit = () => { b.slots = [farm(0, revision(70)), ...b.slots]; };
    await openSlot01(page, b);
    await confirmCancel(page, testInfo);
    await expect(status(page)).toContainText('취소 결과를 아직 확인하지 못했습니다');
    await press(status(page).getByRole('button', { name: '취소 결과 확인' }), testInfo);
    await expect(status(page)).toContainText('01순에는 그 뒤 들어온 다른 예약이 있습니다');
    await expect(band(page)).toContainText('01순 지금 예약: 농지개간');
    await expect(flow(page).getByRole('button', { name: '01순 — 농지개간' })).toBeVisible();
    expect(b.slots).toEqual([farm(0, revision(70)), farm(1)]);
    expect(b.deletes).toHaveLength(1);
  });

  test('결과 불명에서 다른 화면에 다녀오면 같은 탭 기록으로 이어 가고 DELETE 는 다시 보내지 않는다', { tag: [BOTH] }, async ({ page }, testInfo) => {
    const b = backend();
    b.deleteMode = 'lostAfterCommit';
    await openSlot01(page, b);
    await confirmCancel(page, testInfo);
    await expect(status(page)).toContainText('취소 결과를 아직 확인하지 못했습니다');
    await page.goto('/game', { waitUntil: 'domcontentloaded' });
    await expect(flow(page)).toHaveCount(0);
    // Back to the same slot by a fresh navigation in the same tab (not a bfcache restore).
    await page.goto('/game?do=&slot=1', { waitUntil: 'domcontentloaded' });
    await expect(status(page)).toHaveText(new RegExp(DONE), { timeout: 60_000 });
    expect(b.deletes).toHaveLength(1);
    expect(b.resultReads.length).toBeGreaterThanOrEqual(1);
    expect(new Set(b.resultReads)).toEqual(new Set([b.deletes[0].key]));
  });
});
