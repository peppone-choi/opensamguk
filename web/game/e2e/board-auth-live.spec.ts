// Authenticated board verification. Cleanup uses the post DELETE soft-delete route.
// E2E_BOARD_AUTH=1 E2E_GATEWAY_URL=https://sam.peppone.dev E2E_BOARD_ALLOW_PRODUCTION=1 \
// E2E_BOARD_USER_USERNAME=... E2E_BOARD_USER_PASSWORD=... \
// E2E_BOARD_ADMIN_USERNAME=... E2E_BOARD_ADMIN_PASSWORD=... \
// pnpm exec playwright test e2e/board-auth-live.spec.ts
import { expect, test, type Page } from '@playwright/test';

const gatewayUrl = process.env.E2E_GATEWAY_URL ?? 'http://localhost:3000';
const isProduction = new URL(gatewayUrl).hostname === 'sam.peppone.dev';
const credentials = {
  user: {
    username: process.env.E2E_BOARD_USER_USERNAME,
    password: process.env.E2E_BOARD_USER_PASSWORD,
  },
  admin: {
    username: process.env.E2E_BOARD_ADMIN_USERNAME,
    password: process.env.E2E_BOARD_ADMIN_PASSWORD,
  },
};

test.use({ baseURL: gatewayUrl, screenshot: 'off', trace: 'off', video: 'off' });

function gatewayPath(path: string): string {
  return new URL(path, gatewayUrl).toString();
}

async function login(page: Page, account: { username?: string; password?: string }): Promise<void> {
  expect(account.username && account.password, '게시판 e2e 계정명·비밀번호를 환경변수로 제공해야 한다').toBeTruthy();
  await page.goto(gatewayPath('/login'), { waitUntil: 'domcontentloaded' });
  await page.locator('input[name="username"]').fill(account.username!);
  await page.locator('input[name="password"]').fill(account.password!);
  const response = page.waitForResponse((r) =>
    r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/auth/login');
  await page.getByRole('button', { name: '로그인' }).click();
  expect((await response).status(), '인증 응답').toBe(200);
  await expect(page).toHaveURL(/\/lobby(?:\?|$)/);
  expect((await page.context().cookies()).some((cookie) =>
    cookie.name === 'sam_access' && cookie.httpOnly), 'httpOnly 인증 쿠키').toBe(true);
}

async function createPost(
  page: Page,
  category: 'FREE' | 'NOTICE',
  title: string,
  trackCreatedId: (id: number) => void,
): Promise<number> {
  await page.goto(gatewayPath('/board/write'));
  await expect(page.getByRole('heading', { name: '게시글 작성' })).toBeVisible();
  await page.getByLabel('분류').selectOption(category);
  await page.getByLabel('제목').fill(title);
  await page.getByRole('textbox', { name: '내용' }).fill('OPENSAM-288 인증 e2e 임시 게시글입니다. 한글 입력 확인.');
  const creation = page.waitForResponse((r) => r.request().method() === 'POST'
    && new URL(r.url()).pathname === '/api/board/posts');
  await page.getByRole('button', { name: '등록', exact: true }).click();
  const response = await creation;
  expect(response.status(), '게시글 생성 응답').toBe(201);
  const created = await response.json() as { id?: unknown };
  const id = Number(created.id);
  expect(Number.isSafeInteger(id) && id > 0, '생성된 게시글 ID').toBe(true);
  trackCreatedId(id);
  await expect(page).toHaveURL(/\/board\/posts\/\d+$/);
  await expect(page.getByRole('heading', { name: title })).toBeVisible();
  expect(Number(new URL(page.url()).pathname.split('/').pop())).toBe(id);
  return id;
}

test('일반 사용자 게시판 흐름과 어드민 공지 고정·관리·soft-delete', async ({ page }) => {
  test.skip(process.env.E2E_BOARD_AUTH !== '1', 'Requires explicit board auth e2e opt-in');
  expect(!isProduction || process.env.E2E_BOARD_ALLOW_PRODUCTION === '1',
    '프로덕션 글 생성은 E2E_BOARD_ALLOW_PRODUCTION=1 이 필요하다').toBe(true);
  test.setTimeout(180_000);
  const marker = `OPENSAM-288 e2e ${Date.now()}-${process.pid}`;
  const freeTitle = `${marker} 자유`;
  const noticeTitle = `${marker} 공지`;
  const remainingPosts: { id: number; owner: 'user' | 'admin' }[] = [];

  try {
    await login(page, credentials.user);
    await page.getByRole('link', { name: '커뮤니티 게시판' }).click();
    await expect(page.getByRole('heading', { name: '커뮤니티 게시판' })).toBeVisible();
    console.log('PASS 일반 사용자 로비 진입점');

    const freeId = await createPost(page, 'FREE', freeTitle, (id) =>
      remainingPosts.push({ id, owner: 'user' }));
    await page.goto(gatewayPath('/board'));
    await page.getByRole('button', { name: /^자유/ }).click();
    await expect(page.getByRole('link', { name: freeTitle })).toBeVisible();
    await page.getByRole('link', { name: freeTitle }).click();
    await expect(page.getByRole('heading', { name: freeTitle })).toBeVisible();
    console.log(`PASS 일반 사용자 목록·상세·작성 post=${freeId}`);

    await page.getByRole('textbox', { name: '댓글' }).fill('인증 e2e 댓글 확인');
    await page.getByRole('button', { name: '댓글 등록' }).click();
    await expect(page.getByText('인증 e2e 댓글 확인')).toBeVisible();
    console.log('PASS 일반 사용자 댓글 작성');

    await page.getByRole('button', { name: /로\s*그\s*아\s*웃/ }).click();
    await login(page, credentials.admin);
    await expect(page.getByRole('link', { name: '관리' })).toBeVisible();
    const noticeId = await createPost(page, 'NOTICE', noticeTitle, (id) =>
      remainingPosts.push({ id, owner: 'admin' }));
    await page.getByRole('button', { name: '게시글 고정' }).click();
    await expect(page.getByRole('button', { name: '고정 해제' })).toBeVisible();
    await expect(page.getByText('고정', { exact: true })).toBeVisible();
    console.log(`PASS 어드민 공지 고정 post=${noticeId}`);

    await page.goto(gatewayPath('/admin'));
    await page.getByRole('button', { name: /게시판 관리/ }).click();
    const panel = page.getByRole('region', { name: '게시판 관리' });
    await expect(panel.getByRole('heading', { name: '게시물 관리' })).toBeVisible();
    const row = panel.getByRole('row').filter({ hasText: noticeTitle });
    await expect(row).toContainText('고정됨');
    await row.getByRole('button', { name: '삭제' }).click();
    await page.getByRole('dialog').getByRole('button', { name: '삭제' }).click();
    await expect(panel.getByRole('status')).toHaveText('게시물을 삭제했습니다.');
    remainingPosts.splice(remainingPosts.findIndex((post) => post.id === noticeId), 1);
    console.log('PASS 어드민 게시판 관리에서 soft-delete');

    // The admin list retains deleted history while the public list hides it.
    await page.getByRole('button', { name: /회원 관리/ }).click();
    await page.getByRole('button', { name: /게시판 관리/ }).click();
    await expect(panel.getByRole('row').filter({ hasText: noticeTitle })).toContainText('삭제됨');
    await page.goto(gatewayPath('/board'));
    await page.getByRole('button', { name: /^공지/ }).click();
    await expect(page.getByRole('link', { name: noticeTitle })).toHaveCount(0);
    console.log('PASS 삭제 이력 보존·공개 목록 제외');
  } finally {
    for (const { id, owner } of remainingPosts) {
      let response = await page.request.delete(gatewayPath(`/api/board/posts/${id}`));
      if (response.status() === 401 || response.status() === 403) {
        await login(page, credentials[owner]);
        response = await page.request.delete(gatewayPath(`/api/board/posts/${id}`));
      }
      if (response.status() !== 204) {
        throw new Error(`테스트 글 ${id} soft-delete 실패: HTTP ${response.status()}`);
      }
      console.log(`CLEANUP soft-delete post=${id}`);
    }
  }
});
