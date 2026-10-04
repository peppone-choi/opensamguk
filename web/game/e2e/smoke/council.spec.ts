// 회의실 · 기밀실(P-Q01) — 옛 게시판 API 를 합성 자료로(`/api/board` · board 명령) /game/council 을 돈다(@both).
//  「그려짐」: 머리 · 글 카드(서식) · 댓글 · 참여 레일 · 누를 영역 44 · title 0 · 네이티브 disabled 0 · 가로 넘침 0.
//  「조작됨」: 기밀실로(주소 ?room=secret) · 열람한 사람 · 새 글(종류 · 확인 · boardArticle). 「옛 주소」: /game/board → /game/council 308.
import { expect, test, type Page, type Route } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';
import { frontInfo } from '../support/campaignFixtures';

const person = (id: number, name: string) => ({ generalId: id, name, picture: null, imageServer: 0, officerLevelText: '군주' });
const MEETING = {
    result: true, secret: false, title: '회의실', blockedReason: null, myGeneralId: 7, myPermission: 0, chiefCount: 1,
    participants: [{ ...person(1, '조조'), active: true, chief: true }, { ...person(7, '하후돈'), active: false, chief: false }],
    articles: [
        { id: 11, nationId: 1, authorGeneralId: 1, authorName: '조조', title: '허현으로 도읍을 옮긴 뒤의 일', contentHtml: '<p>창고를 <strong>영음현</strong>으로 모은다.</p>', date: '2026-09-30T12:10:00Z', comments: [{ id: 1, authorGeneralId: 7, authorName: '하후돈', text: '알겠습니다', date: '2026-09-30T12:32:00Z' }], kind: 'notice' },
        { id: 12, nationId: 1, authorGeneralId: 7, authorName: '하후돈', title: '군단 쌀이 두 순 치뿐입니다', contentHtml: '<p>보내 주십시오.</p>', date: '2026-09-30T13:17:00Z', comments: [], kind: 'general' },
    ],
};
const SECRET = {
    ...MEETING, secret: true, title: '기밀실', myPermission: 2,
    articles: [{ id: 21, nationId: 1, authorGeneralId: 1, authorName: '조조', title: '원소 본대의 남하 시점', contentHtml: '<p>수뇌 밖으로 내지 말 것.</p>', date: '2026-09-30T14:40:00Z', comments: [], kind: 'operation', readers: { read: [person(1, '조조'), person(7, '하후돈')], total: 3 } }],
};

async function serve(page: Page) {
    const commands: { code: string; body: unknown }[] = [];
    const json = (route: Route, status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    const info = frontInfo();
    info.general.generalId = 7;
    await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/'), async (route) => {
        const url = new URL(route.request().url());
        const path = url.pathname.replace(/^\/api\/game\/api/, '');
        if (path === '/front-info') return json(route, 200, { ...info, nation: { id: 1, name: '조조', color: '#4f7fbf', level: 1, gold: 0, rice: 0 } });
        if (path === '/board') return json(route, 200, url.searchParams.get('secret') === 'true' ? SECRET : MEETING);
        const command = /^\/command\/(board\w+)$/.exec(path);
        if (command) {
            commands.push({ code: command[1], body: route.request().postDataJSON() });
            return json(route, 202, { status: 'AVAILABLE', requestId: `req-${commands.length}` });
        }
        if (path.startsWith('/command/result/')) return json(route, 200, { status: 'RESOLVED', ok: true, requestId: path.split('/').pop() });
        return json(route, 503, {});
    });
    return commands;
}

async function rules(page: Page) {
    const MAIN = 'main[aria-label="게임 콘텐츠"]';
    expect(await smallTouchTargets(page, MAIN)).toEqual([]);
    expect(await titleOnlyInfo(page, MAIN)).toEqual([]);
    expect(await page.locator(`${MAIN} :disabled`).count()).toBe(0);
    await expectNoHorizontalOverflow(page);
}

test.describe('회의실 · 기밀실', () => {
    test('그려짐 · 조작됨: 회의실 → 기밀실 · 열람한 사람 · 새 글', { tag: BOTH }, async ({ page }, info) => {
        const commands = await serve(page);
        await page.goto('/game/council', { waitUntil: 'domcontentloaded' });
        await expect(page.getByText('조조 · 회의실')).toBeVisible({ timeout: 60_000 });
        const notice = page.getByRole('article', { name: '허현으로 도읍을 옮긴 뒤의 일' });
        await expect(notice.locator('strong')).toHaveText('영음현');
        await expect(page.getByRole('region', { name: '회의실 참여' })).toContainText('활동 1 · 침묵 1');
        await rules(page);

        await press(page.getByRole('tab', { name: '기밀실' }), info);
        await expect(page).toHaveURL(/room=secret/);
        await expect(page.getByText('조조 · 기밀실')).toBeVisible();
        await press(page.getByRole('article', { name: '원소 본대의 남하 시점' }).getByRole('button', { name: /열람 2 \/ 3/ }), info);
        await expect(page.getByRole('dialog', { name: '열람한 사람' })).toContainText('하후돈');
        await press(page.getByRole('dialog', { name: '열람한 사람' }).getByRole('button', { name: '닫기' }), info);

        await press(page.getByRole('button', { name: isMobile(info) ? '글쓰기' : '새 글 쓰기' }), info);
        const sheet = page.getByRole('dialog', { name: '새 글 쓰기' });
        await sheet.getByRole('textbox', { name: '제목' }).fill('척후 보고');
        await sheet.locator('[contenteditable="true"]').fill('관도 방면');
        await press(sheet.getByRole('button', { name: '작전' }), info);
        await press(sheet.getByRole('button', { name: '등록' }), info);
        await press(sheet.getByRole('group', { name: '등록 확인' }).getByRole('button', { name: '등록' }), info);
        await expect.poll(() => commands.filter((c) => c.code === 'boardArticle').length).toBe(1);
        expect(commands.find((c) => c.code === 'boardArticle')!.body).toMatchObject({ isSecret: true, title: '척후 보고', kind: 'operation' });
        await expect(page.getByRole('status').filter({ hasText: /등록되었습니다|접수됨/ })).toBeVisible();
        await rules(page);
    });

    test('옛 주소: /game/board → /game/council 한 번에 308(?secret=1 은 그대로)', { tag: BOTH }, async ({ page }) => {
        const res = await page.request.get('/game/board?secret=1', { maxRedirects: 0 });
        expect(res.status()).toBe(308);
        const location = new URL(res.headers()['location'] ?? '', 'http://x');
        expect(`${location.pathname}${location.search}`).toBe('/game/council?secret=1');
    });
});
