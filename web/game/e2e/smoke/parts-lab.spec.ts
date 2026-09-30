// v3.1 공용 부품 미리보기(/parts-lab, 기능 플래그 NEXT_PUBLIC_PARTS_LAB=1) — 합성 자료, 백엔드 없음.
// 데스크톱 · 모바일 두 프로필(@both)에서 v3.1 규칙을 본다: 누를 영역 44 · 네이티브 disabled · title 0 · 가로 넘침 0 ·
// 비활성은 눌러서 사유가 열린다 · 지도 표지와 목록이 같은 상태 · 띠가 표지를 덮지 않는다.
import { expect, test, type Page } from '@playwright/test';
import { BOTH, MOBILE_ONLY, press } from '../support/parity';

const LAB = '/parts-lab';

async function open(page: Page) {
  await page.goto(LAB, { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { name: '공용 부품 미리보기' })).toBeVisible({ timeout: 60_000 });
  // SSR 제목은 수화 전에 보인다 — 리스너가 붙은 뒤에 누른다.
  await expect(page.locator('main[data-hydrated="true"]')).toBeVisible();
}

/** 보이는 누를 것 가운데 44 × 44 보다 작은 것(보이는 크기와 상관없이 누를 영역 기준). */
async function smallTargets(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const sel = 'main button, main a[href], main [role="slider"], main label.os-check, main input[type="search"]';
    const out: string[] = [];
    for (const el of Array.from(document.querySelectorAll<HTMLElement>(sel))) {
      const r = el.getBoundingClientRect();
      const cs = getComputedStyle(el);
      if (r.width === 0 || r.height === 0 || cs.visibility === 'hidden' || el.closest('[hidden]')) continue;
      if (el.matches('[role="slider"]')) { if (r.height < 44) out.push(`slider ${r.height}`); continue; }
      if (r.width < 43.5 || r.height < 43.5) out.push(`${el.tagName.toLowerCase()} "${(el.getAttribute('aria-label') ?? el.textContent ?? '').trim().slice(0, 24)}" ${Math.round(r.width)}×${Math.round(r.height)}`);
    }
    return out;
  });
}

test.describe('공용 부품 미리보기', () => {
  test('규칙: 누를 영역 44 · disabled · title 0 · 가로 넘침 0', { tag: BOTH }, async ({ page }) => {
    await open(page);
    expect(await smallTargets(page)).toEqual([]);
    expect(await page.locator('main [disabled]').count()).toBe(0);
    expect(await page.locator('main [title]').count()).toBe(0);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });

  test('입력 4상태: 막힌 단추는 눌러서 사유가 열리고 보내지 않는다 · 원장 행 없음은 안 그린다', { tag: BOTH }, async ({ page }) => {
    await open(page);
    const lab = page.getByTestId('lab-input');
    await press(lab.getByRole('button', { name: '등용 — 명령 목록에 넣기' }), test.info());
    await expect(page.getByTestId('lab-log')).toHaveText('등용 보냄');

    await press(lab.getByRole('button', { name: '발령' }), test.info());
    const sheet = page.getByRole('dialog', { name: '발령은 주공만 할 수 있습니다.' });
    await expect(sheet).toBeVisible();
    await expect(sheet).toContainText('이렇게 하면 됩니다');
    await expect(page.getByTestId('lab-log')).toHaveText('등용 보냄');
    await press(sheet.getByRole('link', { name: '도움말 — 발령 →' }), test.info());
    await expect(page.getByTestId('lab-log')).toHaveText('도움말 input:court.dispatch!NOT_RULER');

    await expect(lab.getByRole('button', { name: '성방 허물기' })).toHaveAttribute('aria-disabled', 'true');
    await expect(lab.locator('[data-input-id="action.unknown"]')).toHaveCount(0);
  });

  test('모바일: 사유는 화면 아래 시트로 열린다', { tag: MOBILE_ONLY }, async ({ page }) => {
    await open(page);
    await press(page.getByTestId('lab-input').getByRole('button', { name: '발령' }), test.info());
    const sheet = page.getByRole('dialog', { name: '발령은 주공만 할 수 있습니다.' });
    const box = (await sheet.boundingBox())!;
    const vp = page.viewportSize()!;
    expect(Math.round(box.y + box.height)).toBe(vp.height);
    expect(Math.round(box.width)).toBe(vp.width);
    // 닫기 단추(시트 오른쪽 아래)가 시트 내용을 덮지 않는다
    const close = (await page.getByRole('button', { name: '닫기' }).boundingBox())!;
    for (const part of await sheet.locator('.os-reason__title, .os-reason__body, .os-reason__recovery, .os-reason__help').all()) {
      const r = (await part.boundingBox())!;
      const overlap = r.x < close.x + close.width && close.x < r.x + r.width && r.y < close.y + close.height && close.y < r.y + r.height;
      expect(overlap, `닫기 단추가 ${await part.getAttribute('class')} 를 덮는다`).toBe(false);
    }
  });

  test('지도 대상 고르기: 표지와 목록이 같은 상태, 띠는 표지를 덮지 않는다', { tag: BOTH }, async ({ page }) => {
    await open(page);
    const sec = page.getByTestId('lab-pick');
    const list = sec.getByRole('listbox', { name: '갈 곳 후보' });
    await expect(sec.getByText('지도를 누르거나 목록에서 · 가능 3 · 불가 3')).toBeVisible();

    // 불가 행 — 행 전체가 사유 단추, 고르지 않는다
    await press(list.getByRole('option', { name: /신정현/ }), test.info());
    await expect(page.getByRole('dialog', { name: '신정현 — 고를 수 없습니다' })).toBeVisible();
    await expect(page.getByTestId('lab-picked')).toHaveText('—');
    await page.keyboard.press('Escape');

    // 지도 표지 가운데를 누르면 그 표지가 맞는다(띠 · 무늬가 먹지 않는다) — 고르면 목록도 「고름」
    const marker = sec.getByRole('button', { name: '밀현 표지' });
    await press(marker, test.info()); // press 가 가운데 맨 위가 표지인지 먼저 확인한다
    await expect(page.getByTestId('lab-picked')).toHaveText('mi');
    await expect(marker).toHaveAttribute('data-marker-state', 'selected');
    await expect(list.getByRole('option', { name: /밀현/ })).toHaveAttribute('aria-selected', 'true');

    // 「가능만」 · 묶음 탭
    await press(sec.getByRole('radio', { name: '이웃' }).first(), test.info());
    await expect(list.getByRole('option')).toHaveCount(2);
    await press(sec.getByText('가능만').first(), test.info());
    await expect(list.getByRole('option')).toHaveCount(1);
  });

  test('여러 현 고르기는 고른 순서대로', { tag: BOTH }, async ({ page }) => {
    await open(page);
    const list = page.getByRole('listbox', { name: '여러 현 후보' });
    await press(list.getByRole('option', { name: /번창현/ }), test.info());
    await press(list.getByRole('option', { name: /영양현/ }), test.info());
    await expect(page.getByTestId('lab-multi')).toHaveText('bc,yy');
    await expect(list.getByRole('option', { name: /번창현/ })).toContainText('1');
  });

  test('데스크톱: Esc 는 고르기를 그만둔다', { tag: '@desktop-only' }, async ({ page }) => {
    await open(page);
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('lab-log')).toHaveText('고르기 그만');
  });

  test('사람 고르기: 초성 찾기 · NPC 포함 · 사람 칩 · 여러 명 빼기', { tag: BOTH }, async ({ page }) => {
    await open(page);
    const sec = page.getByTestId('lab-people');
    const single = sec.getByRole('listbox', { name: '받는 사람' });
    await expect(single.getByRole('option')).toHaveCount(6);
    await sec.getByRole('searchbox').first().fill('ㅎㅈ');
    await expect(single.getByRole('option')).toHaveCount(1);
    await expect(single.getByRole('option', { name: /허저/ })).toContainText('사람');
    await press(single.getByRole('option', { name: /허저/ }), test.info());
    await expect(page.getByTestId('lab-person')).toHaveText('2');

    await press(sec.getByRole('button', { name: '허저 빼기' }), test.info());
    await expect(sec.getByRole('listbox', { name: '알릴 사람' }).getByRole('option', { name: /허저/ })).toHaveAttribute('aria-selected', 'false');
  });

  test('시간 막대: 사건 표식을 누르면 그 순간으로 · 실시간은 지나간 데까지만', { tag: BOTH }, async ({ page }) => {
    await open(page);
    const sec = page.getByTestId('lab-timebar');
    await press(sec.getByRole('group', { name: '시간 막대' }).first().getByRole('button', { name: /성문/ }), test.info());
    await expect(page.getByTestId('lab-pos')).toHaveText('145000');
    const live = sec.getByRole('group', { name: '시간 막대' }).nth(1);
    // 실시간은 지나간 데(160초)까지만 — 185초의 두 번째 일기토는 없다
    await expect(live.getByRole('button', { name: /일기토/ })).toHaveCount(1);
    await expect(sec.getByRole('group', { name: '시간 막대' }).first().getByRole('button', { name: /일기토/ })).toHaveCount(2);
    await press(live.getByRole('button', { name: '지금으로' }), test.info());
    await expect(page.getByTestId('lab-pos')).toHaveText('160000');
  });
});
