'use client';

import Link from 'next/link';
import { useState } from 'react';
import { Modal, Seg, StatusView } from '@opensamguk/ui';
import { campaignReadNotice } from '@/components/campaign/GameStates';
import { api, isIntakeDenied, isIntakeQueued } from '@/lib/api';
import { CAMPAIGN_RESOURCE_LABELS, useCampaignRead, type CountyWorks, type Read } from '@/lib/campaign-reads';
import { useGameSession } from '@/lib/campaign-session';
import { availabilityOf } from '@/lib/input-availability';
import { connectedTotal, stockLine, warehouseRows } from '@/lib/supply-view';
import { FORTIFICATION, placementRows, type PolicyRow, type WorkRow } from '@/lib/territory-view';
import { useIsMobile } from '@/lib/use-viewport';
import { PlacementList, PlacementSheet } from './PlacementParts';
import { PolicyPanel, PolicySheet } from './PolicyParts';
import { WorkSheet, WorksPanel, type WorkExtra } from './WorkParts';
import styles from './territory.module.css';

type Kind = 'placement' | 'policy' | 'work';
type Sheet = { readonly kind: 'placement'; readonly cardId: number } | { readonly kind: 'policy'; readonly row: PolicyRow } | { readonly kind: 'work'; readonly countyId: number };

const OK_TEXT: Readonly<Record<Kind, string>> = {
    placement: '배치를 접수했습니다 — 카드의 다음 턴부터 부임합니다.',
    policy: '방침을 접수했습니다 — 다음 턴부터 적용합니다.',
    work: '공사를 접수했습니다 — 다음 순 경계부터 진척합니다.',
};

/** 도로 · 보루(도로 모드)는 지도에서 접경 · 길목 칸을 골라야 한다 — 지도 고르기(K2) 전까지 사유로 막는다. */
const MAP_PICK_WAITING = '지도에서 접경 · 길목을 고르는 칸이 곧 들어옵니다.';

export interface TerritoryScreenProps {
    readonly hrefs: { readonly supply: string; readonly court: string };
    /** 도로 · 보루 인자 고르기(지도, K2) — 없으면 그 공사는 사유로 막힌다. */
    readonly extraFor?: (county: CountyWorks, work: string) => WorkExtra | null;
}

function panelState<T extends { status: string }>(read: Read<T>, title: string, retry: () => void) {
    if (read.loading && !read.data) return <StatusView kind="loading" rows={4} />;
    if (read.error) return <StatusView kind="error" title={title} errorCode={read.error.split(':')[0]} onRetry={retry} />;
    const notice = campaignReadNotice(read, read.data?.status);
    if (notice) return <StatusView kind="waiting" title={notice} />;
    // 장수가 없어 부르지 않았다 — 셸이 입구로 보낸다. 값을 짓지 않고 뼈대만.
    return read.data ? null : <StatusView kind="loading" rows={4} />;
}

/**
 * 영지 첫 화면 본문(P-T01) — 머리 띠(본망 자원 합 · 창고망 →) + 세 칸(배치 · 방침 · 공사) / 모바일 세그먼트.
 * 칸마다 따로 읽고 따로 실패한다(한 칸 실패가 다른 칸을 가리지 않는다). 시트는 화면 안 Modal, 제출 결과는 한 줄 알림.
 */
export function TerritoryScreen({ hrefs, extraFor }: TerritoryScreenProps) {
    const { generalId } = useGameSession();
    const mobile = useIsMobile();
    const [reload, setReload] = useState(0);
    const again = () => setReload((n) => n + 1);
    const posts = useCampaignRead((id, s) => api.campaignPosts(id, s), [reload]);
    const policies = useCampaignRead((id, s) => api.campaignPolicies(id, s), [reload]);
    const works = useCampaignRead((id, s) => api.campaignWorks(id, s), [reload]);
    const warehouses = useCampaignRead((id, s) => api.warehouses(id, s));
    const roads = useCampaignRead((id, s) => api.roadForts(id, s));
    const retinue = useCampaignRead((id, s) => api.campaignRetinue(id, s));
    const [tab, setTab] = useState<Kind>('placement');
    const [sheet, setSheet] = useState<Sheet | null>(null);
    const [busy, setBusy] = useState(false);
    const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null);

    const submit = async (kind: Kind, body: Readonly<Record<string, unknown>>) => {
        if (generalId == null) return;
        setBusy(true);
        try {
            const out = await api.campaignDomestic(generalId, kind, body);
            if (isIntakeQueued(out)) {
                setNotice({ tone: 'ok', text: OK_TEXT[kind] });
                setSheet(null);
                again();
            } else if (isIntakeDenied(out)) {
                setNotice({ tone: 'error', text: out.reason?.trim() || '접수하지 못했습니다.' });
            }
        } catch {
            setNotice({ tone: 'error', text: '보내지 못했습니다 — 다시 해 보세요.' });
        } finally {
            setBusy(false);
        }
    };

    if (mobile === null) return <StatusView kind="loading" rows={4} />;

    const faces = new Map((retinue.data?.people ?? []).map((p) => [p.retainerId, { picture: p.picture, imageServer: p.imageServer }]));
    const roadMode = roads.data?.roadMode === true;
    const pickExtra = (county: CountyWorks) => (work: string): WorkExtra | null => {
        const needsMap = roadMode && (work === 'ROAD' || work === FORTIFICATION);
        if (!needsMap) return null;
        return extraFor?.(county, work) ?? { node: <p className={styles.muted}>{MAP_PICK_WAITING}</p>, body: null, missing: MAP_PICK_WAITING };
    };

    const whRows = warehouses.data?.status === 'READY' ? warehouseRows(warehouses.data) : [];
    const total = connectedTotal(whRows);
    const band = (
        <div className={styles.band}>
            <span className={styles.muted}>쓸 수 있는 창고망 합</span>
            <span className="os-mono">{total ? stockLine(total) : CAMPAIGN_RESOURCE_LABELS.map((r) => `${r.label} —`).join(' · ')}</span>
            <Link href={hrefs.supply} className={styles.bandLink}>창고망 · 보급 →</Link>
        </div>
    );
    const noticeLine = notice ? <p className={notice.tone === 'ok' ? styles.okLine : styles.errLine} role="status">{notice.text}</p> : null;

    const placement = panelState(posts, '배치를 불러오지 못했습니다', again) ?? (
        <PlacementList rows={placementRows(posts.data!)} courtHref={hrefs.court} portraitOf={(id) => faces.get(id) ?? null}
            availabilityOf={(r) => availabilityOf('placement.assign', { options: { available: r.placeable, code: r.blocked?.code, reason: r.blocked?.reason } })}
            onChange={(r) => setSheet({ kind: 'placement', cardId: r.cardId })} />
    );
    const policy = panelState(policies, '방침을 불러오지 못했습니다', again) ?? (
        <PolicyPanel policies={policies.data!} onChange={(row) => setSheet({ kind: 'policy', row })}
            availabilityOf={(r) => availabilityOf('policy.set', { options: { available: r.settable, code: r.blocked?.code, reason: r.blocked?.reason } })} />
    );
    const work = panelState(works, '공사를 불러오지 못했습니다', again) ?? (
        <WorksPanel works={works.data!} startAvailabilityOf={() => availabilityOf('work.start')} reduceAvailability={availabilityOf('work.reduce')}
            onNewWork={(r: WorkRow) => setSheet({ kind: 'work', countyId: r.countyId })} onReduce={() => {}} />
    );

    const card = sheet?.kind === 'placement' ? posts.data?.cards.find((c) => c.cardId === sheet.cardId) ?? null : null;
    const county = sheet?.kind === 'work' ? works.data?.counties.find((c) => c.countyId === sheet.countyId) ?? null : null;
    const sheetBody = sheet?.kind === 'placement' && card && posts.data
        ? <PlacementSheet card={card} posts={posts.data} busy={busy} onSubmit={(b) => void submit('placement', b)} onCancel={() => setSheet(null)} />
        : sheet?.kind === 'policy' && policies.data
            ? <PolicySheet policies={policies.data} row={sheet.row} busy={busy} onSubmit={(b) => void submit('policy', b)} onCancel={() => setSheet(null)} />
            : sheet?.kind === 'work' && county
                ? <WorkSheet county={county} busy={busy} extraFor={pickExtra(county)} onSubmit={(b) => void submit('work', b)} onCancel={() => setSheet(null)} />
                : null;
    const modal = sheetBody ? (
        <Modal ariaLabel="영지 입력" onClose={() => setSheet(null)} overlayClassName={mobile ? styles.sheetBottom : styles.sheetRight}>{sheetBody}</Modal>
    ) : null;

    if (mobile) {
        return (
            <div className={styles.screenMobile}>
                <h1 className={styles.srOnly}>배치 · 방침 · 공사</h1>
                {band}
                {noticeLine}
                <Seg label="보기" value={tab} onChange={setTab}
                    options={[{ value: 'placement', label: '배치' }, { value: 'policy', label: '방침' }, { value: 'work', label: '공사' }]} />
                {tab === 'placement' ? placement : tab === 'policy' ? policy : work}
                {modal}
            </div>
        );
    }
    return (
        <div className={styles.screen}>
            <h1 className={styles.srOnly}>배치 · 방침 · 공사</h1>
            {band}
            {noticeLine}
            <div className={styles.columns}>
                <section className={`os-panel ${styles.colPlacement}`} aria-label="배치">{placement}</section>
                <section className={`os-panel ${styles.colPolicy}`} aria-label="방침">{policy}</section>
                <section className={`os-panel ${styles.colWorks}`} aria-label="공사">{work}</section>
            </div>
            {modal}
        </div>
    );
}
