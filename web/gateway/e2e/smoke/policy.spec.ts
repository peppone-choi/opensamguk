// P-G10 개인정보처리방침 · P-G11 이용약관(게이트웨이) — 데스크톱 · 모바일 같은 흐름 스모크(@both). 백엔드 없이 돈다.
// 본문은 공개 알파 정책 문구(U5) 승인 전이라 「준비 중」과 다룰 절만 있다 — 그 상태가 그대로 보이는지, 두 문서를 오가는지 본다.
import { expect, test, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, press, titleOnlyInfo } from '../../../game/e2e/support/parity';
import { smallHitAreas } from '../support/hitArea';

const DOCS = [
  { path: '/privacy', title: '개인정보처리방침', sections: ['모으는 정보', '쓰는 곳', '보관 기간', '지우기 요청', '연락처'] },
  { path: '/terms', title: '이용약관', sections: ['계정', '금지 행동', '이용 제한', '서비스 변경', '연락처'] },
] as const;

async function open(page: Page, path: string, title: string) {
  await page.goto(path);
  await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible();
}

for (const doc of DOCS) {
  test.describe(`${doc.title}(${doc.path}) — 데스크톱 · 모바일 같은 흐름`, () => {
    test('그려진다: 준비 중 · 다룰 절, 가로 넘침 없음, 누를 영역 44', { tag: BOTH }, async ({ page }) => {
      await open(page, doc.path, doc.title);
      const waiting = page.getByRole('status');
      await expect(waiting).toContainText('준비 중');
      await expect(waiting).toHaveAttribute('data-copy-status', 'pending'); // 본문은 승인 전 — 지어내지 않는다
      const toc = page.getByRole('region', { name: '다룰 내용' });
      await expect(toc.getByRole('listitem')).toHaveText([...doc.sections]);
      await expectNoHorizontalOverflow(page);
      expect(await smallHitAreas(page, 'body')).toEqual([]);
      expect(await titleOnlyInfo(page), 'title 전용 정보 금지').toEqual([]);
    });

    test('머리줄: 로고는 처음으로, 「회원가입」은 가입으로', { tag: BOTH }, async ({ page }) => {
      await open(page, doc.path, doc.title);
      await expect(page.getByRole('link', { name: '오픈삼국 — 처음으로' })).toHaveAttribute('href', '/login');
      await expect(page.getByRole('link', { name: '회원가입' })).toHaveAttribute('href', '/join');
    });
  });
}

test('정책 링크로 두 문서를 오간다', { tag: BOTH }, async ({ page }, testInfo) => {
  const [privacy, terms] = DOCS;
  await open(page, privacy.path, privacy.title);
  const policy = page.getByRole('navigation', { name: '정책' });
  await press(policy.getByRole('link', { name: terms.title }), testInfo);
  await expect(page).toHaveURL(new RegExp(`${terms.path}$`));
  await expect(page.getByRole('heading', { level: 1, name: terms.title })).toBeVisible();
  await press(page.getByRole('navigation', { name: '정책' }).getByRole('link', { name: privacy.title }), testInfo);
  await expect(page).toHaveURL(new RegExp(`${privacy.path}$`));
  await expect(page.getByRole('heading', { level: 1, name: privacy.title })).toBeVisible();
});
