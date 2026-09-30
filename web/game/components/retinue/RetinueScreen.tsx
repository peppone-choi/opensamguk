'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useMemo, useState } from 'react';
import { InputAction, Modal, Seg, StatusView, matchesKoreanName } from '@opensamguk/ui';
import { campaignReadNotice } from '@/components/campaign/GameStates';
import { PlacementSheet } from '@/components/territory/PlacementParts';
import { api, isIntakeDenied, isIntakeQueued } from '@/lib/api';
import { useCampaignRead } from '@/lib/campaign-reads';
import { useGameSession } from '@/lib/campaign-session';
import { availabilityOf } from '@/lib/input-availability';
import {
    buName,
    filterRetinue,
    renownBand,
    retinueRows,
    sortRetinue,
    unitRows,
    type RetinueFilter,
    type RetinueRow,
    type RetinueSort,
} from '@/lib/retinue-view';
import { useIsMobile } from '@/lib/use-viewport';
import { BondPanel } from './BondPanel';
import { PersonDetail } from './PersonDetail';
import { RenownBand } from './RenownBand';
import { RetinueList } from './RetinueList';
import { UnitCards } from './UnitCards';
import styles from './retinue.module.css';

export interface RetinueScreenHrefs {
    readonly yuedan: string;
    /** 조정 발령(사람 미리 채움). */
    readonly dispatch: (generalId: number) => string;
    /** 인물 상세(P-R03) — generalId 가 없는 무명 카드는 null. */
    readonly person: (generalId: number) => string;
    /** 명령 흐름을 그 입력으로 연다(`?do=<inputId>`, K6). */
    readonly flow: (inputId: string) => string;
}

type MobileView = 'people' | 'units' | 'bonds';

/**
 * 부 편성 화면 본문(P-R01) — 셸(K3)의 머리 · 레일 안에 들어간다. 조회 셋(부 · 배치 · 월단평)을 한 번씩만 부른다.
 * 데스크톱: 명망 띠 + 세 칸(인물 목록 440 · 상세 · 오른쪽 360), 모바일: 한 줄 띠 + 「인물 · 부대 · 결속」 + 아래 고정 단추 줄.
 * 배치는 이 화면 안 시트(PlacementSheet)로 — 제출 결과는 한 줄 알림(접수 · 거절 사유 그대로).
 */
export function RetinueScreen({ hrefs }: { readonly hrefs: RetinueScreenHrefs }) {
    const { frontInfo, generalId } = useGameSession();
    const router = useRouter();
    const mobile = useIsMobile();
    const [reload, setReload] = useState(0);
    const retinue = useCampaignRead((id, signal) => api.campaignRetinue(id, signal), [reload]);
    const posts = useCampaignRead((id, signal) => api.campaignPosts(id, signal), [reload]);
    const yuedan = useCampaignRead((id, signal) => api.campaignYuedan(id, signal));

    const [sort, setSort] = useState<RetinueSort>('registered');
    const [filter, setFilter] = useState<RetinueFilter>('all');
    const [query, setQuery] = useState('');
    const [selected, setSelected] = useState<number | null>(null);
    const [view, setView] = useState<MobileView>('people');
    const [placing, setPlacing] = useState<number | null>(null);
    const [busy, setBusy] = useState(false);
    const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null);

    const all = useMemo(() => (retinue.data ? retinueRows(retinue.data, posts.data) : []), [retinue.data, posts.data]);
    const rows = useMemo(() => sortRetinue(filterRetinue(all, filter, query, matchesKoreanName), sort), [all, filter, query, sort]);
    const current = rows.find((r) => r.retainerId === selected) ?? rows[0] ?? null;
    const units = retinue.data ? unitRows(retinue.data) : [];
    const band = renownBand(retinue.data, yuedan.data);
    const lord = frontInfo?.general.name ?? '';
    const search = availabilityOf('action.search');
    const employ = availabilityOf('action.employ');
    const assignOf = (r: RetinueRow) => availabilityOf('placement.assign', {
        options: r.post.placeable == null ? null : { available: r.post.placeable, code: r.post.blocked?.code, reason: r.post.blocked?.reason },
    });

    const serverNotice = campaignReadNotice(retinue, retinue.data?.status);
    const placingCard = posts.data?.cards.find((c) => c.cardId === placing) ?? null;

    const submitPlacement = async (body: Readonly<Record<string, unknown>>) => {
        if (generalId == null) return;
        setBusy(true);
        try {
            const out = await api.campaignDomestic(generalId, 'placement', body);
            if (isIntakeQueued(out)) {
                setNotice({ tone: 'ok', text: '배치를 접수했습니다 — 카드의 다음 턴부터 부임합니다.' });
                setPlacing(null);
                setReload((n) => n + 1);
            } else if (isIntakeDenied(out)) {
                setNotice({ tone: 'error', text: out.reason?.trim() || '배치를 받지 못했습니다.' });
            }
        } catch (e) {
            setNotice({ tone: 'error', text: e instanceof Error ? '배치를 보내지 못했습니다 — 다시 해 보세요.' : '배치를 보내지 못했습니다.' });
        } finally {
            setBusy(false);
        }
    };

    if (mobile === null || (retinue.loading && !retinue.data)) return <StatusView kind="loading" rows={3} />;
    if (retinue.error) {
        return <StatusView kind="error" title="부를 불러오지 못했습니다" errorCode={retinue.error.split(':')[0]} onRetry={() => setReload((n) => n + 1)} />;
    }
    if (serverNotice) return <StatusView kind="waiting" title={serverNotice} />;

    const heading = <h1 className={styles.srOnly}>{buName(lord)}</h1>;
    const noticeLine = notice ? <p className={notice.tone === 'ok' ? styles.okLine : styles.errLine} role="status">{notice.text}</p> : null;
    const findButtons = (
        <>
            <InputAction inputId="action.search" availability={search} label="인재탐색" variant="ghost" onAct={() => router.push(hrefs.flow('action.search'))} />
            <InputAction inputId="action.employ" availability={employ} label="등용 — 명령 목록에 넣기" onAct={() => router.push(hrefs.flow('action.employ'))} />
        </>
    );
    const sheet = placingCard && posts.data ? (
        <Modal ariaLabel={`${placingCard.name} 배치`} onClose={() => setPlacing(null)} overlayClassName={mobile ? styles.sheetBottom : styles.sheetRight}>
            <PlacementSheet card={placingCard} posts={posts.data} busy={busy} onSubmit={(b) => void submitPlacement(b)} onCancel={() => setPlacing(null)} />
        </Modal>
    ) : null;

    if (all.length === 0) {
        return (
            <div className={styles.screen}>
                {heading}
                <RenownBand band={band} people={0} units={units.length} yuedanHref={hrefs.yuedan} compact={mobile} />
                <StatusView kind="empty" title="아직 거느린 인물이 없습니다"
                    body="인재탐색으로 재야 인물을 찾고 등용하면 여기에 인물 카드가 생깁니다. 인재탐색과 등용은 명령 목록 12순에 넣는 직접 행동입니다 — 한 순에 하나."
                    actions={findButtons} />
            </div>
        );
    }

    const searchProps = { query, onQueryChange: setQuery, filter, onFilterChange: setFilter, total: all.length };
    const detail = current ? (
        <PersonDetail row={current} assign={assignOf(current)} onAssign={() => setPlacing(current.retainerId)}
            dispatchHref={current.generalId != null ? hrefs.dispatch(current.generalId) : undefined}
            detailLink={current.generalId != null ? <Link href={hrefs.person(current.generalId)} className="os-button os-button--block">인물 상세</Link> : null} />
    ) : null;

    if (mobile) {
        return (
            <div className={styles.screenMobile}>
                {heading}
                <RenownBand band={band} people={all.length} units={units.length} compact />
                {noticeLine}
                <Seg label="보기" value={view} onChange={setView}
                    options={[{ value: 'people', label: '인물', count: all.length }, { value: 'units', label: '부대', count: units.length }, { value: 'bonds', label: '결속' }]} />
                <div className={styles.mobileBody}>
                    {view === 'people' ? (
                        <RetinueList rows={rows} sort={sort} onSortChange={setSort} mobile search={searchProps}
                            onSelect={(r) => { if (r.generalId != null) router.push(hrefs.person(r.generalId)); else setSelected(r.retainerId); }} />
                    ) : view === 'units' ? <UnitCards units={units} /> : <BondPanel rows={all} lordName={lord} />}
                </div>
                <div className={styles.footBar}>{findButtons}</div>
                {selected != null && current && current.generalId == null && !placingCard ? (
                    <Modal ariaLabel={`${current.name} 인물 카드`} onClose={() => setSelected(null)} overlayClassName={styles.sheetBottom}>{detail}</Modal>
                ) : null}
                {sheet}
            </div>
        );
    }

    return (
        <div className={styles.screen}>
            {heading}
            <RenownBand band={band} people={all.length} units={units.length} yuedanHref={hrefs.yuedan} />
            {noticeLine}
            <div className={styles.columns}>
                <section className={`os-panel ${styles.colList}`} aria-label="인물 카드">
                    <RetinueList rows={rows} sort={sort} onSortChange={setSort} selectedId={current?.retainerId ?? null}
                        onSelect={(r) => setSelected(r.retainerId)} search={searchProps} />
                    <div className={styles.listFoot}>{findButtons}</div>
                </section>
                <section className={`os-panel ${styles.colDetail}`} aria-label="고른 인물">{detail}</section>
                <div className={styles.colRight}>
                    <section className="os-panel" aria-label="부대 카드">
                        {units.length === 0 ? <p className={styles.muted}>편성한 부대가 없습니다.</p> : <UnitCards units={units} />}
                    </section>
                    <section className="os-panel" aria-label="결속"><BondPanel rows={all} lordName={lord} /></section>
                </div>
            </div>
            {sheet}
        </div>
    );
}
