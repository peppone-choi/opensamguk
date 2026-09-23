'use client';

import Link from 'next/link';
import { Chip } from '@opensamguk/ui';
import {
    HWIHA_INPUT_TABS,
    HWIHA_HUB_SLUG,
    hwihaHref,
    hwihaTabLanding,
    type HwihaInputTab,
} from '../lib/hwiha-screens';
import { HwihaBlocked, hwihaBlockReason } from './hwiha/HwihaStates';
import { useHwihaRenown } from '../lib/hwiha-reads';
import { useHwihaSession } from '../lib/hwiha-session';
import styles from './HwihaShell.module.css';

export interface HwihaShellProps {
    readonly title: string;
    /** 켜진 입력 탭. 탭에 속하지 않는 화면은 null. */
    readonly tab: HwihaInputTab | null;
    /** 작전실 자신에서는 「← 작전실」을 숨긴다. */
    readonly showBack?: boolean;
    /**
     * 휘하 규칙 월드에서만 뜻이 있는 화면인지. 참이면 규칙이 다른 월드·장수 없음에서 본문 대신
     * 사유를 보인다. 작전실은 지도만으로도 쓸모가 있어 거짓이다.
     */
    readonly requiresHwiha?: boolean;
    readonly children: React.ReactNode;
}

/**
 * 새 시대(휘하) 화면의 공용 셸.
 *
 * 시안 `ui.py` 의 `head(title, on)` 을 옮긴 것이다 — 높이 56, 왼쪽에 「← 작전실」·제목·입력 여섯
 * 탭, 오른쪽에 장수·명망·날짜 칩. 시안의 탭은 정적 `<span>` 이지만 여기서는 진짜 링크로 만든다.
 * 그 탭에 아직 화면이 없으면 숨기지 않고 점선으로 남긴다(표시 원칙).
 */
export default function HwihaShell({ title, tab, showBack = true, requiresHwiha = true, children }: HwihaShellProps) {
    const session = useHwihaSession();
    const { frontInfo, serverId } = session;
    const renown = useHwihaRenown();
    const generalName = frontInfo?.general.name ?? null;
    const allegiance = frontInfo?.nation?.name ?? '재야';
    const blocked = hwihaBlockReason(session);
    return (
        <>
            <div className={styles.head}>
                <div className={styles.left}>
                    {showBack ? (
                        <Link className="os-button os-button--ghost os-button--sm" href={hwihaHref(HWIHA_HUB_SLUG, serverId)}>
                            ← 작전실
                        </Link>
                    ) : null}
                    <span className={styles.title}>{title}</span>
                    <nav className={styles.tabs} aria-label="입력 여섯 가지">
                        {HWIHA_INPUT_TABS.map((t) => {
                            const landing = hwihaTabLanding(t);
                            const on = t === tab;
                            if (!landing) {
                                return (
                                    <span
                                        key={t}
                                        className={`${styles.tab} ${styles.tabEmpty}`}
                                        title="아직 화면이 없습니다"
                                    >
                                        {t}
                                    </span>
                                );
                            }
                            return (
                                <Link
                                    key={t}
                                    className={`${styles.tab}${on ? ` ${styles.tabOn}` : ''}`}
                                    href={hwihaHref(landing.slug, serverId)}
                                    aria-current={on ? 'page' : undefined}
                                >
                                    {t}
                                </Link>
                            );
                        })}
                    </nav>
                </div>
                <div className={styles.right}>
                    {generalName ? <Chip>{`${generalName} · ${allegiance}`}</Chip> : null}
                    {session.isHwihaWorld ? (
                        <Chip tone="bronze">{`명망 ${renown ?? '—'}`}</Chip>
                    ) : null}
                    {session.gameDate ? <Chip>{session.gameDate}</Chip> : null}
                </div>
            </div>
            <div className={styles.body}>
                {requiresHwiha && blocked ? <HwihaBlocked reason={blocked} /> : children}
            </div>
        </>
    );
}
