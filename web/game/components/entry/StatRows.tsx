'use client';

// 새 장수 만들기(P-E02) 다섯 능력 칸 — − / + · 값 칸 · 고르게 · 남은 점수 줄. 규칙은 생성 옵션(K5-02) statRule.
import { useEffect, useState } from 'react';
import { Button } from '@opensamguk/ui';
import type { CreationStats, GeneralCreationOptions } from '@/lib/creation-contract';
import { bumpStat, evenStats, STAT_KEYS, statSum } from '@/lib/create-view';
import { STAT_LABELS } from '@/lib/historical-view';
import styles from './creation.module.css';

const LABEL = Object.fromEntries(STAT_LABELS.map((s) => [s.key, s.label])) as Record<keyof CreationStats, string>;

/**
 * 능력 값 칸 — 치는 동안은 친 글자를 그대로 들고, 범위 안 정수가 되면 곧바로 반영한다(합 · 미리보기가 따라온다).
 * 범위 밖 · 빈 칸은 칸을 떠날 때 · Enter 에서 자르거나(범위 밖) 되돌린다(빈 칸). 치는 중에 자르면 「7」이 20 이 되어 75 를 칠 수 없다(#1329 리뷰).
 */
function StatValue({ label, value, rule, onValue }: {
    readonly label: string; readonly value: number; readonly rule: GeneralCreationOptions['statRule']; readonly onValue: (n: number) => void;
}) {
    const [text, setText] = useState<string | null>(null);
    // 바깥(−/+ · 고르게)에서 값이 바뀌면 치던 글자를 버린다 — 친 값이 반영돼 같아진 것은 그대로 둔다
    useEffect(() => { setText((t) => (t !== null && Number(t) !== value ? null : t)); }, [value]);
    const commit = () => {
        if (text === null) return;
        // 빈 칸은 NaN — bumpStat 이 원래 값을 둔다
        onValue(text.trim() === '' ? Number.NaN : Number(text));
        setText(null);
    };
    return (
        <input type="number" inputMode="numeric" className={`os-input ${styles.statValue}`} aria-label={label} min={rule.min} max={rule.max}
            value={text ?? String(value)}
            onChange={(e) => {
                const raw = e.target.value;
                setText(raw);
                const n = Number(raw);
                if (raw.trim() !== '' && Number.isInteger(n) && n >= rule.min && n <= rule.max) onValue(n);
            }}
            onBlur={commit}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); commit(); } }} />
    );
}

export default function StatRows({ rule, stats, setStats }: { readonly rule: GeneralCreationOptions['statRule']; readonly stats: CreationStats; readonly setStats: (s: CreationStats) => void }) {
    const left = rule.total - statSum(stats);
    return (
        <div className={styles.statsEdit}>
            {STAT_KEYS.map((key) => {
                const v = stats[key];
                const pct = Math.round(((v - rule.min) / Math.max(1, rule.max - rule.min)) * 100);
                return (
                    <div key={key} className={styles.statEdit}>
                        <span className={styles.statName}>{LABEL[key]}</span>
                        {v <= rule.min
                            ? <Button variant="ghost" disabled reason={`${LABEL[key]}은 ${rule.min}보다 낮출 수 없습니다`} aria-label={`${LABEL[key]} 내리기`}>−</Button>
                            : <Button variant="ghost" aria-label={`${LABEL[key]} 내리기`} onClick={() => setStats(bumpStat(stats, key, v - 1, rule))}>−</Button>}
                        <span className={styles.statBar} aria-hidden="true"><i style={{ width: `${pct}%` }} /></span>
                        {v >= rule.max
                            ? <Button variant="ghost" disabled reason={`${LABEL[key]}은 ${rule.max}보다 올릴 수 없습니다`} aria-label={`${LABEL[key]} 올리기`}>+</Button>
                            : <Button variant="ghost" aria-label={`${LABEL[key]} 올리기`} onClick={() => setStats(bumpStat(stats, key, v + 1, rule))}>+</Button>}
                        <StatValue label={`${LABEL[key]} 값`} value={v} rule={rule} onValue={(n) => setStats(bumpStat(stats, key, n, rule))} />
                    </div>
                );
            })}
            <div className={styles.statFoot}>
                <span className={left === 0 ? styles.okLine : styles.errLine} role="status">
                    {left === 0 ? `합 ${rule.total} — 맞습니다` : left > 0 ? `${left}점이 남았습니다 — 합이 ${rule.total}이어야 합니다` : `${-left}점이 넘칩니다 — 합이 ${rule.total}이어야 합니다`}
                </span>
                <Button variant="ghost" onClick={() => setStats(evenStats(rule))}>고르게</Button>
            </div>
            <p className={styles.muted}>각 능력은 {rule.min}–{rule.max}, 합계는 {rule.total}입니다.</p>
        </div>
    );
}
