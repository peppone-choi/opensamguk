// P-G07 커뮤니티 글(게이트웨이) — 데스크톱 · 모바일 같은 흐름 스모크(@both). 백엔드 없이 돈다(page.route).
import { expect, test, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, titleOnlyInfo } from '../../../game/e2e/support/parity';
import { smallHitAreas } from '../support/hitArea';

const USER = { id: 2, username: 'reader', nickname: '독자', role: 'USER', email: null, picture: null, imageServer: 0 };
const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
const detail = (canDelete: boolean) => ({
  post: {
    id: 42, category: 'STRATEGY', authorName: '북풍', authorPicture: null, authorImageServer: 0, title: '영천군에서 보급이 끊기지 않게 창고를 두는 법',
    contentHtml: '<p>창고는 군 치소 가까이에 두고, 현과 현 사이 길이 끊기지 않는지 먼저 봅니다.</p><p><strong>양적현 → 영음현 → 허현</strong> 줄이 가장 안전했습니다.</p>',
    pinned: false, canDelete, deleted: false, createdAt: '2026-09-29T13:41:00Z', updatedAt: '2026-09-29T15:00:00Z', viewCount: 812, commentCount: 2,
    authorGeneralName: '안량', authorWorldId: 1,
  },
  comments: [
    { id: 4, authorName: '북풍', authorPicture: null, authorImageServer: 0, content: '창고를 영음현에 두면 허현까지 한 길로 이어지더군요.', canDelete: false, deleted: false, createdAt: '2026-09-29T14:02:00Z' },
    { id: 5, authorName: '솔바람', authorPicture: null, authorImageServer: 0, content: '좋은 글 고맙습니다.', canDelete: false, deleted: false, createdAt: '2026-09-29T14:40:00Z' },
  ],
});

async function open(page: Page, { member = true, author = false } = {}) {
  await page.route('**/api/auth/me', (route) => route.fulfill(member ? json({ user: USER }) : json({ error: '로그인이 필요합니다.' }, 401)));
  await page.route('**/api/board/posts?**', (route) => route.fulfill(json({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })));
  await page.route('**/api/board/categories', (route) => route.fulfill(json([])));
  await page.route('**/api/board/posts/42', (route) => (route.request().method() === 'DELETE'
    ? route.fulfill({ status: 204, body: '' })
    : route.fulfill(json(detail(author)))));
  await page.goto('/board/posts/42');
  await expect(page.getByRole('heading', { level: 1, name: '영천군에서 보급이 끊기지 않게 창고를 두는 법' })).toBeVisible();
}

test.describe('P-G07 커뮤니티 글 — 데스크톱 · 모바일 같은 흐름', () => {
  test('그려진다: 대표 장수 칩 · 「조회 · 수정됨」 · 댓글 · 가로 넘침 없음 · 누를 영역 44', { tag: BOTH }, async ({ page }) => {
    await open(page);
    await expect(page.getByText('대표 장수 · 안량')).toBeVisible();
    await expect(page.getByText(/조회 812 · 수정됨$/)).toBeVisible();
    await expect(page.getByRole('heading', { level: 2, name: '댓글 2' })).toBeVisible();
    await expect(page.getByRole('button', { name: '댓글 등록' })).toHaveAttribute('data-reason', '댓글 내용을 쓰세요');
    await expect(page.getByText(/월드 1|전콘/)).toHaveCount(0);
    await expectNoHorizontalOverflow(page);
    expect(await smallHitAreas(page, 'body')).toEqual([]);
    expect(await titleOnlyInfo(page), 'title 전용 정보 금지').toEqual([]);
  });

  test('신고: 사유 없으면 잠김 · 모바일은 아래에 붙는 시트 · 접수 알림', { tag: BOTH }, async ({ page }, info) => {
    await open(page);
    await page.route('**/api/board/posts/42/report', (route) => route.fulfill(json({
      id: 1, postId: 42, commentId: null, targetSummary: '영천군', reporterName: '독자', reason: '광고', status: 'OPEN', createdAt: '2026-09-30T00:00:00Z', handledAt: null,
    })));
    await page.getByRole('button', { name: '신고' }).first().click();
    const sheet = page.getByRole('dialog', { name: '글 신고' });
    await expect(sheet.getByRole('button', { name: '신고 접수' })).toHaveAttribute('data-reason', '사유를 쓰세요');
    if (isMobile(info)) {
      const box = (await sheet.boundingBox())!;
      expect(Math.round(box.y + box.height)).toBe(page.viewportSize()!.height);
      expect(Math.round(box.width)).toBe(page.viewportSize()!.width);
    }
    expect(await smallHitAreas(page, '[role="dialog"]')).toEqual([]);
    await sheet.getByLabel('신고 사유').fill('광고 글입니다');
    await sheet.getByRole('button', { name: '신고 접수' }).click();
    await expect(sheet).toHaveCount(0);
    await expect(page.getByRole('status').filter({ hasText: '신고를 접수했습니다. 운영자가 확인합니다.' })).toBeVisible();
  });

  test('글 삭제는 확인 대화상자를 거쳐 목록으로 간다', { tag: BOTH }, async ({ page }) => {
    await open(page, { author: true });
    const deletes: string[] = [];
    page.on('request', (request) => { if (request.method() === 'DELETE') deletes.push(request.url()); });
    await page.getByRole('button', { name: '게시글 삭제' }).click();
    const dialog = page.getByRole('dialog', { name: '게시글 삭제' });
    await expect(dialog).toContainText('글을 지우면 목록에서 사라지고 되돌릴 수 없습니다.');
    expect(deletes).toEqual([]);
    await dialog.getByRole('button', { name: '지우기' }).click();
    await expect(page).toHaveURL(/\/board$/);
    expect(deletes).toHaveLength(1);
  });

  test('손님은 로그인 안내만 본다', { tag: BOTH }, async ({ page }) => {
    await open(page, { member: false });
    await expect(page.getByRole('button', { name: '신고' })).toHaveCount(0);
    await expect(page.getByLabel('댓글', { exact: true })).toHaveCount(0);
    await expect(page.getByRole('main').getByRole('link', { name: '로그인' })).toHaveAttribute('href', '/login?next=%2Fboard%2Fposts%2F42');
  });
});
