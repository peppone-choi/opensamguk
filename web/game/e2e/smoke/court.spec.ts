// 조정(P-K01) 스모크 — 합성 자료(e2e/support/campaignFixtures)로 백엔드 없이, 데스크톱 · 모바일 같은 흐름(@both).
// 받은 요청 띠(정치 동의 · 발령 응답의 새 길) · 막힌 조정 결정의 서버 사유 · 화면 규칙(44 · title 전용 · 넘침).
import { expect, test, type Locator, type Page } from '@playwright/test';
import { frontInfo, serveCampaign } from '../support/campaignFixtures';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const phase = { year: 200, month: 3, phase: 2 };
const table = {
  '/api/front-info': frontInfo(),
  '/api/commands/dispatches': { result: true, dispatches: [
    { dispatchId: 'd1', issuerId: 1, targetId: 7, countyId: 2, issuerLabel: '조조', countyLabel: '양적현', issuedAt: phase, dueAt: phase, status: 'PENDING' },
  ] },
  '/api/commands/political-consent-options': [],
  '/api/commands/dispatch-options': { result: false, code: 'NOT_LORD', reason: '발령은 주공만 할 수 있습니다.', targets: [], counties: [] },
  '/api/commands/legacy-court-options': { inputId: 'court.moveCapital', available: false, reason: '군주만 할 수 있습니다.', choices: [] },
  '/api/retinue': { status: 'READY', renown: 30, costSum: 0, overCapacity: false, people: [], units: [] },
  '/api/map/preview': { mapCode: 'x', width: 1, height: 1, cities: [{ id: 3, name: '허현' }], nations: [] },
};
/** 이 화면이 부르는 조회 — 셸 자신의 조회(서신 배지 등)는 셸 스모크 몫이라 여기서 세지 않는다. */
const MINE = /\/api\/(commands|retinue|map\/preview)/;

/** 본문 왼쪽 여백 — 셸 본문은 여백이 없어 화면이 준다(보드 desk_main 12 · mob_main 12). */
async function insetFromMain(page: Page, target: Locator): Promise<number> {
  const main = await page.getByRole('main', { name: '게임 콘텐츠' }).boundingBox();
  const box = await target.boundingBox();
  return Math.round((box?.x ?? 0) - (main?.x ?? 0));
}

/** 누를 것의 가운데를 다른 상자가 덮는지(K10 「덮임」 · 셸 스모크와 같은 방법 — elementFromPoint). 화면 밖은 세지 않는다. */
async function coveredIn(root: Locator): Promise<string[]> {
  return root.evaluate((r) => {
    const out: string[] = [];
    for (const el of Array.from(r.querySelectorAll<HTMLElement>('a, button, select, input'))) {
      const b = el.getBoundingClientRect();
      if (b.width === 0 || b.height === 0) continue;
      const cx = b.x + b.width / 2;
      const cy = b.y + b.height / 2;
      if (cx < 0 || cy < 0 || cx > innerWidth || cy > innerHeight) continue;
      const hit = document.elementFromPoint(cx, cy);
      if (hit !== el && !el.contains(hit)) out.push(`${(el.textContent ?? '').trim()} ← ${hit?.tagName}.${hit?.className}`);
    }
    return out;
  });
}

test('받은 요청 · 막힌 결정 사유 · 44 · title 전용 · 넘침', { tag: [BOTH] }, async ({ page }, info) => {
  const served = await serveCampaign(page, table);
  await page.goto('/game/court', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 2, name: '조정' })).toBeVisible({ timeout: 60_000 });
  if (isMobile(info)) {
    const list = page.getByRole('list', { name: '조정 결정' });
    await expect(list.getByRole('listitem').first()).toContainText('응답 대기 1');
    expect(await insetFromMain(page, list)).toBeGreaterThanOrEqual(12);
    // 설명 글이 오른쪽에서 잘리지 않는다(넘친 글 = scrollWidth > clientWidth).
    expect(await list.locator('.os-opt__sub').evaluateAll((els) => els.filter((e) => e.scrollWidth > e.clientWidth + 1).map((e) => e.textContent))).toEqual([]);
    // 목록 끝까지 밀어도 마지막 결정이 하단 탭 아래에 깔리지 않는다(K10: 옛 조정 select 를 셸 탭이 덮음).
    await list.getByRole('button').last().scrollIntoViewIfNeeded();
    expect(await coveredIn(list)).toEqual([]);
    await press(list.getByRole('button').first(), info);
    const sheet = page.getByRole('dialog', { name: '받은 요청' });
    expect(await coveredIn(sheet)).toEqual([]);
    expect(await smallTouchTargets(page, '[role="dialog"]')).toEqual([]);
    await expect(sheet.getByRole('button', { name: '닫기' })).toBeVisible();
    // 시트 머리 글이 시트 테두리에 붙지 않는다.
    const sheetBox = await sheet.boundingBox();
    const headBox = await sheet.getByRole('heading', { name: '받은 요청' }).boundingBox();
    expect(Math.round((headBox?.x ?? 0) - (sheetBox?.x ?? 0))).toBeGreaterThanOrEqual(8);
  } else {
    expect(await coveredIn(page.getByRole('main', { name: '게임 콘텐츠' }))).toEqual([]);
  }
  await expect(page.getByRole('button', { name: '수락' })).toBeVisible();
  await expect(page.getByRole('button', { name: '거절' })).toBeVisible();
  if (!isMobile(info)) {
    expect(await insetFromMain(page, page.getByRole('region', { name: '받은 요청' }))).toBeGreaterThanOrEqual(12);
    const decisions = page.getByRole('region', { name: '조정 결정' });
    await expect(decisions).toContainText('군주만 할 수 있습니다.');
    await expect(page.getByRole('region', { name: '천도' })).toContainText('지금 수도 — 허현');
    await expect(page.getByRole('button', { name: /새 발령/ })).toHaveAttribute('aria-disabled', 'true');
  }
  expect(served.unknown.filter((u) => MINE.test(u))).toEqual([]);
  // 서버 원문(영어)은 화면에 두지 않는다(K3 공용 규칙 — 코드는 StatusView 오류 번호에만).
  expect(await page.getByRole('main', { name: '게임 콘텐츠' }).innerText()).not.toMatch(/[A-Za-z]{3,}/);
  await expectNoHorizontalOverflow(page);
  expect(await smallTouchTargets(page, 'main')).toEqual([]);
  expect(await titleOnlyInfo(page, 'main')).toEqual([]);
});

test('읽기 실패 — 빈 목록 · 「없습니다」 대신 한국어 오류, 서버 원문 0', { tag: [BOTH] }, async ({ page }, info) => {
  // 부 인물 · 내린 발령 조회를 표에서 빼면 404 — 화면은 「없습니다」가 아니라 실패를 말해야 한다(리뷰 #1128).
  const { '/api/retinue': _retinue, '/api/commands/dispatches': _pending, ...rest } = table;
  await serveCampaign(page, rest);
  await page.goto('/game/court', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 2, name: '조정' })).toBeVisible({ timeout: 60_000 });
  if (isMobile(info)) {
    const list = page.getByRole('list', { name: '조정 결정' });
    await press(list.getByRole('listitem').filter({ hasText: '직속 인물에게' }).getByRole('button'), info);
    await expect(page.getByRole('dialog', { name: '포상' })).toContainText('부 인물을 불러오지 못했습니다');
  } else {
    await expect(page.getByRole('region', { name: '상사' })).toContainText('부 인물을 불러오지 못했습니다');
    const dispatch = page.getByRole('region', { name: '발령' });
    await expect(dispatch).toContainText('내린 발령을 불러오지 못했습니다');
    await expect(dispatch).not.toContainText('내린 발령이 없습니다.');
  }
  expect(await page.locator('body').innerText()).not.toContain('Not Found');
});
