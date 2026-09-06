'use client';

// 게임 쉘 — ADR-LITE-049: 상태바(56) + 부서 나브(44) + 콘텐츠 + 모바일 5탭. 좌측 사이드바 없음(1000px 폭 사용자 요청 유지).
import { useCallback, useMemo } from 'react';
import { usePathname } from 'next/navigation';
import Header from './Header';
import BackBar from './BackBar';
import BottomNav from './BottomNav';
import DeptNav from './DeptNav';
import { useSSE } from '../hooks/useSSE';
import { useShellFrontInfo } from '../hooks/useShellFrontInfo';
import { deliverTurnCompleted } from '../lib/turnEvents';
import { normalizeGamePathname, useServerId } from '../lib/serverGameUrl';
import type { ControlGating } from '../lib/dept-menu-config';
import type { MenuFlagSource } from '../lib/menu-types';

export default function Shell({ children }: { children: React.ReactNode }) {
    const pathname = usePathname();
    const serverId = useServerId();
    // OPENSAM-196 — 턴 SSE는 페이지를 리로드하지 않는다. 앱 전역에 하나뿐인 이 연결이 신호를
    // 받아 화면 구독자(useTurnRefresh)에게 나눠 주고, 각 화면이 자기 데이터만 다시 읽는다.
    const refresh = useCallback(() => {
        deliverTurnCompleted();
    }, []);
    const normalizedPathname = normalizeGamePathname(pathname ?? '', serverId);
    const isMainPage = normalizedPathname === '/game';
    const { info, error } = useShellFrontInfo();

    useSSE(refresh);

    const gating: ControlGating | null = useMemo(() => {
        if (!info?.general.hasGeneral) return null;
        const g = info.general;
        return {
            showSecret: g.showSecret,
            permission: g.permission,
            myLevel: g.officerLevel,
            nationLevel: info.nation?.level ?? 0,
            isTournamentApplicationOpen: Boolean(info.global.isTournamentApplicationOpen),
            isBettingActive: Boolean(info.global.isBettingActive),
        };
    }, [info]);
    const global = (info?.global ?? {}) as unknown as MenuFlagSource;

    return (
        <div className="shell">
            <Header info={info} error={error} />
            <DeptNav gating={gating} global={global} />
            <div className="shell-body">
                <main className="shell-main shell-scroll-surface" aria-label="게임 콘텐츠">
                    {/* 서브 페이지 공통 돌아가기/갱신 바(레거시 TopBackBar). 메인은 GameChrome이라 미적용. */}
                    {!isMainPage && <BackBar />}
                    {children}
                </main>
            </div>
            <BottomNav gating={gating} global={global} />
        </div>
    );
}
