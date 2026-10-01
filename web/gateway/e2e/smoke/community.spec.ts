// P-G06 커뮤니티 목록(게이트웨이) — 데스크톱 · 모바일 같은 흐름 스모크(@both). 백엔드 없이 돈다(page.route).
// 손님도 읽는다(미들웨어 보호 경로가 아니다). 회원은 /api/auth/me 가 합성 사용자를 돌려준다.
import { expect, test, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, titleOnlyInfo } from '../../../game/e2e/support/parity';
import { smallHitAreas } from '../support/hitArea';

const USER = { id: 1, username: 'hahoudon', nickname: '원양', role: 'USER', email: null, picture: null, imageServer: 0 };
const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
const post = (id: number, title: string, extra: Record<string, unknown> = {}) => ({
  id, category: 'STRATEGY', authorName: '북풍', authorPicture: null, authorImageServer: 0, title, contentHtml: '<p>x</p>', pinned: false,
  canDelete: false, deleted: false, createdAt: '2026-09-29T13:41:00Z', updatedAt: '2026-09-29T13:41:00Z', viewCount: 812, commentCount: 21,
  authorGeneralName: '안량', authorWorldId: 1, ...extra,
});
const POSTS = [
  post(1, '3기 서버 개시 안내와 바뀐 규칙', { category: 'NOTICE', pinned: true, authorName: '운영자', authorGeneralName: null }),
  post(2, '영천군에서 보급이 끊기지 않게 창고를 두는 법'),
  post(3, '관도 전선이 열렸다 — 원소 쪽 분위기', { category: 'SERVER' }),
  post(4, '밤 턴 넘기는 사람들 모여라', { category: 'FREE', authorGeneralName: null }),
];

async function open(page: Page, member: boolean) {
  const requests: string[] = [];
  await page.route('**/api/auth/me', (route) => route.fulfill(member ? json({ user: USER }) : json({ error: '로그인이 필요합니다.' }, 401)));
  await page.route('**/api/board/categories', (route) => route.fulfill(json([
    { category: 'NOTICE', count: 12 }, { category: 'FREE', count: 611 }, { category: 'SUGGESTION', count: 87 },
    { category: 'STRATEGY', count: 204 }, { category: 'SERVER', count: 263 }, { category: 'CREATIVE', count: 107 },
  ])));
  await page.route('**/api/board/posts?**', (route) => {
    const url = route.request().url();
    requests.push(url);
    if (url.includes('size=3')) return route.fulfill(json({ content: POSTS.slice(0, 3), page: 0, size: 3, totalElements: 3, totalPages: 1 }));
    return route.fulfill(json({ content: POSTS, page: 0, size: 20, totalElements: 61, totalPages: 4 }));
  });
  await page.goto('/board');
  await expect(page.getByRole('heading', { level: 1, name: '커뮤니티 게시판' })).toBeVisible();
  await expect(page.getByRole('region', { name: '게시글' }).getByRole('link', { name: '영천군에서 보급이 끊기지 않게 창고를 두는 법' })).toBeVisible();
  return requests;
}

test.describe('P-G06 커뮤니티 목록 — 데스크톱 · 모바일 같은 흐름', () => {
  test('손님: 공개 머리줄 「로그인」 · 「내 글」 사유 · 가로 넘침 없음 · 누를 영역 44', { tag: BOTH }, async ({ page }) => {
    await open(page, false);
    await expect(page.getByRole('banner').getByRole('link', { name: '로그인' })).toBeVisible();
    await expect(page.getByRole('link', { name: '로그인 후 글쓰기' })).toHaveAttribute('href', '/login?next=%2Fboard%2Fwrite');
    const mine = page.getByRole('button', { name: '내 글' });
    await expect(mine).toHaveAttribute('data-reason', '로그인하면 볼 수 있습니다');
    await mine.click();
    await expect(page.getByText('로그인하면 볼 수 있습니다').last()).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByText(/월드 1|전콘|OPEN SAMGUK/)).toHaveCount(0);
    await expectNoHorizontalOverflow(page);
    expect(await smallHitAreas(page, 'body')).toEqual([]);
    expect(await titleOnlyInfo(page), 'title 전용 정보 금지').toEqual([]);
  });

  test('회원: 글쓰기 단추 자리 · 레일 접기 · 줄 전체가 글로 간다', { tag: BOTH }, async ({ page }, info) => {
    await open(page, true);
    const write = page.getByRole('link', { name: '글쓰기' });
    await expect(write).toHaveAttribute('href', '/board/write');
    const box = (await write.boundingBox())!;
    const viewport = page.viewportSize()!;
    const popular = page.getByRole('region', { name: '인기 글' });
    const fold = popular.getByRole('button', { name: '펼치기' });
    const top = popular.getByRole('link', { name: '3기 서버 개시 안내와 바뀐 규칙' });
    if (isMobile(info)) {
      // 오른쪽 아래 떠 있는 단추 56.
      expect([Math.round(box.width), Math.round(box.height)]).toEqual([56, 56]);
      expect(viewport.width - (box.x + box.width)).toBeLessThanOrEqual(17);
      // 레일은 목록 아래 접이 패널로 접혀 있다.
      await expect(top).toBeHidden();
      await fold.click();
      await expect(top).toBeVisible();
      await expect(popular.getByRole('button', { name: '접기' })).toHaveAttribute('aria-expanded', 'true');
    } else {
      await expect(fold).toBeHidden();
      await expect(top).toBeVisible();
    }
    await expectNoHorizontalOverflow(page);
    expect(await smallHitAreas(page, 'body')).toEqual([]);
    // 줄의 제목 밖(작성자 이름 자리)을 눌러도 글로 간다 — 제목 링크가 줄 전체를 덮는다.
    const author = (await page.getByRole('region', { name: '게시글' }).locator('article').nth(1).getByText('북풍').boundingBox())!;
    await page.mouse.click(author.x + author.width / 2, author.y + author.height / 2);
    await expect(page).toHaveURL(/\/board\/posts\/2$/);
  });

  test('게시판 · 정렬 · 검색 · 쪽 넘김이 쿼리에 실린다', { tag: BOTH }, async ({ page }, info) => {
    const requests = await open(page, true);
    await page.getByRole('radiogroup', { name: '게시판' }).getByRole('radio', { name: /^전략·공략/ }).click();
    await expect.poll(() => requests.at(-1)).toContain('category=STRATEGY&page=0&size=20');
    await page.getByRole('radiogroup', { name: '정렬' }).getByRole('radio', { name: '인기' }).click();
    await expect.poll(() => requests.at(-1)).toContain('sort=popular');
    if (isMobile(info)) await page.getByRole('button', { name: '검색', exact: true }).first().click();
    await page.getByRole('searchbox', { name: '검색어' }).fill('창고');
    await page.getByRole('search').getByRole('button', { name: '검색' }).click();
    await expect.poll(() => requests.at(-1)).toContain('q=%EC%B0%BD%EA%B3%A0');
    await expect(page.getByText('「창고」 검색 결과 61건')).toBeVisible();
    await page.getByRole('navigation', { name: '게시글 쪽' }).getByRole('button', { name: '다음' }).click();
    await expect.poll(() => requests.at(-1)).toContain('page=1');
  });
});
