'use client';

import { useEffect, useState } from 'react';
import { Panel, SectionHeader } from '@opensamguk/ui';
import GameShell from '@/components/GameShell';
import Toast from '@/components/Toast';
import DeployForm from '@/components/command/DeployForm';
import { PlacementPanel, PolicyPanel, WorksPanel } from '@/components/campaign/DomesticPanels';
import { Empty } from '@/components/campaign/GameStates';
import embed from '@/components/campaign/GameEmbed.module.css';
import { useToast } from '@/hooks/useToast';
import { api } from '@/lib/api';
import { useHwihaSession } from '@/lib/hwiha-session';

const HWIHA_SLOTS = 12;

/**
 * 배치 · 방침 · 공사 — 시안 Posts.
 *
 * 배치·방침·공사는 12순 슬롯을 쓰지 않는 지속 입력이다(`POST /api/commands/{placement|policy|work}/…`).
 * 출병은 직접 행동이라 명령 목록의 빈 순에 한 건 예약한다.
 */
export default function PostsPage() {
    const { generalId } = useHwihaSession();
    const { toasts, show, remove } = useToast();
    const [refreshKey, setRefreshKey] = useState(0);
    const [turnIdx, setTurnIdx] = useState<number | null>(null);
    const [freeSlots, setFreeSlots] = useState<number[] | null>(null);

    // 출병은 명령 목록 12순 가운데 빈 순에 한 건 예약한다.
    useEffect(() => {
        if (generalId == null) return;
        let alive = true;
        api.reservedCommands(generalId)
            .then((res) => {
                if (!alive) return;
                const used = new Set(res.slots.map((s) => s.turnIdx));
                const free = Array.from({ length: HWIHA_SLOTS }, (_, i) => i).filter((i) => !used.has(i));
                setFreeSlots(free);
                setTurnIdx((prev) => (prev != null && free.includes(prev) ? prev : free[0] ?? null));
            })
            .catch(() => alive && setFreeSlots([]));
        return () => {
            alive = false;
        };
    }, [generalId, refreshKey]);

    return (
        <GameShell title="배치 · 방침 · 공사" tab="배치">
            <div style={{ padding: 12, display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 400px', gap: 12, alignItems: 'start' }}>
                <div style={{ display: 'grid', gap: 12, minWidth: 0 }}>
                    <PlacementPanel onToast={show} refreshKey={refreshKey} onDone={() => setRefreshKey((k) => k + 1)} />
                    <PolicyPanel onToast={show} refreshKey={refreshKey} onDone={() => setRefreshKey((k) => k + 1)} />
                </div>

                <div style={{ display: 'grid', gap: 12 }}>
                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="출병" sub="명령 목록의 빈 순에 한 건" />
                        {generalId == null ? null : freeSlots == null ? (
                            <Empty>명령 목록을 불러오는 중입니다.</Empty>
                        ) : freeSlots.length === 0 ? (
                            <Empty>명령 목록 12순이 모두 찼습니다. 작전실에서 순을 비운 뒤 예약합니다.</Empty>
                        ) : (
                            <>
                                <label style={{ display: 'grid', gap: 4, paddingTop: 8, fontSize: 12, color: 'var(--text-2)' }}>
                                    예약할 순
                                    <select
                                        className="os-inset"
                                        value={turnIdx ?? ''}
                                        onChange={(e) => setTurnIdx(Number(e.target.value))}
                                        style={{ minHeight: 44 }}
                                    >
                                        {freeSlots.map((i) => (
                                            <option key={i} value={i}>{`${i + 1}순`}</option>
                                        ))}
                                    </select>
                                </label>
                                {turnIdx != null ? (
                                    <div className={embed.embed}>
                                    <DeployForm
                                        key={`${turnIdx}:${refreshKey}`}
                                        generalId={generalId}
                                        turnIdx={turnIdx}
                                        refreshKey={refreshKey}
                                        unavailable={false}
                                        onToast={show}
                                        onClose={() => undefined}
                                        onReserved={() => setRefreshKey((k) => k + 1)}
                                    />
                                    </div>
                                ) : null}
                            </>
                        )}
                    </Panel>

                    <WorksPanel onToast={show} refreshKey={refreshKey} onDone={() => setRefreshKey((k) => k + 1)} />
                </div>
            </div>
            <Toast toasts={toasts} onRemove={remove} />
        </GameShell>
    );
}
