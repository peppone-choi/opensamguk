'use client';

import { Chip, KV, Panel, SectionHeader, Table } from '@opensamguk/ui';
import HwihaShell from '../../../components/HwihaShell';
import {
    MOCK_IDENTITY,
    MOCK_RETINUE,
    MOCK_RETINUE_DETAIL,
    MOCK_UNITS,
} from '../../../lib/hwiha-mock';

const LOYALTY_TONE = (loyalty: string) =>
    loyalty.includes('높') ? 'moss' : loyalty.includes('낮') ? 'rust' : 'info';

/**
 * 휘하 편성 — 시안 Main.
 *
 * 명망은 거느릴 수 있는 휘하 코스트의 상한이다(설계 §2.8·§6.6). 삼모의 「장수 코스트 201/420」과
 * 같은 자리이므로 `⚖ 합 / 상한` 표기를 쓴다 — 기존 플레이어가 아는 표기를 새로 만들지 않는다.
 * 값은 설계 §16 미정이라 `[미정]` 으로 둔다.
 */
export default function RetinuePage() {
    return (
        <HwihaShell title="휘하 편성" tab="배치" identity={MOCK_IDENTITY}>
            <div style={{ padding: 12, display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 420px', gap: 12, alignItems: 'start' }}>
                <div style={{ display: 'grid', gap: 12 }}>
                    <Panel style={{ padding: 12 }}>
                        <SectionHeader
                            title="인물 카드"
                            sub={`직접 거느린 인물 ${MOCK_RETINUE.length}`}
                            actions={<Chip tone="bronze">⚖ [미정] / [미정]</Chip>}
                        />
                        <p style={{ fontSize: 12, color: 'var(--muted)', paddingTop: 4 }}>
                            휘하 코스트 합 / 명망 상한. 월단평 뒤 코스트 합이 상한을 넘으면 충성이 낮은
                            인물부터 이탈 판정을 받는다.
                        </p>
                        <Table
                            headers={['인물', '결속', '적성', '충성', '자리', '명망 코스트']}
                            rows={MOCK_RETINUE.map((c) => [
                                <span key="n" style={{ whiteSpace: 'nowrap' }}>
                                    {c.name} <Chip tone="bronze">{c.unique}</Chip>
                                </span>,
                                c.bond,
                                c.aptitude,
                                <Chip key="l" tone={LOYALTY_TONE(c.loyalty)}>
                                    {c.loyalty}
                                </Chip>,
                                c.post === '미배치' ? <Chip key="p" tone="rust">미배치</Chip> : c.post,
                                '[미정]',
                            ])}
                        />
                        <div style={{ paddingTop: 10, borderTop: '1px solid var(--line)', marginTop: 10 }}>
                            <button type="button" className="os-button os-button--ghost os-button--sm">
                                등용 (장수 행동)
                            </button>
                            <span style={{ fontSize: 12, color: 'var(--muted)', paddingLeft: 8 }}>
                                무명 인물 채용 · 공용 카드
                            </span>
                        </div>
                    </Panel>

                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="부대 카드" sub="지휘 인물 1장이 붙어야 움직인다" />
                        <Table
                            headers={['부대', '모집원', '지휘', '비용', '상태']}
                            rows={MOCK_UNITS.map((u) => [u.unit, u.source, u.commander, u.cost, u.state])}
                        />
                    </Panel>
                </div>

                <Panel style={{ padding: 12 }}>
                    <SectionHeader
                        title={`${MOCK_RETINUE_DETAIL.name} ${MOCK_RETINUE_DETAIL.hanja}`}
                        sub={MOCK_RETINUE_DETAIL.kind}
                    />
                    <div style={{ paddingTop: 8 }}>
                        <KV items={MOCK_RETINUE_DETAIL.stats.map((s) => ({ k: s.k, v: s.v }))} />
                    </div>

                    <div style={{ paddingTop: 12 }}>
                        <SectionHeader title="결속" as="h4" actions={<Chip tone="info">{MOCK_RETINUE_DETAIL.bond.label}</Chip>} />
                        <div style={{ fontSize: 13, paddingTop: 6 }}>{MOCK_RETINUE_DETAIL.bond.native}</div>
                        <p style={{ fontSize: 12, color: 'var(--muted)', paddingTop: 4 }}>
                            {MOCK_RETINUE_DETAIL.bond.note}
                        </p>
                    </div>

                    <div style={{ paddingTop: 12 }}>
                        <SectionHeader title="적성" as="h4" />
                        <div style={{ paddingTop: 6 }}>
                            <KV items={MOCK_RETINUE_DETAIL.aptitudes.map((a) => ({ k: a.k, v: a.v }))} />
                        </div>
                    </div>

                    <div style={{ paddingTop: 12 }}>
                        <SectionHeader title="계책 기여" as="h4" />
                        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', paddingTop: 6 }}>
                            {MOCK_RETINUE_DETAIL.stratagems.map((s) => (
                                <Chip key={s}>{s}</Chip>
                            ))}
                        </div>
                        <p style={{ fontSize: 12, color: 'var(--muted)', paddingTop: 6 }}>
                            {MOCK_RETINUE_DETAIL.leaveNote}
                        </p>
                    </div>

                    <div style={{ paddingTop: 12 }}>
                        <SectionHeader title="배치와 대우" as="h4" />
                        <div style={{ paddingTop: 6 }}>
                            <KV items={MOCK_RETINUE_DETAIL.posting.map((p) => ({ k: p.k, v: p.v }))} />
                        </div>
                    </div>

                    <div style={{ display: 'flex', gap: 8, paddingTop: 12, flexWrap: 'wrap' }}>
                        <button type="button" className="os-button os-button--primary os-button--sm">
                            자리에 배치
                        </button>
                        <button type="button" className="os-button os-button--ghost os-button--sm">
                            보물 부착
                        </button>
                        <button type="button" className="os-button os-button--danger os-button--sm">
                            내보내기
                        </button>
                    </div>
                </Panel>
            </div>
        </HwihaShell>
    );
}
