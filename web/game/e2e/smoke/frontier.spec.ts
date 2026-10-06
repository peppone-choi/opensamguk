// 주변 세계(P-K08) — 외교(/game/court/diplomacy)의 「주변 세계」 탭을 백엔드 없이 합성 자료로 돈다(나머지 게임 읽기는 503).
// 주변 세계 읽기(GET /api/frontier, K8-09)는 경우마다 정한다 — 경로 없음(404, 배포 전) · 서버 고정 응답 NOT_SEEDED(#1407).
// 두 프로필(@both): 404 면 서버 대기(K8-09) 한 칸 · 안내 한 줄, NOT_SEEDED 면 「자료 없음」(다시 읽기, 서버 대기 표지 없음).
// 어느 쪽이든 지어낸 행위자 · 입력 단추 없음, 누를 영역 44 · title 0 · disabled 0 · 넘침 0.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Page, type Route } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const API = '/api/game/api';
// 주변 세계 서버 시험(#1407)의 고정 응답 — 접촉 원천이 아직 없다.
const NOT_SEEDED = JSON.parse(readFileSync(join(__dirname, '..', '..', '..', '..', 'app/game-api/src/test/resources/frontier/not-seeded.json'), 'utf-8'));
const NOT_FOUND = { status: 404, body: { error: { code: 'NOT_FOUND', message: 'No static resource' } } };

async function open(page: Page, frontier: { readonly status: number; readonly body: unknown } = NOT_FOUND) {
    const json = (route: Route, status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/'), async (route) => {
        const path = new URL(route.request().url()).pathname.slice(API.length);
        if (path === '/front-info') {
            return json(route, 200, {
                result: true,
                global: { year: 200, month: 3, turnPhase: 2, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
                general: { hasGeneral: true, generalId: 7, name: '하후돈', nationId: 1, officerLevel: 5, permission: 0, showSecret: false },
                nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
            });
        }
        if (path === '/frontier') return json(route, frontier.status, frontier.body);
        if (path === '/diplomacy/conflict') {
            return json(route, 200, {
                result: true, conflict: [], myNationID: 1,
                nations: [
                    { nation: 1, name: '조조', color: '#4f7fbf', type: '', level: 1, capital: 0, gennum: 1, cities: ['허현'], power: 0 },
                    { nation: 2, name: '원소', color: '#b04a3c', type: '', level: 1, capital: 0, gennum: 1, cities: ['업현'], power: 0 },
                ],
                diplomacyList: { 1: { 2: 2 } },
            });
        }
        return json(route, 503, {});
    });
    await page.goto('/game/court/diplomacy', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { level: 2, name: '외교' })).toBeVisible({ timeout: 60_000 });
}

const MAIN = 'main[aria-label="게임 콘텐츠"]';

async function rules(page: Page) {
    expect(await smallTouchTargets(page, MAIN)).toEqual([]);
    expect(await titleOnlyInfo(page, MAIN)).toEqual([]);
    expect(await page.locator(`${MAIN} :disabled`).count()).toBe(0);
    await expectNoHorizontalOverflow(page);
}

test('주변 세계 탭: 서버 대기(K8-09) · 안내 한 줄 · 지어낸 행위자 없음 · 규칙', { tag: [BOTH] }, async ({ page }, testInfo) => {
    await open(page);
    const panel = page.getByRole('region', { name: '외교' });
    await press(panel.getByRole('tab', { name: '주변 세계' }), testInfo);
    await expect(panel.getByRole('tab', { name: '주변 세계' })).toHaveAttribute('aria-selected', 'true');
    await expect(panel.locator('[data-server-wait="K8-09"] .os-status--waiting')).toBeVisible();
    await expect(panel).toContainText('주변 세계 준비 중');
    await expect(panel).toContainText('주변 세계는 지도 밖 세력입니다');
    await expect(panel).not.toContainText('고구려');
    await expect(panel.locator('[data-input-id]')).toHaveCount(0);
    await rules(page);
});

test('주변 세계 탭: 서버가 원천 없음(NOT_SEEDED)이라 답하면 「자료 없음」 · 다시 읽기 · 서버 대기 표지 없음 · 규칙', { tag: [BOTH] }, async ({ page }, testInfo) => {
    await open(page, { status: 200, body: NOT_SEEDED });
    const panel = page.getByRole('region', { name: '외교' });
    await press(panel.getByRole('tab', { name: '주변 세계' }), testInfo);
    await expect(panel).toContainText('주변 세계를 읽을 수 없습니다');
    await expect(panel).not.toContainText('접경한 주변 세계가 없습니다'); // 원천 없음은 「무접촉」이 아니다
    await expect(panel.locator('[data-server-wait="K8-09"]')).toHaveCount(0);
    await expect(panel.getByRole('button', { name: /다시 읽기/ })).toBeVisible();
    await expect(panel).not.toContainText('고구려');
    await expect(panel.locator('[data-input-id]')).toHaveCount(0);
    await rules(page);
});
