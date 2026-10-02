// 지난 순 서랍(P-W04) 스모크 — 작전실(/game)에 붙은 서랍을 합성 자료로 백엔드 없이, 데스크톱 · 모바일 같은 흐름(@both).
// 작전실 나머지 읽기(지도 · 12순 등)는 404 — 서랍만 본다. 작전실 전체 배치(P-W01)는 이 스모크의 몫이 아니다.
import { expect, test, type Locator } from '@playwright/test';
import { frontInfo, serveCampaign } from '../support/campaignFixtures';
import { BOTH, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const table = {
  '/api/front-info': frontInfo(),
  '/api/last-turns': {
    status: 'READY',
    turns: [
      { year: 200, month: 3, phase: 2, phaseLabel: '중순', entries: [
        { kind: 'court.dispatchReceived', text: '관도 방면 군단장 발령이 왔습니다.', refs: {} },
        { kind: 'input.rejected', text: '대상이 같은 구역에 없어 무효가 되었습니다. 비용은 들지 않았습니다.', refs: {} },
        { kind: 'march.corps', text: '군단이 움직였습니다.', refs: {} },
      ] },
      { year: 200, month: 3, phase: 1, phaseLabel: '상순', entries: [] },
    ],
    nationSummary: [{ year: 200, month: 3, phase: 1, phaseLabel: '상순', kind: 'county.captured', text: '원소가 진류현을 차지했습니다.', refs: {} }],
  },
};

/** 누를 것의 가운데를 다른 상자가 덮는지(K10 「덮임」 — elementFromPoint). 화면 밖은 세지 않는다. */
async function coveredIn(root: Locator): Promise<string[]> {
  return root.evaluate((r) => {
    const out: string[] = [];
    for (const el of Array.from(r.querySelectorAll<HTMLElement>('a, button, [role="radio"]'))) {
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

test('손잡이(데) · 칩(모) → 서랍 · 시트: 내 12순 · 분류 · 바로가기, 덮임 0 · 44 · title 전용 · 영어 원문 0, 옛 두 벌 없음', { tag: [BOTH] }, async ({ page }, info) => {
  const served = await serveCampaign(page, table);
  await page.goto('/game', { waitUntil: 'domcontentloaded' });
  const handle = page.getByRole('button', { name: '지난 순 — 새 기록 3' });
  await expect(handle).toBeVisible({ timeout: 60_000 });
  expect(served.unknown.filter((u) => u.includes('/api/last-turns'))).toEqual([]);
  // 옛 「지난 순」 패널 · world_log 3탭은 서랍 한 벌로 합쳤다.
  await expect(page.getByText('세력 요약', { exact: true })).toHaveCount(0);
  await expect(page.getByText('중원 정세')).toHaveCount(0);
  await press(handle, info);

  const box = isMobile(info) ? page.getByRole('dialog', { name: '지난 순' }) : page.getByRole('region', { name: '지난 순' });
  const selector = isMobile(info) ? '[role="dialog"][aria-label="지난 순"]' : 'section[aria-label="지난 순"]';
  await expect(box).toContainText('200년 3월 상순 – 중순');
  const mine = box.getByRole('list', { name: '내 12순' });
  await expect(mine).toContainText('발령 도착');
  await expect(mine).toContainText('3월 상순 — 기록 없음');
  await expect(mine).toContainText('누가 · 어디 · 리플레이 — 준비 중');
  await expect(box.getByRole('link', { name: '조정에서 보기 →' })).toBeVisible();
  expect(await coveredIn(box)).toEqual([]);
  expect(await box.innerText()).not.toMatch(/[A-Za-z]{3,}/);
  expect(await smallTouchTargets(page, selector)).toEqual([]);
  expect(await titleOnlyInfo(page, selector)).toEqual([]);

  await press(box.getByRole('group', { name: '분류' }).getByRole('button', { name: '천하' }), info);
  await press(box.getByRole('radiogroup', { name: '범위' }).getByRole('radio', { name: '전체' }), info);
  await expect(box.getByRole('list', { name: '전체' })).toContainText('원소가 진류현을 차지했습니다.');
  await expect(box.getByRole('list', { name: '전체' })).not.toContainText('발령 도착');

  if (isMobile(info)) {
    await press(box.getByRole('button', { name: '닫기' }), info);
    await expect(box).toBeHidden();
  } else {
    await page.keyboard.press('Escape');
    await expect(box).toBeHidden();
    await expect(handle).toBeFocused();
  }
});
