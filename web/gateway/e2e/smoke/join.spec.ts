// P-G03 가입(게이트웨이) — 데스크톱 · 모바일 같은 흐름 스모크(@both). 백엔드 없이 돈다(지도 미리보기는 page.route).
// 게이트웨이 틀(K3): web/gateway/playwright.config.ts 의 desktop · mobile 프로젝트, baseURL = E2E_GATEWAY_URL.
import { expect, test, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, titleOnlyInfo } from '../../../game/e2e/support/parity';
import { smallHitAreas } from '../support/hitArea';

async function open(page: Page) {
  // 지도 판은 일부러 모르는 판 — 지형을 받지 않는다(캔버스 그림은 지도 레인 스모크가 본다).
  await page.route('**/api/server-map/**', (route) => route.fulfill({ status: 200, contentType: 'application/json',
    body: JSON.stringify({ serverName: 'pep', year: 200, month: 3, mapCode: 'smoke-unsupported', width: 1, height: 1, cities: [], nations: [] }) }));
  await page.goto('/join');
  await expect(page.getByRole('heading', { level: 1, name: '회원 가입' })).toBeVisible();
}

test.describe('P-G03 가입 — 데스크톱 · 모바일 같은 흐름', () => {
  test('그려진다: 계정 안내 · 가입 패널 · 정책, 가로 넘침 없음, 누를 영역 44', { tag: BOTH }, async ({ page }, testInfo) => {
    await open(page);
    await expect(page.getByRole('region', { name: '계정 안내' })).toBeVisible();
    // 계정 안내 · 경고 두 문장은 승인됐다(D18) — 데스크톱 · 모바일 모두 보이고, 초안 칩은 없다.
    await expect(page.getByText('계정은 한 번 만들면 계속 씁니다. 서버가 새로 시작하면 장수만 다시 만듭니다.')).toBeVisible();
    await expect(page.getByText('한 사람이 계정 여러 개를 쓰거나 남의 턴을 대신 넣으면 이용이 막힐 수 있습니다.')).toBeVisible();
    await expect(page.getByText(/문구 초안/)).toHaveCount(0);
    await expect(page.getByText('선택', { exact: true })).toBeVisible();
    await expect(page.getByRole('navigation', { name: '정책' }).getByRole('link', { name: '개인정보처리방침' })).toHaveAttribute('href', '/privacy');
    // 로고는 화면에 한 번(D88): 1199 이하는 머리줄 로고, 1200 이상은 소개 묶음의 큰 워드마크.
    const logos = page.getByAltText('오픈삼국').filter({ visible: true });
    await expect(logos).toHaveCount(1);
    await expect(isMobile(testInfo) ? page.getByRole('banner', { name: '상단바' }).getByAltText('오픈삼국') : page.getByRole('region', { name: '계정 안내' }).getByAltText('오픈삼국')).toBeVisible();
    await expectNoHorizontalOverflow(page);
    expect(await smallHitAreas(page, 'main')).toEqual([]);
    expect(await titleOnlyInfo(page), 'title 전용 정보 금지').toEqual([]);
  });

  test('지도 띠 빈 곳은 지도가 받는다(계정 안내가 지도를 덮지 않는다)', { tag: BOTH }, async ({ page }, testInfo) => {
    await open(page);
    // 모바일: 보드 V31K5MJoin — 불투명 머리줄(56 · 로고) 아래 96 지도 띠, 판 없이 지도만(D88). 데스크톱: 계정 안내와 가입 패널 사이 가운데.
    // (모바일 캡처에서 로그인 전용 소개 규칙이 계정 안내를 지도 띠 위로 띄워 지도 · 조작 단추를 덮었다.)
    if (isMobile(testInfo)) {
      const header = (await page.getByRole('banner', { name: '상단바' }).boundingBox())!;
      const strip = (await page.locator('.gw31-join__map').boundingBox())!;
      expect(Math.round(strip.height), '모바일 지도 띠 높이(보드 96)').toBe(96);
      expect(header.y + header.height, '머리줄이 지도 띠를 덮지 않는다').toBeLessThanOrEqual(strip.y + 0.5);
    }
    // 모바일은 띠 왼쪽(옛 워드마크 판 자리)도 지도가 받는다 — 판이 머리줄로 올라갔다(D88).
    const point = isMobile(testInfo) ? { x: 60, y: 104 } : { x: 690, y: 450 };
    const onMap = await page.evaluate(({ x, y }) => {
      const hit = document.elementFromPoint(x, y);
      return !!hit && !!hit.closest('.gw31-join__map');
    }, point);
    expect(onMap, `(${point.x}, ${point.y}) 는 지도 층이어야 한다`).toBe(true);
  });

  test('가입 폼: 비밀번호가 다르면 확인 칸 아래 오류 · 표시는 두 칸을 함께', { tag: BOTH }, async ({ page }) => {
    await open(page);
    const card = page.getByRole('region', { name: '회원 가입' });
    await card.getByRole('button', { name: '회원가입', exact: true }).click();
    await expect(card.getByRole('alert')).toHaveText('계정명을 입력하세요');
    await card.getByLabel('계정명').fill('hahoudon');
    await card.getByLabel('비밀번호', { exact: true }).fill('secret1');
    await card.getByLabel('비밀번호 확인').fill('secret2');
    await card.getByRole('button', { name: '회원가입', exact: true }).click();
    await expect(card.getByRole('alert')).toHaveText('비밀번호가 서로 다릅니다.');
    await expect(card.getByLabel('비밀번호 확인')).toHaveAttribute('aria-invalid', 'true');
    await card.getByRole('button', { name: '표시' }).click();
    await expect(card.getByLabel('비밀번호 확인')).toHaveAttribute('type', 'text');
  });
});
