import { expect, test } from '@playwright/test';
import { frontInfo, serveCampaign } from '../support/campaignFixtures';
import { BOTH } from '../support/parity';

const person = (generalId: number, name: string, injury: number | null) => ({
  generalId, name, nationId: 1, nationName: '합성국', nationColor: '#4f7fbf', npc: 0, npcState: 0,
  officerLevel: 1, officerLevelText: '일반', leadership: 71, strength: 71, intel: 71, politics: 71, charm: 71,
  explevel: 0, honorText: '-', dedlevel: 0, dedLevelText: '-', bill: 0, cityName: '',
  picture: null, imageServer: 0, age: 20, personalText: '-', specialDomesticText: '-', specialWarText: '-',
  injury, lbonus: 0, killturn: null, refreshScoreTotal: 0, crew: 0, gold: 0, rice: 0, belong: 0,
  dedication: 0, experience: 0, personal: 'None', special: 'None', special2: 'None', ownerName: null,
});

for (const path of ['/game/generals', '/game/rankings/generals', '/game/my-generals']) {
  test(`부상 비공개·실제 부상·실제 건강을 구분한다: ${path}`, { tag: [BOTH] }, async ({ page }, info) => {
    const writes: string[] = [];
    page.on('request', request => {
      if (request.url().includes('/api/game/') && !['GET', 'HEAD'].includes(request.method())) writes.push(new URL(request.url()).pathname);
    });
    // Every API request is synthetic, including shell reads outside the campaign fixture's routes.
    await page.route('**/api/**', route => route.fulfill({ status: 404, json: {} }));
    const people = [person(1, '숨긴합성', null), person(2, '다친합성', 20), person(3, '건강합성', 0)];
    await serveCampaign(page, {
      '/api/front-info': frontInfo(), '/api/generals': people,
      '/api/command/presence': { result: true },
      '/api/my-generals': { result: true, nationId: 1, generals: people },
    });
    await page.goto(path, { waitUntil: 'domcontentloaded' });
    const hidden = page.getByRole('row', { name: /숨긴합성/ });
    await expect(hidden).toBeVisible();
    await expect(hidden.getByText('부상 정보 미확인')).toBeVisible();
    await expect(hidden).not.toContainText(/NaN|부상 없음|건강/);
    await expect(hidden.locator('.stat--wounded')).toHaveCount(0);
    await expect(hidden.getByText('71', { exact: true })).toHaveCount(5);
    const injured = page.getByRole('row', { name: /다친합성/ });
    const healthy = page.getByRole('row', { name: /건강합성/ });
    await expect(injured.getByText('부상 정보 미확인')).toHaveCount(0);
    await expect(healthy.getByText('부상 정보 미확인')).toHaveCount(0);
    if (path !== '/game/generals') {
      await expect(injured.locator('.stat--wounded')).toHaveCount(3);
      await expect(injured.getByText('56', { exact: true })).toHaveCount(3);
    }
    // The existing shell presence pulse is also mocked; no gameplay command is submitted.
    expect(writes).toEqual(['/api/game/api/command/presence']);
    await page.screenshot({ path: info.outputPath('injury-visibility.png'), fullPage: true });
  });
}
