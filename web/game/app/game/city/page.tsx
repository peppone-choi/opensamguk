'use client';

import { Suspense, useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import Gauge from '../../../components/game/Gauge';
import SammoBar from '../../../components/game/SammoBar';
import { api } from '../../../lib/api';
import type { CityDetailResponse, CityGeneralRow } from '../../../types/game';
import {
    formatInjury,
    getNPCColor,
} from '../../../lib/utilGame';
import { useServerGameUrl } from '../../../lib/serverGameUrl';

// 도시 상세 본문(b_currentCity.php 패러티) — 쿼리 ?id=<도시번호>로 특정 도시를 조회한다(MapViewer 도시 클릭 진입).
// id가 없거나 0이면 현재 장수 소재 도시(서버가 0을 현재 도시로 해석).
//
// 섹션(레거시 b_currentCity.php 순서):
//   1) 도시선택 셀렉터(citySelector) — 재야=현재도시만 / 관직자=아국+주둔타국+첩보. 선택 시 ?id= 라우팅.
//   2) 헤더【지역|등급】도시명 + 갱신시각(lastExecute) + 공백/지배/보급 배지.
//   3) 게이지(주민/민심/농업/상업/치안/수비/성벽/시세/주둔수) — visible=false면 마스킹 안내.
//   4) 관직자행(태수/군사/종사) + 군사 집계행(적군/병장총/90·60병장/수비○) + 장수명 CSV.
//   5) 장수 상세표(showDetailedInfo=true일 때만; false면 "알 수 없음").
//
// 첩보(fog) 패러티: visible=false면 서버가 내정/방어 수치를 null로 마스킹 → 게이지 대신 안내(수치 날조 없음).
// showDetailedInfo=false면 장수표/장수명을 숨긴다(visible과 별개 게이트).
// 통무지 부상 색·병종 마스킹("?")·NPC색 등 표시 규칙은 cityGeneral.php를 충실 포팅.

// formatWounded(leadership/strength/intel, injury) — 부상 시 색 span. 레거시 formatWounded는 injury>0이면
// formatInjury 색으로 능력치를 물들인다(값은 그대로). injury==0이면 plain.
function StatCell({ value, injury }: { value: number; injury: number }) {
    if (injury > 0) {
        const woundedValue = Math.trunc(value * (100 - injury) / 100);
        return <span style={{ color: 'red' }}>{woundedValue}</span>;
    }
    return <>{value}</>;
}

// 장수명 셀 — NPC색(getNPCColor) 적용. 비-NPC는 기본색.
function NameSpan({ name, npc }: { name: string; npc: number }) {
    const color = getNPCColor(npc);
    return <span style={color ? { color } : undefined}>{name}</span>;
}

// 도시선택 relation → 한글(b_currentCity.php: 공백지/본국/타국).
const RELATION_TEXT: Record<number, string> = { 0: '공백지', 1: '본국', 2: '타국' };

function CityDetail() {
    const searchParams = useSearchParams();
    const router = useRouter();
    const cityBaseHref = useServerGameUrl('city');
    const idParam = searchParams.get('id');
    const cityId = idParam != null && idParam !== '' ? Number(idParam) : 0;

    const [city, setCity] = useState<CityDetailResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    const fetchData = async () => {
        setLoading(true);
        setError('');
        try {
            const res = await api.city<CityDetailResponse>(Number.isFinite(cityId) ? cityId : 0);
            setCity(res);
        } catch {
            setError('도시 정보를 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchData();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [cityId]);

    if (loading) {
        return (
            <div className="page-content">
                <h1>도시 정보</h1>
                <p className="text-muted">로딩 중...</p>
            </div>
        );
    }

    if (error) {
        return (
            <div className="page-content">
                <h1>도시 정보</h1>
                <div className="error-state">
                    <p>{error}</p>
                    <button onClick={fetchData}>다시 시도</button>
                </div>
            </div>
        );
    }

    if (!city) {
        return (
            <div className="page-content">
                <h1>도시 정보</h1>
                <p className="text-muted">도시 정보가 없습니다.</p>
            </div>
        );
    }

    const neutral = city.nationId === 0;
    // 헤더 라벨 — 레거시【지역 | 등급】도시명.
    const header = `【${city.regionName} | ${city.levelName}】 ${city.name}`;
    const m = city.military;

    return (
        <div className="page-content">
            <h1>도시 정보</h1>

            {/* ── 1) 도시선택 셀렉터(b_currentCity.php:73-159) ──────────────────────────── */}
            {city.citySelector.length > 0 && (
                <div className="control-bar" style={{ display: 'flex', gap: 'var(--space-sm)', alignItems: 'center', marginBottom: 'var(--space-md)', flexWrap: 'wrap' }}>
                    <span style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)' }}>도시선택:</span>
                    <select
                        value={city.id}
                        onChange={(e) => router.push(`${cityBaseHref}?id=${encodeURIComponent(e.target.value)}`)}
                        style={{ minWidth: '20rem' }}
                    >
                        {city.citySelector.map((o) => (
                            <option key={o.cityId} value={o.cityId}>
                                {`【${o.cityName}】${RELATION_TEXT[o.relation] ?? ''}`}
                            </option>
                        ))}
                    </select>
                    <span style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)' }}>명령 화면에서 도시를 클릭하셔도 됩니다.</span>
                </div>
            )}

            <GameCard className="city-detail">
                <div className="card-header" style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)', flexWrap: 'wrap' }}>
                    <h2 style={{ margin: 0 }}>{header}</h2>
                    <StatusBadge variant={neutral ? 'muted' : 'jade'}>{neutral ? '공 백 지' : '지배 도시'}</StatusBadge>
                    {city.supplyState === 0 && !neutral && <StatusBadge variant="crimson">보급 끊김</StatusBadge>}
                    {/* 갱신시각(lastExecute) — config["turntime"] 미배선 시 null → 미표시(날조 없음). */}
                    {city.lastExecute && (
                        <span style={{ marginLeft: 'auto', fontSize: 'var(--text-xs)', color: 'var(--text-muted)' }}>갱신 {city.lastExecute}</span>
                    )}
                </div>

                {city.visible ? (
                    <>
                        <div className="gauge-metrics">
                            <Gauge label="주민" now={city.population ?? 0} max={city.populationMax ?? 0} />
                            <Gauge label="민심" now={city.trust ?? 0} max={100} barOnly />
                            <Gauge label="농업" now={city.agriculture ?? 0} max={city.agricultureMax ?? 0} />
                            <Gauge label="상업" now={city.commerce ?? 0} max={city.commerceMax ?? 0} />
                            <Gauge label="치안" now={city.security ?? 0} max={city.securityMax ?? 0} />
                            <Gauge label="수비" now={city.defense ?? 0} max={city.defenseMax ?? 0} />
                            <Gauge label="성벽" now={city.wall ?? 0} max={city.wallMax ?? 0} />
                            {/* 시세(trade %) — 레거시 (trade-95)*10 클램프. trade==null(상인 없음)이면 텍스트만. */}
                            <div className="mcd-metric gauge-metric">
                                <div className="mcd-metric-head">시세</div>
                                <div className="mcd-metric-body">
                                    {city.trade != null && (
                                        <SammoBar percent={Math.min(100, Math.max(0, (city.trade - 95) * 10))} height={7} />
                                    )}
                                    <div className="mcd-metric-text">{city.trade != null ? `${city.trade}%` : '상인 없음'}</div>
                                </div>
                            </div>
                            <div className="mcd-metric gauge-metric">
                                <div className="mcd-metric-head">주둔 장수</div>
                                <div className="mcd-metric-body">
                                    <div className="mcd-metric-text">{city.officers ?? 0}명</div>
                                </div>
                            </div>
                        </div>

                        {/* ── 4a) 관직자행(태수/군사/종사) — b_currentCity.php:475-480 ───────────────── */}
                        <table className="kv-table" style={{ marginTop: 'var(--space-md)', width: '100%' }}>
                            <tbody>
                                <tr>
                                    <th>태수</th>
                                    <td><NameSpan name={city.officerGovernor.name} npc={city.officerGovernor.npc} /></td>
                                    <th>군사</th>
                                    <td><NameSpan name={city.officerStrategist.name} npc={city.officerStrategist.npc} /></td>
                                    <th>종사</th>
                                    <td><NameSpan name={city.officerSecretary.name} npc={city.officerSecretary.npc} /></td>
                                </tr>
                                <tr>
                                    <th>도시명</th>
                                    <td>{city.cityName}</td>
                                    {/* 적군 = 병력/무장장수수(장수수). number_format = toLocaleString. */}
                                    <th>적군</th>
                                    <td>{`${m.enemyCrew.toLocaleString()}/${m.enemyArmedCnt.toLocaleString()}(${m.enemyCnt.toLocaleString()})`}</td>
                                    <th>병장(총)</th>
                                    <td>{`${m.crewTotal.toLocaleString()}/${m.armedGenTotal.toLocaleString()}(${m.genTotal.toLocaleString()})`}</td>
                                </tr>
                                <tr>
                                    <th>90병장</th>
                                    <td>{`${m.crew90.toLocaleString()}/${m.gen90.toLocaleString()}`}</td>
                                    <th>60병장</th>
                                    <td>{`${m.crew60.toLocaleString()}/${m.gen60.toLocaleString()}`}</td>
                                    <th>수비○</th>
                                    {/* 수비○ = min(훈련,사기)>=defence_train 집계. defence_train 원천 미배선(BE 0 하드코딩)
                                        → 무장 장수 전원이 집계돼 과집계 위조. 배선 전 '-' 마스킹(수치 날조 없음). */}
                                    <td>-</td>
                                </tr>
                                <tr>
                                    <th>장수</th>
                                    <td colSpan={5}>
                                        {city.showDetailedInfo
                                            ? city.generalNames.map((g, i) => (
                                                <span key={`${g.name}-${i}`}>
                                                    {i > 0 && ', '}
                                                    <NameSpan name={g.name} npc={g.npc} />
                                                </span>
                                            ))
                                            : <span style={{ color: 'gray' }}>알 수 없음</span>}
                                    </td>
                                </tr>
                            </tbody>
                        </table>
                    </>
                ) : (
                    <div className="mcd-empty" style={{ padding: 'var(--space-lg)', textAlign: 'center', color: 'var(--text-muted)' }}>
                        {neutral ? '공백지입니다.' : '다른 세력의 도시입니다.'} 첩보가 없어 내정 정보를 볼 수 없습니다.
                    </div>
                )}
            </GameCard>

            {/* ── 5) 장수 상세표(b_currentCity.php:503-529 + cityGeneral.php) ───────────────── */}
            {city.showDetailedInfo && city.generals.length > 0 && (
                <GameCard style={{ marginTop: 'var(--space-md)', overflowX: 'auto' }}>
                    <table className="data-table" style={{ width: '100%', textAlign: 'center' }}>
                        <thead>
                            <tr>
                                <th>얼 굴</th>
                                <th>이 름</th>
                                <th>통솔</th>
                                <th>무력</th>
                                <th>지력</th>
                                <th>정치</th>
                                <th>매력</th>
                                <th>관 직</th>
                                <th>守</th>
                                <th>병 종</th>
                                <th>병 사</th>
                                <th>훈련</th>
                                <th>사기</th>
                                <th style={{ textAlign: 'left' }}>명 령</th>
                            </tr>
                        </thead>
                        <tbody>
                            {city.generals.map((g) => <GeneralRow key={g.no} g={g} />)}
                        </tbody>
                    </table>
                </GameCard>
            )}
        </div>
    );
}

// 장수 상세표 행(cityGeneral.php). ourGeneral이면 병종/병사/훈련/사기/守(defenceTrain) 노출, 타국은 "?",
// 재야는 "재 야". defenceTrain은 BE 원천 미배선(GeneralReadEntity defence_train 컬럼 부재) →
// 守는 '-' 마스킹(P0-14 — 전원 '△' 위조 표기 제거). 원천 배선 후 formatDefenceTrain 복원.
function GeneralRow({ g }: { g: CityGeneralRow }) {
    return (
        <tr>
            {/* 얼굴 — 초상 파일명(CDN 미배선, 다른 read 페이지 동일 컨벤션으로 텍스트). */}
            <td><span style={{ color: 'var(--text-muted)', fontSize: 'var(--text-xs)' }}>{g.iconPath || '-'}</span></td>
            <td><NameSpan name={g.name} npc={g.npc} /></td>
            {/* 통솔 — 통솔 + leadershipBonus(있으면 +N). 부상 시 색. */}
            <td>
                <StatCell value={g.leadership} injury={g.wounded} />
                {g.leadershipBonus > 0 && <span style={{ color: 'cyan' }}>+{g.leadershipBonus}</span>}
            </td>
            <td><StatCell value={g.strength} injury={g.wounded} /></td>
            <td><StatCell value={g.intel} injury={g.wounded} /></td>
            {/* 정치·매력 — RTK 표준 순서(통무지정매). 부상색·보너스는 통무지 전용이라 미적용, plain 값(필드 optional → '-' fallback). */}
            <td>{g.politics ?? '-'}</td>
            <td>{g.charm ?? '-'}</td>
            <td>{g.officerLevelText}</td>
            {g.ourGeneral ? (
                <>
                    {/* 守 = 수비 훈련도 기호(formatDefenceTrain). defence_train 원천 미배선이라 전원 '△'로
                        위조 표기되던 것을 '-' 마스킹(수치 날조 없음). 원천 배선 시 formatDefenceTrain 복원. */}
                    <td>-</td>
                    <td>{g.crewTypeName}</td>
                    <td>{g.crew}</td>
                    <td>{g.train}</td>
                    <td>{g.atmos}</td>
                    {/* 명령 — turnText(예약명령 brief). BE 미emit(general_turn brief 미배선) → NPC 표기/공란. */}
                    <td style={{ textAlign: 'left' }}>{g.isNPC ? 'NPC 장수' : ''}</td>
                </>
            ) : (
                <>
                    <td>?</td>
                    <td>?</td>
                    <td>{g.crew >= 0 ? g.crew : '?'}</td>
                    <td>?</td>
                    <td>?</td>
                    <td style={{ textAlign: 'left' }}>{g.nation !== 0 ? `【${g.nationName}】 장수` : '재 야'}</td>
                </>
            )}
        </tr>
    );
}

export default function CityPage() {
    // useSearchParams는 Suspense 경계가 필요(app router CSR bailout).
    return (
        <Shell>
            <Suspense fallback={<div className="page-content"><h1>도시 정보</h1><p className="text-muted">로딩 중...</p></div>}>
                <CityDetail />
            </Suspense>
        </Shell>
    );
}
