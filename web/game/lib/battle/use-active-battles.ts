'use client';

// 내 전투 목록 읽기(P-C04, K6-11) — 서버가 꺼져 있거나 아직 없으면(401 · 403 · 404 · 5xx) 지금처럼 「전투가 열리지 않음」(서버 대기).
// 빈 배열도 아직 서버 대기다(A안 — producer · 서비스 사용가능 원천이 main 에 들어오기 전에는 「전투 없음」으로 세지 않는다).
// 모양이 계약과 다르면 목록을 지어내지 않고 「읽지 못함」 + 다시 시도. 고정 자료는 시험에만.
import { useCallback, useEffect, useState } from 'react';
import { fetchGame } from '../api';
import { activeBattlesPath, decodeActiveBattles, sortActiveBattles, type ActiveBattleRow } from './active-list';

export type ActiveBattlesLoad =
    | { readonly state: 'loading' }
    | { readonly state: 'waiting' }
    | { readonly state: 'error'; readonly onRetry: () => void }
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
                if (!res.ok) return setLoad({ state: 'waiting' });
                const rows = decodeActiveBattles(await res.json());
                if (rows == null) return setLoad({ state: 'error', onRetry: retry });
                setLoad(rows.length === 0 ? { state: 'waiting' } : { state: 'ready', rows: sortActiveBattles(rows), onRetry: retry });
            } catch {
                if (!ctrl.signal.aborted) setLoad({ state: 'error', onRetry: retry });
            }
        })();
        return () => ctrl.abort();
    }, [generalId, seq, retry]);

    return load;
}
