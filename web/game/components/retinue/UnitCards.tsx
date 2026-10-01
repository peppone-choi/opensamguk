'use client';

import { Chip } from '@opensamguk/ui';
import type { UnitRow } from '@/lib/retinue-view';
import styles from './retinue.module.css';

/**
 * 부대 카드(보드 unit_cards) — 병력 · 훈련 · 사기 · 피로 · 쌀 n달 분 · 지휘. 지휘 인물이 없으면 움직일 수 없다(적갈).
 * 실명 부대 「유일」 칩은 서버가 표지를 주면 붙인다(K8 요청) — 지금은 짓지 않는다.
 */
export function UnitCards({ units }: { readonly units: readonly UnitRow[] }) {
    return (
        <ul className={styles.units} aria-label="부대 카드">
            {units.map((u) => (
                <li key={u.id} className={styles.unit} data-unit-id={u.id}>
                    <div className={styles.unitHead}>
                        <span className="os-serif" style={{ fontWeight: 700 }}>{u.name}</span>
                        <Chip>{u.crewTypeName}</Chip>
                        <span className={u.commander ? styles.muted : styles.warn} style={{ marginLeft: 'auto' }}>
                            {u.commander ? `지휘 ${u.commander}` : '지휘 없음 — 움직일 수 없음'}
                        </span>
                    </div>
                    <span className={`os-mono ${styles.unitNums}`}>
                        {`병력 ${u.troops} · 훈련 ${u.training} · 사기 ${u.morale} · 피로 ${u.fatigue} · 쌀 ${u.provisionMonths}달 분`}
                    </span>
                </li>
            ))}
        </ul>
    );
}
