'use client';

// Front-info + game constants for the shared join flow. Refetch front-info after a turn.

import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/api';
import { useTurnRefresh } from './useTurnRefresh';
import type { FrontInfoResponse, GameConstResponse } from '@/lib/types';

const FRONT_INFO_TIMEOUT_MS = 10_000;
const FRONT_INFO_TIMEOUT_MESSAGE = '서버 응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.';

interface FrontInfoState {
    frontInfo: FrontInfoResponse | null;
    constData: GameConstResponse | null;
    loading: boolean; // true until the first front-info + const resolve (asyncReady gate)
    error: string | null;
    refreshKey: number;
    refresh: () => void; // bump refreshKey → refetch front-info
}

export function useFrontInfo(): FrontInfoState {
    const [frontInfo, setFrontInfo] = useState<FrontInfoResponse | null>(null);
    const [constData, setConstData] = useState<GameConstResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [refreshKey, setRefreshKey] = useState(0);

    const refresh = useCallback(() => setRefreshKey((k) => k + 1), []);

    // Constants load once, independently of front-info refreshes.
    useEffect(() => {
        let alive = true;
        void (async () => {
            try {
                const c = await api.gameConst();
                if (alive) setConstData(c);
            } catch {
                /* const is non-blocking; GameInfo falls back to defaults */
            }
        })();
        return () => {
            alive = false;
        };
    }, []);

    // front-info — refetch on every refreshKey bump (mount = key 0).
    useEffect(() => {
        let alive = true;
        let expired = false;
        const controller = new AbortController();
        const timeout = window.setTimeout(() => {
            if (!alive) return;
            expired = true;
            controller.abort();
            setError(FRONT_INFO_TIMEOUT_MESSAGE);
            setLoading(false);
        }, FRONT_INFO_TIMEOUT_MS);
        void (async () => {
            try {
                const fi = await api.frontInfo(controller.signal);
                if (alive && !expired) {
                    setFrontInfo(fi);
                    setError(null);
                }
            } catch {
                if (alive && !expired) setError('서버 정보를 불러올 수 없습니다.');
            } finally {
                window.clearTimeout(timeout);
                if (alive && !expired) setLoading(false);
            }
        })();
        return () => {
            alive = false;
            window.clearTimeout(timeout);
            controller.abort();
        };
    }, [refreshKey]);

    // 턴 완료 → soft refresh (front-info만 재조회). 연결은 Shell 하나뿐이므로 여기서 EventSource를
    // 새로 열지 않는다 (OPENSAM-196).
    useTurnRefresh(refresh);

    return { frontInfo, constData, loading, error, refreshKey, refresh };
}
