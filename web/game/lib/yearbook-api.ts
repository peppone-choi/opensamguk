// 연감 조회(계약판 K5-08). 화면 부품은 이 함수들만 부른다. 경로가 아직 없으면(404) · 서버가 닫혔으면(503) waiting(서버 대기).

import { fetchGame } from './api';
import type { YearbookPage, YearbookYear } from './yearbook-contract';

export type YearbookRead<T> = { readonly kind: 'ready'; readonly data: T } | { readonly kind: 'waiting' };

export class YearbookHttpError extends Error {
    constructor(readonly status: number) {
        super(status === 401 ? '로그인이 필요합니다.' : '연감을 불러오지 못했습니다.');
        this.name = 'YearbookHttpError';
    }
}

async function read<T>(path: string, signal?: AbortSignal): Promise<YearbookRead<T>> {
    const response = await fetchGame(path, { cache: 'no-store', signal });
    if (response.ok) return { kind: 'ready', data: (await response.json()) as T };
    if (response.status === 404 || response.status === 503) return { kind: 'waiting' };
    throw new YearbookHttpError(response.status);
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
