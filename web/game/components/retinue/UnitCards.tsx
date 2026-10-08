'use client';

import { Chip } from '@opensamguk/ui';
import type { UnitRow } from '@/lib/retinue-view';
import styles from './retinue.module.css';

/**
 * Unit cards show deputy assignment independently of deployment availability.
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
                        <span className={u.commanderRetainerId != null && !u.commander ? styles.warn : styles.muted} style={{ marginLeft: 'auto' }}>
                            {u.commander ? `지휘 ${u.commander}` : u.commanderRetainerId != null
                                ? '지휘 인물 확인 필요' : '본인 지휘 — 출병 조건 확인'}
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
