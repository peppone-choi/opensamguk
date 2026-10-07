'use client';

// 외교(P-K02) 오른쪽 칸 — 탭 세력 외교 · 주변 세계 · 외교 서신. 보드 V31K6Diplomacy · MDiplomacy. K6 설계서 §3.7.
// 세력 외교: 내 세력 기준 관계 표(세력 · 관계 칩 · 현 수 · 제의 단추) + 받은 제의(외교 서신) + 천하 관계(다른 세력끼리 ·
// 「세력 × 세력 표로 보기」 — 옛 「외교 현황」 행렬을 기호 대신 글자로). 군주가 아니면 「보기만 합니다」 안내.
// 종전 제의는 서버 옵션으로 고른 세력에 제출하고, 상대가 외교 서신을 수락할 때만 관계가 바뀐다.
// 주변 세계(P-K08)는 K8 내용 · 서버 C5 대기. 지도(관계 레이어)는 K2 부품이 왼쪽에 그린다.
import { useEffect, useState } from 'react';
import { StatusView, safeNationColor } from '@opensamguk/ui';
import { HelpedInputAction } from '@/components/campaign/HelpedInputAction';
import { FrontierWorld } from '@/components/frontier/FrontierWorld';
import { availabilityOf } from '@/lib/input-availability';
import { matrixCellText, proposalsFor, RELATION_LABEL, type NationRelationRow, type RelationKind, type RelationMatrix, type RelationsView } from '@/lib/diplomacy/relations';
import { peaceOfferOptions, submitPeaceOffer } from '@/lib/diplomacy/peace-proposal';
import type { CourtActionOptions } from '@/lib/types';
import styles from './Diplomacy.module.css';

export type RelationsLoad =
    | { readonly state: 'loading' }
    | { readonly state: 'error'; readonly onRetry: () => void }
    | { readonly state: 'ready'; readonly view: RelationsView };

export interface DiplomacyPanelProps {
    readonly load: RelationsLoad;
    /** 외교 서신 칸 — 서신 부품(K6 MailScreen의 외교 판)이나 서버 대기를 부른 쪽이 준다. */
    readonly letters?: React.ReactNode;
    /** 보는 사람이 군주인가(front-info officerLevel 12). false면 「보기만 합니다」 안내, 모르면(null) 안내 없음. */
    readonly viewerIsRuler?: boolean | null;
    readonly generalId?: number | null;
    readonly onOfferSubmitted?: () => void;
}

const TONE: Record<RelationKind, string> = {
    war: 'os-chip--rust', declared: 'os-chip--rust', none: '', nonAggression: 'os-chip--moss', unknown: '',
};

export function DiplomacyPanel({ load, letters, viewerIsRuler = null, generalId = null, onOfferSubmitted }: DiplomacyPanelProps) {
    const [tab, setTab] = useState<'nations' | 'world' | 'letters'>('nations');
    const [peaceOptions, setPeaceOptions] = useState<CourtActionOptions | 'loading' | 'failed'>('loading');
    const [busyNationId, setBusyNationId] = useState<number | null>(null);
    const [rejected, setRejected] = useState<{ nationId: number; code?: string; reason?: string } | null>(null);
    const [notice, setNotice] = useState<{ kind: 'ok' | 'error' | 'info'; text: string } | null>(null);
    const relationsReady = load.state === 'ready' && load.view.state === 'ready';
    useEffect(() => {
        if (generalId == null || viewerIsRuler !== true || !relationsReady) return;
        let active = true;
        setPeaceOptions('loading');
        peaceOfferOptions(generalId)
            .then((options) => { if (active) setPeaceOptions(options); })
            .catch(() => { if (active) setPeaceOptions('failed'); });
        return () => { active = false; };
    }, [generalId, viewerIsRuler, relationsReady]);

    const offerPeace = async (nationId: number) => {
        if (generalId == null || viewerIsRuler !== true || busyNationId != null || typeof peaceOptions === 'string') return;
        const choice = peaceOptions.choices.find((it) => it.arguments.targetNationId === nationId);
        if (!choice?.available) return;
        setBusyNationId(nationId);
        setRejected(null);
        setNotice(null);
        try {
            const outcome = await submitPeaceOffer(generalId, nationId);
            if (outcome.status === 'rejected') {
                setRejected({ nationId, code: outcome.code, reason: outcome.reason });
                setNotice({ kind: 'error', text: outcome.reason ?? '종전 제의를 접수하지 못했습니다.' });
            } else if (outcome.status === 'reserved') {
                setNotice({ kind: 'ok', text: '종전 제의를 예약했습니다. 다음 개인 턴에 상대 세력으로 서신을 보냅니다.' });
                onOfferSubmitted?.();
            } else if (outcome.status === 'applied') {
                setNotice({ kind: 'ok', text: '종전 제의 서신을 보냈습니다. 상대의 수락 전까지 교전은 계속됩니다.' });
                onOfferSubmitted?.();
            } else {
                setNotice({ kind: 'info', text: '처리가 늦어지고 있습니다. 결과와 외교 서신을 다시 확인해 주세요.' });
            }
            try {
                setPeaceOptions(await peaceOfferOptions(generalId));
            } catch {
                setPeaceOptions('failed');
            }
        } catch {
            setNotice({ kind: 'error', text: '종전 제의 결과를 확인하지 못했습니다. 다시 조회해 주세요.' });
        } finally {
            setBusyNationId(null);
        }
    };
    return (
        <section className={styles.panel} aria-label="외교" data-testid="diplomacy-panel">
            <div className={styles.tabs} role="tablist" aria-label="외교 화면">
                {([['nations', '세력 외교'], ['world', '주변 세계'], ['letters', '외교 서신']] as const).map(([k, label]) => (
                    <button key={k} type="button" role="tab" aria-selected={tab === k} className={styles.tab} onClick={() => setTab(k)}>{label}</button>
                ))}
            </div>
            <div className={styles.body}>
                {tab === 'world' ? <FrontierWorld /> : null}
                {tab === 'letters' ? (letters ?? <StatusView kind="waiting" title="외교 서신 준비 중" body="외교 서신은 군주 · 외교권자만 봅니다." />) : null}
                {tab === 'nations' ? <>
                    {notice ? <p role={notice.kind === 'error' ? 'alert' : 'status'}>{notice.text}</p> : null}
                    <Nations load={load} viewerIsRuler={viewerIsRuler} generalId={generalId}
                        peaceOptions={peaceOptions} busyNationId={busyNationId} rejected={rejected}
                        onOfferPeace={(nationId) => void offerPeace(nationId)} onOpenLetters={() => setTab('letters')} />
                </> : null}
            </div>
        </section>
    );
}

function Nations({ load, viewerIsRuler, generalId, peaceOptions, busyNationId, rejected, onOfferPeace, onOpenLetters }: {
    load: RelationsLoad; viewerIsRuler: boolean | null; generalId: number | null;
    peaceOptions: CourtActionOptions | 'loading' | 'failed'; busyNationId: number | null;
    rejected: { nationId: number; code?: string; reason?: string } | null;
    onOfferPeace: (nationId: number) => void; onOpenLetters: () => void;
}) {
    if (load.state === 'loading') return <StatusView kind="loading" rows={4} />;
    if (load.state === 'error') return <StatusView kind="error" title="외교 관계를 불러오지 못했습니다" body="빈 표가 아닙니다 — 불러오기가 실패했습니다." onRetry={load.onRetry} />;
    const view = load.view;
    if (view.state === 'stateless') return <StatusView kind="empty" title="세력이 없어 외교를 할 수 없습니다" body="세력에 들어가거나 세력을 세우면 이곳이 열립니다." />;
    return (
        <>
            {viewerIsRuler === false ? (
                <p className={styles.notice} role="note"><b>외교는 군주가 합니다</b> — 보기만 합니다. 제의 단추는 입력이 열리면 군주에게만 켜집니다.</p>
            ) : null}
            <h3 className={styles.head}>{view.me.name}의 관계</h3>
            {view.rows.length === 0 ? <StatusView kind="empty" title="다른 세력이 없습니다" body="천하에 우리 세력뿐입니다." /> : (
                <ul className={styles.rows} aria-label="세력별 관계">
                    {view.rows.map((r) => <RelationRow key={r.nationId} row={r}
                        generalId={generalId} viewerIsRuler={viewerIsRuler} peaceOptions={peaceOptions}
                        busy={busyNationId === r.nationId} rejected={rejected?.nationId === r.nationId ? rejected : null}
                        onOfferPeace={onOfferPeace} />)}
                </ul>
            )}
            <section className={styles.box} aria-label="받은 제의">
                <h3 className={styles.head}>받은 제의</h3>
                <p>상대 세력이 보낸 종전 제의는 외교 서신에서 확인하고 답할 수 있습니다.</p>
                <button type="button" className="os-button os-button--ghost" onClick={onOpenLetters}>외교 서신 보기</button>
            </section>
            <World view={view} />
        </>
    );
}

/** 천하 관계 — 다른 세력끼리는 서버가 교전 · 선포만 보인다. 「세력 × 세력 표로 보기」는 옛 외교 현황 행렬(기호 대신 글자). */
function World({ view }: { view: Extract<RelationsView, { state: 'ready' }> }) {
    const [table, setTable] = useState(false);
    return (
        <section className={styles.box} aria-label="천하 관계">
            <h3 className={styles.head}>천하 관계 <span className={styles.sub}>다른 세력끼리는 교전 · 선포만 보입니다</span></h3>
            {view.othersAtWar.length === 0 ? <p className={styles.sub}>다른 세력끼리 교전 · 선포가 없습니다.</p> : (
                <ul className={styles.pairs} aria-label="다른 세력끼리">
                    {view.othersAtWar.map((p) => (
                        <li key={`${p.a.id}-${p.b.id}`}>{p.a.name} · {p.b.name} <span className={`os-chip ${TONE[p.relation]}`}>{RELATION_LABEL[p.relation]}</span></li>
                    ))}
                </ul>
            )}
            <button type="button" className={`os-button os-button--ghost ${styles.more}`} aria-expanded={table} onClick={() => setTable((v) => !v)}>
                {table ? '세력 × 세력 표 닫기' : '세력 × 세력 표로 보기'}
            </button>
            {table ? <Matrix matrix={view.matrix} meId={view.me.id} /> : null}
        </section>
    );
}

function Matrix({ matrix, meId }: { matrix: RelationMatrix; meId: number }) {
    return (
        <div className={styles.matrixWrap} role="region" aria-label="세력 × 세력 표" tabIndex={0}>
            <table className={styles.matrix}>
                <thead>
                    <tr>
                        <th scope="col"><span className="sr-only">세력</span></th>
                        {matrix.nations.map((n) => <th key={n.id} scope="col"><i className={styles.dot} style={{ background: safeNationColor(n.color) }} aria-hidden="true" />{n.name}</th>)}
                    </tr>
                </thead>
                <tbody>
                    {matrix.nations.map((a) => (
                        <tr key={a.id} data-mine={a.id === meId || undefined}>
                            <th scope="row"><i className={styles.dot} style={{ background: safeNationColor(a.color) }} aria-hidden="true" />{a.name}</th>
                            {matrix.nations.map((b) => {
                                const kind = matrix.cells[a.id]?.[b.id] ?? null;
                                const text = matrixCellText(kind, a.id === meId || b.id === meId);
                                return (
                                    <td key={b.id} data-self={kind === 'self' || undefined} data-kind={kind ?? undefined}>
                                        {kind === 'self' ? <span aria-label="같은 세력">＼</span> : text}
                                    </td>
                                );
                            })}
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
}

function RelationRow({ row, generalId, viewerIsRuler, peaceOptions, busy, rejected, onOfferPeace }: {
    row: NationRelationRow; generalId: number | null; viewerIsRuler: boolean | null;
    peaceOptions: CourtActionOptions | 'loading' | 'failed'; busy: boolean;
    rejected: { code?: string; reason?: string } | null; onOfferPeace: (nationId: number) => void;
}) {
    const [open, setOpen] = useState(false);
    const proposals = proposalsFor(row.relation);
    return (
        <li className={styles.row} data-nation-id={row.nationId}>
            <div className={styles.rowHead}>
                <i className={styles.flag} style={{ background: safeNationColor(row.color) }} aria-hidden="true" />
                <span className={styles.name}>{row.name}</span>
                <span className={`os-chip ${TONE[row.relation]}`}>{RELATION_LABEL[row.relation]}</span>
                <button type="button" className={`os-button os-button--ghost ${styles.more}`} aria-expanded={open} onClick={() => setOpen((v) => !v)}>
                    현 {row.counties.length}
                </button>
            </div>
            {open ? <p className={styles.counties}>{row.counties.length > 0 ? row.counties.join(' · ') : '가진 현이 없습니다'}</p> : null}
            {proposals.length > 0 ? (
                <div className={styles.proposals}>
                    {proposals.map((p) => (
                        <HelpedInputAction
                            key={p.inputId}
                            inputId={p.inputId}
                            availability={p.inputId === 'court.offerPeace'
                                ? availabilityOf(p.inputId, {
                                    options: generalId == null || viewerIsRuler !== true
                                        ? { available: false, code: 'NOT_RULER', reason: '소속 세력의 군주만 종전 제의를 보낼 수 있습니다.' }
                                        : typeof peaceOptions === 'string'
                                            ? { available: false, code: 'STATE_UNAVAILABLE', reason: peaceOptions === 'loading'
                                                ? '종전 제의 가능 여부를 확인하고 있습니다.' : '종전 제의 가능 여부를 불러오지 못했습니다.' }
                                            : (() => { const choice = peaceOptions.choices.find((it) => it.arguments.targetNationId === row.nationId);
                                                return choice ? { available: choice.available, code: choice.code, reason: choice.reason }
                                                    : { available: false, code: peaceOptions.code, reason: peaceOptions.reason }; })(),
                                    rejected,
                                })
                                : availabilityOf(p.inputId)}
                            label={p.label}
                            variant={p.danger ? 'danger' : 'ghost'}
                            reasonTitle={`${p.label} — 아직 열리지 않았습니다`}
                            onAct={() => { if (p.inputId === 'court.offerPeace' && !busy) onOfferPeace(row.nationId); }}
                        />
                    ))}
                </div>
            ) : null}
        </li>
    );
}

export default DiplomacyPanel;
