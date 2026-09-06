'use client';

import { useEffect, useMemo, useState } from 'react';
import Shell from '../../../components/Shell';
import PageHead from '../../../components/PageHead';
import GameCard from '../../../components/GameCard';
import GeneralName from '../../../components/game/GeneralName';
import { api } from '../../../lib/api';
import { formatNumber } from '../../../lib/format';
import { useTurnRefresh } from '../../../hooks/useTurnRefresh';
import type { MyCitiesResponse, MyCitySummary, MyCityGeneralName, MyNationDetailResponse } from '../../../types/game';

// legacy func_converter.php newColor() 충실 포팅 — 어두운 국가색 위에 올릴 헤더 텍스트 대비색.
const DARK_COLORS = new Set([
    '', '#330000', '#FF0000', '#800000', '#A0522D', '#FF6347', '#808000',
    '#008000', '#2E8B57', '#008080', '#6495ED', '#0000FF', '#000080',
    '#483D8B', '#7B68EE', '#800080', '#A9A9A9', '#000000',
]);
function newColor(color: string): string {
    return DARK_COLORS.has(color === '' ? '' : color.toUpperCase()) ? '#FFFFFF' : '#000000';
}

// formatName 동치: npc 타입색으로 이름 렌더(공석 "-"는 그대로). PHP formatName($name, $npc).
function GenName({ name, npc }: { name: string; npc: number }) {
    return <GeneralName name={name} npcType={npc} />;
}

const labelStyle: React.CSSProperties = {
    color: 'var(--text-secondary)',
    fontWeight: 600,
    textAlign: 'center',
};
const cellStyle: React.CSSProperties = { textAlign: 'center' };

// 관직자 1칸(공석 null → "-"). PHP officerName[level] (formatName / '-').
function OfficerCell({ name, npc }: { name: string | null; npc: number }) {
    if (!name) return <span style={cellStyle}>-</span>;
    return <span style={cellStyle}><GenName name={name} npc={npc} /></span>;
}

function CityCard({ city, nationColor }: { city: MyCitySummary; nationColor: string }) {
    // 인구율 = round(pop/pop_max*100, 2) (PHP). pop_max 0 방어.
    const popRate = city.populationMax > 0 ? (city.population / city.populationMax) * 100 : 0;
    return (
        <GameCard>
            {/* 헤더 — 국가색 배경 + 【 지역 | 등급 】 도시명. 수도는 도시명 cyan. */}
            <div
                style={{
                    background: nationColor,
                    color: newColor(nationColor),
                    padding: 'var(--space-xs) var(--space-sm)',
                    marginBottom: 'var(--space-sm)',
                    fontSize: 'var(--text-sm)',
                }}
            >
                【 {city.regionText} | {city.levelText} 】{' '}
                <span style={{ color: city.isCapital ? 'cyan' : 'inherit' }}>{city.name}</span>
            </div>

            {/* 행1: 주민 / 인구율 / 자금 수입 / 군량 수입 / 둔전 수입 (수입 3종 §2 BLOCKED → "-") */}
            <div
                style={{
                    display: 'grid',
                    gridTemplateColumns: 'auto 1fr auto 1fr auto 1fr auto 1fr auto 1fr',
                    gap: '2px var(--space-sm)',
                    fontSize: 'var(--text-sm)',
                    alignItems: 'center',
                    marginBottom: '2px',
                }}
            >
                <span style={labelStyle}>주민</span>
                <span style={cellStyle}>
                    {formatNumber(city.population)}/{formatNumber(city.populationMax)}
                </span>
                <span style={labelStyle}>인구율</span>
                <span style={cellStyle}>{popRate.toFixed(2)}%</span>
                <span style={labelStyle}>자금 수입</span>
                <span style={cellStyle}>{city.goldIncome == null ? '-' : formatNumber(city.goldIncome)}</span>
                <span style={labelStyle}>군량 수입</span>
                <span style={cellStyle}>{city.riceIncome == null ? '-' : formatNumber(city.riceIncome)}</span>
                <span style={labelStyle}>둔전 수입</span>
                <span style={cellStyle}>{city.farmIncome == null ? '-' : formatNumber(city.farmIncome)}</span>
            </div>

            {/* 행2: 농업 / 상업 / 치안 / 수비 / 성벽 (cur/max) */}
            <div
                style={{
                    display: 'grid',
                    gridTemplateColumns: 'auto 1fr auto 1fr auto 1fr auto 1fr auto 1fr',
                    gap: '2px var(--space-sm)',
                    fontSize: 'var(--text-sm)',
                    alignItems: 'center',
                    marginBottom: '2px',
                }}
            >
                <span style={labelStyle}>농업</span>
                <span style={cellStyle}>{city.agriculture}/{city.agricultureMax}</span>
                <span style={labelStyle}>상업</span>
                <span style={cellStyle}>{city.commerce}/{city.commerceMax}</span>
                <span style={labelStyle}>치안</span>
                <span style={cellStyle}>{city.security}/{city.securityMax}</span>
                <span style={labelStyle}>수비</span>
                <span style={cellStyle}>{city.defense}/{city.defenseMax}</span>
                <span style={labelStyle}>성벽</span>
                <span style={cellStyle}>{city.wall}/{city.wallMax}</span>
            </div>

            {/* 행3: 민심 / 시세 / 태수 / 군사 / 종사 */}
            <div
                style={{
                    display: 'grid',
                    gridTemplateColumns: 'auto 1fr auto 1fr auto 1fr auto 1fr auto 1fr',
                    gap: '2px var(--space-sm)',
                    fontSize: 'var(--text-sm)',
                    alignItems: 'center',
                    marginBottom: '2px',
                }}
            >
                <span style={labelStyle}>민심</span>
                <span style={cellStyle}>{city.trust.toFixed(1)}</span>
                <span style={labelStyle}>시세</span>
                {/* 시세 null → "- " verbatim(PHP `$city['trade'] = "- "`). */}
                <span style={cellStyle}>{city.trade == null ? '- ' : `${city.trade}%`}</span>
                <span style={labelStyle}>태수</span>
                <OfficerCell name={city.governorName} npc={city.governorNpc} />
                <span style={labelStyle}>군사</span>
                <OfficerCell name={city.strategistName} npc={city.strategistNpc} />
                <span style={labelStyle}>종사</span>
                <OfficerCell name={city.secretaryName} npc={city.secretaryNpc} />
            </div>

            {/* 행4: 장수 (도시 소재 장수 CSV; 없으면 "-") */}
            <div
                style={{
                    display: 'grid',
                    gridTemplateColumns: 'auto 1fr',
                    gap: '2px var(--space-sm)',
                    fontSize: 'var(--text-sm)',
                    alignItems: 'center',
                }}
            >
                <span style={labelStyle}>장수</span>
                <span>
                    {city.generals.length === 0
                        ? '-'
                        : city.generals.map((g: MyCityGeneralName, i: number) => (
                              <span key={i}>
                                  <GenName name={g.name} npc={g.npc} />
                                  {i < city.generals.length - 1 ? ', ' : ''}
                              </span>
                          ))}
                </span>
            </div>
        </GameCard>
    );
}

export default function MyCitiesPage() {
    const [cities, setCities] = useState<MyCitySummary[]>([]);
    const [nationColor, setNationColor] = useState('');
    const [hasNation, setHasNation] = useState(true);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    type SortKey = '이름' | '등급' | '민심' | '농업' | '상업' | '치안' | '수비' | '성벽' | '인구';
    const [sortKey, setSortKey] = useState<SortKey>('이름');
    const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc');

    const sortedCities = useMemo(() => {
        const dir = sortDir === 'asc' ? 1 : -1;
        return [...cities].sort((a, b) => {
            let cmp = 0;
            switch (sortKey) {
                case '이름':
                    cmp = a.name.localeCompare(b.name, 'ko');
                    break;
                case '등급':
                    cmp = a.level - b.level;
                    break;
                case '민심':
                    cmp = a.trust - b.trust;
                    break;
                case '농업':
                    cmp = a.agriculture - b.agriculture;
                    break;
                case '상업':
                    cmp = a.commerce - b.commerce;
                    break;
                case '치안':
                    cmp = a.security - b.security;
                    break;
                case '수비':
                    cmp = a.defense - b.defense;
                    break;
                case '성벽':
                    cmp = a.wall - b.wall;
                    break;
                case '인구':
                    cmp = a.population - b.population;
                    break;
            }
            return cmp * dir;
        });
    }, [cities, sortKey, sortDir]);

    // OPENSAM-196: background=true면 로딩 스피너를 건너뛴다(턴 갱신 시 화면이 잠깐 비는 것을 방지).
    const fetchData = async (background = false) => {
        if (!background) setLoading(true);
        setError('');
        try {
            // 카드 헤더의 국가색은 b_myCityInfo가 nation.color로 칠한다 — my-nation-detail에서 함께 읽는다.
            const [citiesRes, nationRes] = await Promise.all([
                api.myCities<MyCitiesResponse>(),
                api.myNationDetail<MyNationDetailResponse>(),
            ]);
            setCities(citiesRes.cities);
            setNationColor(nationRes.color);
            setHasNation(nationRes.hasNation && citiesRes.nationId !== 0);
        } catch {
            setError('도시 목록을 불러올 수 없습니다.');
        } finally {
            if (!background) setLoading(false);
        }
    };

    useEffect(() => {
        fetchData();
    }, []);

    // OPENSAM-196: 턴 완료 시 도시 목록을 백그라운드로 다시 읽는다.
    useTurnRefresh(() => {
        fetchData(true);
    });

    if (loading) {
        return (
            <Shell>
                <div className="page-content">
                    <PageHead title="세력 도시" />
                    <p className="text-muted">로딩 중...</p>
                </div>
            </Shell>
        );
    }

    if (error) {
        return (
            <Shell>
                <div className="page-content">
                    <PageHead title="세력 도시" />
                    <div className="error-state">
                        <p>{error}</p>
                        <button onClick={() => fetchData()}>다시 시도</button>
                    </div>
                </div>
            </Shell>
        );
    }

    // PHP: officer_level==0 → "재야입니다." exit.
    if (!hasNation) {
        return (
            <Shell>
                <div className="page-content">
                    <PageHead title="세력 도시" />
                    <p className="text-muted">재야입니다.</p>
                </div>
            </Shell>
        );
    }

    return (
        <Shell>
            <div className="page-content">
                <PageHead title="세력 도시" />
                <p className="page-subtitle">총 {cities.length}개</p>

                {cities.length > 0 && (
                    <div
                        style={{
                            display: 'flex',
                            flexWrap: 'wrap',
                            gap: '4px 6px',
                            marginBottom: 'var(--space-md)',
                            fontSize: 'var(--text-sm)',
                        }}
                    >
                        <span style={{ color: 'var(--text-secondary)', marginRight: 4 }}>정렬:</span>
                        {(['이름', '등급', '민심', '농업', '상업', '치안', '수비', '성벽', '인구'] as SortKey[]).map(
                            (key) => {
                                const active = sortKey === key;
                                return (
                                    <button
                                        key={key}
                                        onClick={() => {
                                            if (sortKey === key) {
                                                setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
                                            } else {
                                                setSortKey(key);
                                                setSortDir('asc');
                                            }
                                        }}
                                        style={{
                                            padding: '2px 8px',
                                            borderRadius: 4,
                                            border: '1px solid var(--border-color)',
                                            background: active ? 'var(--accent)' : 'transparent',
                                            color: active ? '#fff' : 'var(--text-primary)',
                                            cursor: 'pointer',
                                            fontSize: 'inherit',
                                        }}
                                    >
                                        {key}
                                        {active && (sortDir === 'asc' ? ' ▲' : ' ▼')}
                                    </button>
                                );
                            }
                        )}
                    </div>
                )}

                {cities.length === 0 ? (
                    <p className="text-muted">소속 도시가 없습니다.</p>
                ) : (
                    sortedCities.map((c) => (
                        <CityCard key={c.cityId} city={c} nationColor={nationColor} />
                    ))
                )}
            </div>
        </Shell>
    );
}
