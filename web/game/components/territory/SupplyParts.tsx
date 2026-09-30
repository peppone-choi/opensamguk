'use client';

import Link from 'next/link';
import { Chip, InputAction, StatusView, withParticle, type InputAvailability } from '@opensamguk/ui';
import { CAMPAIGN_RESOURCE_LABELS, type Stock } from '@/lib/campaign-reads';
import { WAREHOUSE_KIND_LABEL, cutRows, stockCell, stockLine, type WarehouseRow } from '@/lib/supply-view';
import styles from './territory.module.css';

/** 표 아래 안내(설계서 P-T04 — 은퇴어 「국고」 대신). */
export const CAPITAL_NOTE = '세력의 금은 수도 창고에 있습니다. 수도가 함락되면 빼앗깁니다.';

function KindChip({ row }: { readonly row: WarehouseRow }) {
    return <Chip tone={row.kind === 'capital' ? 'bronze' : 'neutral'}>{WAREHOUSE_KIND_LABEL[row.kind]}</Chip>;
}

export interface WarehouseTableProps {
    readonly rows: readonly WarehouseRow[];
    readonly total: Stock | null;
    /** 저장된 값을 읽지 못해 빠진 창고 수(서버 invalidCount). */
    readonly invalidCount?: number;
    readonly mobile?: boolean;
}

/**
 * 창고별 재고(보드 V31K4Supply 왼쪽 · V31K4MSupply 카드) — 창고 · 구분 · 금 쌀 철 목재 말 · 망(본망 · 끊김), 수도 먼저, 행 44.
 * 합계는 본망만. 읽지 못한 창고는 점선 경고 한 줄.
 */
export function WarehouseTable({ rows, total, invalidCount = 0, mobile = false }: WarehouseTableProps) {
    const warn = invalidCount > 0
        ? <p className={styles.alert} role="alert">{`읽지 못한 창고 ${invalidCount}곳 — 창고 기록이 깨져 합계에서 뺐습니다.`}</p>
        : null;
    if (mobile) {
        return (
            <div className={styles.whMobile}>
                {total ? (
                    <div className={styles.totalCard} data-testid="connected-total">
                        <span className={styles.muted}>본망 합계</span>
                        <span className="os-mono">{stockLine(total)}</span>
                    </div>
                ) : null}
                <ul className={styles.whCards} aria-label="창고">
                    {rows.map((r) => (
                        <li key={r.cityId} className={styles.whCard} data-cut={!r.connected || undefined}>
                            <span className={styles.chips}>
                                <span className="os-serif" style={{ fontWeight: 700 }}>{r.name}</span>
                                <KindChip row={r} />
                                {r.connected ? null : <Chip tone="rust">끊김</Chip>}
                            </span>
                            <span className={`os-mono ${styles.muted}`}>{stockLine(r.stock)}</span>
                        </li>
                    ))}
                </ul>
                {warn}
                <p className={styles.note}>{CAPITAL_NOTE}</p>
            </div>
        );
    }
    return (
        <div className={styles.whDesk}>
            <div className="os-table-wrap">
                <table className="os-table os-table--nowrap">
                    <thead>
                        <tr>
                            <th scope="col">창고</th>
                            <th scope="col">구분</th>
                            {CAMPAIGN_RESOURCE_LABELS.map((r) => <th key={r.key} scope="col">{r.label}</th>)}
                            <th scope="col">망</th>
                        </tr>
                    </thead>
                    <tbody>
                        {rows.map((r) => (
                            <tr key={r.cityId} className={styles.whRow} data-cut={!r.connected || undefined}>
                                <td><span className="os-serif" style={{ fontWeight: 700 }}>{r.name}</span></td>
                                <td><KindChip row={r} /></td>
                                {CAMPAIGN_RESOURCE_LABELS.map(({ key }) => <td key={key} className="os-mono">{stockCell(r.stock[key])}</td>)}
                                <td className={r.connected ? styles.muted : styles.warn}>{r.connected ? '본망' : '끊김'}</td>
                            </tr>
                        ))}
                        {total ? (
                            <tr className={styles.whTotal}>
                                <td className={styles.muted}>합계(본망)</td>
                                <td />
                                {CAMPAIGN_RESOURCE_LABELS.map(({ key }) => <td key={key} className="os-mono">{stockCell(total[key])}</td>)}
                                <td />
                            </tr>
                        ) : null}
                    </tbody>
                </table>
            </div>
            {warn}
            <p className={styles.note}>{CAPITAL_NOTE}</p>
        </div>
    );
}

/**
 * 끊긴 곳(보드 「끊긴 곳 n」) — 창고마다 「수도와 끊겨 제 창고만 씁니다」 · 끊긴 까닭 준비 중(K4-06) · 지도에서 보기.
 * 없으면 「모든 창고가 수도와 이어져 있습니다」.
 */
export function CutPanel({ rows, mapHref }: { readonly rows: readonly WarehouseRow[]; readonly mapHref?: (cityId: number) => string }) {
    const cuts = cutRows(rows);
    if (cuts.length === 0) return <p className={styles.ok} role="status">모든 창고가 수도와 이어져 있습니다.</p>;
    return (
        <ul className={styles.rows} aria-label="끊긴 곳">
            {cuts.map((r) => (
                <li key={r.cityId} className={styles.workRow}>
                    <span className={styles.chips}>
                        <span className="os-serif" style={{ fontWeight: 700 }}>{r.name}</span>
                        <Chip tone="rust">끊김</Chip>
                    </span>
                    <span className={styles.muted}>{`${withParticle(r.name, '은/는')} 수도와 끊겨 제 창고만 씁니다.`}</span>
                    <span className={styles.chips} data-waiting="cut-reason">
                        <span className={styles.muted}>끊긴 까닭</span>
                        <Chip tone="info">준비 중</Chip>
                    </span>
                    {mapHref ? <Link href={mapHref(r.cityId)} className={styles.link}>지도에서 보기 — 보급선 켜고 →</Link> : null}
                </li>
            ))}
        </ul>
    );
}

/** 녹봉 · 부대 유지비 전망 — 서버 읽기(K4-14) 전까지 서버 대기 A(영역 전체). */
export function UpkeepWaiting() {
    return (
        <StatusView
            kind="waiting"
            title="지급 전망 — 준비 중"
            body="녹봉 · 유지비는 카드가 있는 곳의 망에서 나갑니다. 못 받을 사람 · 부대 목록은 서버가 아직 주지 않습니다."
        />
    );
}

export interface TransportPanelProps {
    readonly generalName: string;
    readonly availability: InputAvailability | null;
    readonly onTransport: () => void;
}

/**
 * 물자조달(`action.transport`, 직접 행동) — 지금 규칙: 내 장수가 선 현의 창고 → 이웃한 우리 현 창고로 한 자원을 그 순에 바로.
 * 호위 · 지연 수송은 규칙이 없다(계약판 K4-17 새 규칙 제안) — inputId 를 짓지 않고 칩 한 줄.
 */
export function TransportPanel({ generalName, availability, onTransport }: TransportPanelProps) {
    return (
        <div className={styles.transport}>
            <p className={styles.note}>{`${withParticle(generalName, '이/가')} 선 현의 창고에서 이웃한 우리 현 창고로 한 자원을 옮깁니다. 그 순에 바로 옮겨집니다.`}</p>
            <InputAction inputId="action.transport" availability={availability} label="물자조달 — 명령 목록에 넣기" onAct={onTransport} block />
            <span className={styles.chips} data-waiting="escort-transport">
                <Chip tone="info">호위 · 지연 수송 — 규칙 없음</Chip>
            </span>
            <p className={styles.note}>망 밖 원조 · 고립지 구출처럼 멀리 호위해 보내는 수송은 아직 규칙이 없습니다.</p>
        </div>
    );
}
