'use client';

import { useState } from 'react';
import { Chip, Panel, SectionHeader, Table } from '@opensamguk/ui';
import { api, isIntakeDenied, isIntakeQueued } from '@/lib/api';
import { HWIHA_RESOURCE_LABELS, useHwihaRead, type HwihaStock } from '@/lib/hwiha-reads';
import { useHwihaSession } from '@/lib/hwiha-session';
import { HwihaEmpty, hwihaReadNotice } from './HwihaStates';

type Toast = (msg: string, type: 'success' | 'error' | 'info') => void;

const fmt = new Intl.NumberFormat('ko-KR');

/** 비용 한 줄 — 0 인 자원은 뺀다. */
function costLine(stock: HwihaStock): string {
    const parts = HWIHA_RESOURCE_LABELS.filter(({ key }) => stock[key] > 0).map(({ key, label }) => `${label} ${fmt.format(stock[key])}`);
    return parts.length ? parts.join(' · ') : '없음';
}

/** 지속 입력 하나를 보내고 결과를 알린다. 접수(202)는 성공이 아니라 「다음 턴·순 경계에 효력」이다. */
function useDomesticSubmit(onToast: Toast, onDone: () => void) {
    const { generalId } = useHwihaSession();
    const [busy, setBusy] = useState(false);
    const submit = async (kind: 'placement' | 'policy' | 'work' | 'reduce', body: unknown, okText: string) => {
        if (generalId == null || busy) return;
        setBusy(true);
        try {
            const out = await api.hwihaDomestic(generalId, kind, body);
            if (isIntakeQueued(out)) {
                onToast(okText, 'success');
                onDone();
            } else if (isIntakeDenied(out)) onToast(out.reason ?? '접수되지 않았습니다.', 'error');
        } catch (e) {
            onToast(e instanceof Error ? e.message : '보내지 못했습니다.', 'error');
        } finally {
            setBusy(false);
        }
    };
    return { busy, submit };
}

const selectStyle: React.CSSProperties = { minHeight: 44, maxWidth: 220 };

/** 배치 — 직접 거느린 NPC 인물 카드를 현령·군단장·사자·정찰 자리에 앉힌다. 카드는 걸어서 부임한다. */
export function PlacementPanel({ onToast, refreshKey, onDone }: { onToast: Toast; refreshKey: number; onDone: () => void }) {
    const read = useHwihaRead((id, signal) => api.hwihaPosts(id, signal), [refreshKey]);
    const { busy, submit } = useDomesticSubmit(onToast, onDone);
    const [choice, setChoice] = useState<Record<number, string>>({});
    const notice = hwihaReadNotice(read, read.data?.status);
    const cards = read.data?.cards ?? [];
    const posts = read.data?.posts ?? [];
    // 선택지 값은 「자리|대상」 — 대상이 없는 자리(군단장·해제)는 자리만.
    const options = posts.flatMap((p) => {
        if (!p.available) return [];
        // 정찰은 육지 구역 어디든 되지만 서버가 목록을 주지 않는다 — 카드가 지금 선 구역에서 정찰한다.
        if (p.post === 'SCOUT') return [{ value: 'SCOUT|here', label: `${p.label} — 지금 선 구역` }];
        if (!p.targets || p.targets.length === 0) return [{ value: `${p.post}|`, label: p.label }];
        return p.targets.filter((t) => !t.occupied).map((t) => ({
            value: `${p.post}|${t.countyId ?? t.nationId ?? ''}`,
            label: `${p.label} — ${t.commanderyName ? `${t.commanderyName} ` : ''}${t.name}`,
        }));
    });
    const send = (cardId: number, value: string) => {
        const [post, target] = value.split('|');
        const body: Record<string, unknown> = { cardId, post };
        if (post === 'MAGISTRATE') body.countyId = Number(target);
        if (post === 'ENVOY') body.nationId = Number(target);
        if (post === 'SCOUT') {
            const here = cards.find((c) => c.cardId === cardId)?.provinceId;
            if (!here) {
                onToast('카드의 지금 위치를 알 수 없어 정찰을 보낼 수 없습니다.', 'error');
                return;
            }
            body.provinceId = here;
        }
        void submit('placement', body, '배치를 접수했습니다. 카드의 다음 턴부터 부임을 시작합니다.');
    };
    return (
        <Panel style={{ padding: 12 }}>
            <SectionHeader title="배치" sub="카드는 자기 턴마다 지도 위를 실제로 이동해 부임한다" />
            {notice ? <HwihaEmpty>{notice}</HwihaEmpty> : null}
            {!notice && cards.length === 0 ? <HwihaEmpty>앉힐 인물 카드가 없습니다.</HwihaEmpty> : null}
            {cards.length > 0 ? (
                <Table
                    headers={['인물', '지금 자리', '바꿀 자리', '']}
                    rows={cards.map((c) => [
                        <span key="n" style={{ fontWeight: 700, whiteSpace: 'nowrap' }}>{c.name}</span>,
                        <span key="a" style={{ whiteSpace: 'nowrap' }}>
                            {c.active ? `${c.active.postLabel}${c.active.target.label ? ` — ${c.active.target.label}` : ''}` : '미배치'}{' '}
                            {c.active?.state === 'MOVING' ? <Chip tone="info">부임 중</Chip> : null}
                            {c.pending ? <Chip tone="bronze">{`대기 · ${c.pending.postLabel}`}</Chip> : null}
                        </span>,
                        c.placeable ? (
                            <select
                                key="s"
                                className="os-inset"
                                style={selectStyle}
                                aria-label={`${c.name} 자리`}
                                value={choice[c.cardId] ?? ''}
                                onChange={(e) => setChoice((prev) => ({ ...prev, [c.cardId]: e.target.value }))}
                            >
                                <option value="">자리를 고르세요</option>
                                {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                                <option value="NONE|">자리에서 풀기</option>
                            </select>
                        ) : (
                            <span key="b" style={{ fontSize: 12, color: 'var(--rust)' }}>{c.blocked?.reason ?? '지금은 바꿀 수 없습니다'}</span>
                        ),
                        <button
                            key="go"
                            type="button"
                            className="os-button os-button--ghost os-button--sm"
                            disabled={busy || !c.placeable || !choice[c.cardId]}
                            onClick={() => send(c.cardId, choice[c.cardId])}
                        >
                            바꾸기
                        </button>,
                    ])}
                />
            ) : null}
            <p style={{ fontSize: 12, color: 'var(--muted)', padding: '8px 0 0', margin: 0 }}>
                사람 장수를 자리에 앉히는 것은 배치가 아니라 발령(조정 결정)입니다.
            </p>
        </Panel>
    );
}

/** 방침 — 현 방침 6종·군단 방침 5종. 현령 카드의 다음 턴에 현행이 되고, 효과는 순 경계마다 한 번. */
export function PolicyPanel({ onToast, refreshKey, onDone }: { onToast: Toast; refreshKey: number; onDone: () => void }) {
    const read = useHwihaRead((id, signal) => api.hwihaPolicies(id, signal), [refreshKey]);
    const { busy, submit } = useDomesticSubmit(onToast, onDone);
    const notice = hwihaReadNotice(read, read.data?.status);
    const counties = read.data?.counties ?? [];
    const corps = read.data?.corps ?? [];
    const countyOptions = read.data?.countyOptions ?? [];
    const corpsOptions = read.data?.corpsOptions ?? [];
    return (
        <Panel style={{ padding: 12 }}>
            <SectionHeader
                title="방침"
                sub="자리나 군단에 걸어 두는 지속 규칙"
                actions={read.data?.defaultPolicy ? <Chip>{`빈자리 기본 · ${read.data.defaultPolicy.label}`}</Chip> : null}
            />
            {notice ? <HwihaEmpty>{notice}</HwihaEmpty> : null}
            {!notice && counties.length === 0 && corps.length === 0 ? <HwihaEmpty>방침을 걸 수 있는 현·군단이 없습니다.</HwihaEmpty> : null}
            {counties.length > 0 ? (
                <Table
                    headers={['현', '현령', '지금 방침', '바꾸기']}
                    rows={counties.map((c) => [
                        <span key="n" style={{ whiteSpace: 'nowrap' }}>
                            {c.name} {c.commanderyName ? <span style={{ fontSize: 12, color: 'var(--muted)' }}>{c.commanderyName}</span> : null}
                        </span>,
                        c.seat ? c.seat.name : <Chip key="s" tone="rust">빈자리</Chip>,
                        <span key="e" style={{ whiteSpace: 'nowrap' }}>
                            {c.effective ? c.effective.label : '—'}{' '}
                            {c.effective?.source === 'COMMANDERY' ? <Chip tone="info">군 방침</Chip> : null}
                            {c.effective?.source === 'DEFAULT' ? <Chip>기본</Chip> : null}
                            {c.pending ? <Chip tone="bronze">{`대기 · ${c.pending.label ?? '해제'}`}</Chip> : null}
                        </span>,
                        c.settable ? (
                            <select
                                key="p"
                                className="os-inset"
                                style={selectStyle}
                                aria-label={`${c.name} 방침`}
                                value=""
                                disabled={busy}
                                onChange={(e) => e.target.value && void submit(
                                    'policy',
                                    { scope: 'COUNTY', countyId: c.countyId, policy: e.target.value },
                                    `${c.name} 방침을 접수했습니다. 현령의 다음 턴에 걸리고 순 경계마다 적용됩니다.`,
                                )}
                            >
                                <option value="">방침 고르기</option>
                                {countyOptions.map((o) => <option key={o.code} value={o.code}>{o.label}</option>)}
                                <option value="NONE">방침 거두기</option>
                            </select>
                        ) : (
                            <span key="b" style={{ fontSize: 12, color: 'var(--muted)' }}>{c.blocked?.reason ?? '권한 없음'}</span>
                        ),
                    ])}
                />
            ) : null}
            {corps.length > 0 ? (
                <div style={{ paddingTop: 12 }}>
                    <SectionHeader title="군단 방침" as="h4" />
                    <Table
                        headers={['군단', '지금 방침', '바꾸기']}
                        rows={corps.map((c) => [
                            c.commanderName ?? '군단',
                            <span key="a">{c.active?.label ?? '—'} {c.pending ? <Chip tone="bronze">{`대기 · ${c.pending.label ?? '해제'}`}</Chip> : null}</span>,
                            c.settable ? (
                                <select
                                    key="p"
                                    className="os-inset"
                                    style={selectStyle}
                                    aria-label="군단 방침"
                                    value=""
                                    disabled={busy}
                                    onChange={(e) => e.target.value && void submit(
                                        'policy',
                                        { scope: 'CORPS', orderId: c.orderId, policy: e.target.value },
                                        '군단 방침을 접수했습니다.',
                                    )}
                                >
                                    <option value="">방침 고르기</option>
                                    {corpsOptions.map((o) => <option key={o.code} value={o.code}>{o.label}</option>)}
                                    <option value="NONE">방침 거두기</option>
                                </select>
                            ) : (
                                <span key="b" style={{ fontSize: 12, color: 'var(--muted)' }}>{c.blocked?.reason ?? '권한 없음'}</span>
                            ),
                        ])}
                    />
                </div>
            ) : null}
        </Panel>
    );
}

/** 공사 — 현마다 하나씩, 순 경계마다 진척하며 그 현 창고의 자원을 나눠 쓴다. */
export function WorksPanel({ onToast, refreshKey, onDone }: { onToast: Toast; refreshKey: number; onDone: () => void }) {
    const read = useHwihaRead((id, signal) => api.hwihaWorks(id, signal), [refreshKey]);
    const roads = useHwihaRead((id, signal) => api.hwihaRoadForts(id, signal), [refreshKey]);
    const { busy, submit } = useDomesticSubmit(onToast, onDone);
    const [roadChoice, setRoadChoice] = useState<Record<number, string>>({});
    const [fortChoice, setFortChoice] = useState<Record<number, string>>({});
    const notice = hwihaReadNotice(read, read.data?.status);
    const counties = read.data?.counties ?? [];
    const gates = roads.data?.gates ?? [];
    const roadMode = roads.data?.roadMode === true;
    return (
        <Panel style={{ padding: 12 }}>
            <SectionHeader title="공사" sub="순 경계마다 진척" />
            {notice ? <HwihaEmpty>{notice}</HwihaEmpty> : null}
            {!notice && counties.length === 0 ? <HwihaEmpty>공사를 맡길 현이 없습니다.</HwihaEmpty> : null}
            <div style={{ display: 'grid', gap: 10, paddingTop: 8 }}>
                {counties.map((c) => (
                    <div key={c.countyId} style={{ borderTop: '1px solid var(--line)', paddingTop: 8, display: 'grid', gap: 6 }}>
                        <div style={{ display: 'flex', gap: 6, alignItems: 'baseline', flexWrap: 'wrap' }}>
                            <strong>{c.name}</strong>
                            {c.commanderyName ? <span style={{ fontSize: 12, color: 'var(--muted)' }}>{c.commanderyName}</span> : null}
                            {c.completed.map((w) => <Chip key={w.work} tone="moss">{w.label}</Chip>)}
                            {!c.active && c.completed.some(w => w.work === 'FORTIFICATION') && (
                                <button type="button" className="os-button os-button--ghost os-button--sm" disabled={busy}
                                    onClick={() => void submit('reduce', { countyId: c.countyId, work: 'FORTIFICATION' },
                                        `${c.name} 성방을 감축했습니다.`)}>성방 감축</button>
                            )}
                        </div>
                        {c.active ? (
                            <div style={{ display: 'grid', gap: 4 }}>
                                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13 }}>
                                    <span>{c.active.label}</span>
                                    <span className="os-num">{`${c.active.percent}% · ${c.active.remainingPhases}순 남음`}</span>
                                </div>
                                <div style={{ height: 4, background: 'var(--line)' }}>
                                    <i style={{ display: 'block', height: '100%', width: `${c.active.percent}%`, background: 'var(--bronze)' }} />
                                </div>
                                <span style={{ fontSize: 12, color: c.active.stopReasonText ? 'var(--rust)' : 'var(--muted)' }}>
                                    {c.active.stopReasonText ?? (c.active.startsAtNextBoundary ? '다음 순 경계부터 진척합니다.' : `남은 비용 ${costLine(c.active.remainingCost)}`)}
                                </span>
                            </div>
                        ) : (
                            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                                {c.startable.map((w) => w.work === 'ROAD' && roadMode ? (() => {
                                    const choices = gates.filter((gate) => gate.buildable && !gate.active &&
                                        (gate.fromProvinceId === c.provinceId || gate.toProvinceId === c.provinceId));
                                    const chosen = choices.find((choice) => choice.edgeId === roadChoice[c.countyId])?.edgeId ?? choices[0]?.edgeId;
                                    return <span key={w.work} style={{ display: 'inline-flex', gap: 4 }}>
                                        <select aria-label={`${c.name} 도로 접경`} value={chosen ?? ''}
                                            onChange={(event) => setRoadChoice((prev) => ({ ...prev, [c.countyId]: event.target.value }))}>
                                            {choices.length === 0 && <option value="">개척할 접경 없음</option>}
                                            {choices.map((gate) => <option key={gate.edgeId} value={gate.edgeId}>
                                                {gate.historicalRouteIds.length ? `${gate.historicalRouteIds.join(', ')} · ` : ''}{gate.edgeId}
                                            </option>)}
                                        </select>
                                        <button type="button" className="os-button os-button--ghost os-button--sm"
                                            disabled={busy || !w.available || !chosen}
                                            onClick={() => void submit('work', { countyId: c.countyId, work: w.work, edgeId: chosen },
                                                `${c.name}의 도로 개척을 접수했습니다.`)}>도로 개척</button>
                                    </span>;
                                })() : w.work === 'FORTIFICATION' && roadMode ? (() => {
                                    const choices = gates.filter((gate) => gate.active)
                                        .flatMap((gate) => gate.fortCells.filter((cell) => cell.provinceId === c.provinceId)
                                            .filter((cell) => !roads.data?.forts.some((fort) => fort.row === cell.row && fort.col === cell.col))
                                            .map((cell) => ({ value: `${gate.edgeId}|${cell.row}|${cell.col}`,
                                                label: `${gate.historicalRouteIds.join(', ') || '길목'} · ${cell.row}, ${cell.col}` })));
                                    const chosen = choices.find((choice) => choice.value === fortChoice[c.countyId])?.value ?? choices[0]?.value;
                                    return <span key={w.work} style={{ display: 'inline-flex', gap: 4 }}>
                                        <select aria-label={`${c.name} 보루 위치`} value={chosen ?? ''}
                                            onChange={(event) => setFortChoice((prev) => ({ ...prev, [c.countyId]: event.target.value }))}>
                                            {choices.length === 0 && <option value="">건설할 길목 없음</option>}
                                            {choices.map((choice) => <option key={choice.value} value={choice.value}>{choice.label}</option>)}
                                        </select>
                                        <button type="button" className="os-button os-button--ghost os-button--sm"
                                            disabled={busy || !w.available || !chosen}
                                            onClick={() => {
                                                const [edgeId, row, col] = chosen.split('|');
                                                void submit('work', { countyId: c.countyId, work: w.work, edgeId,
                                                    row: Number(row), col: Number(col) }, `${c.name}의 보루 건설을 접수했습니다.`);
                                            }}>보루 건설</button>
                                    </span>;
                                })() : (
                                    <button
                                        key={w.work}
                                        type="button"
                                        className="os-button os-button--ghost os-button--sm"
                                        disabled={busy || !w.available}
                                        title={w.available ? `${costLine(w.cost)} · 약 ${w.estimatedPhases}순` : w.blocked?.reason}
                                        onClick={() => void submit('work', { countyId: c.countyId, work: w.work }, `${c.name} ${w.label} 공사를 접수했습니다. 다음 순 경계부터 진척합니다.`)}
                                    >
                                        {w.label}
                                    </button>
                                ))}
                            </div>
                        )}
                        {c.warehouse ? (
                            <span style={{ fontSize: 12, color: 'var(--muted)' }}>{`창고 ${costLine(c.warehouse)}`}</span>
                        ) : null}
                    </div>
                ))}
            </div>
        </Panel>
    );
}
