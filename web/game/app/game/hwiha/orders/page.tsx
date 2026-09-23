'use client';

import { Panel, SectionHeader } from '@opensamguk/ui';
import HwihaShell from '@/components/HwihaShell';
import HwihaCourtForm from '@/components/command/HwihaCourtForm';
import { HwihaEmpty } from '@/components/hwiha/HwihaStates';
import { useHwihaSession } from '@/lib/hwiha-session';

/**
 * 조정 결정 — 발령 · 포상. 시안 Orders.
 *
 * 발령은 사람 장수에게 내리는 조정 결정이고 결정권자의 턴에 실행된다. 받은 발령의 수락·거절, 직속
 * 장수에게 새 발령은 기존 발령 폼(`HwihaCourtForm`)이 서버 입력 `court.dispatch`·`court.dispatchReply`
 * 로 보낸다. 포상(봉록·상사)은 규칙이 생기면 오른쪽에 붙는다.
 */
export default function OrdersPage() {
    const { generalId, refresh } = useHwihaSession();
    return (
        <HwihaShell title="조정 결정 — 발령 · 포상" tab="조정 결정">
            <div style={{ padding: 12, display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1fr)', gap: 12, alignItems: 'start' }}>
                <Panel style={{ padding: 12 }}>
                    <SectionHeader title="발령" sub="받은 발령 · 내린 발령 · 새 발령" />
                    {generalId != null ? <HwihaCourtForm generalId={generalId} onReserved={refresh} /> : null}
                </Panel>
                <Panel style={{ padding: 12 }}>
                    <SectionHeader title="포상" sub="봉록 · 상사" />
                    <HwihaEmpty>포상 규칙이 아직 없습니다. 봉록과 상사를 내리고 받은 기록이 여기 나옵니다.</HwihaEmpty>
                </Panel>
            </div>
        </HwihaShell>
    );
}
