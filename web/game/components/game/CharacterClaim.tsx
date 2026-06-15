'use client';

// CharacterClaim — 장수 선택 / 빙의 screen (spec §6 entrance char states).
// Shown when front-info general.hasGeneral === false. Lists api.claimable().candidates
// (name · nation · 통/무/지) → api.claim(generalId) → on success calls onClaimed() so the caller
// bumps the front-info refreshKey and enters the game. 409/blocked reasons are surfaced (ClaimResponse
// carries result:false + reason; the proxy returns 409 as a thrown Error, also handled).

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { api } from '@/lib/api';
import { formatNumber } from '@/lib/format';
import { resolveServerGamePath } from '@/lib/serverGameUrl';
import type { ClaimableGeneral, FrontGlobalInfo } from '@/lib/types';

export default function CharacterClaim({ global, onClaimed }: { global: FrontGlobalInfo; onClaimed: () => void }) {
    const [candidates, setCandidates] = useState<ClaimableGeneral[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [claiming, setClaiming] = useState<number | null>(null);
    const npcMode = global.npcMode ?? 1;
    const blockGeneralCreate = global.blockGeneralCreate ?? 0;
    const serverId = global.serverId;
    const canCreate = (blockGeneralCreate & 1) === 0;
    const canSelectNpc = npcMode === 1;
    const canSelectPool = npcMode === 2;

    const joinHref = serverId
        ? resolveServerGamePath(undefined, serverId, '/game', 'join')
        : '/game/join';
    const selectPoolHref = serverId
        ? resolveServerGamePath(undefined, serverId, '/game', 'select-pool')
        : '/game/select-pool';

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
            <h1>장수 등록</h1>
            <p className="page-subtitle">서버 설정에 맞는 등록 방식을 선택하세요.</p>

            <div className="claim-mode-grid">
                <div className={`claim-mode-card game-card ${canCreate ? '' : 'is-disabled'}`}>
                    <h2>장수 생성</h2>
                    <p>새 장수를 만들어 재야에서 시작합니다.</p>
                    {canCreate ? (
                        <Link className="claim-mode-action" href={joinHref}>
                            생성 화면
                        </Link>
                    ) : (
                        <span className="claim-mode-blocked">직접 생성 금지 모드</span>
                    )}
                </div>

                <div className={`claim-mode-card game-card ${canSelectNpc ? '' : 'is-disabled'}`}>
                    <h2>NPC 빙의</h2>
                    <p>기존 NPC 장수를 선택해 플레이합니다.</p>
                    <span className={canSelectNpc ? 'claim-mode-active' : 'claim-mode-blocked'}>
                        {canSelectNpc ? '현재 서버 모드' : `현재 npcmode=${npcMode}`}
                    </span>
                </div>

                <div className={`claim-mode-card game-card ${canSelectPool ? '' : 'is-disabled'}`}>
                    <h2>장수 선택</h2>
                    <p>서버가 준비한 장수 풀에서 선택하거나 조정합니다.</p>
                    {canSelectPool ? (
                        <Link className="claim-mode-action" href={selectPoolHref}>
                            선택 화면
                        </Link>
                    ) : (
                        <span className="claim-mode-blocked">선택 풀 모드 아님</span>
                    )}
                </div>
            </div>

            {error && (
                <div className="claim-error" role="alert">
                    {error}
                </div>
            )}

            {canSelectNpc ? (
                loading ? (
                    <div className="center-screen">
                        <div className="spinner" />
                    </div>
                ) : candidates.length === 0 ? (
                    <div className="error-state">
                        <p>빙의 가능한 장수가 없습니다.</p>
                        <button onClick={() => void load()}>다시 시도</button>
                    </div>
                ) : (
                    <>
                        <h2 className="claim-section-title">빙의 가능 장수</h2>
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
                                        <span>정치 {c.politics ?? '-'}</span>
                                        <span>매력 {c.charm ?? '-'}</span>
                                    </div>
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
                    </>
                )
            ) : (
                <div className="claim-mode-note">
                    이 서버는 NPC 빙의 모드가 아닙니다. 위의 등록 방식을 사용하세요.
                </div>
            )}

            <p className="text-muted claim-hint">선택한 장수의 능력치: 통솔 / 무력 / 지력</p>
            <p className="text-muted claim-hint">
                {canSelectNpc ? `${formatNumber(candidates.length)}명의 장수가 빙의를 기다리고 있습니다.` : '빙의 목록은 NPC 빙의 모드에서만 표시됩니다.'}
            </p>
        </div>
    );
}
