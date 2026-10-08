'use client';

// 내 전투 목록 읽기(P-C04, K6-11) — 인증·권한·조회 실패와 정상 빈 응답을 구분한다.
// 빈 배열은 이번 조회 결과만 뜻한다. producer · 서비스 가용성이 확인되기 전에는 활성화 완료로 세지 않는다(A안).
// SOURCE_UNAVAILABLE도 목록 원천의 불가 응답일 뿐 producer 꺼짐을 추정하지 않는다.
// 모양이 계약과 다르면 목록을 지어내지 않고 「읽지 못함」 + 다시 시도. 고정 자료는 시험에만.
import { useCallback, useEffect, useState } from 'react';
import { fetchGame } from '../api';
import { activeBattlesPath, decodeActiveBattles, sortActiveBattles, type ActiveBattleRow } from './active-list';

export type ActiveBattlesLoad =
    | { readonly state: 'loading' }
    | { readonly state: 'waiting' }
    | { readonly state: 'empty'; readonly onRetry: () => void }
    | { readonly state: 'unauthorized' | 'forbidden' }
    | { readonly state: 'source-unavailable'; readonly onRetry: () => void }
    | { readonly state: 'error'; readonly errorCode?: string; readonly onRetry: () => void }
    | { readonly state: 'ready'; readonly rows: readonly ActiveBattleRow[]; readonly onRetry: () => void };

export function useActiveBattles(generalId: number | null): ActiveBattlesLoad {
    const [seq, setSeq] = useState(0);
    const retry = useCallback(() => setSeq((n) => n + 1), []);
    const [load, setLoad] = useState<ActiveBattlesLoad>({ state: 'loading' });

    useEffect(() => {
        if (generalId == null) {
            setLoad({ state: 'loading' });
            return undefined;
        }
        const ctrl = new AbortController();
        setLoad({ state: 'loading' });
        (async () => {
            try {
                const res = await fetchGame(activeBattlesPath(generalId), { cache: 'no-store', signal: ctrl.signal });
                if (ctrl.signal.aborted) return;
                if (res.status === 401) return setLoad({ state: 'unauthorized' });
                if (res.status === 403) return setLoad({ state: 'forbidden' });
                if (!res.ok) {
                    const body: unknown = await res.json().catch(() => null);
                    if (ctrl.signal.aborted) return;
                    const error = body && typeof body === 'object' && 'error' in body ? body.error : null;
                    const code = error && typeof error === 'object' && 'code' in error ? error.code : null;
                    if (res.status === 503 && code === 'SOURCE_UNAVAILABLE')
                        return setLoad({ state: 'source-unavailable', onRetry: retry });
                    return setLoad({ state: 'error', errorCode: `HTTP_${res.status}`, onRetry: retry });
                }
                const rows = decodeActiveBattles(await res.json().catch(() => null));
                if (ctrl.signal.aborted) return;
                if (rows == null) return setLoad({ state: 'error', errorCode: 'INVALID_RESPONSE', onRetry: retry });
                setLoad(rows.length === 0 ? { state: 'empty', onRetry: retry } : { state: 'ready', rows: sortActiveBattles(rows), onRetry: retry });
            } catch {
                if (!ctrl.signal.aborted) setLoad({ state: 'error', errorCode: 'NETWORK_ERROR', onRetry: retry });
            }
        })();
        return () => ctrl.abort();
    }, [generalId, seq, retry]);

    return load;
}
