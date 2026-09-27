'use client';

import { Chip, Panel, SectionHeader, Table } from '@opensamguk/ui';
import GameShell from '@/components/GameShell';
import { Empty, campaignReadNotice } from '@/components/campaign/GameStates';
import { api } from '@/lib/api';
import { CAMPAIGN_RESOURCE_LABELS, useCampaignRead } from '@/lib/campaign-reads';

const fmt = new Intl.NumberFormat('ko-KR');

/**
 * 보급망 · 창고 — 시안 Supply.
 *
 * 창고는 다섯 자원을 모두 실물로 들고 있고, 국고는 수도 창고 안에 있다 — 수도가 함락되면 국고를
 * 빼앗긴다. 재고는 `GET /api/warehouses`, 끊김은 월 보급이 매기는 城 보급 상태다.
 */
export default function SupplyPage() {
    const read = useCampaignRead((id, signal) => api.warehouses(id, signal));
    const warehouses = read.data?.warehouses ?? [];
    const notice = campaignReadNotice(read, read.data?.status);
    const cut = warehouses.filter((w) => !w.supplied);

    return (
        <GameShell title="보급망 · 창고" tab="배치">
            <div style={{ padding: 12, display: 'grid', gap: 12 }}>
                <Panel style={{ padding: 12 }}>
                    <SectionHeader title="창고별 재고" sub="다섯 자원 모두 실물" actions={<Chip>{`${warehouses.length}곳`}</Chip>} />
                    {notice ? <Empty>{notice}</Empty> : null}
                    {!notice && warehouses.length === 0 ? (
                        <Empty>우리 세력의 창고가 없습니다.</Empty>
                    ) : null}
                    {warehouses.length > 0 ? (
                        <Table
                            headers={['창고', ...CAMPAIGN_RESOURCE_LABELS.map((r) => r.label)]}
                            rows={warehouses.map((w) => [
                                <span key="n" style={{ whiteSpace: 'nowrap' }}>
                                    {w.name}{' '}
                                    {w.isCapital ? <Chip tone="bronze">수도</Chip> : null}{' '}
                                    {!w.supplied ? <Chip tone="rust">고립</Chip> : null}
                                </span>,
                                ...CAMPAIGN_RESOURCE_LABELS.map((r) => (
                                    <span key={r.key} className="os-num">
                                        {fmt.format(w.stock[r.key])}
                                    </span>
                                )),
                            ])}
                        />
                    ) : null}
                    {read.data?.invalidCount ? (
                        <p style={{ fontSize: 12, paddingTop: 8, color: 'var(--rust)' }}>
                            {`저장된 값을 읽지 못한 창고 ${read.data.invalidCount}곳은 빠져 있습니다.`}
                        </p>
                    ) : null}
                    <p style={{ fontSize: 12, paddingTop: 8, color: 'var(--muted)' }}>
                        국고는 수도 창고에 있습니다. 수도가 함락되면 국고를 잃습니다.
                    </p>
                </Panel>

                <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 380px', gap: 12, alignItems: 'start' }}>
                    <Panel style={{ padding: 12 }}>
                        <SectionHeader
                            title="끊긴 곳"
                            sub="월 경계마다 다시 계산"
                            actions={<Chip tone={cut.length ? 'rust' : 'moss'}>{cut.length}</Chip>}
                        />
                        {cut.length === 0 ? (
                            <Empty>{notice ?? '끊긴 창고가 없습니다. 모든 창고가 수도와 이어져 있습니다.'}</Empty>
                        ) : (
                            <div style={{ display: 'grid', gap: 6, paddingTop: 8 }}>
                                {cut.map((w) => (
                                    <div key={w.cityId} style={{ borderTop: '1px solid var(--line)', paddingTop: 6 }}>
                                        <strong>{w.name}</strong>{' '}
                                        {w.commanderyName ? <span style={{ color: 'var(--muted)', fontSize: 12 }}>{w.commanderyName}</span> : null}
                                        <p style={{ fontSize: 13, paddingTop: 4, margin: 0 }}>
                                            수도와 끊겨 제 창고만 씁니다.
                                        </p>
                                    </div>
                                ))}
                            </div>
                        )}
                    </Panel>

                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="녹봉 미지급 위험" sub="월 경계(상순) 지급" />
                        <Empty>녹봉 규칙이 아직 없습니다. 규칙이 생기면 지급 못 할 사람이 여기 나옵니다.</Empty>
                    </Panel>
                </div>
            </div>
        </GameShell>
    );
}
