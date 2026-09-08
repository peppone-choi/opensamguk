'use client';

import { useState } from 'react';
import { readServerCookie } from '@/lib/serverGameUrl';
import { Panel } from '@opensamguk/ui';
import { api, isIntakeQueued, type BattlefieldsResponse } from '@/lib/api';

export default function BattlefieldPanel({ data, siteId, serverId, onRefresh, onClose }: {
    data: BattlefieldsResponse; siteId: string; serverId?: string; onRefresh: () => void; onClose: () => void;
}) {
    const [busy, setBusy] = useState(false);
    const [message, setMessage] = useState('');
    const site = data.sites.find(item => item.id === siteId);
    if (!site) return null;
    const current = data.currentSiteId === site.id;
    const allowed = current ? data.canExit : site.canEnter;
    async function reserve() {
        if (busy || !allowed) return;
        if (readServerCookie() !== serverId) { setMessage('서버가 변경되었습니다. 지도를 갱신해주세요.'); return; }
        setBusy(true); setMessage('');
        try {
            const result = await api.command('che_전장이동', {
                siteId: current ? '' : siteId, catalogHash: data.catalogHash,
                expectedRevision: data.positionRevision,
            }, data.generalId, 0);
            if (readServerCookie() !== serverId) return;
            setMessage(isIntakeQueued(result)
                ? '예약 요청이 접수되었습니다. 실제 이동은 개인 턴 처리 후 반영됩니다.'
                : result.reason);
            onRefresh();
        } catch (error) {
            setMessage(error instanceof Error ? error.message : '예약 요청을 처리하지 못했습니다.');
        } finally { setBusy(false); }
    }
    return <Panel className="battlefield-panel" aria-label={`${site.name} 전장`}>
        <div style={{display:'flex',justifyContent:'space-between',gap:12}}>
            <strong>{site.name} · 전장</strong>
            <button type="button" className="os-button os-button--ghost os-button--sm" onClick={onClose}>닫기</button>
        </div>
        <p className="text-muted">대략적 위치 · 경계 미복원</p>
        {current && <p>현재 주둔 중</p>}
        {!allowed && site.reason && <p>{site.reason}</p>}
        <button type="button" className="os-button os-button--sm" disabled={!allowed || busy} onClick={reserve}>
            {current ? '귀환 예약' : '전장 진입 예약'}
        </button>
        {message && <p role="status">{message}</p>}
    </Panel>;
}
