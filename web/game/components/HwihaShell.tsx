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
import styles from './HwihaShell.module.css';

export interface HwihaShellIdentity {
    /** 장수 이름과 소속 — 시안 헤더의 첫 칩. */
    readonly generalName: string;
    readonly allegiance: string;
    /** 명망. 아직 값이 없으면 null — 시안처럼 `[미정]` 으로 보인다. */
    readonly renown: number | null;
    /** 「200년 3월 중순」 같은 게임 날짜 문구. */
    readonly gameDate: string;
}

export interface HwihaShellProps {
    readonly title: string;
    /** 켜진 입력 탭. 탭에 속하지 않는 화면은 null. */
    readonly tab: HwihaInputTab | null;
    readonly identity: HwihaShellIdentity;
    /** 작전실 자신에서는 「← 작전실」을 숨긴다. */
    readonly showBack?: boolean;
    readonly children: React.ReactNode;
}

/**
 * 새 시대(휘하) 화면의 공용 셸.
 *
 * 시안 `ui.py` 의 `head(title, on)` 을 옮긴 것이다 — 높이 56, 왼쪽에 「← 작전실」·제목·입력 여섯
 * 탭, 오른쪽에 장수·명망·날짜 칩. 시안의 탭은 정적 `<span>` 이지만 여기서는 진짜 링크로 만든다.
 * 그 탭에 아직 화면이 없으면 숨기지 않고 점선으로 남긴다(표시 원칙).
 */
export default function HwihaShell({ title, tab, identity, showBack = true, children }: HwihaShellProps) {
    return (
        <>
            <div className={styles.head}>
                <div className={styles.left}>
                    {showBack ? (
                        <Link className="os-button os-button--ghost os-button--sm" href={hwihaHref(HWIHA_HUB_SLUG)}>
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
                                    href={hwihaHref(landing.slug)}
                                    aria-current={on ? 'page' : undefined}
                                >
                                    {t}
                                </Link>
                            );
                        })}
                    </nav>
                </div>
                <div className={styles.right}>
                    <Chip>{`${identity.generalName} · ${identity.allegiance}`}</Chip>
                    <Chip tone="bronze">{`명망 ${identity.renown ?? '[미정]'}`}</Chip>
                    <Chip>{identity.gameDate}</Chip>
                </div>
            </div>
            <div className={styles.body}>{children}</div>
        </>
    );
}
