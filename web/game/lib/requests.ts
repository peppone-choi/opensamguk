'use client';

// 받은 요청(응답 대기) — 발령 응답(court.dispatchReply) · 정치 동의(court.politicalConsent). K6 설계서 §2.5.
// 서신 「요청」 탭 · 조정 「받은 요청」 띠(K4) · 지난 순 서랍(K4)이 같은 읽기를 쓴다: 한 곳에서 응답하면
// 모든 useRequests가 다시 읽어 「수락함 · 거절함」이 세 곳에 같이 보인다. 응답은 순을 쓰지 않으므로 그 자리에서 보낸다.
//
// 서버가 준 것만 옮긴다: 기한은 dueAt, 거절 결과는 발령 규칙(기한 넘기면 자동 수락 · 거절하면 충성 · 명망 감소 —
// 지금 조정 화면과 같은 안내)만. 정치 동의의 거절 결과는 서버 규칙에 없어서 그리지 않는다.
import { useCallback, useEffect, useState } from 'react';
import { api } from './api';
import { submitCommandAndAwaitResult, type CommandSubmitResult } from './commandSubmit';
import { availabilityOf, type InputAvailability } from './input-availability';
import type { DispatchPendingItem, DispatchPendingResponse, Phase, PoliticalConsentOption } from './types';

export type RequestKind = 'dispatch' | 'politicalConsent';
/** cancelled = 발령이 무효가 되어 벌점 없이 취소됨(엔진 CANCELLED). 기한이 지나면 엔진이 자동 수락하므로 「기한 지남」은 발령에 없다. */
export type RequestState = 'waiting' | 'accepted' | 'refused' | 'cancelled';

export interface IncomingRequest {
    /** 화면 안에서 고유한 키(`dispatch:<id>` · `consent:<inputId>:<issuer>`). */
    readonly key: string;
    readonly kind: RequestKind;
    readonly inputId: 'court.dispatchReply' | 'court.politicalConsent';
    /** 종류 칩. */
    readonly label: string;
    readonly from: { readonly generalId: number; readonly name: string };
    readonly what: string;
    /** 기한 글자(서버 dueAt) — 없으면 null. */
    readonly due: string | null;
    readonly consequence: string | null;
    readonly state: RequestState;
    readonly availability: InputAvailability | null;
    /** 응답 보내기에 쓰는 원자료. */
    readonly ref: { readonly dispatchId: string } | { readonly issuerGeneralId: number; readonly consentInputId: 'action.abdicate' | 'action.oath' };
}

/**
 * 요청 키 — 기록 줄의 refs(K4-03 REQUEST)로 카드 한 장을 꺼낼 때 쓴다.
 * 발령 = `dispatch:<dispatchId>`, 정치 동의 = `consent:<action.abdicate|action.oath>:<보낸 장수 id>`.
 */
export function requestKey(ref: { kind: 'dispatch'; dispatchId: string } | { kind: 'politicalConsent'; inputId: 'action.abdicate' | 'action.oath'; issuerGeneralId: number }): string {
    return ref.kind === 'dispatch' ? `dispatch:${ref.dispatchId}` : `consent:${ref.inputId}:${ref.issuerGeneralId}`;
}

export function findRequest(requests: readonly IncomingRequest[], key: string): IncomingRequest | null {
    return requests.find((r) => r.key === key) ?? null;
}

const PHASES = ['상순', '중순', '하순'];
export function phaseLabel(p: Phase): string {
    const part = PHASES[p.phase - 1];
    return `${p.year}년 ${p.month}월${part ? ` ${part}` : ''}`;
}

const DISPATCH_STATE: Record<DispatchPendingItem['status'], RequestState> = {
    PENDING: 'waiting', ACCEPTED: 'accepted', REFUSED: 'refused', CANCELLED: 'cancelled',
};

/**
 * 나에게 온 발령만 요청이다(내가 낸 발령은 조정 화면의 「발령 현황」).
 * result=false(STATE_UNAVAILABLE · WRONG_RULE_PROFILE — HTTP 200)는 읽기 실패다 — 빈 목록이 아니므로 useRequests가 실패로 친다.
 */
export function fromDispatches(res: DispatchPendingResponse, me: number): IncomingRequest[] {
    if (!res.result) return [];
    return res.dispatches.filter((d) => d.targetId === me).map((d) => {
        const state = DISPATCH_STATE[d.status];
        return {
            key: requestKey({ kind: 'dispatch', dispatchId: d.dispatchId }),
            kind: 'dispatch',
            inputId: 'court.dispatchReply',
            label: '발령',
            from: { generalId: d.issuerId, name: d.issuerLabel ?? '주공' },
            what: d.countyLabel ? `${d.countyLabel}(으)로 가라는 발령입니다` : '다른 현으로 가라는 발령입니다',
            due: `${phaseLabel(d.dueAt)}까지 · 넘기면 수락`,
            consequence: '충성과 명망이 줄어듭니다',
            state,
            // 막힘 사유는 서버 문자열(K6-20 currentFailureReason)만 그대로 쓴다 — 없거나 null(서버 반영 전 · 옛 응답)이면 코드만
            // 넘기고 사유 문장은 지어내지 않는다(InputAction이 「사유를 받지 못했습니다」). 문자열은 가공하지 않는다(문구는 C1 · C7).
            availability: state !== 'waiting' ? null : availabilityOf('court.dispatchReply', {
                options: d.currentFailure
                    ? { available: false, code: d.currentFailure, ...(d.currentFailureReason ? { reason: d.currentFailureReason } : {}) }
                    : null,
            }),
            ref: { dispatchId: d.dispatchId },
        } satisfies IncomingRequest;
    });
}

const CONSENT_LABEL: Record<PoliticalConsentOption['inputId'], { label: string; what: string }> = {
    'action.abdicate': { label: '선양 동의', what: '나에게 자리를 물려주려 합니다 — 동의가 필요합니다' },
    'action.oath': { label: '결의 동의', what: '나와 결의를 맺으려 합니다 — 동의가 필요합니다' },
};

export function fromConsents(list: readonly PoliticalConsentOption[]): IncomingRequest[] {
    return list.map((c) => {
        const state: RequestState = c.accepted === true ? 'accepted' : c.accepted === false ? 'refused' : 'waiting';
        const text = CONSENT_LABEL[c.inputId];
        return {
            key: requestKey({ kind: 'politicalConsent', inputId: c.inputId, issuerGeneralId: c.issuerGeneralId }),
            kind: 'politicalConsent',
            inputId: 'court.politicalConsent',
            label: text.label,
            from: { generalId: c.issuerGeneralId, name: c.issuerName },
            what: text.what,
            due: null,
            consequence: null,
            state,
            availability: state !== 'waiting' ? null : availabilityOf('court.politicalConsent', {
                options: { available: c.available, code: c.code, reason: c.reason },
            }),
            ref: { issuerGeneralId: c.issuerGeneralId, consentInputId: c.inputId },
        } satisfies IncomingRequest;
    });
}

// ── 같은 읽기를 쓰는 곳끼리 응답을 알린다 ─────────────────────────────────────
const listeners = new Set<() => void>();
function announce() { for (const l of [...listeners]) l(); }

export async function respondToRequest(generalId: number, request: IncomingRequest, accept: boolean): Promise<CommandSubmitResult> {
    const out = await submitCommandAndAwaitResult(() => ('dispatchId' in request.ref
        ? api.courtDispatchReply(generalId, { dispatchId: request.ref.dispatchId, accept })
        : api.courtPoliticalConsent(generalId, {
            issuerGeneralId: request.ref.issuerGeneralId, inputId: request.ref.consentInputId, accepted: accept,
        })));
    if (out.status !== 'rejected') announce();
    return out;
}

export type RequestsLoad =
    | { readonly state: 'loading' }
    /** 두 읽기가 모두 실패 — 빈 것과 다른 모양(다시 시도). */
    | { readonly state: 'error'; readonly message: string }
    /** partial = 두 읽기 중 하나만 실패했다(받은 것은 보이고 한 줄 알린다). */
    | { readonly state: 'ready'; readonly requests: readonly IncomingRequest[]; readonly partial: boolean };

export interface RequestRejection {
    readonly side: 'accept' | 'refuse';
    readonly seq: number;
    readonly code?: string;
    readonly reason?: string;
}

export interface UseRequests {
    readonly load: RequestsLoad;
    /** 응답 대기 수(머리줄 서신 배지 「응답 n」). */
    readonly waiting: number;
    readonly respond: (request: IncomingRequest, accept: boolean) => Promise<CommandSubmitResult>;
    readonly busyKey: string | null;
    /** 마지막 응답 거절 — 카드 key → 누른 쪽 · 서버 code · reason. 다시 누르면 지운다. */
    readonly rejected: Readonly<Record<string, RequestRejection>>;
    readonly reload: () => void;
}

/** 받은 요청 읽기 — generalId가 없으면 부르지 않는다. refreshKey가 바뀌면(턴 갱신 등) 다시 읽는다. */
export function useRequests(generalId: number | null, refreshKey = 0): UseRequests {
    const [load, setLoad] = useState<RequestsLoad>({ state: 'loading' });
    const [seq, setSeq] = useState(0);
    const [busyKey, setBusyKey] = useState<string | null>(null);
    const [rejected, setRejected] = useState<Record<string, RequestRejection>>({});
    const reload = useCallback(() => setSeq((n) => n + 1), []);

    useEffect(() => {
        listeners.add(reload);
        return () => { listeners.delete(reload); };
    }, [reload]);

    useEffect(() => {
        if (generalId == null) return undefined;
        let alive = true;
        Promise.allSettled([api.dispatchPending(generalId), api.politicalConsentOptions(generalId)]).then(([d, c]) => {
            if (!alive) return;
            // 발령 대기 읽기는 실패를 HTTP 200 + result=false로도 준다 — 그것도 실패다(빈 목록으로 보이면 안 된다).
            const dispatchOk = d.status === 'fulfilled' && d.value.result !== false;
            const consentOk = c.status === 'fulfilled';
            if (!dispatchOk && !consentOk) {
                setLoad({ state: 'error', message: '받은 요청을 불러오지 못했습니다' });
                return;
            }
            const requests = [
                ...(dispatchOk ? fromDispatches(d.value, generalId) : []),
                ...(consentOk ? fromConsents(c.value) : []),
            ];
            setLoad({ state: 'ready', requests, partial: !dispatchOk || !consentOk });
        });
        return () => { alive = false; };
    }, [generalId, refreshKey, seq]);

    const respond = useCallback(async (request: IncomingRequest, accept: boolean) => {
        if (generalId == null) return { status: 'rejected', reason: '장수가 없습니다' } as CommandSubmitResult;
        setBusyKey(request.key);
        setRejected((r) => { const next = { ...r }; delete next[request.key]; return next; });
        try {
            const out = await respondToRequest(generalId, request, accept);
            if (out.status === 'rejected') {
                const rejection: RequestRejection = {
                    side: accept ? 'accept' : 'refuse', seq: Date.now(),
                    ...(out.code ? { code: out.code } : {}), ...(out.reason ? { reason: out.reason } : {}),
                };
                setRejected((r) => ({ ...r, [request.key]: rejection }));
            }
            return out;
        } finally {
            setBusyKey(null);
        }
    }, [generalId]);

    const waiting = load.state === 'ready' ? load.requests.filter((r) => r.state === 'waiting').length : 0;
    return { load, waiting, respond, busyKey, rejected, reload };
}
