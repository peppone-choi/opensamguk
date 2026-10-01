import { expect, test, type APIRequestContext, type APIResponse, type Locator, type Page, type Response } from '@playwright/test';

const gatewayUrl = process.env.E2E_GATEWAY_URL ?? 'http://localhost:3000';
const gameUrl = process.env.E2E_GAME_URL ?? 'http://localhost:3001';
const commandTimeoutMs = Number(process.env.E2E_COMMAND_TIMEOUT_MS ?? 30_000);
// 서신 화면(P-Q02)은 브라우저 confirm 대신 확인 대화(K3 ConfirmDialog)로 한 번 묻는다.
const deleteConfirmTitle = '이 서신을 지웁니다';

type JsonRecord = Record<string, unknown>;
type MailboxCredentials = {
  readonly username: string;
  readonly password: string;
};
type MailboxScope = 'private' | 'national' | 'public' | 'diplomacy';
type DenialFixture = {
  readonly text: string;
  readonly reason: string;
  readonly scope: MailboxScope;
};
type JsonResponse = Pick<APIResponse, 'text'> | Pick<Response, 'text'>;

function isJsonRecord(value: unknown): value is JsonRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function requireRecord(value: unknown, label: string): JsonRecord {
  if (!isJsonRecord(value)) {
    throw new Error(`${label} must be a JSON object`);
  }
  return value;
}

/**
 * command/result 의 terminal payload 는 핸들러 결과를 `result` 안에 중첩해 싣는다(실측 2026-08-18):
 *   {"status":"RESOLVED","ok":true,"type":"sendMessage",
 *    "result":{"type":"sendMessage","ok":true,"generalId":1230,"msgID":2}}
 * `ok`/`type`/`reason` 은 최상위에도 복제되지만 `msgID` 는 `result` 안에만 있다.
 */
function terminalResult(terminal: JsonRecord, label: string): JsonRecord {
  return requireRecord(terminal.result, `${label}.result`);
}

function requireNonEmptyString(record: JsonRecord, key: string, label: string): string {
  const value = record[key];
  if (typeof value !== 'string' || value.length === 0) {
    throw new Error(`${label}.${key} must be a non-empty string`);
  }
  return value;
}

function requirePositiveInteger(record: JsonRecord, key: string, label: string): number {
  const value = record[key];
  if (typeof value !== 'number' || !Number.isInteger(value) || value <= 0) {
    throw new Error(`${label}.${key} must be a positive integer`);
  }
  return value;
}

function apiPath(path: string): string {
  return new URL(`/api/game/api/${path}`, gameUrl).toString();
}

function gatewayPath(path: string): string {
  return new URL(path, gatewayUrl).toString();
}

function mailboxCredentials(): MailboxCredentials | null {
  const username = process.env.E2E_MAILBOX_USERNAME;
  const password = process.env.E2E_MAILBOX_PASSWORD;
  if (!username || !password) {
    return null;
  }
  return { username, password };
}

function isMailboxScope(value: string): value is MailboxScope {
  return value === 'private' || value === 'national' || value === 'public' || value === 'diplomacy';
}

function denialFixture(): DenialFixture | null {
  const text = process.env.E2E_MAILBOX_DENIAL_TEXT;
  const reason = process.env.E2E_MAILBOX_DENIAL_REASON;
  const scope = process.env.E2E_MAILBOX_DENIAL_SCOPE ?? 'private';
  if (!text || !reason || !isMailboxScope(scope)) {
    return null;
  }
  return { text, reason, scope };
}

async function jsonResponse(response: JsonResponse): Promise<unknown> {
  const body = await response.text();
  try {
    const parsed: unknown = JSON.parse(body);
    return parsed;
  } catch (error) {
    if (error instanceof SyntaxError) {
      return null;
    }
    throw error;
  }
}

async function reachableStatus(request: APIRequestContext, url: string): Promise<number | null> {
  try {
    return (await request.get(url, { timeout: 10_000 })).status();
  } catch (error) {
    if (error instanceof Error) {
      return null;
    }
    throw error;
  }
}

async function unavailableRuntimeReason(request: APIRequestContext): Promise<string | null> {
  const gatewayStatus = await reachableStatus(request, gatewayPath('/login'));
  if (gatewayStatus === null || gatewayStatus >= 500) {
    return `채점대기: gateway runtime is unavailable at ${gatewayUrl}`;
  }

  const gameStatus = await reachableStatus(request, new URL('/game/mailbox', gameUrl).toString());
  if (gameStatus === null || gameStatus >= 500) {
    return `채점대기: game runtime is unavailable at ${gameUrl}`;
  }

  return null;
}

async function login(page: Page, credentials: MailboxCredentials): Promise<void> {
  await page.goto(gatewayPath('/login'), { waitUntil: 'domcontentloaded' });
  const username = page.locator('input[name="username"]');
  const password = page.locator('input[name="password"]');
  await expect(username).toBeEditable();
  await expect(password).toBeEditable();
  await username.fill(credentials.username);
  await password.fill(credentials.password);

  const loginResponse = page.waitForResponse((response) => (
    response.request().method() === 'POST' && new URL(response.url()).pathname.endsWith('/api/auth/login')
  ));
  await page.getByRole('button', { name: '로그인' }).click();
  expect((await loginResponse).status(), 'login response').toBe(200);
  expect(
    (await page.context().cookies()).some((cookie) => cookie.name === 'sam_access' && cookie.httpOnly),
    'login must establish an httpOnly access cookie',
  ).toBeTruthy();
}

async function currentGeneralId(page: Page): Promise<number | null> {
  const response = await page.request.get(apiPath('front-info'));
  expect(response.status(), 'front-info response').toBe(200);
  const frontInfo = requireRecord(await jsonResponse(response), 'front-info');
  const general = requireRecord(frontInfo.general, 'front-info.general');
  if (general.hasGeneral !== true) {
    return null;
  }
  return requirePositiveInteger(general, 'generalId', 'front-info.general');
}

async function terminalCommandResult(page: Page, requestId: string): Promise<JsonRecord> {
  let resolved: JsonRecord | null = null;
  await expect.poll(async () => {
    const response = await page.request.get(apiPath(`command/result/${encodeURIComponent(requestId)}`));
    if (response.status() !== 200) {
      return false;
    }
    const payload = await jsonResponse(response);
    if (!isJsonRecord(payload) || payload.status !== 'RESOLVED') {
      return false;
    }
    resolved = payload;
    return true;
  }, { timeout: commandTimeoutMs }).toBe(true);

  if (resolved === null) {
    throw new Error(`command ${requestId} did not resolve`);
  }
  return resolved;
}

async function createDisposableSelfMessage(page: Page, generalId: number, text: string): Promise<number> {
  const response = await page.request.post(
    apiPath(`command/sendMessage?generalId=${generalId}&turnIdx=0`),
    { data: { mailbox: generalId, text } },
  );
  expect(response.status(), 'sendMessage intake response').toBe(202);
  const intake = requireRecord(await jsonResponse(response), 'sendMessage intake');
  expect(intake.status, 'sendMessage intake status').toBe('AVAILABLE');
  const requestId = requireNonEmptyString(intake, 'requestId', 'sendMessage intake');
  const terminal = await terminalCommandResult(page, requestId);
  expect(terminal.type, 'sendMessage terminal type').toBe('sendMessage');
  expect(terminal.ok, 'sendMessage terminal result').toBe(true);
  return requirePositiveInteger(terminalResult(terminal, 'sendMessage terminal'), 'msgID', 'sendMessage terminal.result');
}

/** 목록 한 줄(행 단추) — 미리 보기에 본문 글자가 그대로 보인다. 지우면 「지운 서신입니다」로 바뀌어 0 줄이 된다. */
function mailboxRow(page: Page, text: string): Locator {
  return page.getByRole('listitem').filter({ has: page.getByText(text, { exact: true }) });
}

/** 행을 눌러 가운데 읽기 칸에 연 카드 — 「지우기」는 여기 있다(내가 보낸 5분 안의 서신만). */
async function openCard(page: Page, text: string): Promise<Locator> {
  await mailboxRow(page, text).getByRole('button').first().click();
  const card = page.getByRole('article').filter({ has: page.getByText(text, { exact: true }) });
  await expect(card).toHaveCount(1);
  return card;
}

function confirmDialog(page: Page): Locator {
  return page.getByRole('dialog', { name: deleteConfirmTitle });
}

function isDeleteCommandResponse(response: Response): boolean {
  return response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/game/api/command/deleteMessage';
}

function isCommandResultResponse(response: Response, requestId: string): boolean {
  return response.request().method() === 'GET'
    && new URL(response.url()).pathname === `/api/game/api/command/result/${encodeURIComponent(requestId)}`;
}

function isMailboxRecentResponse(response: Response): boolean {
  return response.request().method() === 'GET'
    && new URL(response.url()).pathname === '/api/game/api/mailbox/recent';
}

function waitForBrowserTerminalResult(page: Page, requestId: string): Promise<JsonRecord> {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      page.off('response', listener);
      reject(new Error(`browser did not observe a terminal result for ${requestId}`));
    }, commandTimeoutMs);
    const listener = async (response: Response) => {
      if (!isCommandResultResponse(response, requestId)) {
        return;
      }
      const payload = await jsonResponse(response);
      if (!isJsonRecord(payload) || payload.status !== 'RESOLVED') {
        return;
      }
      clearTimeout(timeout);
      page.off('response', listener);
      resolve(payload);
    };
    page.on('response', listener);
  });
}

async function openMailbox(page: Page): Promise<void> {
  await page.goto(new URL('/game/mailbox', gameUrl).toString(), { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { name: '서신', exact: true })).toBeVisible();
}

async function openDiplomacyLetters(page: Page): Promise<void> {
  await page.goto(new URL('/game/global-diplomacy', gameUrl).toString(), { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { name: '외교', exact: true })).toBeVisible();
  await page.getByRole('tab', { name: '외교 서신' }).click();
}

function disposableMessageText(): string {
  return `e2e-mailbox-delete-${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`;
}

test('Given an authenticated disposable general, when canceling then confirming mailbox deletion, the terminal result reloads without that row', async ({ page }) => {
  const credentials = mailboxCredentials();
  test.skip(credentials === null, '채점대기: set E2E_MAILBOX_USERNAME and E2E_MAILBOX_PASSWORD for the live mailbox scenario');
  if (credentials === null) {
    return;
  }

  const unavailableReason = await unavailableRuntimeReason(page.request);
  test.skip(unavailableReason !== null, unavailableReason ?? '');
  if (unavailableReason !== null) {
    return;
  }

  await login(page, credentials);
  const generalId = await currentGeneralId(page);
  test.skip(generalId === null, '채점대기: the configured mailbox account must own an active general');
  if (generalId === null) {
    return;
  }

  await openMailbox(page);
  const text = disposableMessageText();
  const messageId = await createDisposableSelfMessage(page, generalId, text);
  await page.reload({ waitUntil: 'domcontentloaded' });

  const row = mailboxRow(page, text);
  await expect(row, 'new private fixture message').toHaveCount(1);
  const card = await openCard(page, text);
  const deleteButton = card.getByRole('button', { name: '지우기' });
  await expect(deleteButton).toBeVisible();

  const cancelledRequests: string[] = [];
  const recordCancelledDelete = (request: { method(): string; url(): string }) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/game/api/command/deleteMessage') {
      cancelledRequests.push(request.url());
    }
  };
  page.on('request', recordCancelledDelete);
  try {
    await deleteButton.click();
    await expect(confirmDialog(page)).toBeVisible();
    await confirmDialog(page).getByRole('button', { name: '그대로 두기' }).click();
    await expect(confirmDialog(page)).toHaveCount(0);
    expect(cancelledRequests, 'confirm dismissal must not submit deleteMessage').toEqual([]);
  } finally {
    page.off('request', recordCancelledDelete);
  }
  await expect(row, 'cancelled message remains visible').toHaveCount(1);

  const deleteResponse = page.waitForResponse(isDeleteCommandResponse);
  await deleteButton.click();
  await confirmDialog(page).getByRole('button', { name: '지우기' }).click();

  const intakeResponse = await deleteResponse;
  expect(intakeResponse.status(), 'deleteMessage intake response').toBe(202);
  const intakeUrl = new URL(intakeResponse.url());
  expect(intakeUrl.searchParams.get('generalId'), 'deleteMessage generalId').toBe(String(generalId));
  const intakeBody = requireRecord(JSON.parse(intakeResponse.request().postData() ?? ''), 'deleteMessage intake body');
  expect(intakeBody.msgID, 'deleteMessage body').toBe(messageId);
  const intake = requireRecord(await jsonResponse(intakeResponse), 'deleteMessage intake');
  expect(intake.status, 'deleteMessage intake status').toBe('AVAILABLE');
  const requestId = requireNonEmptyString(intake, 'requestId', 'deleteMessage intake');

  const reloadedMailbox = page.waitForResponse(isMailboxRecentResponse);
  const terminal = await waitForBrowserTerminalResult(page, requestId);
  expect(terminal.type, 'deleteMessage terminal type').toBe('deleteMessage');
  expect(terminal.ok, 'deleteMessage terminal result').toBe(true);
  expect(terminalResult(terminal, 'deleteMessage terminal').msgID, 'deleteMessage terminal message id').toBe(messageId);

  const mailboxReload = await reloadedMailbox;
  expect(mailboxReload.status(), 'post-delete mailbox reload response').toBe(200);
  await expect(page.getByText('서신을 지웠습니다', { exact: true })).toBeVisible({ timeout: commandTimeoutMs });
  await expect(row, 'successful terminal result reload removes the message row').toHaveCount(0, { timeout: commandTimeoutMs });
});

test('Given a configured live denial fixture, when delete resolves denied, the reason and row are preserved', async ({ page }) => {
  const credentials = mailboxCredentials();
  const fixture = denialFixture();
  test.skip(credentials === null, '채점대기: set E2E_MAILBOX_USERNAME and E2E_MAILBOX_PASSWORD for the live mailbox scenario');
  test.skip(fixture === null, '채점대기: denial fixture requires E2E_MAILBOX_DENIAL_TEXT, E2E_MAILBOX_DENIAL_REASON, and an optional valid E2E_MAILBOX_DENIAL_SCOPE');
  if (credentials === null || fixture === null) {
    return;
  }

  const unavailableReason = await unavailableRuntimeReason(page.request);
  test.skip(unavailableReason !== null, unavailableReason ?? '');
  if (unavailableReason !== null) {
    return;
  }

  await login(page, credentials);
  const generalId = await currentGeneralId(page);
  test.skip(generalId === null, '채점대기: the configured mailbox account must own an active general');
  if (generalId === null) {
    return;
  }

  let card: Locator;
  /** 거절되면 그대로 남아야 할 것 — 서신 화면은 목록 행, 외교 서신 칸은 카드. */
  let kept: Locator;
  if (fixture.scope === 'diplomacy') {
    // 외교 서신은 서신 화면이 아니라 외교 화면(P-K02, /game/global-diplomacy)의 「외교 서신」 칸에 있다.
    // 그 칸은 좁은 서신 부품(서랍 판)이라 목록 행 대신 카드가 바로 보이고, 지우기 · 확인 대화는 같다.
    await openDiplomacyLetters(page);
    card = page.getByRole('article').filter({ has: page.getByText(fixture.text, { exact: true }) });
    await expect(card, 'configured diplomacy denial fixture card').toHaveCount(1);
    kept = card;
  } else {
    await openMailbox(page);
    if (fixture.scope !== 'private') {
      await page.getByRole('tab', { name: fixture.scope === 'national' ? '세력' : '전체' }).click();
    }
    const row = mailboxRow(page, fixture.text);
    await expect(row, 'configured denial fixture row').toHaveCount(1);
    card = await openCard(page, fixture.text);
    kept = row;
  }
  const deleteButton = card.getByRole('button', { name: '지우기' });
  await expect(deleteButton, 'configured denial fixture must be frontend-deletable').toBeVisible();

  const deleteResponse = page.waitForResponse(isDeleteCommandResponse);
  await deleteButton.click();
  await confirmDialog(page).getByRole('button', { name: '지우기' }).click();

  const intakeResponse = await deleteResponse;
  expect(intakeResponse.status(), 'denial deleteMessage intake response').toBe(202);
  const intake = requireRecord(await jsonResponse(intakeResponse), 'denial deleteMessage intake');
  expect(intake.status, 'denial deleteMessage intake status').toBe('AVAILABLE');
  const requestId = requireNonEmptyString(intake, 'requestId', 'denial deleteMessage intake');

  const terminal = await waitForBrowserTerminalResult(page, requestId);
  expect(terminal.type, 'denial deleteMessage terminal type').toBe('deleteMessage');
  expect(terminal.ok, 'denial deleteMessage terminal result').toBe(false);
  expect(terminal.reason, 'denial reason').toBe(fixture.reason);

  await expect(page.getByText(fixture.reason, { exact: true })).toBeVisible({ timeout: commandTimeoutMs });
  await expect(kept, 'denied delete keeps the message row').toHaveCount(1);
});
