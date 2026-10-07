'use client';

import Link from 'next/link';
import { useCallback, useState } from 'react';
import { Modal, Seg, StatusView, useProvinceName, useViewportClass } from '@opensamguk/ui';
import { campaignReadNotice } from '@/components/campaign/GameStates';
import { api, isIntakeDenied, isIntakeQueued } from '@/lib/api';
import { CAMPAIGN_RESOURCE_LABELS, useCampaignRead, type CountyWorks, type Read } from '@/lib/campaign-reads';
import { useGameSession } from '@/lib/campaign-session';
import { availabilityOf } from '@/lib/input-availability';
import { connectedTotal, stockLine, warehouseRows } from '@/lib/supply-view';
import { candidateBody, fortCandidates, roadCandidates } from '@/lib/road-candidates';
import { FORTIFICATION, placementRows, type PolicyRow, type WorkRow } from '@/lib/territory-view';
import { PlacementList, PlacementSheet } from './PlacementParts';
import { PolicyPanel, PolicySheet } from './PolicyParts';
import { RoadPicker } from './RoadPicker';
import { WorkSheet, WorksPanel, type WorkExtra } from './WorkParts';
import styles from './territory.module.css';

type Kind = 'placement' | 'policy' | 'work';
export type TerritoryView = Kind;

/** 주소 `?view=` 값 → 처음 펼칠 칸(배치 · 방침 · 공사). 모르는 값은 null(기본 배치). 도움말 첫걸음 바로가기가 쓴다. */
export function territoryView(raw: string | null | undefined): TerritoryView | null {
    return raw === 'placement' || raw === 'policy' || raw === 'work' ? raw : null;
}

/** 현 상세 배치 링크의 현 id. 양의 정수 표기와 안전한 정수 범위만 받는다. */
export function territoryPlacementCounty(raw: string | null | undefined): number | null {
    if (!raw || !/^[1-9]\d*$/.test(raw)) return null;
    const id = Number(raw);
    return Number.isSafeInteger(id) ? id : null;
}
type Sheet = { readonly kind: 'placement'; readonly cardId: number } | { readonly kind: 'policy'; readonly row: PolicyRow } | { readonly kind: 'work'; readonly countyId: number };

const OK_TEXT: Readonly<Record<Kind, string>> = {
    placement: '배치를 접수했습니다 — 카드의 다음 턴부터 부임합니다.',
    policy: '방침을 접수했습니다 — 다음 턴부터 적용합니다.',
    work: '공사를 접수했습니다 — 다음 순 경계부터 진척합니다.',
};


export interface TerritoryScreenProps {
    readonly hrefs: { readonly supply: string; readonly court: string };
    /** 처음 펼칠 칸(`?view=`) — 모바일은 그 세그먼트를 연다. 데스크톱은 세 칸이 다 보여 바꿀 것이 없다. */
    readonly initialView?: TerritoryView | null;
    /** 현 상세에서 고른 현 — 서버가 허용하는 현령 후보일 때만 시트에 미리 채운다. */
    readonly initialCountyId?: number | null;
    /** 도로 · 보루 인자 고르기를 지도(K2)로 바꿀 때 — 없으면 K3 후보 목록(RoadPicker)으로 고른다. */
    readonly extraFor?: (county: CountyWorks, work: string) => WorkExtra | null;
    /** 구역 한글 이름 — 넘기지 않으면 공용 useProvinceName(지도 캐시). 못 풀면 「이름 모를 구역」. */
    readonly provinceName?: (provinceId: string) => string | null;
}

function panelState<T extends { status: string }>(read: Read<T>, title: string, retry: () => void) {
    if (read.loading && !read.data) return <StatusView kind="loading" rows={4} />;
    if (read.error) return <StatusView kind="error" title={title} errorCode={read.errorCode ?? undefined} onRetry={retry} />;
    const notice = campaignReadNotice(read, read.data?.status);
    if (notice) return <StatusView kind="waiting" title={notice} />;
    // 장수가 없어 부르지 않았다 — 셸이 입구로 보낸다. 값을 짓지 않고 뼈대만.
    return read.data ? null : <StatusView kind="loading" rows={4} />;
}

/**
 * 영지 첫 화면 본문(P-T01) — 머리 띠(본망 자원 합 · 창고망 →) + 세 칸(배치 · 방침 · 공사) / 모바일 세그먼트.
 * 칸마다 따로 읽고 따로 실패한다(한 칸 실패가 다른 칸을 가리지 않는다). 시트는 화면 안 Modal, 제출 결과는 한 줄 알림.
 */
export function TerritoryScreen({ hrefs, extraFor, provinceName, initialView = null, initialCountyId = null }: TerritoryScreenProps) {
    // 구역 한글 이름 — 지도 훅이 이미 받은 지형에서만(K1 #1106). 없으면 undefined → 「이름 모를 구역」. 정식은 K4-21.
    const cachedName = useProvinceName();
    const { generalId } = useGameSession();
    const viewport = useViewportClass();
    // 구조가 다른 것은 모바일뿐 — 태블릿은 데스크톱 구조에 CSS 로 줄인다. 재기 전(null)은 뼈대.
    const mobile = viewport === null ? null : viewport === 'mobile';
    const [reload, setReload] = useState(0);
    const again = () => setReload((n) => n + 1);
    const posts = useCampaignRead((id, s) => api.campaignPosts(id, s), [reload]);
    const policies = useCampaignRead((id, s) => api.campaignPolicies(id, s), [reload]);
    const works = useCampaignRead((id, s) => api.campaignWorks(id, s), [reload]);
    const warehouses = useCampaignRead((id, s) => api.warehouses(id, s), [reload]);
    const roads = useCampaignRead((id, s) => api.roadForts(id, s), [reload]);
    const retinue = useCampaignRead((id, s) => api.campaignRetinue(id, s), [reload]);
    const [tab, setTab] = useState<Kind>(initialView ?? 'placement');
    const [sheet, setSheet] = useState<Sheet | null>(null);
    const [busy, setBusy] = useState(false);
    const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null);
    const [roadPick, setRoadPick] = useState<string | null>(null);
    const onRoadPick = useCallback((id: string | null) => setRoadPick(id), []);

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
    const name = provinceName ?? ((id: string) => cachedName(id) ?? null);
    const pickExtra = (county: CountyWorks) => (work: string): WorkExtra | null => {
        const road = work === 'ROAD';
        if (!road && work !== FORTIFICATION) return null;
        // Wait for the road mode and target data before allowing submission.
        if (roads.loading && !roads.data) {
            const why = '도로 · 보루 자리를 불러오는 중입니다.';
            return { node: <p role="status">{why}</p>, body: null, missing: why };
        }
        if (roads.error || (roads.data && roads.data.status !== 'READY')) {
            const why = '도로 · 보루 자리를 불러오지 못했습니다 — 다시 시도해 주세요.';
            return { node: <p className={styles.errLine}>{why}</p>, body: null, missing: why };
        }
        if (!roadMode || !roads.data) return null;
        const custom = extraFor?.(county, work);
        if (custom) return custom;
        const candidates = road ? roadCandidates(roads.data, county, name) : fortCandidates(roads.data, county, name);
        const picked = roadPick?.startsWith(road ? 'road|' : 'fort|') ? roadPick : null;
        return {
            node: <RoadPicker key={work} label={road ? '도로를 낼 접경' : '보루를 지을 길목'} candidates={candidates} onPick={onRoadPick} onCancel={() => setSheet(null)} />,
            body: candidateBody(picked),
            missing: road ? '도로를 낼 접경을 고르세요.' : '보루를 지을 길목을 고르세요.',
        };
    };

    const whRows = warehouses.data?.status === 'READY' ? warehouseRows(warehouses.data) : [];
    const total = connectedTotal(whRows);
    // 창고망을 못 읽었거나 서버 상태(옛 형식 월드 등)면 「금 — · 쌀 —」 같은 빈 값이 아니라 그 상태를 쓴다.
    const whNotice = warehouses.loading && !warehouses.data ? '불러오는 중입니다.'
        : warehouses.error ? '창고망을 불러오지 못했습니다.'
        : campaignReadNotice({ loading: false, error: null }, warehouses.data?.status);
    const band = (
        <div className={styles.band}>
            <span className={styles.muted}>쓸 수 있는 창고망 합</span>
            {whNotice ? <span className={styles.muted} role="status">{whNotice}</span>
                : <span className="os-mono">{total ? stockLine(total) : CAMPAIGN_RESOURCE_LABELS.map((r) => `${r.label} —`).join(' · ')}</span>}
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
    const placementCounty = initialCountyId != null && posts.data?.status === 'READY'
        ? posts.data.posts.find((p) => p.post === 'MAGISTRATE' && p.available)?.targets
            ?.find((t) => t.countyId === initialCountyId && !t.occupied)
        : null;
    const sheetBody = sheet?.kind === 'placement' && card && posts.data
        ? <PlacementSheet card={card} posts={posts.data} busy={busy} onSubmit={(b) => void submit('placement', b)} onCancel={() => setSheet(null)}
            initialPost={initialCountyId != null ? 'MAGISTRATE' : undefined} initialTarget={placementCounty ? String(placementCounty.countyId) : undefined} />
        : sheet?.kind === 'policy' && policies.data
            ? <PolicySheet policies={policies.data} row={sheet.row} busy={busy} onSubmit={(b) => void submit('policy', b)} onCancel={() => setSheet(null)} />
            : sheet?.kind === 'work' && county
                ? <WorkSheet county={county} busy={busy} extraFor={pickExtra(county)} onSubmit={(b) => void submit('work', b)} onCancel={() => setSheet(null)} />
                : null;
    const sheetLabel = sheet?.kind === 'placement' && card ? `${card.name} 배치` : sheet?.kind === 'policy' ? '방침 바꾸기'
        : sheet?.kind === 'work' && county ? `${county.name} 공사` : '영지 입력';
    const modal = sheetBody ? (
        <Modal ariaLabel={sheetLabel} onClose={() => setSheet(null)} overlayClassName={mobile ? styles.sheetBottom : styles.sheetRight}>{sheetBody}</Modal>
    ) : null;

    if (mobile) {
        return (
            <div className={styles.screenMobile}>
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
