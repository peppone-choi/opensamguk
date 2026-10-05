'use client';

// 외교(P-K02) 오른쪽 칸 — 탭 세력 외교 · 주변 세계 · 외교 서신. 보드 V31K6Diplomacy · MDiplomacy. K6 설계서 §3.7.
// 세력 외교: 내 세력 기준 관계 표(세력 · 관계 칩 · 현 수 · 제의 단추) + 받은 제의(서버 대기) + 천하 관계(다른 세력끼리 ·
// 「세력 × 세력 표로 보기」 — 옛 「외교 현황」 행렬을 기호 대신 글자로). 군주가 아니면 「보기만 합니다」 안내.
// 제의 5종은 원장 PLANNED라 단추가 「준비 중」. 받은 제의 응답은 원장 행이 없어 단추를 그리지 않고 영역 전체 서버 대기
// (계약판 K6-05 · A7 — 옛 삼모 외교 서신의 수락 · 거절은 옮기지 않는다).
// 주변 세계(P-K08)는 K8 내용 · 서버 C5 대기. 지도(관계 레이어)는 K2 부품이 왼쪽에 그린다.
import { useState } from 'react';
import { StatusView, safeNationColor } from '@opensamguk/ui';
import { HelpedInputAction } from '@/components/campaign/HelpedInputAction';
import { FrontierTab } from '@/components/frontier/FrontierTab';
import { availabilityOf } from '@/lib/input-availability';
import { matrixCellText, proposalsFor, RELATION_LABEL, type NationRelationRow, type RelationKind, type RelationMatrix, type RelationsView } from '@/lib/diplomacy/relations';
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
}

const TONE: Record<RelationKind, string> = {
    war: 'os-chip--rust', declared: 'os-chip--rust', none: '', nonAggression: 'os-chip--moss', unknown: '',
};

export function DiplomacyPanel({ load, letters, viewerIsRuler = null }: DiplomacyPanelProps) {
    const [tab, setTab] = useState<'nations' | 'world' | 'letters'>('nations');
    return (
        <section className={styles.panel} aria-label="외교" data-testid="diplomacy-panel">
            <div className={styles.tabs} role="tablist" aria-label="외교 화면">
                {([['nations', '세력 외교'], ['world', '주변 세계'], ['letters', '외교 서신']] as const).map(([k, label]) => (
                    <button key={k} type="button" role="tab" aria-selected={tab === k} className={styles.tab} onClick={() => setTab(k)}>{label}</button>
                ))}
            </div>
            <div className={styles.body}>
                {tab === 'world' ? <FrontierTab /> : null}
                {tab === 'letters' ? (letters ?? <StatusView kind="waiting" title="외교 서신 준비 중" body="외교 서신은 군주 · 외교권자만 봅니다." />) : null}
                {tab === 'nations' ? <Nations load={load} viewerIsRuler={viewerIsRuler} /> : null}
            </div>
        </section>
    );
}

function Nations({ load, viewerIsRuler }: { load: RelationsLoad; viewerIsRuler: boolean | null }) {
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
                    {view.rows.map((r) => <RelationRow key={r.nationId} row={r} />)}
                </ul>
            )}
            <section className={styles.box} aria-label="받은 제의">
                <h3 className={styles.head}>받은 제의</h3>
                <StatusView kind="waiting" title="받은 제의 준비 중" body="다른 세력이 보낸 제의와 그 응답은 서버가 아직 주지 않습니다." />
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

function RelationRow({ row }: { row: NationRelationRow }) {
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
                            availability={availabilityOf(p.inputId)}
                            label={p.label}
                            variant={p.danger ? 'danger' : 'ghost'}
                            reasonTitle={`${p.label} — 아직 열리지 않았습니다`}
                            onAct={() => {}}
                        />
                    ))}
                </div>
            ) : null}
        </li>
    );
}

export default DiplomacyPanel;
