'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { Chip, KV, Panel, SectionHeader } from '@opensamguk/ui';
import HwihaShell from '@/components/HwihaShell';
import { HwihaEmpty } from '@/components/hwiha/HwihaStates';
import { api } from '@/lib/api';
import { hwihaHref } from '@/lib/hwiha-screens';
import { useHwihaSession } from '@/lib/hwiha-session';

/** 아직 입력이 없는 결정 — 숨기지 않고 사유와 함께 비활성으로 둔다(표시 원칙). */
function Pending({ label, danger = false }: { label: string; danger?: boolean }) {
    return (
        <button
            type="button"
            className={`os-button os-button--${danger ? 'danger' : 'ghost'} os-button--sm`}
            disabled
            title="아직 이 결정이 없습니다"
        >
            {label}
        </button>
    );
}

/**
 * 조정 — 관직 · 외교 · 천도. 시안 Court.
 *
 * 관직 임명·외교(사자 카드)·천도는 2·3층 결정이라 서버에 아직 없다(정본 설계 §11·§15.1, #208·#210).
 * 이 화면은 자리만 세우고, 지금 있는 결정(발령)은 발령 화면으로 잇는다. 삼모 외교 서신은 모델이 달라
 * 여기에 싣지 않는다.
 */
export default function CourtPage() {
    const { serverId, frontInfo } = useHwihaSession();
    const capital = frontInfo?.nation?.capitalCityId ?? null;
    // 수도 이름은 공개 지도 미리보기의 城 표에서 찾는다 — 번호를 그대로 보이지 않는다.
    const [capitalName, setCapitalName] = useState<string | null>(null);
    useEffect(() => {
        if (capital == null) return;
        const controller = new AbortController();
        api.mapPreview(controller.signal)
            .then((p) => setCapitalName(p.cities.find((c) => c.id === capital)?.name ?? null))
            .catch(() => undefined);
        return () => controller.abort();
    }, [capital]);
    return (
        <HwihaShell title="조정 — 관직 · 외교 · 천도" tab="조정 결정">
            <div style={{ padding: 12, display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1fr)', gap: 12, alignItems: 'start' }}>
                <div style={{ display: 'grid', gap: 12 }}>
                    {/* 관직은 둘로 나눈다(2026-09-23 사용자 결정). 지방 관직은 2층 임명·실효 지배,
                        중앙 관직은 황실 조서로만 주어지는 3층이다. */}
                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="지방 관직" sub="자사 · 태수 · 현령 · 관할과 실효 지배" />
                        <HwihaEmpty>지방 관직 모델이 아직 없습니다. 주 → 군 → 현 관할마다 앉은 사람·실효 지배가 여기 나옵니다.</HwihaEmpty>
                        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', paddingTop: 10 }}>
                            <Chip tone="moss">실권 있음</Chip>
                            <Chip tone="rust">명목</Chip>
                        </div>
                    </Panel>
                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="중앙 관직" sub="삼공 · 구경 · 상서 · 장군호 — 황실 조서로 받는다" />
                        <HwihaEmpty>황실과 조서가 생기면 조정의 자리와 앉은 사람이 여기 나옵니다.</HwihaEmpty>
                        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', paddingTop: 10 }}>
                            <Chip tone="bronze">조서 임명</Chip>
                            <Chip tone="info">추천됨</Chip>
                            <Chip tone="rust">자칭</Chip>
                        </div>
                    </Panel>
                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="관직 임명" sub="추천 → 심의 → 임명" />
                        <HwihaEmpty>임명·추천·천거 결정이 아직 없습니다.</HwihaEmpty>
                        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', paddingTop: 10 }}>
                            <Pending label="추천" />
                            <Pending label="천거 요청" />
                            <Pending label="임명" />
                        </div>
                    </Panel>
                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="발령" sub="응답은 장수 행동을 쓰지 않는다" />
                        <p style={{ margin: '8px 0 0', fontSize: 13, color: 'var(--text-2)' }}>
                            받은 발령의 수락·거절과 직속 장수 발령은 발령 화면에서 합니다.
                        </p>
                        <div style={{ paddingTop: 10 }}>
                            <Link className="os-button os-button--primary os-button--sm" href={hwihaHref('orders', serverId)}>
                                발령 · 포상으로
                            </Link>
                        </div>
                    </Panel>
                </div>

                <div style={{ display: 'grid', gap: 12 }}>
                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="외교" sub="군주의 결정 · 사자 카드 필요" />
                        <HwihaEmpty>휘하 규칙의 외교 결정이 아직 없습니다. 세력마다 관계와 유예가 여기 나옵니다.</HwihaEmpty>
                        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', paddingTop: 10 }}>
                            <Pending label="원조" />
                            <Pending label="불가침 제의" />
                            <Pending label="종전 제의" />
                            <Pending label="불가침 파기" />
                            <Pending label="선전포고" danger />
                        </div>
                        <p style={{ margin: '8px 0 0', fontSize: 12, color: 'var(--muted)' }}>
                            선전포고는 유예 [미정]순 뒤에 교전이 열립니다.
                        </p>
                    </Panel>
                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="천도" sub="치소 이동 공사" />
                        <KV
                            items={[
                                { k: '지금 수도', v: capital == null ? '없음' : capitalName ?? '—' },
                                { k: '공사 비용 · 기간', v: '[미정]' },
                                { k: '국고', v: '수도 창고에 있다 — 새 수도로 실물 수송' },
                            ]}
                        />
                        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', paddingTop: 10 }}>
                            <Pending label="천도 공사 시작" />
                            <Pending label="천도 건의" />
                        </div>
                    </Panel>
                </div>
            </div>
        </HwihaShell>
    );
}
