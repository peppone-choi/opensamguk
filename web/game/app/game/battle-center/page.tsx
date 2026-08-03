'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import GeneralBasicCard from '../../../components/game/GeneralBasicCard';
import { api, type GeneralLogType, type NationGeneralListResponse } from '../../../lib/api';
import { formatLog } from '../../../lib/utilGame';
import type { FrontGeneralInfo, FrontNationInfo } from '../../../lib/types';

type SortKey = 'recent_war' | 'warnum' | 'turntime' | 'name';

type GeneralRow = Record<string, unknown>;

interface BattleCenterGeneral extends FrontGeneralInfo {
    no: number;
    npc: number;
    recentWar: string | null;
    turntime: string | null;
    warnum: number;
}

interface LogEntry {
    id: number;
    text: string;
}

type LogState = Record<GeneralLogType, LogEntry[]>;
type LogErrorState = Partial<Record<GeneralLogType, string>>;

const SORTS: Record<SortKey, { label: string; asc: boolean; extra: (general: BattleCenterGeneral) => string }> = {
    recent_war: {
        label: '최근 전투',
        asc: false,
        extra: (general) => `[${lastFive(general.recentWar)}]`,
    },
    warnum: {
        label: '전투 횟수',
        asc: false,
        extra: (general) => `[${general.warnum}회]`,
    },
    turntime: {
        label: '최근 턴',
        asc: false,
        extra: () => '',
    },
    name: {
        label: '이름',
        asc: true,
        extra: () => '',
    },
};

const LOG_SECTIONS: { type: GeneralLogType; title: string; color: string }[] = [
    { type: 'generalHistory', title: '장수 열전', color: 'skyblue' },
    { type: 'battleDetail', title: '전투 기록', color: 'orange' },
    { type: 'battleResult', title: '전투 결과', color: 'orange' },
    { type: 'generalAction', title: '개인 기록', color: 'skyblue' },
];

function lastFive(value: string | null | undefined): string {
    if (!value) return '--:--';
    const timePart = value.includes(' ') ? value.split(' ')[1] : value;
    return timePart.length >= 5 ? timePart.slice(0, 5) : timePart;
}

function raw(row: GeneralRow, ...keys: string[]): unknown {
    for (const key of keys) {
        if (Object.prototype.hasOwnProperty.call(row, key)) return row[key];
    }
    return undefined;
}

function numberValue(row: GeneralRow, keys: string[], fallback = 0): number {
    const value = raw(row, ...keys);
    return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function stringValue(row: GeneralRow, keys: string[], fallback = ''): string {
    const value = raw(row, ...keys);
    return typeof value === 'string' ? value : fallback;
}

function nullableString(row: GeneralRow, keys: string[]): string | null {
    const value = raw(row, ...keys);
    return typeof value === 'string' ? value : null;
}

function tupleToRow(columns: string[], values: unknown[]): GeneralRow {
    const row: GeneralRow = {};
    columns.forEach((column, index) => {
        row[column] = values[index];
    });
    return row;
}

function rowToGeneral(row: GeneralRow): BattleCenterGeneral {
    const no = numberValue(row, ['no']);
    const nationId = numberValue(row, ['nation', 'nationId']);
    const officerLevel = numberValue(row, ['officerLevel', 'officer_level']);
    const picture = nullableString(row, ['picture']);
    const imageServer = numberValue(row, ['imgsvr', 'imageServer'], 0);
    const recentWar = nullableString(row, ['recentWar', 'recent_war']);
    const turntime = nullableString(row, ['turntime', 'turnTime']);
    const warnum = numberValue(row, ['warnum']);

    return {
        no,
        hasGeneral: true,
        generalId: no,
        name: stringValue(row, ['name'], '-'),
        nationId,
        officerLevel,
        permission: 0,
        showSecret: false,
        leadership: numberValue(row, ['leadership']),
        strength: numberValue(row, ['strength']),
        intel: numberValue(row, ['intel']),
        politics: numberValue(row, ['politics']),
        charm: numberValue(row, ['charm']),
        injury: numberValue(row, ['injury']),
        gold: numberValue(row, ['gold']),
        rice: numberValue(row, ['rice']),
        crew: numberValue(row, ['crew']),
        cityId: numberValue(row, ['city', 'cityId']),
        picture,
        imageServer,
        experience: numberValue(row, ['experience']),
        dedication: numberValue(row, ['dedication']),
        train: numberValue(row, ['train']),
        atmos: numberValue(row, ['atmos']),
        crewTypeId: numberValue(row, ['crewtype', 'crewTypeId']),
        troop: numberValue(row, ['troop']),
        horse: nullableString(row, ['horse']),
        weapon: nullableString(row, ['weapon']),
        book: nullableString(row, ['book']),
        item: nullableString(row, ['item']),
        age: numberValue(row, ['age']),
        specialDomestic: nullableString(row, ['specialDomestic', 'special']),
        specialWar: nullableString(row, ['specialWar', 'special2']),
        personal: nullableString(row, ['personal']),
        explevel: numberValue(row, ['explevel']),
        dedlevel: numberValue(row, ['dedlevel']),
        killturn: numberValue(row, ['killturn']),
        officerLevelText: nullableString(row, ['officerLevelText', 'officer_level_text']),
        honorText: nullableString(row, ['honorText', 'honor_text']),
        dedLevelText: nullableString(row, ['dedLevelText', 'ded_level_text']),
        lbonus: numberValue(row, ['lbonus']),
        bill: numberValue(row, ['bill']),
        leadershipExp: numberValue(row, ['leadershipExp', 'leadership_exp']),
        strengthExp: numberValue(row, ['strengthExp', 'strength_exp']),
        intelExp: numberValue(row, ['intelExp', 'intel_exp']),
        warnum,
        killnum: numberValue(row, ['killnum']),
        deathnum: numberValue(row, ['deathnum']),
        killcrew: numberValue(row, ['killcrew']),
        deathcrew: numberValue(row, ['deathcrew']),
        firenum: numberValue(row, ['firenum']),
        belong: numberValue(row, ['belong']),
        npc: numberValue(row, ['npc']),
        recentWar,
        turntime,
    };
}

function normalizeGenerals(response: NationGeneralListResponse): BattleCenterGeneral[] {
    return response.list.map((values) => rowToGeneral(tupleToRow(response.column, values)));
}

function compareValue(general: BattleCenterGeneral, key: SortKey): string | number {
    if (key === 'recent_war') return general.recentWar ?? '';
    if (key === 'warnum') return general.warnum;
    if (key === 'turntime') return general.turntime ?? '';
    return `${general.npc} ${general.name ?? ''}`;
}

function sortGenerals(generals: BattleCenterGeneral[], sortKey: SortKey): BattleCenterGeneral[] {
    const config = SORTS[sortKey];
    return [...generals].sort((a, b) => {
        const aVal = compareValue(a, sortKey);
        const bVal = compareValue(b, sortKey);
        if (aVal === bVal) return 0;
        const cmp = aVal > bVal ? 1 : -1;
        return config.asc ? cmp : -cmp;
    });
}

function optionLabel(general: BattleCenterGeneral, sortKey: SortKey): string {
    const name = general.officerLevel > 4 ? `*${general.name}*` : general.name ?? '-';
    return `${name}(${lastFive(general.turntime)})${SORTS[sortKey].extra(general)}`;
}

function emptyLogs(): LogState {
    return {
        generalAction: [],
        battleDetail: [],
        battleResult: [],
        generalHistory: [],
    };
}

function LogPanel({ title, color, logs, error }: { title: string; color: string; logs: LogEntry[]; error?: string }) {
    return (
        <GameCard style={{ marginBottom: 'var(--space-md)' }}>
            <div
                style={{
                    textAlign: 'center',
                    fontWeight: 700,
                    color,
                    padding: 'var(--space-xs) 0',
                    background: 'var(--bg-elevated)',
                    marginBottom: 'var(--space-sm)',
                }}
            >
                {title}
            </div>
            {error ? (
                <p role="alert" style={{ color: 'var(--crimson)', textAlign: 'center', margin: 0 }}>
                    {error}
                </p>
            ) : logs.length === 0 ? (
                <p style={{ color: 'var(--text-muted)', textAlign: 'center', margin: 0 }}>기록이 없습니다.</p>
            ) : (
                <div style={{ fontSize: 'var(--text-sm)', lineHeight: 1.7 }}>
                    {logs.map((entry) => (
                        <div
                            key={entry.id}
                            style={{ padding: 'var(--space-xs) 0', borderBottom: '1px solid var(--border-subtle)' }}
                            dangerouslySetInnerHTML={{ __html: formatLog(entry.text) }}
                        />
                    ))}
                </div>
            )}
        </GameCard>
    );
}

export default function BattleCenterPage() {
    const [generals, setGenerals] = useState<BattleCenterGeneral[]>([]);
    const [nation, setNation] = useState<FrontNationInfo | null>(null);
    const [sortKey, setSortKey] = useState<SortKey>('turntime');
    const [targetId, setTargetId] = useState<number | null>(null);
    const [logs, setLogs] = useState<LogState>(emptyLogs);
    const [logErrors, setLogErrors] = useState<LogErrorState>({});
    const [loading, setLoading] = useState(true);
    const [loadingLogs, setLoadingLogs] = useState(false);
    const [error, setError] = useState('');

    const orderedGenerals = useMemo(() => sortGenerals(generals, sortKey), [generals, sortKey]);
    const targetGeneral = useMemo(
        () => orderedGenerals.find((general) => general.no === targetId) ?? null,
        [orderedGenerals, targetId],
    );

    const loadGenerals = useCallback(async () => {
        setLoading(true);
        setError('');
        setLogs(emptyLogs());
        setLogErrors({});
        try {
            const [listResponse, frontInfo] = await Promise.all([api.nationGeneralList(), api.frontInfo()]);
            if (listResponse.permission === 0) {
                setGenerals([]);
                setNation(frontInfo.nation);
                setTargetId(null);
                setError(listResponse.reason ?? '권한이 부족합니다.');
                return;
            }
            const normalized = normalizeGenerals(listResponse);
            setGenerals(normalized);
            setNation(frontInfo.nation);
            const sorted = sortGenerals(normalized, 'turntime');
            setTargetId((prev) => (prev != null && sorted.some((general) => general.no === prev) ? prev : sorted[0]?.no ?? null));
        } catch (e) {
            setGenerals([]);
            setNation(null);
            setTargetId(null);
            setError(e instanceof Error ? e.message : '감찰부 정보를 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        void loadGenerals();
    }, [loadGenerals]);

    useEffect(() => {
        if (targetId == null) {
            setLogs(emptyLogs());
            setLogErrors({});
            return;
        }
        let active = true;
        setLoadingLogs(true);
        setLogs(emptyLogs());
        setLogErrors({});
        const load = async () => {
            const nextLogs = emptyLogs();
            const nextErrors: LogErrorState = {};
            await Promise.all(
                LOG_SECTIONS.map(async ({ type }) => {
                    try {
                        const res = await api.generalLog(targetId, type);
                        if (!res.result) {
                            nextErrors[type] = res.reason ?? '로그를 불러올 수 없습니다.';
                            return;
                        }
                        nextLogs[type] = Object.entries(res.log ?? {})
                            .map(([id, text]) => ({ id: Number(id), text }))
                            .sort((a, b) => b.id - a.id);
                    } catch (e) {
                        nextErrors[type] = e instanceof Error ? e.message : '로그를 불러올 수 없습니다.';
                    }
                }),
            );
            if (active) {
                setLogs(nextLogs);
                setLogErrors(nextErrors);
                setLoadingLogs(false);
            }
        };
        void load();
        return () => {
            active = false;
        };
    }, [targetId]);

    function changeTargetByOffset(offset: number) {
        if (orderedGenerals.length === 0 || targetId == null) return;
        const current = orderedGenerals.findIndex((general) => general.no === targetId);
        if (current < 0) return;
        const next = (current + offset + orderedGenerals.length) % orderedGenerals.length;
        setTargetId(orderedGenerals[next].no);
    }

    function handleSortChange(nextSort: SortKey) {
        const sorted = sortGenerals(generals, nextSort);
        setSortKey(nextSort);
        setTargetId(sorted[0]?.no ?? null);
    }

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>감찰부</h1>

            {loading ? (
                <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>
            ) : (
                <>
                    {error && (
                        <div style={{ marginBottom: 'var(--space-md)' }}>
                            <p role="alert" style={{ color: 'var(--crimson)', margin: 0 }}>
                                {error}
                            </p>
                            <button type="button" onClick={loadGenerals} style={{ marginTop: 'var(--space-sm)' }}>
                                다시 시도
                            </button>
                        </div>
                    )}

                    {!error && orderedGenerals.length === 0 ? (
                        <div>
                            <p style={{ color: 'var(--text-muted)' }}>감찰할 장수가 없습니다.</p>
                            <button type="button" onClick={loadGenerals}>
                                다시 시도
                            </button>
                        </div>
                    ) : null}

                    {orderedGenerals.length > 0 && (
                        <>
                            <div
                                style={{
                                    display: 'grid',
                                    gridTemplateColumns: 'minmax(70px, 1fr) minmax(120px, 4fr) minmax(180px, 6fr) minmax(70px, 1fr)',
                                    gap: 'var(--space-xs)',
                                    alignItems: 'stretch',
                                    marginBottom: 'var(--space-md)',
                                }}
                            >
                                <button type="button" onClick={() => changeTargetByOffset(-1)}>
                                    ◀ 이전
                                </button>
                                <label style={{ display: 'grid', gap: '2px', fontSize: 'var(--text-xs)' }}>
                                    정렬
                                    <select
                                        aria-label="정렬"
                                        value={sortKey}
                                        onChange={(e) => handleSortChange(e.target.value as SortKey)}
                                    >
                                        {Object.entries(SORTS).map(([key, config]) => (
                                            <option key={key} value={key}>
                                                {config.label}
                                            </option>
                                        ))}
                                    </select>
                                </label>
                                <label style={{ display: 'grid', gap: '2px', fontSize: 'var(--text-xs)' }}>
                                    대상 장수
                                    <select
                                        aria-label="대상 장수"
                                        value={targetId ?? ''}
                                        onChange={(e) => setTargetId(Number(e.target.value))}
                                    >
                                        {orderedGenerals.map((general) => (
                                            <option key={general.no} value={general.no}>
                                                {optionLabel(general, sortKey)}
                                            </option>
                                        ))}
                                    </select>
                                </label>
                                <button type="button" onClick={() => changeTargetByOffset(1)}>
                                    다음 ▶
                                </button>
                            </div>

                            {targetGeneral && (
                                <div
                                    style={{
                                        display: 'grid',
                                        gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))',
                                        gap: 'var(--space-md)',
                                    }}
                                >
                                    <GameCard>
                                        <div
                                            style={{
                                                textAlign: 'center',
                                                color: 'skyblue',
                                                fontWeight: 700,
                                                padding: 'var(--space-xs) 0',
                                                background: 'var(--bg-elevated)',
                                                marginBottom: 'var(--space-sm)',
                                            }}
                                        >
                                            장수 정보
                                        </div>
                                        <GeneralBasicCard general={targetGeneral} nation={nation} />
                                    </GameCard>
                                    {LOG_SECTIONS.map(({ type, title, color }) => (
                                        <LogPanel
                                            key={type}
                                            title={title}
                                            color={color}
                                            logs={loadingLogs ? [] : logs[type]}
                                            error={logErrors[type]}
                                        />
                                    ))}
                                </div>
                            )}
                        </>
                    )}
                </>
            )}
        </Shell>
    );
}
