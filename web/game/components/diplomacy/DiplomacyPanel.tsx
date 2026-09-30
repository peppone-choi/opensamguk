'use client';

// 외교(P-K02) 오른쪽 칸 — 탭 세력 외교 · 주변 세계 · 외교 서신. 보드 V31K6Diplomacy · MDiplomacy. K6 설계서 §3.7.
// 세력 외교: 내 세력 기준 관계 표(세력 · 관계 칩 · 현 수 · 제의 단추) + 받은 제의(서버 대기) + 「다른 세력끼리」 목록.
// 제의 5종은 원장 PLANNED라 단추가 「준비 중」. 받은 제의 응답은 원장 행이 없어 단추를 그리지 않고 영역 전체 서버 대기.
// 주변 세계(P-K08)는 K8 내용 · 서버 C5 대기. 지도(관계 레이어)는 K2 부품이 왼쪽에 그린다.
import { useState } from 'react';
import { InputAction, StatusView } from '@opensamguk/ui';
import { availabilityOf } from '@/lib/input-availability';
import { proposalsFor, RELATION_LABEL, type NationRelationRow, type RelationKind, type RelationsView } from '@/lib/diplomacy/relations';
import styles from './Diplomacy.module.css';

export type RelationsLoad =
    | { readonly state: 'loading' }
    | { readonly state: 'error'; readonly onRetry: () => void }
    | { readonly state: 'ready'; readonly view: RelationsView };

export interface DiplomacyPanelProps {
    readonly load: RelationsLoad;
    /** 외교 서신 칸 — 서신 부품(K6 MailScreen의 외교 판)이나 서버 대기를 부른 쪽이 준다. */
    readonly letters?: React.ReactNode;
}

const TONE: Record<RelationKind, string> = {
    war: 'os-chip--rust', declared: 'os-chip--rust', none: '', nonAggression: 'os-chip--moss', unknown: '',
};

export function DiplomacyPanel({ load, letters }: DiplomacyPanelProps) {
    const [tab, setTab] = useState<'nations' | 'world' | 'letters'>('nations');
    return (
        <section className={styles.panel} aria-label="외교" data-testid="diplomacy-panel">
            <div className={styles.tabs} role="tablist" aria-label="외교 화면">
                {([['nations', '세력 외교'], ['world', '주변 세계'], ['letters', '외교 서신']] as const).map(([k, label]) => (
                    <button key={k} type="button" role="tab" aria-selected={tab === k} className={styles.tab} onClick={() => setTab(k)}>{label}</button>
                ))}
            </div>
            <div className={styles.body}>
                {tab === 'world' ? <StatusView kind="waiting" title="주변 세계 준비 중" body="침입 · 조공 · 내속 · 교역은 서버가 아직 주지 않습니다." /> : null}
                {tab === 'letters' ? (letters ?? <StatusView kind="waiting" title="외교 서신 준비 중" body="외교 서신은 군주 · 외교권자만 봅니다." />) : null}
                {tab === 'nations' ? <Nations load={load} /> : null}
            </div>
        </section>
    );
}

function Nations({ load }: { load: RelationsLoad }) {
    if (load.state === 'loading') return <StatusView kind="loading" rows={4} />;
    if (load.state === 'error') return <StatusView kind="error" title="외교 관계를 불러오지 못했습니다" body="빈 표가 아닙니다 — 불러오기가 실패했습니다." onRetry={load.onRetry} />;
    const view = load.view;
    if (view.state === 'stateless') return <StatusView kind="empty" title="세력이 없어 외교를 할 수 없습니다" body="세력에 들어가거나 세력을 세우면 이곳이 열립니다." />;
    return (
        <>
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
            {view.othersAtWar.length > 0 ? (
                <section className={styles.box} aria-label="다른 세력끼리">
                    <h3 className={styles.head}>다른 세력끼리</h3>
                    <ul className={styles.pairs}>
                        {view.othersAtWar.map((p) => (
                            <li key={`${p.a.id}-${p.b.id}`}>{p.a.name} · {p.b.name} <span className={`os-chip ${TONE[p.relation]}`}>{RELATION_LABEL[p.relation]}</span></li>
                        ))}
                    </ul>
                </section>
            ) : null}
        </>
    );
}

function RelationRow({ row }: { row: NationRelationRow }) {
    const [open, setOpen] = useState(false);
    const proposals = proposalsFor(row.relation);
    return (
        <li className={styles.row} data-nation-id={row.nationId}>
            <div className={styles.rowHead}>
                <i className={styles.flag} style={{ background: row.color }} aria-hidden="true" />
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
                        <InputAction
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
