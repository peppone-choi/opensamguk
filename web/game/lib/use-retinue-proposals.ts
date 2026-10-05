'use client';

// 참모 제안 훅(K8-06) — 읽기(lib/api/retinue-proposals)를 화면 상태(ProposalsLoad)로 옮긴다. 화면은 이 훅만 부른다(D105 층).
//  - 경로 없음(404, 배포 전) · NOT_SEEDED(제안을 만드는 서버 producer 없음) → 서버 대기(waiting, K8-06) — 「제안 없음」이 아니다.
//  - 셈 못 함(UNAVAILABLE) · 읽기 실패 → 「읽을 수 없음」(unavailable) — 다시 읽을 수 있다.
//  - READY [] → 이번 순 제안 없음(empty).
import { useCallback, useEffect, useState } from 'react';
import { readRetinueProposals } from './api/retinue-proposals';

export type ProposalsLoad =
    | { readonly state: 'loading' }
    | { readonly state: 'waiting' }
    | { readonly state: 'unavailable'; readonly onReload: () => void }
    | { readonly state: 'empty' };

type Read = 'loading' | 'waiting' | 'unavailable' | 'empty';

export function useRetinueProposals(generalId: number | null): ProposalsLoad {
    const [read, setRead] = useState<Read>('loading');
    const [nonce, setNonce] = useState(0);
    const retry = useCallback(() => setNonce((n) => n + 1), []);

    useEffect(() => {
        if (generalId == null) return undefined;
        const controller = new AbortController();
        setRead('loading');
        readRetinueProposals(generalId, controller.signal)
            .then((r) => {
                if (controller.signal.aborted) return;
                if (!r.ok) setRead(r.httpStatus === 404 ? 'waiting' : 'unavailable');
                else if (r.proposals.status === 'NOT_SEEDED') setRead('waiting');
                else setRead(r.proposals.status === 'READY' ? 'empty' : 'unavailable');
            })
            .catch(() => undefined); // 끊은 요청(AbortError)만 여기 온다.
        return () => controller.abort();
    }, [generalId, nonce]);

    return read === 'unavailable' ? { state: 'unavailable', onReload: retry } : { state: read };
}
