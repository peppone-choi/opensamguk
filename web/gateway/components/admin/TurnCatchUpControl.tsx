'use client';

import { useEffect, useState } from 'react';
import { Button, Seg } from '@opensamguk/ui';

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

    const block = busy ? '처리 중입니다' : selected === catchUp.multiplier ? '지금과 같은 배속입니다' : null;
    return (
        <div role="group" aria-label="밀린 턴 따라잡기" className="admin31-catchup">
            <p className="gw31-card__line">현재 지연: {duration(catchUp.backlogSeconds)} · 회복한 지연: {duration(catchUp.recoveredSeconds)}</p>
            <p className="gw31-card__line">현재 {catchUp.multiplier}배속 · 남은 회복 시간: {duration(catchUp.remainingSeconds)}</p>
            <p className="gw31-card__line">정상 속도 예상: {koreanEta(catchUp.etaAt)}</p>
            <div className="admin31-row">
                <span className="gw31-field__label">따라잡기 배속</span>
                <Seg label="따라잡기 배속" options={[{ value: 2, label: '2배속' }, { value: 4, label: '4배속' }]} value={selected} onChange={(next) => { if (!busy) setSelected(next as 2 | 4); }} />
                {block ? <Button variant="primary" disabled reason={block}>배속 적용</Button> : <Button variant="primary" onClick={() => void changeMultiplier()}>배속 적용</Button>}
            </div>
            {error && <p role="alert" className="gw31-alert">{error}</p>}
        </div>
    );
}
