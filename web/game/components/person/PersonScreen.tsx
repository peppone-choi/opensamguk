'use client';

import Link from 'next/link';
import { useMemo, useState } from 'react';
import { Chip, Modal, Portrait, StatusView, useViewportClass } from '@opensamguk/ui';
import { campaignReadNotice } from '@/components/campaign/GameStates';
import { HelpedInputAction } from '@/components/campaign/HelpedInputAction';
import { bondText } from '@/components/retinue/RetinueList';
import { AptitudeCells, StatCells } from '@/components/retinue/StatCells';
import { PlacementSheet } from '@/components/territory/PlacementParts';
import { api, isIntakeDenied, isIntakeQueued } from '@/lib/api';
import { useCampaignRead } from '@/lib/campaign-reads';
import { useGameSession } from '@/lib/campaign-session';
import { availabilityOf } from '@/lib/input-availability';
import { personView, stateCells, type PersonView } from '@/lib/person-view';
import { retinueRows } from '@/lib/retinue-view';
import styles from './person.module.css';

export interface PersonScreenHrefs {
    /** 인물 일람(P-R02). */
    readonly people: string;
    /** 부 편성(P-R01) — 그 인물을 연 채로(`?person=<retainerId>`). 인물 없이 부르면 부 편성 첫 화면. */
    readonly retinue: (retainerId?: number) => string;
    /** 조정 발령(사람 장수). */
    readonly dispatch: (generalId: number) => string;
}

export interface PersonScreenProps {
    /** 경로의 장수 id. null 이면 「이 인물을 찾을 수 없습니다」. */
    readonly generalId: number | null;
    readonly hrefs: PersonScreenHrefs;
}

function Cell({ title, sub, children }: { readonly title: string; readonly sub?: string; readonly children: React.ReactNode }) {
    return (
        <section className={`os-panel ${styles.cell}`} aria-label={title}>
            <div className={styles.cellHead}><h3 className={styles.cellTitle}>{title}</h3>{sub ? <span className={styles.muted}>{sub}</span> : null}</div>
            <div className={styles.cellBody}>{children}</div>
        </section>
    );
}

const Waiting = ({ title, body }: { readonly title: string; readonly body: string }) => <StatusView kind="waiting" title={title} body={body} />;

/**
 * 인물 상세(P-R03, 보드 V31K4Person · V31K4MPerson) — 왼쪽 360 히어로(초상 · 이름 · 칩 · 관계에 맞는 단추) / 오른쪽 2 × 3 칸
 * (능력 · 적성 / 결속 · 계책 기여 / 자리 · 상태 8칸 · 관직 카드). 모바일은 위에서 아래로.
 * 인물 상세 읽기(K4-13)가 오기 전이라 나 · 내 부 인물만 채운다. 다른 인물은 「서버 대기」, 계책 기여 · 관직 카드는 늘 서버 대기다.
 */
export function PersonScreen({ generalId, hrefs }: PersonScreenProps) {
    const viewport = useViewportClass();
    const mobile = viewport === 'mobile';
    const session = useGameSession();
    const { frontInfo } = session;
    const [attempt, setAttempt] = useState(0);
    const isSelf = generalId != null && frontInfo?.general.generalId === generalId;
    // 나는 front-info 로 그린다. 내 부 인물인지는 부 · 배치 읽기로 가른다(나일 때는 부르지 않는다).
    const retinue = useCampaignRead((id, signal) => (isSelf ? Promise.resolve(null) : api.campaignRetinue(id, signal)), [isSelf, attempt]);
    const posts = useCampaignRead((id, signal) => (isSelf ? Promise.resolve(null) : api.campaignPosts(id, signal)), [isSelf, attempt]);
    const rows = useMemo(
        () => (retinue.data?.status === 'READY' ? retinueRows(retinue.data, posts.data) : null),
        [retinue.data, posts.data],
    );
    // 배치 — 부 편성(P-R01)과 같은 시트 · 같은 접수(보드 V31K4MPerson 아래 「자리에 배치」). 접수 · 거절은 한 줄 알림.
    const [placing, setPlacing] = useState<number | null>(null);
    const [busy, setBusy] = useState(false);
    const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null);
    const submitPlacement = async (body: Readonly<Record<string, unknown>>) => {
        if (session.generalId == null) return;
        setBusy(true);
        try {
            const out = await api.campaignDomestic(session.generalId, 'placement', body);
            if (isIntakeQueued(out)) {
                setNotice({ tone: 'ok', text: '배치를 접수했습니다 — 카드의 다음 턴부터 부임합니다.' });
                setPlacing(null);
                setAttempt((n) => n + 1);
            } else if (isIntakeDenied(out)) {
                setNotice({ tone: 'error', text: out.reason?.trim() || '배치를 받지 못했습니다.' });
            }
        } catch (e) {
            setNotice({ tone: 'error', text: e instanceof Error ? '배치를 보내지 못했습니다 — 다시 해 보세요.' : '배치를 보내지 못했습니다.' });
        } finally {
            setBusy(false);
        }
    };
    const placingCard = posts.data?.status === 'READY' ? posts.data.cards.find((c) => c.cardId === placing) ?? null : null;

    if (generalId == null) {
        return <StatusView kind="empty" title="이 인물을 찾을 수 없습니다" body="주소의 인물 번호가 올바르지 않습니다. 인물 일람에서 고르세요."
            actions={<Link href={hrefs.people} className="os-button">인물 일람으로</Link>} />;
    }
    if (viewport === null || (session.loading && !frontInfo)) return <StatusView kind="loading" rows={6} />;
    if (!isSelf) {
        if (retinue.error) {
            return <StatusView kind="error" title="인물을 불러오지 못했습니다" errorCode={retinue.errorCode ?? undefined} onRetry={() => setAttempt((n) => n + 1)} />;
        }
        // 장수가 없는 세션은 부 읽기를 부르지 않아 data 가 끝내 null 이다 — 읽는 중일 때만 뼈대, 아니면 아래 「아직 볼 수 없습니다」로(#1265 리뷰).
        if (!retinue.data && retinue.loading) return <StatusView kind="loading" rows={6} />;
    }
    const view = personView(generalId, frontInfo ? { general: frontInfo.general, nation: frontInfo.nation } : null, rows);
    if (view.relation === 'UNKNOWN') {
        const notice = retinue.data && retinue.data.status !== 'READY' ? campaignReadNotice(retinue, retinue.data.status) : null;
        return (
            <StatusView kind="empty" title="이 인물의 상세는 아직 볼 수 없습니다"
                body={notice ?? '지금은 내 장수와 내 부 인물만 상세가 열립니다. 다른 인물의 능력 · 결속 · 자리는 서버가 인물 상세를 주면 보입니다.'}
                actions={<Link href={hrefs.people} className="os-button">인물 일람으로</Link>} />
        );
    }
    return (
        <>
            {notice ? <p className={notice.tone === 'ok' ? styles.okLine : styles.errLine} role="status">{notice.text}</p> : null}
            <PersonBody view={view} mobile={mobile} place={isSelf ? frontInfo?.city?.name ?? null : null}
                postsNotice={posts.error ? '배치 자리를 불러오지 못했습니다.' : campaignReadNotice({ loading: false, error: null }, posts.data?.status)}
                assignBusy={busy || (posts.loading && !posts.data)} hrefs={hrefs} onAssign={(retainerId) => { setNotice(null); setPlacing(retainerId); }} />
            {placingCard && posts.data ? (
                <Modal ariaLabel={`${placingCard.name} 배치`} onClose={() => setPlacing(null)} overlayClassName={mobile ? styles.sheetBottom : styles.sheetRight}>
                    <PlacementSheet card={placingCard} posts={posts.data} busy={busy} onSubmit={(b) => void submitPlacement(b)} onCancel={() => setPlacing(null)} />
                </Modal>
            ) : null}
        </>
    );
}

function PersonBody({ view, mobile, place, postsNotice, assignBusy, hrefs, onAssign }: {
    readonly view: PersonView;
    readonly mobile: boolean;
    readonly place: string | null;
    readonly postsNotice: string | null;
    readonly assignBusy: boolean;
    readonly hrefs: PersonScreenHrefs;
    readonly onAssign: (retainerId: number) => void;
}) {
    const r = view.retinue;
    const mine = view.relation === 'RETINUE';
    const self = view.relation === 'SELF';
    const chips = (
        <div className={styles.chips}>
            {self ? <Chip tone="bronze">나</Chip> : null}
            {mine ? <Chip>내 부</Chip> : null}
            {mine && r?.isHuman === true ? <Chip tone="info">사람</Chip> : null}
            {mine && r?.isHuman === false ? <Chip>NPC</Chip> : null}
            {view.affiliation ? <Chip>{view.affiliation}</Chip> : null}
            {view.injured ? <Chip tone="rust">부상</Chip> : null}
            {mine && r ? <Chip tone={r.loyaltyTone === 'neutral' ? undefined : r.loyaltyTone}>{`충성 ${r.loyalty}`}</Chip> : null}
            {mine && r ? <Chip>{`코스트 ${r.cost ?? '—'}`}</Chip> : null}
            {mine && r?.departureOrder != null ? <Chip tone="rust">{`이탈 판정 ${r.departureOrder}번째`}</Chip> : null}
        </div>
    );
    // 관계에 맞는 단추(설계서 P-R03 「관계별」): 나 → 내 부로, 내 부 NPC → 자리에 배치(부 편성의 배치 시트), 내 부 사람 장수 → 발령은 조정에서.
    const assign = mine && r && r.isHuman === false ? availabilityOf('placement.assign', {
        options: postsNotice ? { available: false, reason: postsNotice }
            : r.post.placeable == null ? null : { available: r.post.placeable, code: r.post.blocked?.code, reason: r.post.blocked?.reason },
    }) : null;
    const actions = (
        <div className={mobile ? styles.actionsMobile : styles.actions}>
            {self ? <Link href={hrefs.retinue()} className="os-button os-button--primary os-button--block">내 부로</Link> : null}
            {mine && r?.isHuman === true ? <Link href={hrefs.dispatch(view.generalId)} className="os-button os-button--primary os-button--block">발령은 조정에서 →</Link> : null}
            {mine && r && r.isHuman === false ? <HelpedInputAction inputId="placement.assign" availability={assign} label="자리에 배치" onAct={() => onAssign(r.retainerId)} busy={assignBusy} block /> : null}
            {mine && r ? <Link href={hrefs.retinue(r.retainerId)} className="os-button os-button--block">부 편성에서 보기</Link> : null}
        </div>
    );
    const humanWait = mine && r?.isHuman == null
        ? <p className={styles.muted} data-waiting="human-flag">사람 장수는 조정에서 발령합니다. 이 인물이 사람 장수인지는 아직 서버가 알려 주지 않습니다.</p>
        : null;
    const hiddenNote = self ? null : !mine ? <p className={styles.muted}>충성 · 코스트 · 녹봉은 내 부 인물만 보입니다.</p> : null;

    const cells = (
        <div className={mobile ? styles.cellsMobile : styles.cells}>
            <Cell title="능력" sub="숫자 그대로">{view.stats ? <StatCells stats={view.stats} /> : <Waiting title="능력 — 서버 대기" body="이 인물의 다섯 능력을 아직 받지 못했습니다." />}</Cell>
            <Cell title="역할 적성" sub="장 · 리 · 사 · 사자">
                {view.aptitudes ? <AptitudeCells aptitudes={view.aptitudes} /> : <Waiting title="적성 — 서버 대기" body="역할 적성은 인물 상세 읽기가 오면 보입니다." />}
            </Cell>
            <Cell title="결속" sub="본관 · 혈연 · 은의 · 결의 · 명망">
                {view.bonds == null ? <Waiting title="결속 — 서버 대기" body="결속은 인물 상세 읽기가 오면 보입니다." /> : (
                    <div className={styles.chips}>
                        {view.bonds.length === 0 ? <span className={styles.muted}>결속 없음</span> : null}
                        {view.bonds.map((b, i) => <Chip key={i} tone="bronze">{bondText(b)}</Chip>)}
                        {view.bonds.some((b) => b.sameAsLord) ? <Chip tone="moss">주공과 같은 고향</Chip> : null}
                        <Chip tone="info">그 밖의 결속 — 준비 중</Chip>
                    </div>
                )}
            </Cell>
            <Cell title="계책 기여">
                <Waiting title="계책 기여 — 준비 중" body="이 인물이 덱에 넣는 계책 카드는 서버가 아직 주지 않습니다." />
            </Cell>
            <Cell title="자리 · 상태">
                <dl className={styles.state}>
                    {stateCells(view, place).map((c) => (
                        <div key={c.label} className={`os-inset ${styles.stateCell}`} data-kind={c.kind}>
                            <dt className={styles.muted}>{c.label}</dt>
                            <dd className={c.kind === 'value' ? undefined : styles.muted}>{c.value}</dd>
                        </div>
                    ))}
                </dl>
            </Cell>
            <Cell title="인물 관직 카드" sub="조정 · 지방 관직 · 작위 · 추천 이력">
                <Waiting title="관직 카드 — 준비 중" body="관직 체계(2층)가 들어오면 법적 관할 · 실효 관할이 여기에 보입니다." />
            </Cell>
        </div>
    );

    if (mobile) {
        return (
            <div className={styles.screenMobile}>
                <div className={styles.heroMobile}>
                    <Portrait picture={view.picture} imageServer={view.imageServer} size="hero" alt={`${view.name} 초상`}
                        ring={view.nationColor ? { color: view.nationColor, reason: self ? 'self' : 'context' } : undefined} />
                </div>
                <h2 className={`os-serif ${styles.nameMobile}`}>{view.name}</h2>
                {chips}
                {hiddenNote}
                {humanWait}
                {cells}
                {actions}
            </div>
        );
    }
    return (
        <div className={styles.screen}>
            <section className={`os-panel ${styles.hero}`} aria-label={`${view.name} 인물 카드`}>
                <div className={styles.portrait}>
                    <Portrait picture={view.picture} imageServer={view.imageServer} size="hero" alt={`${view.name} 초상`}
                        ring={view.nationColor ? { color: view.nationColor, reason: self ? 'self' : 'context' } : undefined} />
                </div>
                <div className={styles.heroBody}>
                    <h2 className={`os-serif ${styles.name}`}>{view.name}</h2>
                    {chips}
                    {hiddenNote}
                    {humanWait}
                </div>
                {actions}
            </section>
            {cells}
        </div>
    );
}
