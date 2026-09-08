// 접근성 스모크(계획 Phase 6) — 로그인·로비·작전실·커뮤니티·콘솔 5화면에서 axe critical 0.
//
// 라이브 스택이 필요하다(게이트웨이 3000 · 게임 3001 · gateway-api · game-api). 스택 없이 돌리면
// 화면을 못 열어 실패한다 — 조용히 건너뛰지 않는다(0건이 「검사가 안 돌았다」를 뜻하면 안 된다).
// 계정은 매 실행마다 새로 만들고, 콘솔 화면은 ADMIN 이 필요하므로 없으면 그 화면만 사유를 남기고 뺀다.
import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

const gatewayUrl = process.env.E2E_GATEWAY_URL ?? 'http://localhost:3000';
const gameUrl = process.env.E2E_GAME_URL ?? 'http://localhost:3001';
const adminUsername = process.env.E2E_ADMIN_USERNAME;
const adminPassword = process.env.E2E_ADMIN_PASSWORD;

type Violation = { id: string; impact?: string | null; nodes: unknown[] };

async function scan(page: Page, label: string): Promise<Violation[]> {
  const result = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  const critical = result.violations.filter((v) => v.impact === 'critical');
  if (critical.length > 0) {
    console.log(`[axe] ${label} critical:`, JSON.stringify(critical.map((v) => ({
      id: v.id, help: v.help, nodes: v.nodes.map((n) => n.target).slice(0, 5),
    })), null, 2));
  }
  const serious = result.violations.filter((v) => v.impact === 'serious');
  console.log(`[axe] ${label}: critical ${critical.length} · serious ${serious.length}`
    + (serious.length > 0 ? ` (${serious.map((v) => v.id).join(', ')})` : ''));
  return critical as Violation[];
}

async function register(page: Page): Promise<{ username: string; password: string }> {
  const suffix = `${Date.now()}${Math.floor(Math.random() * 10_000)}`;
  const username = `a11y_${suffix}`;
  const password = `A11y!${suffix}a`;
  const response = await page.request.post(`${gatewayUrl}/api/auth/register`, {
    data: { username, password, passwordConfirm: password, nickname: `a11y-${suffix}`.slice(0, 20) },
  });
  expect(response.status(), 'actual register response').toBe(200);
  return { username, password };
}

test.describe('a11y smoke (axe critical 0)', () => {
  test('로그인·로비·작전실·커뮤니티·콘솔', async ({ page }) => {
    const failures: string[] = [];

    await page.goto(`${gatewayUrl}/login`, { waitUntil: 'networkidle' });
    if ((await scan(page, '로그인')).length > 0) failures.push('로그인');

    const { username, password } = await register(page);
    await page.request.post(`${gatewayUrl}/api/auth/login`, { data: { username, password } });

    await page.goto(`${gatewayUrl}/`, { waitUntil: 'networkidle' });
    if ((await scan(page, '로비')).length > 0) failures.push('로비');

    await page.goto(`${gameUrl}/game`, { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('.game-chrome, .join-form, main', { timeout: 60_000 });
    await page.waitForTimeout(2_000);
    if ((await scan(page, '작전실')).length > 0) failures.push('작전실');

    await page.goto(`${gameUrl}/game/board`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(2_000);
    if ((await scan(page, '커뮤니티')).length > 0) failures.push('커뮤니티');

    if (adminUsername && adminPassword) {
      await page.request.post(`${gatewayUrl}/api/auth/logout`);
      await page.request.post(`${gatewayUrl}/api/auth/login`, {
        data: { username: adminUsername, password: adminPassword },
      });
      await page.goto(`${gatewayUrl}/admin`, { waitUntil: 'networkidle' });
      if ((await scan(page, '콘솔')).length > 0) failures.push('콘솔');
    } else {
      console.log('[axe] 콘솔: 건너뜀 — E2E_ADMIN_USERNAME/PASSWORD 미설정(UNKNOWN, 0건 아님)');
    }

    expect(failures, `axe critical 위반이 남은 화면: ${failures.join(', ')}`).toEqual([]);
  });
});
