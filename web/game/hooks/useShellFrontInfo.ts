'use client';

// 쉘(상태바·부서 나브·모바일 탭)이 쓰는 front-info 구독.
import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/api';
import type { GatingState } from '@/lib/dept-menu-config';
import type { FrontInfoResponse } from '@/lib/types';
import { useTurnRefresh } from './useTurnRefresh';

export interface ShellFrontInfo {
    info: FrontInfoResponse | null;
    error: boolean;
    state: GatingState;
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

    return { info, error, state: info ? 'ready' : error ? 'error' : 'loading', reload };
}
