'use client';

import { Chip } from '@opensamguk/ui';
import type { RetinueRow } from '@/lib/retinue-view';
import { bondText } from './RetinueList';
import styles from './retinue.module.css';

export interface BondGroup {
    readonly text: string;
    readonly members: readonly string[];
    readonly withLord: boolean;
}

/** 서버가 준 결속을 같은 글자끼리 묶는다(향당 · 패국 초현 → 허저 · 이전). 순서는 처음 나온 순서. */
export function bondGroups(rows: readonly RetinueRow[]): BondGroup[] {
    const map = new Map<string, { members: string[]; withLord: boolean }>();
    for (const row of rows) {
        for (const b of row.bonds) {
            const key = bondText(b);
            const g = map.get(key) ?? { members: [], withLord: false };
            g.members.push(row.name);
            g.withLord ||= b.sameAsLord;
            map.set(key, g);
        }
    }
    return [...map].map(([text, g]) => ({ text, members: g.members, withLord: g.withLord }));
}

/**
 * 결속 칸(보드 bonds) — 지금 서버는 향당만 준다. 나머지 여섯 가지(혈연 · 은의 · 결의 · 명망 …)는 한 줄 「준비 중」.
 * 결속 효과(시너지) 문구는 서버가 주기 전엔 짓지 않는다.
 */
export function BondPanel({ rows, lordName }: { readonly rows: readonly RetinueRow[]; readonly lordName: string }) {
    const groups = bondGroups(rows);
    return (
        <ul className={styles.bonds} aria-label="결속">
            {groups.map((g) => (
                <li key={g.text} className={styles.bondRow}>
                    <Chip tone="bronze">{g.text}</Chip>
                    <span className={styles.muted} style={{ flex: 1 }}>{(g.withLord ? [lordName, ...g.members] : g.members).join(' · ')}</span>
                </li>
            ))}
            <li className={styles.bondRow} data-waiting="bond-kinds">
                <Chip>혈연 · 은의 · 결의 · 명망</Chip>
                <span className={styles.muted} style={{ flex: 1 }}>서버 준비 중</span>
                <Chip tone="info">준비 중</Chip>
            </li>
        </ul>
    );
}
