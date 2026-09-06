'use client';
// Phase 4X-C 감찰부 「리플레이」 열 (spec v4.1 §7) — 내 국가가 공격·수비한 리플레이 목록. 없으면 점선 「기록 없음(계획 미봉인)」.
import { useEffect, useState } from 'react';
import { Chip, Panel, SectionHeader, type ChipTone } from '@opensamguk/ui';
import { api } from '../../lib/api';
import type { BattleReplaySummary } from '../../types/game';

const RESULT_TONE: Record<string, ChipTone> = { conquered: 'bronze', defenders_down: 'moss', retreat: 'rust', repelled: 'neutral' };

export default function BattleReplayList({ hrefFor, scope = 'nation' }: { readonly hrefFor: (id: number) => string; readonly scope?: 'nation' | 'mine' }) {
    const [rows, setRows] = useState<BattleReplaySummary[] | null>(null);
    const [error, setError] = useState('');
    useEffect(() => {
        Promise.resolve().then(() => api.battleReplays<BattleReplaySummary[]>(scope)).then((r) => { setRows(r); setError(''); }).catch(() => setError('리플레이 목록을 불러올 수 없습니다.'));
    }, [scope]);
    return (
        <Panel className="replay-list" id="battle-replays" aria-label="리플레이">
            <SectionHeader title="리플레이" tone="rust" sub={rows ? `${rows.length}` : undefined} />
            {error ? (
                <p role="alert" className="record-empty" style={{ color: 'var(--rust)' }}>{error}</p>
            ) : rows == null ? (
                <p className="record-empty">불러오는 중...</p>
            ) : rows.length === 0 ? (
                <p className="record-empty replay-list__none" title="계획을 봉인한 출병만 기록됩니다">기록 없음(계획 미봉인)</p>
            ) : (
                <ul className="replay-list__rows">
                    {rows.map((r) => (
                        <li key={r.id} className="replay-list__row">
                            <span className="os-num">{r.year}年 {r.month}月</span>
                            <span>{r.attackerName} → {r.defenderCityName}</span>
                            <Chip tone={RESULT_TONE[r.result] ?? 'neutral'}>{r.resultLabel}</Chip>
                            <span className="os-num">-{r.attackerDead.toLocaleString()} / 적 -{r.defenderDead.toLocaleString()}</span>
                            <a className="os-button os-button--ghost os-button--sm" href={hrefFor(r.id)}>리플레이</a>
                        </li>
                    ))}
                </ul>
            )}
        </Panel>
    );
}
