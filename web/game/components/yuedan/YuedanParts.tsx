'use client';

import Link from 'next/link';
import { useRef } from 'react';
import { Chip, Portrait } from '@opensamguk/ui';
import type { RenownPendingEvent, Yuedan, YuedanRow } from '@/lib/campaign-reads';
import { loyaltyTone } from '@/lib/retinue-view';
import { RENOWN_FALLING, RENOWN_RISING, pendingChip, reasonChip, type DepartureRow } from '@/lib/yuedan-view';
import styles from './yuedan.module.css';

/** 세력 칩 — 세력색 네모 + 이름, 재야는 글자만. */
function Nation({ row }: { readonly row: YuedanRow }) {
    if (!row.nationName) return <span className={styles.muted}>재야</span>;
    return (
        <span className={styles.nation}>
            <i className={styles.swatch} style={{ background: row.nationColor ?? 'transparent' }} aria-hidden="true" />
            {row.nationName}
        </span>
    );
}

function Reasons({ row }: { readonly row: YuedanRow }) {
    if (!row.reasons || row.reasons.length === 0) return <span className={styles.muted}>—</span>;
    return (
        <span className={styles.chips}>
            {row.reasons.map((r) => {
                const c = reasonChip(r);
                return <Chip key={r.kind} tone={c.tone}>{c.text}</Chip>;
            })}
        </span>
    );
}

export interface RankingProps {
    readonly ranking: readonly YuedanRow[];
    readonly meId: number | null;
    readonly mobile?: boolean;
}

/**
 * 순위(보드 V31K4Yuedan 표 · V31K4MYuedan 카드) — 순위 · 장수(+나) · 세력 · 명망 · 이번 달 사유. 내 줄은 청동.
 * 「내 순위로」는 내 줄이 목록에 있을 때만 — 누르면 그 줄로 옮기고 초점을 준다.
 */
export function Ranking({ ranking, meId, mobile = false }: RankingProps) {
    const mine = useRef<HTMLElement | null>(null);
    const hasMe = meId != null && ranking.some((r) => r.generalId === meId);
    const jump = () => {
        mine.current?.scrollIntoView({ block: 'center' });
        mine.current?.focus();
    };
    const jumpButton = hasMe ? <button type="button" className="os-button" onClick={jump}>내 순위로</button> : null;

    if (mobile) {
        return (
            <div className={styles.rankMobile}>
                {jumpButton}
                <ol className={styles.rankCards} aria-label="월단평 순위">
                    {ranking.map((r) => {
                        const me = r.generalId === meId;
                        return (
                            <li
                                key={r.generalId}
                                ref={me ? (el) => { mine.current = el; } : undefined}
                                tabIndex={me ? -1 : undefined}
                                className={styles.rankCard}
                                data-me={me || undefined}
                            >
                                <span className={`os-mono ${styles.rankNo}`}>{r.rank}</span>
                                <span className={styles.rankName}>
                                    <span className="os-serif">{r.name}</span>
                                    {me ? <Chip tone="bronze">나</Chip> : null}
                                </span>
                                <Nation row={r} />
                                <span className="os-mono">{r.renown}</span>
                                <span className={styles.rankReasons}><Reasons row={r} /></span>
                            </li>
                        );
                    })}
                </ol>
            </div>
        );
    }
    return (
        <div className={styles.rankDesk}>
            <div className="os-table-wrap">
                <table className="os-table os-table--nowrap">
                    <thead>
                        <tr><th scope="col">순위</th><th scope="col">장수</th><th scope="col">세력</th><th scope="col">명망</th><th scope="col">이번 달 사유</th></tr>
                    </thead>
                    <tbody>
                        {ranking.map((r) => {
                            const me = r.generalId === meId;
                            return (
                                <tr
                                    key={r.generalId}
                                    ref={me ? (el) => { mine.current = el; } : undefined}
                                    tabIndex={me ? -1 : undefined}
                                    className={styles.rankRow}
                                    data-me={me || undefined}
                                >
                                    <td className="os-mono">{r.rank}</td>
                                    <td><span className="os-serif" style={{ fontWeight: 700 }}>{r.name}</span>{me ? <> <Chip tone="bronze">나</Chip></> : null}</td>
                                    <td><Nation row={r} /></td>
                                    <td className="os-mono">{r.renown}</td>
                                    <td><Reasons row={r} /></td>
                                </tr>
                            );
                        })}
                    </tbody>
                </table>
            </div>
            {jumpButton ? <div className={styles.pad}>{jumpButton}</div> : null}
        </div>
    );
}

/** 오르는 경로 · 떨어지는 경로(서버 RenownEventKind 라벨). */
export function RenownPaths() {
    return (
        <div className={styles.paths}>
            <div className={styles.pathCol}>
                <span className={styles.up}>오르는 경로</span>
                <span className={styles.chips}>{RENOWN_RISING.map((l) => <Chip key={l} tone="moss">{l}</Chip>)}</span>
            </div>
            <div className={styles.pathCol}>
                <span className={styles.down}>떨어지는 경로</span>
                <span className={styles.chips}>{RENOWN_FALLING.map((l) => <Chip key={l} tone="rust">{l}</Chip>)}</span>
            </div>
        </div>
    );
}

export interface MyRenownProps {
    readonly self: NonNullable<Yuedan['self']>;
    readonly pending: readonly RenownPendingEvent[] | undefined;
    readonly compact?: boolean;
}

/** 내 명망(보드 「내 명망」) — 큰 숫자 · 초과 칩 · 부 코스트 합 / 코스트 상한 막대 · 다음 월단평에 반영될 일. */
export function MyRenown({ self, pending, compact = false }: MyRenownProps) {
    const { renown, retinueCost, overCapacity } = self;
    const ratio = renown != null && retinueCost != null && renown > 0 ? Math.min(1, retinueCost / renown) : 0;
    const num = (v: number | null) => (v == null ? '—' : String(v));
    return (
        <div className={compact ? styles.meCompact : styles.me} data-testid="my-renown">
            <div className={styles.meHead}>
                {compact ? <span className={styles.label}>내 명망</span> : null}
                <span className={`os-mono ${styles.meNumber}`}>{num(renown)}</span>
                {overCapacity ? <Chip tone="rust">{compact ? '코스트 초과' : '코스트 초과 — 이탈 판정 대상'}</Chip> : null}
            </div>
            <div className={styles.meRow}>
                <span className={styles.label}>부 코스트 합 / 코스트 상한</span>
                <span className="os-mono">{`${num(retinueCost)} / ${num(renown)}`}</span>
            </div>
            <div
                className={styles.bar}
                role="meter"
                aria-label="부 코스트 합 / 코스트 상한"
                aria-valuemin={0}
                aria-valuemax={renown ?? undefined}
                aria-valuenow={retinueCost ?? undefined}
                data-over={overCapacity || undefined}
            >
                <i style={{ width: `${Math.round(ratio * 100)}%` }} />
            </div>
            {compact ? null : <span className={styles.muted}>명망이 곧 거느릴 수 있는 부 코스트의 상한입니다.</span>}
            <span className={styles.label}>다음 월단평에 반영될 일</span>
            <span className={styles.chips}>
                {pending && pending.length > 0
                    ? pending.map((e, i) => {
                        const c = pendingChip(e);
                        return <Chip key={`${e.kind}-${i}`} tone={c.tone}>{c.text}</Chip>;
                    })
                    : <span className={styles.muted}>아직 없습니다</span>}
            </span>
        </div>
    );
}

export interface DepartureOrderProps {
    readonly rows: readonly DepartureRow[];
    readonly overCapacity: boolean;
    readonly retinueHref?: string;
}

/**
 * 이탈 판정 순서(보드 「상한을 넘으면 충성이 낮은 인물부터」). 상한 안이면 「상한 안 — 이탈 판정 없음」
 * (옛 화면은 0명이어도 같은 문구였다). 인물을 누르면 인물 상세는 화면이 고리로 준다.
 */
export function DepartureOrder({ rows, overCapacity, retinueHref }: DepartureOrderProps) {
    return (
        <div className={styles.departure}>
            {!overCapacity || rows.length === 0 ? (
                <p className={styles.muted} role="status">{overCapacity ? '이탈 판정 순서를 아직 받지 못했습니다.' : '상한 안 — 이탈 판정 없음'}</p>
            ) : (
                <ol className={styles.depList} aria-label="이탈 판정 순서">
                    {rows.map((d) => (
                        <li key={d.retainerId} className={styles.depRow}>
                            <span className={`os-mono ${styles.muted}`}>{d.order}</span>
                            <Portrait picture={d.picture} imageServer={d.imageServer} size="icon-28" alt="" />
                            <span className="os-serif" style={{ fontWeight: 700, flex: 1 }}>{d.name}</span>
                            <Chip tone={loyaltyTone(d.loyalty)}>{`충성 ${d.loyalty}`}</Chip>
                            <span className="os-mono">{`코스트 ${d.cost ?? '—'}`}</span>
                        </li>
                    ))}
                </ol>
            )}
            {retinueHref ? <Link href={retinueHref} className={styles.link}>부 편성으로 →</Link> : null}
        </div>
    );
}
