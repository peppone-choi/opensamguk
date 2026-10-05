// 인물 상세(P-R03) 스모크 — 합성 자료로 백엔드 없이, 데스크톱 · 모바일 같은 흐름(@both).
// 인물 상세 읽기(K4-13)가 오기 전이라 나(front-info) · 내 부 인물(부 · 배치 읽기)만 채우고, 그 밖은 「서버 대기」다.
import { expect, test, type Locator, type Page } from '@playwright/test';
import { posts, retinueTable, serveCampaign } from '../support/campaignFixtures';
import { BOTH, coveredTargets, expectNoHorizontalOverflow, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

/** 이 화면이 부르는 조회 — 셸 · 도움말 조회는 각자 스모크 몫. */
const MINE = /\/api\/(retinue|posts|commands)/;
const coveredIn = (root: Locator): Promise<string[]> => coveredTargets(root, 'a, button, [role="option"]');

async function quality(page: Page) {
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  expect(await coveredIn(main)).toEqual([]);
  await expectNoHorizontalOverflow(page);
  expect(await smallTouchTargets(page, 'main')).toEqual([]);
  expect(await titleOnlyInfo(page, 'main')).toEqual([]);
  // 서버 원문(영어 · 상태 코드)을 싣지 않는다 — 「NPC」는 화면 용어(보드 사람/NPC 칩)라 뺀다
  expect((await main.innerText()).replace(/NPC/g, '')).not.toMatch(/[A-Za-z]{3,}/);
}

test('나 — 히어로 · 능력 · 자리 · 상태, 계책 기여 · 관직 카드는 준비 중, 「내 부로」', { tag: [BOTH] }, async ({ page }) => {
  const served = await serveCampaign(page, retinueTable('full'));
  await page.goto('/game/retinue/people/7', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { name: '하후돈', exact: true })).toBeVisible({ timeout: 60_000 });
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  await expect(main.getByRole('region', { name: '계책 기여' })).toContainText('계책 기여 — 준비 중');
  await expect(main.getByRole('region', { name: '인물 관직 카드' })).toContainText('관직 카드 — 준비 중');
  await expect(main.getByRole('link', { name: '내 부로' })).toBeVisible();
  // 나는 front-info 로 그린다 — 부 읽기를 부르지 않는다
  expect(served.unknown.filter((u) => MINE.test(u))).toEqual([]);
  await quality(page);
});

test('내 부 NPC — 충성 · 결속, 「자리에 배치」 → 이 화면 배치 시트 → 접수 한 줄', { tag: [BOTH] }, async ({ page }, info) => {
  // 배치 단추는 서버가 NPC 라고 알려 줄 때만(K4-18 isHuman false). 공용 자료는 isHuman 이 없어(모름 → 단추 없음) 이 시험에서만 채운다.
  const npcPosts = { ...posts('full'), cards: posts('full').cards.map((c) => ({ ...c, isHuman: false })) };
  await serveCampaign(page, { ...retinueTable('full'), '/api/posts': npcPosts, '/api/commands/placement/assign': { status: 'AVAILABLE' } });
  await page.goto('/game/retinue/people/101', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { name: '허저', exact: true })).toBeVisible({ timeout: 60_000 });
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  await expect(main).toContainText(/충성 \d+/);
  await quality(page);
  await press(main.getByRole('button', { name: '자리에 배치' }), info);
  const sheet = page.getByRole('dialog', { name: '허저 배치' });
  await press(sheet.getByRole('option', { name: '자리에서 풀기' }), info);
  expect(await coveredIn(sheet)).toEqual([]);
  await press(sheet.getByRole('button', { name: '이 자리로' }), info);
  await expect(page.getByRole('status').filter({ hasText: '배치를' })).toContainText('배치를 접수했습니다');
});

test('나도 내 부도 아닌 인물 — 이름을 짓지 않고 서버 대기 안내 + 인물 일람으로', { tag: [BOTH] }, async ({ page }) => {
  await serveCampaign(page, retinueTable('full'));
  await page.goto('/game/retinue/people/999', { waitUntil: 'domcontentloaded' });
  await expect(page.getByText('이 인물의 상세는 아직 볼 수 없습니다')).toBeVisible({ timeout: 60_000 });
  await expect(page.getByRole('link', { name: '인물 일람으로' })).toBeVisible();
  await quality(page);
});
