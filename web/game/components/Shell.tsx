'use client';

import { useCallback } from 'react';
import { usePathname } from 'next/navigation';
import Header from './Header';
import BackBar from './BackBar';
import BottomNav from './BottomNav';
import { useSSE } from '../hooks/useSSE';
import { deliverTurnCompleted } from '../lib/turnEvents';
import { normalizeGamePathname, useServerId } from '../lib/serverGameUrl';

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

    useSSE(refresh);

    return (
        <div className="shell">
            <Header />
            {/* 좌측 사이드바 제거(사용자 요청 — 1000px 폭에서 너비 부족). 네비는 Header(상단)+BottomNav(하단)+GameChrome GlobalMenu. */}
            <div className="shell-body">
                <main className="shell-main shell-scroll-surface" aria-label="게임 콘텐츠">
                    {/* 서브 페이지 공통 돌아가기/갱신 바(레거시 TopBackBar). 메인은 GameChrome이라 미적용. */}
                    {!isMainPage && <BackBar />}
                    {children}
                </main>
            </div>
            <BottomNav />
        </div>
    );
}
