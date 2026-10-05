// 연감 조회(계약판 K5-08 · 소비 안 K5-WAIT-04). 화면 부품은 이 함수들만 부른다.
// - 계약 본문 없는 404 · 503 = 경로가 아직 없다(서버 대기, waiting).
// - 404 YEARBOOK_NOT_PUBLISHED = 그해 연감이 아직 나오지 않음(not-published).
// - 503 YEARBOOK_SOURCE_UNAVAILABLE 등 코드 있는 실패 = 원천을 읽지 못함 — 빈 연감이나 미발행으로 보이지 않고 오류로 던진다.

import { fetchGame } from './api';
import type { YearbookPage, YearbookYear } from './yearbook-contract';

export type YearbookRead<T> =
    | { readonly kind: 'ready'; readonly data: T }
    | { readonly kind: 'waiting' }
    | { readonly kind: 'not-published' };

export class YearbookHttpError extends Error {
    constructor(readonly status: number, readonly code: string | null = null) {
        super(status === 401 ? '로그인이 필요합니다.'
            : code === 'YEARBOOK_SOURCE_UNAVAILABLE' ? '연감 기록을 읽지 못했습니다. 잠시 뒤 다시 해 보세요.'
                : '연감을 불러오지 못했습니다.');
        this.name = 'YearbookHttpError';
    }
}

async function errorCodeOf(response: Response): Promise<string | null> {
    try {
        const body = (await response.json()) as { error?: { code?: unknown } } | null;
        return typeof body?.error?.code === 'string' ? body.error.code : null;
    } catch {
        return null;
    }
}

async function read<T>(path: string, signal?: AbortSignal): Promise<YearbookRead<T>> {
    const response = await fetchGame(path, { cache: 'no-store', signal });
    if (response.ok) return { kind: 'ready', data: (await response.json()) as T };
    const code = response.status === 404 || response.status === 503 ? await errorCodeOf(response) : null;
    if (response.status === 404 && code === 'YEARBOOK_NOT_PUBLISHED') return { kind: 'not-published' };
    if ((response.status === 404 || response.status === 503) && code === null) return { kind: 'waiting' };
    throw new YearbookHttpError(response.status, code);
}

export function readYearbookYears(signal?: AbortSignal): Promise<YearbookRead<readonly YearbookYear[]>> {
    return read<readonly YearbookYear[]>('/api/yearbook/years', signal);
}

export function yearbookPath(year: number, cursor: string | null): string {
    const params = new URLSearchParams({ year: String(year) });
    if (cursor !== null) params.set('cursor', cursor);
    return `/api/yearbook?${params.toString()}`;
}

export function readYearbook(year: number, cursor: string | null, signal?: AbortSignal): Promise<YearbookRead<YearbookPage>> {
    return read<YearbookPage>(yearbookPath(year, cursor), signal);
}
