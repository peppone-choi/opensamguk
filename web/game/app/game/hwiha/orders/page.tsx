'use client';

import { useState } from 'react';
import { Panel, SectionHeader } from '@opensamguk/ui';
import GameShell from '@/components/GameShell';
import CourtForm from '@/components/command/CourtForm';
import { Empty, hwihaReadNotice } from '@/components/campaign/GameStates';
import { api } from '@/lib/api';
import { submitCommandAndAwaitResult } from '@/lib/commandSubmit';
import { useHwihaRead } from '@/lib/hwiha-reads';
import { useHwihaSession } from '@/lib/hwiha-session';

/**
 * 조정 결정 — 발령 · 포상. 시안 Orders.
 *
 * 발령은 사람 장수에게 내리는 조정 결정이고 결정권자의 턴에 실행된다. 받은 발령의 수락·거절, 직속
 * 장수에게 새 발령은 기존 발령 폼(`CourtForm`)이 서버 입력 `court.dispatch`·`court.dispatchReply`
 * 로 보낸다. 상사는 카드 위치의 창고망 금 잔액을 확인해 `court.reward`로 접수한다.
 */
export default function OrdersPage() {
    const { generalId, refresh } = useHwihaSession();
    const [refreshKey, setRefreshKey] = useState(0);
    const [retainerId, setRetainerId] = useState(0);
    const [amount, setAmount] = useState('');
    const [busy, setBusy] = useState(false);
    const [notice, setNotice] = useState<{ kind: 'error' | 'status'; text: string } | null>(null);
    const retinue = useHwihaRead((id, signal) => api.hwihaRetinue(id, signal), [refreshKey]);
    const warehouses = useHwihaRead((id, signal) => api.warehouses(id, signal), [refreshKey]);
    const people = (retinue.data?.people ?? []).filter((person) => person.generalId != null);
    const selected = people.find((person) => person.retainerId === retainerId);
    const location = warehouses.data?.warehouses.find((row) => row.cityId === selected?.locationCityId);
    const balance = location == null ? 0 : location.supplied
        ? warehouses.data!.warehouses.filter((row) => row.supplied).reduce((sum, row) => sum + row.stock.money, 0)
        : location.stock.money;
    const money = Number(amount);
    const valid = selected != null && Number.isSafeInteger(money) && money > 0 && money <= Math.min(balance, 1_000_000_000);
    const problem = hwihaReadNotice(retinue, retinue.data?.status) ?? hwihaReadNotice(warehouses, warehouses.data?.status);
    async function reward() {
        if (generalId == null || !valid || busy) return;
        setBusy(true); setNotice(null);
        try {
            const result = await submitCommandAndAwaitResult(() => api.courtReward(generalId, { retainerId, money }));
            if (result.status === 'rejected') setNotice({ kind: 'error', text: result.reason ?? '상사를 처리할 수 없습니다.' });
            else {
                setNotice({ kind: 'status', text: result.status === 'applied' ? '상사가 완료되었습니다.' : '상사가 접수됐습니다. 다음 개인 턴의 결과를 확인해 주세요.' });
                setRefreshKey((key) => key + 1); refresh();
            }
        } catch (error) {
            setNotice({ kind: 'error', text: error instanceof Error ? error.message : '상사를 제출하지 못했습니다.' });
        } finally { setBusy(false); }
    }
    return (
        <GameShell title="조정 결정 — 발령 · 포상" tab="조정 결정">
            <div style={{ padding: 12, display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1fr)', gap: 12, alignItems: 'start' }}>
                <Panel style={{ padding: 12 }}>
                    <SectionHeader title="발령" sub="받은 발령 · 내린 발령 · 새 발령" />
                    {generalId != null ? <CourtForm generalId={generalId} onReserved={refresh} /> : null}
                </Panel>
                <Panel style={{ padding: 12 }}>
                    <SectionHeader title="상사" sub="직속 인물에게 창고 금을 내립니다" />
                    <p style={{ margin: '8px 0', fontSize: 13, color: 'var(--text-2)' }}>금 100당 충성 +1, 한 번에 최대 +10</p>
                    {problem && <Empty>{problem}</Empty>}
                    {notice && <p role={notice.kind === 'error' ? 'alert' : 'status'}>{notice.text}</p>}
                    {!problem && people.length === 0 && <Empty>상사할 직속 인물 카드가 없습니다.</Empty>}
                    {!problem && people.length > 0 && <div style={{ display: 'grid', gap: 10, paddingTop: 10 }}>
                        <label>상사 대상
                            <select className="os-inset" aria-label="상사 대상" value={retainerId || ''} onChange={(event) => setRetainerId(Number(event.target.value))} disabled={busy}>
                                <option value="">인물을 선택하세요</option>
                                {people.map((person) => <option key={person.retainerId} value={person.retainerId}>{person.name} · 충성 {person.loyalty}</option>)}
                            </select>
                        </label>
                        {selected && <p style={{ margin: 0, fontSize: 13 }}>사용 가능한 창고망 금: {new Intl.NumberFormat('ko-KR').format(balance)}
                            {location == null ? ' · 카드 위치에 쓸 창고가 없습니다.' : location.supplied ? ' · 보급된 창고 합계' : ' · 고립된 현 창고'}</p>}
                        <label>금액
                            <input className="os-inset" aria-label="상사 금액" type="number" min={1} max={Math.min(balance, 1_000_000_000)} step={1} value={amount} onChange={(event) => setAmount(event.target.value)} disabled={busy || !selected} />
                        </label>
                        <button className="os-button os-button--primary" disabled={busy || !valid} onClick={() => void reward()}>상사 접수</button>
                    </div>}
                </Panel>
            </div>
        </GameShell>
    );
}
