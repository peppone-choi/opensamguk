'use client';

import { useEffect } from 'react';
import { TargetCandidateList, useTargetPicker, type TargetCandidate } from '@opensamguk/ui';
import styles from './territory.module.css';

/**
 * 도로 · 보루 인자 고르기(K3 후보 목록) — 고른 후보 id 를 위로 알린다. 지도에서 고르기는 K2 뒤(같은 picker 에 PickBar · 지도를 붙인다).
 * 후보가 바뀌면(다른 공사 · 다른 현) 고름을 비운다.
 */
export function RoadPicker({ label, candidates, onPick, onCancel }: {
    readonly label: string;
    readonly candidates: readonly TargetCandidate[];
    readonly onPick: (targetId: string | null) => void;
    readonly onCancel: () => void;
}) {
    const picker = useTargetPicker({ kind: 'place', candidates, onCancel });
    const chosen = picker.selected[0] ?? null;
    useEffect(() => { onPick(chosen); }, [chosen, onPick]);
    if (candidates.length === 0) return <p className={styles.muted}>이 현에서 고를 접경 · 길목이 없습니다.</p>;
    return <TargetCandidateList picker={picker} candidates={candidates} label={label} />;
}
