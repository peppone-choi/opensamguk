'use client';

import { useEffect, useState } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import { api, type SelectPoolCard, type SelectPoolResponse } from '../../../lib/api';
import { submitCommandAndAwaitResult } from '../../../lib/commandSubmit';
import { onPortraitError, portraitUrl } from '../../../lib/portrait';

interface StatEdit {
    leadership: string;
    strength: string;
    intel: string;
}

interface SelectPoolCustomOptions {
    stat: boolean;
    personality: boolean;
    picture: boolean;
    personalities: Array<{ code: string; name: string }>;
}

interface SelectPoolPageResponse extends SelectPoolResponse {
    customOptions?: SelectPoolCustomOptions;
    member?: {
        name: string;
        canUsePicture: boolean;
    };
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
    const [pool, setPool] = useState<SelectPoolPageResponse | null>(null);
    const [edits, setEdits] = useState<Record<string, StatEdit>>({});
    const [personalities, setPersonalities] = useState<Record<string, string>>({});
    const [ownPictures, setOwnPictures] = useState<Record<string, boolean>>({});
    const [loadVersion, setLoadVersion] = useState(0);
    const [loading, setLoading] = useState<string | null>(null);
    const [status, setStatus] = useState('');
    const [error, setError] = useState('');

    useEffect(() => {
        let alive = true;
        const applyPool = (response: SelectPoolPageResponse) => {
            if (!alive) return;
            setPool(response);
            setEdits(Object.fromEntries(response.pick.map((card) => [card.uniqueName, initialEdit(card)])));
            setPersonalities(Object.fromEntries(response.pick.map((card) => [card.uniqueName, 'Random'])));
            setOwnPictures(Object.fromEntries(response.pick.map((card) => [card.uniqueName, false])));
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
    }, [loadVersion]);

    function updateStat(uniqueName: string, key: keyof StatEdit, value: string) {
        setEdits((current) => ({
            ...current,
            [uniqueName]: {
                ...(current[uniqueName] ?? { leadership: '15', strength: '15', intel: '15' }),
                [key]: value,
            },
        }));
    }

    function updatePersonality(uniqueName: string, value: string) {
        setPersonalities((current) => ({ ...current, [uniqueName]: value }));
    }

    function updateOwnPicture(uniqueName: string, checked: boolean) {
        setOwnPictures((current) => ({ ...current, [uniqueName]: checked }));
    }

    async function submit(card: SelectPoolCard) {
        if (pool == null) return;
        const action = pool.generalId == null ? 'pick' : 'update';
        const edit = edits[card.uniqueName];
        const canEditStats = action === 'pick' && card.statEditable && (pool.customOptions?.stat ?? true);
        const canSelectPersonality = action === 'pick' && pool.customOptions?.personality === true;
        const canUseOwnPicture = action === 'pick' && pool.customOptions?.picture === true && pool.member?.canUsePicture === true;
        const leadership = canEditStats ? numberOrUndefined(edit?.leadership) : undefined;
        const payload = {
            uniqueName: card.uniqueName,
            leadership,
            strength: leadership,
            intel: leadership,
            personalityName: canSelectPersonality ? (personalities[card.uniqueName] ?? 'Random') : undefined,
            useOwnPicture: canUseOwnPicture && ownPictures[card.uniqueName] === true,
        };
        setLoading(card.uniqueName);
        setStatus('');
        setError('');
        try {
            const outcome = await submitCommandAndAwaitResult(() =>
                action === 'pick'
                    ? api.commands.selectPoolPick(payload, 0)
                    : api.commands.selectPoolUpdate(payload, pool.generalId!),
            );
            if (outcome.status === 'applied') {
                setStatus(action === 'pick' ? '선택이 처리되었습니다.' : '변경이 처리되었습니다.');
                setLoadVersion((current) => current + 1);
            } else if (outcome.status === 'rejected') {
                setError(outcome.reason ?? '요청 결과를 확인할 수 없습니다.');
            } else {
                setError(outcome.reason);
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
                            const canEditStats = pool.generalId == null && card.statEditable && (pool.customOptions?.stat ?? true);
                            const canSelectPersonality = pool.generalId == null && pool.customOptions?.personality === true;
                            const canUseOwnPicture = pool.generalId == null && pool.customOptions?.picture === true && pool.member?.canUsePicture === true;
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
                                                style={{ width: 64, height: 64, objectFit: 'contain' }}
                                            />
                                            <div>
                                                <h2 style={{ margin: 0, fontSize: 'var(--text-lg)' }}>{card.generalName}</h2>
                                                <div>{stats}</div>
                                            </div>
                                        </div>
                                        <div>숙련 {card.dex.map((value) => Math.trunc(value / 1000)).join(' / ')}</div>
                                        <div>성격 {card.personality ?? '-'} · 내정 {card.specialDomestic ?? '-'} · 전투 {card.specialWar ?? '-'}</div>
                                        {canEditStats && (
                                            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 'var(--space-xs)' }}>
                                                <label>통솔<input aria-label={`${card.generalName} 통솔`} type="number" min={15} max={80} value={edit.leadership} onChange={(e) => updateStat(card.uniqueName, 'leadership', e.target.value)} /></label>
                                                <label>무력<input aria-label={`${card.generalName} 무력`} type="number" min={15} max={80} value={edit.strength} onChange={(e) => updateStat(card.uniqueName, 'strength', e.target.value)} /></label>
                                                <label>지력<input aria-label={`${card.generalName} 지력`} type="number" min={15} max={80} value={edit.intel} onChange={(e) => updateStat(card.uniqueName, 'intel', e.target.value)} /></label>
                                            </div>
                                        )}
                                        {canSelectPersonality && (
                                            <label>
                                                성격
                                                <select aria-label={`${card.generalName} 성격`} value={personalities[card.uniqueName] ?? 'Random'} onChange={(e) => updatePersonality(card.uniqueName, e.target.value)}>
                                                    <option value="Random">임의</option>
                                                    {pool.customOptions?.personalities.map((option) => (
                                                        <option key={option.code} value={option.code}>{option.name}</option>
                                                    ))}
                                                </select>
                                            </label>
                                        )}
                                        {canUseOwnPicture && (
                                            <label>
                                                <input aria-label={`${card.generalName} 내 이미지 사용`} type="checkbox" checked={ownPictures[card.uniqueName] ?? false} onChange={(e) => updateOwnPicture(card.uniqueName, e.target.checked)} />
                                                내 이미지 사용
                                            </label>
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
