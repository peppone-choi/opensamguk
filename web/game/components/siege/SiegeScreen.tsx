'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { StatusView, useViewportClass } from '@opensamguk/ui';
import { campaignReadNotice } from '@/components/campaign/GameStates';
import { api } from '@/lib/api';
import { useCampaignRead } from '@/lib/campaign-reads';
import { availabilityOf } from '@/lib/input-availability';
import { fortRows, siegeRows } from '@/lib/siege-view';
import { FortOrders, SiegeDetail, SiegeList, SiegeOrders, fortKey, siegeKey } from './SiegeParts';
import styles from './siege.module.css';

/** canAct=false 의 뜻(옛 화면 문구 그대로) — 서버가 사유를 따로 주지 않는다. */
const NOT_COMMANDER = '포위 지휘관만 행동을 예약할 수 있습니다.';

export interface SiegeScreenProps {
    readonly hrefs: {
        /** 명령 흐름을 그 입력 · 대상으로(`?do=<inputId>&target=<kind>:<id>`, 순은 흐름에서 고른다). */
        readonly flow: (inputId: string, target: string) => string;
        readonly corps: string;
        readonly stratagem?: string;
    };
}

/**
 * 공성 화면 본문(P-C02) — 포위 목록 360 / 고른 포위 / 명령 360. 모바일: 목록 → 누르면 상세(뒤로) + 명령.
 * 강공 · 항복 권고 · 보루 포위는 명령 흐름에서 순을 고른다(K6 Q9). 가능 여부: 원장 + 포위 지휘관 여부(canAct), 나머지는 서버가 제출 때.
 */
export function SiegeScreen({ hrefs }: SiegeScreenProps) {
    const router = useRouter();
    const viewport = useViewportClass();
    // 구조가 다른 것은 모바일뿐 — 태블릿은 데스크톱 구조에 CSS 로 줄인다. 재기 전(null)은 뼈대.
    const mobile = viewport === null ? null : viewport === 'mobile';
    const [reload, setReload] = useState(0);
    const [selected, setSelected] = useState<string | null>(null);
    const sieges = useCampaignRead((id, s) => api.campaignSieges(id, s), [reload]);
    const roads = useCampaignRead((id, s) => api.roadForts(id, s), [reload]);

    if (mobile === null || (sieges.loading && !sieges.data)) return <StatusView kind="loading" rows={4} />;
    if (sieges.error) return <StatusView kind="error" title="포위를 불러오지 못했습니다" errorCode={sieges.error.split(':')[0]} onRetry={() => setReload((n) => n + 1)} />;
    const notice = campaignReadNotice(sieges, sieges.data?.status);
    if (notice || !sieges.data) return <StatusView kind="waiting" title={notice ?? '포위를 받지 못했습니다'} />;

    const rows = siegeRows(sieges.data.sieges);
    const forts = roads.data && roads.data.status === 'READY' ? fortRows(roads.data.forts) : [];
    const pickedKey = selected ?? (mobile ? null : rows[0] ? siegeKey(rows[0].countyId) : forts[0] ? fortKey(forts[0].id) : null);
    const siege = rows.find((r) => siegeKey(r.countyId) === pickedKey) ?? null;
    const fort = forts.find((f) => fortKey(f.id) === pickedKey) ?? null;
    const actor = (canAct: boolean, inputId: string) => availabilityOf(inputId, { options: canAct ? null : { available: false, reason: NOT_COMMANDER } });

    const list = <SiegeList sieges={rows} forts={forts} selected={pickedKey} onSelect={setSelected} corpsHref={hrefs.corps} />;
    const detail = siege ? <SiegeDetail row={siege} /> : fort ? <h3 className={styles.fortTitle}>도로 보루</h3> : null;
    const orders = siege ? (
        <SiegeOrders row={siege} stratagemHref={hrefs.stratagem}
            assault={actor(siege.canAct, 'action.assault')} surrender={actor(siege.canAct, 'action.demandSurrender')}
            onAssault={() => router.push(hrefs.flow('action.assault', `siege:${siege.countyId}`))}
            onSurrender={() => router.push(hrefs.flow('action.demandSurrender', `siege:${siege.countyId}`))} />
    ) : fort ? (
        <FortOrders fort={fort} availability={availabilityOf('action.siegeRoadFort', { options: { available: fort.canBesiege } })}
            onBesiege={() => router.push(hrefs.flow('action.siegeRoadFort', `fort:${fort.id}`))} />
    ) : null;

    if (mobile) {
        if (!pickedKey || (!siege && !fort)) {
            return <div className={styles.screenMobile}><h1 className={styles.srOnly}>공성</h1>{list}</div>;
        }
        return (
            <div className={styles.screenMobile}>
                <h1 className={styles.srOnly}>공성</h1>
                <button type="button" className={`os-button os-button--ghost ${styles.back}`} onClick={() => setSelected(null)}>← 포위 목록</button>
                {detail}
                {orders}
            </div>
        );
    }
    return (
        <div className={styles.screen}>
            <h1 className={styles.srOnly}>공성</h1>
            <section className={`os-panel ${styles.colList}`} aria-label="포위 중인 성">{list}</section>
            <section className={`os-panel ${styles.colDetail}`} aria-label="고른 포위">{detail}</section>
            <section className={`os-panel ${styles.colOrders}`} aria-label="공성 명령">{orders}</section>
        </div>
    );
}
