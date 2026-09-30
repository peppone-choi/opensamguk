'use client';

import Link from 'next/link';
import { Chip, Portrait } from '@opensamguk/ui';
import type { PeopleRow } from '@/lib/people-view';
import { Affiliation, NameChips } from './PeopleTable';
import styles from './people.module.css';

const q = (v: number | null | undefined) => (v == null ? '?' : String(v));

export interface PeopleCardsProps {
    readonly rows: readonly PeopleRow[];
    /** 인물 상세(P-R03) 주소. */
    readonly detailHref: (generalId: number) => string;
    readonly cityName: (cityId: number) => string | null;
}

/** 모바일 인물 카드(보드 V31K4MPeople card) — 누르면 인물 상세. 카드 높이 80 이상, 카드 전체가 고리. */
export function PeopleCards({ rows, detailHref, cityName }: PeopleCardsProps) {
    return (
        <ul className={styles.cards} aria-label="인물">
            {rows.map((r) => {
                const s = r.stats;
                const where = r.locationCityId == null ? '?' : cityName(r.locationCityId) ?? '?';
                return (
                    <li key={r.generalId}>
                        <Link href={detailHref(r.generalId)} className={styles.card} data-me={r.isMe || undefined} data-general-id={r.generalId}>
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
                        </Link>
                    </li>
                );
            })}
        </ul>
    );
}
