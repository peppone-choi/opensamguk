// 모바일 시트 포커스 트랩 + 390px + reduced-motion (계획 Phase 6). **라이브 스택 전용**.
// 「트랩 코드가 있다」가 아니라 「Tab 이 시트를 벗어나지 않는다」를 키보드로 확인한다.
//
// 부서 시트는 장수가 있는 계정의 게임 쉘에서만 뜬다(장수 없는 계정은 /game 이 임관으로 보낸다).
// 그래서 계정을 env 로 받는다 — 없으면 조용히 넘어가지 않고 사유와 함께 실패한다.
//   E2E_PLAYER_USERNAME=<장수 보유 계정> E2E_PLAYER_PASSWORD=... pnpm exec playwright test e2e/mobile-focus-trap-live.spec.ts
import { expect, test } from '@playwright/test';

const gatewayUrl = process.env.E2E_GATEWAY_URL ?? 'http://localhost:3000';
const gameUrl = process.env.E2E_GAME_URL ?? 'http://localhost:3001';
const playerUsername = process.env.E2E_PLAYER_USERNAME;
const playerPassword = process.env.E2E_PLAYER_PASSWORD;

test.use({ viewport: { width: 390, height: 844 } });

test('390px 부서 시트가 포커스를 가두고 Escape 로 트리거에 되돌린다', async ({ page }) => {
  expect(playerUsername && playerPassword,
    'E2E_PLAYER_USERNAME/E2E_PLAYER_PASSWORD 에 장수 보유 계정이 필요하다(시트는 게임 쉘에만 있다)').toBeTruthy();
  await page.request.post(`${gatewayUrl}/api/auth/login`, {
    data: { username: playerUsername, password: playerPassword },
  });

  await page.goto(`${gameUrl}/game`, { waitUntil: 'domcontentloaded' });
  const moreButton = page.locator('.game-bottom-item', { hasText: '더보기' }).first();
  await expect(moreButton, '모바일 하단 나브의 더보기 버튼').toBeVisible({ timeout: 90_000 });

  await moreButton.click();
  const sheet = page.locator('.dept-sheet');
  await expect(sheet).toBeVisible();
  await expect(sheet).toHaveAttribute('aria-modal', 'true');

  const count = await sheet.locator('button:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])').count();
  expect(count, '시트 안 포커스 가능한 요소').toBeGreaterThan(0);
  // 요소 수보다 많이 눌러도 포커스가 시트를 벗어나면 안 된다(순환).
  for (let i = 0; i < count + 3; i += 1) {
    await page.keyboard.press('Tab');
    expect(await page.evaluate(() => {
      const s = document.querySelector('.dept-sheet');
      return s != null && document.activeElement != null && s.contains(document.activeElement);
    }), `Tab ${i + 1}회 뒤 포커스가 시트 안에 있어야 한다`).toBe(true);
  }
  await page.keyboard.press('Shift+Tab');
  expect(await page.evaluate(() => {
    const s = document.querySelector('.dept-sheet');
    return s != null && document.activeElement != null && s.contains(document.activeElement);
  }), 'Shift+Tab 뒤에도 시트 안').toBe(true);

  await page.keyboard.press('Escape');
  await expect(sheet).toBeHidden();
  expect(await page.evaluate(() => document.activeElement?.textContent?.trim() ?? ''), 'Escape 뒤 트리거로 복귀')
    .toContain('더보기');
});

test('390px 로그인 화면에 가로 넘침이 없다', async ({ page }) => {
  await page.goto(`${gatewayUrl}/login`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1_500);
  const overflow = await page.evaluate(() =>
    document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow, '가로 넘침(px)').toBeLessThanOrEqual(0);
});

test('reduced-motion 에서 순 전환 연출 시간이 0 이 된다', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.goto(`${gatewayUrl}/login`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1_500);
    const turn = await page.evaluate(() =>
      getComputedStyle(document.documentElement).getPropertyValue('--motion-turn').trim());
    expect(turn, '--motion-turn 은 reduced-motion 에서 0 이어야 한다').toBe('0ms');
});
