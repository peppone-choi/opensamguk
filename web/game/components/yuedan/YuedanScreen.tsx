'use client';

import { useState } from 'react';
import { Seg, StatusView } from '@opensamguk/ui';
import { campaignReadNotice } from '@/components/campaign/GameStates';
import { api } from '@/lib/api';
import { useCampaignRead } from '@/lib/campaign-reads';
import { useGameSession } from '@/lib/campaign-session';
import { useIsMobile } from '@/lib/use-viewport';
import { departureRows, stampLabel } from '@/lib/yuedan-view';
import { DepartureOrder, MyRenown, Ranking, RenownPaths } from './YuedanParts';
import styles from './yuedan.module.css';

/**
 * 월단평 화면 본문(P-R04) — 순위(공개) · 경로 / 내 명망 · 이탈 판정 순서(내 장수만). 조회: 월단평 · 부(이탈 순서) 한 번씩.
 * 첫 월단평 전(NOT_ASSESSED)은 빈 상태, 발표했는데 순위 0 은 따로 한 줄. 도장이 형식 밖이면 「이번 달」.
 */
export function YuedanScreen({ retinueHref }: { readonly retinueHref: string }) {
    const { generalId } = useGameSession();
    const mobile = useIsMobile();
    const [reload, setReload] = useState(0);
    const [view, setView] = useState<'rank' | 'departure'>('rank');
    const yuedan = useCampaignRead((id, signal) => api.campaignYuedan(id, signal), [reload]);
    const retinue = useCampaignRead((id, signal) => api.campaignRetinue(id, signal));

    if (mobile === null || (yuedan.loading && !yuedan.data)) return <StatusView kind="loading" rows={6} />;
    if (yuedan.error) return <StatusView kind="error" title="월단평을 불러오지 못했습니다" errorCode={yuedan.error.split(':')[0]} onRetry={() => setReload((n) => n + 1)} />;
    const data = yuedan.data;
    if (data?.status === 'NOT_ASSESSED') {
        return <StatusView kind="empty" title="아직 첫 월단평이 없습니다" body="월단평은 매월 상순에 발표합니다. 발표되면 여기에 순위가 보입니다." />;
    }
    const notice = campaignReadNotice(yuedan, data?.status);
    if (notice || !data) return <StatusView kind="waiting" title={notice ?? '월단평을 받지 못했습니다'} />;

    const title = `${stampLabel(data.stamp) ?? '이번 달'} 월단평`;
    const self = data.self;
    const deps = departureRows(retinue.data);
    const ranking = data.ranking.length === 0
        ? <p className={styles.muted} role="status">이번 달 순위에 오른 장수가 없습니다.</p>
        : <Ranking ranking={data.ranking} meId={generalId} mobile={mobile} />;
    const me = self ? <MyRenown self={self} pending={data.selfPendingEvents} compact={mobile} /> : null;
    const departure = <DepartureOrder rows={deps} overCapacity={self?.overCapacity ?? false} retinueHref={retinueHref} />;

    if (mobile) {
        return (
            <div className={styles.screenMobile}>
                <h1 className={styles.title}>{title}</h1>
                {me}
                <Seg label="보기" value={view} onChange={setView} options={[{ value: 'rank', label: '순위' }, { value: 'departure', label: '이탈 순서' }]} />
                {view === 'rank' ? ranking : departure}
                <p className={styles.muted}>매달 발표 · 순위는 공개, 사유는 본인만</p>
            </div>
        );
    }
    return (
        <div className={styles.screen}>
            <section className={`os-panel ${styles.left}`} aria-label={title}>
                <h1 className={styles.title}>{title}</h1>
                {ranking}
                <RenownPaths />
            </section>
            <div className={styles.right}>
                {me ? <section className="os-panel" aria-label="내 명망">{me}</section> : null}
                <section className="os-panel" aria-label="이탈 판정 순서">{departure}</section>
            </div>
        </div>
    );
}
