'use client';

import { Chip, KV, Panel, SectionHeader, Table } from '@opensamguk/ui';
import HwihaShell from '../../../components/HwihaShell';
import {
    MOCK_DEPARTURE_ORDER,
    MOCK_IDENTITY,
    MOCK_RENOWN_PATHS,
    MOCK_YUEDAN_RANKING,
    type MockTrend,
} from '../../../lib/hwiha-mock';

const TREND_MARK: Record<MockTrend, string> = { up: '▲', down: '▼', flat: '—' };
const TREND_TONE: Record<MockTrend, 'moss' | 'rust' | 'neutral'> = {
    up: 'moss',
    down: 'rust',
    flat: 'neutral',
};

/**
 * 월단평 — 시안 Yuedan.
 *
 * 매월 상순 명망을 갱신하고 순위를 발표한다(정본 설계 §2.8·§5.2). 엔진 쪽은
 * `HwihaMonthlyAssessment` 가 월 경계에서 돌지만 읽기 API 가 아직 없어 목 데이터로 그린다.
 * 명망 절대값·코스트 상한은 설계 §16 미정이라 `[미정]` 으로 둔다 — 숫자를 지어내지 않는다.
 */
export default function YuedanPage() {
    return (
        <HwihaShell title="월단평" tab="장수 행동" identity={MOCK_IDENTITY}>
            <div
                style={{
                    padding: 12,
                    display: 'grid',
                    gridTemplateColumns: 'minmax(0, 1fr) 380px',
                    gap: 12,
                    alignItems: 'start',
                }}
            >
                <Panel style={{ padding: 12 }}>
                    <SectionHeader title="200년 3월 월단평" sub="달마다 새로 매긴다" />
                    <Table
                        headers={['순위', '장수', '세력', '변동', '이번 달 사유']}
                        rows={MOCK_YUEDAN_RANKING.map((row) => [
                            <span className="mono" key="r">
                                {row.rank ?? '[순위]'}
                            </span>,
                            <span key="g" style={{ whiteSpace: 'nowrap' }}>
                                {row.general}
                                {row.self ? (
                                    <>
                                        {' '}
                                        <Chip tone="info">나</Chip>
                                    </>
                                ) : null}
                            </span>,
                            row.nation,
                            <Chip key="t" tone={TREND_TONE[row.trend]}>
                                {TREND_MARK[row.trend]}
                            </Chip>,
                            row.reasons.join(' · '),
                        ])}
                    />
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, paddingTop: 12 }}>
                        <div>
                            <SectionHeader title="오르는 경로" as="h4" />
                            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', paddingTop: 8 }}>
                                {MOCK_RENOWN_PATHS.rising.map((p) => (
                                    <Chip key={p} tone="moss">
                                        {p}
                                    </Chip>
                                ))}
                            </div>
                        </div>
                        <div>
                            <SectionHeader title="떨어지는 경로" as="h4" />
                            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', paddingTop: 8 }}>
                                {MOCK_RENOWN_PATHS.falling.map((p) => (
                                    <Chip key={p} tone="rust">
                                        {p}
                                    </Chip>
                                ))}
                            </div>
                        </div>
                    </div>
                </Panel>

                <div style={{ display: 'grid', gap: 12 }}>
                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="내 명망" sub="매월 상순 갱신" />
                        <div style={{ fontFamily: 'var(--font-serif)', fontSize: 28, fontWeight: 900, paddingTop: 8 }}>
                            {MOCK_IDENTITY.renown ?? '[미정]'}
                        </div>
                        <div style={{ paddingTop: 6 }}>
                            <Chip tone="moss">▲ 전공 · 양적현 방어</Chip>
                        </div>
                        <div style={{ paddingTop: 12 }}>
                            <KV
                                items={[
                                    { k: '휘하 코스트 합', v: '[미정]' },
                                    { k: '새 상한', v: '[미정]' },
                                ]}
                            />
                        </div>
                        <p style={{ fontSize: 12, paddingTop: 8, color: 'var(--muted)' }}>
                            명망은 거느릴 수 있는 휘하 코스트의 상한이다.
                        </p>
                    </Panel>

                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="이탈 판정 순서" sub="상한을 넘으면 충성이 낮은 인물부터" />
                        <Table
                            headers={['인물', '결속', '충성', '순서']}
                            rows={MOCK_DEPARTURE_ORDER.map((r) => [
                                <span key="n" style={{ whiteSpace: 'nowrap' }}>
                                    {r.name} <Chip tone={r.bond === '혈연' ? 'bronze' : 'info'}>{r.bond}</Chip>
                                </span>,
                                r.bondNote || '—',
                                r.loyalty,
                                r.order ? (
                                    <Chip key="o" tone="rust">
                                        {r.order}
                                    </Chip>
                                ) : (
                                    '—'
                                ),
                            ])}
                        />
                        <p style={{ fontSize: 12, paddingTop: 8, color: 'var(--muted)' }}>
                            이번 달은 상한 안 — 이탈 판정 없음. 명망에 이끌려 온 인물(명망 결속)은 명망이
                            떨어지면 먼저 흔들린다.
                        </p>
                    </Panel>
                </div>
            </div>
        </HwihaShell>
    );
}
