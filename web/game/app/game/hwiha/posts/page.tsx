'use client';

import { useEffect, useState } from 'react';
import { Chip, Panel, SectionHeader, Table } from '@opensamguk/ui';
import HwihaShell from '@/components/HwihaShell';
import Toast from '@/components/Toast';
import HwihaDeployForm from '@/components/command/HwihaDeployForm';
import { HwihaEmpty, hwihaReadNotice } from '@/components/hwiha/HwihaStates';
import embed from '@/components/hwiha/HwihaEmbed.module.css';
import { useToast } from '@/hooks/useToast';
import { api } from '@/lib/api';
import { useHwihaRead } from '@/lib/hwiha-reads';
import { useHwihaSession } from '@/lib/hwiha-session';

const HWIHA_SLOTS = 12;
const NOT_YET = '아직 이 입력이 없습니다';

/**
 * 배치 · 방침 · 공사 — 시안 Posts.
 *
 * 지금 서버에 있는 입력은 **군단 출병**(부곡을 골라 목적지 구역으로)뿐이다. 인물 카드를 자리에 앉히는
 * 배치, 자리·군단의 방침, 공사는 입력이 아직 없어 현재 자리만 보이고 바꾸기는 비활성으로 둔다.
 */
export default function PostsPage() {
    const { generalId } = useHwihaSession();
    const { toasts, show, remove } = useToast();
    const [refreshKey, setRefreshKey] = useState(0);
    const [turnIdx, setTurnIdx] = useState<number | null>(null);
    const [freeSlots, setFreeSlots] = useState<number[] | null>(null);
    const retinue = useHwihaRead((id, signal) => api.hwihaRetinue(id, signal), [refreshKey]);
    const people = retinue.data?.people ?? [];

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
        <HwihaShell title="배치 · 방침 · 공사" tab="배치">
            <div style={{ padding: 12, display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 400px', gap: 12, alignItems: 'start' }}>
                <div style={{ display: 'grid', gap: 12, minWidth: 0 }}>
                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="배치" sub="카드는 자기 턴마다 지도 위를 실제로 이동해 부임한다" />
                        {hwihaReadNotice(retinue, retinue.data?.status) ? (
                            <HwihaEmpty>{hwihaReadNotice(retinue, retinue.data?.status)}</HwihaEmpty>
                        ) : people.length === 0 ? (
                            <HwihaEmpty>앉힐 인물 카드가 없습니다.</HwihaEmpty>
                        ) : (
                            <Table
                                headers={['인물', '자리', '임무', '']}
                                rows={people.map((p) => [
                                    <span key="n" style={{ fontWeight: 700, whiteSpace: 'nowrap' }}>{p.name}</span>,
                                    p.roleLabel ?? <Chip key="r" tone="rust">미배치</Chip>,
                                    p.taskLabel ?? '—',
                                    <button key="b" type="button" className="os-button os-button--ghost os-button--sm" disabled title={NOT_YET}>
                                        바꾸기
                                    </button>,
                                ])}
                            />
                        )}
                        <p style={{ fontSize: 12, color: 'var(--muted)', padding: '8px 0 0', margin: 0 }}>
                            사람 장수를 자리에 앉히는 것은 배치가 아니라 발령(조정 결정)입니다.
                        </p>
                    </Panel>

                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="방침" sub="자리나 군단에 걸어 두는 지속 규칙" />
                        <HwihaEmpty>방침 입력이 아직 없습니다. 권농·휼민·조련·공략·수비·요격·회피가 여기에 걸립니다.</HwihaEmpty>
                    </Panel>
                </div>

                <div style={{ display: 'grid', gap: 12 }}>
                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="출병" sub="명령 목록의 빈 순에 한 건" />
                        {generalId == null ? null : freeSlots == null ? (
                            <HwihaEmpty>명령 목록을 불러오는 중입니다.</HwihaEmpty>
                        ) : freeSlots.length === 0 ? (
                            <HwihaEmpty>명령 목록 12순이 모두 찼습니다. 작전실에서 순을 비운 뒤 예약합니다.</HwihaEmpty>
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
                                    <HwihaDeployForm
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

                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="공사" sub="순 경계마다 진척" />
                        <HwihaEmpty>공사 입력이 아직 없습니다. 수리·둔전·성방·도로·역참·창고가 여기에 걸립니다.</HwihaEmpty>
                    </Panel>
                </div>
            </div>
            <Toast toasts={toasts} onRemove={remove} />
        </HwihaShell>
    );
}
