import { expect, test, type Page } from '@playwright/test';
import { hierarchyFixture } from '../../__tests__/fixtures/retinue-hierarchy';
import { retinueTable, serveCampaign } from '../support/campaignFixtures';
import { BOTH, expectNoHorizontalOverflow, press, smallTouchTargets } from '../support/parity';

async function open(page: Page, empty = false) {
  const served = await serveCampaign(page, retinueTable(empty ? 'empty' : 'full'));
  await page.goto('/game/retinue');
  await expect(page.getByRole('region', { name: '부 조직도' })).toBeVisible();
  return served;
}

test('D164 structural chart keeps three levels and direct/total counts @both', { tag: BOTH }, async ({ page }, info) => {
  const served = await open(page);
  const chart = page.getByRole('region', { name: '부 조직도' });
  const self = chart.locator('[data-general-id="7"]');
  await expect(self).toContainText('직속 장수2명');
  await expect(self).toContainText('전체 휘하3명');
  await expect(chart.getByRole('list', { name: '상관 계보' })).toContainText('직속 상관가상 상관2단계 상관가상 최상위');
  const descendant = chart.locator('[data-general-id="201"]');
  await descendant.scrollIntoViewIfNeeded();
  await expect(descendant).toHaveAttribute('data-depth', '2');
  await expect(descendant).toContainText('가상 직속의 직속');
  expect(served.unknown.filter((u) => /\/api\/retinue/.test(u))).toEqual([]);
  await expectNoHorizontalOverflow(page);
  await info.attach('D164-structural-chart', { body: await chart.screenshot(), contentType: 'image/png' });
});

test('D164 zero direct people retains self and ancestors at narrow widths @both', { tag: BOTH }, async ({ page }) => {
  await open(page, true);
  const chart = page.getByRole('region', { name: '부 조직도' });
  await expect(chart.getByText('아직 직속 장수가 없습니다.')).toBeVisible();
  for (const width of [320, 375, 414, 768]) {
    await page.setViewportSize({ width, height: 900 });
    await expect(chart.getByText('가상 상관', { exact: true })).toBeVisible();
    await expect(chart.getByText('본인', { exact: true })).toBeVisible();
    await expectNoHorizontalOverflow(page);
  }
});

test('D164 unavailable and read failure retry without fabricating an empty tree @both', { tag: BOTH }, async ({ page }, info) => {
  await serveCampaign(page, retinueTable('full'));
  let mode: 'unavailable' | 'error' | 'ready' = 'unavailable';
  const actors: string[] = [];
  await page.route((url) => url.pathname === '/api/game/api/retinue/hierarchy', (route) => {
    actors.push(new URL(route.request().url()).searchParams.get('generalId') ?? '');
    return route.fulfill(mode === 'error' ? { status: 503, json: {} }
      : { json: mode === 'unavailable' ? { status: 'UNAVAILABLE', actorGeneralId: 7, superiors: [], nodes: [] } : hierarchyFixture() });
  });
  await page.goto('/game/retinue');
  const chart = page.getByRole('region', { name: '부 조직도' });
  await expect(chart.getByText('조직도를 확인할 수 없습니다.')).toBeVisible();
  await expect(chart.getByRole('list')).toHaveCount(0);
  await expect(chart.getByText('아직 직속 장수가 없습니다.')).toHaveCount(0);
  await page.evaluate(() => document.fonts.ready);
  expect(await smallTouchTargets(page, '[aria-label="부 조직도"]')).toEqual([]);
  mode = 'error';
  await press(chart.getByRole('button', { name: '다시 읽기' }), info);
  await expect(chart.getByText('조직도를 불러오지 못했습니다.')).toBeVisible();
  mode = 'ready';
  await press(chart.getByRole('button', { name: '다시 시도' }), info);
  await expect(chart.locator('[data-general-id="201"]')).toHaveAttribute('data-depth', '2');
  expect(actors).toEqual(['7', '7', '7']);
});
