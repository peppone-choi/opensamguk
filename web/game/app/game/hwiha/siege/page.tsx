'use client';

import { Panel, SectionHeader } from '@opensamguk/ui';
import HwihaShell from '@/components/HwihaShell';
import { HwihaEmpty } from '@/components/hwiha/HwihaStates';

/**
 * 공성 — 시안 Siege(탭 「방침」).
 *
 * 포위 누적·포위 군단 보급·항복 권고·강공 계획은 포위 상태 조회가 붙으면 채운다. 그 전에는 없는
 * 상황을 지어내지 않고 빈 상태로 둔다.
 */
export default function SiegePage() {
    return (
        <HwihaShell title="공성" tab="방침">
            <div style={{ padding: 12, display: 'grid', gap: 12 }}>
                <Panel style={{ padding: 12 }}>
                    <SectionHeader title="포위 중인 성" sub="보이는 만큼만" />
                    <HwihaEmpty>포위 중인 성이 없습니다.</HwihaEmpty>
                </Panel>
            </div>
        </HwihaShell>
    );
}
