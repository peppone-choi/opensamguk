'use client';

import { useEffect, useState } from 'react';
import { api } from '../../lib/api';
import { submitCommandAndAwaitResult } from '../../lib/commandSubmit';
import type { EnlistmentOptionsResponse } from '../../lib/types';

export function useRuleProfile(supplied?: string | null) {
    const [loaded, setLoaded] = useState<string | null>(null);
    useEffect(() => {
        if (supplied !== undefined) return;
        let active = true;
        Promise.resolve().then(() => api.frontInfo()).then(response => {
            if (active) setLoaded(response.global.ruleProfile ?? null);
        }).catch(() => { if (active) setLoaded(null); });
        return () => { active = false; };
    }, [supplied]);
    const value = supplied === undefined ? loaded : supplied;
    return value === 'SAMMO' || value === 'HWIHA' ? value : null;
}

export default function EnlistmentForm({ inputId = 'action.enlist', generalId, turnIdx, unavailable, onToast, onClose, onReserved }: {
    inputId?: 'action.enlist' | 'action.randomEnlist' | 'action.targetEnlist';
    generalId: number; turnIdx: number; unavailable: boolean;
    onToast: (message: string, type: 'success' | 'error' | 'info') => void;
    onClose: () => void; onReserved?: () => void;
}) {
    const [data, setData] = useState<EnlistmentOptionsResponse | null>(null);
    const [selection, setSelection] = useState('');
    const [reason, setReason] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    useEffect(() => {
        let active = true;
        setData(null); setSelection(''); setReason(null);
        if (!unavailable && Number.isInteger(turnIdx) && turnIdx >= 0 && turnIdx < 12) api.enlistmentOptions(generalId).then(value => {
            if (!active) return;
            if (!value.result || value.inputId !== 'action.enlist' || value.maxReservedTurns !== 12) {
                setReason('출사 정보를 확인하지 못했습니다.'); return;
            }
            setData(value);
        }).catch(() => { if (active) setReason('출사 정보를 불러오지 못했습니다.'); });
        return () => { active = false; };
    }, [generalId, unavailable, turnIdx]);
    if (unavailable) return <p role="status">이 명령은 아직 제공하지 않습니다. 개인 출사·출병을 한 건씩 예약할 수 있습니다.</p>;
    if (!Number.isInteger(turnIdx) || turnIdx < 0 || turnIdx >= 12) return <p role="status">출사는 1~12순에만 예약할 수 있습니다.</p>;
    const option = selection === '' ? undefined : data?.options[Number(selection)];
    async function reserve() {
        if (!option || option.availability.status !== 'AVAILABLE' || busy) return;
        setBusy(true); setReason(null);
        const body = option.mode === 'RANDOM' ? { mode: option.mode } : { mode: option.mode, targetId: option.targetId };
        try {
            const result = await submitCommandAndAwaitResult(() => api.command(inputId, body, generalId, turnIdx));
            if (result.status === 'reserved' || result.status === 'applied') {
                onToast(result.status === 'reserved' ? '출사 명령이 예약되었습니다.' : '출사 명령이 실행되었습니다.', 'success');
                onReserved?.(); onClose();
            } else setReason(result.reason ?? '출사를 예약할 수 없습니다.');
        } catch (error) { setReason(error instanceof Error ? error.message : '출사 예약에 실패했습니다.'); }
        finally { setBusy(false); }
    }
    const labels = { RANDOM: '무작위', NATION: '세력', GENERAL: '장수' };
    return <div className="cmd-form">
        <h3>출사</h3>
        <p>개인 행동에서 출사 또는 출병을 선택할 수 있습니다. 실행 시점에 조건을 다시 확인합니다.</p>
        {!data && !reason && <p role="status">출사 후보를 불러오는 중입니다.</p>}
        {data && <label>출사 대상<select className="os-inset" aria-label="출사 대상" value={selection} onChange={event => setSelection(event.target.value)} disabled={busy}>
            <option value="">선택하세요</option>
            {data.options.map((row, index) => (inputId === 'action.randomEnlist' && row.mode !== 'RANDOM'
                || inputId === 'action.targetEnlist' && row.mode !== 'GENERAL' ? null : <option key={`${row.mode}:${row.targetId ?? ''}`} value={index}>
                {labels[row.mode]} · {row.label}{row.availability.status === 'BLOCKED' ? ` — ${row.availability.reason ?? '출사 불가'}` : ''}
            </option>))}
        </select></label>}
        {option?.availability.status === 'BLOCKED' && <p role="status">{option.availability.reason ?? '출사할 수 없습니다.'}</p>}
        {reason && <p role="alert">{reason}</p>}
        <button type="button" className="cmd-submit os-button os-button--primary" disabled={busy || option?.availability.status !== 'AVAILABLE'} onClick={() => void reserve()}>{busy ? '처리 중...' : '출사 예약'}</button>
    </div>;
}
