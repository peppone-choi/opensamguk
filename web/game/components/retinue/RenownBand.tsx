'use client';

import Link from 'next/link';
import { Chip } from '@opensamguk/ui';
import type { RenownBand as Band } from '@/lib/retinue-view';
import styles from './retinue.module.css';

export interface RenownBandProps {
    readonly band: Band;
    readonly people: number;
    readonly units: number;
    /** 월단평 화면 주소(`/game/<서버>/retinue/yuedan`). 없으면 고리를 그리지 않는다. */
    readonly yuedanHref?: string;
    /** 모바일 한 줄(명망 · 막대 · 초과 칩). */
    readonly compact?: boolean;
}

const num = (v: number | null) => (v == null ? '—' : String(v));

/**
 * 명망 띠(보드 V31K4Retinue 머리 띠) — 명망 = 거느릴 수 있는 부 코스트의 상한. 인물이 0이어도 보인다(상한은 있다).
 * 초과 문구의 달은 짓지 않는다 — 월단평 일정은 서버가 준 값이 생기면 붙인다.
 */
export function RenownBand({ band, people, units, yuedanHref, compact = false }: RenownBandProps) {
    const { renown, costSum, overCapacity, ratio } = band;
    const empty = people === 0;
    const meter = (
        <div
            className={styles.bar}
            role="meter"
            aria-label="부 코스트 합 / 명망 상한"
            aria-valuemin={0}
            aria-valuemax={renown ?? undefined}
            aria-valuenow={costSum ?? undefined}
            aria-valuetext={`${num(costSum)} / ${num(renown)}`}
            data-over={overCapacity || undefined}
        >
            <i style={{ width: `${Math.round((ratio ?? 0) * 100)}%` }} />
        </div>
    );

    if (compact) {
        return (
            <div className={styles.bandCompact} data-testid="renown-band">
                <span className={styles.label}>명망</span>
                <span className={`os-mono ${styles.renown}`}>{num(renown)}</span>
                {meter}
                {empty ? <Chip>인물 0</Chip> : overCapacity ? <Chip tone="rust">상한 초과</Chip> : null}
            </div>
        );
    }

    return (
        <div className={styles.band} data-testid="renown-band">
            <span className={styles.label}>명망</span>
            <span className={`os-mono ${styles.renown}`}>{num(renown)}</span>
            <span className={styles.label}>부 코스트 합 / 명망 상한</span>
            {meter}
            <span className="os-mono">{`${num(costSum)} / ${num(renown)}`}</span>
            {empty ? null : overCapacity ? (
                <Chip tone="rust">상한 초과 — 다음 월단평에 충성 낮은 인물부터 이탈 판정</Chip>
            ) : costSum != null && renown != null ? (
                <Chip tone="moss">상한 안</Chip>
            ) : null}
            <span className={styles.count}>{`인물 ${people} · 부대 ${units}`}</span>
            {yuedanHref ? <Link href={yuedanHref} className={styles.link}>월단평 →</Link> : null}
        </div>
    );
}
