// 장수 만들기 조회 · 쓰기(계약판 K5-01 · K5-02 · K5-03, 서버 #1137). 화면 부품은 이 함수들만 부른다(직접 fetch 하지 않는다).
// 서버 오류 본문은 `{ error: { code, message } }` 이고, 문장은 서버 것을 그대로 보인다.
// main 에 경로가 없으면(404, 계약 본문 없음) · 정책이 닫혔으면(503 CREATION_POLICY_UNAVAILABLE) 「생성 대기」(waiting)다.

import { fetchGame } from './api';
import type {
    CreationAccepted,
    CreationCell,
    CreationCounty,
    CreationCountyWire,
    CreationError,
    CreationRequest,
    CreationResult,
    GeneralCreationOptions,
    GeneralCreationOptionsWire,
    HistoricalCreationPage,
    HistoricalStatus,
} from './creation-contract';

/** 받은 HTTP 상태 + 서버 오류(있으면). 문장은 서버 것, 없으면 상태별 기본 문장. */
export class CreationHttpError extends Error {
    constructor(readonly status: number, readonly code: string | null, message: string) {
        super(message);
        this.name = 'CreationHttpError';
    }
}

/** 조회 결과 — 서버가 아직 없거나(경로 없음) 정책이 닫혔으면 waiting. */
export type CreationRead<T> =
    | { readonly kind: 'ready'; readonly data: T }
    | { readonly kind: 'waiting'; readonly code: string | null; readonly message: string | null };

const FALLBACK: Readonly<Record<number, string>> = {
    401: '로그인이 필요합니다.',
    403: '이 화면을 쓸 권한이 없습니다.',
};

async function errorOf(response: Response): Promise<CreationError | null> {
    try {
        const body: unknown = await response.json();
        const error = (body as { error?: unknown } | null)?.error as Partial<CreationError> | undefined;
        if (error && typeof error.code === 'string' && typeof error.message === 'string') return { code: error.code, message: error.message };
    } catch {
        // 본문 없음 · JSON 아님 — 계약 본문이 아니다.
    }
    return null;
}

async function failure(response: Response): Promise<CreationHttpError> {
    const error = await errorOf(response);
    return new CreationHttpError(response.status, error?.code ?? null, error?.message ?? FALLBACK[response.status] ?? `${response.status}: 요청을 처리하지 못했습니다.`);
}

/** 조회: 200 → ready, 404(경로 없음) · 503(정책 닫힘) → waiting, 그 밖은 던진다. */
async function read<T>(path: string, signal?: AbortSignal): Promise<CreationRead<T>> {
    const response = await fetchGame(path, { cache: 'no-store', signal });
    if (response.ok) return { kind: 'ready', data: (await response.json()) as T };
    if (response.status === 404 || response.status === 503) {
        const error = await errorOf(response);
        return { kind: 'waiting', code: error?.code ?? null, message: error?.message ?? null };
    }
    throw await failure(response);
}

/** 역사 인물 거르기 — 서버 인자 그대로(`q` · `nation` · `status`, 정렬은 서버가 등록순만 받는다). */
export interface HistoricalQuery {
    readonly q: string;
    /** 세력 id. 0 = 재야. null = 전체. */
    readonly nation: number | null;
    readonly status: HistoricalStatus | null;
}

/** 한 번에 받는 수(서버 1–100). */
export const HISTORICAL_PAGE_LIMIT = 50;

export function historicalPath(query: HistoricalQuery, cursor: string | null): string {
    const params = new URLSearchParams({ sort: 'ID_ASC', limit: String(HISTORICAL_PAGE_LIMIT) });
    const q = query.q.trim();
    if (q) params.set('q', q);
    if (query.nation !== null) params.set('nation', String(query.nation));
    if (query.status !== null) params.set('status', query.status);
    if (cursor !== null) params.set('cursor', cursor);
    return `/api/generals/creation/historical?${params.toString()}`;
}

export function readHistoricalPage(query: HistoricalQuery, cursor: string | null, signal?: AbortSignal): Promise<CreationRead<HistoricalCreationPage>> {
    return read<HistoricalCreationPage>(historicalPath(query, cursor), signal);
}

/**
 * 본관 현 지도 칸 — 중첩 `cell` 이 있으면 그것, 없으면 납작한 `cellCol · cellRow`(#1137 초안).
 * 빠졌거나 정수가 아닌 칸은 null 이다 — 지어내지 않고, 빠진 칸(undefined)으로 지도 카메라에 NaN 이 들어가지 않게 한다.
 */
export function countyCellOf(county: CreationCountyWire): CreationCell | null {
    const cell = county.cell !== undefined
        ? county.cell
        : county.cellCol != null && county.cellRow != null ? { col: county.cellCol, row: county.cellRow } : null;
    return cell && Number.isInteger(cell.col) && Number.isInteger(cell.row) ? { col: cell.col, row: cell.row } : null;
}

function countyOf(county: CreationCountyWire): CreationCounty {
    const { cell: _cell, cellCol: _col, cellRow: _row, ...rest } = county;
    return { ...rest, cell: countyCellOf(county) };
}

export async function readCreationOptions(signal?: AbortSignal): Promise<CreationRead<GeneralCreationOptions>> {
    const got = await read<GeneralCreationOptionsWire>('/api/generals/creation/options', signal);
    if (got.kind !== 'ready') return got;
    return { kind: 'ready', data: { ...got.data, nativeCounties: got.data.nativeCounties.map(countyOf) } };
}

/** 생성 접수 — 202 만 받아들인다. 거절(400 · 409 · 422 · 503)은 서버 문장을 담아 던진다. */
export async function submitCreation(request: CreationRequest): Promise<CreationAccepted> {
    const response = await fetchGame('/api/generals/creation', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
    });
    if (response.status === 202) return (await response.json()) as CreationAccepted;
    throw await failure(response);
}

export async function readCreationResult(requestId: string, signal?: AbortSignal): Promise<CreationResult> {
    const response = await fetchGame(`/api/generals/creation/${encodeURIComponent(requestId)}`, { cache: 'no-store', signal });
    if (!response.ok) throw await failure(response);
    return (await response.json()) as CreationResult;
}
