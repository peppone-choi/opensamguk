'use client';

import { useEffect, useState } from 'react';

export interface AdminCatchUpInfo {
    active: boolean;
    multiplier: 2 | 4;
    backlogSeconds: number;
    remainingSeconds: number;
    etaAt: string | null;
    initialBacklogSeconds: number;
    recoveredSeconds: number;
}

function duration(seconds: number): string {
    const minutes = Math.ceil(Math.max(0, seconds) / 60);
    const hours = Math.floor(minutes / 60);
    const rest = minutes % 60;
    return hours > 0 ? `${hours}시간 ${rest}분` : `${rest}분`;
}

function koreanEta(value: string | null): string {
    if (!value) return '계산 중';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '계산 중';
    return `${new Intl.DateTimeFormat('ko-KR', {
        timeZone: 'Asia/Seoul', year: 'numeric', month: 'numeric', day: 'numeric',
        hour: '2-digit', minute: '2-digit', hour12: false,
    }).format(date)} (한국 시간)`;
}

export default function TurnCatchUpControl({
    catchUp, serverId, onChanged,
}: {
    catchUp: AdminCatchUpInfo | null | undefined;
    serverId: string;
    onChanged: () => Promise<void>;
}) {
    const [selected, setSelected] = useState<2 | 4>(catchUp?.multiplier ?? 2);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => setSelected(catchUp?.multiplier ?? 2), [catchUp?.multiplier]);
    useEffect(() => {
        if (!catchUp?.active) return;
        const timer = window.setInterval(() => void onChanged(), 60_000);
        return () => window.clearInterval(timer);
    }, [catchUp?.active, onChanged]);
    if (!catchUp?.active) return null;

    async function changeMultiplier() {
        if (!serverId || !catchUp) return;
        setBusy(true);
        setError(null);
        try {
            const response = await fetch(
                `/api/proxy/admin/turn-daemon/catch-up?serverId=${encodeURIComponent(serverId)}`,
                { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ multiplier: selected }) },
            );
            if (!response.ok) throw new Error('배속을 바꾸지 못했습니다.');
            await onChanged();
        } catch {
            setError('배속을 바꾸지 못했습니다.');
        } finally {
            setBusy(false);
        }
    }

    return (
        <div role="group" aria-label="밀린 턴 따라잡기" style={{ paddingTop: 12, lineHeight: 1.6 }}>
            <strong>밀린 턴 따라잡기</strong>
            <div>현재 지연: {duration(catchUp.backlogSeconds)} · 회복한 지연: {duration(catchUp.recoveredSeconds)}</div>
            <div>현재 {catchUp.multiplier}배속 · 남은 회복 시간: {duration(catchUp.remainingSeconds)}</div>
            <div>정상 속도 예상: {koreanEta(catchUp.etaAt)}</div>
            <label className="field" style={{ display: 'inline-flex', alignItems: 'center', gap: 8, marginTop: 8 }}>
                <span>따라잡기 배속</span>
                <select value={selected} disabled={busy} onChange={(event) => setSelected(Number(event.target.value) as 2 | 4)}>
                    <option value={2}>2배속</option>
                    <option value={4}>4배속</option>
                </select>
            </label>
            <button type="button" className="btn-primary" style={{ marginLeft: 8 }} disabled={busy || selected === catchUp.multiplier} onClick={changeMultiplier}>
                배속 적용
            </button>
            {error && <p role="alert" className="deploy-result fail">{error}</p>}
        </div>
    );
}
