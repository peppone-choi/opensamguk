'use client';

// 모바일 5탭(S1): 작전실 · 지도 · 명령 · 국가 · 더보기. 「더보기」는 부서 시트(같은 6그룹)를 연다.
import { useState } from 'react';
import { usePathname } from 'next/navigation';
import DeptNav, { resolveDeptHref } from './DeptNav';
import { MOBILE_TABS, type ControlGating } from '@/lib/dept-menu-config';
import type { MenuFlagSource } from '@/lib/menu-types';
import { normalizeGamePathname, useServerId } from '@/lib/serverGameUrl';

export interface BottomNavProps {
    gating?: ControlGating | null;
    global?: MenuFlagSource;
}

export default function BottomNav({ gating = null, global = {} }: BottomNavProps) {
    const pathname = usePathname();
    const serverId = useServerId();
    const normalizedPathname = normalizeGamePathname(pathname ?? '', serverId);
    const [moreOpen, setMoreOpen] = useState(false);

    return (
        <>
            <nav className="game-bottom-nav" aria-label="Mobile">
                {MOBILE_TABS.map((tab) => {
                    if (tab.key === 'more') {
                        return (
                            <button
                                key={tab.key}
                                type="button"
                                className={`game-bottom-item${moreOpen ? ' active' : ''}`}
                                aria-expanded={moreOpen}
                                aria-controls="dept-more"
                                onClick={() => setMoreOpen((o) => !o)}
                            >
                                <span className="game-bottom-label">{tab.label}</span>
                            </button>
                        );
                    }
                    const target = tab.href.split('#')[0];
                    const active = tab.key === 'commands' ? false : normalizedPathname === target;
                    return (
                        <a
                            key={tab.key}
                            href={resolveDeptHref(tab.href, serverId)}
                            className={`game-bottom-item${active ? ' active' : ''}`}
                            aria-current={active ? 'page' : undefined}
                            onClick={() => setMoreOpen(false)}
                        >
                            <span className="game-bottom-label">{tab.label}</span>
                        </a>
                    );
                })}
            </nav>
            {moreOpen && (
                <div className="dept-sheet" role="dialog" aria-label="부서 메뉴" id="dept-more">
                    <button type="button" className="dept-sheet__scrim" aria-label="닫기" onClick={() => setMoreOpen(false)} />
                    <div className="dept-sheet__panel">
                        <DeptNav gating={gating} global={global} vertical onNavigate={() => setMoreOpen(false)} />
                    </div>
                </div>
            )}
        </>
    );
}
