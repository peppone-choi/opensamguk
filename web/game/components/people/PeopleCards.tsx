'use client';

import Link from 'next/link';
import { Chip, Portrait } from '@opensamguk/ui';
import type { PeopleRow } from '@/lib/people-view';
import { Affiliation, NameChips } from './PeopleTable';
import styles from './people.module.css';

const q = (v: number | null | undefined) => (v == null ? '?' : String(v));

export interface PeopleCardsProps {
    readonly rows: readonly PeopleRow[];
    /** 인물 상세(P-R03) 주소 — 없으면(그 화면 전) 카드를 누르면 `onSelect`(미리보기 시트). */
    readonly detailHref?: (generalId: number) => string;
    readonly onSelect?: (row: PeopleRow) => void;
    readonly cityName: (cityId: number) => string | null;
}

/** 모바일 인물 카드(보드 V31K4MPeople card) — 누르면 인물 상세(없으면 미리보기 시트). 카드 높이 80 이상, 카드 전체가 누를 곳. */
export function PeopleCards({ rows, detailHref, onSelect, cityName }: PeopleCardsProps) {
    return (
        <ul className={styles.cards} aria-label="인물">
            {rows.map((r) => {
                const s = r.stats;
                const where = r.locationCityId == null ? '?' : cityName(r.locationCityId) ?? '?';
                const body = (
                    <>
                        <Portrait picture={r.picture} imageServer={r.imageServer} size="card-44" alt="" />
                        <span className={styles.cardText}>
                            <span className={styles.cardName}>
                                <span className="os-serif">{r.name}</span>
                                <NameChips row={r} />
                            </span>
                            <span className={styles.cardSub}>
                                <Affiliation value={r.affiliation} />
                                <span>{`· 소재 ${where}`}</span>
                            </span>
                            <span className={`os-mono ${styles.cardStats}`}>
                                {`통 ${q(s?.leadership)} · 무 ${q(s?.strength)} · 지 ${q(s?.intel)} · 정 ${q(s?.politics)} · 매 ${q(s?.charm)}`}
                            </span>
                            {r.top ? <span><Chip>{`${r.top.label} ${r.top.value}`}</Chip></span> : null}
                        </span>
                    </>
                );
                return (
                    <li key={r.generalId}>
                        {detailHref ? (
                            <Link href={detailHref(r.generalId)} className={styles.card} data-me={r.isMe || undefined} data-general-id={r.generalId}>{body}</Link>
                        ) : (
                            <button type="button" className={styles.card} data-me={r.isMe || undefined} data-general-id={r.generalId}
                                aria-haspopup="dialog" onClick={() => onSelect?.(r)}>{body}</button>
                        )}
                    </li>
                );
            })}
        </ul>
    );
}
