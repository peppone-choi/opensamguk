'use client';

// 쉘(상태바·부서 나브·모바일 탭)이 쓰는 가벼운 front-info 구독. 마운트 시 1회 + 턴 완료마다 재조회.
// 메인(GameChrome)은 자기 useFrontInfo 로 전체 캐스케이드를 돌린다 — Phase 1 에서 하나로 합친다.
import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/api';
import type { FrontInfoResponse } from '@/lib/types';
import { useTurnRefresh } from './useTurnRefresh';

export interface ShellFrontInfo {
    info: FrontInfoResponse | null;
    error: boolean;
    reload: () => void;
}

export function useShellFrontInfo(): ShellFrontInfo {
    const [info, setInfo] = useState<FrontInfoResponse | null>(null);
    const [error, setError] = useState(false);
    const [tick, setTick] = useState(0);
    const reload = useCallback(() => setTick((t) => t + 1), []);

    useEffect(() => {
        let alive = true;
        api.frontInfo()
            .then((fi) => {
                if (!alive) return;
                setInfo(fi);
                setError(false);
            })
            .catch(() => {
                if (!alive) return;
                setError(true);
            });
        return () => {
            alive = false;
        };
    }, [tick]);

    useTurnRefresh(reload);

    return { info, error, reload };
}
