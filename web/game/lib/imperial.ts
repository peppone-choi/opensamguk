'use client';

// 황제 소재지 읽기(`GET /api/imperial/presence`) — 응답 형태 · 검증 · 화면 상태 · 훅.
//
// 계약: docs/design/imperial-presence-api.md, 응답 예시 app/game-api/src/test/resources/imperial/presence-*.json.
//  - 200 {status:'READY', badges:[…]}         황통마다 배지 하나. 공위 · 종결 황통은 배지가 없다(READY + 빈 목록 = 공위).
//  - 200 {status:'NOT_SEEDED', badges:[]}     월드에 황실이 없다. 공위나 멸망으로 읽지 않는다.
//  - 409 {status:'STATE_UNAVAILABLE', badges:[]} 황실 상태가 깨졌다. 배지를 숨기고 다시 시도를 보인다.
// 조서 · 인장 · 관계 · 호의는 이 응답에 없다(계약판 K8-10). 모르는 모양은 성공으로 치지 않는다(fail closed).

import { useCallback, useEffect, useState } from 'react';
import { api } from './api';

export type ImperialPresenceStatus = 'READY' | 'NOT_SEEDED' | 'STATE_UNAVAILABLE';
export type ImperialNodeKind = 'LAND_PROVINCE' | 'WATER_ZONE';

export interface ImperialBadge {
    readonly lineCode: string;
    readonly lineName: string;
    readonly emperorGeneralId: number;
    /** 같은 월드 장수 행의 지금 공개 이름. 빈 이름은 명시적 null — 계통명 등으로 채우지 않는다(계약판 K8-16, #1150). */
    readonly emperorName: string | null;
    readonly emperorNodeKind: ImperialNodeKind;
    /** 황제가 실제로 선 구역(省) 또는 수역 id. 배지는 이 노드에 그린다. */
    readonly emperorNodeId: string;
    /** 황제가 기준 城에 실제로 서 있을 때만 그 城 id. 성 밖 · 수역 · 전장이면 null — 위치 대용으로 쓰지 않는다. */
    readonly emperorCityId: number | null;
    /** 조정 소재지(황제 위치와 별개). 미정이면 null. */
    readonly courtCityId: number | null;
}

export interface ImperialPresence {
    readonly status: ImperialPresenceStatus;
    readonly badges: readonly ImperialBadge[];
}

/** 화면이 고르는 모양(K8 설계 보드 V31K8ImperialStates). */
export type ImperialPresenceView =
    | { readonly kind: 'NO_IMPERIAL_HOUSE' } // 황실 없음(NOT_SEEDED)
    | { readonly kind: 'UNAVAILABLE' } // 읽기 실패(409) — 배지를 숨기고 다시 시도
    | { readonly kind: 'VACANT' } // 공위 — READY 인데 배지가 없다
    | { readonly kind: 'PRESENT'; readonly badges: readonly ImperialBadge[] };

export type ImperialPresenceRead =
    | { readonly ok: true; readonly presence: ImperialPresence }
    | { readonly ok: false; readonly httpStatus: number | null };

const STATUSES: readonly ImperialPresenceStatus[] = ['READY', 'NOT_SEEDED', 'STATE_UNAVAILABLE'];
const NODE_KINDS: readonly ImperialNodeKind[] = ['LAND_PROVINCE', 'WATER_ZONE'];

function isRecord(v: unknown): v is Record<string, unknown> {
    return typeof v === 'object' && v !== null && !Array.isArray(v);
}

function isIntOrNull(v: unknown): v is number | null {
    return v === null || (typeof v === 'number' && Number.isInteger(v));
}

function parseBadge(v: unknown): ImperialBadge | null {
    if (!isRecord(v)) return null;
    const { lineCode, lineName, emperorGeneralId, emperorName, emperorNodeKind, emperorNodeId, emperorCityId, courtCityId } = v;
    if (typeof lineCode !== 'string' || lineCode.length === 0) return null;
    if (typeof lineName !== 'string') return null;
    if (typeof emperorGeneralId !== 'number' || !Number.isInteger(emperorGeneralId)) return null;
    if (!('emperorName' in v) || (emperorName !== null && typeof emperorName !== 'string')) return null;
    if (typeof emperorNodeKind !== 'string' || !NODE_KINDS.includes(emperorNodeKind as ImperialNodeKind)) return null;
    if (typeof emperorNodeId !== 'string' || emperorNodeId.length === 0) return null;
    // 계약은 null 을 「명시」한다 — 빠진 키를 null 로 채우지 않는다.
    if (!('emperorCityId' in v) || !isIntOrNull(emperorCityId)) return null;
    if (!('courtCityId' in v) || !isIntOrNull(courtCityId)) return null;
    // 수역의 황제는 城에 있을 수 없다.
    if (emperorNodeKind === 'WATER_ZONE' && emperorCityId !== null) return null;
    return {
        lineCode,
        lineName,
        emperorGeneralId,
        emperorName: emperorName === '' ? null : (emperorName as string | null),
        emperorNodeKind: emperorNodeKind as ImperialNodeKind,
        emperorNodeId,
        emperorCityId: emperorCityId as number | null,
        courtCityId: courtCityId as number | null,
    };
}

/** 응답 본문을 검증한다. 계약과 다르면 null(성공으로 치지 않는다). */
export function parseImperialPresence(body: unknown): ImperialPresence | null {
    if (!isRecord(body)) return null;
    const { status, badges } = body;
    if (typeof status !== 'string' || !STATUSES.includes(status as ImperialPresenceStatus)) return null;
    if (!Array.isArray(badges)) return null;
    if (status !== 'READY' && badges.length > 0) return null;
    const parsed: ImperialBadge[] = [];
    for (const b of badges) {
        const badge = parseBadge(b);
        if (!badge) return null;
        parsed.push(badge);
    }
    // 서버는 lineCode 오름차순으로 준다. 화면이 순서에 기대므로 어긋나면 받지 않는다.
    for (let i = 1; i < parsed.length; i++) {
        if (parsed[i - 1].lineCode >= parsed[i].lineCode) return null;
    }
    return { status: status as ImperialPresenceStatus, badges: parsed };
}

/** 200 · 409 는 본문을 검증해 넘기고, 그 밖의 상태 · 모르는 본문은 실패로 돌린다. */
export async function readImperialPresence(signal?: AbortSignal): Promise<ImperialPresenceRead> {
    let res: Response;
    try {
        res = await api.imperialPresenceResponse(signal);
    } catch (e) {
        if (e instanceof DOMException && e.name === 'AbortError') throw e;
        return { ok: false, httpStatus: null };
    }
    if (res.status !== 200 && res.status !== 409) return { ok: false, httpStatus: res.status };
    let body: unknown;
    try {
        body = await res.json();
    } catch {
        return { ok: false, httpStatus: res.status };
    }
    const presence = parseImperialPresence(body);
    if (!presence) return { ok: false, httpStatus: res.status };
    // 409 는 STATE_UNAVAILABLE 만, 200 은 그 밖의 둘만 계약이다.
    if ((res.status === 409) !== (presence.status === 'STATE_UNAVAILABLE')) return { ok: false, httpStatus: res.status };
    return { ok: true, presence };
}

export function imperialPresenceView(p: ImperialPresence): ImperialPresenceView {
    if (p.status === 'NOT_SEEDED') return { kind: 'NO_IMPERIAL_HOUSE' };
    if (p.status === 'STATE_UNAVAILABLE') return { kind: 'UNAVAILABLE' };
    if (p.badges.length === 0) return { kind: 'VACANT' };
    return { kind: 'PRESENT', badges: p.badges };
}

/** 황제가 선 곳의 모양 — 화면 문구(「성 안」 · 「성 밖」 · 「물 위」)는 부르는 쪽이 정한다. */
export type EmperorWhere = 'IN_CITY' | 'OUTSIDE_CITY' | 'ON_WATER';

export function emperorWhere(b: ImperialBadge): EmperorWhere {
    if (b.emperorNodeKind === 'WATER_ZONE') return 'ON_WATER';
    return b.emperorCityId === null ? 'OUTSIDE_CITY' : 'IN_CITY';
}

export type ImperialPresenceState =
    | { readonly state: 'loading' }
    | { readonly state: 'ready'; readonly presence: ImperialPresence; readonly view: ImperialPresenceView }
    | { readonly state: 'error'; readonly httpStatus: number | null };

/** 황제 소재지 훅. 로그인 없이도 열리는 API 라 generalId 를 받지 않는다. */
export function useImperialPresence(): ImperialPresenceState & { readonly retry: () => void } {
    const [value, setValue] = useState<ImperialPresenceState>({ state: 'loading' });
    const [nonce, setNonce] = useState(0);
    const retry = useCallback(() => setNonce((n) => n + 1), []);
    useEffect(() => {
        const controller = new AbortController();
        setValue({ state: 'loading' });
        readImperialPresence(controller.signal)
            .then((r) => {
                if (controller.signal.aborted) return;
                setValue(r.ok ? { state: 'ready', presence: r.presence, view: imperialPresenceView(r.presence) } : { state: 'error', httpStatus: r.httpStatus });
            })
            .catch(() => undefined); // 끊은 요청(AbortError)만 여기 온다.
        return () => controller.abort();
    }, [nonce]);
    return { ...value, retry };
}
