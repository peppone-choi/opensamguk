'use client';

import Link from 'next/link';
import type { ReactNode } from 'react';
import { Chip, InputAction, type InputAvailability } from '@opensamguk/ui';
import { fallConsequences, type FortRow, type SiegeRow } from '@/lib/siege-view';
import styles from './siege.module.css';

export const siegeKey = (countyId: number) => `siege:${countyId}`;
export const fortKey = (id: string) => `fort:${id}`;

export interface SiegeListProps {
    readonly sieges: readonly SiegeRow[];
    readonly forts: readonly FortRow[];
    readonly selected: string | null;
    readonly onSelect: (key: string) => void;
    /** 빈 상태의 「군단으로」 고리. */
    readonly corpsHref?: string;
}

/** 포위 목록(보드 V31K4Siege 왼쪽 360) — 성 · 보루 한 목록. 비면 「포위 중인 성이 없습니다」 + 군단으로. */
export function SiegeList({ sieges, forts, selected, onSelect, corpsHref }: SiegeListProps) {
    if (sieges.length === 0 && forts.length === 0) {
        return (
            <div className={styles.empty} role="status">
                <p>포위 중인 성이 없습니다 — 군단이 적 성에 닿으면 여기에 나옵니다.</p>
                {corpsHref ? <Link href={corpsHref} className={styles.link}>군단으로 →</Link> : null}
            </div>
        );
    }
    const opt = (key: string, name: string, sub: string, chip: ReactNode) => {
        const sel = key === selected;
        return (
            <button key={key} type="button" role="option" aria-selected={sel} data-key={key}
                className={['os-opt', styles.opt, sel ? 'os-opt--sel' : ''].filter(Boolean).join(' ')} onClick={() => onSelect(key)}>
                <span className="os-opt__text"><span className="os-opt__name">{name}</span><span className="os-opt__sub">{sub}</span></span>
                <span className="os-opt__end">{chip}</span>
            </button>
        );
    };
    return (
        <div role="listbox" aria-label="포위" className={styles.list}>
            {sieges.map((s) => opt(siegeKey(s.countyId), s.title, `${s.sides} · 포위 ${s.turns}순째`, <Chip tone={s.status.tone}>{s.status.label}</Chip>))}
            {forts.map((f) => opt(fortKey(f.id), '도로 보루', `성벽 ${f.wall} · ${f.progress == null ? '포위 없음' : `포위 진척 ${f.progress}%`}`, <Chip>보루</Chip>))}
        </div>
    );
}

export interface SiegeDetailProps {
    readonly row: SiegeRow;
    /** 성 그림(B안 성 부품, K2). */
    readonly castle?: ReactNode;
}

/** 고른 포위(보드 가운데) — 머리 · 성 그림 · 6칸 · 포위군 급식 · 포위 기록. 보이지 않는 값은 「?」. */
export function SiegeDetail({ row, castle }: SiegeDetailProps) {
    return (
        <article className={styles.detail} aria-label={`${row.title} 포위`}>
            <header className={styles.head}>
                <h3 className={`os-serif ${styles.title}`}>{row.title}</h3>
                <Chip tone={row.status.tone}>{row.status.label}</Chip>
                <span className={styles.muted}>{`${row.sides} · ${row.startedAt}부터 · 포위 ${row.turns}순째 · 지휘 ${row.commander}`}</span>
            </header>
            {castle ? <div className={styles.castle}>{castle}</div> : null}
            <dl className={styles.cells}>
                {row.cells.map((c) => (
                    <div key={c.key} className={`os-inset ${styles.cell}`}>
                        <dt className={styles.cellKey}>{c.key}</dt>
                        <dd className="os-mono">{c.value}</dd>
                    </div>
                ))}
            </dl>
            <div className={styles.supply}>
                <span className={styles.muted}>포위군 보급</span>
                {row.fed == null ? <Chip>?</Chip> : row.fed ? <Chip tone="moss">받는 중</Chip> : <Chip tone="rust">못 받음 — 병력이 줄어듭니다</Chip>}
            </div>
            <section aria-label="포위 기록">
                <h4 className={styles.sub}>포위 기록</h4>
                {row.timeline.length === 0 ? <p className={styles.muted}>기록이 없습니다.</p> : (
                    <ol className={styles.timeline}>
                        {row.timeline.map((t, i) => (
                            <li key={i} className={styles.tlRow}>
                                <span className={`os-mono ${styles.muted}`}>{t.when}</span>
                                <span>{t.what}</span>
                            </li>
                        ))}
                    </ol>
                )}
            </section>
        </article>
    );
}

export interface SiegeOrdersProps {
    readonly row: SiegeRow;
    readonly assault: InputAvailability | null;
    readonly surrender: InputAvailability | null;
    readonly onAssault: () => void;
    readonly onSurrender: () => void;
    /** 도움말 띠(K7, 44) — 「강공은 성새전(실시간 전투)을 엽니다…」. */
    readonly help?: ReactNode;
    readonly stratagemHref?: string;
}

/**
 * 명령 칸(보드 오른쪽 360) — 강공 · 항복 권고(둘 다 명령 흐름에서 순을 고른다) · 항복 권고 조건 · 함락되면 · 계책 고리.
 * 포위가 끝났으면 명령 단추는 없다. 지휘관이 아니거나 준비가 안 됐으면 서버 사유로 점선.
 */
export function SiegeOrders({ row, assault, surrender, onAssault, onSurrender, help, stratagemHref }: SiegeOrdersProps) {
    return (
        <div className={styles.orders}>
            {row.active ? (
                <section className={styles.block} aria-label="명령">
                    {help}
                    <InputAction inputId="action.assault" availability={assault} label="강공 — 순 고르기" variant="danger" onAct={onAssault} block />
                    <InputAction inputId="action.demandSurrender" availability={surrender} label="항복 권고 — 순 고르기" onAct={onSurrender} block />
                </section>
            ) : null}
            {row.active ? (
                <section className={styles.block} aria-label="항복 권고">
                    <h4 className={styles.sub}>항복 권고</h4>
                    <p className={styles.muted}>
                        {row.surrenderLikely ? '지금 권고하면 받아들일 것으로 보입니다.' : '지금은 거절할 것으로 보입니다.'}
                        {' '}성 안 민심이 낮을수록, 쌀이 줄수록 받아들이기 쉽습니다.
                    </p>
                </section>
            ) : null}
            <section className={styles.block} aria-label="함락되면">
                <h4 className={styles.sub}>함락되면</h4>
                <ul className={styles.fall}>
                    {fallConsequences(row.title).map((f) => <li key={f.what}><b>{f.what}</b>{` — ${f.then}`}</li>)}
                </ul>
            </section>
            {stratagemHref ? <Link href={stratagemHref} className={styles.link}>계책 덱에서 공성 계책 쓰기 →</Link> : null}
        </div>
    );
}

/** 도로 보루 명령 — 불가면 점선 + 사유(옛 화면은 단추를 숨겼다). */
export function FortOrders({ fort, availability, onBesiege }: { readonly fort: FortRow; readonly availability: InputAvailability | null; readonly onBesiege: () => void }) {
    return (
        <section className={styles.block} aria-label="도로 보루">
            <dl className={styles.cells}>
                <div className={`os-inset ${styles.cell}`}><dt className={styles.cellKey}>성벽</dt><dd className="os-mono">{fort.wall}</dd></div>
                <div className={`os-inset ${styles.cell}`}><dt className={styles.cellKey}>보루 수비</dt><dd className="os-mono">{fort.garrison}</dd></div>
                <div className={`os-inset ${styles.cell}`}><dt className={styles.cellKey}>포위 진척</dt><dd className="os-mono">{fort.progress == null ? '포위 없음' : `${fort.progress}%`}</dd></div>
            </dl>
            <InputAction inputId="action.siegeRoadFort" availability={availability} label="보루 포위 — 순 고르기" variant="danger" onAct={onBesiege} block />
        </section>
    );
}
