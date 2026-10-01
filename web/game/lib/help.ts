// 도움말 · 튜토리얼 읽기 — game-api `/api/help/**` 4종과 `/api/tutorial/progress`.
// 계약: docs/development/help-tutorial-api-contract.md. 응답 필드는 계약 그대로 옮긴다.
//
// - 도움말은 로그인 없이 읽힌다. 휘하 규칙 월드에서만 답하고, 월드가 없으면 503 `WORLD_UNAVAILABLE`,
//   다른 규칙이면 404 `WORLD_PROFILE_UNAVAILABLE` 이다. 화면은 상태를 가르므로 오류 코드를 버리지 않는다.
// - 사람 글은 지금 전부 `reviewState: DRAFT`(초안)다. 화면은 「초안」을 보인다.
// - 식별자(inputId · helpTopicId · 사유 코드 · 목표 id)는 화면에 쓰지 않는다. 제목 · 설명만 보인다.
// - 튜토리얼 완료는 서버가 확정한 사건으로만 바뀐다. 화면이 누름으로 완료를 칠하지 않는다.
import { fetchGame } from './api';

export type ReviewState = 'DRAFT' | 'APPROVED';

export interface HelpSection {
    readonly explanation: string;
    readonly example: string;
    readonly successExample: string;
    readonly failureExample: string;
    readonly recoveryAdvice: string;
    readonly historicalContext?: string | null;
}

export interface HistoricalSource {
    readonly tradition: 'CHRONICLE' | 'ROMANCE';
    readonly work: string;
    readonly book: string;
    readonly passage?: string | null;
}

export interface HelpTopic {
    readonly id: string;
    readonly title: string;
    readonly reviewState: ReviewState;
    readonly sections: HelpSection;
    readonly sources: readonly HistoricalSource[];
    readonly relatedTopicIds: readonly string[];
}

export interface HelpTopicResponse {
    readonly schemaVersion: 1;
    readonly topic: HelpTopic;
}

export interface HelpSearchHit {
    readonly id: string;
    readonly title: string;
    readonly reviewState: ReviewState;
    readonly excerpt: string;
    readonly matchedSection: 'title' | 'explanation' | 'example' | string;
}

export interface HelpSearchResponse {
    readonly schemaVersion: 1;
    readonly query: string;
    readonly hits: readonly HelpSearchHit[];
}

export type InputKind = 'GENERAL_ACTION' | 'PLACEMENT' | 'POLICY' | 'WORK' | 'STRATAGEM' | 'COURT_DECISION';

export interface CostSchema {
    readonly status?: string;
    readonly money?: number | null;
    readonly grain?: number | null;
    readonly iron?: number | null;
    readonly timber?: number | null;
    readonly horses?: number | null;
    readonly [key: string]: unknown;
}

export interface InputContract {
    readonly inputId: string;
    readonly kind: InputKind;
    readonly displayName: string | null;
    readonly deliveryState: string;
    readonly actor: string;
    readonly authorityRule: string;
    readonly targetSchema: { readonly status?: string; readonly [key: string]: unknown };
    readonly costSchema: CostSchema;
    readonly timing: { readonly phase?: string; readonly turnSlots?: number | null; readonly perPhaseLimit?: number | null; readonly [key: string]: unknown };
    readonly effectScope: string;
    readonly failureReasons: readonly string[];
    readonly helpTopicId: string;
    readonly tutorialObjectiveId: string | null;
}

export interface ContextHelpResponse {
    readonly schemaVersion: 1;
    readonly topic: HelpTopic;
    readonly input: InputContract;
}

export interface FailureHelpResponse {
    readonly schemaVersion: 1;
    readonly reason: string;
    readonly reviewState: ReviewState;
    readonly explanation: string;
    readonly recoveryAdvice: string;
    readonly relatedTopicIds: readonly string[];
}

export type ObjectiveStatus = 'LOCKED' | 'CURRENT' | 'COMPLETED';

export interface ObjectiveProgress {
    readonly id: string;
    readonly title: string;
    readonly order: number;
    readonly scope: 'ACCOUNT' | 'GENERAL';
    readonly prerequisites: readonly string[];
    readonly status: ObjectiveStatus;
    readonly completedAt: string | null;
    readonly helpTopicId: string | null;
}

export interface TutorialProgressResponse {
    readonly schemaVersion: 1;
    readonly worldId: number;
    readonly accountId: string;
    readonly generalId: number | null;
    readonly objectives: readonly ObjectiveProgress[];
}

// ── 오류 — 상태와 서버 코드를 보존한다 ─────────────────────────────────────────
/** 화면이 가르는 오류 종류. */
export type HelpErrorKind =
    | 'NOT_FOUND' // 404 주제 · 입력 · 사유 없음
    | 'BAD_QUERY' // 400 검색어 · 사유가 이 입력의 것이 아님
    | 'WORLD_UNAVAILABLE' // 503 활성 월드 없음 · 점검
    | 'PROFILE_UNAVAILABLE' // 404 휘하가 아닌 규칙 월드
    | 'AUTH' // 401 진척은 로그인 필요
    | 'NETWORK' // 연결 실패
    | 'OTHER'; // 그 밖의 5xx 등

export class HelpApiError extends Error {
    readonly status: number;
    readonly code: string | null;

    constructor(status: number, code: string | null, message: string) {
        super(message);
        this.name = 'HelpApiError';
        this.status = status;
        this.code = code;
    }
}

export function helpErrorKind(error: unknown): HelpErrorKind {
    if (!(error instanceof HelpApiError)) return 'NETWORK';
    if (error.status === 401) return 'AUTH';
    if (error.status === 503) return 'WORLD_UNAVAILABLE';
    if (error.status === 400) return 'BAD_QUERY';
    if (error.status === 404) return error.code === 'WORLD_PROFILE_UNAVAILABLE' ? 'PROFILE_UNAVAILABLE' : 'NOT_FOUND';
    return 'OTHER';
}

async function read<T>(path: string, signal?: AbortSignal): Promise<T> {
    const res = await fetchGame(path, { cache: 'no-store', signal });
    if (!res.ok) {
        let code: string | null = null;
        let message = `${res.status}: ${res.statusText}`;
        try {
            const body: unknown = await res.json();
            const err = body && typeof body === 'object' ? (body as { error?: { code?: unknown; message?: unknown } }).error : undefined;
            if (err && typeof err.code === 'string') code = err.code;
            if (err && typeof err.message === 'string' && err.message) message = err.message;
        } catch {
            // 본문이 JSON 이 아니면 상태줄로 둔다.
        }
        throw new HelpApiError(res.status, code, message);
    }
    return res.json() as Promise<T>;
}

// 정적 도움말(주제 · 문맥 · 실패 사유)은 배포 단위로만 바뀐다 — 한 탭 안에서는 한 번만 읽는다.
// 실패한 읽기는 캐시에 남기지 않는다(다시 시도가 새로 읽게).
const cache = new Map<string, Promise<unknown>>();

function cached<T>(path: string): Promise<T> {
    const hit = cache.get(path);
    if (hit) return hit as Promise<T>;
    const pending = read<T>(path).catch((error: unknown) => {
        cache.delete(path);
        throw error;
    });
    cache.set(path, pending);
    return pending;
}

/** 테스트 전용. */
export function __resetHelpCache() {
    cache.clear();
}

// ── 검색어 규칙(서버 400 을 요청 전에 막는다) ───────────────────────────────────
export const SEARCH_MIN = 2;
export const SEARCH_MAX = 80;
export const SEARCH_LIMIT = 20;
export const SEARCH_LIMIT_MAX = 50;

/** 보낼 수 있는 검색어면 다듬은 문자열, 아니면 null. */
export function searchQuery(raw: string): string | null {
    const q = raw.trim();
    return q.length >= SEARCH_MIN && q.length <= SEARCH_MAX ? q : null;
}

const enc = encodeURIComponent;

export const helpApi = {
    topic: (topicId: string) => cached<HelpTopicResponse>(`/api/help/topics/${enc(topicId)}`),
    context: (inputId: string) => cached<ContextHelpResponse>(`/api/help/context?inputId=${enc(inputId)}`),
    failure: (reason: string, inputId?: string | null) =>
        cached<FailureHelpResponse>(`/api/help/failures/${enc(reason)}${inputId ? `?inputId=${enc(inputId)}` : ''}`),
    search: (query: string, limit = SEARCH_LIMIT, signal?: AbortSignal) =>
        read<HelpSearchResponse>(`/api/help/search?q=${enc(query)}&limit=${Math.min(Math.max(limit, 1), SEARCH_LIMIT_MAX)}`, signal),
    tutorialProgress: (signal?: AbortSignal) => read<TutorialProgressResponse>('/api/tutorial/progress', signal),
};
