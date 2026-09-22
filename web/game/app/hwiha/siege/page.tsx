'use client';

import { Chip, KV, Panel, SectionHeader, Table } from '@opensamguk/ui';
import HwihaShell from '../../../components/HwihaShell';
import { MOCK_IDENTITY, MOCK_SIEGE } from '../../../lib/hwiha-mock';

/**
 * 공성 — 시안 Siege.
 *
 * 성 안 사정은 정찰·첩보로 본 만큼만 보인다. 방침은 자기 턴 6단계에 판정하며 전투 중 조작은 없다.
 * 엔진 쪽 포위 사기·항복 판정은 `HwihaSiegeMorale` 이 정본 수치로 돌지만 읽기 API 가 없어 목
 * 데이터로 그린다. 감소량·포위 기간·판정 계수는 설계 미정이라 `[값]`·`[미정]` 으로 둔다.
 */
export default function SiegePage() {
    return (
        <HwihaShell title={`공성 — ${MOCK_SIEGE.county}`} tab="방침" identity={MOCK_IDENTITY}>
            <div style={{ padding: 12, display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1fr) 380px', gap: 12, alignItems: 'start' }}>
                <Panel style={{ padding: 12 }}>
                    <SectionHeader title="포위된 성" sub="보이는 만큼만" actions={<Chip tone="bronze">{MOCK_SIEGE.seatNote}</Chip>} />
                    <div style={{ paddingTop: 8 }}>
                        <div style={{ fontWeight: 700 }}>{MOCK_SIEGE.county}</div>
                        <div style={{ fontSize: 12, color: 'var(--muted)' }}>{MOCK_SIEGE.where}</div>
                        <div style={{ paddingTop: 6 }}>
                            <Chip tone="moss">{MOCK_SIEGE.defenderNote}</Chip>
                        </div>
                    </div>
                    <div style={{ paddingTop: 12 }}>
                        <KV items={MOCK_SIEGE.inside.map((i) => ({ k: i.k, v: i.v }))} />
                    </div>
                    <p style={{ fontSize: 12, paddingTop: 8, color: 'var(--muted)' }}>{MOCK_SIEGE.scoutNote}</p>
                </Panel>

                <div style={{ display: 'grid', gap: 12 }}>
                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="포위 군단의 보급" sub="순 경계마다 다시 계산" />
                        <div style={{ paddingTop: 8 }}>
                            <KV items={MOCK_SIEGE.supply.map((i) => ({ k: i.k, v: i.v }))} />
                        </div>
                        <div style={{ paddingTop: 10, borderTop: '1px solid var(--line)', marginTop: 10 }}>
                            <Chip tone="rust">경고 — 보급로가 한 줄이다</Chip>
                            <p style={{ fontSize: 12, paddingTop: 6, color: 'var(--muted)' }}>
                                {MOCK_SIEGE.supplyWarning}
                            </p>
                        </div>
                    </Panel>

                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="포위 누적" sub="순 경계 2단계 — 성 안 군량 감소" />
                        <Table
                            headers={['순', '무슨 일']}
                            rows={MOCK_SIEGE.timeline.map((t) => [
                                <span key="w" style={{ whiteSpace: 'nowrap' }}>
                                    {t.when}
                                    {t.when.includes('지금') ? (
                                        <>
                                            {' '}
                                            <Chip tone="info">지금</Chip>
                                        </>
                                    ) : null}
                                </span>,
                                t.what,
                            ])}
                        />
                        <p style={{ fontSize: 12, paddingTop: 8, color: 'var(--muted)' }}>
                            눈금 없음 — 감소량과 포위 기간은 미정이다. 성 안 군량이 다하면 항복 권고가 쉬워진다.
                        </p>
                    </Panel>
                </div>

                <div style={{ display: 'grid', gap: 12 }}>
                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="공성 방침" sub="자기 턴 6단계에 판정 · 전투 중 조작은 없다" />
                        <div style={{ display: 'grid', gap: 10, paddingTop: 8 }}>
                            <div style={{ borderTop: '1px solid var(--line)', paddingTop: 8 }}>
                                <div style={{ fontWeight: 700 }}>강공</div>
                                <p style={{ fontSize: 13 }}>이번 턴에 함락을 판정한다.</p>
                                <p style={{ fontSize: 12, color: 'var(--muted)' }}>
                                    손실이 크다 — 방비가 높을수록 ▲. 봉인 계획(태세 · 퇴각 조건)을 쓴다.
                                </p>
                                <button type="button" className="os-button os-button--ghost os-button--sm">
                                    강공 계획 봉인
                                </button>
                            </div>
                            <div style={{ borderTop: '1px solid var(--line)', paddingTop: 8 }}>
                                <div style={{ fontWeight: 700, display: 'flex', alignItems: 'center', gap: 6 }}>
                                    포위 유지 <Chip tone="moss">지금 방침</Chip>
                                </div>
                                <p style={{ fontSize: 13 }}>싸우지 않고 성 안 군량을 말린다.</p>
                                <p style={{ fontSize: 12, color: 'var(--muted)' }}>
                                    순 경계마다 포위가 쌓인다 — 아군 군량도 ▼. 함락까지 걸리는 기간 [미정]
                                </p>
                            </div>
                        </div>
                    </Panel>

                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="항복 권고" sub="민심과 계책으로 판정한다" />
                        <div style={{ paddingTop: 8 }}>
                            <KV items={MOCK_SIEGE.surrender.map((i) => ({ k: i.k, v: i.v }))} />
                        </div>
                        <div style={{ paddingTop: 10 }}>
                            <button type="button" className="os-button os-button--primary os-button--sm">
                                항복 권고 보내기
                            </button>
                            <p style={{ fontSize: 12, paddingTop: 6, color: 'var(--muted)' }}>
                                받아들이면 싸우지 않고 성이 넘어온다. 거절해도 포위는 이어진다.
                            </p>
                            <p style={{ fontSize: 12, paddingTop: 4 }}>
                                <Chip tone="rust">{MOCK_SIEGE.lastAdvice}</Chip>
                            </p>
                        </div>
                    </Panel>
                </div>
            </div>
        </HwihaShell>
    );
}
