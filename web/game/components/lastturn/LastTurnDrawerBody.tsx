'use client';

import Link from 'next/link';
import { useMemo, useState } from 'react';
import { StatusView, type RecordSection } from '@opensamguk/ui';
import { campaignReadNotice } from '@/components/campaign/GameStates';
import { IncomingRequests } from '@/components/requests/IncomingRequests';
import { api } from '@/lib/api';
import { useCampaignRead, type GamePhase } from '@/lib/campaign-reads';
import { useGameSession } from '@/lib/campaign-session';
import { lastTurnGroups, rangeText, type LastTurnTab } from '@/lib/last-turn-view';
import { requestKey, useRequests } from '@/lib/requests';
import { LastTurnFilters, LastTurnList } from './LastTurnParts';
import styles from './lastturn.module.css';

export interface LastTurnDrawerBodyProps {
    readonly hrefs: {
        readonly yuedan: string;
        readonly county: (countyId: number) => string;
        readonly records: string;
        /** 조정(P-K01) — 펼친 요청 카드 아래 「조정에서 모두 보기」. */
        readonly court: string;
    };
    readonly onClose?: () => void;
}

/**
 * 지난 순 서랍 본문(P-W04) — 머리(범위) · 탭 · 분류 거르기 · 12순 목록 · 기록 전체 보기. 틀(데스크톱 지도 위 360 겹침 · 모바일 하단 시트)은
 * 작전실이 준다. 「응답하기」는 같은 요청 카드(K6)를 그 항목 아래에서 펼친다 — 응답은 순을 쓰지 않으니 자리를 떠나지 않는다.
 * 응답 대기 표시는 받은 요청 읽기(useRequests)에서 아직 답하지 않은 발령만.
 */
export function LastTurnDrawerBody({ hrefs, onClose }: LastTurnDrawerBodyProps) {
    const { generalId, frontInfo } = useGameSession();
    const [reload, setReload] = useState(0);
    const [tab, setTab] = useState<LastTurnTab>('mine');
    const [section, setSection] = useState<RecordSection | null>(null);
    const [replyOpen, setReplyOpen] = useState<string | null>(null);
    const read = useCampaignRead((id, s) => api.campaignLastTurns(id, s), [reload]);
    const requests = useRequests(generalId);
    const now: GamePhase | null = frontInfo ? { year: frontInfo.global.year, month: frontInfo.global.month, phase: frontInfo.global.turnPhase ?? 1 } : null;
    const pendingIds = useMemo(() => new Set(requests.load.state === 'ready'
        ? requests.load.requests.filter((r) => r.state === 'waiting' && 'dispatchId' in r.ref).map((r) => (r.ref as { dispatchId: string }).dispatchId)
        : []), [requests.load]);

    let body;
    if (read.loading && !read.data) body = <StatusView kind="loading" rows={5} />;
    else if (read.error) body = <StatusView kind="error" title="지난 순을 불러오지 못했습니다" errorCode={read.error.split(':')[0]} onRetry={() => setReload((n) => n + 1)} />;
    else if (campaignReadNotice(read, read.data?.status) || !read.data || !now) body = <StatusView kind="waiting" title={campaignReadNotice(read, read.data?.status) ?? '지난 순을 받지 못했습니다'} />;
    else {
        body = (
            <LastTurnList groups={lastTurnGroups(read.data, now, tab, section, pendingIds)} recordsHref={hrefs.records} yuedanHref={hrefs.yuedan} countyHref={hrefs.county}
                onReply={(id) => setReplyOpen((cur) => (cur === id ? null : id))} replyOpen={replyOpen}
                replySlot={(id) => (
                    <>
                        <IncomingRequests generalId={generalId} source={requests} onlyKey={requestKey({ kind: 'dispatch', dispatchId: id })} compact />
                        <Link className={styles.courtLink} href={hrefs.court}>조정에서 모두 보기 →</Link>
                    </>
                )} />
        );
    }

    return (
        <section className={styles.drawer} aria-label="지난 순" id="last-turn-drawer">
            <header className={styles.drawerHead}>
                <h2 className={styles.drawerTitle}>지난 순</h2>
                {now ? <span className={`os-mono ${styles.range}`}>{rangeText(now)}</span> : null}
                {onClose ? <button type="button" className={styles.close} aria-label="지난 순 닫기" onClick={onClose}>×</button> : null}
            </header>
            <LastTurnFilters tab={tab} onTab={setTab} section={section} onSection={setSection} />
            {body}
        </section>
    );
}
