'use client';

// ── page 8 · NPC 정책 (NPC control) — READ-ONLY display this wave ──────────────
// Consumes api.npcPolicy() → NpcPolicyResponse (foundation phase) + api.frontInfo()
// for the permission>=1 gate. Mirrors legacy hwe/v_NPCControl.php + ts/PageNPCControl.vue.
// Setters are DEFERRED (no mutation wiring this wave): the 초깃값으로/이전값으로/설정
// control bar and the draggable priority lists render as a read-only display.
// EMPTY-SAFE: missing policy keys / empty priority arrays render empty, never crash.
// Verbatim Korean labels/info/deny strings reproduced byte-for-byte from the legacy.

import { useEffect, useState } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import { api } from '../../../lib/api';
import type { NpcPolicyResponse } from '../../../types/game';
import type { FrontInfoResponse } from '../../../lib/types';

// 국가 정책 — verbatim titles + info text from ts/PageNPCControl.vue (NumberInputWithInfo).
// `info` is rendered as static help text; the zero/calc-derived figures the Vue interpolates
// at runtime ({{ zeroPolicy.* }}, {{ calcPolicyValue(...) }}) are shown from the response's
// zeroPolicy where available — read-only display only.
interface PolicyFieldDef {
    key: string;
    title: string;
    info: string;
    // zeroRef: when set, the field's "0이면 …" baseline is taken from zeroPolicy[zeroRef].
    zeroRef?: string;
}

const POLICY_FIELDS: PolicyFieldDef[] = [
    { key: 'reqNationGold', title: '국가 권장 금', info: '이보다 많으면 포상, 적으면 몰수/헌납합니다.(긴급포상 제외)' },
    { key: 'reqNationRice', title: '국가 권장 쌀', info: '이보다 많으면 포상, 적으면 몰수/헌납합니다.(긴급포상 제외)' },
    { key: 'reqHumanWarUrgentGold', title: '유저전투장 긴급포상 금', info: '유저장긴급포상시 이보다 금이 적은 장수에게 포상합니다. 0이면 보병 6회 징병 가능한 금을 기준으로 합니다.', zeroRef: 'reqHumanWarUrgentGold' },
    { key: 'reqHumanWarUrgentRice', title: '유저전투장 긴급포상 쌀', info: '유저장긴급포상시 이보다 쌀이 적은 장수에게 포상합니다. 0이면 기본 병종으로 사살 가능한 쌀을 기준으로 합니다.', zeroRef: 'reqHumanWarUrgentRice' },
    { key: 'reqHumanWarRecommandGold', title: '유저전투장 권장 금', info: '유저전투장에게 주는 금입니다. 이보다 적으면 포상합니다. 0이면 유저전투장 긴급포상 금의 2배를 기준으로 합니다.' },
    { key: 'reqHumanWarRecommandRice', title: '유저전투장 권장 쌀', info: '유저전투장에게 주는 쌀입니다. 이보다 적으면 포상합니다. 0이면 유저전투장 긴급포상 쌀의 2배를 기준으로 합니다.' },
    { key: 'reqHumanDevelGold', title: '유저내정장 권장 금', info: '유저내정장에게 주는 금입니다. 이보다 적으면 포상합니다.' },
    { key: 'reqHumanDevelRice', title: '유저내정장 권장 쌀', info: '유저내정장에게 주는 쌀입니다. 이보다 적으면 포상합니다.' },
    { key: 'reqNPCWarGold', title: 'NPC전투장 권장 금', info: 'NPC전투장에게 주는 금입니다. 이보다 적으면 포상합니다. 0이면 기본 병종 4회 징병비를 기준으로 합니다.', zeroRef: 'reqNPCWarGold' },
    { key: 'reqNPCWarRice', title: 'NPC전투장 권장 쌀', info: 'NPC전투장에게 주는 쌀입니다. 이보다 적으면 포상합니다. 0이면 기본 병종으로 사살 가능한 쌀을 기준으로 합니다.', zeroRef: 'reqNPCWarRice' },
    { key: 'reqNPCDevelGold', title: 'NPC내정장 권장 금', info: 'NPC내정장에게 주는 금입니다. 이보다 5배 더 많다면 헌납합니다. 0이면 30턴 내정 가능한 금을 기준으로 합니다.', zeroRef: 'reqNPCDevelGold' },
    { key: 'reqNPCDevelRice', title: 'NPC내정장 권장 쌀', info: 'NPC내정장에게 주는 쌀입니다. 이보다 5배 더 많다면 헌납합니다.' },
    { key: 'minimumResourceActionAmount', title: '포상/몰수/헌납/삼/팜 최소 단위', info: '연산결과가 이 단위보다 적다면 수행하지 않습니다.' },
    { key: 'maximumResourceActionAmount', title: '포상/몰수/헌납/삼/팜 최대 단위', info: '연산결과가 이 단위보다 크다면, 이 값에 맞춥니다.' },
    { key: 'minWarCrew', title: '최소 전투 가능 병력 수', info: '이보다 적을 때에는 징병을 시도합니다.' },
    { key: 'minNPCRecruitCityPopulation', title: 'NPC 최소 징병 가능 인구 수', info: '도시의 인구가 이보다 낮으면 NPC는 도시에서 징병하지 않고 후방 워프합니다. NPC의 최대 병력수보다 낮게 설정하면 제자리에서 정착장려를 합니다.' },
    { key: 'safeRecruitCityPopulationRatio', title: '제자리 징병 허용 인구율(%)', info: "전쟁 시 후방 발령, 후방 워프의 기준 인구입니다. 이보다 많다면 '충분하다'고 판단합니다. NPC의 최대 병력수보다 낮게 설정하면 제자리에서 정착장려를 합니다." },
    { key: 'minNPCWarLeadership', title: 'NPC 전투 참여 통솔 기준', info: '이 수치보다 같거나 높으면 NPC전투장으로 분류됩니다.' },
    { key: 'properWarTrainAtmos', title: '훈련/사기진작 목표치', info: '훈련/사기진작 기준치입니다. 이보다 같거나 높으면 출병합니다.' },
    { key: 'cureThreshold', title: '요양 기준', info: '요양 기준 %입니다. 이보다 많이 부상을 입으면 요양합니다.' },
];

// safeRecruitCityPopulationRatio is stored 0..1 and displayed *100 in the legacy view.
const RATIO_FIELDS = new Set(['safeRecruitCityPopulationRatio']);

function fmtPolicyValue(key: string, raw: number | undefined): string {
    if (raw == null) return '-';
    const v = RATIO_FIELDS.has(key) ? raw * 100 : raw;
    return v.toLocaleString();
}

const RESET_LABEL = '초깃값으로';
const REVERT_LABEL = '이전값으로';
const SUBMIT_LABEL = '설정';

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

function lastSetterLine(setter: string | null, date: string | null) {
    // Verbatim from the Vue: 최근 설정: {setter ?? "-없음-"} ({date ?? "설정 기록 없음"})
    return `최근 설정: ${setter ?? '-없음-'} (${date ?? '설정 기록 없음'})`;
}

// Read-only control bar (setters deferred this wave): renders the verbatim button captions disabled.
function ControlBar() {
    const btnStyle: React.CSSProperties = {
        fontSize: 'var(--text-xs)',
        padding: 'var(--space-xs) var(--space-sm)',
        opacity: 0.5,
        cursor: 'not-allowed',
    };
    return (
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 'var(--space-xs)', marginTop: 'var(--space-sm)' }}>
            <button type="button" disabled style={btnStyle}>{RESET_LABEL}</button>
            <button type="button" disabled style={btnStyle}>{REVERT_LABEL}</button>
            <button type="button" disabled style={btnStyle}>{SUBMIT_LABEL}</button>
        </div>
    );
}

// Read-only 활성/비활성 priority panel. `active` is the ordered current priority; `available`
// is the full catalog → 비활성 = catalog minus active (insertion order of catalog preserved).
function PriorityPanel({
    title,
    hint,
    setterLine,
    active,
    available,
}: {
    title: string;
    hint: React.ReactNode;
    setterLine: string;
    active: string[];
    available: string[];
}) {
    const activeSet = new Set(active);
    const unused = available.filter((id) => !activeSet.has(id));

    const itemStyle: React.CSSProperties = {
        display: 'flex',
        alignItems: 'center',
        gap: 'var(--space-xs)',
        padding: 'var(--space-xs) var(--space-sm)',
        background: 'var(--bg-hover)',
        borderRadius: 'var(--radius-sm)',
        fontSize: 'var(--text-xs)',
    };

    const renderList = (items: string[]) =>
        items.length === 0 ? (
            <p style={{ color: 'var(--text-muted)', fontSize: 'var(--text-xs)', padding: 'var(--space-xs)' }}>
                항목이 없습니다.
            </p>
        ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-xs)' }}>
                {items.map((id, i) => (
                    <div key={`${id}-${i}`} style={itemStyle}>
                        <span style={{ color: 'var(--text-muted)' }}>≡</span>
                        <span>{id}</span>
                    </div>
                ))}
            </div>
        );

    return (
        <GameCard>
            <div style={sectionBarStyle}>{title}</div>
            <p style={{ textAlign: 'right', fontSize: 'var(--text-xs)', color: 'var(--text-muted)', marginBottom: 'var(--space-xs)' }}>
                {setterLine}
            </p>
            <p style={{ fontSize: 'var(--text-xs)', color: 'var(--text-secondary)', marginBottom: 'var(--space-sm)' }}>
                {hint}
            </p>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 'var(--space-sm)' }}>
                <div>
                    <div style={subBarStyle}>비활성</div>
                    {renderList(unused)}
                </div>
                <div>
                    <div style={subBarStyle}>활성</div>
                    {renderList(active)}
                </div>
            </div>
            <ControlBar />
        </GameCard>
    );
}

export default function NpcControlPage() {
    const [data, setData] = useState<NpcPolicyResponse | null>(null);
    const [permission, setPermission] = useState<number | null>(null);
    const [hasNation, setHasNation] = useState<boolean>(true);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    useEffect(() => {
        let cancelled = false;
        (async () => {
            setLoading(true);
            setError('');
            try {
                const front = await api.frontInfo();
                if (cancelled) return;
                const perm = front.general.permission ?? 0;
                // nationId 0 (재야) → 국가 미소속. Mirrors checkSecretPermission < 0.
                const inNation = (front.general.nationId ?? 0) !== 0 && front.general.hasGeneral;
                setHasNation(inNation);
                setPermission(perm);
                // permission>=1 gate: only fetch the policy payload when allowed (legacy dies otherwise).
                if (inNation && perm >= 1) {
                    const res = await api.npcPolicy();
                    if (!cancelled) setData(res);
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

    if (loading) {
        return (
            <Shell>
                <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>NPC 정책</h1>
                <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>
            </Shell>
        );
    }

    if (error) {
        return (
            <Shell>
                <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>NPC 정책</h1>
                <p style={{ color: 'var(--crimson)' }}>{error}</p>
            </Shell>
        );
    }

    // Permission gate — deny reasons render as INFO (not error), verbatim from v_NPCControl.php.
    if (!hasNation) {
        return (
            <Shell>
                <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>NPC 정책</h1>
                <GameCard>
                    <p style={{ color: 'var(--text-secondary)' }}>국가에 소속되어있지 않습니다.</p>
                </GameCard>
            </Shell>
        );
    }
    if ((permission ?? 0) < 1) {
        return (
            <Shell>
                <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>NPC 정책</h1>
                <GameCard>
                    <p style={{ color: 'var(--text-secondary)' }}>권한이 부족합니다. 수뇌부가 아니거나 사관년도가 부족합니다.</p>
                </GameCard>
            </Shell>
        );
    }

    // EMPTY-SAFE defaults: if api.npcPolicy() returned a partial/empty shape, every map below
    // degrades to an empty render (no crash). All record/array fields default to {}/[].
    const current = data?.currentNationPolicy ?? {};
    const dflt = data?.defaultNationPolicy ?? {};
    const zero = data?.zeroPolicy ?? {};
    const currentNationPriority = data?.currentNationPriority ?? [];
    const availableNationPriorityItems = data?.availableNationPriorityItems ?? [];
    const currentGeneralActionPriority = data?.currentGeneralActionPriority ?? [];
    const availableGeneralActionPriorityItems = data?.availableGeneralActionPriorityItems ?? [];
    const lastSetters = data?.lastSetters ?? {
        policy: { setter: null, date: null },
        nation: { setter: null, date: null },
        general: { setter: null, date: null },
    };

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-sm)' }}>NPC 정책</h1>
            <p style={{ fontSize: 'var(--text-sm)', color: 'var(--text-muted)', marginBottom: 'var(--space-md)' }}>
                현재 설정과 서버 초깃값을 표시합니다. (설정 변경은 추후 지원)
            </p>

            {/* ── 국가 정책 ── */}
            <GameCard className="mb-md">
                <div style={sectionBarStyle}>국가 정책</div>
                <p style={{ textAlign: 'right', fontSize: 'var(--text-xs)', color: 'var(--text-muted)', marginBottom: 'var(--space-sm)' }}>
                    {lastSetterLine(lastSetters.policy.setter, lastSetters.policy.date)}
                </p>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 'var(--space-sm)' }}>
                    {POLICY_FIELDS.map((f) => {
                        const cur = (current as Record<string, number>)[f.key];
                        const def = (dflt as Record<string, number>)[f.key];
                        const zeroVal = f.zeroRef ? (zero as Record<string, number>)[f.zeroRef] : undefined;
                        return (
                            <div
                                key={f.key}
                                style={{
                                    border: '1px solid var(--border-subtle)',
                                    borderRadius: 'var(--radius-sm)',
                                    padding: 'var(--space-sm)',
                                }}
                            >
                                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 'var(--space-sm)' }}>
                                    <strong style={{ fontSize: 'var(--text-sm)' }}>{f.title}</strong>
                                    <span style={{ fontSize: 'var(--text-base)', color: 'var(--gold)', fontWeight: 600 }}>
                                        {fmtPolicyValue(f.key, cur)}
                                    </span>
                                </div>
                                <div style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)', marginTop: 'var(--space-xs)' }}>
                                    초깃값 {fmtPolicyValue(f.key, def)}
                                    {f.zeroRef && zeroVal != null ? ` · 0 기준값 ${zeroVal.toLocaleString()}` : ''}
                                </div>
                                <p style={{ fontSize: 'var(--text-xs)', color: 'var(--text-secondary)', marginTop: 'var(--space-xs)' }}>
                                    {f.info}
                                </p>
                            </div>
                        );
                    })}
                </div>
                <ControlBar />
            </GameCard>

            {/* ── NPC 사령턴 우선순위 / NPC 일반턴 우선순위 ── */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(360px, 1fr))', gap: 'var(--space-md)' }}>
                <PriorityPanel
                    title="NPC 사령턴 우선순위"
                    hint={<>예턴이 없거나, 지정되어 있더라도 실패하면 아래 순위에 따라 사령턴을 시도합니다.</>}
                    setterLine={lastSetterLine(lastSetters.nation.setter, lastSetters.nation.date)}
                    active={currentNationPriority}
                    available={availableNationPriorityItems}
                />
                <PriorityPanel
                    title="NPC 일반턴 우선순위"
                    hint={<>순위가 높은 것부터 시도합니다. 아무것도 실행할 수 없으면 물자조달이나 인재탐색을 합니다.</>}
                    setterLine={lastSetterLine(lastSetters.general.setter, lastSetters.general.date)}
                    active={currentGeneralActionPriority}
                    available={availableGeneralActionPriorityItems}
                />
            </div>
        </Shell>
    );
}
