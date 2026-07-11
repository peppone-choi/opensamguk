'use client';

import { useEffect, useState } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import { api, isIntakeDenied, isIntakeQueued } from '../../../lib/api';
import { onPortraitError, portraitUrl } from '../../../lib/portrait';
import type { IntakeOutcome } from '../../../lib/types';
import type { SelectPoolCard, SelectPoolResponse } from '../../../lib/api';

interface StatEdit {
    leadership: string;
    strength: string;
    intel: string;
}

function numberOrUndefined(value: string | undefined): number | undefined {
    if (value == null || value.trim() === '') return undefined;
    const parsed = Number(value);
    return Number.isFinite(parsed) ? Math.trunc(parsed) : undefined;
}

function initialEdit(card: SelectPoolCard): StatEdit {
    return {
        leadership: String(card.leadership ?? 15),
        strength: String(card.strength ?? 15),
        intel: String(card.intel ?? 15),
    };
}

export default function SelectPoolPage() {
    const [pool, setPool] = useState<SelectPoolResponse | null>(null);
    const [edits, setEdits] = useState<Record<string, StatEdit>>({});
    const [loading, setLoading] = useState<string | null>(null);
    const [status, setStatus] = useState('');
    const [error, setError] = useState('');

    useEffect(() => {
        let alive = true;
        const applyPool = (response: SelectPoolResponse) => {
            if (!alive) return;
            setPool(response);
            setEdits(Object.fromEntries(response.pick.map((card) => [card.uniqueName, initialEdit(card)])));
        };
        const load = async () => {
            const response = await api.selectPool();
            if (response.pick.length > 0) {
                applyPool(response);
                return;
            }
            setStatus('장수 후보를 준비하고 있습니다.');
            const accepted = await api.refreshSelectPool();
            for (let attempt = 0; attempt < 20; attempt += 1) {
                await new Promise((resolve) => setTimeout(resolve, 300));
                const result = await api.commandResult(accepted.requestId);
                if (result.status === 'PENDING') continue;
                if (!result.ok) throw new Error(result.reason ?? '장수 후보를 준비하지 못했습니다.');
                const refreshed = await api.selectPool();
                applyPool(refreshed);
                if (alive) setStatus('');
                return;
            }
            throw new Error('장수 후보 준비가 지연되고 있습니다. 잠시 후 다시 시도하세요.');
        };
        load().catch((e) => {
                if (!alive) return;
                setError(e instanceof Error ? e.message : '장수 후보를 불러올 수 없습니다.');
                setStatus('');
            });
        return () => {
            alive = false;
        };
    }, []);

    function updateStat(uniqueName: string, key: keyof StatEdit, value: string) {
        setEdits((current) => ({
            ...current,
            [uniqueName]: {
                ...(current[uniqueName] ?? { leadership: '15', strength: '15', intel: '15' }),
                [key]: value,
            },
        }));
    }

    async function submit(card: SelectPoolCard) {
        if (pool == null) return;
        const action = pool.generalId == null ? 'pick' : 'update';
        const edit = edits[card.uniqueName];
        const payload = {
            uniqueName: card.uniqueName,
            leadership: card.statEditable && action === 'pick' ? numberOrUndefined(edit?.leadership) : undefined,
            strength: card.statEditable && action === 'pick' ? numberOrUndefined(edit?.strength) : undefined,
            intel: card.statEditable && action === 'pick' ? numberOrUndefined(edit?.intel) : undefined,
            personalityName: undefined,
            useOwnPicture: false,
        };
        setLoading(card.uniqueName);
        setStatus('');
        setError('');
        try {
            const outcome: IntakeOutcome = action === 'pick'
                ? await api.commands.selectPoolPick(payload, 0)
                : await api.commands.selectPoolUpdate(payload, pool.generalId!);
            if (isIntakeQueued(outcome)) {
                setStatus(action === 'pick' ? '선택 요청이 접수되었습니다.' : '변경 요청이 접수되었습니다.');
            } else if (isIntakeDenied(outcome)) {
                setError(outcome.reason);
            } else {
                setError('요청 결과를 확인할 수 없습니다.');
            }
        } catch (e) {
            setError(e instanceof Error ? e.message : '장수 선택 요청에 실패했습니다.');
        } finally {
            setLoading(null);
        }
    }

    return (
        <Shell>
            <div className="page-content claim-screen">
                <h1>장수 선택</h1>
                {pool?.validUntil && <p className="page-subtitle">선택 기한 {pool.validUntil}</p>}
                {error && <div className="claim-error" role="alert">{error}</div>}
                {status && <div className="claim-mode-active" role="status">{status}</div>}
                {pool == null && !error && <p>후보를 불러오는 중입니다.</p>}
                {pool?.pick.length === 0 && <p>현재 선택할 수 있는 장수가 없습니다.</p>}
                {pool && pool.pick.length > 0 && (
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: 'var(--space-md)' }}>
                        {pool.pick.map((card) => {
                            const edit = edits[card.uniqueName] ?? initialEdit(card);
                            const stats = [card.leadership, card.strength, card.intel, card.politics, card.charm]
                                .map((value) => value ?? '-')
                                .join(' / ');
                            return (
                                <GameCard key={card.uniqueName}>
                                    <article style={{ display: 'grid', gap: 'var(--space-sm)' }}>
                                        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-md)' }}>
                                            <img
                                                src={portraitUrl(card.picture, card.imageServer)}
                                                alt=""
                                                width={64}
                                                height={64}
                                                onError={onPortraitError}
                                                style={{ width: 64, height: 64, objectFit: 'cover' }}
                                            />
                                            <div>
                                                <h2 style={{ margin: 0, fontSize: 'var(--text-lg)' }}>{card.generalName}</h2>
                                                <div>{stats}</div>
                                            </div>
                                        </div>
                                        <div>숙련 {card.dex.map((value) => Math.trunc(value / 1000)).join(' / ')}</div>
                                        <div>성격 {card.personality ?? '-'} · 내정 {card.specialDomestic ?? '-'} · 전투 {card.specialWar ?? '-'}</div>
                                        {card.statEditable && pool.generalId == null && (
                                            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 'var(--space-xs)' }}>
                                                <label>통솔<input aria-label={`${card.generalName} 통솔`} type="number" min={15} max={80} value={edit.leadership} onChange={(e) => updateStat(card.uniqueName, 'leadership', e.target.value)} /></label>
                                                <label>무력<input aria-label={`${card.generalName} 무력`} type="number" min={15} max={80} value={edit.strength} onChange={(e) => updateStat(card.uniqueName, 'strength', e.target.value)} /></label>
                                                <label>지력<input aria-label={`${card.generalName} 지력`} type="number" min={15} max={80} value={edit.intel} onChange={(e) => updateStat(card.uniqueName, 'intel', e.target.value)} /></label>
                                            </div>
                                        )}
                                        <button type="button" disabled={loading != null} onClick={() => void submit(card)}>
                                            {loading === card.uniqueName
                                                ? '요청 중...'
                                                : `${card.generalName}${pool.generalId == null ? ' 선택' : '로 변경'}`}
                                        </button>
                                    </article>
                                </GameCard>
                            );
                        })}
                    </div>
                )}
            </div>
        </Shell>
    );
}
