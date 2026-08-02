'use client';

import { useCallback } from 'react';
import { usePathname } from 'next/navigation';
import Header from './Header';
import BackBar from './BackBar';
import BottomNav from './BottomNav';
import { useSSE } from '../hooks/useSSE';
import { normalizeGamePathname, useServerId } from '../lib/serverGameUrl';

export default function Shell({ children }: { children: React.ReactNode }) {
    const pathname = usePathname();
  const serverId = useServerId();
    const refresh = useCallback(() => {
        window.location.reload();
    }, []);
  const normalizedPathname = normalizeGamePathname(pathname ?? '', serverId);
    const isMainPage = normalizedPathname === '/game';

    useSSE(refresh);

    return (
        <div className="shell">
            <Header />
            {/* 좌측 사이드바 제거(사용자 요청 — 1000px 폭에서 너비 부족). 네비는 Header(상단)+BottomNav(하단)+GameChrome GlobalMenu. */}
            <div className="shell-body">
                <main className="shell-main">
                    {/* 서브 페이지 공통 돌아가기/갱신 바(레거시 TopBackBar). 메인은 GameChrome이라 미적용. */}
                    {!isMainPage && <BackBar />}
                    {children}
                </main>
            </div>
            <BottomNav />
        </div>
    );
}
