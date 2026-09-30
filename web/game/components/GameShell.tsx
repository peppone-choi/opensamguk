'use client';

import { usePathname, useSearchParams } from 'next/navigation';
import { useEffect, useRef, type ReactNode } from 'react';
import { ReasonTooltip } from '@opensamguk/ui';
import CampaignLink from './campaign/CampaignLink';
import { Blocked, campaignBlockReason } from './campaign/GameStates';
import { useGameSession } from '../lib/campaign-session';
import { locateScreen, screenHref, type NavScreen } from '../lib/nav31';
import { normalizeGamePathname } from '../lib/serverGameUrl';
import styles from './GameShell.module.css';

export interface GameShellProps {
    readonly title: string;
    /** @deprecated 옛 「입력 여섯 가지」 탭. 새 셸은 하위 탭을 경로(NAV31)로 찾는다 — 화면 PR 이 들어오면서 지운다. */
    readonly tab?: unknown;
    /** @deprecated 「← 작전실」은 이제 레일 · 하단 탭이 한다. */
    readonly showBack?: boolean;
    /** 장수가 있어야 뜻이 있는 화면인지. 참이면 불러오는 중 · 실패 · 장수 없음에서 본문 대신 사유를 보인다. */
    readonly requiresHwiha?: boolean;
    readonly children: ReactNode;
}

const NOT_READY = '아직 준비 중인 화면입니다';

/**
 * 페이지 머리(보드 V31SystemPage · MPage) — 제목과, 지금 묶음의 하위 화면 탭. 머리줄 · 레일 · 하단 탭은 /game 레이아웃의
 * GameFrame 이 그린다. 하위 화면이 아직 없으면 숨기지 않고 점선으로 두고 누르면 사유가 열린다(표시 원칙).
 * 모바일은 탭 한 줄을 가로로 밀고, 고른 탭이 보이게 밀어 둔다.
 */
export default function GameShell({ title, requiresHwiha = true, children }: GameShellProps) {
    const session = useGameSession();
    const pathname = usePathname() ?? '';
    const search = useSearchParams();
    const rest = normalizeGamePathname(pathname, session.serverId).replace(/^\/game\/?/, '');
    const located = locateScreen(rest, search?.toString() ?? '');
    const screens = located?.group.screens ?? [];
    const blocked = campaignBlockReason(session);
    const current = useRef<HTMLAnchorElement | null>(null);

    useEffect(() => {
        current.current?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' });
    }, [located?.screen?.label]);

    return (
        <>
            <div className={styles.head}>
                <h2 className={styles.title}>{title}</h2>
                {screens.length > 1 ? (
                    <nav className={styles.tabs} aria-label="하위 화면">
                        {screens.map((screen) => (
                            <SubTab key={screen.label} screen={screen} on={located?.screen === screen} anchor={located?.screen === screen ? current : undefined} />
                        ))}
                    </nav>
                ) : null}
            </div>
            <div className={styles.body}>
                {requiresHwiha && blocked ? <Blocked reason={blocked} /> : children}
            </div>
        </>
    );
}

function SubTab({ screen, on, anchor }: { readonly screen: NavScreen; readonly on: boolean; readonly anchor?: React.RefObject<HTMLAnchorElement | null> }) {
    const href = screenHref(screen);
    if (href === null) {
        return (
            <ReasonTooltip reason={NOT_READY}>
                <button type="button" className={`${styles.tab} ${styles.tabEmpty}`} aria-disabled="true" aria-haspopup="dialog">{screen.label}</button>
            </ReasonTooltip>
        );
    }
    return (
        <CampaignLink ref={anchor} slug={href} className={`${styles.tab}${on ? ` ${styles.tabOn}` : ''}`} aria-current={on ? 'page' : undefined}>
            {screen.label}
        </CampaignLink>
    );
}
