'use client';

// CharacterClaim — 장수 선택 / 빙의 screen (spec §6 entrance char states).
// Shown when front-info general.hasGeneral === false. Lists api.claimable().candidates
// (name · nation · 통/무/지) → api.claim(generalId) → on success calls onClaimed() so the caller
// bumps the front-info refreshKey and enters the game. 409/blocked reasons are surfaced (ClaimResponse
// carries result:false + reason; the proxy returns 409 as a thrown Error, also handled).

import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/api';
import { formatNumber } from '@/lib/format';
import type { ClaimableGeneral } from '@/lib/types';

export default function CharacterClaim({ onClaimed }: { onClaimed: () => void }) {
    const [candidates, setCandidates] = useState<ClaimableGeneral[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [claiming, setClaiming] = useState<number | null>(null);

    const load = useCallback(async () => {
        setLoading(true);
        setError(null);
        try {
            const res = await api.claimable();
            setCandidates(res.candidates ?? []);
        } catch {
            setError('빙의 가능한 장수 목록을 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        void load();
    }, [load]);

    const claim = useCallback(
        async (generalId: number) => {
            setClaiming(generalId);
            setError(null);
            try {
                const res = await api.claim(generalId);
                if (res.result) {
                    onClaimed();
                    return;
                }
                // result:false → server-supplied deny reason (PHP-faithful).
                setError(res.reason ?? '빙의에 실패했습니다.');
            } catch (e) {
                // proxy surfaces 409 as `409: Conflict`; show a friendly faithful message.
                const msg = e instanceof Error ? e.message : '';
                setError(msg.startsWith('409') ? '이미 다른 사용자가 선택한 장수입니다.' : '빙의 요청에 실패했습니다.');
            } finally {
                setClaiming(null);
            }
        },
        [onClaimed],
    );

    return (
        <div className="page-content claim-screen">
            <h1>장수선택</h1>
            <p className="page-subtitle">빙의할 장수를 선택하세요.</p>

            {error && (
                <div className="claim-error" role="alert">
                    {error}
                </div>
            )}

            {loading ? (
                <div className="center-screen">
                    <div className="spinner" />
                </div>
            ) : candidates.length === 0 ? (
                <div className="error-state">
                    <p>빙의 가능한 장수가 없습니다.</p>
                    <button onClick={() => void load()}>다시 시도</button>
                </div>
            ) : (
                <div className="claim-grid">
                    {candidates.map((c) => (
                        <div key={c.generalId} className="claim-card game-card">
                            <div className="claim-card-head">
                                <span className="claim-name">{c.name}</span>
                                <span className="claim-nation">{c.nationName ?? '재야'}</span>
                            </div>
                            <div className="claim-stats">
                                <span>통 {c.leadership}</span>
                                <span>무 {c.strength}</span>
                                <span>지 {c.intel}</span>
                            </div>
                            {/* legacy select_npc.ts 카드: 성격명 → 내정특기 / 전투특기 (officerLevel은 카드에 없음). */}
                            <div className="claim-meta">{c.personal ?? '-'}</div>
                            <div className="claim-meta">
                                {(c.special ?? '-')} / {(c.special2 ?? '-')}
                            </div>
                            <button
                                className="claim-btn"
                                disabled={claiming !== null}
                                onClick={() => void claim(c.generalId)}
                            >
                                {claiming === c.generalId ? '빙의 중…' : '빙의'}
                            </button>
                        </div>
                    ))}
                </div>
            )}

            <p className="text-muted claim-hint">선택한 장수의 능력치: 통솔 / 무력 / 지력</p>
            <p className="text-muted claim-hint">{formatNumber(candidates.length)}명의 장수가 빙의를 기다리고 있습니다.</p>
        </div>
    );
}
