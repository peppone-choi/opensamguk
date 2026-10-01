// P-G08 커뮤니티 글쓰기(게이트웨이) — 데스크톱 · 모바일 같은 흐름 스모크(@both). 백엔드 없이 돈다(page.route).
import { expect, test, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, titleOnlyInfo } from '../../../game/e2e/support/parity';
import { smallHitAreas } from '../support/hitArea';

const USER = { id: 1, username: 'hahoudon', nickname: '원양', role: 'USER', email: null, picture: null, imageServer: 0 };
const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
const POST = {
  id: 42, category: 'STRATEGY', authorName: '원양', authorPicture: null, authorImageServer: 0, title: '영천군 보급 정리',
  contentHtml: '<p>창고는 군 치소 가까이에 둡니다.</p>', pinned: false, canDelete: true, deleted: false,
  createdAt: '2026-09-29T13:41:00Z', updatedAt: '2026-09-29T13:41:00Z', viewCount: 10, commentCount: 0, authorGeneralName: null, authorWorldId: null,
};

async function open(page: Page, path: string) {
  const sent: { method: string; url: string; body: unknown }[] = [];
  await page.route('**/api/auth/me', (route) => route.fulfill(json({ user: USER })));
  await page.route('**/api/board/posts', (route) => {
    sent.push({ method: route.request().method(), url: route.request().url(), body: route.request().postDataJSON() });
    return route.fulfill(json({ ...POST, id: 77, title: '새로 쓴 글' }, 201));
  });
  await page.route('**/api/board/posts/42', (route) => {
    if (route.request().method() === 'PATCH') {
      sent.push({ method: 'PATCH', url: route.request().url(), body: route.request().postDataJSON() });
      return route.fulfill(json({ ...POST, title: '고친 제목' }));
    }
    return route.fulfill(json({ post: POST, comments: [] }));
  });
  await page.goto(path);
  return sent;
}

test.describe('P-G08 커뮤니티 글쓰기 — 데스크톱 · 모바일 같은 흐름', () => {
  test('새 글: 비면 사유 · 서식 단추 44 · 등록하면 글로 간다', { tag: BOTH }, async ({ page }) => {
    const sent = await open(page, '/board/write');
    await expect(page.getByRole('heading', { level: 1, name: '게시글 작성' })).toBeVisible();
    const submit = page.getByRole('button', { name: '등록' });
    await expect(submit).toHaveAttribute('data-reason', '제목과 내용을 모두 입력해주세요');
    await expect(page.getByRole('radiogroup', { name: '게시판' }).getByRole('radio', { name: '공지' })).toHaveCount(0);
    await expectNoHorizontalOverflow(page);
    expect(await smallHitAreas(page, 'body')).toEqual([]);
    expect(await titleOnlyInfo(page), 'title 전용 정보 금지').toEqual([]);

    await page.getByRole('radiogroup', { name: '게시판' }).getByRole('radio', { name: '전략·공략' }).click();
    await page.getByLabel('제목').fill('새로 쓴 글');
    const editor = page.getByRole('textbox', { name: '내용' });
    await editor.click();
    await page.getByRole('button', { name: '굵게' }).click();
    await page.keyboard.type('굵은 첫 줄');
    await expect(page.getByRole('button', { name: '굵게' })).toHaveAttribute('aria-pressed', 'true');
    await submit.click();
    await expect(page).toHaveURL(/\/board\/posts\/77$/);
    expect(sent).toHaveLength(1);
    expect(sent[0].body).toMatchObject({ category: 'STRATEGY', title: '새로 쓴 글', contentFormat: 'RICH_HTML' });
    expect((sent[0].body as { content: string }).content).toContain('<strong>굵은 첫 줄</strong>');
  });

  test('쓰던 글을 버릴 때는 확인을 받는다', { tag: BOTH }, async ({ page }) => {
    await open(page, '/board/write');
    await page.getByLabel('제목').fill('쓰다 만 글');
    await page.getByRole('button', { name: '취소', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '쓰던 글 버리기' });
    await expect(dialog).toContainText('지금까지 쓴 내용이 사라집니다.');
    await dialog.getByRole('button', { name: '계속 쓰기' }).click();
    await expect(page.getByLabel('제목')).toHaveValue('쓰다 만 글');
  });

  test('고치기: 원래 글을 채워 열고 PATCH 로 저장한다', { tag: BOTH }, async ({ page }) => {
    const sent = await open(page, '/board/write?edit=42');
    await expect(page.getByRole('heading', { level: 1, name: '게시글 수정' })).toBeVisible();
    await expect(page.getByLabel('제목')).toHaveValue('영천군 보급 정리');
    await expect(page.getByRole('textbox', { name: '내용' })).toContainText('창고는 군 치소 가까이에 둡니다.');
    await page.getByLabel('제목').fill('고친 제목');
    await page.getByRole('button', { name: '저장' }).click();
    await expect(page).toHaveURL(/\/board\/posts\/42$/);
    expect(sent.map((s) => s.method)).toEqual(['PATCH']);
    expect(sent[0].body).toMatchObject({ category: 'STRATEGY', title: '고친 제목' });
  });
});
