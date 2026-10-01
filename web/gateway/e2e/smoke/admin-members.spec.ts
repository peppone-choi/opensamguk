// P-G09 운영 콘솔 · 회원(게이트웨이) — 데스크톱 · 모바일 같은 흐름 스모크(@both). 백엔드 없이 돈다(page.route).
// 회원 조치(차단 · 임시 비밀번호)는 합성 응답에만 대고 누른다. 실제 계정엔 닿지 않는다.
import { expect, test, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, titleOnlyInfo } from '../../../game/e2e/support/parity';
import { smallHitAreas } from '../support/hitArea';

const ADMIN = { id: 1, username: 'admin', nickname: '운영자', role: 'ADMIN', email: null, picture: null, imageServer: 0 };
const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
const member = (id: number, username: string, extra: Record<string, unknown> = {}) => ({
  id, username, email: `${username}@example.com`, authType: 'local', grade: 1, gradeLabel: '일반', blockUntil: null, nickname: `${username}별명`,
  icon: null, joinDate: '2026-09-01T10:00:00', lastLoginAt: '2026-10-01T09:00:00', deleteAfter: null, generalNamesByServer: {}, ...extra,
});
const USERS = [member(11, 'spam01'), member(12, 'boss', { grade: 6, gradeLabel: '운영자' }), member(13, 'blocked', { grade: 0, blockUntil: '2027-01-01T00:00:00', email: null })];

async function open(page: Page, baseURL: string | undefined) {
  const posts: { path: string; body: unknown }[] = [];
  await page.context().addCookies([{ name: 'sam_access', value: 'smoke', url: baseURL ?? 'http://127.0.0.1:3000' }]);
  await page.route('**/api/auth/me', (route) => route.fulfill(json({ user: ADMIN })));
  await page.route('**/api/proxy/admin/**', (route) => {
    const url = new URL(route.request().url());
    if (route.request().method() === 'POST') {
      posts.push({ path: url.pathname, body: route.request().postDataJSON() });
      if (url.pathname.endsWith('/reset_pw')) return route.fulfill(json({ result: true, detail: 'Tmp-9f3K2' }));
      return route.fulfill(json({ result: true }));
    }
    if (url.pathname.endsWith('/admin/users')) return route.fulfill(json({ users: USERS, servers: ['pep'], allowJoin: true, allowLogin: true }));
    if (url.pathname.endsWith('/admin/version')) return route.fulfill(json({ gateway: { reachable: true, version: '0.9.2', imageTag: 'sha-1', buildTime: null }, servers: [], skew: false }));
    if (url.pathname.endsWith('/admin/scenarios')) return route.fulfill(json({ scenarios: [] }));
    return route.fulfill(json({}, 404));
  });
  await page.goto('/admin');
  await page.getByRole('navigation', { name: '운영 콘솔' }).getByRole('button', { name: '회원', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: '회원' })).toBeVisible();
  await expect(page.getByText('spam01', { exact: true })).toBeVisible();
  return posts;
}

test.describe('P-G09 운영 콘솔 · 회원 — 데스크톱 · 모바일 같은 흐름', () => {
  test('그려진다: 상태 칩 · 서버 대기 · 가로 넘침 없음 · 누를 영역 44', { tag: BOTH }, async ({ page, baseURL }) => {
    await open(page, baseURL);
    await expect(page.getByText('차단 · 2027-01-01까지')).toBeVisible();
    await expect(page.getByText('서버 대기').first()).toBeVisible();
    await expect(page.getByText(/부운영자|별도 권한|전콘/)).toHaveCount(0);
    await expectNoHorizontalOverflow(page);
    expect(await smallHitAreas(page, 'body')).toEqual([]);
    expect(await titleOnlyInfo(page), 'title 전용 정보 금지').toEqual([]);
  });

  test('조치: 목록(모바일 하단 시트) → 차단 일수 칸 → 합성 응답에만 보낸다', { tag: BOTH }, async ({ page, baseURL }, info) => {
    const posts = await open(page, baseURL);
    await page.getByRole('row').filter({ hasText: 'spam01' }).getByRole('button', { name: '조치' }).click();
    const sheet = page.getByRole('dialog', { name: 'spam01 조치' });
    await expect(sheet).toBeVisible();
    if (isMobile(info)) {
      const box = (await sheet.boundingBox())!;
      expect(Math.round(box.y + box.height)).toBe(page.viewportSize()!.height);
    }
    expect(await smallHitAreas(page, '[role="dialog"]')).toEqual([]);
    await sheet.getByRole('button', { name: /^차단/ }).click();
    const block = page.getByRole('dialog', { name: 'spam01 차단' });
    await expect(block.getByLabel('차단 일수')).toHaveValue('7');
    await block.getByLabel('차단 일수').fill('30');
    await expect(block).toContainText('spam01 계정을 차단합니다(30일). 계속할까요?');
    await block.getByRole('button', { name: '차단', exact: true }).click();
    await expect.poll(() => posts.map((p) => p.path)).toEqual(['/api/proxy/admin/users/11/block']);
    expect(posts[0].body).toEqual({ param: 30 });
  });

  test('임시 비밀번호는 결과 창에서만 보인다', { tag: BOTH }, async ({ page, baseURL }) => {
    await open(page, baseURL);
    await page.getByRole('row').filter({ hasText: 'spam01' }).getByRole('button', { name: '조치' }).click();
    await page.getByRole('dialog', { name: 'spam01 조치' }).getByRole('button', { name: /임시 비밀번호 발급/ }).click();
    await page.getByRole('dialog', { name: '임시 비밀번호 발급' }).getByRole('button', { name: '발급' }).click();
    const result = page.getByRole('dialog', { name: 'spam01 임시 비밀번호' });
    await expect(result.getByText('Tmp-9f3K2')).toBeVisible();
    await expect(page.getByText(/Tmp-9f3K2/)).toHaveCount(1);
    await result.getByRole('button', { name: '닫기' }).click();
    await expect(page.getByText(/Tmp-9f3K2/)).toHaveCount(0);
  });
});
