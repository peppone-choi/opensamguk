// 공통 상태 화면(보드 P-X01, K3 2026-10-05) — 게이트웨이의 없는 주소는 「찾는 화면이 없습니다」 + 로비로.
import { expect, test } from '@playwright/test';
import { BOTH } from '../../../game/e2e/support/parity';

test('없는 주소: 「찾는 화면이 없습니다」 · 로비로', { tag: BOTH }, async ({ page }) => {
  await page.goto('/no-such-page', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('status').filter({ hasText: '찾는 화면이 없습니다' })).toBeVisible({ timeout: 60_000 });
  await expect(page.getByRole('link', { name: '로비로' })).toHaveAttribute('href', '/lobby');
});
