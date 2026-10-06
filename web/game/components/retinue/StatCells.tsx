'use client';

import styles from './retinue.module.css';

type Stats = { readonly leadership: number; readonly strength: number; readonly intel: number; readonly politics: number; readonly charm: number };
type Aptitudes = { readonly command: number; readonly administration: number; readonly strategy: number; readonly envoy: number };

export const STAT_LABELS = [
    ['통솔', 'leadership'],
    ['무력', 'strength'],
    ['지력', 'intel'],
    ['정치', 'politics'],
    ['매력', 'charm'],
] as const;
export const APTITUDE_LABELS = [
    ['장 · 군단', 'command'],
    ['리 · 내정', 'administration'],
    ['사 · 계책', 'strategy'],
    ['사자 · 외교', 'envoy'],
] as const;

function Cells({ label, items }: { readonly label: string; readonly items: ReadonlyArray<readonly [string, string]> }) {
    return (
        <div className={styles.cells} role="group" aria-label={label} style={{ gridTemplateColumns: `repeat(${items.length}, minmax(0, 1fr))` }}>
            {items.map(([k, v]) => (
                <div key={k} className={`os-inset ${styles.cell}`}>
                    <span className={styles.cellKey}>{k}</span>
                    <span className="os-mono">{v}</span>
                </div>
            ))}
        </div>
    );
}

/**
 * 5능력 다섯 칸(보드 stat_grid). 값이 없을 때 글자 — 부 카드에 능력치가 없으면 「—」,
 * 인물 일람에서 시야 · 권한 밖이라 서버가 null 을 주면 「?」(설계서 P-R02).
 */
export function StatCells({ stats, missing = '—' }: { readonly stats: Stats | null; readonly missing?: string }) {
    return <Cells label="능력" items={STAT_LABELS.map(([k, f]) => [k, stats ? String(stats[f]) : missing] as const)} />;
}

/** 역할 적성 네 칸(보드 apt_grid) — 장 · 리 · 사 · 사자. */
export function AptitudeCells({ aptitudes, missing = '—' }: { readonly aptitudes: Aptitudes | null; readonly missing?: string }) {
    return <Cells label="역할 적성" items={APTITUDE_LABELS.map(([k, f]) => [k, aptitudes ? String(aptitudes[f]) : missing] as const)} />;
}
