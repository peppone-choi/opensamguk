// 연감 조회(계약판 K5-08 · 소비 안 K5-WAIT-04). 화면 부품은 이 함수들만 부른다.
// - 계약 본문 없는 404 · 503 = 경로가 아직 없다(서버 대기, waiting).
// - 404 YEARBOOK_NOT_PUBLISHED = 그해 연감이 아직 나오지 않음(not-published).
// - 503 YEARBOOK_SOURCE_UNAVAILABLE 등 코드 있는 실패 = 원천을 읽지 못함 — 빈 연감이나 미발행으로 보이지 않고 오류로 던진다.

import { fetchGame } from './api';
import type { YearbookPage, YearbookYear } from './yearbook-contract';
import { yearbookPartsError } from './yearbook-view';

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

// Validate the existing consumer shape before exposing a successful response.
// D124 consumer shape is fixed; actual producer/runtime ACK remains separate.
export class YearbookResponseError extends Error {
    constructor() {
        super('연감 응답이 요청한 해의 기록과 맞지 않습니다. 다시 시도해 주세요.');
        this.name = 'YearbookResponseError';
    }
}

const record = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value);
const integer = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value);
const text = (value: unknown): value is string => typeof value === 'string';
const utcTime = (value: unknown): boolean => {
    if (!text(value) || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|\+00:00)$/.test(value)) return false;
    const time = Date.parse(value);
    return Number.isFinite(time) && new Date(time).toISOString().slice(0, 19) === value.slice(0, 19);
};
const scalarRecord = (value: unknown): boolean => record(value) && Object.values(value).every((v) => text(v) || (typeof v === 'number' && Number.isFinite(v)));

function yearsShape(value: unknown): value is readonly YearbookYear[] {
    return Array.isArray(value) && value.every((row: unknown) => record(row) && integer(row.year) && typeof row.published === 'boolean');
}

function pageShape(value: unknown, year: number): value is YearbookPage {
    if (!record(value) || value.year !== year || !Array.isArray(value.territory) || !Array.isArray(value.events)
        || !(value.nextCursor === null || text(value.nextCursor))) return false;
    if (!value.territory.every((row: unknown) => record(row) && integer(row.nationId) && text(row.name) && text(row.color)
        && integer(row.countyCount) && (row.capitalCityId === null || integer(row.capitalCityId))
        && (row.counties === undefined || row.counties === null || (Array.isArray(row.counties) && row.counties.every((county: unknown) => record(county) && integer(county.cityId) && text(county.name)))))) return false;
    if (!value.events.every((event: unknown) => record(event) && integer(event.id) && text(event.kind) && text(event.section)
        && record(event.occurredAt) && integer(event.occurredAt.year) && integer(event.occurredAt.month)
        && integer(event.occurredAt.phase) && integer(event.occurredAt.ordinal) && scalarRecord(event.refs) && scalarRecord(event.facts))) return false;
    const snapshot = value.snapshot;
    if (snapshot !== undefined && (!record(snapshot) || !integer(snapshot.worldId) || snapshot.year !== year
        || !text(snapshot.revision) || snapshot.revision.length === 0 || !utcTime(snapshot.publishedAt))) return false;
    const ownership = value.ownership;
    if (ownership !== undefined && ownership !== null && (!record(ownership) || !text(ownership.revision) || !text(ownership.mapPin)
        || !Array.isArray(ownership.provinces) || !ownership.provinces.every((province: unknown) => record(province) && integer(province.index) && integer(province.nationId)))) return false;
    if (value.absent !== undefined && (!Array.isArray(value.absent)
        || !value.absent.every((part: unknown) => part === 'ownership' || part === 'counties')
        || new Set(value.absent).size !== value.absent.length)) return false;
    return yearbookPartsError(value as unknown as YearbookPage) === null;
}

async function read<T>(path: string, shape: (value: unknown) => value is T, signal?: AbortSignal, pageRequest = true): Promise<YearbookRead<T>> {
    const response = await fetchGame(path, { cache: 'no-store', signal });
    if (response.ok) {
        const body: unknown = await response.json();
        if (!shape(body)) throw new YearbookResponseError();
        return { kind: 'ready', data: body };
    }
    const code = response.status === 404 || response.status === 503 ? await errorCodeOf(response) : null;
    if (pageRequest && response.status === 404 && code === 'YEARBOOK_NOT_PUBLISHED') return { kind: 'not-published' };
    if ((response.status === 404 || response.status === 503) && code === null) return { kind: 'waiting' };
    throw new YearbookHttpError(response.status, code);
}

export function readYearbookYears(signal?: AbortSignal): Promise<YearbookRead<readonly YearbookYear[]>> {
    return read('/api/yearbook/years', yearsShape, signal, false);
}

export function yearbookPath(year: number, cursor: string | null): string {
    const params = new URLSearchParams({ year: String(year) });
    if (cursor !== null) params.set('cursor', cursor);
    return `/api/yearbook?${params.toString()}`;
}

export function readYearbook(year: number, cursor: string | null, signal?: AbortSignal): Promise<YearbookRead<YearbookPage>> {
    return read(yearbookPath(year, cursor), (value): value is YearbookPage => pageShape(value, year), signal);
}
