'use client';

import Link from 'next/link';
import type { EntrustedCell, EntrustedKey } from '@/lib/war-room-view';
import styles from './warroom.module.css';

export interface EntrustedTasksProps {
    readonly cells: readonly EntrustedCell[];
    /** 칸마다 가는 곳 — 출병 → 군단(P-C01) · 배치 · 방침 · 공사 → 영지(P-T01) · 계책 → 계책 덱 · 발령 → 조정(P-K01). */
    readonly hrefOf: (key: EntrustedKey) => string;
}

/**
 * 「맡겨 둔 일」 3 × 2 칸(보드 V31K4WarRoom 12순 열 아래, 높이 108) — 칸 전체가 고리(44 이상).
 * 받기 전은 「—」, 내 응답이 필요하면 적갈 칩. 입력은 보내지 않는다(이동만).
 */
export function EntrustedTasks({ cells, hrefOf }: EntrustedTasksProps) {
    return (
        <nav className={styles.entrusted} aria-label="맡겨 둔 일">
            {cells.map((c) => (
                <Link key={c.key} href={hrefOf(c.key)} className={styles.cell} data-key={c.key}>
                    <span className={styles.cellLabel}>{c.label}</span>
                    <span className={`os-mono ${styles.cellValue}`}>{c.value}</span>
                    {c.alert ? <span className="os-chip os-chip--rust">{`내 응답 필요 ${c.alert}`}</span> : null}
                </Link>
            ))}
        </nav>
    );
}
