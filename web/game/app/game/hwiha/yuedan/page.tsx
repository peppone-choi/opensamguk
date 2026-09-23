'use client';

import { Chip, KV, Panel, SectionHeader, Table } from '@opensamguk/ui';
import HwihaShell from '@/components/HwihaShell';
import { HwihaEmpty, hwihaReadNotice } from '@/components/hwiha/HwihaStates';
import { api } from '@/lib/api';
import { useHwihaRead } from '@/lib/hwiha-reads';

/** 명망이 오르고 떨어지는 길 — 정본 설계 §2.8 그대로. */
const RISING = ['전공', '치적', '관직', '결속 사건'];
const FALLING = ['패전', '배신', '실정', '발령 거절'];

/** 도장 「0200-03」 → 「200년 3월」. 형식이 다르면 그대로 보인다. */
function stampLabel(stamp: string | null): string | null {
    if (!stamp) return null;
    const m = /^(\d+)-(\d+)$/.exec(stamp);
    return m ? `${Number(m[1])}년 ${Number(m[2])}월` : stamp;
}

/**
 * 월단평 — 시안 Yuedan.
 *
 * 매월 상순 명망을 갱신하고 순위를 발표한다(정본 설계 §2.8·§5.2). 순위는 월 경계의
 * `HwihaMonthlyAssessment` 가 남긴 것을 `GET /api/hwiha/yuedan` 으로 읽는다.
 */
export default function YuedanPage() {
    const yuedan = useHwihaRead((id, signal) => api.hwihaYuedan(id, signal));
    const retinue = useHwihaRead((id, signal) => api.hwihaRetinue(id, signal));
    const notice = hwihaReadNotice(yuedan, yuedan.data?.status);
    const self = yuedan.data?.self ?? null;
    const ranking = yuedan.data?.ranking ?? [];
    const when = stampLabel(yuedan.data?.stamp ?? null);
    const departures = (retinue.data?.people ?? [])
        .filter((p) => p.departureOrder != null)
        .sort((a, b) => (a.departureOrder ?? 0) - (b.departureOrder ?? 0));

    return (
        <HwihaShell title="월단평" tab="장수 행동">
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
                    <SectionHeader title={when ? `${when} 월단평` : '월단평'} sub="달마다 새로 매긴다" />
                    {notice ? <HwihaEmpty>{notice}</HwihaEmpty> : null}
                    {!notice && yuedan.data?.status === 'NOT_ASSESSED' ? (
                        <HwihaEmpty>아직 첫 월단평이 없습니다. 다음 달 상순에 처음 발표합니다.</HwihaEmpty>
                    ) : null}
                    {ranking.length > 0 ? (
                        <Table
                            headers={['순위', '장수', '세력', '명망', '이번 달 사유']}
                            rows={ranking.map((r) => [
                                <span key="r" className="os-num">{r.rank}</span>,
                                <span key="g" style={{ whiteSpace: 'nowrap' }}>
                                    {r.name} {r.generalId === self?.generalId ? <Chip tone="info">나</Chip> : null}
                                </span>,
                                <span key="n" style={{ display: 'inline-flex', alignItems: 'center', gap: 4, whiteSpace: 'nowrap' }}>
                                    {r.nationColor ? (
                                        <span aria-hidden style={{ width: 10, height: 10, borderRadius: 2, background: r.nationColor, display: 'inline-block' }} />
                                    ) : null}
                                    {r.nationName ?? '재야'}
                                </span>,
                                <span key="v" className="os-num">{r.renown}</span>,
                                <span key="why" style={{ display: 'inline-flex', gap: 4, flexWrap: 'wrap' }}>
                                    {(r.reasons ?? []).length === 0 ? <span style={{ color: 'var(--muted)' }}>—</span> : null}
                                    {(r.reasons ?? []).map((why) => (
                                        <Chip key={why.kind} tone={why.amount >= 0 ? 'moss' : 'rust'}>
                                            {`${why.amount >= 0 ? '▲' : '▼'} ${why.label}`}
                                        </Chip>
                                    ))}
                                </span>,
                            ])}
                        />
                    ) : null}

                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, paddingTop: 12 }}>
                        <div>
                            <div style={{ fontSize: 12, color: 'var(--muted)', paddingBottom: 4 }}>오르는 경로</div>
                            <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                                {RISING.map((r) => <Chip key={r} tone="moss">{r}</Chip>)}
                            </div>
                        </div>
                        <div>
                            <div style={{ fontSize: 12, color: 'var(--muted)', paddingBottom: 4 }}>떨어지는 경로</div>
                            <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                                {FALLING.map((r) => <Chip key={r} tone="rust">{r}</Chip>)}
                            </div>
                        </div>
                    </div>
                </Panel>

                <div style={{ display: 'grid', gap: 12 }}>
                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="내 명망" sub="매월 상순 갱신" />
                        <div className="os-num" style={{ fontSize: 36, fontWeight: 900, padding: '8px 0', fontFamily: 'var(--font-serif)' }}>
                            {self?.renown ?? '—'}
                        </div>
                        {self?.overCapacity ? <Chip tone="rust">코스트 초과 — 이탈 판정 대상</Chip> : null}
                        <div style={{ paddingTop: 8 }}>
                            <KV
                                items={[
                                    { k: '휘하 코스트 합', v: self?.retinueCost == null ? '—' : String(self.retinueCost) },
                                    { k: '코스트 상한', v: self?.renown != null ? String(self.renown) : '—' },
                                ]}
                            />
                        </div>
                        <p style={{ fontSize: 12, color: 'var(--muted)', paddingTop: 8, margin: 0 }}>
                            명망이 곧 거느릴 수 있는 휘하 코스트의 상한입니다.
                        </p>
                        <div style={{ paddingTop: 10 }}>
                            <div style={{ fontSize: 12, color: 'var(--muted)', paddingBottom: 4 }}>다음 월단평에 반영될 일</div>
                            {(yuedan.data?.selfPendingEvents ?? []).length === 0 ? (
                                <span style={{ fontSize: 12, color: 'var(--muted)' }}>아직 없습니다.</span>
                            ) : (
                                <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                                    {(yuedan.data?.selfPendingEvents ?? []).map((e) => (
                                        <Chip key={`${e.kind}-${e.stamp}`} tone={e.amount >= 0 ? 'moss' : 'rust'}>
                                            {`${e.amount >= 0 ? '▲' : '▼'} ${e.label}${e.sourceLabel && e.sourceLabel !== e.label ? ` · ${e.sourceLabel}` : ''}`}
                                        </Chip>
                                    ))}
                                </div>
                            )}
                        </div>
                    </Panel>

                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="이탈 판정 순서" sub="코스트가 상한을 넘으면 충성이 낮은 사람부터" />
                        {departures.length === 0 ? (
                            <HwihaEmpty>{hwihaReadNotice(retinue, retinue.data?.status) ?? '코스트가 상한 안이라 이탈 판정을 받을 사람이 없습니다.'}</HwihaEmpty>
                        ) : (
                            <Table
                                headers={['순서', '인물', '충성', '코스트']}
                                rows={departures.map((p) => [
                                    <span key="o" className="os-num">{p.departureOrder}</span>,
                                    p.name,
                                    <span key="l" className="os-num">{p.loyalty}</span>,
                                    <span key="c" className="os-num">{p.cost ?? '—'}</span>,
                                ])}
                            />
                        )}
                    </Panel>
                </div>
            </div>
        </HwihaShell>
    );
}
