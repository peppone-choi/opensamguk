// 도움말 API 대역 — 저장소의 data/help/*.json · data/commands/input-catalog.json 으로 game-api HelpController 를 흉내 낸다
// (app/game-api/.../help/HelpController.kt · HelpStore.kt 의 규칙: 검색 2–80자 · 제목 → 설명 → 예 순 · 입력별 사유 검사).
// 백엔드 없이 도는 스모크용(e2e/smoke 규칙). 튜토리얼 진척은 계약 fixture.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { Page, Route } from '@playwright/test';

const ROOT = join(__dirname, '..', '..', '..', '..');
const read = (p: string) => JSON.parse(readFileSync(join(ROOT, p), 'utf-8'));

interface Row { inputId: string; kind: string; displayName: string | null; deliveryState: string; actor: string; authorityRule: string;
    targetSchema: unknown; costSchema: unknown; timing: unknown; effectScope: string; failureReasons: string[]; helpTopicId: string; tutorialObjectiveId: string }
interface Topic { id: string; title: string; reviewState: string; sections: { explanation: string; example: string } }
interface Reason { code: string; reviewState: string; explanation: string; recoveryAdvice: string; byInputId: Record<string, { explanation?: string; recoveryAdvice?: string }> }

const catalog: Row[] = read('data/commands/input-catalog.json').inputs;
const topics: Topic[] = read('data/help/topics.json').topics;
const reasons: Reason[] = read('data/help/failure-reasons.json').reasons;
export const progressFixture = read('docs/development/fixtures/help-tutorial/tutorial-progress-start.json');

const topicById = new Map(topics.map((t) => [t.id, t]));
const rowById = new Map(catalog.map((r) => [r.inputId, r]));
const reasonByCode = new Map(reasons.map((r) => [r.code, r]));

const json = (route: Route, status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
const err = (route: Route, status: number, code: string) => json(route, status, { error: { code, message: code } });

export interface HelpApiOptions {
    /** 모든 도움말 응답을 이 상태로(503 등). */
    readonly forceStatus?: { status: number; code: string };
    /** 불린 요청 경로(검사용). */
    readonly log?: string[];
}

export async function serveHelpApi(page: Page, options: HelpApiOptions = {}) {
    await page.route('**/api/auth/me', (route) => json(route, 200, { user: { id: 1, loginId: 'k7-smoke', nickname: '하후돈' } }));
    await page.route('**/api/game/**', async (route) => {
        const url = new URL(route.request().url());
        const path = url.pathname.replace(/^\/api\/game/, '');
        options.log?.push(`${path}${url.search}`);
        if (path.startsWith('/api/help/') && options.forceStatus) return err(route, options.forceStatus.status, options.forceStatus.code);
        if (path.startsWith('/api/help/topics/')) {
            const t = topicById.get(decodeURIComponent(path.slice('/api/help/topics/'.length)));
            return t ? json(route, 200, { schemaVersion: 1, topic: t }) : err(route, 404, 'HELP_TOPIC_NOT_FOUND');
        }
        if (path === '/api/help/search') {
            const q = (url.searchParams.get('q') ?? '').trim();
            const limit = Number(url.searchParams.get('limit') ?? '20');
            if (q.length < 2 || q.length > 80 || !(limit >= 1 && limit <= 50)) return err(route, 400, 'INVALID_SEARCH_QUERY');
            const order = { title: 0, explanation: 1, example: 2 } as const;
            const hits = topics.flatMap((t) => {
                const sec = t.title.includes(q) ? 'title' : t.sections.explanation.includes(q) ? 'explanation' : t.sections.example.includes(q) ? 'example' : null;
                return sec ? [{ id: t.id, title: t.title, reviewState: t.reviewState, excerpt: sec === 'example' ? t.sections.example : t.sections.explanation, matchedSection: sec }] : [];
            }).sort((a, b) => order[a.matchedSection as keyof typeof order] - order[b.matchedSection as keyof typeof order] || a.id.localeCompare(b.id)).slice(0, limit);
            return json(route, 200, { schemaVersion: 1, query: q, hits });
        }
        if (path === '/api/help/context') {
            const row = rowById.get(url.searchParams.get('inputId') ?? '');
            if (!row) return err(route, 404, 'INPUT_NOT_FOUND');
            const { inputId, kind, displayName, deliveryState, actor, authorityRule, targetSchema, costSchema, timing, effectScope, failureReasons, helpTopicId } = row;
            return json(route, 200, { schemaVersion: 1, topic: topicById.get(helpTopicId), input: { inputId, kind, displayName, deliveryState, actor, authorityRule,
                targetSchema, costSchema, timing, effectScope, failureReasons, helpTopicId, tutorialObjectiveId: row.tutorialObjectiveId === 'N/A' ? null : row.tutorialObjectiveId } });
        }
        if (path.startsWith('/api/help/failures/')) {
            const code = decodeURIComponent(path.slice('/api/help/failures/'.length));
            const inputId = url.searchParams.get('inputId');
            const row = inputId ? rowById.get(inputId) : undefined;
            if (inputId && !row) return err(route, 404, 'INPUT_NOT_FOUND');
            const help = reasonByCode.get(code);
            if (!help) return err(route, 404, 'FAILURE_REASON_NOT_FOUND');
            if (row && !row.failureReasons.includes(code)) return err(route, 400, 'REASON_NOT_FOR_INPUT');
            const ctx = inputId ? help.byInputId[inputId] : undefined;
            const related = row ? [row.helpTopicId] : [...new Set(catalog.filter((r) => r.failureReasons.includes(code)).map((r) => r.helpTopicId))].sort();
            return json(route, 200, { schemaVersion: 1, reason: code, reviewState: help.reviewState, explanation: ctx?.explanation ?? help.explanation,
                recoveryAdvice: ctx?.recoveryAdvice ?? help.recoveryAdvice, relatedTopicIds: related });
        }
        if (path === '/api/tutorial/progress') return json(route, 200, progressFixture);
        // 셸이 부르는 그 밖의 읽기 — 이 스모크의 대상이 아니다.
        return json(route, 404, {});
    });
}
