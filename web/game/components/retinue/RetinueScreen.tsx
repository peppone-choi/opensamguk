'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useMemo, useState } from 'react';
import { InputAction, matchesKoreanName, Modal, Seg, StatusView, useViewportClass } from '@opensamguk/ui';
import { campaignReadNotice } from '@/components/campaign/GameStates';
import { PlacementSheet } from '@/components/territory/PlacementParts';
import { api, isIntakeDenied, isIntakeQueued } from '@/lib/api';
import { useCampaignRead } from '@/lib/campaign-reads';
import { useGameSession } from '@/lib/campaign-session';
import { availabilityOf } from '@/lib/input-availability';
import {
    filterRetinue,
    renownBand,
    retinueRows,
    sortRetinue,
    unitRows,
    type RetinueFilter,
    type RetinueRow,
    type RetinueSort,
} from '@/lib/retinue-view';
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
    /** 인물 상세(P-R03). 없으면(그 화면 전) 장수 카드도 이 화면 안 카드로 연다. */
    readonly person?: (generalId: number) => string;
    /** 명령 흐름을 그 입력으로 연다(`?do=<inputId>`, K6). */
    readonly flow: (inputId: string) => string;
}

type MobileView = 'people' | 'units' | 'bonds';

/**
 * 부 편성 화면 본문(P-R01) — 셸(K3)의 머리 · 레일 안에 들어간다. 조회 셋(부 · 배치 · 월단평)을 한 번씩만 부른다.
 * 데스크톱: 명망 띠 + 세 칸(인물 목록 440 · 상세 · 오른쪽 360), 모바일: 한 줄 띠 + 「인물 · 부대 · 결속」 + 아래 고정 단추 줄.
 * 배치는 이 화면 안 시트(PlacementSheet)로 — 제출 결과는 한 줄 알림(접수 · 거절 사유 그대로).
 */
export function RetinueScreen({ hrefs, initialPerson = null }: {
    readonly hrefs: RetinueScreenHrefs;
    /** 처음 고를 인물(retainerId) — 작전실 장수 목록의 `?person=` 링크. */
    readonly initialPerson?: number | null;
}) {
    const { frontInfo, generalId } = useGameSession();
    const router = useRouter();
    const viewport = useViewportClass();
    // 구조가 다른 것은 모바일뿐 — 태블릿은 데스크톱 구조에 CSS 로 줄인다. 재기 전(null)은 뼈대.
    const mobile = viewport === null ? null : viewport === 'mobile';
    const [reload, setReload] = useState(0);
    const retinue = useCampaignRead((id, signal) => api.campaignRetinue(id, signal), [reload]);
    const posts = useCampaignRead((id, signal) => api.campaignPosts(id, signal), [reload]);
    const yuedan = useCampaignRead((id, signal) => api.campaignYuedan(id, signal));

    const [sort, setSort] = useState<RetinueSort>('registered');
    const [filter, setFilter] = useState<RetinueFilter>('all');
    const [query, setQuery] = useState('');
    const [selected, setSelected] = useState<number | null>(initialPerson);
    const [view, setView] = useState<MobileView>('people');
    const [placing, setPlacing] = useState<number | null>(null);
    const [busy, setBusy] = useState(false);
    const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null);

    const all = useMemo(() => (retinue.data ? retinueRows(retinue.data, posts.data) : []), [retinue.data, posts.data]);
    const rows = useMemo(() => sortRetinue(filterRetinue(all, filter, query, matchesKoreanName), sort), [all, filter, query, sort]);
    const picked = rows.find((r) => r.retainerId === selected) ?? null;
    const current = picked ?? rows[0] ?? null;
    const units = retinue.data ? unitRows(retinue.data) : [];
    const band = renownBand(retinue.data, yuedan.data);
    const lord = frontInfo?.general.name ?? '';
    const search = availabilityOf('action.search');
    const employ = availabilityOf('action.employ');
    // 배치 자리(/api/posts)를 읽는 중 · 못 읽었으면 「자리에 배치」를 가능으로 그리지 않는다 — 시트는 자리 목록이 있어야 열린다.
    const postsNotice = posts.error ? '배치 자리를 불러오지 못했습니다.' : campaignReadNotice({ loading: false, error: null }, posts.data?.status);
    const assignOf = (r: RetinueRow) => availabilityOf('placement.assign', {
        options: postsNotice ? { available: false, reason: postsNotice }
            : r.post.placeable == null ? null : { available: r.post.placeable, code: r.post.blocked?.code, reason: r.post.blocked?.reason },
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
                // 모바일은 인물 카드 시트에서 배치를 열었다 — 다시 열리면 접수 한 줄을 가린다.
                if (mobile) setSelected(null);
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
        return <StatusView kind="error" title="부를 불러오지 못했습니다" body={retinue.error} errorCode={retinue.errorCode ?? undefined} onRetry={() => setReload((n) => n + 1)} />;
    }
    if (serverNotice) return <StatusView kind="waiting" title={serverNotice} />;

    const noticeLine = (
        <>
            {notice ? <p className={notice.tone === 'ok' ? styles.okLine : styles.errLine} role="status">{notice.text}</p> : null}
            {posts.error ? (
                <p className={styles.errLine}>
                    배치 자리를 불러오지 못했습니다 — 「자리에 배치」는 다시 읽은 뒤 쓸 수 있습니다.{' '}
                    <button type="button" className="os-button os-button--sm" onClick={() => setReload((n) => n + 1)}>다시 시도</button>
                </p>
            ) : null}
        </>
    );
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
                <RenownBand band={band} people={0} units={units.length} yuedanHref={hrefs.yuedan} compact={mobile} />
                <StatusView kind="empty" title="아직 거느린 인물이 없습니다"
                    body="인재탐색으로 재야 인물을 찾고 등용하면 여기에 인물 카드가 생깁니다. 인재탐색과 등용은 명령 목록 12순에 넣는 직접 행동입니다 — 한 순에 하나."
                    actions={findButtons} />
            </div>
        );
    }

    const searchProps = { query, onQueryChange: setQuery, filter, onFilterChange: setFilter, total: all.length };
    const detail = current ? (
        <PersonDetail row={current} assign={assignOf(current)} assignBusy={posts.loading && !posts.data} onAssign={() => setPlacing(current.retainerId)}
            dispatchHref={current.generalId != null ? hrefs.dispatch(current.generalId) : undefined}
            detailLink={current.generalId != null && hrefs.person ? <Link href={hrefs.person(current.generalId)} className="os-button os-button--block">인물 상세</Link> : null} />
    ) : null;

    if (mobile) {
        return (
            <div className={styles.screenMobile}>
                <RenownBand band={band} people={all.length} units={units.length} compact />
                {noticeLine}
                <Seg label="보기" value={view} onChange={setView}
                    options={[{ value: 'people', label: '인물', count: all.length }, { value: 'units', label: '부대', count: units.length }, { value: 'bonds', label: '결속' }]} />
                <div className={styles.mobileBody}>
                    {view === 'people' ? (
                        <RetinueList rows={rows} sort={sort} onSortChange={setSort} mobile search={searchProps}
                            onSelect={(r) => { if (r.generalId != null && hrefs.person) router.push(hrefs.person(r.generalId)); else setSelected(r.retainerId); }} />
                    ) : view === 'units' ? <UnitCards units={units} /> : <BondPanel rows={all} lordName={lord} />}
                </div>
                <div className={styles.footBar}>{findButtons}</div>
                {picked && (picked.generalId == null || !hrefs.person) && !placingCard ? (
                    <Modal ariaLabel={`${picked.name} 인물 카드`} onClose={() => setSelected(null)} overlayClassName={styles.sheetBottom}>
                        {/* 바깥 누르기만으로 닫히면 모바일에서 닫는 길이 안 보인다 — 머리에 닫기(44). */}
                        <div className={styles.sheetHead}>
                            <h3 className={styles.sheetTitle}>인물 카드</h3>
                            <button type="button" className="os-button os-button--sm" onClick={() => setSelected(null)}>닫기</button>
                        </div>
                        {detail}
                    </Modal>
                ) : null}
                {sheet}
            </div>
        );
    }

    return (
        <div className={styles.screen}>
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
