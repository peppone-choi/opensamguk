'use client';

import { useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { Chip, KV, Panel, Portrait, SectionHeader, Table } from '@opensamguk/ui';
import GameShell from '@/components/GameShell';
import { Empty, hwihaReadNotice } from '@/components/campaign/GameStates';
import { api } from '@/lib/api';
import { useHwihaRead, type PersonCard } from '@/lib/hwiha-reads';

const loyaltyTone = (loyalty: number) => (loyalty >= 80 ? 'moss' : loyalty < 50 ? 'rust' : 'info');

/** 아직 입력이 없는 행동 — 숨기지 않고 사유와 함께 비활성으로 둔다(표시 원칙). */
const NOT_YET = '아직 이 입력이 없습니다';

function PersonDetail({ person }: { person: PersonCard }) {
    const s = person.stats;
    const a = person.aptitudes;
    return (
        <Panel style={{ padding: 12 }}>
            <div style={{ display: 'grid', gridTemplateColumns: '126px minmax(0, 1fr)', gap: 12, alignItems: 'start' }}>
                <Portrait picture={person.picture} imageServer={person.imageServer} size="card-126" alt={`${person.name} 초상`} />
                <div style={{ minWidth: 0 }}>
                    <div style={{ fontFamily: 'var(--font-serif)', fontSize: 20, fontWeight: 900 }}>{person.name}</div>
                    <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', paddingTop: 6 }}>
                        <Chip tone={loyaltyTone(person.loyalty)}>{`충성 ${person.loyalty}`}</Chip>
                        <Chip tone="bronze">{`코스트 ${person.cost ?? '—'}`}</Chip>
                        {person.departureOrder != null ? <Chip tone="rust">{`이탈 판정 ${person.departureOrder}번째`}</Chip> : null}
                    </div>
                </div>
            </div>

            <div style={{ paddingTop: 12 }}>
                <SectionHeader title="능력" as="h4" />
                {s ? (
                    <KV
                        items={[
                            { k: '통솔', v: String(s.leadership) },
                            { k: '무력', v: String(s.strength) },
                            { k: '지력', v: String(s.intel) },
                            { k: '정치', v: String(s.politics) },
                            { k: '매력', v: String(s.charm) },
                        ]}
                    />
                ) : (
                    <Empty>능력치가 없는 카드입니다.</Empty>
                )}
            </div>

            <div style={{ paddingTop: 12 }}>
                <SectionHeader title="역할 적성" sub="능력치에서 나온 값" as="h4" />
                {a ? (
                    <KV
                        items={[
                            { k: '장 · 군단', v: String(a.command) },
                            { k: '리 · 내정', v: String(a.administration) },
                            { k: '사 · 계책', v: String(a.strategy) },
                            { k: '사자 · 외교', v: String(a.envoy) },
                        ]}
                    />
                ) : (
                    <Empty>능력치가 없어 적성을 매길 수 없습니다.</Empty>
                )}
            </div>

            <div style={{ paddingTop: 12 }}>
                <SectionHeader title="결속" as="h4" />
                {person.bonds.length === 0 ? (
                    <Empty>확인된 결속이 없습니다.</Empty>
                ) : (
                    <div style={{ display: 'grid', gap: 6, paddingTop: 6 }}>
                        {person.bonds.map((b) => (
                            <div key={`${b.kind}-${b.nativeCountyName ?? ''}`} style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
                                <Chip tone="bronze">{b.label}</Chip>
                                {b.nativeCountyName ? <span>{b.nativeCountyName}</span> : null}
                                {b.sameAsLord ? <Chip tone="moss">주공과 같은 고향</Chip> : null}
                            </div>
                        ))}
                    </div>
                )}
            </div>

            <div style={{ paddingTop: 12 }}>
                <SectionHeader title="자리와 임무" as="h4" />
                <KV
                    items={[
                        { k: '자리', v: person.roleLabel ?? '미배치' },
                        { k: '임무', v: person.taskLabel ?? '없음' },
                    ]}
                />
            </div>

            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', paddingTop: 12 }}>
                <button type="button" className="os-button os-button--ghost os-button--sm" disabled title={NOT_YET}>자리에 배치</button>
                <button type="button" className="os-button os-button--ghost os-button--sm" disabled title={NOT_YET}>보물 부착</button>
                <button type="button" className="os-button os-button--ghost os-button--sm" disabled title={NOT_YET}>내보내기</button>
            </div>
        </Panel>
    );
}

/**
 * 휘하 편성 — 시안 Main.
 *
 * 명망은 거느릴 수 있는 휘하 코스트의 상한이다(설계 §2.8·§6.6). 삼모의 「장수 코스트 201/420」과
 * 같은 자리이므로 `⚖ 합 / 상한` 표기를 쓴다. 값은 `GET /api/retinue` 에서 온다.
 */
export default function RetinuePage() {
    const read = useHwihaRead((id, signal) => api.hwihaRetinue(id, signal));
    // 작전실 장수 목록에서 ?person=<retainerId> 로 들어오면 그 인물을 연다.
    const linked = Number(useSearchParams().get('person'));
    const [selectedId, setSelectedId] = useState<number | null>(Number.isInteger(linked) && linked > 0 ? linked : null);
    const notice = hwihaReadNotice(read, read.data?.status);
    const people = read.data?.people ?? [];
    const units = read.data?.units ?? [];
    const selected = people.find((p) => p.retainerId === selectedId) ?? people[0] ?? null;
    const commanderName = (retainerId: number | null) =>
        retainerId == null ? '—' : people.find((p) => p.retainerId === retainerId)?.name ?? '—';

    return (
        <GameShell title="휘하 편성" tab="배치">
            <div style={{ padding: 12, display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 420px', gap: 12, alignItems: 'start' }}>
                <div style={{ display: 'grid', gap: 12, minWidth: 0 }}>
                    <Panel style={{ padding: 12 }}>
                        <SectionHeader
                            title="인물 카드"
                            sub={`직접 거느린 인물 ${people.length}`}
                            actions={
                                read.data ? (
                                    <Chip tone={read.data.overCapacity ? 'rust' : 'bronze'}>
                                        {`⚖ ${read.data.costSum ?? '—'} / ${read.data.renown ?? '—'}`}
                                    </Chip>
                                ) : null
                            }
                        />
                        {notice ? <Empty>{notice}</Empty> : null}
                        {!notice && people.length === 0 ? <Empty>거느린 인물이 없습니다.</Empty> : null}
                        {people.length > 0 ? (
                            <Table
                                headers={['인물', '결속', '충성', '자리', '코스트']}
                                rows={people.map((p) => [
                                    <button
                                        key="n"
                                        type="button"
                                        className="os-button os-button--ghost os-button--sm"
                                        aria-pressed={selected?.retainerId === p.retainerId}
                                        onClick={() => setSelectedId(p.retainerId)}
                                        style={{ whiteSpace: 'nowrap', display: 'inline-flex', alignItems: 'center', gap: 8, minHeight: 44 }}
                                    >
                                        <Portrait picture={p.picture} imageServer={p.imageServer} size="card-36" alt="" />
                                        {p.name}
                                    </button>,
                                    p.bonds.length ? p.bonds.map((b) => b.label).join(' · ') : '—',
                                    <Chip key="l" tone={loyaltyTone(p.loyalty)}>{p.loyalty}</Chip>,
                                    p.roleLabel ? p.roleLabel : <Chip key="r" tone="rust">미배치</Chip>,
                                    <span key="c" className="os-num">{p.cost ?? '—'}</span>,
                                ])}
                            />
                        ) : null}
                        <div style={{ paddingTop: 10 }}>
                            <button type="button" className="os-button os-button--ghost os-button--sm" disabled title="휘하 규칙에는 아직 등용 입력이 없습니다">
                                등용 (장수 행동)
                            </button>
                        </div>
                    </Panel>

                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="부대 카드" sub={`부곡 ${units.length}`} />
                        {!notice && units.length === 0 ? <Empty>편성한 부대가 없습니다.</Empty> : null}
                        {units.length > 0 ? (
                            <Table
                                headers={['부대', '병종', '병력', '훈련', '사기', '군량', '지휘']}
                                rows={units.map((u) => [
                                    u.name,
                                    u.crewTypeName,
                                    <span key="t" className="os-num">{u.troops}</span>,
                                    <span key="tr" className="os-num">{u.training}</span>,
                                    <span key="m" className="os-num">{u.morale}</span>,
                                    <span key="p" className="os-num">{`${u.provisionMonths}달`}</span>,
                                    commanderName(u.commanderRetainerId),
                                ])}
                            />
                        ) : null}
                    </Panel>
                </div>

                {selected ? <PersonDetail person={selected} /> : (
                    <Panel style={{ padding: 12 }}>
                        <Empty>인물을 고르면 여기에 카드가 열립니다.</Empty>
                    </Panel>
                )}
            </div>
        </GameShell>
    );
}
