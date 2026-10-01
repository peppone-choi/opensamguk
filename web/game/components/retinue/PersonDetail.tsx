'use client';

import Link from 'next/link';
import type { ReactNode } from 'react';
import { Chip, InputAction, Portrait, type InputAvailability } from '@opensamguk/ui';
import type { RetinueRow } from '@/lib/retinue-view';
import { bondText, postText } from './RetinueList';
import { AptitudeCells, StatCells } from './StatCells';
import styles from './retinue.module.css';

const TONE = { moss: 'moss', rust: 'rust', neutral: 'neutral' } as const;

export interface PersonDetailProps {
    readonly row: RetinueRow;
    /** `placement.assign` 가능 여부 — 화면이 이 카드의 `/api/posts` 값으로 정해 넘긴다(행 없음 = null → 그리지 않음). */
    readonly assign: InputAvailability | null;
    readonly onAssign: () => void;
    /** 인물 상세 화면(P-R03) 고리. */
    readonly detailLink?: ReactNode;
    /** 조정 발령 화면 주소(사람 미리 채움). 사람 장수 판정은 row.isHuman(K4-18)에서 읽는다. */
    readonly dispatchHref?: string;
}

/**
 * 가운데 인물 상세(보드 V31K4Retinue det) — 초상 · 충성 · 코스트 · 5능력 · 적성 넷 · 결속 · 자리 · 계책 기여(준비 중) · 단추 줄.
 * 서버가 안 주는 칸(보물 · 경험 · 녹봉 · 생몰 · 유일/공용)은 그리지 않는다. 내보내기 · 보물 부착은 원장 행이 생기기 전엔 없다(Q2).
 */
/**
 * 사람 장수 판정(K4-18 `isHuman: boolean | null`): 없음 · null(인물 미해결 — NPC 확정 아님) = 「서버 대기」 한 줄,
 * true = 배치 대신 조정 발령 고리, false = 배치 단추(서버 blocked · placeable 그대로). 이름 · id 로 짐작하지 않는다.
 */
export function PersonDetail({ row, assign, onAssign, detailLink, dispatchHref }: PersonDetailProps) {
    const isHuman = row.isHuman;
    return (
        <article className={styles.detail} aria-label={`${row.name} 인물 카드`}>
            <div className={styles.detailHead}>
                <Portrait picture={row.picture} imageServer={row.imageServer} size="card" alt={`${row.name} 초상`} />
                <div className={styles.detailBody}>
                    <h3 className={`os-serif ${styles.detailName}`}>{row.name}</h3>
                    <div className={styles.chips}>
                        <Chip tone={TONE[row.loyaltyTone]}>{`충성 ${row.loyalty}`}</Chip>
                        <Chip>{`코스트 ${row.cost ?? '—'}`}</Chip>
                        {row.departureOrder != null ? <Chip tone="rust">{`이탈 판정 ${row.departureOrder}번째`}</Chip> : null}
                    </div>
                    <StatCells stats={row.stats} />
                    <AptitudeCells aptitudes={row.aptitudes} />
                    <div className={styles.field}>
                        <span className={styles.label}>결속</span>
                        <div className={styles.chips}>
                            {row.bonds.length === 0 ? <span className={styles.muted}>결속 없음</span> : null}
                            {row.bonds.map((b, i) => <Chip key={i} tone="bronze">{bondText(b)}</Chip>)}
                            {row.bonds.some((b) => b.sameAsLord) ? <Chip tone="moss">주공과 같은 고향</Chip> : null}
                        </div>
                    </div>
                </div>
            </div>
            <dl className={styles.facts}>
                <div className={`os-inset ${styles.cell}`}><dt className={styles.cellKey}>자리</dt><dd>{postText(row)}</dd></div>
                <div className={`os-inset ${styles.cell}`}><dt className={styles.cellKey}>지휘 병력</dt><dd className="os-mono">{row.troops}</dd></div>
            </dl>
            <div className={styles.field} data-waiting="stratagem-contribution">
                <span className={styles.label}>계책 기여</span>
                <Chip tone="info">준비 중</Chip>
                <span className={styles.muted}>이 인물이 부를 떠나면 기여한 계책 카드도 덱에서 빠집니다.</span>
            </div>
            {isHuman == null ? (
                <div className={styles.field} data-waiting="human-flag">
                    <Chip tone="info">준비 중</Chip>
                    <span className={styles.muted}>사람 장수는 조정에서 발령합니다. 이 인물이 사람 장수인지는 아직 서버가 알려 주지 않습니다.</span>
                </div>
            ) : null}
            <div className={styles.actions}>
                {isHuman && dispatchHref ? (
                    <Link href={dispatchHref} className="os-button os-button--primary os-button--block">발령은 조정에서 →</Link>
                ) : isHuman ? null : (
                    <InputAction inputId="placement.assign" availability={assign} label="자리에 배치" onAct={onAssign} block />
                )}
                {detailLink}
            </div>
        </article>
    );
}
