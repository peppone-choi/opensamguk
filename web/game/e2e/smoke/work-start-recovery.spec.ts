// Browser contract smoke with mocked HTTP and turn state; no authentication or engine claim.
import { expect, test } from '@playwright/test';
import { frontInfo, posts, retinue, serveCampaign } from '../support/campaignFixtures';
import { BOTH, expectNoHorizontalOverflow, press } from '../support/parity';

for (const [work, label] of [['ROAD', '도로'], ['FORTIFICATION', '성방']] as const) {
  test(`[work.start] ${label} 자리 재조회 → 접수 → 다음 순 진척·완공 읽기`, { tag: [BOTH] }, async ({ page }, info) => {
    const zero = { money: 0, grain: 0, iron: 0, timber: 0, horses: 0 };
    let phase = 1;
    let submitted = false;
    let targetReads = 0;
    let targetsHealthy = false;
    let writes = 0;
    const choice = { work, label, available: true, blocked: null, cost: zero, estimatedPhases: 2 };
    await serveCampaign(page, {
      '/api/front-info': () => {
        const front = frontInfo();
        return { ...front, global: { ...front.global, year: 200, month: 1, turnPhase: phase,
          turnPhaseText: ['상순', '중순', '하순'][phase - 1] } };
      },
      '/api/posts': posts('empty'), '/api/retinue': retinue('empty'),
      '/api/policies': { status: 'READY', countyOptions: [], corpsOptions: [], defaultPolicy: null, corps: [], counties: [] },
      '/api/warehouses': { status: 'READY', warehouses: [] },
      '/api/works': () => ({ status: 'READY', counties: [{ countyId: 129, provinceId: 'p-yang', provinceIds: ['p-yang'],
        name: '양성현', commanderyName: '영천군', warehouse: zero,
        active: submitted && phase < 3 ? { work, label, percent: phase === 1 ? 0 : 50, remainingPhases: 3 - phase,
          remainingCost: zero, stopReasonText: null, startsAtNextBoundary: phase === 1 } : null,
        completed: submitted && phase === 3 ? [{ work, label, edgeId: 'e-1', completedAt: { year: 200, month: 1, phase: 3 } }] : [],
        startable: submitted ? [] : [choice] }] }),
    });
    await page.route((url) => url.pathname === '/api/game/api/road-forts', async (route) => {
      targetReads++;
      if (!targetsHealthy) {
        await route.fulfill({ status: 503, json: { private: 'upstream diagnostics' } });
        return;
      }
      await route.fulfill({ json: { status: 'READY', roadMode: true, forts: [], gates: [{ edgeId: 'e-1',
        fromProvinceId: 'p-yang', toProvinceId: 'p-xu', active: work === 'FORTIFICATION', buildable: true,
        historicalRouteIds: [], fortCells: [{ row: 5, col: 6, provinceId: 'p-yang' }] }] } });
    });
    await page.route((url) => url.pathname === '/api/game/api/commands/work/start', async (route) => {
      expect(route.request().method()).toBe('POST');
      expect(route.request().postDataJSON()).toEqual({ countyId: 129, work, edgeId: 'e-1',
        ...(work === 'FORTIFICATION' ? { row: 5, col: 6 } : {}) });
      writes++; submitted = true;
      await route.fulfill({ status: 202, json: { status: 'AVAILABLE', requestId: 'work-129' } });
    });
    await page.goto('/game/territory?view=work', { waitUntil: 'domcontentloaded' });
    await press(page.getByRole('button', { name: '새 공사' }), info);
    const sheet = page.getByRole('dialog', { name: '양성현 공사' });
    await press(sheet.getByRole('option', { name: new RegExp(label) }), info);
    await expect(sheet.getByRole('button', { name: '이 공사로' })).toHaveAttribute('aria-disabled', 'true');
    await expect(sheet).not.toContainText('upstream diagnostics');
    expect(writes).toBe(0);
    const retry = sheet.getByRole('button', { name: '도로 · 보루 자리 다시 시도' });
    await expect(retry).toBeVisible();
    expect(targetReads).toBeGreaterThan(0);
    // Keep the outage through the client's transport retries; only this user retry recovers it.
    targetsHealthy = true;
    await press(retry, info);
    await press(sheet.getByRole('option', { name: /접경/ }), info);
    const submit = sheet.getByRole('button', { name: '이 공사로' });
    await expect(submit).not.toHaveAttribute('aria-disabled', 'true');
    await press(submit, info);
    await expect(sheet).toHaveCount(0);
    await expect(page.getByRole('meter', { name: `양성현 ${label} 진척` })).toHaveAttribute('aria-valuenow', '0');
    await expect(page.getByRole('main', { name: '게임 콘텐츠' })).toContainText('공사를 접수했습니다 — 다음 순 경계부터 진척합니다.');
    phase = 2;
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('meter', { name: `양성현 ${label} 진척` })).toHaveAttribute('aria-valuenow', '50');
    phase = 3;
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('main', { name: '게임 콘텐츠' })).toContainText(`${label} · 200년 1월 하순`);
    expect(writes).toBe(1);
    await expectNoHorizontalOverflow(page);
    await page.screenshot({ path: info.outputPath('work-completed.png'), fullPage: true });
  });
}
