'use client';

// 쉘(상태바·부서 나브·모바일 탭)이 쓰는 가벼운 구독: front-info 는 마운트 시 1회 + 턴 완료마다, 전역 메뉴는 1회.
// 메인(GameChrome)은 자기 useFrontInfo 로 전체 캐스케이드를 돌린다 — 요청 1회 중복은 Phase 0/1 에서 감수하고
// 게이트 리포트에 적었다(교차 비평 #11). 전역 메뉴는 서버(GetGlobalMenu)가 정본이고 픽스처는 폴백이다(#4).
import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/api';
import { GLOBAL_MENU_V2 } from '@/lib/global-menu-fixture';
import type { GatingState } from '@/lib/dept-menu-config';
import type { MenuNode } from '@/lib/menu-types';
import type { FrontInfoResponse } from '@/lib/types';
import { useTurnRefresh } from './useTurnRefresh';

export interface ShellFrontInfo {
    info: FrontInfoResponse | null;
    error: boolean;
    state: GatingState;
    menu: MenuNode[];
    reload: () => void;
}

export function useShellFrontInfo(): ShellFrontInfo {
    const [info, setInfo] = useState<FrontInfoResponse | null>(null);
    const [error, setError] = useState(false);
    const [menu, setMenu] = useState<MenuNode[]>(GLOBAL_MENU_V2);
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

    useEffect(() => {
        let alive = true;
        api.globalMenu()
            .then((m) => {
                if (alive && m?.menu?.length) setMenu(m.menu as MenuNode[]);
            })
            .catch(() => {
                /* 픽스처 유지 */
            });
        return () => {
            alive = false;
        };
    }, []);

    useTurnRefresh(reload);

    return { info, error, state: info ? 'ready' : error ? 'error' : 'loading', menu, reload };
}
