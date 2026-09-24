import { expect, test, type Page, type Response } from '@playwright/test';

const gameUrl = process.env.E2E_GAME_URL ?? 'http://localhost:3001';
const gatewayUrl = process.env.E2E_GATEWAY_URL ?? 'http://localhost:3000';

async function commandResult(page: Page, response: Response) {
  expect(response.status()).toBe(202);
  const { requestId } = await response.json() as { requestId: string };
  await expect.poll(async () => {
    const result = await page.request.get(`${gameUrl}/api/game/api/command/result/${requestId}`);
    return (await result.json() as { status: string }).status;
  }, { timeout: 180_000, intervals: [1000, 3000] }).toBe('RESOLVED');
}

async function canvasHit(page: Page, selector: string) {
  const canvas = page.locator(selector).first();
  await expect(canvas).toBeVisible();
  const box = await canvas.boundingBox();
  expect(box).not.toBeNull();
  const x = Math.round(box!.x + box!.width / 2);
  const y = Math.round(box!.y + box!.height / 2);
  const hit = await page.evaluate(({ x, y }) => {
    const element = document.elementFromPoint(x, y);
    return element?.tagName.toLowerCase() ?? null;
  }, { x, y });
  expect(hit).toBe('canvas');
  await page.mouse.move(x, y);
  await page.mouse.wheel(0, -400);
  await page.mouse.down();
  await page.mouse.move(x + 60, y + 40, { steps: 5 });
  await page.mouse.up();
  return { x, y, hit };
}

test('one HWIHA board across public preview and both operation rooms', async ({ page, context }, testInfo) => {
  test.skip(process.env.SCENARIO_QA_TURNTERM !== '1', 'Requires the isolated HWIHA fixture');
  const isoRequests: string[] = [];
  const retiredSpritePath = `/sprites/${'iso'}${'2d'}/`;
  page.on('request', request => {
    if (request.url().includes(retiredSpritePath)) isoRequests.push(request.url());
  });

  await page.goto(`${gatewayUrl}/login`);
  const preview = page.locator('.map-preview canvas').first();
  await expect(preview).toBeVisible({ timeout: 60_000 });
  await expect(page.locator('.map-preview')).not.toContainText('지원하지 않는 지도 판');
  await testInfo.attach('public-login-preview', { body: await page.screenshot(), contentType: 'image/png' });

  const suffix = Date.now().toString();
  const username = `map_${suffix}`;
  const password = `Map!${suffix}a`;
  await page.goto(`${gatewayUrl}/join`);
  for (const [name, value] of Object.entries({ username, password, passwordConfirm: password, nickname: `map${suffix}` })) {
    await page.locator(`input[name="${name}"]`).fill(value);
  }
  const registration = page.waitForResponse(response => response.request().method() === 'POST' && response.url().includes('/api/auth/register'));
  await page.getByRole('button', { name: /가입/ }).click();
  expect((await registration).status()).toBe(200);
  expect((await context.cookies()).some(cookie => cookie.name === 'sam_access' && cookie.httpOnly)).toBe(true);

  await page.goto(`${gameUrl}/game/join`);
  await page.locator('form input[type="text"]').first().fill(`QA${suffix}`);
  page.on('dialog', dialog => void dialog.accept());
  const creation = page.waitForResponse(response => response.request().method() === 'POST' && response.url().includes('/api/game/api/join'));
  await page.getByRole('button', { name: '장수 생성', exact: true }).click();
  await commandResult(page, await creation);
  await expect.poll(async () => {
    const response = await page.request.get(`${gameUrl}/api/game/api/front-info`);
    return (await response.json() as { general: { hasGeneral: boolean } }).general.hasGeneral;
  }).toBe(true);

  await page.goto(`${gameUrl}/game`);
  await expect(page.locator('.map-viewer canvas')).toBeVisible({ timeout: 60_000 });
  const mainFocus = (await page.getByTestId('commandery-focus').first().textContent())?.trim();
  expect(mainFocus).toBeTruthy();
  await testInfo.attach('main-operation-room', { body: await page.screenshot(), contentType: 'image/png' });
  await testInfo.attach('main-map-canvas', { body: await page.locator('.map-viewer canvas').first().screenshot(), contentType: 'image/png' });
  const mainHit = await canvasHit(page, '.map-viewer canvas');

  await page.goto(`${gameUrl}/game/hwiha/war-room`);
  await expect(page.locator('canvas[aria-label^="천하 형세"]')).toBeVisible({ timeout: 60_000 });
  const warFocus = (await page.getByTestId('commandery-focus').first().textContent())?.trim();
  expect(warFocus).toBe(mainFocus);
  await testInfo.attach('hwiha-operation-room', { body: await page.screenshot(), contentType: 'image/png' });
  await testInfo.attach('hwiha-map-canvas', { body: await page.locator('canvas[aria-label^="천하 형세"]').screenshot(), contentType: 'image/png' });
  const warHit = await canvasHit(page, 'canvas[aria-label^="천하 형세"]');

  expect(isoRequests).toEqual([]);
  await testInfo.attach('map-verification', {
    body: JSON.stringify({ mainFocus, warFocus, mainHit, warHit, isoSpriteRequests: isoRequests.length }, null, 2),
    contentType: 'application/json',
  });
});
