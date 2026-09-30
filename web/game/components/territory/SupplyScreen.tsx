'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { Seg, StatusView, useViewportClass } from '@opensamguk/ui';
import { campaignReadNotice } from '@/components/campaign/GameStates';
import { api } from '@/lib/api';
import { useCampaignRead } from '@/lib/campaign-reads';
import { useGameSession } from '@/lib/campaign-session';
import { availabilityOf } from '@/lib/input-availability';
import { connectedTotal, cutRows, warehouseRows } from '@/lib/supply-view';
import { CutPanel, TransportPanel, UpkeepWaiting, WarehouseTable } from './SupplyParts';
import styles from './territory.module.css';

export interface SupplyScreenProps {
    readonly hrefs: {
        /** 명령 흐름(물자조달 `?do=action.transport`). */
        readonly transport: string;
        /** 지도 보급선 층을 켜고 그 창고로(`?layer=supply&focus=<cityId>`). */
        readonly map: (cityId: number) => string;
    };
}

/**
 * 창고망 · 보급 화면 본문(P-T04) — 재고 표(합계 본망) / 오른쪽 400 끊긴 곳 · 녹봉 전망(서버 대기) · 물자조달.
 * 모바일: 본망 합계 카드 · 「창고 · 끊긴 곳 · 위험」 세그먼트 · 아래 물자조달. 재야는 선 현 창고 하나만 온다(서버).
 * UNSUPPORTED_WORLD_FORMAT 등은 「창고가 없습니다」 가 아니라 알림(옛 결함).
 */
export function SupplyScreen({ hrefs }: SupplyScreenProps) {
    const { frontInfo } = useGameSession();
    const router = useRouter();
    const viewport = useViewportClass();
    // 구조가 다른 것은 모바일뿐 — 태블릿은 데스크톱 구조에 CSS 로 줄인다. 재기 전(null)은 뼈대.
    const mobile = viewport === null ? null : viewport === 'mobile';
    const [reload, setReload] = useState(0);
    const [view, setView] = useState<'stock' | 'cut' | 'risk'>('stock');
    const read = useCampaignRead((id, s) => api.warehouses(id, s), [reload]);

    if (mobile === null || (read.loading && !read.data)) return <StatusView kind="loading" rows={5} />;
    if (read.error) return <StatusView kind="error" title="창고를 불러오지 못했습니다" errorCode={read.error.split(':')[0]} onRetry={() => setReload((n) => n + 1)} />;
    const notice = campaignReadNotice(read, read.data?.status);
    if (notice || !read.data) return <StatusView kind="waiting" title={notice ?? '창고를 받지 못했습니다'} />;

    const rows = warehouseRows(read.data);
    const total = connectedTotal(rows);
    const stateless = frontInfo?.nation == null;
    if (rows.length === 0) {
        return <StatusView kind="empty" title="창고가 있는 현이 없습니다"
            body={stateless ? '소속이 없으면 지금 선 현 창고만 보입니다.' : '창고가 생기면 여기에 재고가 보입니다.'} />;
    }
    const table = <WarehouseTable rows={rows} total={total} invalidCount={read.data.invalidCount} mobile={mobile} />;
    const cuts = <CutPanel rows={rows} mapHref={hrefs.map} />;
    const transport = <TransportPanel generalName={frontInfo?.general.name ?? '내 장수'} availability={availabilityOf('action.transport')} onTransport={() => router.push(hrefs.transport)} />;

    if (mobile) {
        const cutCount = cutRows(rows).length;
        return (
            <div className={styles.screenMobile}>
                <h1 className={styles.srOnly}>창고망 · 보급</h1>
                <Seg label="보기" value={view} onChange={setView}
                    options={[{ value: 'stock', label: '창고', count: rows.length }, { value: 'cut', label: '끊긴 곳', count: cutCount }, { value: 'risk', label: '위험' }]} />
                {view === 'stock' ? table : view === 'cut' ? cuts : <UpkeepWaiting />}
                {transport}
            </div>
        );
    }
    return (
        <div className={styles.screen}>
            <h1 className={styles.srOnly}>창고망 · 보급</h1>
            <div className={styles.columns}>
                <section className={`os-panel ${styles.colPolicy}`} aria-label="창고별 재고">{table}</section>
                <div className={`${styles.colWorks} ${styles.stack}`}>
                    <section className="os-panel" aria-label="끊긴 곳">{cuts}</section>
                    <section className="os-panel" aria-label="녹봉 · 부대 유지비"><UpkeepWaiting /></section>
                    <section className="os-panel" aria-label="물자조달">{transport}</section>
                </div>
            </div>
        </div>
    );
}
