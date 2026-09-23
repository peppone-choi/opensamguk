'use client';

// 휘하 화면의 공용 세션 — 로그인한 계정의 장수·세력·날짜·규칙.
//
// `/game/**` 안에 있으므로 AuthGate 가 로그인을 확정한 뒤에 돈다. 장수와 서버는 기존 게임 화면과
// 같은 경로로 정한다 — `front-info` 가 장수를, `sam_server` 쿠키(미들웨어가 URL 에서 심는다)가
// 서버를 정한다. 휘하 API 는 모두 `?generalId=` 로 장수를 받으므로 여기서 한 번만 풀어 둔다.

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { api } from './api';
import { useServerId } from './serverGameUrl';
import type { FrontInfoResponse } from './types';

export interface HwihaSession {
    readonly loading: boolean;
    readonly error: string | null;
    readonly frontInfo: FrontInfoResponse | null;
    /** 로그인한 계정의 장수. 장수가 없으면 null. */
    readonly generalId: number | null;
    readonly serverId: string | undefined;
    /** 휘하 규칙 월드인지. 아니면 휘하 API 는 모두 `WRONG_RULE_PROFILE` 을 돌려준다. */
    readonly isHwihaWorld: boolean;
    /** 「200년 3월 중순」 같은 게임 날짜 문구. */
    readonly gameDate: string;
    readonly refresh: () => void;
}

const HwihaSessionContext = createContext<HwihaSession | null>(null);

export function formatHwihaDate(info: FrontInfoResponse | null): string {
    if (!info) return '';
    const { year, month, turnPhaseText } = info.global;
    return `${year}년 ${month}월${turnPhaseText ? ` ${turnPhaseText}` : ''}`;
}

export function HwihaSessionProvider({ children }: { children: React.ReactNode }) {
    const serverId = useServerId();
    const [frontInfo, setFrontInfo] = useState<FrontInfoResponse | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [loading, setLoading] = useState(true);
    const [refreshKey, setRefreshKey] = useState(0);
    const refresh = useCallback(() => setRefreshKey((k) => k + 1), []);

    useEffect(() => {
        const controller = new AbortController();
        setError(null);
        api.frontInfo(controller.signal)
            .then((info) => setFrontInfo(info))
            .catch((e: unknown) => {
                if (controller.signal.aborted) return;
                setError(e instanceof Error ? e.message : '장수 정보를 불러오지 못했습니다.');
            })
            .finally(() => {
                if (!controller.signal.aborted) setLoading(false);
            });
        return () => controller.abort();
    }, [refreshKey]);

    const value = useMemo<HwihaSession>(() => ({
        loading,
        error,
        frontInfo,
        generalId: frontInfo?.general.hasGeneral ? frontInfo.general.generalId : null,
        serverId,
        isHwihaWorld: frontInfo?.global.ruleProfile === 'HWIHA',
        gameDate: formatHwihaDate(frontInfo),
        refresh,
    }), [error, frontInfo, loading, refresh, serverId]);

    return <HwihaSessionContext.Provider value={value}>{children}</HwihaSessionContext.Provider>;
}

export function useHwihaSession(): HwihaSession {
    const session = useContext(HwihaSessionContext);
    if (!session) throw new Error('useHwihaSession 은 HwihaSessionProvider 안에서만 쓴다');
    return session;
}
