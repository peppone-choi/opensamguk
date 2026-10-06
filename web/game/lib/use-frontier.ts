'use client';

// 주변 세계 훅(K8-09) — 읽기(lib/api/frontier)를 화면 상태(FrontierLoad)로 옮긴다. 화면은 이 훅만 부른다(D105 층).
//  - 경로 없음(404, 배포 전) → 서버 대기(waiting).
//  - NOT_SEEDED(접촉 원천 없음) · 셈 못 함 · 읽기 실패 → 「자료 없음」(unavailable, D29) — 빈 상태와 다르고 다시 읽을 수 있다.
//  - READY [] → 접경한 주변 세계 없음(empty, 내륙).
import { useCallback, useEffect, useState } from 'react';
import { readFrontier } from './api/frontier';

export type FrontierLoad =
    | { readonly state: 'loading' }
    | { readonly state: 'waiting' }
    | { readonly state: 'unavailable'; readonly onReload: () => void }
    | { readonly state: 'empty' };

type Read = 'loading' | 'waiting' | 'unavailable' | 'empty';

export function useFrontier(generalId: number | null): FrontierLoad {
    const [read, setRead] = useState<Read>('loading');
    const [nonce, setNonce] = useState(0);
    const retry = useCallback(() => setNonce((n) => n + 1), []);

    useEffect(() => {
        if (generalId == null) return undefined;
        const controller = new AbortController();
        setRead('loading');
        readFrontier(generalId, controller.signal)
            .then((r) => {
                if (controller.signal.aborted) return;
                if (!r.ok) setRead(r.httpStatus === 404 ? 'waiting' : 'unavailable');
                else setRead(r.frontier.status === 'READY' ? 'empty' : 'unavailable');
            })
            .catch(() => undefined); // 끊은 요청(AbortError)만 여기 온다.
        return () => controller.abort();
    }, [generalId, nonce]);

    return read === 'unavailable' ? { state: 'unavailable', onReload: retry } : { state: read };
}
