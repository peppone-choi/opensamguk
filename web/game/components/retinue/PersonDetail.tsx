'use client';

import Link from 'next/link';
import type { ReactNode } from 'react';
import { Chip, InputAction, Portrait, type InputAvailability } from '@opensamguk/ui';
import type { RetinueRow } from '@/lib/retinue-view';
import { bondText, postText } from './RetinueList';
import styles from './retinue.module.css';

const STATS = [
    ['통솔', 'leadership'],
    ['무력', 'strength'],
    ['지력', 'intel'],
    ['정치', 'politics'],
    ['매력', 'charm'],
] as const;
const APTITUDES = [
    ['장 · 군단', 'command'],
    ['리 · 내정', 'administration'],
    ['사 · 계책', 'strategy'],
    ['사자 · 외교', 'envoy'],
] as const;
const TONE = { moss: 'moss', rust: 'rust', neutral: 'neutral' } as const;

function Cells({ label, items }: { readonly label: string; readonly items: ReadonlyArray<readonly [string, number | null]> }) {
    return (
        <div className={styles.cells} role="group" aria-label={label} style={{ gridTemplateColumns: `repeat(${items.length}, minmax(0, 1fr))` }}>
            {items.map(([k, v]) => (
                <div key={k} className={`os-inset ${styles.cell}`}>
                    <span className={styles.cellKey}>{k}</span>
                    <span className="os-mono">{v ?? '—'}</span>
                </div>
            ))}
        </div>
    );
}

export interface PersonDetailProps {
    readonly row: RetinueRow;
    /** `placement.assign` 가능 여부 — 화면이 이 카드의 `/api/posts` 값으로 정해 넘긴다(행 없음 = null → 그리지 않음). */
    readonly assign: InputAvailability | null;
    readonly onAssign: () => void;
    /** 인물 상세 화면(P-R03) 고리. */
    readonly detailLink?: ReactNode;
    /**
     * 사람 장수 카드인가(계약판 K4-18 `posts.cards[].isHuman`). 서버가 주기 전엔 undefined → 「서버 대기」 한 줄.
     * true 면 배치 대신 조정 발령(P-K01) 고리를 보인다. 이름 · id 로 짐작하지 않는다.
     */
    readonly isHuman?: boolean;
    /** 조정 발령 화면 주소(사람 미리 채움). */
    readonly dispatchHref?: string;
}

/**
 * 가운데 인물 상세(보드 V31K4Retinue det) — 초상 · 충성 · 코스트 · 5능력 · 적성 넷 · 결속 · 자리 · 계책 기여(준비 중) · 단추 줄.
 * 서버가 안 주는 칸(보물 · 경험 · 녹봉 · 생몰 · 유일/공용)은 그리지 않는다. 내보내기 · 보물 부착은 원장 행이 생기기 전엔 없다(Q2).
 */
export function PersonDetail({ row, assign, onAssign, detailLink, isHuman, dispatchHref }: PersonDetailProps) {
    const s = row.stats;
    const a = row.aptitudes;
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
                    <Cells label="능력" items={STATS.map(([k, f]) => [k, s ? s[f] : null] as const)} />
                    <Cells label="역할 적성" items={APTITUDES.map(([k, f]) => [k, a ? a[f] : null] as const)} />
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
            {isHuman === undefined ? (
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
