'use client';

import { Chip, KV, Panel, SectionHeader, Table } from '@opensamguk/ui';
import HwihaShell from '../../../components/HwihaShell';
import { MOCK_IDENTITY, MOCK_SUPPLY_BREAK, MOCK_WAREHOUSES } from '../../../lib/hwiha-mock';

/**
 * 보급망 · 창고 — 시안 Supply.
 *
 * 지도가 아니다. 이어짐과 지연만 보인다(시안 문구). 창고는 다섯 자원을 모두 실물로 들고 있고,
 * 국고는 수도 창고 안에 있다 — 수도가 함락되면 국고를 빼앗긴다.
 *
 * 엔진 쪽 창고·월세입은 `HwihaCountyWarehouse` · `HwihaMonthlyCountyIncome` 가 돌리지만 읽기
 * API 가 없어 목 데이터로 그린다. 자원 값은 설계 미정이라 `[값]` 으로, 그 창고에 없는 자원은
 * 시안처럼 `—` 로 둔다.
 */
export default function SupplyPage() {
    return (
        <HwihaShell title="보급망 · 창고" tab="배치" identity={MOCK_IDENTITY}>
            <div style={{ padding: 12, display: 'grid', gap: 12 }}>
                <Panel style={{ padding: 12 }}>
                    <SectionHeader title="창고별 재고" sub="다섯 자원 모두 실물" />
                    <Table
                        headers={['창고', '구분', '금', '쌀', '철', '목재', '말']}
                        rows={MOCK_WAREHOUSES.map((w) => [
                            <span key="n" style={{ whiteSpace: 'nowrap' }}>
                                {w.name}{' '}
                                {w.kind === '고립' ? (
                                    <Chip tone="rust">고립</Chip>
                                ) : w.kind.includes('수도') ? (
                                    <Chip tone="bronze">수도</Chip>
                                ) : null}
                            </span>,
                            w.kind,
                            w.money,
                            w.grain,
                            w.iron,
                            w.timber,
                            w.horses,
                        ])}
                    />
                    <p style={{ fontSize: 12, paddingTop: 8, color: 'var(--muted)' }}>
                        {MOCK_SUPPLY_BREAK.treasuryNote}
                    </p>
                </Panel>

                <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 380px', gap: 12, alignItems: 'start' }}>
                    <Panel style={{ padding: 12 }}>
                        <SectionHeader
                            title="끊긴 곳"
                            sub={MOCK_SUPPLY_BREAK.since}
                            actions={<Chip tone="rust">1</Chip>}
                        />
                        <div style={{ paddingTop: 8 }}>
                            <div style={{ fontWeight: 700 }}>{MOCK_SUPPLY_BREAK.title}</div>
                            <p style={{ fontSize: 13, paddingTop: 6 }}>{MOCK_SUPPLY_BREAK.why}</p>
                            <p style={{ fontSize: 13, paddingTop: 6, color: 'var(--muted)' }}>
                                {MOCK_SUPPLY_BREAK.recover}
                            </p>
                        </div>
                        <div style={{ paddingTop: 12 }}>
                            <SectionHeader title="윤씨현 조각" sub="제 창고만 사용" as="h4" />
                            <div style={{ paddingTop: 8 }}>
                                <KV items={MOCK_SUPPLY_BREAK.isolated.map((i) => ({ k: i.k, v: i.v }))} />
                            </div>
                        </div>
                    </Panel>

                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="녹봉 미지급 위험" sub="월 경계(상순) 지급" actions={<Chip tone="rust">2</Chip>} />
                        <div style={{ display: 'grid', gap: 10, paddingTop: 8 }}>
                            {MOCK_SUPPLY_BREAK.risks.map((r) => (
                                <div key={r.who} style={{ borderTop: '1px solid var(--line)', paddingTop: 8 }}>
                                    <div style={{ whiteSpace: 'nowrap' }}>
                                        <strong>{r.who}</strong> <Chip tone="rust">{r.tag}</Chip>
                                    </div>
                                    <div style={{ fontSize: 12, color: 'var(--muted)' }}>{r.role}</div>
                                    <p style={{ fontSize: 13, paddingTop: 4 }}>{r.what}</p>
                                </div>
                            ))}
                        </div>
                        <p style={{ fontSize: 12, paddingTop: 8, color: 'var(--muted)' }}>
                            {MOCK_SUPPLY_BREAK.riskNote}
                        </p>
                    </Panel>
                </div>
            </div>
        </HwihaShell>
    );
}
