'use client';

// 모바일 5탭(S1): 작전실 · 지도 · 명령 · 국가 · 더보기. 「더보기」는 부서 시트(같은 6그룹)를 연다.
// 「국가」는 세력 정보(#11)와 같은 게이팅을 타고, 막히면 점선 + 사유로 남는다.
import { useEffect, useRef, useState } from 'react';
import { usePathname } from 'next/navigation';
import { ReasonTooltip } from '@opensamguk/ui';
import DeptNav, { resolveDeptHref } from './DeptNav';
import { MOBILE_TABS, evaluateMobileTab, type ControlGating, type GatingState } from '@/lib/dept-menu-config';
import type { MenuFlagSource, MenuNode } from '@/lib/menu-types';
import { normalizeGamePathname, useServerId } from '@/lib/serverGameUrl';

export interface BottomNavProps {
    gating?: ControlGating | null;
    gatingState?: GatingState;
    global?: MenuFlagSource;
    menu?: MenuNode[];
}

export default function BottomNav({ gating = null, gatingState = gating ? 'ready' : 'loading', global = {}, menu }: BottomNavProps) {
    const pathname = usePathname();
    const serverId = useServerId();
    const normalizedPathname = normalizeGamePathname(pathname ?? '', serverId);
    const [moreOpen, setMoreOpen] = useState(false);
    const moreButtonRef = useRef<HTMLButtonElement>(null);
    const sheetRef = useRef<HTMLDivElement>(null);

    // 시트: 열리면 첫 포커스 가능 요소로, Tab 은 시트 안에서 순환(공유 Modal 과 같은 규칙), Escape 로 닫고 더보기 버튼으로 복귀.
    useEffect(() => {
        if (!moreOpen) return;
        const focusables = () =>
            Array.from(sheetRef.current?.querySelectorAll<HTMLElement>('button:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])') ?? []);
        focusables()[0]?.focus();
        const onKey = (e: KeyboardEvent) => {
            if (e.key === 'Escape') {
                setMoreOpen(false);
                moreButtonRef.current?.focus();
                return;
            }
            if (e.key !== 'Tab') return;
            const items = focusables();
            if (items.length === 0) {
                e.preventDefault();
                sheetRef.current?.focus();
                return;
            }
            const first = items[0];
            const last = items[items.length - 1];
            const active = document.activeElement;
            const inside = sheetRef.current?.contains(active) ?? false;
            if (e.shiftKey && (active === first || !inside)) {
                e.preventDefault();
                last.focus();
            } else if (!e.shiftKey && (active === last || !inside)) {
                e.preventDefault();
                first.focus();
            }
        };
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
    }, [moreOpen]);

    return (
        <>
            <nav className="game-bottom-nav" aria-label="Mobile">
                {MOBILE_TABS.map((tab) => {
                    if (tab.key === 'more') {
                        return (
                            <button
                                key={tab.key}
                                ref={moreButtonRef}
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
                    const view = evaluateMobileTab(tab, gating, global, gatingState);
                    const target = tab.href.split('#')[0];
                    const active = tab.key === 'commands' ? false : normalizedPathname === target;
                    if (!view.enabled) {
                        return (
                            <ReasonTooltip key={tab.key} reason={view.reason ?? '사용 불가'} className="game-bottom-item game-bottom-item--disabled">
                                <span role="link" aria-disabled="true" tabIndex={0} className="game-bottom-label">{tab.label}</span>
                            </ReasonTooltip>
                        );
                    }
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
                <div className="dept-sheet" role="dialog" aria-modal="true" aria-label="부서 메뉴" id="dept-more" ref={sheetRef} tabIndex={-1}>
                    <button type="button" className="dept-sheet__scrim" aria-label="닫기" onClick={() => setMoreOpen(false)} />
                    <div className="dept-sheet__panel">
                        <DeptNav gating={gating} gatingState={gatingState} global={global} menu={menu} vertical onNavigate={() => setMoreOpen(false)} />
                    </div>
                </div>
            )}
        </>
    );
}
