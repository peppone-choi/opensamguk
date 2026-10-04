'use client';

// 머리줄 서신 서랍(P-Q02, 보드 V31K6MailDrawer) — 셸(GameFrame)이 주소에 `?mail=<탭>`이 있으면 `<main>` 뒤 서랍 자리(aside)에 그린다.
// 머리 「서신」 · 받은 요청 수 · 「전체 화면」(/game/mail) · 닫기, 그 아래 서신 화면의 서랍 판(목록 + 짧은 서신).
// 서신 화면과 같은 읽기 · 쓰기(lib/mail · /api/mailbox/recent · sendMessage)를 쓴다. 안 읽음 수는 서버 값(K3-02)이 없어 그리지 않는다.
// 탭은 주소 값과 같다(personal · nation · all · requests). 탭을 바꾸면 주소를 replace로 맞추고, 닫기 · 기기 뒤로 가기는 `?mail=`를 뺀다.
import Link from 'next/link';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useCallback, useMemo, type KeyboardEvent } from 'react';
import { StatusView } from '@opensamguk/ui';
import CampaignLink from '@/components/campaign/CampaignLink';
import { ShellIcon } from '@/components/shell/ShellIcon';
import { campaignHref } from '@/lib/campaign-screens';
import { useGameSession } from '@/lib/campaign-session';
import { useRequests } from '@/lib/requests';
import { MailScreen, type MailTab } from './MailScreen';
import styles from './MailDrawer.module.css';

/** 주소 값 → 서신 탭. 모르는 값이면 개인. */
const TAB_OF_KEY: Readonly<Record<string, MailTab>> = { personal: 'private', nation: 'national', all: 'public', requests: 'requests' };
const KEY_OF_TAB: Readonly<Record<MailTab, string>> = { private: 'personal', national: 'nation', public: 'all', diplomacy: 'personal', requests: 'requests' };

export function mailTabOf(view: string | null): MailTab {
    return (view && TAB_OF_KEY[view]) || 'private';
}

export function mailViewOf(tab: MailTab): string {
    return KEY_OF_TAB[tab];
}

export default function MailDrawer({ view, closeHref }: {
    /** 주소의 `?mail=` 값. */
    readonly view: string;
    /** `?mail=`를 뺀 같은 화면 주소(경로 포함). */
    readonly closeHref: string;
}) {
    const router = useRouter();
    const pathname = usePathname() ?? '';
    const search = useSearchParams();
    const session = useGameSession();
    const general = session.frontInfo?.general ?? null;
    const requests = useRequests(session.generalId);
    // 서신 화면 · 조정 발령 탭 주소 — 서버가 든 주소(CampaignLink와 같은 campaignHref).
    const links = useMemo(() => ({ mail: campaignHref('mail', session.serverId), court: `${campaignHref('court', session.serverId)}?tab=orders` }), [session.serverId]);

    const onTabChange = useCallback((tab: MailTab) => {
        const query = new URLSearchParams(search?.toString() ?? '');
        query.set('mail', mailViewOf(tab));
        router.replace(`${pathname}?${query.toString()}`, { scroll: false });
    }, [pathname, router, search]);

    // 서랍 안에서 Esc — 닫는다. 조합 중이거나 안쪽(사유 시트)이 먼저 받았으면(preventDefault) 둔다.
    // 확인 대화(ConfirmDialog → Modal)는 Esc 를 window 리스너로 받고 포털 없이 이 안에 그려져 이 onKeyDown 이 먼저 받는다 —
    // 대화 안에서 난 Esc 는 대화 몫이라 그냥 둔다(Esc 한 번에 한 겹, #1277 리뷰). 닫을 때는 받았다고 표시해 명령 흐름 Esc가 겹쳐 닫지 않게 한다.
    const onKeyDown = useCallback((event: KeyboardEvent<HTMLDivElement>) => {
        if (event.key !== 'Escape' || event.nativeEvent.isComposing || event.defaultPrevented) return;
        if (event.target instanceof Element && event.target.closest('[role="dialog"]')) return;
        event.preventDefault();
        router.push(closeHref, { scroll: false });
    }, [closeHref, router]);

    return (
        <div className={styles.drawer} onKeyDown={onKeyDown} data-testid="mail-drawer">
            <div className={styles.head}>
                <h2 className={styles.title}>서신</h2>
                {requests.waiting > 0 ? <span className="os-chip os-chip--bronze">{`요청 ${requests.waiting}`}</span> : null}
                <CampaignLink slug="mail" className={`os-button os-button--ghost ${styles.full}`}>전체 화면</CampaignLink>
                <Link href={closeHref} scroll={false} className={styles.close} aria-label="서랍 닫기">
                    <ShellIcon name="close" />
                </Link>
            </div>
            {session.generalId != null && general ? (
                <MailScreen
                    me={{ generalId: session.generalId, nationId: general.nationId }}
                    variant="header"
                    initialTab={mailTabOf(view)}
                    requests={requests}
                    onTabChange={onTabChange}
                    links={links}
                />
            ) : (
                <StatusView kind="loading" rows={4} />
            )}
        </div>
    );
}
