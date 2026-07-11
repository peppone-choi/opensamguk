'use client';

import { useEffect, useMemo, useState } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import { api, isIntakeDenied, isIntakeQueued, type CommandResultResponse } from '../../../lib/api';
import type { NpcPolicyResponse, NpcPolicyValue } from '../../../types/game';

interface PolicyFieldDef {
    key: string;
    title: string;
    info: string;
    step: number;
    min?: number;
    max?: number;
    ratio?: boolean;
}

const POLICY_FIELDS: PolicyFieldDef[] = [
    { key: 'reqNationGold', title: '국가 권장 금', info: '이보다 많으면 포상, 적으면 몰수/헌납합니다.(긴급포상 제외)', step: 100 },
    { key: 'reqNationRice', title: '국가 권장 쌀', info: '이보다 많으면 포상, 적으면 몰수/헌납합니다.(긴급포상 제외)', step: 100 },
    { key: 'reqHumanWarUrgentGold', title: '유저전투장 긴급포상 금', info: '유저장긴급포상시 이보다 금이 적은 장수에게 포상합니다.', step: 100 },
    { key: 'reqHumanWarUrgentRice', title: '유저전투장 긴급포상 쌀', info: '유저장긴급포상시 이보다 쌀이 적은 장수에게 포상합니다.', step: 100 },
    { key: 'reqHumanWarRecommandGold', title: '유저전투장 권장 금', info: '유저전투장에게 주는 금입니다. 이보다 적으면 포상합니다.', step: 100 },
    { key: 'reqHumanWarRecommandRice', title: '유저전투장 권장 쌀', info: '유저전투장에게 주는 쌀입니다. 이보다 적으면 포상합니다.', step: 100 },
    { key: 'reqHumanDevelGold', title: '유저내정장 권장 금', info: '유저내정장에게 주는 금입니다. 이보다 적으면 포상합니다.', step: 100 },
    { key: 'reqHumanDevelRice', title: '유저내정장 권장 쌀', info: '유저내정장에게 주는 쌀입니다. 이보다 적으면 포상합니다.', step: 100 },
    { key: 'reqNPCWarGold', title: 'NPC전투장 권장 금', info: 'NPC전투장에게 주는 금입니다. 이보다 적으면 포상합니다.', step: 100 },
    { key: 'reqNPCWarRice', title: 'NPC전투장 권장 쌀', info: 'NPC전투장에게 주는 쌀입니다. 이보다 적으면 포상합니다.', step: 100 },
    { key: 'reqNPCDevelGold', title: 'NPC내정장 권장 금', info: 'NPC내정장에게 주는 금입니다. 이보다 5배 더 많다면 헌납합니다.', step: 100 },
    { key: 'reqNPCDevelRice', title: 'NPC내정장 권장 쌀', info: 'NPC내정장에게 주는 쌀입니다. 이보다 5배 더 많다면 헌납합니다.', step: 100 },
    { key: 'minimumResourceActionAmount', title: '포상/몰수/헌납/삼/팜 최소 단위', info: '연산결과가 이 단위보다 적다면 수행하지 않습니다.', step: 100, min: 100 },
    { key: 'maximumResourceActionAmount', title: '포상/몰수/헌납/삼/팜 최대 단위', info: '연산결과가 이 단위보다 크다면, 이 값에 맞춥니다.', step: 100, min: 100 },
    { key: 'minWarCrew', title: '최소 전투 가능 병력 수', info: '이보다 적을 때에는 징병을 시도합니다.', step: 50 },
    { key: 'minNPCRecruitCityPopulation', title: 'NPC 최소 징병 가능 인구 수', info: '도시의 인구가 이보다 낮으면 NPC는 도시에서 징병하지 않고 후방 워프합니다.', step: 100 },
    { key: 'safeRecruitCityPopulationRatio', title: '제자리 징병 허용 인구율(%)', info: "전쟁 시 후방 발령, 후방 워프의 기준 인구입니다. 이보다 많다면 '충분하다'고 판단합니다.", step: 0.5, min: 0, max: 100, ratio: true },
    { key: 'minNPCWarLeadership', title: 'NPC 전투 참여 통솔 기준', info: '이 수치보다 같거나 높으면 NPC전투장으로 분류됩니다.', step: 5 },
    { key: 'properWarTrainAtmos', title: '훈련/사기진작 목표치', info: '훈련/사기진작 기준치입니다. 이보다 같거나 높으면 출병합니다.', step: 5, min: 20, max: 100 },
    { key: 'cureThreshold', title: '요양 기준', info: '요양 기준 %입니다. 이보다 많이 부상을 입으면 요양합니다.', step: 5, min: 10, max: 100 },
];

const sectionBarStyle: React.CSSProperties = {
    textAlign: 'center',
    border: '0.5px solid var(--border-medium)',
    background: 'var(--bg-elevated)',
    padding: 'var(--space-xs) var(--space-sm)',
    fontWeight: 600,
    fontSize: 'var(--text-sm)',
    marginBottom: 'var(--space-sm)',
};

const subBarStyle: React.CSSProperties = {
    textAlign: 'center',
    border: '0.5px solid var(--border-subtle)',
    background: 'var(--bg-hover)',
    padding: 'var(--space-xs)',
    fontSize: 'var(--text-xs)',
    color: 'var(--text-secondary)',
    marginBottom: 'var(--space-xs)',
};

const buttonStyle: React.CSSProperties = {
    fontSize: 'var(--text-xs)',
    padding: 'var(--space-xs) var(--space-sm)',
};

function sameJson(a: unknown, b: unknown) {
    return JSON.stringify(a) === JSON.stringify(b);
}

function clonePolicy<T>(value: T): T {
    return JSON.parse(JSON.stringify(value)) as T;
}

function lastSetterLine(setter: string | null, date: string | null) {
    return `최근 설정: ${setter ?? '-없음-'} (${date ?? '설정 기록 없음'})`;
}

function displayValue(field: PolicyFieldDef, value: NpcPolicyValue | undefined): number {
    if (typeof value !== 'number') return 0;
    return field.ratio ? value * 100 : value;
}

function rollbackValue<T>(history: T[], current: T): { next: T; history: T[] } {
    let nextHistory = [...history];
    let last = nextHistory[nextHistory.length - 1] ?? current;
    while (nextHistory.length > 1 && sameJson(last, current)) {
        nextHistory = nextHistory.slice(0, -1);
        last = nextHistory[nextHistory.length - 1] ?? current;
    }
    return { next: clonePolicy(last), history: nextHistory };
}

async function waitForResult(requestId: string): Promise<CommandResultResponse> {
    for (let i = 0; i < 20; i += 1) {
        const result = await api.commandResult(requestId);
        if (result.status === 'RESOLVED') return result;
        await new Promise((resolve) => setTimeout(resolve, 250));
    }
    return { status: 'PENDING', requestId };
}

function PolicyControls({
    onReset,
    onRevert,
    onSubmit,
    disabled,
}: {
    onReset: () => void;
    onRevert: () => void;
    onSubmit: () => void;
    disabled: boolean;
}) {
    return (
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 'var(--space-xs)', marginTop: 'var(--space-sm)' }}>
            <button type="button" disabled={disabled} style={buttonStyle} onClick={onReset}>초깃값으로</button>
            <button type="button" disabled={disabled} style={buttonStyle} onClick={onRevert}>이전값으로</button>
            <button type="button" disabled={disabled} style={buttonStyle} onClick={onSubmit}>설정</button>
        </div>
    );
}

function PriorityPanel({
    title,
    hint,
    setterLine,
    active,
    available,
    onChange,
    onReset,
    onRevert,
    onSubmit,
    disabled,
}: {
    title: string;
    hint: React.ReactNode;
    setterLine: string;
    active: string[];
    available: string[];
    onChange: (next: string[]) => void;
    onReset: () => void;
    onRevert: () => void;
    onSubmit: () => void;
    disabled: boolean;
}) {
    const unused = useMemo(() => {
        const activeSet = new Set(active);
        return available.filter((id) => !activeSet.has(id));
    }, [active, available]);

    const move = (id: string, enabled: boolean) => {
        if (enabled) onChange([...active, id]);
        else onChange(active.filter((item) => item !== id));
    };
    const shift = (index: number, delta: number) => {
        const target = index + delta;
        if (target < 0 || target >= active.length) return;
        const next = [...active];
        [next[index], next[target]] = [next[target], next[index]];
        onChange(next);
    };

    const itemStyle: React.CSSProperties = {
        display: 'grid',
        gridTemplateColumns: '1fr auto',
        gap: 'var(--space-xs)',
        alignItems: 'center',
        padding: 'var(--space-xs) var(--space-sm)',
        background: 'var(--bg-hover)',
        borderRadius: 'var(--radius-sm)',
        fontSize: 'var(--text-xs)',
    };

    return (
        <GameCard>
            <div style={sectionBarStyle}>{title}</div>
            <p style={{ textAlign: 'right', fontSize: 'var(--text-xs)', color: 'var(--text-muted)', marginBottom: 'var(--space-xs)' }}>
                {setterLine}
            </p>
            <p style={{ fontSize: 'var(--text-xs)', color: 'var(--text-secondary)', marginBottom: 'var(--space-sm)' }}>{hint}</p>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 'var(--space-sm)' }}>
                <div>
                    <div style={subBarStyle}>비활성</div>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-xs)' }}>
                        {unused.map((id) => (
                            <div key={id} style={itemStyle}>
                                <span>{id}</span>
                                <button type="button" disabled={disabled} aria-label={`${id} 활성`} onClick={() => move(id, true)}>›</button>
                            </div>
                        ))}
                    </div>
                </div>
                <div>
                    <div style={subBarStyle}>활성</div>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-xs)' }}>
                        {active.map((id, index) => (
                            <div key={`${id}-${index}`} style={itemStyle}>
                                <span>{id}</span>
                                <span style={{ display: 'flex', gap: 4 }}>
                                    <button type="button" disabled={disabled || index === 0} aria-label={`${id} 위로`} onClick={() => shift(index, -1)}>↑</button>
                                    <button type="button" disabled={disabled || index === active.length - 1} aria-label={`${id} 아래로`} onClick={() => shift(index, 1)}>↓</button>
                                    <button type="button" disabled={disabled} aria-label={`${id} 비활성`} onClick={() => move(id, false)}>‹</button>
                                </span>
                            </div>
                        ))}
                    </div>
                </div>
            </div>
            <PolicyControls onReset={onReset} onRevert={onRevert} onSubmit={onSubmit} disabled={disabled} />
        </GameCard>
    );
}

export default function NpcControlPage() {
    const [data, setData] = useState<NpcPolicyResponse | null>(null);
    const [permission, setPermission] = useState<number | null>(null);
    const [hasNation, setHasNation] = useState(true);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');
    const [status, setStatus] = useState('');
    const [policy, setPolicy] = useState<Record<string, NpcPolicyValue>>({});
    const [policyHistory, setPolicyHistory] = useState<Record<string, NpcPolicyValue>[]>([]);
    const [nationPriority, setNationPriority] = useState<string[]>([]);
    const [nationHistory, setNationHistory] = useState<string[][]>([]);
    const [generalPriority, setGeneralPriority] = useState<string[]>([]);
    const [generalHistory, setGeneralHistory] = useState<string[][]>([]);
    const [saving, setSaving] = useState(false);

    useEffect(() => {
        let cancelled = false;
        (async () => {
            setLoading(true);
            setError('');
            try {
                const front = await api.frontInfo();
                if (cancelled) return;
                const perm = front.general.permission ?? 0;
                const inNation = (front.general.nationId ?? 0) !== 0 && front.general.hasGeneral;
                setHasNation(inNation);
                setPermission(perm);
                if (inNation && perm >= 1) {
                    const res = await api.npcPolicy();
                    if (cancelled) return;
                    setData(res);
                    setPolicy(clonePolicy(res.currentNationPolicy));
                    setPolicyHistory([clonePolicy(res.currentNationPolicy)]);
                    setNationPriority([...res.currentNationPriority]);
                    setNationHistory([[...res.currentNationPriority]]);
                    setGeneralPriority([...res.currentGeneralActionPriority]);
                    setGeneralHistory([[...res.currentGeneralActionPriority]]);
                }
            } catch {
                if (!cancelled) setError('NPC 정책을 불러올 수 없습니다.');
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();
        return () => {
            cancelled = true;
        };
    }, []);

    const submit = async (type: 'nationPolicy' | 'nationPriority' | 'generalPriority', payload: unknown) => {
        setSaving(true);
        setStatus('');
        try {
            const out = await api.updateNpcPolicy({ type, data: payload });
            if (isIntakeDenied(out)) {
                setStatus(`설정하지 못했습니다: ${out.reason}`);
                return false;
            }
            if (!isIntakeQueued(out) || !out.requestId) {
                setStatus('설정하지 못했습니다: 처리 결과를 확인할 수 없습니다.');
                return false;
            }
            const resolved = await waitForResult(out.requestId);
            if (resolved.status !== 'RESOLVED') {
                setStatus('설정하지 못했습니다: 처리 결과를 확인할 수 없습니다.');
                return false;
            }
            if (!resolved.ok) {
                setStatus(`설정하지 못했습니다: ${resolved.reason ?? '처리할 수 없습니다.'}`);
                return false;
            }
            setStatus('NPC 정책이 반영되었습니다.');
            return true;
        } catch (e) {
            setStatus(`설정하지 못했습니다: ${e instanceof Error ? e.message : String(e)}`);
            return false;
        } finally {
            setSaving(false);
        }
    };

    if (loading) {
        return <Shell><h1>NPC 정책</h1><p style={{ color: 'var(--text-muted)' }}>로딩 중...</p></Shell>;
    }
    if (error) {
        return <Shell><h1>NPC 정책</h1><p style={{ color: 'var(--crimson)' }}>{error}</p></Shell>;
    }
    if (!hasNation) {
        return <Shell><h1>NPC 정책</h1><GameCard><p>국가에 소속되어있지 않습니다.</p></GameCard></Shell>;
    }
    if ((permission ?? 0) < 1) {
        return <Shell><h1>NPC 정책</h1><GameCard><p>권한이 부족합니다. 수뇌부가 아니거나 사관년도가 부족합니다.</p></GameCard></Shell>;
    }

    const defaults = data?.defaultNationPolicy ?? {};
    const zero = data?.zeroPolicy ?? {};
    const lastSetters = data?.lastSetters ?? {
        policy: { setter: null, date: null },
        nation: { setter: null, date: null },
        general: { setter: null, date: null },
    };

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>NPC 정책</h1>
            {status && (
                <div role="status" style={{ marginBottom: 'var(--space-sm)', color: status.startsWith('설정하지') ? 'var(--crimson)' : 'var(--text-secondary)' }}>
                    {status}
                </div>
            )}
            <GameCard className="mb-md">
                <div style={sectionBarStyle}>국가 정책</div>
                <p style={{ textAlign: 'right', fontSize: 'var(--text-xs)', color: 'var(--text-muted)', marginBottom: 'var(--space-sm)' }}>
                    {lastSetterLine(lastSetters.policy.setter, lastSetters.policy.date)}
                </p>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 'var(--space-sm)' }}>
                    {POLICY_FIELDS.map((field) => (
                        <label key={field.key} style={{ border: '1px solid var(--border-subtle)', borderRadius: 'var(--radius-sm)', padding: 'var(--space-sm)' }}>
                            <span style={{ display: 'block', fontSize: 'var(--text-sm)', fontWeight: 700, marginBottom: 'var(--space-xs)' }}>{field.title}</span>
                            <input
                                aria-label={field.title}
                                type="number"
                                step={field.step}
                                min={field.min}
                                max={field.max}
                                value={displayValue(field, policy[field.key])}
                                onChange={(event) => {
                                    const raw = Number(event.currentTarget.value);
                                    setPolicy((prev) => ({
                                        ...prev,
                                        [field.key]: field.ratio ? raw / 100 : raw,
                                    }));
                                }}
                                style={{ width: '100%', marginBottom: 'var(--space-xs)' }}
                            />
                            <span style={{ display: 'block', fontSize: 'var(--text-xs)', color: 'var(--text-muted)' }}>
                                초깃값 {displayValue(field, defaults[field.key]).toLocaleString()}
                                {typeof zero[field.key] === 'number' ? ` · 0 기준값 ${(zero[field.key] as number).toLocaleString()}` : ''}
                            </span>
                            <span style={{ display: 'block', fontSize: 'var(--text-xs)', color: 'var(--text-secondary)', marginTop: 'var(--space-xs)' }}>{field.info}</span>
                        </label>
                    ))}
                </div>
                <PolicyControls
                    disabled={saving}
                    onReset={() => {
                        setPolicy(clonePolicy(defaults));
                        setStatus('서버 초깃값을 적용했습니다.설정 버튼을 누르면 반영됩니다.');
                    }}
                    onRevert={() => {
                        const rollback = rollbackValue(policyHistory, policy);
                        setPolicy(rollback.next);
                        setPolicyHistory(rollback.history);
                        setStatus('이전 설정으로 되돌렸습니다.');
                    }}
                    onSubmit={async () => {
                        if (await submit('nationPolicy', policy)) {
                            setPolicyHistory((prev) => sameJson(prev[prev.length - 1], policy) ? prev : [...prev, clonePolicy(policy)]);
                        }
                    }}
                />
            </GameCard>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(360px, 1fr))', gap: 'var(--space-md)' }}>
                <PriorityPanel
                    title="NPC 사령턴 우선순위"
                    hint={<>예턴이 없거나, 지정되어 있더라도 실패하면 아래 순위에 따라 사령턴을 시도합니다.</>}
                    setterLine={lastSetterLine(lastSetters.nation.setter, lastSetters.nation.date)}
                    active={nationPriority}
                    available={data?.availableNationPriorityItems ?? []}
                    onChange={setNationPriority}
                    disabled={saving}
                    onReset={() => {
                        setNationPriority([...(data?.defaultNationPriority ?? [])]);
                        setStatus('서버 초깃값을 적용했습니다.설정 버튼을 누르면 반영됩니다.');
                    }}
                    onRevert={() => {
                        const rollback = rollbackValue(nationHistory, nationPriority);
                        setNationPriority(rollback.next);
                        setNationHistory(rollback.history);
                        setStatus('이전 설정으로 되돌렸습니다.');
                    }}
                    onSubmit={async () => {
                        if (await submit('nationPriority', nationPriority)) {
                            setNationHistory((prev) => sameJson(prev[prev.length - 1], nationPriority) ? prev : [...prev, [...nationPriority]]);
                        }
                    }}
                />
                <PriorityPanel
                    title="NPC 일반턴 우선순위"
                    hint={<>순위가 높은 것부터 시도합니다. 아무것도 실행할 수 없으면 물자조달이나 인재탐색을 합니다.</>}
                    setterLine={lastSetterLine(lastSetters.general.setter, lastSetters.general.date)}
                    active={generalPriority}
                    available={data?.availableGeneralActionPriorityItems ?? []}
                    onChange={setGeneralPriority}
                    disabled={saving}
                    onReset={() => {
                        setGeneralPriority([...(data?.defaultGeneralActionPriority ?? [])]);
                        setStatus('서버 초깃값을 적용했습니다.설정 버튼을 누르면 반영됩니다.');
                    }}
                    onRevert={() => {
                        const rollback = rollbackValue(generalHistory, generalPriority);
                        setGeneralPriority(rollback.next);
                        setGeneralHistory(rollback.history);
                        setStatus('이전 설정으로 되돌렸습니다.');
                    }}
                    onSubmit={async () => {
                        if (await submit('generalPriority', generalPriority)) {
                            setGeneralHistory((prev) => sameJson(prev[prev.length - 1], generalPriority) ? prev : [...prev, [...generalPriority]]);
                        }
                    }}
                />
            </div>
        </Shell>
    );
}
