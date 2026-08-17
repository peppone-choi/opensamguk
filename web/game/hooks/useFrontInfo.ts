'use client';

// useFrontInfo — the load cascade (spec §1.5). Fetches front-info + const + global-menu, caches them,
// and exposes a refreshKey bump used after 장수 claim and on SSE turnCompleted (soft refresh — refetch,
// never window.location.reload). const + global-menu are fetched once on mount (independent of the
// refresh key); front-info refetches whenever refreshKey changes. Falls back to the GLOBAL_MENU_V2
// fixture if the menu endpoint is empty/absent (spec §4) so the chrome renders before the API lands.

import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/api';
import { GLOBAL_MENU_V2 } from '@/lib/global-menu-fixture';
import { useTurnRefresh } from './useTurnRefresh';
import type { FrontInfoResponse, GameConstResponse } from '@/lib/types';
import type { MenuNode } from '@/lib/menu-types';

interface FrontInfoState {
    frontInfo: FrontInfoResponse | null;
    constData: GameConstResponse | null;
    menu: MenuNode[];
    loading: boolean; // true until the first front-info + const resolve (asyncReady gate)
    error: string | null;
    refreshKey: number;
    refresh: () => void; // bump refreshKey → refetch front-info
}

export function useFrontInfo(): FrontInfoState {
    const [frontInfo, setFrontInfo] = useState<FrontInfoResponse | null>(null);
    const [constData, setConstData] = useState<GameConstResponse | null>(null);
    const [menu, setMenu] = useState<MenuNode[]>(GLOBAL_MENU_V2);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [refreshKey, setRefreshKey] = useState(0);

    const refresh = useCallback(() => setRefreshKey((k) => k + 1), []);

    // const + global-menu — once on mount (independent of refreshKey).
    useEffect(() => {
        let alive = true;
        void (async () => {
            try {
                const c = await api.gameConst();
                if (alive) setConstData(c);
            } catch {
                /* const is non-blocking; GameInfo falls back to defaults */
            }
            try {
                const m = await api.globalMenu();
                if (alive && m?.menu?.length) setMenu(m.menu as MenuNode[]);
            } catch {
                /* keep the v2 fixture */
            }
        })();
        return () => {
            alive = false;
        };
    }, []);

    // front-info — refetch on every refreshKey bump (mount = key 0).
    useEffect(() => {
        let alive = true;
        void (async () => {
            try {
                const fi = await api.frontInfo();
                if (alive) {
                    setFrontInfo(fi);
                    setError(null);
                }
            } catch {
                if (alive) setError('서버 정보를 불러올 수 없습니다.');
            } finally {
                if (alive) setLoading(false);
            }
        })();
        return () => {
            alive = false;
        };
    }, [refreshKey]);

    // 턴 완료 → soft refresh (front-info만 재조회). 연결은 Shell 하나뿐이므로 여기서 EventSource를
    // 새로 열지 않는다 (OPENSAM-196).
    useTurnRefresh(refresh);

    return { frontInfo, constData, menu, loading, error, refreshKey, refresh };
}
