'use client';

// 역사 인물 고르기(P-E03) — 보드 V31K5Historical · MHistorical · MHistoricalSheet(D83 · D84 승인본), 요구 문서 §3.2.
// 계약(K5-03 · K5-01, 서버 #1137) 값만 그린다: 이름 · 초상 · 다섯 능력 · 소속 세력 id · 등장 · 고를 수 있음 · 사유 코드.
// 계약에 없는 것은 서버 대기다 — 역할 칩 · 역할 거르기 · 누구의 부 · 묶음 · 결속 · 본관 · 시작 위치 · 자리 한도 · 이름 · 능력 정렬.
// 세력 이름은 공개 지도 미리보기의 세력표로 붙인다(후보는 id 만 준다). 서버가 없으면(404 · 503) 「생성 대기」.

import { useEffect, useMemo, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Button, Chip, Flag, Modal, Panel, Portrait, ReasonTooltip, SectionHeader, Seg, StatusView, useViewportClass } from '@opensamguk/ui';
import CampaignLink from '@/components/campaign/CampaignLink';
import { useCreationRequest } from '@/hooks/useCreationRequest';
import { useHistoricalCandidates, useNationRefs } from '@/hooks/useHistoricalCandidates';
import { useGameSession } from '@/lib/campaign-session';
import { campaignHref } from '@/lib/campaign-screens';
import type { HistoricalCreationPerson, HistoricalStatus } from '@/lib/creation-contract';
import { affiliationText, cardView, STAT_LABELS, STATUS_FILTERS, type HistoricalCardView, type NationRef } from '@/lib/historical-view';
import { formatNumber } from '@/lib/format';
import CreationProgress from './CreationProgress';
import CreationWaiting from './CreationWaiting';
import styles from './creation.module.css';

const ROLE_WAIT = '역할(주공 · 중간직 · 소속 장수 · 예비 주공 · 재야)로 거르기는 서버가 아직 주지 않습니다.';
const SORT_WAIT = '지금은 등록순입니다. 이름 · 능력 순 정렬은 서버가 아직 주지 않습니다.';
const FIELDS_WAIT = '역할 · 누구의 부 · 본관은 서버가 아직 주지 않아 카드에 없습니다.';
const PLACE_WAIT = '들어갈 자리 · 함께 시작할 인물 · 거병 조건 · 시작 위치 · 자리 한도는 서버가 아직 주지 않습니다.';

type StatusKey = HistoricalStatus | 'ALL';

function Filters({ q, setQ, nation, setNation, status, setStatus, nations }: {
    readonly q: string; readonly setQ: (v: string) => void;
    readonly nation: number | null; readonly setNation: (v: number | null) => void;
    readonly status: StatusKey; readonly setStatus: (v: StatusKey) => void;
    readonly nations: ReadonlyMap<number, NationRef>;
}) {
    const nationOptions = useMemo(() => [
        { value: 'ALL', label: '전체' },
        { value: '0', label: '재야' },
        ...[...nations.values()].map((n) => ({ value: String(n.id), label: n.name })),
    ], [nations]);
    return (
        <div className={styles.filters}>
            <input type="search" className="os-input" aria-label="이름으로 찾기" placeholder="이름으로 찾기" value={q} onChange={(e) => setQ(e.target.value)} />
            <div className={styles.field}>
                <span className={styles.fieldLabel}>역할</span>
                <p className={styles.wait}>{ROLE_WAIT}</p>
            </div>
            <div className={styles.field}>
                <span className={styles.fieldLabel}>소속</span>
                <Seg label="소속" options={nationOptions} value={nation === null ? 'ALL' : String(nation)} onChange={(v) => setNation(v === 'ALL' ? null : Number(v))} scroll />
            </div>
            <div className={styles.field}>
                <span className={styles.fieldLabel}>상태</span>
                <Seg<StatusKey> label="상태" options={STATUS_FILTERS.map((s) => ({ value: s.value, label: s.label }))} value={status} onChange={setStatus} scroll />
            </div>
            <div className={styles.field}>
                <span className={styles.fieldLabel}>정렬</span>
                <p className={styles.wait}>{SORT_WAIT}</p>
            </div>
        </div>
    );
}

function Card({ view, selected, mobile, onPick }: { readonly view: HistoricalCardView; readonly selected: boolean; readonly mobile: boolean; readonly onPick: () => void }) {
    const body = (
        <>
            <Portrait picture={view.portrait} size={mobile ? 'card-64' : 'card-74'} alt={`${view.name} 초상`} />
            <span className={styles.cardText}>
                <span className={styles.cardName}>{view.name}</span>
                <span className={styles.cardSub}>
                    {view.affiliation.kind === 'nation' ? <Flag color={view.affiliation.nation.color} size={12} label={`${view.affiliation.nation.name} 깃발`} /> : null}
                    {affiliationText(view.affiliation)}
                </span>
                <span className={styles.cardStats}>{view.statLine}</span>
                <span><Chip tone={view.available ? 'moss' : 'neutral'}>{view.chip}</Chip></span>
            </span>
        </>
    );
    if (!view.available) {
        // 고를 수 없는 카드 — 누르면 사유가 열린다(보드 why_tag). 고르지는 않는다.
        return (
            <ReasonTooltip reason={view.reason ?? '지금 고를 수 없습니다.'}>
                <button type="button" role="option" aria-selected={false} aria-disabled="true" className={styles.card}>{body}</button>
            </ReasonTooltip>
        );
    }
    return (
        <button type="button" role="option" aria-selected={selected} className={`${styles.card}${selected ? ` ${styles.cardOn}` : ''}`} onClick={onPick}>{body}</button>
    );
}

function Detail({ person, view, onStart, mobile = false }: { readonly person: HistoricalCreationPerson; readonly view: HistoricalCardView; readonly onStart: () => void; readonly mobile?: boolean }) {
    return (
        <div className={styles.detail}>
            <p className={styles.help}>시나리오에 나온 인물 한 명을 골라 그 사람의 자리로 들어갑니다. 서버에 한 장뿐이라 먼저 고른 쪽이 가집니다.</p>
            <div className={styles.detailTop}>
                <Portrait picture={person.portrait} size={mobile ? 'card-64' : 'card'} alt={`${person.name} 초상`} />
                <div className={styles.detailStats}>
                    <span className={styles.chips}><Chip tone="bronze">유일</Chip></span>
                    <dl className={styles.statRows}>
                        {STAT_LABELS.map((s) => (
                            <div key={s.key} className={styles.statRow}><dt>{s.label}</dt><dd>{formatNumber(person.stats[s.key])}</dd></div>
                        ))}
                    </dl>
                </div>
            </div>
            <dl className={styles.kv}>
                <div><dt>소속</dt><dd>{affiliationText(view.affiliation)}</dd></div>
            </dl>
            <p className={styles.wait}>{PLACE_WAIT}</p>
            <p className={styles.muted}>능력은 역사 값 그대로입니다.</p>
            <div className={styles.startRow}>
                <Button variant="primary" block onClick={onStart} data-guide="tutorial.createGeneral">이 인물로 시작</Button>
            </div>
        </div>
    );
}

export default function HistoricalScreen() {
    const [q, setQ] = useState('');
    const [nation, setNation] = useState<number | null>(null);
    const [status, setStatus] = useState<StatusKey>('AVAILABLE');
    const query = useMemo(() => ({ q, nation, status: status === 'ALL' ? null : status }), [q, nation, status]);
    const { state, loadMore, reload } = useHistoricalCandidates(query);
    const nations = useNationRefs();
    const { phase, submit, reset } = useCreationRequest();
    const [selectedId, setSelectedId] = useState<number | null>(null);
    const [filtersOpen, setFiltersOpen] = useState(false);
    const mobile = useViewportClass() === 'mobile';
    const router = useRouter();
    const session = useGameSession();

    // 만들어지면 세션을 다시 읽고 입구(장수가 있으면 작전실)로 — 역할에 따른 자리는 서버 세션이 정한다.
    const created = phase.kind === 'created';
    const leave = useRef(() => {});
    leave.current = () => { session.refresh(); router.push(campaignHref('', session.serverId)); };
    useEffect(() => { if (created) leave.current(); }, [created]);

    const people = state.kind === 'ready' ? state.people : [];
    const views = useMemo(() => new Map(people.map((p) => [p.historicalGeneralId, cardView(p, nations)])), [people, nations]);
    const selected = selectedId === null ? null : people.find((p) => p.historicalGeneralId === selectedId) ?? null;
    const selectedView = selected ? views.get(selected.historicalGeneralId) ?? null : null;

    if (state.kind === 'waiting') return <CreationWaiting historical message={state.message} />;
    if (phase.kind !== 'idle') {
        return (
            <CreationProgress
                phase={phase}
                portrait={selected ? { picture: selected.portrait, name: selected.name } : null}
                next="그 인물의 자리로"
                retryLabel="다른 인물 고르기"
                onRetry={() => { reset(); setSelectedId(null); reload(); }}
            />
        );
    }

    const start = () => {
        if (!selected || state.kind !== 'ready') return;
        void submit(state.worldId, { kind: 'HISTORICAL', historicalGeneralId: selected.historicalGeneralId });
    };
    const filters = <Filters q={q} setQ={setQ} nation={nation} setNation={setNation} status={status} setStatus={setStatus} nations={nations} />;

    let list;
    if (state.kind === 'loading') list = <StatusView kind="loading" rows={4} />;
    else if (state.kind === 'error') {
        list = <StatusView kind="error" title="역사 인물 목록을 불러오지 못했습니다" body={state.error.message} onRetry={reload} />;
    } else if (people.length === 0) {
        list = <StatusView kind="empty" title="맞는 인물이 없습니다" body={q.trim() ? `「${q.trim()}」와 맞는 인물이 없습니다. 이름을 줄이거나 거르기를 바꿔 보세요.` : '거르기를 바꿔 보세요.'} />;
    } else {
        list = (
            <>
                <div role="listbox" aria-label="역사 인물" className={styles.grid}>
                    {people.map((p) => (
                        <Card key={p.historicalGeneralId} view={views.get(p.historicalGeneralId)!} selected={p.historicalGeneralId === selectedId} mobile={mobile}
                            onPick={() => setSelectedId(p.historicalGeneralId)} />
                    ))}
                </div>
                {state.kind === 'ready' && state.moreError ? <p className={styles.alert} role="alert">{state.moreError.message}</p> : null}
                {state.kind === 'ready' && state.nextCursor !== null ? (
                    state.loadingMore
                        ? <Button variant="ghost" block disabled reason="불러오는 중입니다">더 보기</Button>
                        : <Button variant="ghost" block onClick={loadMore}>더 보기</Button>
                ) : null}
            </>
        );
    }
    const countSub = state.kind === 'ready' ? `${formatNumber(people.length)}명${state.nextCursor !== null ? ' · 더 보기' : ''}` : undefined;

    if (mobile) {
        return (
            <div className={styles.mobile}>
                <div className={styles.mobileHead}>
                    <h2 className={styles.title}>역사 인물 고르기</h2>
                    <CampaignLink slug="" className="os-button os-button--ghost">입구로</CampaignLink>
                </div>
                <div className={styles.mobileSearch}>
                    <input type="search" className="os-input" aria-label="이름으로 찾기" placeholder="이름으로 찾기" value={q} onChange={(e) => setQ(e.target.value)} />
                    <Button variant="ghost" onClick={() => setFiltersOpen(true)}>거르기</Button>
                </div>
                <p className={styles.note} role="note">{countSub ? `${countSub} · ` : ''}{FIELDS_WAIT}</p>
                {list}
                {filtersOpen ? (
                    <Modal ariaLabel="거르기" onClose={() => setFiltersOpen(false)} overlayClassName={styles.sheetBottom}>
                        <div className={styles.sheetHead}>
                            <h3 className={styles.sheetTitle}>거르기</h3>
                            <button type="button" className="os-button os-button--sm" onClick={() => setFiltersOpen(false)}>닫기</button>
                        </div>
                        {filters}
                    </Modal>
                ) : null}
                {selected && selectedView ? (
                    <Modal ariaLabel={selected.name} onClose={() => setSelectedId(null)} overlayClassName={styles.sheetBottom}>
                        <div className={styles.sheetHead}>
                            <h3 className={styles.sheetTitle}>{selected.name}</h3>
                            <button type="button" className="os-button os-button--sm" onClick={() => setSelectedId(null)}>닫기</button>
                        </div>
                        <Detail person={selected} view={selectedView} onStart={start} mobile />
                    </Modal>
                ) : null}
            </div>
        );
    }

    return (
        <div className={styles.screen}>
            <h2 className="sr-only">역사 인물 고르기</h2>
            <Panel className={styles.filterPanel} aria-label="거르기">
                <SectionHeader title="거르기" />
                {filters}
                <div className={styles.back}><CampaignLink slug="" className="os-button os-button--ghost">입구로</CampaignLink></div>
            </Panel>
            <Panel className={styles.center} aria-label="등장한 인물">
                <SectionHeader title="등장한 인물" sub={countSub} />
                <p className={styles.note} role="note">{FIELDS_WAIT}</p>
                <div className={styles.listWrap}>{list}</div>
            </Panel>
            <Panel className={styles.detailPanel} aria-label="고른 인물">
                <SectionHeader title={selected ? selected.name : '고른 인물'} sub={selected ? '고름' : undefined} />
                {selected && selectedView
                    ? <Detail person={selected} view={selectedView} onStart={start} />
                    : <StatusView kind="empty" title="인물을 고르세요" body="가운데 목록에서 고를 수 있는 인물을 누르면 여기에 보입니다." />}
            </Panel>
        </div>
    );
}
